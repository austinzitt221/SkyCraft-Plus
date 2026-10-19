#include "Dig.h"
#include "Game.h"
#include "Perf.h"

#include <SimpleIni.h>

// Skyrim's NPCs in a dug-up world. Every actor moves with a Havok character proxy: it collects
// contact points with the world (bhkCharacterPointCollector), turns them into surface planes, and
// solves its movement against those planes. Two hooks:
//  - contact points on diggable Skyrim geometry inside a dug block are dropped, so the ground
//    that was dug away holds nobody up any more (they fall into holes);
//  - Minecraft's solid blocks are added as surface planes, so NPCs stand on them (the floor of a
//    hole, a pillar, a bridge) and bump into them, like any Skyrim surface.
// Reverse-engineered on 1.7.104: hkpCharacterProxy::integrate (0x140B9E4E0) and checkSupport
// (0x140B9D340) both size the solver's plane arrays for the manifold plus the proxy's userPlanes
// (+0xB4) extra planes, then call every listener's processConstraintsCallback (0x140BA0990), which
// may add up to userPlanes planes. Skyrim's own surface planes (0x140BA04D0): the contact normal,
// distance minus keepDistance (+0xA8), the proxy's frictions, and a push out of penetration at
// penetrationRecoverySpeed (+0xDC).
namespace skycraft
{
	namespace BlockCrates
	{
		RE::FormID BaseForm();  // BlockCrates.cpp
	}
}

namespace skycraft::Dig
{
	namespace
	{
		constexpr float kIntoSurface = 0.05f;   // blocks: a contact point belongs to the block behind it
		constexpr float kPlaneReach = 0.6f;     // Havok units: blocks this close to the proxy become planes
		constexpr int   kWantUserPlanes = 16;   // planes each proxy may take (Havok's default: 4)

		float BlocksPerHavok()
		{
			return RE::bhkWorld::GetWorldScaleInverse() / static_cast<float>(proto::kUnitsPerBlock);
		}

		const RE::hkpCollidable* Root(const RE::hkpCdBody* a_body)
		{
			while (a_body && a_body->parent) {
				a_body = a_body->parent;
			}
			return static_cast<const RE::hkpCollidable*>(a_body);
		}

		bool IsCharacter(const RE::hkpCollidable* a_collidable)
		{
			return a_collidable && a_collidable->GetCollisionLayer() == RE::COL_LAYER::kCharController;
		}

		// Is this contact on diggable Skyrim geometry in a dug block?
		bool OnDugGround(const RE::hkpCdPoint& a_point)
		{
			const auto* rootA = Root(a_point.cdBodyA);
			const auto* rootB = Root(a_point.cdBodyB);
			// The world object is B for a character's own queries; allow either way round.
			const RE::hkpCollidable* world = IsCharacter(rootA) ? rootB : rootA;
			alignas(16) float p[4], n[4];
			_mm_store_ps(p, a_point.contact.position.quad);
			_mm_store_ps(n, a_point.contact.separatingNormal.quad);
			const float sign = world == rootB ? 1.0f : -1.0f;  // the normal points out of B
			const float k = BlocksPerHavok();
			// Havok (x, y, z up) -> Minecraft (x, y up, z = -y), nudged into the surface.
			const float mx = (p[0] - n[0] * sign * kIntoSurface) * k;
			const float my = (p[2] - n[2] * sign * kIntoSurface) * k;
			const float mz = -(p[1] - n[1] * sign * kIntoSurface) * k;
			if (!IsDug(std::int32_t(std::floor(mx)), std::int32_t(std::floor(my)), std::int32_t(std::floor(mz)))) {
				return false;
			}
			return IsDiggableCollidable(world);
		}

		std::atomic<int> loggedDropped{ 0 };
		std::atomic<int> loggedPlanes{ 0 };
		std::atomic<bool> loggedPlayerBox{ false };
		// SkyCraft+ : the player's controller last stood on a Minecraft block at this tick.
		std::atomic<RE::bhkCharacterController*> blockGroundController{ nullptr };
		std::atomic<std::uint64_t>               blockGroundTick{ 0 };
		std::atomic<const RE::hkpCollidable*> puppet{ nullptr };
		// The player's proxy in Skyrim's own control mode. Minecraft's blocks are real crates there (BlockCrates.cpp), so Havok's own contacts
		// are all the player gets: no planes of ours, no holding it up by hand.
		std::atomic<const RE::hkpCollidable*> skyPlayer{ nullptr };
		std::atomic<float>                    puppetFeet{ 0.0f };
		constexpr float                       kFeetSlack = 0.3f;  // Havok units above the feet still "under" them

		// The player's body while Minecraft drives it: a contact above its feet (the ground over a
		// hole or tunnel it's in, a wall at its head) would only push it away from where Minecraft
		// put it, so Skyrim would think it moved the player and pull Minecraft along.
		bool AboveThePuppetsFeet(const RE::hkpCdPoint& a_point)
		{
			const auto* body = puppet.load(std::memory_order_relaxed);
			if (!body || (Root(a_point.cdBodyA) != body && Root(a_point.cdBodyB) != body)) {
				return false;
			}
			alignas(16) float p[4];
			_mm_store_ps(p, a_point.contact.position.quad);
			return p[2] > puppetFeet.load(std::memory_order_relaxed) + kFeetSlack;
		}

		struct AddCdPointHook
		{
			static void thunk(RE::bhkCharacterPointCollector* a_this, const RE::hkpCdPoint& a_point)
			{
				Perf::Scope timer(Perf::kHookCharContacts);
				if (Any() && OnDugGround(a_point)) {
					if (loggedDropped.fetch_add(1) < 3) {
						logger::info("dig: an actor's contact with dug-away ground dropped");
					}
					return;
				}
				if (AboveThePuppetsFeet(a_point)) {
					return;
				}
				func(a_this, a_point);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// Havok's plain collector: a character's support check refreshes its contacts with one
		// (checkSupport -> 0x140B9DBF0 on 1.7.104), and so do other shape queries. Without this an
		// NPC over a hole still stands on the dug-away ground: it walks on air.
		struct AllCdPointHook
		{
			static void thunk(RE::hkpAllCdPointCollector* a_this, const RE::hkpCdPoint& a_point)
			{
				Perf::Scope timer(Perf::kHookContacts);
				if (Any() && OnDugGround(a_point)) {
					return;
				}
				func(a_this, a_point);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		struct Plane
		{
			float n[3];
			float d;  // gap between the proxy and the block (negative: overlapping)
			// Diagnostics: the block it came from, and how (f full block, p part block; r ramp, i inside).
			std::int32_t src[3]{};
			char         from = 0, how = 0;
		};

		// SkyCraft+ : exact block shapes. Low ledges (slabs, stair steps, carpets) get a walkable
		// ramp up to their top, like the invisible ramps over Skyrim's own stairs: a character
		// proxy can't step up by itself.
		constexpr float kStepMin = 0.02f;         // blocks: lower than this is just ground
		constexpr float kStepMax = 0.55f;         // blocks: Minecraft steps up to 0.6
		constexpr float kRampSin = 0.6428f;       // a 40 degree ramp: walkable for Skyrim's characters
		constexpr float kRampCos = 0.7660f;
		constexpr float kPlayerHalfWidth = 0.3f;  // blocks: Minecraft's player is 0.6 wide
		constexpr float kPlayerHeight = 1.8f;     // blocks: and 1.8 tall (Skyrim's is 1.93: two-block doorways)

		// A block or one box of a block's shape, in Havok space; seam: which faces (+x -x +y -y +z
		// -z) are shared with a full neighbouring block (inside the wall).
		struct BlockBox
		{
			float xl, xh, yl, yh, zl, zh;
			bool  seam[6];
		};

		bool FullAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			return NpcBlocks::ShapeAt(a_x, a_y, a_z, nullptr) == 0;
		}

		// Minecraft's solid blocks around a proxy, as the planes Havok would make for them. A
		// vertical capsule stands in for the proxy's shape (its box). Blocks that aren't full cubes
		// (stairs, slabs, doors, fences) count with their real boxes. a_ramps: walkable ramps up low
		// ledges. a_maxHalfWidth, a_maxHeight (Havok units): smaller than the proxy (the player, so it fits where
		// Minecraft's player does).
		void BlockPlanes(const RE::hkAabb& a_box, const float* a_centre, std::vector<Plane>& a_out, bool a_ramps, float a_maxHalfWidth, float a_maxHeight)
		{
			alignas(16) float lo[4], hi[4];
			_mm_store_ps(lo, a_box.min.quad);
			_mm_store_ps(hi, a_box.max.quad);
			// The body's axis: its position if given (the player's shape isn't round, so its box's centre
			// moves as it turns, and a wall it stood clear of would suddenly be overlapping), else the box's centre.
			const float cx = a_centre ? a_centre[0] : (lo[0] + hi[0]) * 0.5f, cy = a_centre ? a_centre[1] : (lo[1] + hi[1]) * 0.5f;
			const float r = std::max(0.05f, std::min({ hi[0] - lo[0], hi[1] - lo[1], 2.0f * a_maxHalfWidth }) * 0.5f);
			float       zBot = lo[2] + r, zTop = std::min(hi[2], lo[2] + a_maxHeight) - r;
			if (zTop < zBot) {
				zBot = zTop = (lo[2] + hi[2]) * 0.5f;
			}
			const float k = BlocksPerHavok();
			const float feet = zBot - r;
			// Havok box -> Minecraft blocks: x = hx, y = hz, z = -hy.
			const int x0 = int(std::floor((lo[0] - kPlaneReach) * k)), x1 = int(std::floor((hi[0] + kPlaneReach) * k));
			const int y0 = int(std::floor((lo[2] - kPlaneReach) * k)), y1 = int(std::floor((hi[2] + kPlaneReach) * k));
			const int z0 = int(std::floor(-(hi[1] + kPlaneReach) * k)), z1 = int(std::floor(-(lo[1] - kPlaneReach) * k));
			if ((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 512) {
				return;  // something huge (a dragon): not worth it
			}

			std::int32_t src[3]{};  // diagnostics: the block being added, part or full, and how
			char         from = 0, how = 0;
			auto add = [&](Plane a_plane) {
				a_plane.src[0] = src[0], a_plane.src[1] = src[1], a_plane.src[2] = src[2];
				a_plane.from = from, a_plane.how = how;
				if (a_plane.d > kPlaneReach) {
					return;
				}
				for (const auto& other : a_out) {
					if (std::fabs(other.d - a_plane.d) < 1e-3f && other.n[0] * a_plane.n[0] + other.n[1] * a_plane.n[1] + other.n[2] * a_plane.n[2] > 0.9999f) {
						return;
					}
				}
				a_out.push_back(a_plane);
			};

			auto addBox = [&](const BlockBox& b, bool a_freeAbove) {
				float d[3];
				d[0] = cx - std::clamp(cx, b.xl, b.xh);
				d[1] = cy - std::clamp(cy, b.yl, b.yh);
				d[2] = zTop < b.zl ? zTop - b.zl : (zBot > b.zh ? zBot - b.zh : 0.0f);
				// Faces shared with another solid block are inside the wall: no edge planes at the
				// seams, or walking over a block floor would snag. Two seams at once (a floor
				// block's side and its top) put the nearest point in the block diagonally between
				// those neighbours: only if that one is solid too. Next to a doorway it isn't, and
				// the edge of a floor block a step away became a wall right in front of the player.
				bool drop[3] = { (d[0] > 0 && b.seam[0]) || (d[0] < 0 && b.seam[1]), (d[1] > 0 && b.seam[2]) || (d[1] < 0 && b.seam[3]),
					(d[2] > 0 && b.seam[4]) || (d[2] < 0 && b.seam[5]) };
				// Havok +x, +y, +z = Minecraft +x, -z, +y.
				const int  ox = drop[0] ? (d[0] > 0 ? 1 : -1) : 0, oz = drop[1] ? (d[1] > 0 ? -1 : 1) : 0, oy = drop[2] ? (d[2] > 0 ? 1 : -1) : 0;
				const int  dropped = int(drop[0]) + int(drop[1]) + int(drop[2]);
				if (dropped == 1 || (dropped > 1 && FullAt(src[0] + ox, src[1] + oy, src[2] + oz))) {
					for (int i = 0; i < 3; ++i) {
						if (drop[i]) {
							d[i] = 0;
						}
					}
				}
				const float hlen = std::sqrt(d[0] * d[0] + d[1] * d[1]);
				const float step = b.zh - feet;
				if (a_ramps && a_freeAbove && hlen > 1e-5f && step > kStepMin / k && step <= kStepMax / k) {
					// Beside a low ledge: a ramp from the ground up to its top edge instead of a wall.
					const float nx = d[0] / hlen * kRampSin, ny = d[1] / hlen * kRampSin;
					const float ex = std::clamp(cx, b.xl, b.xh), ey = std::clamp(cy, b.yl, b.yh);
					how = 'r';
					add({ { nx, ny, kRampCos }, nx * (cx - ex) + ny * (cy - ey) + kRampCos * (zBot - b.zh) - r });
					return;
				}
				const float len = std::sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
				how = 'e';
				if (len > 1e-5f) {
					add({ { d[0] / len, d[1] / len, d[2] / len }, len - r });
					return;
				}
				// The capsule's core is inside the box: out through the top if the feet are near it,
				// else the nearest side.
				const float up = b.zh - feet;
				const float side[4] = { cx - b.xl, b.xh - cx, cy - b.yl, b.yh - cy };
				const float nearest = std::min({ side[0], side[1], side[2], side[3] });
				how = 'i';
				if (up <= nearest + 0.25f || (a_freeAbove && up < 0.6f)) {
					add({ { 0, 0, 1 }, -up });
				} else if (nearest == side[0]) {
					add({ { -1, 0, 0 }, -(side[0] + r) });
				} else if (nearest == side[1]) {
					add({ { 1, 0, 0 }, -(side[1] + r) });
				} else if (nearest == side[2]) {
					add({ { 0, -1, 0 }, -(side[2] + r) });
				} else {
					add({ { 0, 1, 0 }, -(side[3] + r) });
				}
			};

			static thread_local std::vector<proto::RenShapeBox> parts;
			for (int by = y0; by <= y1; ++by) {
				for (int bz = z0; bz <= z1; ++bz) {
					for (int bx = x0; bx <= x1; ++bx) {
						parts.clear();
						const int boxes = NpcBlocks::ShapeAt(bx, by, bz, &parts);
						if (boxes < 0) {
							continue;
						}
						const bool freeAbove = !NpcBlocks::SolidAt(bx, by + 1, bz);
						src[0] = bx, src[1] = by, src[2] = bz;
						from = boxes == 0 ? 'f' : 'p';
						if (boxes == 0) {
							// A full block in Havok space.
							addBox({ bx / k, (bx + 1) / k, -(bz + 1) / k, -bz / k, by / k, (by + 1) / k,
									   { FullAt(bx + 1, by, bz), FullAt(bx - 1, by, bz), FullAt(bx, by, bz - 1), FullAt(bx, by, bz + 1),
										   FullAt(bx, by + 1, bz), FullAt(bx, by - 1, bz) } },
								freeAbove);
							continue;
						}
						for (const auto& p : parts) {
							addBox({ (bx + p.min[0] / 16.0f) / k, (bx + p.max[0] / 16.0f) / k,
									   -(bz + p.max[2] / 16.0f) / k, -(bz + p.min[2] / 16.0f) / k,
									   (by + p.min[1] / 16.0f) / k, (by + p.max[1] / 16.0f) / k, {} },
								freeAbove);
						}
					}
				}
			}
		}

		struct ProcessConstraintsHook
		{
			static void thunk(RE::bhkCharProxyController* a_this, RE::hkpCharacterProxy* a_proxy, const RE::hkArray<RE::hkpRootCdPoint>& a_manifold, RE::hkpSimplexSolverInput& a_input)
			{
				Perf::Scope timer(Perf::kHookPlanes);
				const int before = a_input.numConstraints;
				func(a_this, a_proxy, a_manifold, a_input);
				if (!a_proxy || !a_proxy->shapePhantom || !a_input.constraints) {
					return;
				}
				// Room for our planes: what Havok set aside, minus what Skyrim's callback just used.
				const int room = a_proxy->userPlanes - (a_input.numConstraints - before);
				if (a_proxy->userPlanes < kWantUserPlanes) {
					a_proxy->userPlanes = kWantUserPlanes;  // bigger arrays from the next update on
				}
				if (room <= 0) {
					return;
				}
				const auto* phantom = a_proxy->shapePhantom;
				const auto* shape = phantom->collidable.shape;
				if (!shape) {
					return;
				}
				RE::hkAabb box;
				shape->GetAabbImpl(phantom->motionState.transform, 0.0f, box);
				// The player in Skyrim's own control mode stands on real crates (BlockCrates.cpp): Havok's contacts with them are all it gets.
				if (&phantom->collidable == skyPlayer.load(std::memory_order_relaxed)) {
					return;
				}
				static thread_local std::vector<Plane> planes;
				planes.clear();
				const bool isPuppet = &phantom->collidable == puppet.load(std::memory_order_relaxed);
				// The player is as wide as Minecraft's (fits through a one-block gap); ramps up low
				// ledges for everyone but the puppet (Minecraft does its stepping).
				auto*       player = RE::PlayerCharacter::GetSingleton();
				const bool  isPlayer = player && player->GetCharController() == static_cast<RE::bhkCharacterController*>(a_this);
				const float halfWidth = isPlayer ? kPlayerHalfWidth / BlocksPerHavok() : 1.0e9f;
				if (isPlayer && !loggedPlayerBox.exchange(true)) {
					alignas(16) float lo[4], hi[4];
					_mm_store_ps(lo, box.min.quad);
					_mm_store_ps(hi, box.max.quad);
					const float k = BlocksPerHavok();
					logger::info("dig: the player's collision box is {:.2f} x {:.2f} x {:.2f} blocks (Minecraft's: 0.6 x 0.6 x 1.8)",
						(hi[0] - lo[0]) * k, (hi[1] - lo[1]) * k, (hi[2] - lo[2]) * k);
				}
				alignas(16) float centre[4];
				_mm_store_ps(centre, phantom->motionState.transform.translation.quad);
				BlockPlanes(box, isPlayer ? centre : nullptr, planes, !isPuppet, halfWidth, isPlayer ? kPlayerHeight / BlocksPerHavok() : 1.0e9f);
				if (isPlayer && !isPuppet && std::ranges::any_of(planes, [](const Plane& a_plane) { return a_plane.n[2] > 0.7f && a_plane.d < 0.15f; })) {
					// Standing on a Minecraft block (see CheckSupportHook).
					blockGroundController.store(static_cast<RE::bhkCharacterController*>(a_this));
					blockGroundTick.store(GetTickCount64());
				}
				if (isPuppet) {
					// The player's body: only the blocks it stands on (Minecraft does the rest).
					std::erase_if(planes, [](const Plane& a_plane) { return a_plane.n[2] < 0.7f; });
				}
				if (planes.empty()) {
					return;
				}
				std::ranges::sort(planes, {}, &Plane::d);
				if (isPlayer && !isPuppet && DiagnosticsEnabled()) {
					// What Minecraft's blocks do to the player in Skyrim mode: twice a second, and at
					// once (up to ten a second) whenever one overlaps it.
					static std::atomic<std::uint64_t> nextLog{ 0 }, nextOverlapLog{ 0 };
					const auto                        now = GetTickCount64();
					const bool overlap = std::ranges::any_of(planes, [](const Plane& a_plane) { return a_plane.d < -0.02f; });
					if (now >= nextLog.load() || (overlap && now >= nextOverlapLog.load())) {
						nextLog = now + 500;
						nextOverlapLog = now + 100;
						alignas(16) float lo[4], hi[4];
						_mm_store_ps(lo, box.min.quad);
						_mm_store_ps(hi, box.max.quad);
						const float k = BlocksPerHavok();
						std::string list;
						for (const auto& p : planes) {
							list += fmt::format(" [{:+.2f} {:+.2f} {:+.2f} d {:+.3f} {}{} {},{},{}]", p.n[0], p.n[1], p.n[2], p.d * k, p.from, p.how,
								p.src[0], p.src[1], p.src[2]);
						}
						// Jumping needs Skyrim to count the player as supported (flags kSupport 0x100,
						// kCanJump 0x400; state 0 = on ground).
						logger::info("dig: {}player at MC {:.2f} {:.2f} {:.2f} (box centre {:.2f} {:.2f}, box {:.2f} x {:.2f}, keep {:.3f}; flags {:X}, state {}, want {}): {} block planes:{}",
							overlap ? "OVERLAP " : "", centre[0] * k, lo[2] * k, -centre[1] * k, (lo[0] + hi[0]) * 0.5f * k, -(lo[1] + hi[1]) * 0.5f * k,
							(hi[0] - lo[0]) * k, (hi[1] - lo[1]) * k, a_proxy->keepDistance * k, a_this->flags.underlying(),
							static_cast<int>(a_this->context.currentState), static_cast<int>(a_this->wantState), planes.size(), list);
					}
				}
				const int count = std::min<int>(room, static_cast<int>(planes.size()));
				if (loggedPlanes.fetch_add(1) < 3) {
					logger::info("dig: {} Minecraft block planes for an actor (room {}, nearest {:.2f})", count, room, planes[0].d);
				}
				for (int i = 0; i < count; ++i) {
					const auto& p = planes[i];
					auto&       c = a_input.constraints[a_input.numConstraints++];
					float       w = p.d - a_proxy->keepDistance;
					float       v[3] = { 0, 0, 0 };
					if (w < 0.0f) {
						const float push = -w * a_proxy->penetrationRecoverySpeed;
						v[0] = p.n[0] * push, v[1] = p.n[1] * push, v[2] = p.n[2] * push;
						w = 0.0f;
					}
					c.plane = RE::hkVector4(p.n[0], p.n[1], p.n[2], w);
					c.velocity = RE::hkVector4(v[0], v[1], v[2], 0.0f);
					c.staticFriction = a_proxy->staticFriction;
					c.extraUpStaticFriction = a_proxy->extraUpStaticFriction;
					c.extraDownStaticFriction = a_proxy->extraDownStaticFriction;
					c.dynamicFriction = a_proxy->dynamicFriction;
					c.priority = 0;
				}
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	namespace
	{
		// SkyCraft+ : jumping off Minecraft blocks. Havok's support check and Skyrim's support flags
		// only see the world's real contacts, not our block planes: on a block the player stayed "in
		// the air" (state 2) with a fall timer running, and Jump only twitched the animation. After
		// the support check, while the player stands on a block plane and isn't rising, it gets
		// what real ground would give: support flags, a flat supported surface, no fall, on-ground.
		constexpr float kRisingSpeed = 0.3f;  // Havok units a second upwards: taking off, not standing

		struct CheckSupportHook
		{
			static void thunk(RE::bhkCharacterController* a_this)
			{
				func(a_this);
				if (!a_this || a_this != blockGroundController.load() || GetTickCount64() - blockGroundTick.load() >= 250) {
					return;
				}
				// Not while rising: that's a jump taking off from the block.
				RE::hkVector4 velocity;
				a_this->GetLinearVelocityImpl(velocity);
				alignas(16) float v[4];
				_mm_store_ps(v, velocity.quad);
				if (v[2] > kRisingSpeed) {
					return;
				}
				// Everything Skyrim knows about the ground it stands on, as if the block were real:
				// support flags, a supported surface (so Havok's state machine lands it instead of
				// leaving it "in the air" forever, with a fall timer running), the support and surface
				// normals (flat: the slope check) and no fall.
				alignas(16) float supportNorm[4], surfaceNorm[4];
				_mm_store_ps(supportNorm, a_this->supportNorm.quad);
				_mm_store_ps(surfaceNorm, a_this->surfaceInfo.surfaceNormal.quad);
				// Logged a few times when Skyrim thought otherwise (in the air, or falling).
				static std::atomic<int> logged{ 0 };
				if ((a_this->context.currentState != RE::hkpCharacterStateTypes::kOnGround || a_this->fallTime > 0.25f) && logged.fetch_add(1) < 5) {
					logger::info("dig: the player stands on a Minecraft block; Skyrim had: state {}, flags {:X}, surface {} normal z {:.2f}, support normal z {:.2f}, falling {:.2f}s, rising {:.2f}",
						static_cast<int>(a_this->context.currentState), a_this->flags.underlying(), static_cast<int>(a_this->surfaceInfo.supportedState.get()),
						surfaceNorm[2], supportNorm[2], a_this->fallTime, v[2]);
				}
				a_this->flags.set(RE::CHARACTER_FLAGS::kSupport, RE::CHARACTER_FLAGS::kCanJump);
				a_this->surfaceInfo.supportedState = RE::hkpSurfaceInfo::SupportedState::kSupported;
				if (surfaceNorm[2] < 0.7f) {
					a_this->surfaceInfo.surfaceNormal = RE::hkVector4(0.0f, 0.0f, 1.0f, 0.0f);
				}
				if (supportNorm[2] < 0.7f) {
					a_this->supportNorm = RE::hkVector4(0.0f, 0.0f, 1.0f, 0.0f);
				}
				if (auto* player = RE::PlayerCharacter::GetSingleton()) {
					a_this->fallStartHeight = player->GetPositionZ();
				}
				a_this->fallTime = 0.0f;
				// And the state itself: Havok left it "in the air" on a block whatever the surface
				// said (its own support check runs on the world's contacts).
				if (a_this->context.currentState == RE::hkpCharacterStateTypes::kInAir) {
					a_this->context.currentState = RE::hkpCharacterStateTypes::kOnGround;
					static std::atomic<int> forced{ 0 };
					if (forced.fetch_add(1) < 5) {
						logger::info("dig: the player stands on a Minecraft block: Havok's state set from in-air to on-ground");
					}
				}
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// Every actor's movement update (AE 37507) asks for the land's height under it (TES
		// GetLandHeight, AE 13344) and, if that's more than 30 units above the actor, puts the actor
		// back on top of the land: Skyrim's rescue for characters that fell through the ground. In a
		// dug hole or tunnel that ground is gone (or they're meant to be under it), so it mustn't.
		struct LandRescueHook
		{
			static bool thunk(RE::TES* a_tes, const RE::NiPoint3& a_pos, float& a_height)
			{
				Perf::Scope timer(Perf::kHookLand);
				const bool found = func(a_tes, a_pos, a_height);
				// SkyCraft+ : the underground. Never for the player while Minecraft is in its world:
				// Minecraft's blocks hold them up, deep under Skyrim's land (down to bedrock), and the
				// rescue lifted them to the surface partway down a mine.
				auto&      st = State();
				auto*      player = RE::PlayerCharacter::GetSingleton();
				if (player && found && a_height > a_pos.z && (st.puppeting || st.skyrimDrives || st.minecraftOwnsPlayer) &&
					player->GetPosition().GetSquaredDistance(a_pos) < 100.0f) {
					a_height = a_pos.z;
					return found;
				}
				if (Any() && a_height > a_pos.z) {
					const auto actor = SkyToMc(a_pos);
					const auto land = SkyToMc(RE::NiPoint3(a_pos.x, a_pos.y, a_height));
					const int  x = int(std::floor(actor.x)), z = int(std::floor(actor.z));
					if (IsDug(x, int(std::floor(land.y - 0.01)), z) || IsDug(x, int(std::floor(actor.y + 0.1)), z) || IsDug(x, int(std::floor(actor.y + 1.0)), z)) {
						a_height = a_pos.z;
					}
				}
				return found;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	bool PlayerOnBlock()
	{
		auto* player = RE::PlayerCharacter::GetSingleton();
		return player && blockGroundController.load() == player->GetCharController() && GetTickCount64() - blockGroundTick.load() < 250;
	}

	void SetPuppet(const RE::hkpCollidable* a_proxy, float a_feetHavokZ)
	{
		puppetFeet.store(a_feetHavokZ, std::memory_order_relaxed);
		puppet.store(a_proxy, std::memory_order_relaxed);
	}

	void SetSkyrimPlayer(const RE::hkpCollidable* a_proxy)
	{
		skyPlayer.store(a_proxy, std::memory_order_relaxed);
	}

	void Install()
	{
		REL::Relocation<std::uintptr_t> collector{ RE::VTABLE_bhkCharacterPointCollector[0] };
		AddCdPointHook::func = collector.write_vfunc(0x1, AddCdPointHook::thunk);
		REL::Relocation<std::uintptr_t> plain{ RE::VTABLE_hkpAllCdPointCollector[0] };
		AllCdPointHook::func = plain.write_vfunc(0x1, AllCdPointHook::thunk);
		REL::Relocation<std::uintptr_t> controller{ RE::VTABLE_bhkCharProxyController[0] };
		ProcessConstraintsHook::func = controller.write_vfunc(0x1, ProcessConstraintsHook::thunk);
		REL::Relocation<std::uintptr_t> controllerBase{ RE::VTABLE_bhkCharProxyController[1] };
		CheckSupportHook::func = controllerBase.write_vfunc(0x0D, CheckSupportHook::thunk);
		// The land-height call in the actor movement update (checked: a 5-byte call there).
		REL::Relocation<std::uintptr_t> site{ REL::ID(37507), 0xCEA };
		if (REL::Module::IsAE() && *reinterpret_cast<const std::uint8_t*>(site.address()) == 0xE8) {
			LandRescueHook::func = SKSE::GetTrampoline().write_call<5>(site.address(), LandRescueHook::thunk);
			logger::info("dig: NPC collision hooks installed");
		} else {
			logger::warn("dig: the land-rescue call isn't where expected; actors in dug holes may be lifted out");
		}
	}
}
