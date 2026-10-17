#include "Collision.h"

#include "Dig.h"
#include "Game.h"

namespace skycraft
{
	namespace
	{
		using Clock = std::chrono::steady_clock;

		constexpr int   kRadius = 5;          // regions around the player horizontally (arrows fly far)
		constexpr int   kBelow = 3;           // regions below the player
		constexpr int   kAbove = 2;           // regions above the player
		constexpr int   kGrid = Collision::kRegionSize * 8;  // voxels per region edge (64)
		constexpr auto  kRefreshNear = 1000ms;  // re-send regions next to the player this often (doors etc.)
		constexpr auto  kFrameBudget = 2500us;
		constexpr int   kMaxHarvestsPerFrame = 3;
		constexpr int   kMaxKeys = 16384;
		constexpr float kSteepMin = 0.1f;    // |n.y| below this is a wall: keep it fine-grained
		constexpr float kSteepMax = 0.643f;  // |n.y| below this (steeper than ~50 deg) gets block-coarsened
		constexpr float kPrimMargin = 0.5f;  // voxels; lets thin convex shapes still register

		// Blocks per Havok unit (Havok unit = 69.99125 game units, block = 70 game units).
		float BlocksPerHavok()
		{
			return RE::bhkWorld::GetWorldScaleInverse() / static_cast<float>(proto::kUnitsPerBlock);
		}

		bool Finite(const float* a_v, int a_n)
		{
			for (int i = 0; i < a_n; ++i) {
				if (!std::isfinite(a_v[i]) || std::fabs(a_v[i]) > 1.0e6f) {
					return false;
				}
			}
			return true;
		}

		// Havok transform: rotation columns at [0..2], [4..6], [8..10]; translation at [12..14].
		void XfPoint(const float* a_xf, const float* a_p, float* a_out)
		{
			for (int i = 0; i < 3; ++i) {
				a_out[i] = a_xf[i] * a_p[0] + a_xf[4 + i] * a_p[1] + a_xf[8 + i] * a_p[2] + a_xf[12 + i];
			}
		}

		void XfDir(const float* a_xf, const float* a_d, float* a_out)
		{
			for (int i = 0; i < 3; ++i) {
				a_out[i] = a_xf[i] * a_d[0] + a_xf[4 + i] * a_d[1] + a_xf[8 + i] * a_d[2];
			}
		}

		void XfCompose(const float* a_parent, const float* a_child, float* a_out)
		{
			for (int c = 0; c < 3; ++c) {
				XfDir(a_parent, a_child + c * 4, a_out + c * 4);
				a_out[c * 4 + 3] = 0.0f;
			}
			XfPoint(a_parent, a_child + 12, a_out + 12);
			a_out[15] = 1.0f;
		}

		bool XfLooksValid(const float* a_xf)
		{
			if (!Finite(a_xf, 16)) {
				return false;
			}
			for (int c = 0; c < 3; ++c) {
				const float* col = a_xf + c * 4;
				const float  len = col[0] * col[0] + col[1] * col[1] + col[2] * col[2];
				if (std::fabs(len - 1.0f) > 0.05f) {
					return false;
				}
			}
			return true;
		}

		// Havok world space -> MC space.
		void HkToMc(const float* a_h, float a_k, float* a_out)
		{
			a_out[0] = a_h[0] * a_k;
			a_out[1] = a_h[2] * a_k;
			a_out[2] = -a_h[1] * a_k;
		}

		void HkDirToMc(const float* a_h, float* a_out)
		{
			a_out[0] = a_h[0];
			a_out[1] = a_h[2];
			a_out[2] = -a_h[1];
		}

		void HkAabbToMc(const RE::hkAabb& a_box, float a_k, float* a_lo, float* a_hi)
		{
			alignas(16) float mn[4], mx[4];
			_mm_store_ps(mn, a_box.min.quad);
			_mm_store_ps(mx, a_box.max.quad);
			a_lo[0] = mn[0] * a_k;
			a_hi[0] = mx[0] * a_k;
			a_lo[1] = mn[2] * a_k;
			a_hi[1] = mx[2] * a_k;
			a_lo[2] = -mx[1] * a_k;
			a_hi[2] = -mn[1] * a_k;
		}

		bool Overlaps(const float* a_lo, const float* a_hi, const float* b_lo, const float* b_hi)
		{
			return a_lo[0] <= b_hi[0] && a_hi[0] >= b_lo[0] && a_lo[1] <= b_hi[1] && a_hi[1] >= b_lo[1] && a_lo[2] <= b_hi[2] && a_hi[2] >= b_lo[2];
		}

		const float* Vec(const void* a_base, std::size_t a_offset)
		{
			return reinterpret_cast<const float*>(reinterpret_cast<const std::uint8_t*>(a_base) + a_offset);
		}

		template <class T>
		T Field(const void* a_base, std::size_t a_offset)
		{
			T value;
			std::memcpy(&value, reinterpret_cast<const std::uint8_t*>(a_base) + a_offset, sizeof(T));
			return value;
		}

		bool IsStairHelper(RE::COL_LAYER a_layer) { return a_layer == RE::COL_LAYER::kStairHelper; }

		bool Included(RE::COL_LAYER a_layer)
		{
			switch (a_layer) {
			case RE::COL_LAYER::kStairHelper:
			case RE::COL_LAYER::kStatic:
			case RE::COL_LAYER::kAnimStatic:
			case RE::COL_LAYER::kTransparent:
			case RE::COL_LAYER::kTrees:
			case RE::COL_LAYER::kProps:
			case RE::COL_LAYER::kTerrain:
			case RE::COL_LAYER::kGround:
			case RE::COL_LAYER::kInvisibleWall:
				return true;
			default:
				return false;
			}
		}

		// ---- triangle / box overlap (Akenine-Moller SAT), voxel units -------------------------
		inline void Sub(const float* a, const float* b, float* o) { o[0] = a[0] - b[0], o[1] = a[1] - b[1], o[2] = a[2] - b[2]; }
		inline void Cross(const float* a, const float* b, float* o)
		{
			o[0] = a[1] * b[2] - a[2] * b[1];
			o[1] = a[2] * b[0] - a[0] * b[2];
			o[2] = a[0] * b[1] - a[1] * b[0];
		}
		inline float Dot(const float* a, const float* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

		bool AxisTest(const float* v0, const float* v1, const float* v2, const float* axis, float h)
		{
			const float p0 = Dot(v0, axis), p1 = Dot(v1, axis), p2 = Dot(v2, axis);
			const float mn = std::min({ p0, p1, p2 }), mx = std::max({ p0, p1, p2 });
			const float r = h * (std::fabs(axis[0]) + std::fabs(axis[1]) + std::fabs(axis[2]));
			return !(mn > r || mx < -r);
		}

		// Box centered at c with half-size h (all axes). Triangle verts a/b/c, face normal n.
		bool TriBoxOverlap(const float* c, float h, const float* ta, const float* tb, const float* tc, const float* n)
		{
			float v0[3], v1[3], v2[3];
			Sub(ta, c, v0);
			Sub(tb, c, v1);
			Sub(tc, c, v2);
			for (int i = 0; i < 3; ++i) {
				const float mn = std::min({ v0[i], v1[i], v2[i] }), mx = std::max({ v0[i], v1[i], v2[i] });
				if (mn > h || mx < -h) {
					return false;
				}
			}
			const float d = Dot(n, v0);
			const float r = h * (std::fabs(n[0]) + std::fabs(n[1]) + std::fabs(n[2]));
			if (std::fabs(d) > r) {
				return false;
			}
			float e[3][3];
			Sub(v1, v0, e[0]);
			Sub(v2, v1, e[1]);
			Sub(v0, v2, e[2]);
			static constexpr float kAxes[3][3] = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
			for (auto& edge : e) {
				for (auto& unit : kAxes) {
					float axis[3];
					Cross(edge, unit, axis);
					if (!AxisTest(v0, v1, v2, axis, h)) {
						return false;
					}
				}
			}
			return true;
		}

		bool GuardedAabb(const RE::hkpShape* a_shape, const float* a_xf, RE::hkAabb& a_out)
		{
			__try {
				a_shape->GetAabbImpl(*reinterpret_cast<const RE::hkTransform*>(a_xf), 0.0f, a_out);
				return true;
			} __except (EXCEPTION_EXECUTE_HANDLER) {
				return false;
			}
		}

		std::uint64_t RegionKey(int a_x, int a_y, int a_z)
		{
			return (std::uint64_t(std::uint32_t(a_x) & 0x1FFFFF) << 42) | (std::uint64_t(std::uint32_t(a_y) & 0x1FFFFF) << 21) | (std::uint32_t(a_z) & 0x1FFFFF);
		}
	}

	namespace
	{
		// Havok shape layouts are partly reverse-engineered; never let a bad read take down the game.
		using CollectFn = void (*)(void* a_self, const RE::hkpShape*, const float*, const float*, const float*, void*);
		bool GuardedCall(CollectFn a_fn, void* a_self, const RE::hkpShape* a_shape, const float* a_xf, const float* a_lo, const float* a_hi, void* a_job)
		{
			__try {
				a_fn(a_self, a_shape, a_xf, a_lo, a_hi, a_job);
				return true;
			} __except (EXCEPTION_EXECUTE_HANDLER) {
				return false;
			}
		}
	}

	Collision& Collision::Get()
	{
		static Collision instance;
		return instance;
	}

	void Collision::Start()
	{
		for (int dx = -kRadius; dx <= kRadius; ++dx) {
			for (int dz = -kRadius; dz <= kRadius; ++dz) {
				for (int dy = -kBelow; dy <= kAbove; ++dy) {
					offsets_.push_back({ dx, dy, dz });
				}
			}
		}
		std::ranges::sort(offsets_, {}, [](const auto& o) { return o[0] * o[0] + o[2] * o[2] + o[1] * o[1] * 2; });
		worker_ = std::thread([this] { WorkerLoop(); });
		worker_.detach();
	}

	void Collision::Reset(std::uint32_t a_epoch)
	{
		epoch_ = a_epoch;
		harvested_.clear();
		std::scoped_lock lock(mutex_);
		queue_.clear();
		Job job{};
		job.clear = true;
		job.epoch = a_epoch;
		queue_.push_back(std::move(job));
		cv_.notify_one();
	}

	void Collision::Update(const McVec& a_playerMc)
	{
		auto* player = RE::PlayerCharacter::GetSingleton();
		auto* cell = player ? player->GetParentCell() : nullptr;
		auto* bhk = cell ? cell->GetbhkWorld() : nullptr;
		auto* world = bhk ? bhk->GetWorld1() : nullptr;
		if (!world) {
			return;
		}

		const int prx = static_cast<int>(std::floor(a_playerMc.x / kRegionSize));
		const int pry = static_cast<int>(std::floor(a_playerMc.y / kRegionSize));
		const int prz = static_cast<int>(std::floor(a_playerMc.z / kRegionSize));
		const auto now = Clock::now();

		WatchMovers(player);  // SkyCraft+ : opened doors

		int  done = 0;
		bool gathered = false;
		const auto start = now;
		// Regions around blocks just dug: their collision changed.
		while (!urgent_.empty() && done < kMaxHarvestsPerFrame * 2) {
			const auto r = urgent_.back();
			urgent_.pop_back();
			if (std::abs(r[0] - prx) > kRadius + 1 || std::abs(r[2] - prz) > kRadius + 1 || r[1] - pry < -kBelow - 1 || r[1] - pry > kAbove + 1) {
				harvested_.erase(RegionKey(r[0], r[1], r[2]));  // far away: sent again whenever it's needed
				continue;
			}
			if (!gathered) {
				RE::BSReadLockGuard lock(bhk->worldLock);
				GatherBodies(world);
				gathered = true;
			}
			{
				RE::BSReadLockGuard lock(bhk->worldLock);
				Harvest(r[0], r[1], r[2]);
			}
			harvested_[RegionKey(r[0], r[1], r[2])] = now;
			++done;
		}
		for (const auto& o : offsets_) {
			const int rx = prx + o[0], ry = pry + o[1], rz = prz + o[2];
			const auto key = RegionKey(rx, ry, rz);
			const auto it = harvested_.find(key);
			// SkyCraft+ : opened doors. And the regions above: a door or drawbridge reaching up into
			// one stayed shut there, at head height, an invisible wall in the open doorway.
			const bool isNear = std::abs(o[0]) <= 1 && std::abs(o[2]) <= 1 && o[1] >= -1 && o[1] <= 1;
			if (it != harvested_.end() && !(isNear && now - it->second > kRefreshNear)) {
				continue;
			}
			if (!gathered) {
				RE::BSReadLockGuard lock(bhk->worldLock);
				GatherBodies(world);
				gathered = true;
			}
			{
				RE::BSReadLockGuard lock(bhk->worldLock);
				Harvest(rx, ry, rz);
			}
			harvested_[key] = now;
			if (++done >= kMaxHarvestsPerFrame || Clock::now() - start > kFrameBudget) {
				break;
			}
		}

		// Bound memory: drop bookkeeping for far-away regions.
		if (harvested_.size() > offsets_.size() * 4) {
			harvested_.clear();
		}
	}

	// SkyCraft+ : opened doors. In Helgen Keep every door, gate and cage door opened in Skyrim
	// stayed an invisible wall in Minecraft, and so did the drawbridge once lowered, until a block
	// dug beside it sent its region again. Things near the player that move (their 3D's bounds
	// change) have the regions they cover sent again at once, and once more when they stop.
	void Collision::WatchMovers(RE::PlayerCharacter* a_player)
	{
		const auto now = Clock::now();
		auto*      tes = RE::TES::GetSingleton();
		if (!tes || now - moversAt_ < 200ms) {
			return;
		}
		moversAt_ = now;
		constexpr float kRange = 16.0f * float(proto::kUnitsPerBlock);
		constexpr float kStill = 1.0f;  // game units
		std::unordered_map<RE::FormID, Mover> seen;
		tes->ForEachReferenceInRange(a_player, kRange, [&](RE::TESObjectREFR* a_ref) {
			if (!a_ref || a_ref == a_player || a_ref->IsDisabled() || a_ref->IsDeleted() || !a_ref->Is3DLoaded()) {
				return RE::BSContainer::ForEachResult::kContinue;
			}
			auto* base = a_ref->GetBaseObject();
			if (!base) {
				return RE::BSContainer::ForEachResult::kContinue;
			}
			switch (base->GetFormType()) {
			case RE::FormType::Door:
			case RE::FormType::Activator:
			case RE::FormType::MovableStatic:
			case RE::FormType::Static:
			case RE::FormType::Furniture:
				break;
			default:
				return RE::BSContainer::ForEachResult::kContinue;
			}
			auto* root3D = a_ref->Get3D();
			if (!root3D) {
				return RE::BSContainer::ForEachResult::kContinue;
			}
			Mover now3D{ root3D->worldBound.center, root3D->worldBound.radius };
			const auto it = movers_.find(a_ref->GetFormID());
			if (it == movers_.end()) {
				// Only things with collision matter (looked for once per thing in range).
				std::function<bool(RE::NiAVObject*, int)> solid = [&](RE::NiAVObject* a_obj, int a_depth) {
					if (!a_obj || a_depth > 12) {
						return false;
					}
					if (a_obj->GetCollisionObject()) {
						return true;
					}
					if (auto* node = a_obj->AsNode()) {
						for (auto& child : node->GetChildren()) {
							if (solid(child.get(), a_depth + 1)) {
								return true;
							}
						}
					}
					return false;
				};
				now3D.solid = solid(root3D, 0);
			} else {
				now3D.solid = it->second.solid;
			}
			if (it != movers_.end() && now3D.solid) {
				const bool moved = it->second.centre.GetDistance(now3D.centre) > kStill || std::fabs(it->second.radius - now3D.radius) > kStill;
				now3D.moving = moved;
				now3D.movingFor = moved ? it->second.movingFor + 0.2f : 0.0f;
				if ((moved || it->second.moving) && now3D.movingFor < 10.0f) {
					// Both where it was and where it is now (and once more where it came to rest).
					for (const auto* m : { &it->second, &now3D }) {
						const auto c = SkyToMc(m->centre);
						const float r = m->radius / float(proto::kUnitsPerBlock) + 0.5f;
						const float lo[3] = { float(c.x) - r, float(c.y) - r, float(c.z) - r };
						const float hi[3] = { float(c.x) + r, float(c.y) + r, float(c.z) + r };
						QueueRegions(lo, hi);
					}
					static int logged = 0;
					if (moved && !it->second.moving && logged++ < 30) {
						logger::info("collision: {:08X} ({}) moves: its collision is sent again", a_ref->GetFormID(), a_ref->GetDisplayFullName());
					}
				}
			}
			seen.emplace(a_ref->GetFormID(), now3D);
			return RE::BSContainer::ForEachResult::kContinue;
		});
		movers_ = std::move(seen);
	}

	void Collision::QueueRegions(const float* a_lo, const float* a_hi)
	{
		if (a_hi[0] - a_lo[0] > 64.0f || a_hi[1] - a_lo[1] > 64.0f || a_hi[2] - a_lo[2] > 64.0f) {
			return;  // (something huge; the regions around the player are sent again anyway)
		}
		std::unordered_set<std::uint64_t> queued;
		for (const auto& r : urgent_) {
			queued.insert(RegionKey(r[0], r[1], r[2]));
		}
		for (int rx = int(std::floor(a_lo[0] / kRegionSize)); rx <= int(std::floor(a_hi[0] / kRegionSize)); ++rx) {
			for (int ry = int(std::floor(a_lo[1] / kRegionSize)); ry <= int(std::floor(a_hi[1] / kRegionSize)); ++ry) {
				for (int rz = int(std::floor(a_lo[2] / kRegionSize)); rz <= int(std::floor(a_hi[2] / kRegionSize)); ++rz) {
					if (queued.insert(RegionKey(rx, ry, rz)).second) {
						urgent_.push_back({ rx, ry, rz });
					}
				}
			}
		}
	}

	void Collision::GatherBodies(RE::hkpWorld* a_world)
	{
		bodies_.clear();
		const float k = BlocksPerHavok();
		auto addIsland = [&](RE::hkpSimulationIsland* a_island, bool a_fixed) {
			if (!a_island) {
				return;
			}
			auto& entities = a_island->entities;
			for (std::int32_t i = 0; i < entities.size(); ++i) {
				auto* entity = entities.data()[i];
				if (!entity) {
					continue;
				}
				const auto& collidable = entity->collidable;
				if (!Included(collidable.GetCollisionLayer())) {
					continue;
				}
				const auto* shape = collidable.shape;
				const auto* xf = static_cast<const float*>(collidable.motion);
				if (!shape || !xf || !Finite(xf, 16)) {
					continue;
				}
				RE::hkAabb box;
				if (!GuardedAabb(shape, xf, box)) {
					continue;
				}
				// SkyCraft+ : the Nether and the End. The floors laid under Skyrim enemies' bodies
				// (MinecraftEnemies) are for their ragdolls only: Minecraft's player stood on them, out
				// over the edge of the blocks.
				if (a_fixed && State().parked) {
					auto* ref = RE::TESHavokUtilities::FindCollidableRef(collidable);
					auto* refBase = ref ? ref->GetBaseObject() : nullptr;
					if (refBase && refBase->GetFormID() == 0xD06CF && (ref->GetFormID() >> 24) == 0xFF) {
						continue;
					}
				}
				Body body{ shape, xf, {}, {}, IsStairHelper(collidable.GetCollisionLayer()) };
				HkAabbToMc(box, k, body.lo, body.hi);
				if (a_fixed && Dig::IsDiggableCollidable(&collidable)) {
					body.diggable = true;
					body.ref = RE::TESHavokUtilities::FindCollidableRef(collidable);
					body.terrain = !body.ref;
					auto* base = body.ref ? body.ref->GetBaseObject() : nullptr;
					body.tree = collidable.GetCollisionLayer() == RE::COL_LAYER::kTrees || (base && base->GetFormType() == RE::FormType::Tree);
				}
				if (Finite(body.lo, 3) && Finite(body.hi, 3)) {
					bodies_.push_back(body);
				}
			}
		};
		addIsland(a_world->fixedIsland, true);
		for (std::int32_t i = 0; i < a_world->activeSimulationIslands.size(); ++i) {
			addIsland(a_world->activeSimulationIslands.data()[i], false);
		}
		for (std::int32_t i = 0; i < a_world->inactiveSimulationIslands.size(); ++i) {
			addIsland(a_world->inactiveSimulationIslands.data()[i], false);
		}
	}

	void Collision::Harvest(int a_rx, int a_ry, int a_rz)
	{
		Job job{};
		job.rx = a_rx;
		job.ry = a_ry;
		job.rz = a_rz;
		job.epoch = epoch_.load();
		const float margin = 0.25f;
		const float lo[3] = { float(a_rx * kRegionSize) - margin, float(a_ry * kRegionSize) - margin, float(a_rz * kRegionSize) - margin };
		const float hi[3] = { float((a_rx + 1) * kRegionSize) + margin, float((a_ry + 1) * kRegionSize) + margin, float((a_rz + 1) * kRegionSize) + margin };
		static constexpr CollectFn collect = [](void* a_self, const RE::hkpShape* a_shape, const float* a_xf, const float* a_lo, const float* a_hi, void* a_job) {
			static_cast<Collision*>(a_self)->Collect(a_shape, a_xf, a_lo, a_hi, *static_cast<Job*>(a_job), 0, RE::HK_INVALID_SHAPE_KEY);
		};
		Job helpers{};
		for (const auto& body : bodies_) {
			if (!Overlaps(body.lo, body.hi, lo, hi)) {
				continue;
			}
			job.top = body.shape;
			job.ref = body.ref;
			job.diggable = body.diggable;
			job.terrain = body.terrain;
			job.tree = body.tree;
			if (!GuardedCall(collect, this, body.shape, body.xf, lo, hi, body.helper ? &helpers : &job)) {
				if (loggedTypes_.insert(-1).second) {
					logger::warn("collision: faulted reading a Havok shape (type {}); skipping it", static_cast<int>(body.shape->type));
				}
			}
		}
		Triangulate(helpers, job.helperTris);
		std::scoped_lock lock(mutex_);
		queue_.push_back(std::move(job));
		cv_.notify_one();
	}

	std::uint32_t Collision::FlagsFor(const Job& a_job, RE::hkpShapeKey a_key) const
	{
		if (!a_job.diggable) {
			return 0;
		}
		const auto havok = Dig::ShapeMaterial(a_job.top, a_key);
		const auto material = Dig::MaterialFor(havok, a_job.ref, a_job.tree);

		return proto::kTriDiggable | (a_job.terrain ? proto::kTriTerrain : 0) | (std::uint32_t(material) << proto::kTriMaterialShift);
	}

	void Collision::Collect(const RE::hkpShape* a_shape, const float* a_xf, const float a_lo[3], const float a_hi[3], Job& a_job, int a_depth, RE::hkpShapeKey a_key)
	{
		if (!a_shape || a_depth > 8 || a_job.tris.size() > 400000) {
			return;
		}
		const float k = BlocksPerHavok();
		using T = RE::hkpShapeType;
		const auto type = a_shape->type;

		switch (type) {
		case T::kMOPP:
		case T::kBVTree:
			{
				auto* bv = static_cast<const RE::hkpBvTreeShape*>(a_shape);
				// Query box -> Havok world -> shape-local (inverse transform of the 8 corners).
				float hlo[3] = { a_lo[0] / k, -a_hi[2] / k, a_lo[1] / k };
				float hhi[3] = { a_hi[0] / k, -a_lo[2] / k, a_hi[1] / k };
				float llo[3] = { FLT_MAX, FLT_MAX, FLT_MAX }, lhi[3] = { -FLT_MAX, -FLT_MAX, -FLT_MAX };
				for (int c = 0; c < 8; ++c) {
					const float p[3] = { (c & 1) ? hhi[0] : hlo[0], (c & 2) ? hhi[1] : hlo[1], (c & 4) ? hhi[2] : hlo[2] };
					const float d[3] = { p[0] - a_xf[12], p[1] - a_xf[13], p[2] - a_xf[14] };
					for (int i = 0; i < 3; ++i) {
						const float v = a_xf[i * 4] * d[0] + a_xf[i * 4 + 1] * d[1] + a_xf[i * 4 + 2] * d[2];  // R^T d
						llo[i] = std::min(llo[i], v);
						lhi[i] = std::max(lhi[i], v);
					}
				}
				RE::hkAabb local;
				local.min = RE::hkVector4(llo[0], llo[1], llo[2], 0.0f);
				local.max = RE::hkVector4(lhi[0], lhi[1], lhi[2], 0.0f);
				static thread_local std::vector<RE::hkpShapeKey> keys(kMaxKeys);
				const auto found = std::min<std::uint32_t>(bv->QueryAabbImpl(local, keys.data(), kMaxKeys), kMaxKeys);
				const auto* container = bv->GetContainer();
				if (!container) {
					return;
				}
				for (std::uint32_t i = 0; i < found; ++i) {
					RE::hkpShapeBuffer buffer;
					Collect(container->GetChildShape(keys[i], buffer), a_xf, a_lo, a_hi, a_job, a_depth + 1, a_depth == 0 ? keys[i] : a_key);
				}
				return;
			}
		case T::kList:
		case T::kCollection:
		case T::kCompressedMesh:
		case T::kExtendedMesh:
		case T::kTriangleCollection:
		case T::kConvexList:
			{
				const auto* container = a_shape->GetContainer();
				if (!container) {
					EmitAabbFallback(a_shape, a_xf, a_job, a_key);
					return;
				}
				int guard = 0;
				for (auto key = container->GetFirstKey(); key != RE::HK_INVALID_SHAPE_KEY && guard < 200000; key = container->GetNextKey(key), ++guard) {
					RE::hkpShapeBuffer buffer;
					const auto*        child = container->GetChildShape(key, buffer);
					if (!child) {
						continue;
					}
					RE::hkAabb box;
					child->GetAabbImpl(*reinterpret_cast<const RE::hkTransform*>(a_xf), 0.0f, box);
					float lo[3], hi[3];
					HkAabbToMc(box, k, lo, hi);
					if (Overlaps(lo, hi, a_lo, a_hi)) {
						Collect(child, a_xf, a_lo, a_hi, a_job, a_depth + 1, a_depth == 0 ? key : a_key);
					}
				}
				return;
			}
		case T::kTriangle:
			{
				Tri tri{};
				for (int v = 0; v < 3; ++v) {
					float w[3];
					XfPoint(a_xf, Vec(a_shape, 0x30 + v * 0x10), w);
					HkToMc(w, k, tri.v + v * 3);
				}
				if (Finite(tri.v, 9)) {
					tri.flags = FlagsFor(a_job, a_key);
					if (a_job.terrain) {
						// The land's collision carries no material: take it from the texture painted there.
						const auto centre = McToSky((tri.v[0] + tri.v[3] + tri.v[6]) / 3.0, 0.0, (tri.v[2] + tri.v[5] + tri.v[8]) / 3.0);
						const auto id = Dig::LandMaterialAt(centre.x, centre.y);
						static std::atomic<int> logged{ 0 };
						if (logged.fetch_add(1) < 8) {
							logger::info("collision: land material from its texture: {} -> {}", std::uint32_t(id), Dig::MaterialFor(id, nullptr, false));
						}
						if (id != RE::MATERIAL_ID::kNone) {
							tri.flags = proto::kTriDiggable | proto::kTriTerrain | (std::uint32_t(Dig::MaterialFor(id, nullptr, false)) << proto::kTriMaterialShift);
						}
					}
					if (a_job.terrain) {
						// The land is a height field: its outside is up, whatever the winding says.
						const float ux = tri.v[3] - tri.v[0], uz = tri.v[5] - tri.v[2];
						const float wx = tri.v[6] - tri.v[0], wz = tri.v[8] - tri.v[2];
						if (uz * wx - ux * wz < 0.0f) {
							std::swap_ranges(tri.v + 3, tri.v + 6, tri.v + 6);
						}
					}
					a_job.tris.push_back(tri);
				}
				return;
			}
		case T::kBox:
			{
				const float* half = Vec(a_shape, 0x30);
				const float  radius = Field<float>(a_shape, 0x20);
				Obb obb{};
				float center[3];
				XfPoint(a_xf, std::array<float, 3>{ 0, 0, 0 }.data(), center);
				HkToMc(center, k, obb.c);
				for (int i = 0; i < 3; ++i) {
					HkDirToMc(a_xf + i * 4, obb.axis[i]);
					obb.half[i] = (half[i] + radius) * k;
				}
				if (Finite(obb.c, 3) && Finite(obb.half, 3)) {
					obb.flags = FlagsFor(a_job, a_key);
					a_job.boxes.push_back(obb);
				}
				return;
			}
		case T::kCapsule:
		case T::kSphere:
			{
				const float radius = Field<float>(a_shape, 0x20);
				Capsule cap{};
				float   w[3];
				const float zero[3] = { 0, 0, 0 };
				XfPoint(a_xf, type == T::kCapsule ? Vec(a_shape, 0x30) : zero, w);
				HkToMc(w, k, cap.a);
				XfPoint(a_xf, type == T::kCapsule ? Vec(a_shape, 0x40) : zero, w);
				HkToMc(w, k, cap.b);
				cap.r = radius * k;
				if (Finite(cap.a, 3) && Finite(cap.b, 3) && std::isfinite(cap.r) && cap.r < 1000.0f) {
					cap.flags = FlagsFor(a_job, a_key);
					a_job.capsules.push_back(cap);
				}
				return;
			}
		case T::kConvexVertices:
			{
				const auto& planes = *reinterpret_cast<const RE::hkArray<RE::hkVector4>*>(reinterpret_cast<const std::uint8_t*>(a_shape) + 0x78);
				const float radius = Field<float>(a_shape, 0x20);
				if (planes.size() <= 0 || planes.size() > 512) {
					EmitAabbFallback(a_shape, a_xf, a_job, a_key);
					return;
				}
				Convex cvx{};
				RE::hkAabb box;
				a_shape->GetAabbImpl(*reinterpret_cast<const RE::hkTransform*>(a_xf), 0.0f, box);
				HkAabbToMc(box, k, cvx.lo, cvx.hi);
				for (std::int32_t i = 0; i < planes.size(); ++i) {
					alignas(16) float p[4];
					_mm_store_ps(p, planes.data()[i].quad);
					float nw[3];
					XfDir(a_xf, p, nw);
					const float dw = p[3] - (nw[0] * a_xf[12] + nw[1] * a_xf[13] + nw[2] * a_xf[14]) - radius;
					float nm[3];
					HkDirToMc(nw, nm);
					cvx.planes.push_back({ nm[0], nm[1], nm[2], dw * k });
				}
				if (Finite(cvx.lo, 3) && Finite(cvx.hi, 3)) {
					cvx.flags = FlagsFor(a_job, a_key);
					a_job.convexes.push_back(std::move(cvx));
				}
				return;
			}
		case T::kConvexTransform:
		case T::kConvexTranslate:
			{
				const auto* child = Field<const RE::hkpShape*>(a_shape, 0x30);
				alignas(16) float local[16] = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
				if (type == T::kConvexTransform) {
					std::memcpy(local, Vec(a_shape, 0x40), sizeof(local));
				} else {
					std::memcpy(local + 12, Vec(a_shape, 0x40), sizeof(float) * 3);
				}
				if (!child || !XfLooksValid(local)) {
					EmitAabbFallback(a_shape, a_xf, a_job, a_key);
					return;
				}
				alignas(16) float composed[16];
				XfCompose(a_xf, local, composed);
				Collect(child, composed, a_lo, a_hi, a_job, a_depth + 1, a_key);
				return;
			}
		case T::kTransform:
			{
				const auto* child = Field<const RE::hkpShape*>(a_shape, 0x28);
				alignas(16) float local[16];
				std::memcpy(local, Vec(a_shape, 0x50), sizeof(local));
				if (!child || !XfLooksValid(local)) {
					EmitAabbFallback(a_shape, a_xf, a_job, a_key);
					return;
				}
				alignas(16) float composed[16];
				XfCompose(a_xf, local, composed);
				Collect(child, composed, a_lo, a_hi, a_job, a_depth + 1, a_key);
				return;
			}
		default:
			if (loggedTypes_.insert(static_cast<int>(type)).second) {
				logger::info("collision: shape type {} handled as AABB (convex={})", static_cast<int>(type), a_shape->IsConvex());
			}
			if (a_shape->IsConvex()) {
				EmitAabbFallback(a_shape, a_xf, a_job, a_key);
			}
			return;
		}
	}

	void Collision::EmitAabbFallback(const RE::hkpShape* a_shape, const float* a_xf, Job& a_job, RE::hkpShapeKey a_key)
	{
		RE::hkAabb box;
		a_shape->GetAabbImpl(*reinterpret_cast<const RE::hkTransform*>(a_xf), 0.0f, box);
		float lo[3], hi[3];
		HkAabbToMc(box, BlocksPerHavok(), lo, hi);
		if (!Finite(lo, 3) || !Finite(hi, 3) || hi[0] - lo[0] > 64 || hi[1] - lo[1] > 64 || hi[2] - lo[2] > 64) {
			return;
		}
		Obb obb{};
		for (int i = 0; i < 3; ++i) {
			obb.c[i] = (lo[i] + hi[i]) * 0.5f;
			obb.half[i] = (hi[i] - lo[i]) * 0.5f;
			obb.axis[i][0] = obb.axis[i][1] = obb.axis[i][2] = 0.0f;
			obb.axis[i][i] = 1.0f;
		}
		obb.flags = FlagsFor(a_job, a_key);
		a_job.boxes.push_back(obb);
	}

	// ---- worker -----------------------------------------------------------------------------

	void Collision::WorkerLoop()
	{
		while (true) {
			Job job;
			{
				std::unique_lock lock(mutex_);
				cv_.wait(lock, [this] { return !queue_.empty(); });
				job = std::move(queue_.front());
				queue_.pop_front();
			}
			try {
				if (job.clear) {
					{
						std::unique_lock boxes(boxesLock_);
						boxes_.clear();
						++boxesGen_;
					}
					std::vector<std::uint8_t> payload(4);
					std::memcpy(payload.data(), &job.epoch, 4);
					Send(payload, proto::kColClear);
				} else if (job.epoch == epoch_.load()) {
					SendTriangles(job);
					Voxelize(job);
				}
			} catch (const std::exception& e) {
				logger::error("collision worker: {}", e.what());
			}
		}
	}

	void Collision::Send(const std::vector<std::uint8_t>& a_payload, proto::ColType a_type)
	{
		auto& link = Link::Get();
		for (int attempt = 0; attempt < 2000; ++attempt) {
			if (link.WriteCollision(a_type, a_payload.data(), static_cast<std::uint32_t>(a_payload.size()))) {
				return;
			}
			std::this_thread::sleep_for(1ms);  // ring full: Minecraft is behind (or not running)
		}
		logger::warn("collision ring stayed full; dropped a message");
	}

	// Boxes, capsules (as boxes) and convex hulls -> triangles, plus the job's own triangles.
	// Primitives are solid, so their triangles are wound to face outward.
	void Collision::Triangulate(const Job& a_src, std::vector<Tri>& a_out)
	{
		a_out.insert(a_out.end(), a_src.tris.begin(), a_src.tris.end());
		std::uint32_t flags = 0;
		const float*  centre = nullptr;   // outward = away from here
		const float*  outward = nullptr;  // or along this
		auto emit = [&](const float* a, const float* b, const float* c) {
			Tri t{ { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] }, flags };
			float e1[3], e2[3], n[3];
			Sub(b, a, e1);
			Sub(c, a, e2);
			Cross(e1, e2, n);
			float dir[3] = { 0, 0, 0 };
			if (outward) {
				std::memcpy(dir, outward, sizeof(dir));
			} else if (centre) {
				for (int i = 0; i < 3; ++i) {
					dir[i] = (a[i] + b[i] + c[i]) / 3.0f - centre[i];
				}
			}
			if (Dot(n, dir) < 0.0f) {
				std::swap_ranges(t.v + 3, t.v + 6, t.v + 6);
			}
			a_out.push_back(t);
		};
		auto quad = [&](const float* a, const float* b, const float* c, const float* d) {
			emit(a, b, c);
			emit(a, c, d);
		};
		auto box = [&](const float* c, const float (*axis)[3], const float* half) {
			float corner[8][3];
			for (int i = 0; i < 8; ++i) {
				const float sx = (i & 1) ? 1.0f : -1.0f, sy = (i & 2) ? 1.0f : -1.0f, sz = (i & 4) ? 1.0f : -1.0f;
				for (int k = 0; k < 3; ++k) {
					corner[i][k] = c[k] + axis[0][k] * half[0] * sx + axis[1][k] * half[1] * sy + axis[2][k] * half[2] * sz;
				}
			}
			quad(corner[0], corner[1], corner[3], corner[2]);
			quad(corner[4], corner[5], corner[7], corner[6]);
			quad(corner[0], corner[1], corner[5], corner[4]);
			quad(corner[2], corner[3], corner[7], corner[6]);
			quad(corner[0], corner[2], corner[6], corner[4]);
			quad(corner[1], corner[3], corner[7], corner[5]);
		};
		for (const auto& b : a_src.boxes) {
			flags = b.flags;
			centre = b.c;
			box(b.c, b.axis, b.half);
		}
		for (const auto& cap : a_src.capsules) {
			float ab[3];
			Sub(cap.b, cap.a, ab);
			const float len = std::sqrt(Dot(ab, ab));
			float axis[3][3]{};
			if (len > 1e-4f) {
				for (int k = 0; k < 3; ++k) {
					axis[2][k] = ab[k] / len;
				}
			} else {
				axis[2][1] = 1.0f;
			}
			const float ref[3] = { std::fabs(axis[2][1]) < 0.9f ? 0.0f : 1.0f, std::fabs(axis[2][1]) < 0.9f ? 1.0f : 0.0f, 0.0f };
			Cross(ref, axis[2], axis[0]);
			const float l0 = std::sqrt(Dot(axis[0], axis[0]));
			for (int k = 0; k < 3; ++k) {
				axis[0][k] /= l0;
			}
			Cross(axis[2], axis[0], axis[1]);
			const float c[3] = { (cap.a[0] + cap.b[0]) * 0.5f, (cap.a[1] + cap.b[1]) * 0.5f, (cap.a[2] + cap.b[2]) * 0.5f };
			const float half[3] = { cap.r, cap.r, len * 0.5f + cap.r };
			flags = cap.flags;
			centre = c;
			box(c, axis, half);
		}
		// Convex hull from planes: clip a big square on each plane by all the other planes.
		centre = nullptr;
		for (const auto& cvx : a_src.convexes) {
			flags = cvx.flags;
			const float ex = cvx.hi[0] - cvx.lo[0], ey = cvx.hi[1] - cvx.lo[1], ez = cvx.hi[2] - cvx.lo[2];
			const float diag = std::sqrt(ex * ex + ey * ey + ez * ez) + 1.0f;
			const float mid[3] = { (cvx.lo[0] + cvx.hi[0]) * 0.5f, (cvx.lo[1] + cvx.hi[1]) * 0.5f, (cvx.lo[2] + cvx.hi[2]) * 0.5f };
			for (std::size_t i = 0; i < cvx.planes.size(); ++i) {
				const auto& pl = cvx.planes[i];
				const float n[3] = { pl[0], pl[1], pl[2] };
				const float nl = std::sqrt(Dot(n, n));
				if (nl < 1e-6f) {
					continue;
				}
				const float dist = (Dot(n, mid) + pl[3]) / (nl * nl);
				const float o[3] = { mid[0] - n[0] * dist, mid[1] - n[1] * dist, mid[2] - n[2] * dist };
				const float ref[3] = { std::fabs(n[1]) < 0.9f * nl ? 0.0f : 1.0f, std::fabs(n[1]) < 0.9f * nl ? 1.0f : 0.0f, 0.0f };
				float t1[3], t2[3];
				Cross(ref, n, t1);
				const float lt = std::sqrt(Dot(t1, t1));
				for (int k = 0; k < 3; ++k) {
					t1[k] /= lt;
				}
				Cross(n, t1, t2);
				const float l2 = std::sqrt(Dot(t2, t2));
				for (int k = 0; k < 3; ++k) {
					t2[k] /= l2;
				}
				std::vector<std::array<float, 3>> poly;
				const float sgn1[4] = { -1, 1, 1, -1 }, sgn2[4] = { -1, -1, 1, 1 };
				for (int q = 0; q < 4; ++q) {
					const float s1 = sgn1[q] * diag, s2 = sgn2[q] * diag;
					poly.push_back({ o[0] + t1[0] * s1 + t2[0] * s2, o[1] + t1[1] * s1 + t2[1] * s2, o[2] + t1[2] * s1 + t2[2] * s2 });
				}
				for (std::size_t j = 0; j < cvx.planes.size() && poly.size() >= 3; ++j) {
					if (j == i) {
						continue;
					}
					const auto& cp = cvx.planes[j];
					std::vector<std::array<float, 3>> out;
					for (std::size_t v = 0; v < poly.size(); ++v) {
						const auto& A = poly[v];
						const auto& B = poly[(v + 1) % poly.size()];
						const float da = cp[0] * A[0] + cp[1] * A[1] + cp[2] * A[2] + cp[3];
						const float db = cp[0] * B[0] + cp[1] * B[1] + cp[2] * B[2] + cp[3];
						if (da <= 0.0f) {
							out.push_back(A);
						}
						if ((da <= 0.0f) != (db <= 0.0f)) {
							const float t = da / (da - db);
							out.push_back({ A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t });
						}
					}
					poly.swap(out);
				}
				outward = n;
				for (std::size_t v = 1; v + 1 < poly.size(); ++v) {
					emit(poly[0].data(), poly[v].data(), poly[v + 1].data());
				}
				outward = nullptr;
			}
		}
	}

	void Collision::SendTriangles(const Job& a_job)
	{
		std::vector<Tri> solid;
		Triangulate(a_job, solid);
		const float lo[3] = { float(a_job.rx * kRegionSize) - 0.5f, float(a_job.ry * kRegionSize) - 0.5f, float(a_job.rz * kRegionSize) - 0.5f };
		const float hi[3] = { lo[0] + kRegionSize + 1.0f, lo[1] + kRegionSize + 1.0f, lo[2] + kRegionSize + 1.0f };
		std::vector<proto::ColTri> out;
		out.reserve(solid.size() + a_job.helperTris.size());
		auto add = [&](const Tri& a_tri, std::uint32_t a_flags) {
			float tlo[3], thi[3];
			for (int k = 0; k < 3; ++k) {
				tlo[k] = std::min({ a_tri.v[k], a_tri.v[3 + k], a_tri.v[6 + k] });
				thi[k] = std::max({ a_tri.v[k], a_tri.v[3 + k], a_tri.v[6 + k] });
			}
			if (Overlaps(tlo, thi, lo, hi) && Finite(a_tri.v, 9)) {
				proto::ColTri t{};
				std::memcpy(t.v, a_tri.v, sizeof(t.v));
				t.flags = a_flags;
				out.push_back(t);
			}
		};
		// The dug blocks around this region's diggable triangles, merged into boxes once.
		std::vector<Clip::Box> boxes;
		if (Dig::Any()) {
			float dlo[3] = { FLT_MAX, FLT_MAX, FLT_MAX }, dhi[3] = { -FLT_MAX, -FLT_MAX, -FLT_MAX };
			for (const auto& t : solid) {
				if (t.flags & proto::kTriDiggable) {
					for (int k = 0; k < 3; ++k) {
						dlo[k] = std::min({ dlo[k], t.v[k], t.v[3 + k], t.v[6 + k] });
						dhi[k] = std::max({ dhi[k], t.v[k], t.v[3 + k], t.v[6 + k] });
					}
				}
			}
			if (dlo[0] <= dhi[0]) {
				std::vector<Clip::Cube> cubes;
				Dig::Collect(dlo, dhi, cubes);
				boxes = Clip::Merge(cubes);
			}
		}
		std::vector<Clip::Box>  nearBoxes;
		std::vector<Clip::Poly> pieces;
		for (const auto& t : solid) {
			if ((t.flags & proto::kTriDiggable) && !boxes.empty()) {
				float tlo[3], thi[3];
				for (int k = 0; k < 3; ++k) {
					tlo[k] = std::min({ t.v[k], t.v[3 + k], t.v[6 + k] });
					thi[k] = std::max({ t.v[k], t.v[3 + k], t.v[6 + k] });
				}
				nearBoxes.clear();
				for (const auto& b : boxes) {
					if (thi[0] >= b.lo[0] && tlo[0] <= b.hi[0] && thi[1] >= b.lo[1] && tlo[1] <= b.hi[1] && thi[2] >= b.lo[2] && tlo[2] <= b.hi[2]) {
						nearBoxes.push_back(b);
					}
				}
				if (!nearBoxes.empty()) {
					static std::atomic<int> logged{ 0 };
					if (logged.fetch_add(1) < 3) {
						logger::info("collision: cut dug blocks out of a triangle (material {})", (t.flags >> proto::kTriMaterialShift) & 0xFF);
					}
					add(t, t.flags | proto::kTriGhost);  // the surface as it was: what's behind it is solid
					pieces.clear();
					Clip::Subtract(Clip::FromTriangle(t.v, t.v + 3, t.v + 6), nearBoxes, pieces);
					for (const auto& piece : pieces) {
						for (std::size_t v = 1; v + 1 < piece.size(); ++v) {
							Tri part{ { piece[0].p[0], piece[0].p[1], piece[0].p[2], piece[v].p[0], piece[v].p[1], piece[v].p[2], piece[v + 1].p[0], piece[v + 1].p[1],
										  piece[v + 1].p[2] },
								t.flags };
							add(part, part.flags);
						}
					}
					continue;
				}
			}
			add(t, t.flags);
		}
		for (const auto& t : a_job.helperTris) {
			add(t, proto::kTriStairHelper);
		}
		proto::ColRegion header{};
		header.minX = a_job.rx * kRegionSize;
		header.minY = a_job.ry * kRegionSize;
		header.minZ = a_job.rz * kRegionSize;
		header.maxX = header.minX + kRegionSize - 1;
		header.maxY = header.minY + kRegionSize - 1;
		header.maxZ = header.minZ + kRegionSize - 1;
		header.epoch = a_job.epoch;
		header.count = static_cast<std::uint32_t>(out.size());
		std::vector<std::uint8_t> payload(sizeof(header) + out.size() * sizeof(proto::ColTri));
		std::memcpy(payload.data(), &header, sizeof(header));
		if (!out.empty()) {
			std::memcpy(payload.data() + sizeof(header), out.data(), out.size() * sizeof(proto::ColTri));
		}
		Send(payload, proto::kColTris);
	}

	void Collision::Voxelize(const Job& a_job)
	{
		constexpr int G = kGrid;
		std::vector<std::uint64_t> solid(G * G, 0), steep(G * G, 0);
		std::vector<std::uint64_t> digSolid(G * G, 0), digSteep(G * G, 0);  // diggable geometry
		auto set = [&](std::vector<std::uint64_t>& a_grid, int x, int y, int z) { a_grid[y * G + z] |= 1ull << x; };

		const float ox = float(a_job.rx * kRegionSize), oy = float(a_job.ry * kRegionSize), oz = float(a_job.rz * kRegionSize);
		auto toVoxel = [&](const float* a_mc, float* a_out) {
			a_out[0] = (a_mc[0] - ox) * 8.0f;
			a_out[1] = (a_mc[1] - oy) * 8.0f;
			a_out[2] = (a_mc[2] - oz) * 8.0f;
		};
		auto clampLo = [](float v) { return std::clamp(static_cast<int>(std::floor(v)), 0, G - 1); };
		auto clampHi = [](float v) { return std::clamp(static_cast<int>(std::ceil(v)) - 1, 0, G - 1); };

		// Triangles: plane-guided SAT test so big triangles cost O(area) instead of O(volume).
		for (const auto& tri : a_job.tris) {
			float a[3], b[3], c[3];
			toVoxel(tri.v, a);
			toVoxel(tri.v + 3, b);
			toVoxel(tri.v + 6, c);
			float lo[3], hi[3];
			for (int i = 0; i < 3; ++i) {
				lo[i] = std::min({ a[i], b[i], c[i] });
				hi[i] = std::max({ a[i], b[i], c[i] });
			}
			if (hi[0] < 0 || hi[1] < 0 || hi[2] < 0 || lo[0] > G || lo[1] > G || lo[2] > G) {
				continue;
			}
			float e1[3], e2[3], n[3];
			Sub(b, a, e1);
			Sub(c, a, e2);
			Cross(e1, e2, n);
			const float len = std::sqrt(Dot(n, n));
			if (len < 1e-9f) {
				continue;
			}
			n[0] /= len, n[1] /= len, n[2] /= len;
			const float ny = std::fabs(n[1]);
			const bool  flat = ny >= kSteepMax || ny < kSteepMin;
			auto&       grid = (tri.flags & proto::kTriDiggable) ? (flat ? digSolid : digSteep) : (flat ? solid : steep);

			int dom = 0;
			if (std::fabs(n[1]) > std::fabs(n[dom])) dom = 1;
			if (std::fabs(n[2]) > std::fabs(n[dom])) dom = 2;
			const int   u = (dom + 1) % 3, v = (dom + 2) % 3;
			const float d = Dot(n, a);
			const float r = 0.5f * (std::fabs(n[0]) + std::fabs(n[1]) + std::fabs(n[2]));
			const int   iu0 = clampLo(lo[u]), iu1 = clampHi(hi[u]), iv0 = clampLo(lo[v]), iv1 = clampHi(hi[v]);
			const int   id0 = clampLo(lo[dom]), id1 = clampHi(hi[dom]);
			for (int iu = iu0; iu <= iu1; ++iu) {
				for (int iv = iv0; iv <= iv1; ++iv) {
					const float cu = iu + 0.5f, cv = iv + 0.5f;
					const float s0 = (d - r - n[u] * cu - n[v] * cv) / n[dom];
					const float s1 = (d + r - n[u] * cu - n[v] * cv) / n[dom];
					int         a0 = std::max(id0, static_cast<int>(std::floor(std::min(s0, s1) - 0.5f)));
					int         a1 = std::min(id1, static_cast<int>(std::ceil(std::max(s0, s1) - 0.5f)));
					for (int id = a0; id <= a1; ++id) {
						float cen[3];
						cen[dom] = id + 0.5f;
						cen[u] = cu;
						cen[v] = cv;
						if (TriBoxOverlap(cen, 0.5f, a, b, c, n)) {
							int p[3];
							p[dom] = id, p[u] = iu, p[v] = iv;
							set(grid, p[0], p[1], p[2]);
						}
					}
				}
			}
		}

		// Convex primitives: voxel-center containment with a small margin.
		auto fillPrimitive = [&](const float* a_lo, const float* a_hi, std::uint32_t a_flags, auto&& a_inside) {
			auto& grid = (a_flags & proto::kTriDiggable) ? digSolid : solid;
			float lo[3], hi[3];
			toVoxel(a_lo, lo);
			toVoxel(a_hi, hi);
			if (hi[0] < 0 || hi[1] < 0 || hi[2] < 0 || lo[0] > G || lo[1] > G || lo[2] > G) {
				return;
			}
			for (int y = clampLo(lo[1] - 1); y <= clampHi(hi[1] + 1); ++y) {
				for (int z = clampLo(lo[2] - 1); z <= clampHi(hi[2] + 1); ++z) {
					for (int x = clampLo(lo[0] - 1); x <= clampHi(hi[0] + 1); ++x) {
						const float p[3] = { ox + (x + 0.5f) / 8.0f, oy + (y + 0.5f) / 8.0f, oz + (z + 0.5f) / 8.0f };
						if (a_inside(p)) {
							set(grid, x, y, z);
						}
					}
				}
			}
		};
		constexpr float m = kPrimMargin / 8.0f;
		for (const auto& box : a_job.boxes) {
			float lo[3], hi[3];
			for (int i = 0; i < 3; ++i) {
				const float ext = std::fabs(box.axis[0][i]) * box.half[0] + std::fabs(box.axis[1][i]) * box.half[1] + std::fabs(box.axis[2][i]) * box.half[2];
				lo[i] = box.c[i] - ext;
				hi[i] = box.c[i] + ext;
			}
			fillPrimitive(lo, hi, box.flags, [&](const float* p) {
				const float d[3] = { p[0] - box.c[0], p[1] - box.c[1], p[2] - box.c[2] };
				for (int i = 0; i < 3; ++i) {
					if (std::fabs(Dot(d, box.axis[i])) > box.half[i] + m) {
						return false;
					}
				}
				return true;
			});
		}
		for (const auto& cap : a_job.capsules) {
			float lo[3], hi[3];
			for (int i = 0; i < 3; ++i) {
				lo[i] = std::min(cap.a[i], cap.b[i]) - cap.r;
				hi[i] = std::max(cap.a[i], cap.b[i]) + cap.r;
			}
			fillPrimitive(lo, hi, cap.flags, [&](const float* p) {
				float ab[3], ap[3];
				Sub(cap.b, cap.a, ab);
				Sub(p, cap.a, ap);
				const float len2 = Dot(ab, ab);
				const float t = len2 > 0 ? std::clamp(Dot(ap, ab) / len2, 0.0f, 1.0f) : 0.0f;
				const float q[3] = { cap.a[0] + ab[0] * t - p[0], cap.a[1] + ab[1] * t - p[1], cap.a[2] + ab[2] * t - p[2] };
				return Dot(q, q) <= (cap.r + m) * (cap.r + m);
			});
		}
		for (const auto& cvx : a_job.convexes) {
			fillPrimitive(cvx.lo, cvx.hi, cvx.flags, [&](const float* p) {
				for (const auto& pl : cvx.planes) {
					if (pl[0] * p[0] + pl[1] * p[1] + pl[2] * p[2] + pl[3] > m) {
						return false;
					}
				}
				return true;
			});
		}

		// Steep (50-84 degree) surfaces: snap to whole-block footprints so the risers between
		// neighbouring columns exceed MC's 0.6 step height. Minecraft's own step-up/jump rules
		// then decide what is climbable, like a cliff made of blocks.
		auto coarsen = [&](const std::vector<std::uint64_t>& a_steep, std::vector<std::uint64_t>& a_solid) {
			for (int by = 0; by < kRegionSize; ++by) {
				for (int bz = 0; bz < kRegionSize; ++bz) {
					for (int bx = 0; bx < kRegionSize; ++bx) {
						const std::uint64_t xmask = 0xFFull << (bx * 8);
						int                 minY = 99, maxY = -1;
						for (int y = by * 8; y < by * 8 + 8; ++y) {
							for (int z = bz * 8; z < bz * 8 + 8; ++z) {
								if (a_steep[y * G + z] & xmask) {
									minY = std::min(minY, y);
									maxY = std::max(maxY, y);
								}
							}
						}
						if (maxY < 0) {
							continue;
						}
						for (int y = minY; y <= maxY; ++y) {
							for (int z = bz * 8; z < bz * 8 + 8; ++z) {
								a_solid[y * G + z] |= xmask;
							}
						}
					}
				}
			}
		};
		coarsen(steep, solid);
		coarsen(digSteep, digSolid);

		// Dug blocks: the diggable geometry in them is gone.
		if (Dig::Any()) {
			const float rlo[3] = { ox + 0.5f, oy + 0.5f, oz + 0.5f };
			const float rhi[3] = { ox + kRegionSize - 0.5f, oy + kRegionSize - 0.5f, oz + kRegionSize - 0.5f };
			std::vector<Clip::Cube> dug;
			Dig::Collect(rlo, rhi, dug);
			for (const auto& cube : dug) {
				const int bx = cube[0] - int(ox), by = cube[1] - int(oy), bz = cube[2] - int(oz);
				if (bx < 0 || by < 0 || bz < 0 || bx >= kRegionSize || by >= kRegionSize || bz >= kRegionSize) {
					continue;
				}
				const std::uint64_t keep = ~(0xFFull << (bx * 8));
				for (int y = by * 8; y < by * 8 + 8; ++y) {
					for (int z = bz * 8; z < bz * 8 + 8; ++z) {
						digSolid[y * G + z] &= keep;
					}
				}
			}
		}
		for (std::size_t i = 0; i < solid.size(); ++i) {
			solid[i] |= digSolid[i];
		}

		// Pack non-empty blocks, and keep the box around each one's voxels (contact shadows).
		std::array<std::uint32_t, 512> boxes{};
		std::vector<proto::ColBlock> blocks;
		blocks.reserve(64);
		for (int by = 0; by < kRegionSize; ++by) {
			for (int bz = 0; bz < kRegionSize; ++bz) {
				for (int bx = 0; bx < kRegionSize; ++bx) {
					proto::ColBlock blk{};
					bool            any = false;
					for (int sy = 0; sy < 8; ++sy) {
						std::uint64_t layer = 0;
						for (int sz = 0; sz < 8; ++sz) {
							const auto row = (solid[(by * 8 + sy) * G + (bz * 8 + sz)] >> (bx * 8)) & 0xFF;
							layer |= row << (sz * 8);
						}
						blk.bits[sy] = layer;
						any |= layer != 0;
					}
					if (any) {
						int      lo[3] = { 8, 8, 8 }, hi[3] = { -1, -1, -1 };
						std::uint64_t columns = 0;
						for (int sy = 0; sy < 8; ++sy) {
							if (blk.bits[sy]) {
								lo[1] = std::min(lo[1], sy);
								hi[1] = std::max(hi[1], sy);
								columns |= blk.bits[sy];
							}
						}
						std::uint32_t rows = 0;
						for (int sz = 0; sz < 8; ++sz) {
							const auto row = static_cast<std::uint32_t>((columns >> (sz * 8)) & 0xFF);
							if (row) {
								lo[2] = std::min(lo[2], sz);
								hi[2] = std::max(hi[2], sz);
								rows |= row;
							}
						}
						for (int sx = 0; sx < 8; ++sx) {
							if (rows & (1u << sx)) {
								lo[0] = std::min(lo[0], sx);
								hi[0] = std::max(hi[0], sx);
							}
						}
						boxes[bx + 8 * (by + 8 * bz)] = kBoxPresent | std::uint32_t(lo[0]) << 2 | std::uint32_t(lo[1]) << 5 | std::uint32_t(lo[2]) << 8 |
						                                std::uint32_t(hi[0]) << 11 | std::uint32_t(hi[1]) << 14 | std::uint32_t(hi[2]) << 17;
						blk.x = a_job.rx * kRegionSize + bx;
						blk.y = a_job.ry * kRegionSize + by;
						blk.z = a_job.rz * kRegionSize + bz;
						blocks.push_back(blk);
					}
				}
			}
		}

		{
			std::unique_lock lock(boxesLock_);
			const auto key = RegionKey(a_job.rx, a_job.ry, a_job.rz);
			const bool empty = std::ranges::all_of(boxes, [](std::uint32_t b) { return b == 0; });
			const auto it = boxes_.find(key);
			if (empty ? it != boxes_.end() : it == boxes_.end() || it->second != boxes) {
				if (empty) {
					boxes_.erase(it);
				} else {
					boxes_[key] = boxes;
				}
				++boxesGen_;
			}
			if (boxes_.size() > 4096) {  // about 8 MB: forget what's far from here
				auto unpack = [](std::uint64_t a_key, int a_shift) {
					const int v = static_cast<int>((a_key >> a_shift) & 0x1FFFFF);
					return v >= 0x100000 ? v - 0x200000 : v;
				};
				std::erase_if(boxes_, [&](const auto& a_entry) {
					return std::abs(unpack(a_entry.first, 42) - a_job.rx) > 12 || std::abs(unpack(a_entry.first, 21) - a_job.ry) > 12 ||
					       std::abs(unpack(a_entry.first, 0) - a_job.rz) > 12;
				});
			}
		}

		proto::ColRegion header{};
		header.minX = a_job.rx * kRegionSize;
		header.minY = a_job.ry * kRegionSize;
		header.minZ = a_job.rz * kRegionSize;
		header.maxX = header.minX + kRegionSize - 1;
		header.maxY = header.minY + kRegionSize - 1;
		header.maxZ = header.minZ + kRegionSize - 1;
		header.epoch = a_job.epoch;
		header.count = static_cast<std::uint32_t>(blocks.size());
		std::vector<std::uint8_t> payload(sizeof(header) + blocks.size() * sizeof(proto::ColBlock));
		std::memcpy(payload.data(), &header, sizeof(header));
		if (!blocks.empty()) {
			std::memcpy(payload.data() + sizeof(header), blocks.data(), blocks.size() * sizeof(proto::ColBlock));
		}
		Send(payload, proto::kColRegion);
	}

	void Collision::DigChanged(const std::vector<std::array<int, 3>>& a_blocks)
	{
		auto floorDiv = [](int v) { return v >= 0 ? v / kRegionSize : -((-v + kRegionSize - 1) / kRegionSize); };
		std::unordered_set<std::uint64_t> queued;
		for (const auto& r : urgent_) {
			queued.insert(RegionKey(r[0], r[1], r[2]));
		}
		for (const auto& b : a_blocks) {
			// Triangles are sent with every region they come within half a block of.
			for (int rx = floorDiv(b[0] - 1); rx <= floorDiv(b[0] + 1); ++rx) {
				for (int ry = floorDiv(b[1] - 1); ry <= floorDiv(b[1] + 1); ++ry) {
					for (int rz = floorDiv(b[2] - 1); rz <= floorDiv(b[2] + 1); ++rz) {
						if (queued.insert(RegionKey(rx, ry, rz)).second) {
							urgent_.push_back({ rx, ry, rz });
						}
					}
				}
			}
		}
	}

	void Collision::CopyBoxes(const std::int32_t a_origin[3], const std::int32_t a_size[3], std::uint32_t* a_out) const
	{
		auto floorDiv = [](int v) { return v >= 0 ? v / kRegionSize : -((-v + kRegionSize - 1) / kRegionSize); };
		const int x0 = a_origin[0], y0 = a_origin[1], z0 = a_origin[2];
		const int w = a_size[0], h = a_size[1], d = a_size[2];
		std::shared_lock lock(boxesLock_);
		for (int ry = floorDiv(y0); ry <= floorDiv(y0 + h - 1); ++ry) {
			for (int rz = floorDiv(z0); rz <= floorDiv(z0 + d - 1); ++rz) {
				for (int rx = floorDiv(x0); rx <= floorDiv(x0 + w - 1); ++rx) {
					const auto it = boxes_.find(RegionKey(rx, ry, rz));
					if (it == boxes_.end()) {
						continue;
					}
					for (int by = 0; by < kRegionSize; ++by) {
						const int y = ry * kRegionSize + by - y0;
						if (y < 0 || y >= h) {
							continue;
						}
						for (int bz = 0; bz < kRegionSize; ++bz) {
							const int z = rz * kRegionSize + bz - z0;
							if (z < 0 || z >= d) {
								continue;
							}
							for (int bx = 0; bx < kRegionSize; ++bx) {
								const int x = rx * kRegionSize + bx - x0;
								if (x >= 0 && x < w) {
									a_out[x + w * (y + h * z)] |= it->second[bx + kRegionSize * (by + kRegionSize * bz)];
								}
							}
						}
					}
				}
			}
		}
	}
}
