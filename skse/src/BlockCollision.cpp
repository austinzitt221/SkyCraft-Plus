#include "Game.h"
#include "CollisionNif.h"

#include <SimpleIni.h>

#include <chrono>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <map>
#include <array>
#include <unordered_set>

// Real collision for Minecraft's blocks, for everything in Skyrim: the player, NPCs, arrows and spells, over the whole area Minecraft has
// sent, not just the few blocks round the player that BlockCrates.cpp can keep crates on.
//
// Skyrim can only make Havok bodies from a model it loads, so this writes the model. For every section (16 x 16 x 16 blocks) Minecraft
// sent, the solid blocks are merged into as few boxes as will do (a whole floor is one box), written to a small .nif that is nothing but
// collision (CollisionNif.h) under Data/Meshes/SkyCraft/BlockCollision, and put in the world as an invisible static reference of a
// form made for it. Skyrim's loader does the rest, exactly as for a rock or a wall: the collision is in the cell's Havok world with the
// 3D, and goes with the reference. When a section changes (a block placed or broken) a new model replaces the old one, and the old one
// goes only once the new one has loaded, so there is no moment without a floor.
//
// If anything about this doesn't work on a given install (the files can't be written, the form can't be made, or the model loads without
// any collision in it), it says so in SkyCraft.log and gives up, and BlockCrates.cpp takes over again.
namespace skycraft
{
	namespace
	{
		struct CollisionCfg
		{
			bool          on{ true };
			int           radius{ 4 };      // sections (16 blocks) round the player, sideways
			int           maxMeshes{ 220 };
			float         overlap{ 0.02f }; // how much wider than a block (a fraction) so boxes that meet leave no seam to catch a foot on
			std::uint32_t material{ collisionnif::kMaterialStone };
			bool          cratesAlso{ false };  // keep the crates round the player even once the models are known to work
			bool          library{ true };    // non-cube blocks whose shape is in the pre-made shape library are placed as references to its models
			std::string   catalog;            // path of shape_catalog.json (empty: look where the Minecraft that comes with SkyCraft writes it)
			int           maxShapeRefs{ 600 };  // most library references at once
			bool          experimental{ true };  // load the collision models (off in SkyCraft.ini to use crates only, if Skyrim crashes on them)
		};

		const CollisionCfg& Cfg()
		{
			static const CollisionCfg settings = [] {
				CSimpleIniA ini;
				ini.SetUnicode();
				ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
				CollisionCfg s;
				s.on = ini.GetBoolValue("Collision", "bBlockMesh", true);
				s.radius = static_cast<int>(std::clamp<long>(ini.GetLongValue("Collision", "iRadius", 4), 1, 8));
				s.maxMeshes = static_cast<int>(std::clamp<long>(ini.GetLongValue("Collision", "iMaxMeshes", 220), 20, 1000));
				s.overlap = static_cast<float>(std::clamp(ini.GetDoubleValue("Collision", "fOverlap", 0.02), 0.0, 0.2));
				s.material = static_cast<std::uint32_t>(std::strtoul(ini.GetValue("Collision", "sMaterial", "3741512247"), nullptr, 0));
				s.cratesAlso = ini.GetBoolValue("Collision", "bCratesWithMesh", false);
				s.library = ini.GetBoolValue("Collision", "bShapeLibrary", true);
				s.catalog = ini.GetValue("Collision", "sShapeCatalog", "");
				s.maxShapeRefs = static_cast<int>(std::clamp<long>(ini.GetLongValue("Collision", "iMaxShapeRefs", 600), 0, 5000));
				s.experimental = ini.GetBoolValue("Collision", "bBlockMeshExperimental", true);
				return s;
			}();
			return settings;
		}

		const std::filesystem::path kDir = "Data/Meshes/SkyCraft/BlockCollision";
		constexpr const char*       kModelDir = "SkyCraft\\BlockCollision\\";  // as a model path is written (relative to Data\Meshes)

		// Skyrim loads only model files that existed when it started (a file made while it runs opens fine but never gets a 3D). So
		// PrepareFiles() makes this many placeholder files at plugin load; a section model is written over the next unused one just before its
		// reference is made. A file is used once: Skyrim keeps a model it has loaded, so a changed section takes a fresh file.
		constexpr int kSlots = 3000;
		int           slotNext = 0;

		bool IsFixedFile(const std::string& a_file)
		{
			return a_file.rfind("slot", 0) == 0 || a_file.rfind("pre_", 0) == 0;
		}

		void RemoveFile(const std::string& a_file)
		{
			if (!a_file.empty() && !IsFixedFile(a_file)) {
				std::error_code ec;
				std::filesystem::remove(kDir / a_file, ec);
			}
		}

		std::string TakeSlot()
		{
			return slotNext < kSlots ? fmt::format("slot{:04d}.nif", slotNext++) : std::string();
		}

		bool WriteFile(const std::string& a_file, const std::vector<std::uint8_t>& a_bytes)
		{
			std::ofstream out(kDir / a_file, std::ios::binary | std::ios::trunc);
			out.write(reinterpret_cast<const char*>(a_bytes.data()), static_cast<std::streamsize>(a_bytes.size()));
			return out.good();
		}

		struct Mesh
		{
			std::int32_t  sx{ 0 }, sy{ 0 }, sz{ 0 };
			std::uint32_t stamp{ 0 };  // the section's stamp (NpcBlocks) it was built from
			RE::FormID    refId{ 0 };
			std::string   file;        // its file name
			std::uint32_t boxes{ 0 };
			bool          pending{ false };  // its reference is made, and not yet known to hold collision in Skyrim's Havok world
			bool          forced{ false };   // Skyrim was asked to load its 3D
			bool          atPlayer{ false }; // its reference was made at the player and moved (else made where it goes)
			float         waited{ 0.0f };
			float         settled{ 0.0f };   // seconds since its 3D loaded
			RE::FormID    oldRefId{ 0 };     // the mesh this one replaces: put away when this one has loaded
			std::string   oldFile;
		};

		std::unordered_map<std::uint64_t, Mesh> meshes;
		bool                                    failed = false;
		float                                   sincePass = 1.0f;
		std::uint32_t                           serial = 0;
		std::uint32_t                           inWorldCount = 0;  // models whose collision is in the Havok world: they work
		std::uint32_t                           failures = 0;
		std::uint64_t                           cellKey = 0;  // which cell / world the meshes are in

		// Tells a model Skyrim rejects from a form it can't load models for: the first time a section model is made, a vanilla model (the
		// crate's own) is put in the world through the very same kind of form, and the log says which of the two got a 3D.
		struct Probe
		{
			RE::FormID refId{ 0 };
			float      waited{ 0.0f };
			bool       done{ false };
		};
		Probe probe;

		// Tells which details of the .nif Skyrim is particular about: the first time a section model is made, tiny models that differ in
		// those details are put in the world side by side, and the log says which of them Skyrim loaded. The first that loads is then used
		// for every section model.
		struct Variant
		{
			std::string            label;
			collisionnif::Options  opts;
			RE::FormID             refId{ 0 };
			std::string            file;
			float                  waited{ 0.0f };
			bool                   forced{ false };
			bool                   launched{ false };  // its reference is made (they are made one at a time, each after the one before has loaded)
			bool                   done{ false };
			bool                   ok{ false };
			bool                   usable{ true };  // a model of the real kind (not a smaller test file or a copy of someone else's)
			std::filesystem::path  copyFrom;        // a loose .nif found in Data\\Meshes, copied to see if Skyrim loads any loose file that is new
		};
		std::vector<Variant>  variants;
		bool                  variantsStarted = false;
		RE::NiPoint3          variantPos;
		bool                  variantsDone = false;
		collisionnif::Options activeOpts;

		std::uint64_t Key(int a_x, int a_y, int a_z)
		{
			return (std::uint64_t(std::uint32_t(a_x) & 0x3FFFFF) << 42) | (std::uint64_t(std::uint32_t(a_z) & 0x3FFFFF) << 20) | (std::uint64_t(std::uint32_t(a_y) & 0xFFFFF));
		}

		template <class R>
		void DeleteRef(R* a_ref)
		{
			if (!a_ref) {
				return;
			}
			// Never delete an actor: a stored ID can outlive its reference (Skyrim frees our references when a cell unloads or a save loads,
			// then reuses the ID), and deleting what it now names, a stand-in actor, crashes the engine's movement code.
			if constexpr (requires { a_ref->template As<RE::Actor>(); }) {
				if (a_ref->template As<RE::Actor>()) {
					return;
				}
			}
			if constexpr (requires { a_ref->Disable(); }) {
				a_ref->Disable();
			}
			if constexpr (requires { a_ref->SetDelete(true); }) {
				a_ref->SetDelete(true);
			}
		}

		void EndProbe()
		{
			if (probe.refId != 0) {
				DeleteRef(RE::TESForm::LookupByID<RE::TESObjectREFR>(probe.refId));
				probe.refId = 0;
			}
		}

		std::string AbsolutePath(const std::filesystem::path& a_path)
		{
			std::error_code ec;
			const auto      full = std::filesystem::absolute(a_path, ec);
			return (ec ? a_path : full).string();
		}

		void EndVariants()
		{
			for (auto& v : variants) {
				if (v.refId != 0) {
					DeleteRef(RE::TESForm::LookupByID<RE::TESObjectREFR>(v.refId));
					v.refId = 0;
				}
				RemoveFile(v.file);
				v.file.clear();
			}
			variants.clear();
			if (!variantsDone) {
				variantsStarted = false;  // interrupted (another cell): starts again
			}
		}

		// ---- the shape library: Data/Meshes/SkyCraft/BlockLibrary/shp_<key>.nif, one model per distinct block collision shape (the origin is the
		// block's low corner). Written at plugin load, so Skyrim has them from its start; never changed while it runs. ----
		const std::filesystem::path kLibDir = "Data/Meshes/SkyCraft/BlockLibrary";
		constexpr const char*       kLibModelDir = "SkyCraft\\BlockLibrary\\";
		std::unordered_set<std::string>                           libraryKeys;
		std::unordered_map<std::string, RE::TESObjectSTAT*>       libForms;
		std::unordered_map<RE::FormID, std::vector<RE::FormID>>   libOf;  // a section's reference -> the library references that go with it
		bool                                                      libBroken = false;
		bool                                                      libWasOn = false;

		void PutAway(RE::FormID a_refId, const std::string& a_file)
		{
			if (auto lib = libOf.find(a_refId); lib != libOf.end()) {
				for (const auto id : lib->second) {
					DeleteRef(RE::TESForm::LookupByID<RE::TESObjectREFR>(id));
				}
				libOf.erase(lib);
			}
			if (a_refId != 0) {
				DeleteRef(RE::TESForm::LookupByID<RE::TESObjectREFR>(a_refId));
			}
			RemoveFile(a_file);
		}

		// The solid blocks of a section as the boxes of a model whose origin is the section's min corner (Skyrim units, Skyrim's axes).
		// Whole cubes are merged (greedily: along x, then z, then y); other shapes (slabs, stairs, fences...) keep their own boxes.
		struct LibBlock
		{
			int         cell[3];  // the block, Minecraft coordinates
			std::string key;
		};

		void BuildBoxes(int a_sx, int a_sy, int a_sz, float a_overlap, std::vector<collisionnif::Box>& a_out, std::vector<LibBlock>* a_lib = nullptr, int a_libCap = 0)
		{
			const int x0 = a_sx * 16, y0 = a_sy * 16, z0 = a_sz * 16;
			std::vector<NpcBlocks::BlockBox> all;
			NpcBlocks::GatherBoxes(x0, x0 + 15, y0, y0 + 15, z0, z0 + 15, all);

			std::vector<std::uint8_t>        full(4096, 0);
			std::vector<NpcBlocks::BlockBox> others;
			constexpr float                  eps = 1e-3f;
			for (const auto& b : all) {
				bool wholeCube = true;
				int  cell[3] = { 0, 0, 0 };
				for (int k = 0; k < 3; ++k) {
					const float r = std::round(b.lo[k]);
					if (std::fabs(b.lo[k] - r) > eps || std::fabs(b.hi[k] - r - 1.0f) > eps) {
						wholeCube = false;
					}
					cell[k] = static_cast<int>(r);
				}
				const int lx = cell[0] - x0, ly = cell[1] - y0, lz = cell[2] - z0;
				if (wholeCube && lx >= 0 && lx < 16 && ly >= 0 && ly < 16 && lz >= 0 && lz < 16) {
					full[lx + 16 * lz + 256 * ly] = 1;
				} else {
					others.push_back(b);
				}
			}

			const double U = proto::kUnitsPerBlock;
			auto         emit = [&](const float a_lo[3], const float a_hi[3], float a_pad) {
                // (Minecraft: x east, y up, z south.) Merged whole cubes are a little wider on the sides only (the top and underside left exactly at
                // the block's top) so neighbours leave no seam. Every other shape is Minecraft's exact box: a door stays 3/16 thick.
				const float lo[3] = { a_lo[0] - a_pad, a_lo[1], a_lo[2] - a_pad };
				const float hi[3] = { a_hi[0] + a_pad, a_hi[1], a_hi[2] + a_pad };
				collisionnif::Box b;
				b.lo[0] = float((lo[0] - x0) * U);
				b.hi[0] = float((hi[0] - x0) * U);
				b.lo[1] = float(-(hi[2] - z0) * U);
				b.hi[1] = float(-(lo[2] - z0) * U);
				b.lo[2] = float((lo[1] - y0) * U);
				b.hi[2] = float((hi[1] - y0) * U);
				a_out.push_back(b);
			};

			std::vector<std::uint8_t> used(4096, 0);
			auto                      at = [](int x, int y, int z) { return x + 16 * z + 256 * y; };
			auto                      isFree = [&](int x, int y, int z) { return full[at(x, y, z)] && !used[at(x, y, z)]; };
			for (int y = 0; y < 16; ++y) {
				for (int z = 0; z < 16; ++z) {
					for (int x = 0; x < 16; ++x) {
						if (!isFree(x, y, z)) {
							continue;
						}
						int x1 = x;
						while (x1 + 1 < 16 && isFree(x1 + 1, y, z)) {
							++x1;
						}
						int z1 = z;
						for (; z1 + 1 < 16; ++z1) {
							bool row = true;
							for (int xx = x; xx <= x1 && row; ++xx) {
								row = isFree(xx, y, z1 + 1);
							}
							if (!row) {
								break;
							}
						}
						int y1 = y;
						for (; y1 + 1 < 16; ++y1) {
							bool layer = true;
							for (int zz = z; zz <= z1 && layer; ++zz) {
								for (int xx = x; xx <= x1 && layer; ++xx) {
									layer = isFree(xx, y1 + 1, zz);
								}
							}
							if (!layer) {
								break;
							}
						}
						for (int yy = y; yy <= y1; ++yy) {
							for (int zz = z; zz <= z1; ++zz) {
								for (int xx = x; xx <= x1; ++xx) {
									used[at(xx, yy, zz)] = 1;
								}
							}
						}
						const float lo[3] = { float(x0 + x), float(y0 + y), float(z0 + z) };
						const float hi[3] = { float(x0 + x1 + 1), float(y0 + y1 + 1), float(z0 + z1 + 1) };
						emit(lo, hi, a_overlap);
					}
				}
			}
			// The other shapes, block by block: a block whose shape is in the library becomes a reference to its model (a_lib), the rest keep
			// their boxes in the section's model.
			std::map<std::array<int, 3>, std::vector<const NpcBlocks::BlockBox*>> byBlock;
			for (const auto& b : others) {
				byBlock[{ int(std::floor(b.lo[0] + 1e-3f)), int(std::floor(b.lo[1] + 1e-3f)), int(std::floor(b.lo[2] + 1e-3f)) }].push_back(&b);
			}
			for (const auto& [cell, list] : byBlock) {
				if (a_lib && static_cast<int>(a_lib->size()) < a_libCap) {
					std::vector<ShapeBox16> q;
					for (const auto* b : list) {
						ShapeBox16 box{};
						for (int k = 0; k < 3; ++k) {
							box.v[k] = std::clamp(int(std::lround((b->lo[k] - cell[k]) * 16.0f)), 0, 32);
							box.v[k + 3] = std::clamp(int(std::lround((b->hi[k] - cell[k]) * 16.0f)), 0, 32);
						}
						if (box.v[3] > box.v[0] && box.v[4] > box.v[1] && box.v[5] > box.v[2]) {
							q.push_back(box);
						}
					}
					auto key = ShapeKey(q);
					if (!q.empty() && libraryKeys.contains(key)) {
						a_lib->push_back({ { cell[0], cell[1], cell[2] }, std::move(key) });
						continue;
					}
				}
				for (const auto* b : list) {
					emit(b->lo, b->hi, 0.0f);
				}
			}
		}

		// A form of our own to be the base of one section's reference: a static whose model is that section's file.
		RE::TESObjectSTAT* MakeStatic(const std::string& a_modelPath, float a_sizeUnits)
		{
			auto* form = RE::IFormFactory::Create<RE::TESObjectSTAT>();
			if (!form) {
				return nullptr;
			}
			if (form->GetFormID() == 0) {
				form->SetFormID(0, true);
			}
			if (form->GetFormID() == 0) {
				return nullptr;
			}
			static_cast<RE::TESModel*>(form)->SetModel(a_modelPath.c_str());
			const auto s = static_cast<std::int16_t>(std::clamp(a_sizeUnits + 40.0f, 1.0f, 32000.0f));
			form->boundData.boundMin.x = -20;
			form->boundData.boundMin.y = static_cast<std::int16_t>(-s);
			form->boundData.boundMin.z = -20;
			form->boundData.boundMax.x = s;
			form->boundData.boundMax.y = 20;
			form->boundData.boundMax.z = s;
			return form;
		}

		// The base form of a library model (one per shape, shared by every block of it).
		RE::TESObjectSTAT* LibForm(const std::string& a_key)
		{
			if (auto it = libForms.find(a_key); it != libForms.end()) {
				return it->second;
			}
			auto* form = MakeStatic(std::string(kLibModelDir) + "shp_" + a_key + ".nif", float(2 * proto::kUnitsPerBlock));
			libForms[a_key] = form;
			return form;
		}

		bool DefaultOptions(const collisionnif::Options& a_o)
		{
			const collisionnif::Options d;
			return a_o.plainNode == d.plainNode && a_o.rigidBodyT == d.rigidBodyT && a_o.collisionFlags == d.collisionFlags && a_o.bsxFlags == d.bsxFlags &&
			       a_o.preambleExtra == d.preambleExtra && a_o.tailExtra == d.tailExtra && a_o.level == d.level;
		}

		std::uint64_t CellKeyOf(RE::PlayerCharacter* a_player)
		{
			auto* cell = a_player->GetParentCell();
			if (!cell) {
				return 0;
			}
			if (cell->IsInteriorCell()) {
				return (std::uint64_t(1) << 40) | cell->GetFormID();
			}
			auto* world = a_player->GetWorldspace();
			return world ? world->GetFormID() : 0;
		}

		// Is this mesh's reference still somewhere it can hold collision (its cell is up)?
		bool Alive(const Mesh& a_mesh)
		{
			auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(a_mesh.refId);
			if (!ref || ref->IsDeleted() || ref->IsDisabled()) {
				return false;
			}
			auto* cell = ref->GetParentCell();
			return cell && cell->IsAttached();
		}

		// What a loaded model has: a collision object, and a body of it that is in a Havok world.
		void CollisionState(RE::NiAVObject* a_root, bool& a_hasObject, bool& a_inWorld)
		{
			a_hasObject = false;
			a_inWorld = false;
			auto* object = a_root ? a_root->GetCollisionObject() : nullptr;
			if (!object) {
				return;
			}
			a_hasObject = true;
			auto* body = skyrim_cast<RE::bhkRigidBody*>(object->body.get());
			auto* rigid = body ? body->GetRigidBody() : nullptr;
			a_inWorld = rigid && rigid->world;
		}

		void DropMeshes()
		{
			if (meshes.empty()) {
				return;
			}
			for (const auto& entry : meshes) {
				PutAway(entry.second.refId, entry.second.file);
				PutAway(entry.second.oldRefId, entry.second.oldFile);
			}
			logger::info("Block collision: put away {} section meshes", meshes.size());
			meshes.clear();
			sincePass = 1.0f;
		}

		void GiveUp(const char* a_why)
		{
			failed = true;
			logger::warn("Block collision: {}; giving it up, block crates take over", a_why);
			BlockCollision::Release();
		}
	}

	namespace
	{
		bool variantsSafe = false;  // only models with no collision in them are tried (a bad collision model can crash Skyrim's loader)

		// ---- vanilla models, copied out of Skyrim's own files, to see how its collision is laid out ----

		// Reads exactly a_count bytes through Skyrim's stream (whatever the stream class calls its read), appending them to a_out.
		template <class S>
		bool ReadBytes(S& a_stream, std::vector<std::uint8_t>& a_out, std::size_t a_count)
		{
			if (a_count > (64u << 20)) {
				return false;
			}
			const auto at = a_out.size();
			a_out.resize(at + a_count);
			if (a_count == 0) {
				return true;
			}
			// (NiBinaryStream::read is a template over the element type: with char, a count is a number of bytes)
			if constexpr (requires { a_stream.read(static_cast<char*>(nullptr), std::uint32_t(0)); }) {
				a_stream.read(reinterpret_cast<char*>(a_out.data() + at), static_cast<std::uint32_t>(a_count));
				return a_stream.good();
			} else {
				return false;
			}
		}

		// A whole .nif from Skyrim's files (loose or in an archive): the header says how long everything is, so it is read piece by piece.
		bool ReadNif(const std::string& a_path, std::vector<std::uint8_t>& a_out)
		{
			RE::BSResourceNiBinaryStream stream(a_path);
			if (!stream.good()) {
				return false;
			}
			a_out.clear();
			auto u32at = [&](std::size_t a_at) {
				std::uint32_t v = 0;
				std::memcpy(&v, a_out.data() + a_at, 4);
				return v;
			};
			// the first line
			for (int i = 0; i < 80; ++i) {
				if (!ReadBytes(stream, a_out, 1)) {
					return false;
				}
				if (a_out.back() == '\n') {
					break;
				}
				if (i == 79) {
					return false;
				}
			}
			std::size_t p = a_out.size();
			if (!ReadBytes(stream, a_out, 4 + 1 + 4 + 4 + 4)) {  // version, endian, user version, blocks, bethesda version
				return false;
			}
			const std::uint32_t blocks = u32at(p + 9);
			for (int i = 0; i < 3; ++i) {  // author, process script, export script (short strings)
				if (!ReadBytes(stream, a_out, 1) || !ReadBytes(stream, a_out, a_out.back())) {
					return false;
				}
			}
			p = a_out.size();
			if (blocks > 100000 || !ReadBytes(stream, a_out, 2)) {
				return false;
			}
			std::uint16_t types = 0;
			std::memcpy(&types, a_out.data() + p, 2);
			for (std::uint16_t i = 0; i < types; ++i) {
				p = a_out.size();
				if (!ReadBytes(stream, a_out, 4)) {
					return false;
				}
				if (!ReadBytes(stream, a_out, u32at(p))) {
					return false;
				}
			}
			if (!ReadBytes(stream, a_out, std::size_t(blocks) * 2)) {  // block type of each block
				return false;
			}
			p = a_out.size();
			if (!ReadBytes(stream, a_out, std::size_t(blocks) * 4)) {  // size of each block
				return false;
			}
			std::uint64_t total = 0;
			for (std::uint32_t i = 0; i < blocks; ++i) {
				total += u32at(p + std::size_t(i) * 4);
			}
			p = a_out.size();
			if (!ReadBytes(stream, a_out, 8)) {  // number of strings, longest
				return false;
			}
			const std::uint32_t strings = u32at(p);
			for (std::uint32_t i = 0; i < strings && i < 100000; ++i) {
				p = a_out.size();
				if (!ReadBytes(stream, a_out, 4) || !ReadBytes(stream, a_out, u32at(p))) {
					return false;
				}
			}
			p = a_out.size();
			if (!ReadBytes(stream, a_out, 4)) {  // groups
				return false;
			}
			if (!ReadBytes(stream, a_out, std::size_t(u32at(p)) * 4)) {
				return false;
			}
			if (total > (32u << 20) || !ReadBytes(stream, a_out, static_cast<std::size_t>(total))) {
				return false;
			}
			p = a_out.size();
			if (!ReadBytes(stream, a_out, 4)) {  // roots
				return false;
			}
			return ReadBytes(stream, a_out, std::size_t(u32at(p)) * 4);
		}

		template <class T>
		RE::TESObjectREFR* AsRef(T&& a_ref)
		{
			if constexpr (std::is_pointer_v<std::remove_cvref_t<T>>) {
				return a_ref;
			} else {
				return &a_ref;
			}
		}

		// Copies the models of what is around the player (a crate first) into Data/Meshes/SkyCraft/vanilla_dump, to be looked at.
		void DumpVanillaModels(RE::PlayerCharacter* a_player)
		{
			static bool dumped = false;
			if (dumped) {
				return;
			}
			dumped = true;
			const std::filesystem::path dir = kDir.parent_path() / "vanilla_dump";
			std::error_code             ec;
			std::filesystem::create_directories(dir, ec);

			std::vector<std::string> paths;
			auto                     add = [&](const char* a_model) {
                if (!a_model || !*a_model || paths.size() >= 40) {
                    return;
                }
                std::string m = a_model;
                std::string lower = m;
                std::ranges::transform(lower, lower.begin(), [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
                if (lower.find("skycraft") != std::string::npos || lower.find(".nif") == std::string::npos) {
                    return;
                }
                for (const auto& have : paths) {
                    std::string h = have;
                    std::ranges::transform(h, h.begin(), [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
                    if (h == lower) {
                        return;
                    }
                }
                paths.push_back(m);
			};
			if (auto* crate = RE::TESForm::LookupByID(0x0007A617)) {
				if (auto* model = skyrim_cast<RE::TESModel*>(crate)) {
					add(model->GetModel());
				}
			}
			if (auto* cell = a_player->GetParentCell()) {
				cell->ForEachReference([&](auto&& a_ref) {
					auto* ref = AsRef(a_ref);
					auto* base = ref ? ref->GetBaseObject() : nullptr;
					auto* model = base ? skyrim_cast<RE::TESModel*>(base) : nullptr;
					if (model) {
						add(model->GetModel());
					}
					return paths.size() >= 40 ? RE::BSContainer::ForEachResult::kStop : RE::BSContainer::ForEachResult::kContinue;
				});
			}

			int copied = 0;
			for (const auto& path : paths) {
				std::vector<std::uint8_t> bytes;
				if (!ReadNif(std::string("Meshes\\") + path, bytes) || bytes.size() > 400000) {
					continue;
				}
				std::string name = path;
				for (auto& ch : name) {
					if (ch == '\\' || ch == '/' || ch == ':') {
						ch = '_';
					}
				}
				std::ofstream out(dir / name, std::ios::binary | std::ios::trunc);
				out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
				++copied;
			}
			logger::info("Block collision: copied {} of {} nearby vanilla models into {} (for looking at their collision layout)", copied, paths.size(), AbsolutePath(dir));
		}

		void StartVariants(RE::PlayerCharacter* a_player, RE::TESDataHandler* a_handler, const RE::NiPoint3& a_pos, std::uint32_t a_material)
		{
			variantsStarted = true;
			variantsDone = false;
			using O = collisionnif::Options;
			struct Def
			{
				std::string label;
				O           opts;
				std::string fixedFile;  // a file made at plugin load and left as it was (else a slot, written now), or "new": a file made now under a new name
				bool        usable{ false };
			};
			std::vector<Def> defs;
			// Revision 32: only the models that can be right, tried ONE AT A TIME, smallest first (a bad collision model crashes Skyrim, so the
			// last "LOADING" line in the log names the file that did it). The byte-shifted layouts that used to be tried alongside are gone.
			{ O o; o.level = 0; defs.push_back({ "TEST 1: bare node, a file made at plugin load", o, "pre_bare.nif", false }); }
			{ O o; o.level = 1; defs.push_back({ "TEST 2: node with BSXFlags, written over a slot just now", o, "", false }); }
			{ O o; o.level = 2; defs.push_back({ "TEST 3: body with one box, a file made at plugin load", o, "pre_box.nif", false }); }
			{ O o; defs.push_back({ "TEST 4: list of boxes, a file made at plugin load", o, "pre_full.nif", false }); }
			defs.push_back({ "as written", O{}, "", true });
			if (variantsSafe) {
				std::erase_if(defs, [](const Def& d) { return d.opts.level >= 2; });  // nothing with collision in it
			}
			const float                          u = float(proto::kUnitsPerBlock);
			const std::vector<collisionnif::Box> one{ collisionnif::Box{ { 0.0f, 0.0f, 0.0f }, { u, u, u } } };
			logger::info("Block collision test: model files are in {}", AbsolutePath(kDir));
			for (auto& d : defs) {
				Variant v;
				v.label = d.label;
				v.opts = d.opts;
				v.usable = d.usable;
				const auto bytes = collisionnif::Build(one, a_material, 0.01f, d.opts);
				if (!d.fixedFile.empty() && d.fixedFile != "new") {
					v.file = d.fixedFile;  // as PrepareFiles left it
				} else if (d.fixedFile == "new") {
					v.file = fmt::format("rt{}.nif", ++serial);
					if (!WriteFile(v.file, bytes)) {
						v.file.clear();
					}
				} else {
					v.file = TakeSlot();
					if (!v.file.empty() && !WriteFile(v.file, bytes)) {
						v.file.clear();
					}
				}
				if (v.file.empty()) {
					v.done = true;
					variants.push_back(std::move(v));
					continue;
				}
				variantPos = a_pos;  // its reference is made by TickVariants, when it is its turn
				variants.push_back(std::move(v));
			}
			logger::info("Block collision test: {} variants of the model are in the world to see which Skyrim loads", variants.size());
		}

		// Puts one variant's model in the world. The line is logged first: if Skyrim crashes while loading it, it is the last line of the log.
		void LaunchVariant(Variant& a_v)
		{
			a_v.launched = true;
			logger::info("Block collision test: LOADING '{}' from {} (if Skyrim crashes now, this is the file that did it)", a_v.label, a_v.file);
			spdlog::default_logger()->flush();
			auto* player = RE::PlayerCharacter::GetSingleton();
			auto* handler = RE::TESDataHandler::GetSingleton();
			if (player && handler) {
				if (auto* form = MakeStatic(std::string(kModelDir) + a_v.file, float(16 * proto::kUnitsPerBlock))) {
					auto ref = handler->CreateReferenceAtLocation(form, variantPos, RE::NiPoint3(), player->GetParentCell(), player->GetWorldspace(), nullptr, nullptr, RE::ObjectRefHandle(), false, true).get();
					if (ref) {
						ref->SetActivationBlocked(true);
						a_v.refId = ref->GetFormID();
					}
				}
			}
			if (a_v.refId == 0) {
				a_v.done = true;
				logger::info("Block collision test: could not make a reference for '{}'", a_v.label);
			}
		}

		void TickVariants(float a_delta)
		{
			if (!variantsStarted || variantsDone) {
				return;
			}
			Variant* current = nullptr;
			for (auto& v : variants) {
				if (!v.done) {
					current = &v;  // one at a time: the next is not started until this one has loaded (or been given up on)
					break;
				}
			}
			if (current) {
				auto& v = *current;
				if (!v.launched) {
					LaunchVariant(v);
				} else {
					v.waited += a_delta;
					auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(v.refId);
					auto* root = ref ? ref->Get3D() : nullptr;
					if (ref && !root && !v.forced && v.waited > 1.5f) {
						v.forced = true;
						logger::info("Block collision test: asking Skyrim for the 3D of '{}'", v.label);
						root = ref->Load3D(false);
					}
					if (root) {
						bool hasObject = false, inWorld = false;
						CollisionState(root, hasObject, inWorld);
						v.done = v.ok = true;
						logger::info("Block collision test: LOADED  '{}' (3D after {:.1f} s; collision object {}, in the Havok world {})", v.label, v.waited, hasObject ? "yes" : "no", inWorld ? "yes" : "not yet");
					} else if (v.waited > 5.0f) {
						v.done = true;
						logger::info("Block collision test: no 3D  '{}'", v.label);
					}
				}
			}
			bool all = true;
			for (const auto& v : variants) {
				all = all && v.done;
			}
			if (!all) {
				return;
			}
			variantsDone = true;
			if (variantsSafe) {
				logger::info("Block collision test: finished (models with no collision in them only; see which of them loaded above)");
				EndVariants();
				return;
			}
			int first = -1;
			for (std::size_t i = 0; i < variants.size(); ++i) {
				if (variants[i].ok && variants[i].usable) {
					first = static_cast<int>(i);
					break;
				}
			}
			if (first < 0) {
				logger::warn("Block collision test: Skyrim loaded none of the {} real-model variants (see the TEST lines above for what it does load); {} is the file as written (NifSkope will say what is wrong with it)", variants.size(), AbsolutePath(kDir.parent_path() / "debug_default.nif"));
			} else if (variants[static_cast<std::size_t>(first)].label == "as written") {
				logger::info("Block collision test: the model as written loads; the section models should too");
			} else {
				activeOpts = variants[first].opts;
				logger::info("Block collision test: using '{}' for the section models from now on", variants[first].label);
				failures = 0;
				DropMeshes();  // the ones made as written are made again
			}
			EndVariants();
		}
	}

	namespace BlockCollision
	{
		// Reads shape_catalog.json (written by the Fabric side: ShapeCatalog.java) and writes a model for every shape in it. A catalogue whose
		// keys don't match the ones computed here (the two sides disagree on ShapeCatalog.canonical) is not used at all.
		void PrepareLibrary()
		{
			const auto& cfg = Cfg();
			libraryKeys.clear();
			if (!cfg.library || cfg.maxShapeRefs <= 0) {
				return;
			}
			std::vector<std::filesystem::path> candidates;
			if (!cfg.catalog.empty()) {
				candidates.emplace_back(cfg.catalog);
			} else {
				if (const char* local = std::getenv("LOCALAPPDATA")) {
					candidates.push_back(std::filesystem::path(local) / "SkyCraft" / "Prism" / "instances" / "SkyCraft" / ".minecraft" / "skycraft" / "shape_catalog.json");
				}
				candidates.emplace_back("Data/SKSE/Plugins/SkyCraft/shape_catalog.json");
			}
			std::string text;
			std::filesystem::path used;
			for (const auto& c : candidates) {
				std::ifstream in(c, std::ios::binary);
				if (in) {
					text.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
					used = c;
					break;
				}
			}
			if (text.empty()) {
				logger::info("Block collision: no shape catalogue yet ({} places tried); Minecraft writes it the first time its world starts, and the shape library is made from it at the next start. Odd shapes are in the section models until then", candidates.size());
				return;
			}
			std::error_code ec;
			std::filesystem::create_directories(kLibDir, ec);
			const float                    u = float(proto::kUnitsPerBlock);
			std::unordered_set<std::string> keys;
			std::size_t                    pos = 0;
			int                            bad = 0;
			while ((pos = text.find("\"key\": \"", pos)) != std::string::npos) {
				pos += 8;
				const std::string key = text.substr(pos, 8);
				const auto        at = text.find("\"boxes\": [", pos);
				if (at == std::string::npos) {
					break;
				}
				std::size_t    i = at + 10;
				int            depth = 1;
				std::vector<int> nums;
				while (i < text.size() && depth > 0) {
					const char c = text[i];
					if (c == '[') {
						++depth;
					} else if (c == ']') {
						--depth;
					} else if ((c >= '0' && c <= '9') || c == '-') {
						char* end = nullptr;
						nums.push_back(static_cast<int>(std::strtol(text.c_str() + i, &end, 10)));
						i = static_cast<std::size_t>(end - text.c_str());
						continue;
					}
					++i;
				}
				pos = i;
				if (nums.empty() || nums.size() % 6 != 0) {
					++bad;
					continue;
				}
				std::vector<ShapeBox16>         q;
				std::vector<collisionnif::Box>  boxes;
				for (std::size_t n = 0; n < nums.size(); n += 6) {
					ShapeBox16 b{};
					for (int k = 0; k < 6; ++k) {
						b.v[k] = nums[n + k];
					}
					q.push_back(b);
				}
				if (ShapeKey(q) != key) {
					++bad;
					continue;
				}
				for (const auto& b : q) {  // Minecraft (x, y up, z) in sixteenths -> the model's axes (x, y north = -z, z up), game units
					collisionnif::Box box;
					box.lo[0] = b.v[0] * u / 16.0f;
					box.hi[0] = b.v[3] * u / 16.0f;
					box.lo[1] = -b.v[5] * u / 16.0f;
					box.hi[1] = -b.v[2] * u / 16.0f;
					box.lo[2] = b.v[1] * u / 16.0f;
					box.hi[2] = b.v[4] * u / 16.0f;
					boxes.push_back(box);
				}
				const auto           bytes = collisionnif::Build(boxes, Cfg().material, 0.01f);
				std::ofstream        out(kLibDir / ("shp_" + key + ".nif"), std::ios::binary | std::ios::trunc);
				out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
				if (out.good()) {
					keys.insert(key);
				}
			}
			if (bad > 0 && keys.empty()) {
				logger::warn("Block collision: shape catalogue {} isn't usable ({} entries whose keys or boxes don't match this plugin's); the library is off", used.string(), bad);
				return;
			}
			libraryKeys = std::move(keys);
			logger::info("Block collision: shape library: {} models in {} from {} ({} entries skipped); used once a section model has been seen working", libraryKeys.size(), AbsolutePath(kLibDir), used.string(), bad);
		}

		void PrepareFiles()
		{
			if (!Cfg().on) {
				return;
			}
			const auto started = std::chrono::steady_clock::now();
			std::error_code ec;
			std::filesystem::remove_all(kDir, ec);  // models left by an earlier session
			std::filesystem::create_directories(kDir, ec);
			if (ec || !std::filesystem::is_directory(kDir)) {
				logger::warn("Block collision: can't make the folder {}", kDir.string());
				return;
			}
			using O = collisionnif::Options;
			const float                          u = float(proto::kUnitsPerBlock);
			const std::vector<collisionnif::Box> one{ collisionnif::Box{ { 0.0f, 0.0f, 0.0f }, { u, u, u } } };
			O                                    bare, box;
			bare.level = 0;
			box.level = 2;
			const auto placeholder = collisionnif::Build(one, Cfg().material, 0.01f, bare);
			int        written = 0;
			for (int i = 0; i < kSlots; ++i) {
				written += WriteFile(fmt::format("slot{:04d}.nif", i), placeholder) ? 1 : 0;
			}
			written += WriteFile("pre_bare.nif", placeholder) ? 1 : 0;
			written += WriteFile("pre_box.nif", collisionnif::Build(one, Cfg().material, 0.01f, box)) ? 1 : 0;
			written += WriteFile("pre_full.nif", collisionnif::Build(one, Cfg().material, 0.01f, O{})) ? 1 : 0;
			PrepareLibrary();
			logger::info("Block collision: made {} model files in {} at plugin load ({} ms)", written, AbsolutePath(kDir),
				std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count());
		}

		bool Wanted()
		{
			return Cfg().on && !failed;
		}

		bool Verified()
		{
			return inWorldCount > 0;
		}

		bool NeedCrates()
		{
			return !Verified() || Cfg().cratesAlso;
		}

		void Release()
		{
			EndProbe();
			EndVariants();
			DropMeshes();
			cellKey = 0;
		}

		void Update(RE::PlayerCharacter* a_player, float a_delta)
		{
			const auto& cfg = Cfg();
			if (!cfg.on || failed || !a_player || !a_player->GetParentCell()) {
				return;
			}

			static bool started = false;
			if (!started) {
				started = true;
				std::error_code ec;
				std::filesystem::create_directories(kDir, ec);  // (the files in it were made by PrepareFiles at plugin load)
				slotNext = 0;
				if (ec || !std::filesystem::is_directory(kDir)) {
					GiveUp("can't make the folder Data/Meshes/SkyCraft/BlockCollision");
					return;
				}
				serial = static_cast<std::uint32_t>(std::time(nullptr)) & 0xFFFFFF;
				if (!std::filesystem::exists(kDir / "slot0000.nif")) {
					logger::warn("Block collision: the model files weren't made at plugin load; the models will probably not load");
				}
				logger::info("Block collision: on, {} sections out ({} blocks), up to {} section meshes", cfg.radius, cfg.radius * 16, cfg.maxMeshes);
			}

			// Another cell or world: nothing made for the last one is any use here.
			const auto key = CellKeyOf(a_player);
			if (key != cellKey) {
				Release();
				cellKey = key;
			}

			// Meshes made a moment ago: has Skyrim loaded them, and is their collision in the Havok world?
			for (auto it = meshes.begin(); it != meshes.end();) {
				auto& mesh = it->second;
				if (!mesh.pending) {
					++it;
					continue;
				}
				mesh.waited += a_delta;
				auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(mesh.refId);
				auto* root = ref ? ref->Get3D() : nullptr;
				if (ref && !root && !mesh.forced && mesh.waited > 1.5f) {
					mesh.forced = true;
					root = ref->Load3D(false);
					logger::info("Block collision: section ({}, {}, {}) had no 3D after {:.1f} s; asked Skyrim to load it: {}", mesh.sx, mesh.sy, mesh.sz, mesh.waited,
						root ? "it did" : "still nothing");
				}
				bool failed3d = false;
				if (root) {
					mesh.settled += a_delta;
					bool hasObject = false, inWorld = false;
					CollisionState(root, hasObject, inWorld);
					if (hasObject && inWorld) {
						mesh.pending = false;
						++inWorldCount;
						static int logged = 0;
						if (logged++ < 6) {
							logger::info("Block collision: section ({}, {}, {}) works: {} boxes, collision in the Havok world ({}; reference made {})", mesh.sx, mesh.sy, mesh.sz, mesh.boxes, mesh.file,
								mesh.atPlayer ? "at the player and moved" : "where it goes");
						}
						if (mesh.oldRefId != 0 || !mesh.oldFile.empty()) {
							// Every swap is logged (a door opening or closing is one): this is the moment the old collision, door and all, goes.
							logger::info("Block collision: section ({}, {}, {}) changed: the new model ({}, {} boxes) is in the Havok world {:.1f} s after it was made; the old one is put away", mesh.sx, mesh.sy, mesh.sz,
								mesh.file, mesh.boxes, mesh.waited);
							PutAway(mesh.oldRefId, mesh.oldFile);
							mesh.oldRefId = 0;
							mesh.oldFile.clear();
						}
					} else if (mesh.settled > 4.0f) {
						failed3d = true;
						logger::warn("Block collision: section ({}, {}, {}) loaded ({}) but {}", mesh.sx, mesh.sy, mesh.sz, mesh.file,
							hasObject ? "its collision object never got a body into the Havok world" : "there is NO collision object in it");
					}
				} else if (mesh.waited > 8.0f) {
					failed3d = true;
					logger::warn("Block collision: section ({}, {}, {}) has no 3D after {:.0f} s ({}); reference {:08X} {}, in {}, model '{}'", mesh.sx, mesh.sy, mesh.sz, mesh.waited, mesh.file,
						mesh.refId, ref ? (ref->IsDisabled() ? "is disabled" : "is enabled") : "is gone", ref && ref->GetParentCell() ? (ref->GetParentCell()->IsAttached() ? "an attached cell" : "a cell that isn't attached") : "no cell",
						mesh.file);
				}
				if (failed3d) {
					++failures;
					PutAway(mesh.refId, mesh.file);  // the next pass makes it again, until it has failed three times
					PutAway(mesh.oldRefId, mesh.oldFile);
					it = meshes.erase(it);
					if (failures >= 3 && inWorldCount == 0) {
						GiveUp("the section models never got collision into the Havok world");
						return;
					}
				} else {
					++it;
				}
			}

			TickVariants(a_delta);

			if (probe.refId != 0 && !probe.done) {
				probe.waited += a_delta;
				auto* probeRef = RE::TESForm::LookupByID<RE::TESObjectREFR>(probe.refId);
				if (probeRef && probeRef->Get3D()) {
					probe.done = true;
					logger::info("Block collision probe: a vanilla model loaded through a runtime-made static form in {:.1f} s, so such forms work; if the section models don't, Skyrim is rejecting SkyCraft's .nif files", probe.waited);
					EndProbe();
				} else if (probe.waited > 8.0f) {
					probe.done = true;
					logger::warn("Block collision probe: a vanilla model did NOT load through a runtime-made static form either, so the cause is the form or the reference (not the .nif files)");
					EndProbe();
				}
			}

			if (!cfg.experimental) {
				// Collision models are off until their layout is right (a wrong one crashed Skyrim): the crates hold the player up, and what
				// can be learnt without risk is done here: a copy of some vanilla models, and a few models with no collision in them.
				DumpVanillaModels(a_player);
				if (!variantsStarted) {
					variantsSafe = true;
					if (auto* handler = RE::TESDataHandler::GetSingleton()) {
						StartVariants(a_player, handler, a_player->GetPosition(), cfg.material);
					}
				}
				return;
			}

			sincePass += a_delta;
			if (sincePass < 0.25f) {
				return;
			}
			sincePass = 0.0f;

			const auto sky = a_player->GetPosition();
			const auto mc = SkyToMc(sky);
			const int  psx = static_cast<int>(std::floor(mc.x / 16.0)), psy = static_cast<int>(std::floor(mc.y / 16.0)), psz = static_cast<int>(std::floor(mc.z / 16.0));

			std::vector<NpcBlocks::SectionStamp> sections;
			NpcBlocks::ListSections(sections);
			std::unordered_map<std::uint64_t, const NpcBlocks::SectionStamp*> present;
			for (const auto& s : sections) {
				present[Key(s.sx, s.sy, s.sz)] = &s;
			}

			// Meshes whose section has no blocks now, whose reference's cell has gone, or that are well out of range: put away.
			for (auto it = meshes.begin(); it != meshes.end();) {
				const auto& mesh = it->second;
				const bool  gone = !present.contains(it->first);
				const bool  outOfRange = std::abs(mesh.sx - psx) > cfg.radius + 1 || std::abs(mesh.sz - psz) > cfg.radius + 1 || std::abs(mesh.sy - psy) > 4;
				if (gone || outOfRange || (!mesh.pending && !Alive(mesh))) {
					PutAway(mesh.refId, mesh.file);
					PutAway(mesh.oldRefId, mesh.oldFile);
					it = meshes.erase(it);
				} else {
					++it;
				}
			}

			// Sections that need a model (none yet, or built from blocks that have changed), nearest first.
			struct Todo
			{
				const NpcBlocks::SectionStamp* section;
				int                            dist;
			};
			std::vector<Todo> todo;
			for (const auto& s : sections) {
				const int dx = std::abs(s.sx - psx), dz = std::abs(s.sz - psz), dy = std::abs(s.sy - psy);
				if (dx > cfg.radius || dz > cfg.radius || dy > 3) {
					continue;
				}
				const auto it = meshes.find(Key(s.sx, s.sy, s.sz));
				if (it != meshes.end() && (it->second.stamp == s.stamp || it->second.pending)) {
					continue;
				}
				todo.push_back({ &s, dx * dx + dz * dz + dy * dy });
			}
			std::ranges::sort(todo, {}, &Todo::dist);

			// The library is used once a section model has been seen working (so the layout is proven) and only with the default layout, which
			// is what the library files are written in. At that moment the sections already made are made again, so their odd shapes move over.
			const bool useLib = cfg.library && !libBroken && !libraryKeys.empty() && Verified() && DefaultOptions(activeOpts);
			if (useLib && !libWasOn) {
				libWasOn = true;
				for (auto& entry : meshes) {
					entry.second.stamp = 0;
				}
				logger::info("Block collision: shape library on ({} shapes)", libraryKeys.size());
			}
			int libTotal = 0;
			for (const auto& entry : libOf) {
				libTotal += static_cast<int>(entry.second.size());
			}
			auto* handler = RE::TESDataHandler::GetSingleton();
			int   made = 0;
			for (const auto& t : todo) {
				if (made >= 2) {
					break;
				}
				const auto& s = *t.section;
				const auto  mapKey = Key(s.sx, s.sy, s.sz);
				if (meshes.size() >= static_cast<std::size_t>(cfg.maxMeshes) && !meshes.contains(mapKey)) {
					continue;
				}

				std::vector<collisionnif::Box> boxes;
				std::vector<LibBlock> lib;
				BuildBoxes(s.sx, s.sy, s.sz, cfg.overlap, boxes, useLib ? &lib : nullptr, useLib ? std::min(96, cfg.maxShapeRefs - libTotal) : 0);
				if (boxes.empty() && !lib.empty()) {
					lib.clear();  // only odd shapes in this section: a section model of their boxes is simpler than a reference for each
					BuildBoxes(s.sx, s.sy, s.sz, cfg.overlap, boxes);
				}
				logger::info("Block collision: rebuilding ({}, {}, {}) from stamp {}: {} boxes, {} library blocks", s.sx, s.sy, s.sz, s.stamp, boxes.size(), lib.size());
				if (boxes.empty()) {
					// Nothing solid after all: any old mesh goes.
					if (auto it = meshes.find(mapKey); it != meshes.end()) {
						PutAway(it->second.refId, it->second.file);
						PutAway(it->second.oldRefId, it->second.oldFile);
						meshes.erase(it);
					}
					continue;
				}
				if (boxes.size() > collisionnif::kMaxBoxes) {
					logger::warn("Block collision: section ({}, {}, {}) has {} boxes; only the first {} get collision", s.sx, s.sy, s.sz, boxes.size(), collisionnif::kMaxBoxes);
				}

				const auto bytes = collisionnif::Build(boxes, cfg.material, 0.01f, activeOpts);
				static bool dumped = false;
				if (!dumped) {
					dumped = true;  // kept for looking at in NifSkope if Skyrim won't load it
					std::ofstream dump(kDir.parent_path() / "debug_default.nif", std::ios::binary | std::ios::trunc);
					dump.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
				}
				const auto file = TakeSlot();
				if (file.empty()) {
					GiveUp("all the model files made at plugin load are used up (restart the game to get more)");
					return;
				}
				if (!WriteFile(file, bytes)) {
					GiveUp("can't write a section model into Data/Meshes/SkyCraft/BlockCollision");
					return;
				}

				static bool checkedVisible = false;
				if (!checkedVisible) {
					checkedVisible = true;
					RE::BSResourceNiBinaryStream stream(std::string("Meshes\\") + kModelDir + file);
					logger::info("Block collision: can Skyrim's own file system open the first model (Meshes\\{}{}): {}", kModelDir, file, stream.good() ? "yes" : "NO");
				}

				auto* form = MakeStatic(std::string(kModelDir) + file, float(16 * proto::kUnitsPerBlock));
				if (!form) {
					GiveUp("can't make a form for a section model");
					return;
				}
				const RE::NiPoint3 pos = McToSky(double(s.sx) * 16.0, double(s.sy) * 16.0, double(s.sz) * 16.0);
				// Two ways to put a reference in the world, tried by turns if the first doesn't give a model that loads: made right where it goes, or
				// made at the player (as the crates are) and moved.
				const bool                       placeAtPlayer = (failures & 1) != 0;
				RE::NiPointer<RE::TESObjectREFR> ref;
				if (placeAtPlayer) {
					ref = a_player->PlaceObjectAtMe(form, false);
					if (ref) {
						ref->SetPosition(pos);
					}
				} else {
					ref = handler->CreateReferenceAtLocation(form, pos, RE::NiPoint3(), a_player->GetParentCell(), a_player->GetWorldspace(), nullptr, nullptr, RE::ObjectRefHandle(), false, true).get();
				}
				if (!ref) {
					GiveUp("can't place a section model");
					return;
				}
				ref->SetActivationBlocked(true);
				++made;

				// The odd shapes that come from the library: a reference to the model of each, at the block's low corner. They go (and are put
				// away) with the section's reference. If one can't be made the library is given up and the section is made again without it.
				bool libFailed = false;
				if (!lib.empty()) {
					auto& made_ids = libOf[ref->GetFormID()];
					for (const auto& block : lib) {
						auto* shapeForm = LibForm(block.key);
						const RE::NiPoint3 at = McToSky(double(block.cell[0]), double(block.cell[1]), double(block.cell[2]));
						RE::NiPointer<RE::TESObjectREFR> shapeRef;
						if (shapeForm) {
							shapeRef = handler->CreateReferenceAtLocation(shapeForm, at, RE::NiPoint3(), a_player->GetParentCell(), a_player->GetWorldspace(), nullptr, nullptr, RE::ObjectRefHandle(), false, true).get();
						}
						if (!shapeRef) {
							libFailed = true;
							break;
						}
						shapeRef->SetActivationBlocked(true);
						made_ids.push_back(shapeRef->GetFormID());
					}
					if (libFailed) {
						libBroken = true;
						logger::warn("Block collision: can't place a shape library model; the library is given up and the odd shapes are in the section models again");
					}
				}

				if (!variantsStarted) {
					StartVariants(a_player, handler, pos, cfg.material);
				}
				if (!probe.done && probe.refId == 0) {
					auto* crateForm = RE::TESForm::LookupByID(0x0007A617);
					auto* crateModel = crateForm ? skyrim_cast<RE::TESModel*>(crateForm) : nullptr;
					const char* vanillaPath = crateModel ? crateModel->GetModel() : nullptr;
					if (vanillaPath && *vanillaPath) {
						if (auto* probeForm = MakeStatic(vanillaPath, float(16 * proto::kUnitsPerBlock))) {
							auto probeRef = handler->CreateReferenceAtLocation(probeForm, pos, RE::NiPoint3(), a_player->GetParentCell(), a_player->GetWorldspace(), nullptr, nullptr, RE::ObjectRefHandle(), false, true).get();
							if (probeRef) {
								probeRef->SetActivationBlocked(true);
								probe.refId = probeRef->GetFormID();
								logger::info("Block collision probe: put the vanilla model '{}' in the world through a runtime-made static form", vanillaPath);
							}
						}
					}
					if (probe.refId == 0) {
						probe.done = true;
					}
				}

				Mesh fresh;
				fresh.sx = s.sx;
				fresh.sy = s.sy;
				fresh.sz = s.sz;
				fresh.stamp = s.stamp;
				fresh.refId = ref->GetFormID();
				fresh.file = file;
				fresh.boxes = static_cast<std::uint32_t>(std::min<std::size_t>(boxes.size(), collisionnif::kMaxBoxes));
				fresh.pending = true;
				fresh.atPlayer = placeAtPlayer;
				if (auto it = meshes.find(mapKey); it != meshes.end()) {
					fresh.oldRefId = it->second.refId;  // stays until this one has loaded
					fresh.oldFile = it->second.file;
					if (it->second.oldRefId != 0 || !it->second.oldFile.empty()) {
						PutAway(it->second.oldRefId, it->second.oldFile);
					}
					it->second = std::move(fresh);
				} else {
					meshes.emplace(mapKey, std::move(fresh));
				}
				if (libFailed) {
					meshes[mapKey].stamp = 0;  // made again, without the library, next pass
				}
			}
		}
	}
}
