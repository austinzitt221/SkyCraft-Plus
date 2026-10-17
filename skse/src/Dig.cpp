#include "Dig.h"

#include <bit>

namespace skycraft::Dig
{
	namespace
	{
		using Bits = std::array<std::uint64_t, 64>;  // bit x + 16z + 256y

		std::unordered_map<std::uint64_t, Bits> sections;
		std::shared_mutex                        lock;
		std::atomic<std::uint32_t>               dugCount{ 0 };
		std::atomic<std::uint64_t>               generation{ 0 };
		std::atomic<std::uint32_t>               world{ 0 };
		std::mutex                               changedLock;
		std::vector<Clip::Cube>                  changed;
		constexpr std::size_t                    kMaxChanged = 65536;

		std::uint64_t SectionKey(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			return (std::uint64_t(std::uint32_t(a_x) & 0x3FFFFF) << 42) | (std::uint64_t(std::uint32_t(a_z) & 0x3FFFFF) << 20) |
			       (std::uint64_t(std::uint32_t(a_y) & 0xFFFFF));
		}

		std::array<int, 3> UnpackKey(std::uint64_t a_key)
		{
			auto sext = [](std::uint64_t v, int bits) {
				const std::int64_t s = std::int64_t(v << (64 - bits)) >> (64 - bits);
				return int(s);
			};
			return { sext(a_key >> 42, 22), sext(a_key & 0xFFFFF, 20), sext((a_key >> 20) & 0x3FFFFF, 22) };
		}

		// Every block of a section whose bit differs between a_old and a_new (null: all clear).
		void NoteChanged(int a_sx, int a_sy, int a_sz, const Bits* a_old, const Bits* a_new)
		{
			++generation;
			std::scoped_lock guard(changedLock);
			for (int word = 0; word < 64; ++word) {
				std::uint64_t diff = (a_old ? (*a_old)[word] : 0) ^ (a_new ? (*a_new)[word] : 0);
				while (diff && changed.size() < kMaxChanged) {
					const int bit = word * 64 + std::countr_zero(diff);
					diff &= diff - 1;
					changed.push_back({ a_sx * 16 + (bit & 15), a_sy * 16 + (bit >> 8), a_sz * 16 + ((bit >> 4) & 15) });
				}
			}
		}

		bool StartsWith(const char* a_path, const char* a_prefix)
		{
			return _strnicmp(a_path, a_prefix, std::strlen(a_prefix)) == 0;
		}

		const char* ModelPath(RE::TESForm* a_base)
		{
			auto* model = a_base ? a_base->As<RE::TESModel>() : nullptr;
			const char* path = model ? model->GetModel() : nullptr;
			if (!path) {
				return "";
			}
			if (StartsWith(path, "meshes\\") || StartsWith(path, "meshes/")) {
				path += 7;
			}
			return path;
		}

		bool Contains(const char* a_path, const char* a_part)
		{
			// Case-insensitive, no copies (runs for many references a second).
			const std::size_t n = std::strlen(a_part);
			for (const char* p = a_path; *p; ++p) {
				if (_strnicmp(p, a_part, n) == 0) {
					return true;
				}
			}
			return false;
		}

		RE::MATERIAL_ID GuardedMaterial(const RE::bhkShape* a_wrapper, RE::hkpShapeKey a_key)
		{
			__try {
				return a_wrapper->GetMaterialID(a_key);
			} __except (EXCEPTION_EXECUTE_HANDLER) {
				return RE::MATERIAL_ID::kNone;
			}
		}
	}

	void OnDug(const std::uint8_t* a_data, std::uint32_t a_bytes)
	{
		if (a_bytes < sizeof(proto::RenDug)) {
			return;
		}
		const auto* hdr = reinterpret_cast<const proto::RenDug*>(a_data);
		if (hdr->worldId != world.load()) {
			return;  // sent for the world Skyrim just left
		}
		const auto key = SectionKey(hdr->sx, hdr->sy, hdr->sz);
		std::unique_lock guard(lock);
		const auto       it = sections.find(key);
		if (hdr->count == 0 || a_bytes < sizeof(proto::RenDug) + 512) {
			if (it == sections.end()) {
				return;
			}
			for (auto word : it->second) {
				dugCount -= std::uint32_t(std::popcount(word));
			}
			NoteChanged(hdr->sx, hdr->sy, hdr->sz, &it->second, nullptr);
			sections.erase(it);
			return;
		}
		Bits bits;
		std::memcpy(bits.data(), a_data + sizeof(proto::RenDug), 512);
		if (it != sections.end()) {
			if (it->second == bits) {
				return;
			}
			for (auto word : it->second) {
				dugCount -= std::uint32_t(std::popcount(word));
			}
		}
		for (auto word : bits) {
			dugCount += std::uint32_t(std::popcount(word));
		}
		NoteChanged(hdr->sx, hdr->sy, hdr->sz, it != sections.end() ? &it->second : nullptr, &bits);
		sections[key] = bits;
	}

	void SetWorld(std::uint32_t a_worldId)
	{
		if (world.exchange(a_worldId) == a_worldId) {
			return;
		}
		std::unique_lock guard(lock);
		for (const auto& [key, bits] : sections) {
			const auto s = UnpackKey(key);
			NoteChanged(s[0], s[1], s[2], &bits, nullptr);
		}
		sections.clear();
		dugCount = 0;
	}

	void Clear()
	{
		std::unique_lock guard(lock);
		for (const auto& [key, bits] : sections) {
			const auto s = UnpackKey(key);
			NoteChanged(s[0], s[1], s[2], &bits, nullptr);
		}
		sections.clear();
		dugCount = 0;
	}

	bool Any() { return dugCount.load(std::memory_order_relaxed) != 0; }

	std::uint64_t Generation() { return generation.load(); }

	bool IsDug(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
	{
		if (!Any()) {
			return false;
		}
		std::shared_lock guard(lock);
		const auto       it = sections.find(SectionKey(a_x >> 4, a_y >> 4, a_z >> 4));
		if (it == sections.end()) {
			return false;
		}
		const int bit = (a_x & 15) + 16 * (a_z & 15) + 256 * (a_y & 15);
		return (it->second[bit >> 6] >> (bit & 63)) & 1;
	}

	void Collect(const float a_lo[3], const float a_hi[3], std::vector<Clip::Cube>& a_out)
	{
		if (!Any()) {
			return;
		}
		int lo[3], hi[3];
		for (int i = 0; i < 3; ++i) {
			lo[i] = int(std::floor(a_lo[i] - Clip::kSlop));
			hi[i] = int(std::floor(a_hi[i] + Clip::kSlop));
		}
		std::shared_lock guard(lock);
		for (int sy = lo[1] >> 4; sy <= hi[1] >> 4; ++sy) {
			for (int sz = lo[2] >> 4; sz <= hi[2] >> 4; ++sz) {
				for (int sx = lo[0] >> 4; sx <= hi[0] >> 4; ++sx) {
					const auto it = sections.find(SectionKey(sx, sy, sz));
					if (it == sections.end()) {
						continue;
					}
					const auto& bits = it->second;
					const int   y0 = std::max(lo[1], sy * 16), y1 = std::min(hi[1], sy * 16 + 15);
					const int   z0 = std::max(lo[2], sz * 16), z1 = std::min(hi[2], sz * 16 + 15);
					const int   x0 = std::max(lo[0], sx * 16), x1 = std::min(hi[0], sx * 16 + 15);
					for (int y = y0; y <= y1; ++y) {
						for (int z = z0; z <= z1; ++z) {
							for (int x = x0; x <= x1; ++x) {
								const int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
								if ((bits[bit >> 6] >> (bit & 63)) & 1) {
									a_out.push_back({ x, y, z });
								}
							}
						}
					}
				}
			}
		}
	}

	void TakeChanged(std::vector<Clip::Cube>& a_out)
	{
		std::scoped_lock guard(changedLock);
		a_out.insert(a_out.end(), changed.begin(), changed.end());
		changed.clear();
	}

	bool IsSmallThing(RE::TESObjectREFR* a_ref)
	{
		auto* base = a_ref ? a_ref->GetBaseObject() : nullptr;
		if (!base) {
			return false;
		}
		switch (base->GetFormType()) {
		case RE::FormType::Static:
		case RE::FormType::Tree:
		case RE::FormType::Flora:
			break;
		default:
			return false;
		}
		const char* path = ModelPath(base);
		if (StartsWith(path, "architecture\\") || StartsWith(path, "architecture/")) {
			return false;
		}
		auto* root = a_ref->Get3D();
		return root && root->worldBound.radius > 0.0f && root->worldBound.radius < kSmallThingRadius;
	}

	bool IsDiggableRef(RE::TESObjectREFR* a_ref)
	{
		auto* base = a_ref ? a_ref->GetBaseObject() : nullptr;
		if (!base) {
			return false;
		}
		switch (base->GetFormType()) {
		case RE::FormType::Tree:
		case RE::FormType::Flora:
		case RE::FormType::Furniture:
		case RE::FormType::Door:
		case RE::FormType::Container:
		case RE::FormType::Activator:
		case RE::FormType::MovableStatic:
			return true;
		case RE::FormType::Static:
			{
				// Everything built too (houses, castles, city walls), just not the sky and effects
				// placed in the world.
				const char* path = ModelPath(base);
				return !StartsWith(path, "effects") && !StartsWith(path, "sky") && !Contains(path, "cloud") && !Contains(path, "fog");
			}
		default:
			return false;
		}
	}

	bool IsDiggableCollidable(const RE::hkpCollidable* a_collidable)
	{
		if (!a_collidable) {
			return false;
		}
		const auto layer = a_collidable->GetCollisionLayer();
		switch (layer) {
		case RE::COL_LAYER::kStatic:
		case RE::COL_LAYER::kTrees:
		case RE::COL_LAYER::kTerrain:
		case RE::COL_LAYER::kGround:
			break;
		default:
			return false;
		}
		auto* ref = RE::TESHavokUtilities::FindCollidableRef(*a_collidable);
		if (!ref) {
			return layer == RE::COL_LAYER::kTerrain || layer == RE::COL_LAYER::kGround;  // the land
		}
		return IsDiggableRef(ref);
	}

	RE::MATERIAL_ID ShapeMaterial(const RE::hkpShape* a_top, RE::hkpShapeKey a_key)
	{
		// A MOPP tree only knows one material of its own; the mesh inside it has one per triangle
		// (the land: grass here, dirt there, rock...).
		const RE::hkpShape* shape = a_top;
		if (shape && shape->type == RE::hkpShapeType::kMOPP) {
			if (const auto* child = static_cast<const RE::hkpMoppBvTreeShape*>(shape)->child.childShape; child && child->userData) {
				shape = child;
			}
		}
		if (!shape || !shape->userData) {
			return a_top && a_top->userData ? a_top->userData->materialID : RE::MATERIAL_ID::kNone;
		}
		const auto id = GuardedMaterial(shape->userData, a_key);
		return id != RE::MATERIAL_ID::kNone ? id : shape->userData->materialID;
	}

	RE::MATERIAL_ID LandMaterialAt(float a_x, float a_y)
	{
		auto* tes = RE::TES::GetSingleton();
		auto* grid = tes ? tes->gridCells : nullptr;
		if (!grid || tes->interiorCell) {
			return RE::MATERIAL_ID::kNone;
		}
		constexpr float kCell = 4096.0f, kQuad = 2048.0f, kSpacing = 128.0f;
		const int       cx = int(std::floor(a_x / kCell)), cy = int(std::floor(a_y / kCell));
		RE::TESObjectCELL* cell = nullptr;
		for (std::uint32_t i = 0; i < grid->length * grid->length && !cell; ++i) {
			auto* c = grid->cells[i];
			auto* ext = c ? c->GetCoordinates() : nullptr;
			if (ext && ext->cellX == cx && ext->cellY == cy) {
				cell = c;
			}
		}
		auto* land = cell ? cell->GetRuntimeData().cellLand : nullptr;
		auto* loaded = land ? land->loadedData : nullptr;
		if (!loaded) {
			return RE::MATERIAL_ID::kNone;
		}
		const float lx = a_x - cx * kCell, ly = a_y - cy * kCell;
		const int   quad = (lx >= kQuad ? 1 : 0) + (ly >= kQuad ? 2 : 0);
		const int   col = std::clamp(int(std::lround((lx - (quad & 1) * kQuad) / kSpacing)), 0, 16);
		const int   row = std::clamp(int(std::lround((ly - (quad >> 1) * kQuad) / kSpacing)), 0, 16);
		const int   vertex = row * 17 + col;
		// The layer painted most strongly over the quad's base texture there.
		RE::TESLandTexture* texture = loaded->defQuadTextures[quad];
		int                 best = 40;
		for (int layer = 0; layer < 6; ++layer) {
			const int weight = std::uint8_t(loaded->percents[quad][vertex][layer]);
			if (loaded->quadTextures[quad][layer] && weight > best) {
				best = weight;
				texture = loaded->quadTextures[quad][layer];
			}
		}
		if (!texture) {
			// No texture of its own: Skyrim's default land texture (dirt).
			return RE::MATERIAL_ID::kDirt;
		}
		return texture->materialType ? texture->materialType->materialID : RE::MATERIAL_ID::kNone;
	}

	// SkyCraft+ : Skyrim's ore veins (activators running MineOreScript: "Iron Ore Vein" and the like).
	bool IsOreVein(RE::TESObjectREFR* a_ref)
	{
		auto* base = a_ref ? a_ref->GetBaseObject() : nullptr;
		if (!base || !base->Is(RE::FormType::Activator)) {
			return false;
		}
		static std::mutex                            lock;
		static std::unordered_map<RE::FormID, bool> known;
		std::scoped_lock                             guard(lock);
		if (auto it = known.find(base->GetFormID()); it != known.end()) {
			return it->second;
		}
		const std::string_view name = base->GetName() ? base->GetName() : "";
		const char*            path = ModelPath(base);
		const bool vein = name.ends_with(" Vein") || Contains(path, "orevein") || Contains(path, "mineore");
		known.emplace(base->GetFormID(), vein);
		if (vein) {
			logger::info("ore veins: {} ({:08X}, {}) is one", name, base->GetFormID(), path);
		}
		return vein;
	}

	std::uint8_t MaterialFor(RE::MATERIAL_ID a_material, RE::TESObjectREFR* a_ref, bool a_tree)
	{
		using M = RE::MATERIAL_ID;
		const char* path = a_ref ? ModelPath(a_ref->GetBaseObject()) : "";
		const bool  treeish = a_tree || Contains(path, "trees\\") || Contains(path, "trees/");
		auto        log = [&] {
			if (Contains(path, "aspen") || Contains(path, "birch")) {
				return proto::kDigBirchLog;
			}
			if (Contains(path, "pine") || Contains(path, "snow") || Contains(path, "reach")) {
				return proto::kDigSpruceLog;
			}
			return proto::kDigOakLog;
		};
		// SkyCraft+ : Skyrim things that break into their Minecraft counterpart, by what they are.
		if (IsOreVein(a_ref)) {
			return proto::kDigOreVein;
		}
		const bool metal = a_material == M::kMetalLight || a_material == M::kMetalSolid || a_material == M::kMetalHeavy || a_material == M::kChainMetal ||
		                   a_material == M::kChain;
		if (Contains(path, "barrel")) {
			return proto::kDigBarrel;
		}
		if (Contains(path, "crate")) {
			return proto::kDigChest;
		}
		if (Contains(path, "bookshel") || Contains(path, "bookcase")) {
			return proto::kDigBookshelf;
		}
		if (Contains(path, "woodpile") || Contains(path, "firewood") || Contains(path, "logpile") || Contains(path, "logstack")) {
			return proto::kDigOakLog;
		}
		if (Contains(path, "candle")) {
			return proto::kDigCandle;
		}
		if (Contains(path, "lantern")) {
			return proto::kDigLantern;
		}
		if (Contains(path, "brazier") || Contains(path, "firepit") || Contains(path, "campfire")) {
			return proto::kDigCampfire;
		}
		if (Contains(path, "bedroll")) {
			return proto::kDigBed;
		}
		if (Contains(path, "mushroom") || Contains(path, "fungus")) {
			return proto::kDigMushroom;
		}
		if (Contains(path, "rubble") || Contains(path, "rockpile")) {
			return proto::kDigCobble;
		}
		if (Contains(path, "skull") || Contains(path, "bonepile")) {
			return proto::kDigBone;
		}
		if (a_material == M::kCarpet || Contains(path, "\\rug") || Contains(path, "carpet")) {
			return proto::kDigCarpet;
		}
		if (metal && Contains(path, "chain")) {
			return proto::kDigChain;
		}
		if (metal && (Contains(path, "gate") || Contains(path, "grate") || Contains(path, "portcullis") || Contains(path, "bars") || Contains(path, "jail") ||
					  Contains(path, "cage"))) {
			return proto::kDigIronBars;
		}
		// SkyCraft+ : hay bales and piles are cloth to Skyrim's sounds, but hay to Minecraft.
		if (Contains(path, "haybale") || Contains(path, "haypile") || Contains(path, "haystack") || Contains(path, "\\hay")) {
			return proto::kDigHay;
		}
		switch (a_material) {
		case M::kGrass:
			return proto::kDigGrass;
		case M::kDirt:
			return proto::kDigDirt;
		case M::kMud:
			return proto::kDigMud;
		case M::kSand:
			return proto::kDigSand;
		case M::kGravel:
			return proto::kDigGravel;
		case M::kSnow:
		case M::kSnowStairs:
			return proto::kDigSnow;
		case M::kIce:
		case M::kIceForm:
			return proto::kDigIce;
		case M::kStone:
		case M::kStoneHeavy:
		case M::kStoneStairs:
		case M::kStoneAsStairs:
		case M::kBoulderSmall:
		case M::kBoulderMedium:
		case M::kBoulderLarge:
		case M::kCeramicMedium:
			return proto::kDigStone;
		case M::kStoneBroken:
		case M::kStoneStairsBroken:
			return proto::kDigCobble;
		case M::kWood:
		case M::kWoodHeavy:
		case M::kWoodLight:
		case M::kWoodStairs:
		case M::kWoodAsStairs:
		case M::kBarrel:
		case M::kBasket:
		case M::kCarriageWheel:
			return treeish ? log() : proto::kDigPlanks;
		case M::kMetalLight:
		case M::kMetalSolid:
		case M::kMetalHeavy:
		case M::kChainMetal:
		case M::kChain:
		case M::kPotsPans:
		case M::kSkinMetalLarge:
		case M::kSkinMetalSmall:
			return proto::kDigMetal;
		case M::kGlass:
		case M::kGlassStairs:
		case M::kBottle:
		case M::kBottleSmall:
			return proto::kDigGlass;
		case M::kOrganic:
		case M::kOrganicLarge:
			return treeish ? log() : proto::kDigOrganic;
		case M::kCloth:
		case M::kCarpet:
			return proto::kDigCloth;
		case M::kBone:
		case M::kBoneActor:
		case M::kDraugrSkeleton:
		case M::kSkinSkeleton:
			return proto::kDigBone;
		case M::kWeb:
			return proto::kDigWeb;
		case M::kAsh:
			return proto::kDigAsh;
		default:
			return treeish ? log() : proto::kDigStone;
		}
	}

	// SkyCraft+ : the ore a vein gives, as Skyrim's own mining does (MineOreScript's "ore"), or by its
	// name ("Iron Ore Vein": "Iron Ore").
	RE::TESBoundObject* OreOf(RE::TESObjectREFR* a_ref)
	{
		if (auto* vm = RE::BSScript::Internal::VirtualMachine::GetSingleton()) {
			auto*      policy = vm->GetObjectHandlePolicy();
			const auto handle = policy ? policy->GetHandleForObject(static_cast<RE::VMTypeID>(a_ref->GetFormType()), a_ref) : 0;
			RE::BSTSmartPointer<RE::BSScript::Object> object;
			if (policy && handle != policy->EmptyHandle() && vm->FindBoundObject(handle, "MineOreScript", object) && object) {
				if (auto* ore = object->GetProperty("ore"); ore && ore->IsObject()) {
					if (auto* form = ore->Unpack<RE::TESForm*>()) {
						return form->As<RE::TESBoundObject>();
					}
				}
			}
		}
		std::string name = a_ref->GetBaseObject()->GetName();
		if (name.ends_with(" Vein")) {
			name.resize(name.size() - 5);
		}
		if (auto* data = RE::TESDataHandler::GetSingleton()) {
			for (auto* misc : data->GetFormArray<RE::TESObjectMISC>()) {
				if (misc && misc->GetName() && name == misc->GetName()) {
					return misc;
				}
			}
		}
		return nullptr;
	}

	void GiveMinedOre(RE::PlayerCharacter* a_player, double a_x, double a_y, double a_z)
	{
		const RE::NiPoint3 at = McToSky(a_x, a_y, a_z);
		RE::TESObjectREFR* vein = nullptr;
		float              best = 3.0f * static_cast<float>(proto::kUnitsPerBlock);
		RE::TES::GetSingleton()->ForEachReferenceInRange(a_player, 800.0f, [&](RE::TESObjectREFR* a_ref) {
			if (a_ref && IsOreVein(a_ref)) {
				const float d = a_ref->GetPosition().GetDistance(at);
				if (d < best) {
					best = d;
					vein = a_ref;
				}
			}
			return RE::BSContainer::ForEachResult::kContinue;
		});
		auto* ore = vein ? OreOf(vein) : nullptr;
		if (!ore) {
			logger::info("ore veins: mined at {:.0f} {:.0f} {:.0f}, but no vein's ore found there", at.x, at.y, at.z);
			return;
		}
		a_player->AddObjectToContainer(ore, nullptr, 1, nullptr);
		RE::SendHUDMessage::ShowHUDMessage(std::format("{} added", ore->GetName()).c_str());
		logger::info("ore veins: {} mined out of {}", ore->GetName(), vein->GetBaseObject()->GetName());
	}
}
