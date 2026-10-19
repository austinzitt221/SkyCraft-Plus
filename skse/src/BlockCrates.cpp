#include "Game.h"

#include <SimpleIni.h>

#include <filesystem>
#include <fstream>

// Skyrim's own physics knows nothing of Minecraft's blocks: the player's Havok body only gets a few fake support planes
// (DigPhysics.cpp), so it never sees ground under it. It is put in the air state on every step, which is the slipping, and a
// jump asked for in that state is ignored. This gives Havok the real thing: real, invisible crates (a base form from
// SkyCraft.ini [Crates]), each scaled to one Minecraft block and set on the top of every solid block around the player that has
// a face showing. Skyrim then stands the player on them, rubs against them and jumps off them as it does with any crate in the
// game. They are only there while Skyrim's controls are on (Release() puts them all away), and are recycled as the player moves.
namespace skycraft
{
	namespace BlockCrates
	{
		void Update(RE::PlayerCharacter* a_player, float a_delta);
		void Release();
	}

	namespace
	{
		struct CrateCfg
		{
			bool        on{ true };
			RE::FormID  base{ 0x0007A617 };
			int         range{ 5 };
			float       overlap{ 0.05f };  // how much wider than a block (a fraction) so neighbouring crates overlap and leave no seam
			float       sink{ 0.0f };      // blocks to set the crates' tops below the block's top
		};

		const CrateCfg& CratesCfg()
		{
			static const CrateCfg settings = [] {
				CSimpleIniA ini;
				ini.SetUnicode();
				ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
				CrateCfg s;
				s.on = ini.GetBoolValue("Crates", "bBlockCrates", true);
				s.base = static_cast<RE::FormID>(std::strtoul(ini.GetValue("Crates", "sBaseForm", "0x0007A617"), nullptr, 0));
				s.range = static_cast<int>(std::clamp<long>(ini.GetLongValue("Crates", "iRange", 5), 1, 8));
				s.overlap = static_cast<float>(std::clamp(ini.GetDoubleValue("Crates", "fOverlap", 0.05), 0.0, 0.3));
				s.sink = static_cast<float>(std::clamp(ini.GetDoubleValue("Crates", "fSink", 0.0), -0.2, 0.2));
				return s;
			}();
			return settings;
		}

		struct Crate
		{
			RE::FormID    id{ 0 };
			std::uint64_t key{ 0 };
			int           x{ 0 }, y{ 0 }, z{ 0 };  // the block it stands on
			bool          used{ false };    // standing on a block now (else it waits to be recycled)
			bool          hidden{ false };  // its geometry has been hidden
		};

		std::vector<Crate> crates;
		bool               failed = false;  // no usable base form: nothing is tried again
		float              sincePass = 1.0f;
		float              emptyFor = 0.0f;  // seconds with no block around (a section being sent again looks like that for a moment)

		constexpr std::size_t kMaxCrates = 450;
		constexpr int         kMaxMadePerPass = 20;
		const char*           kStaleFile = "Data/SKSE/Plugins/SkyCraft-crates.txt";

		std::uint64_t BlockKey(int a_x, int a_y, int a_z)
		{
			return (std::uint64_t(std::uint32_t(a_x) & 0x3FFFFF) << 42) | (std::uint64_t(std::uint32_t(a_z) & 0x3FFFFF) << 20) | (std::uint64_t(std::uint32_t(a_y) & 0xFFFFF));
		}

		// The base form's own bounds (what its model measures), if this CommonLib can read them.
		template <class B>
		bool BoundsOf(B* a_base, float& a_width, float& a_depth, float& a_height, float& a_centreX, float& a_centreY, float& a_top)
		{
			if constexpr (requires { a_base->boundData.boundMin.x; a_base->boundData.boundMax.x; }) {
				const auto& lo = a_base->boundData.boundMin;
				const auto& hi = a_base->boundData.boundMax;
				a_width = float(hi.x) - float(lo.x);
				a_depth = float(hi.y) - float(lo.y);
				a_height = float(hi.z) - float(lo.z);
				a_centreX = (float(hi.x) + float(lo.x)) * 0.5f;
				a_centreY = (float(hi.y) + float(lo.y)) * 0.5f;
				a_top = float(hi.z);
				return a_width > 1.0f && a_depth > 1.0f && a_height > 1.0f;
			} else {
				return false;
			}
		}

		template <class R>
		void SetRefScale(R* a_ref, float a_scale)
		{
			if constexpr (requires { a_ref->refScale = std::uint16_t(100); }) {
				a_ref->refScale = static_cast<std::uint16_t>(std::clamp<long>(std::lround(a_scale * 100.0f), 1, 1000));
			} else if constexpr (requires { a_ref->SetScale(a_scale); }) {
				a_ref->SetScale(a_scale);
			}
		}

		template <class R>
		void PlaceRef(R* a_ref, const RE::NiPoint3& a_pos)
		{
			if constexpr (requires { a_ref->SetPosition(a_pos, true); }) {
				a_ref->SetPosition(a_pos, true);
			} else if constexpr (requires { a_ref->SetPosition(a_pos); }) {
				a_ref->SetPosition(a_pos);
			}
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

		template <class P, class B>
		RE::TESObjectREFR* SpawnRef(P* a_player, B* a_base)
		{
			if constexpr (requires { a_player->PlaceObjectAtMe(a_base, false); }) {
				auto ref = a_player->PlaceObjectAtMe(a_base, false);
				return ref ? ref.get() : nullptr;
			} else {
				return nullptr;
			}
		}

		template <class R>
		void BlockActivation(R* a_ref)
		{
			if constexpr (requires { a_ref->SetActivationBlocked(true); }) {
				a_ref->SetActivationBlocked(true);
			}
		}

		// Hides the crate's geometry and nothing else: its collision stays. (Once its 3D has loaded, a moment after it is made.)
		template <class R>
		bool HideGeometry(R* a_ref, float a_wantScale)
		{
			auto* root = a_ref->Get3D();
			if (!root) {
				return false;
			}
			int hidden = 0;
			if constexpr (requires { root->AsNode(); root->AsGeometry(); root->AsNode()->GetChildren(); root->SetAppCulled(true); }) {
				std::vector<RE::NiAVObject*> stack{ root };
				for (std::size_t visited = 0; !stack.empty() && visited < 512; ++visited) {
					auto* object = stack.back();
					stack.pop_back();
					if (!object) {
						continue;
					}
					if (object->AsGeometry()) {
						object->SetAppCulled(true);
						++hidden;
					} else if (auto* node = object->AsNode()) {
						for (auto& child : node->GetChildren()) {
							if (child) {
								stack.push_back(child.get());
							}
						}
					}
				}
			}
			static int logged = 0;
			if (logged++ < 3) {
				float have = 0.0f;
				if constexpr (requires { root->local.scale; }) {
					have = float(root->local.scale);
				}
				logger::info("Block crate ({:08X}): hid {} geometry nodes; its 3D has scale {:.3f} (wanted {:.3f})", a_ref->GetFormID(), hidden, have, a_wantScale);
			}
			return true;
		}

		void WriteStaleFile()
		{
			std::ofstream out(kStaleFile, std::ios::trunc);
			for (const auto& crate : crates) {
				out << std::hex << crate.id << '\n';
			}
		}

		// A crate left in a save (a crash, or a save made while they were out) is put away the next time the plugin starts.
		void PutAwayStale(RE::FormID a_base)
		{
			std::ifstream in(kStaleFile);
			std::string   line;
			int           gone = 0;
			while (std::getline(in, line)) {
				const auto id = static_cast<RE::FormID>(std::strtoul(line.c_str(), nullptr, 16));
				if (id == 0) {
					continue;
				}
				auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(id);
				if (ref && ref->GetBaseObject() && ref->GetBaseObject()->GetFormID() == a_base) {
					DeleteRef(ref);
					++gone;
				}
			}
			in.close();
			std::ofstream(kStaleFile, std::ios::trunc);
			if (gone > 0) {
				logger::info("Block crates: put away {} crates left over from an earlier session", gone);
			}
		}

		bool FullCube(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			NpcBlocks::BlockBox boxes[NpcBlocks::kMaxBoxes];
			const int           n = NpcBlocks::BoxesAt(a_x, a_y, a_z, boxes);
			if (n != 1) {
				return false;
			}
			const auto& b = boxes[0];
			return b.lo[0] < 0.01f && b.lo[1] < 0.01f && b.lo[2] < 0.01f && b.hi[0] > 0.99f && b.hi[1] > 0.99f && b.hi[2] > 0.99f;
		}

		bool Exposed(int a_x, int a_y, int a_z)
		{
			return !NpcBlocks::SolidAt(a_x, a_y + 1, a_z) || !NpcBlocks::SolidAt(a_x, a_y - 1, a_z) || !NpcBlocks::SolidAt(a_x + 1, a_y, a_z) ||
			       !NpcBlocks::SolidAt(a_x - 1, a_y, a_z) || !NpcBlocks::SolidAt(a_x, a_y, a_z + 1) || !NpcBlocks::SolidAt(a_x, a_y, a_z - 1);
		}

		struct Want
		{
			int           x, y, z;
			std::uint64_t key;
			float         dist;
		};
	}

	namespace BlockCrates
	{
		RE::FormID BaseForm()
		{
			return CratesCfg().base;
		}

		void Release()
		{
			if (crates.empty()) {
				return;
			}
			for (const auto& crate : crates) {
				if (auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(crate.id)) {
					DeleteRef(ref);
				}
			}
			logger::info("Block crates: put away {} crates", crates.size());
			crates.clear();
			std::ofstream(kStaleFile, std::ios::trunc);
			sincePass = 1.0f;
		}

		void Update(RE::PlayerCharacter* a_player, float a_delta)
		{
			const auto& cfg = CratesCfg();
			if (!cfg.on || failed || !a_player || !a_player->GetParentCell()) {
				return;
			}
			static bool started = false;
			static RE::TESBoundObject* base = nullptr;
			static float scale = 1.0f, centreX = 0.0f, centreY = 0.0f, top = 0.0f;
			if (!started) {
				started = true;
				auto* form = RE::TESForm::LookupByID(cfg.base);
				base = form ? form->As<RE::TESBoundObject>() : nullptr;
				if (!base) {
					failed = true;
					logger::warn("Block crates: form {:08X} (SkyCraft.ini [Crates] sBaseForm) isn't an object that can be placed; no crates", cfg.base);
					return;
				}
				PutAwayStale(cfg.base);
				float w = 0, d = 0, h = 0;
				if (BoundsOf(base, w, d, h, centreX, centreY, top)) {
					// One uniform scale (a reference can't be scaled any other way): its narrower side is a block plus a little, so neighbours overlap and leave no seam; its top is set at the block's top.
					scale = static_cast<float>(proto::kUnitsPerBlock) * (1.0f + cfg.overlap) / std::min(w, d);
					logger::info("Block crates: '{}' ({:08X}, form type {}) is {:.1f} x {:.1f} x {:.1f} units; scaled by {:.3f} it is {:.2f} x {:.2f} x {:.2f} blocks",
						base->GetName(), base->GetFormID(), int(base->GetFormType()), w, d, h, scale, w * scale / proto::kUnitsPerBlock, d * scale / proto::kUnitsPerBlock,
						h * scale / proto::kUnitsPerBlock);
				} else {
					logger::warn("Block crates: can't read the size of form {:08X} in this build; its crates are placed unscaled", base->GetFormID());
				}
			}
			if (!base) {
				return;
			}

			// Crates made a moment ago: their geometry is hidden as soon as their 3D has loaded.
			for (auto& crate : crates) {
				if (!crate.hidden) {
					if (auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(crate.id)) {
						crate.hidden = HideGeometry(ref, scale);
					}
				}
			}

			sincePass += a_delta;
			if (sincePass < 0.1f) {
				return;
			}
			sincePass = 0.0f;

			const auto sky = a_player->GetPosition();
			const auto mc = SkyToMc(sky);
			const int  px = int(std::floor(mc.x)), py = int(std::floor(mc.y)), pz = int(std::floor(mc.z));
			const int  r = cfg.range;

			std::vector<Want> wanted;
			for (int z = pz - r; z <= pz + r; ++z) {
				for (int x = px - r; x <= px + r; ++x) {
					for (int y = py - 2; y <= py + 2; ++y) {
						if (!NpcBlocks::SolidAt(x, y, z) || !FullCube(x, y, z) || !Exposed(x, y, z)) {
							continue;
						}
						const float dx = float(x) + 0.5f - float(mc.x), dy = float(y) + 0.5f - float(mc.y), dz = float(z) + 0.5f - float(mc.z);
						wanted.push_back({ x, y, z, BlockKey(x, y, z), dx * dx + dy * dy + dz * dz });
					}
				}
			}
			std::ranges::sort(wanted, {}, &Want::dist);
			if (wanted.size() > kMaxCrates) {
				wanted.resize(kMaxCrates);
			}

			// No block around at all: a section being sent again looks like that for a moment, and every crate must not go with it.
			if (wanted.empty()) {
				emptyFor += 0.1f;
				if (emptyFor < 1.0f) {
					return;
				}
			} else {
				emptyFor = 0.0f;
			}

			// Crates that left the cell the player is in (a door, fast travel) can't be moved to it, and crates whose block has gone (dug,
			// or no longer a whole cube) would be a wall nobody can see: both are put away. Crates for blocks that are still there but
			// out of range stay where they are (they are still real ground) until one is needed for a nearer block.
			bool changed = false;
			for (auto& crate : crates) {
				auto* ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(crate.id);
				if (!ref || ref->GetParentCell() != a_player->GetParentCell() || !FullCube(crate.x, crate.y, crate.z)) {
					DeleteRef(ref);
					crate.id = 0;
					changed = true;
				}
			}
			std::erase_if(crates, [](const Crate& a_crate) { return a_crate.id == 0; });

			std::unordered_set<std::uint64_t> wantedKeys;
			for (const auto& w : wanted) {
				wantedKeys.insert(w.key);
			}
			std::unordered_set<std::uint64_t> have;
			for (auto& crate : crates) {
				// (A crate out of range keeps its block's key and coordinates: it stays, and is still checked above, until a nearer block needs it.)
				crate.used = wantedKeys.contains(crate.key);
				if (crate.used) {
					have.insert(crate.key);
				}
			}

			std::size_t slot = 0;
			int         made = 0;
			for (const auto& w : wanted) {
				if (have.contains(w.key)) {
					continue;
				}
				// A crate no nearby block needs, or a new one.
				while (slot < crates.size() && crates[slot].used) {
					++slot;
				}
				Crate*             crate = nullptr;
				RE::TESObjectREFR* ref = nullptr;
				if (made < kMaxMadePerPass && crates.size() < kMaxCrates) {
					// New ones first while there is room: a crate left standing on a block out of range is still ground, so it is not taken.
					ref = SpawnRef(a_player, base);
					if (!ref) {
						failed = true;
						logger::warn("Block crates: couldn't place a crate (this CommonLib has no PlaceObjectAtMe); no crates");
						return;
					}
					++made;
					SetRefScale(ref, scale);
					BlockActivation(ref);
					crates.push_back({ ref->GetFormID() });
					crate = &crates.back();
				} else if (slot < crates.size()) {
					crate = &crates[slot];
					ref = RE::TESForm::LookupByID<RE::TESObjectREFR>(crate->id);
				}
				if (!crate || !ref) {
					continue;
				}
				RE::NiPoint3 pos = McToSky(double(w.x) + 0.5, double(w.y) + 1.0 - double(cfg.sink), double(w.z) + 0.5);
				pos.x -= centreX * scale;
				pos.y -= centreY * scale;
				pos.z -= top * scale;
				PlaceRef(ref, pos);
				crate->key = w.key;
				crate->x = w.x;
				crate->y = w.y;
				crate->z = w.z;
				crate->used = true;
				changed = true;
			}
			if (changed) {
				WriteStaleFile();
				static int logged = 0;
				if (logged++ < 5) {
					logger::info("Block crates: {} crates for {} blocks around the player", crates.size(), wanted.size());
				}
			}
		}
	}
}
