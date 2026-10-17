#pragma once

#include "Link.h"

namespace skycraft
{
	// Streams Skyrim's Havok collision around the player to Minecraft as 1/8-block voxels.
	//
	// Main thread: picks which 8x8x8-block regions need (re)sending, walks the Havok world under
	// its read lock and copies out every triangle / convex primitive touching the region.
	// Worker thread: voxelizes those primitives and writes the result to the collision ring.
	class Collision
	{
	public:
		static Collision& Get();

		void Start();
		// Drops everything; Minecraft clears its store when it sees the new epoch.
		void Reset(std::uint32_t a_epoch);
		// Called once per frame with the player's position in MC coordinates.
		void Update(const McVec& a_playerMc);

		static constexpr int kRegionSize = 8;  // blocks per region edge (must match Java)

		// Skyrim's geometry per Minecraft block, kept for the blocks' contact shadows: the box
		// around the block's solid voxels, packed as kBoxPresent | lo x, y, z | hi x, y, z (3 bits
		// each, eighths of a block, hi inclusive) from bit 2 up; 0 where there's nothing. ORs the
		// boxes of the box at a_origin (Minecraft block coords) of a_size blocks into
		// a_out[x + w*(y + h*z)]. Any thread. The generation changes whenever any box does.
		static constexpr std::uint32_t kBoxPresent = 2;
		void          CopyBoxes(const std::int32_t a_origin[3], const std::int32_t a_size[3], std::uint32_t* a_out) const;
		std::uint32_t BoxesGeneration() const { return boxesGen_.load(); }

		// Blocks dug out of (or back into) Skyrim's world: the regions around them are sent again,
		// ahead of everything else, without Skyrim's diggable geometry in dug blocks. Main thread.
		void DigChanged(const std::vector<std::array<int, 3>>& a_blocks);

	private:
		// flags: proto::ColTriFlags (kTriDiggable, the DigMaterial in bits 8-15).
		struct Tri
		{
			float         v[9];  // three vertices, MC space
			std::uint32_t flags{ 0 };
		};
		struct Obb
		{
			float         c[3];
			float         axis[3][3];  // unit axes, MC space
			float         half[3];     // half extents incl. convex radius, blocks
			std::uint32_t flags{ 0 };
		};
		struct Capsule
		{
			float         a[3], b[3];
			float         r;
			std::uint32_t flags{ 0 };
		};
		struct Convex
		{
			std::vector<std::array<float, 4>> planes;  // n.p + d <= 0 inside, MC space
			float                             lo[3], hi[3];
			std::uint32_t                     flags{ 0 };
		};
		struct Job
		{
			int                  rx, ry, rz;
			std::uint32_t        epoch;
			bool                 clear{ false };
			std::vector<Tri>     tris;
			std::vector<Obb>     boxes;
			std::vector<Capsule> capsules;
			std::vector<Convex>  convexes;
			std::vector<Tri>     helperTris;  // stair ramps: sent as triangles only, never voxelized

			// While collecting one body's shapes.
			const RE::hkpShape*  top{ nullptr };
			RE::TESObjectREFR*   ref{ nullptr };
			bool                 diggable{ false };
			bool                 terrain{ false };
			bool                 tree{ false };
		};
		struct Body
		{
			const RE::hkpShape*     shape;
			const float*            xf;  // hkTransform (column-major rotation + translation)
			float                   lo[3], hi[3];  // world AABB, MC space
			bool                    helper;        // stair helper (triangles only)
			bool                    diggable{ false };
			bool                    terrain{ false };
			bool                    tree{ false };
			RE::TESObjectREFR*      ref{ nullptr };
		};

		void GatherBodies(RE::hkpWorld* a_world);
		void Harvest(int a_rx, int a_ry, int a_rz);
		void Collect(const RE::hkpShape* a_shape, const float* a_xf, const float a_lo[3], const float a_hi[3], Job& a_job, int a_depth, RE::hkpShapeKey a_key);
		void EmitAabbFallback(const RE::hkpShape* a_shape, const float* a_xf, Job& a_job, RE::hkpShapeKey a_key);
		std::uint32_t FlagsFor(const Job& a_job, RE::hkpShapeKey a_key) const;

		void WorkerLoop();
		void Voxelize(const Job& a_job);
		void SendTriangles(const Job& a_job);
		static void Triangulate(const Job& a_src, std::vector<Tri>& a_out);
		void Send(const std::vector<std::uint8_t>& a_payload, proto::ColType a_type);

		std::mutex              mutex_;
		std::condition_variable cv_;
		std::deque<Job>         queue_;
		std::thread             worker_;

		std::atomic<std::uint32_t>                                     epoch_{ 0 };
		std::unordered_map<std::uint64_t, std::chrono::steady_clock::time_point> harvested_;
		std::vector<std::array<int, 3>>                                urgent_;  // regions to send again first
		// SkyCraft+ : opened doors. Bounds of things that can move (doors, gates, drawbridges).
		struct Mover
		{
			RE::NiPoint3 centre;
			float        radius{ 0.0f };
			bool         moving{ false };
			float        movingFor{ 0.0f };  // seconds; one that never stops (a banner) is let be
			bool         solid{ false };     // has collision
		};
		void WatchMovers(RE::PlayerCharacter* a_player);
		void QueueRegions(const float* a_lo, const float* a_hi);
		std::unordered_map<RE::FormID, Mover>                          movers_;
		std::chrono::steady_clock::time_point                          moversAt_{};
		std::vector<Body>                                              bodies_;
		std::vector<std::array<int, 3>>                                offsets_;
		std::unordered_set<int>                                        loggedTypes_;

		mutable std::shared_mutex                                            boxesLock_;
		std::unordered_map<std::uint64_t, std::array<std::uint32_t, 512>>    boxes_;  // region -> bx + 8*(by + 8*bz)
		std::atomic<std::uint32_t>                                           boxesGen_{ 0 };
	};
}
