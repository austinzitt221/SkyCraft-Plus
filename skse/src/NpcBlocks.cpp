#include "Game.h"
#include "Dig.h"

#include <SimpleIni.h>

// Skyrim's NPCs don't walk through Minecraft's blocks: Minecraft sends which blocks of each section
// have a collision shape (proto::kRenSolids), and every frame any NPC overlapping one is pushed
// back out sideways, so they stop at walls and slide along them.
namespace skycraft
{
	namespace
	{
		std::unordered_map<std::uint64_t, std::array<std::uint64_t, 64>> solids;  // section -> bit x + 16z + 256y
		// Which sections have solids, and when each last changed (the generation at that moment): BlockCollision.cpp rebuilds a section's model when it moves on.
		std::unordered_map<std::uint64_t, NpcBlocks::SectionStamp> stamps;  // guarded by solidsLock
		// Written on the main thread; read there and by Skyrim's AI threads planning NPC paths.
		std::shared_mutex solidsLock;
		std::atomic<std::uint32_t> solidsGeneration{ 0 };  // bumped on every change
		// SkyCraft+ : exact block shapes. Section -> its solid blocks that aren't full cubes, with
		// their collision boxes (proto::kRenShapes). Same lock as solids.
		std::unordered_map<std::uint64_t, std::unordered_map<std::uint16_t, std::vector<proto::RenShapeBox>>> shapes;

		std::uint64_t SectionKey(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			return (std::uint64_t(std::uint32_t(a_x) & 0x3FFFFF) << 42) | (std::uint64_t(std::uint32_t(a_z) & 0x3FFFFF) << 20) |
			       (std::uint64_t(std::uint32_t(a_y) & 0xFFFFF));
		}

		int FloorDiv16(int a_v) { return a_v >> 4; }  // arithmetic shift: floor for negatives too

		constexpr float kRange = 4000.0f;          // Skyrim units from the player
		constexpr float kMaxRadiusBlocks = 0.45f;  // wider actors still fit through a one-block gap
		constexpr float kSlideSpeed = 3.2f;        // blocks a second along a wall
		// Havok keeps NPCs just clear of blocks now (DigPhysics.cpp); a wall this close still counts
		// as walked into, so they follow it round.
		constexpr double kTouchBlocks = 0.1;

		// An NPC following a wall around: which way (+1/-1 along the wall) and for how long more.
		struct Detour
		{
			int   side = 0;
			float time = 0.0f;
		};
		std::unordered_map<RE::FormID, Detour> detours;

		bool ColumnBlocked(int a_x, int a_z, int a_y0, int a_y1);

		// A circle (an actor seen from above) against a box's footprint: adds the push out to a_px, a_pz.
		void PushOutOfRect(double a_cx, double a_cz, double a_r, double a_x0, double a_x1, double a_z0, double a_z1, double& a_px, double& a_pz)
		{
			const double qx = std::clamp(a_cx, a_x0, a_x1), qz = std::clamp(a_cz, a_z0, a_z1);
			double       dx = a_cx - qx, dz = a_cz - qz;
			const double d = std::sqrt(dx * dx + dz * dz);
			if (d >= a_r) {
				return;
			}
			if (d < 1e-6) {
				// Centre inside the footprint: out through the nearest side.
				const double toW = a_cx - a_x0, toE = a_x1 - a_cx, toN = a_cz - a_z0, toS = a_z1 - a_cz;
				const double m = std::min({ toW, toE, toN, toS });
				dx = m == toW ? -1.0 : m == toE ? 1.0 : 0.0;
				dz = m == toN ? -1.0 : m == toS ? 1.0 : 0.0;
				a_px += dx * (m + a_r);
				a_pz += dz * (m + a_r);
			} else {
				a_px += dx / d * (a_r - d);
				a_pz += dz / d * (a_r - d);
			}
		}
	}

	namespace NpcBlocks
	{
		bool SolidAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z);
		bool BlocksBody(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z);
	}

	namespace
	{
		bool ColumnBlocked(int a_x, int a_z, int a_y0, int a_y1)
		{
			for (int y = a_y0; y <= a_y1; ++y) {
				if (NpcBlocks::BlocksBody(a_x, y, a_z)) {
					return true;
				}
			}
			return false;
		}
	}

	namespace NpcBlocks
	{
		void OnSolids(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenSolids)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenSolids*>(a_data);
			const auto  key = SectionKey(hdr->sx, hdr->sy, hdr->sz);
			std::unique_lock lock(solidsLock);
			++solidsGeneration;
			if (hdr->count == 0 || a_bytes < sizeof(proto::RenSolids) + 512) {
				solids.erase(key);
				shapes.erase(key);
				stamps.erase(key);
				return;
			}
			std::memcpy(solids[key].data(), a_data + sizeof(proto::RenSolids), 512);
			stamps[key] = { hdr->sx, hdr->sy, hdr->sz, solidsGeneration.load() };
		}

		void OnShapes(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenSolids)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenSolids*>(a_data);
			const auto  key = SectionKey(hdr->sx, hdr->sy, hdr->sz);
			const auto  count = std::min<std::uint32_t>(hdr->count, (a_bytes - sizeof(proto::RenSolids)) / sizeof(proto::RenShapeBox));
			std::unique_lock lock(solidsLock);
			++solidsGeneration;
			if (count == 0) {
				shapes.erase(key);
				if (const auto it = stamps.find(key); it != stamps.end()) {
					it->second.stamp = solidsGeneration.load();
				}
				return;
			}
			auto& section = shapes[key];
			section.clear();
			const auto* boxes = reinterpret_cast<const proto::RenShapeBox*>(a_data + sizeof(proto::RenSolids));
			for (std::uint32_t i = 0; i < count; ++i) {
				section[static_cast<std::uint16_t>(boxes[i].cell & proto::kShapeCellMask)].push_back(boxes[i]);
			}
			if (const auto it = stamps.find(key); it != stamps.end()) {
				it->second.stamp = solidsGeneration.load();  // its collision model is built again with the shapes
			}
		}

		void Clear()
		{
			std::unique_lock lock(solidsLock);
			++solidsGeneration;
			solids.clear();
			shapes.clear();
			stamps.clear();
		}

		void ListSections(std::vector<SectionStamp>& a_out)
		{
			std::shared_lock lock(solidsLock);
			a_out.reserve(a_out.size() + stamps.size());
			for (const auto& entry : stamps) {
				a_out.push_back(entry.second);
			}
		}

		int ShapeAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z, std::vector<proto::RenShapeBox>* a_out)
		{
			std::shared_lock lock(solidsLock);
			const auto       sectionKey = SectionKey(FloorDiv16(a_x), FloorDiv16(a_y), FloorDiv16(a_z));
			const auto       it = solids.find(sectionKey);
			if (it == solids.end()) {
				return -1;
			}
			const int bit = (a_x & 15) + 16 * (a_z & 15) + 256 * (a_y & 15);
			if (!((it->second[bit >> 6] >> (bit & 63)) & 1)) {
				return -1;
			}
			const auto section = shapes.find(sectionKey);
			if (section == shapes.end()) {
				return 0;
			}
			const auto cell = section->second.find(static_cast<std::uint16_t>(bit));
			if (cell == section->second.end()) {
				return 0;
			}
			if (a_out) {
				a_out->insert(a_out->end(), cell->second.begin(), cell->second.end());
			}
			return static_cast<int>(cell->second.size());
		}

		// SkyCraft+ : exact block shapes. A wall for NPC push-out and path avoidance: a full block,
		// or a part block that is neither a step (slab, stair, carpet: Havok's ramps take them up)
		// nor something mobs walk through (an open door). Fences, walls and closed doors are.
		bool BlocksBody(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			std::shared_lock lock(solidsLock);
			const auto       sectionKey = SectionKey(FloorDiv16(a_x), FloorDiv16(a_y), FloorDiv16(a_z));
			const auto       it = solids.find(sectionKey);
			if (it == solids.end()) {
				return false;
			}
			const int bit = (a_x & 15) + 16 * (a_z & 15) + 256 * (a_y & 15);
			if (!((it->second[bit >> 6] >> (bit & 63)) & 1)) {
				return false;
			}
			const auto section = shapes.find(sectionKey);
			if (section == shapes.end()) {
				return true;
			}
			const auto cell = section->second.find(static_cast<std::uint16_t>(bit));
			if (cell == section->second.end() || cell->second.empty()) {
				return true;
			}
			return (cell->second.front().cell & (proto::kShapeStep | proto::kShapePassable)) == 0;
		}

		std::uint32_t Generation() { return solidsGeneration.load(); }

		// SkyCraft+ : companions and stairs. The highest top of a step block's (stair, slab, carpet)
		// collision boxes in row a_y under the point (a_px, a_pz), within a_margin; -infinity if none.
		double StepTopAt(std::int32_t a_y, double a_px, double a_pz, double a_margin)
		{
			double best = -std::numeric_limits<double>::infinity();
			std::shared_lock lock(solidsLock);
			for (int bx = int(std::floor(a_px - a_margin)); bx <= int(std::floor(a_px + a_margin)); ++bx) {
				for (int bz = int(std::floor(a_pz - a_margin)); bz <= int(std::floor(a_pz + a_margin)); ++bz) {
					const auto sectionKey = SectionKey(FloorDiv16(bx), FloorDiv16(a_y), FloorDiv16(bz));
					const auto section = shapes.find(sectionKey);
					if (section == shapes.end()) {
						continue;
					}
					const int  bit = (bx & 15) + 16 * (bz & 15) + 256 * (a_y & 15);
					const auto cell = section->second.find(static_cast<std::uint16_t>(bit));
					if (cell == section->second.end()) {
						continue;
					}
					for (const auto& box : cell->second) {
						if ((box.cell & proto::kShapeStep) == 0 || (box.cell & proto::kShapePassable) != 0) {
							break;
						}
						const double x0 = bx + box.min[0] / 16.0, x1 = bx + box.max[0] / 16.0;
						const double z0 = bz + box.min[2] / 16.0, z1 = bz + box.max[2] / 16.0;
						if (a_px + a_margin >= x0 && a_px - a_margin <= x1 && a_pz + a_margin >= z0 && a_pz - a_margin <= z1) {
							best = std::max(best, a_y + box.max[1] / 16.0);
						}
					}
				}
			}
			return best;
		}

		void CopySolids(const std::int32_t a_origin[3], const std::int32_t a_size[3], std::uint32_t* a_out, std::uint32_t a_bit)
		{
			std::shared_lock lock(solidsLock);
			const int x0 = a_origin[0], y0 = a_origin[1], z0 = a_origin[2];
			const int w = a_size[0], h = a_size[1], d = a_size[2];
			for (int sy = FloorDiv16(y0); sy <= FloorDiv16(y0 + h - 1); ++sy) {
				for (int sz = FloorDiv16(z0); sz <= FloorDiv16(z0 + d - 1); ++sz) {
					for (int sx = FloorDiv16(x0); sx <= FloorDiv16(x0 + w - 1); ++sx) {
						const auto it = solids.find(SectionKey(sx, sy, sz));
						if (it == solids.end()) {
							continue;
						}
						const auto& bits = it->second;
						for (int ly = 0; ly < 16; ++ly) {
							const int y = sy * 16 + ly - y0;
							if (y < 0 || y >= h) {
								continue;
							}
							for (int lz = 0; lz < 16; ++lz) {
								const int z = sz * 16 + lz - z0;
								if (z < 0 || z >= d) {
									continue;
								}
								for (int lx = 0; lx < 16; ++lx) {
									const int x = sx * 16 + lx - x0;
									const int bit = lx + 16 * lz + 256 * ly;
									if (x >= 0 && x < w && ((bits[bit >> 6] >> (bit & 63)) & 1)) {
										a_out[x + w * (y + h * z)] |= a_bit;
									}
								}
							}
						}
					}
				}
			}
		}


		// Boxes of one block, block-local, in blocks. The caller holds solidsLock.
		int BoxesLocked(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z, BlockBox a_out[kMaxBoxes])
		{
			const auto key = SectionKey(FloorDiv16(a_x), FloorDiv16(a_y), FloorDiv16(a_z));
			const auto it = solids.find(key);
			if (it == solids.end()) {
				return 0;
			}
			const int bit = (a_x & 15) + 16 * (a_z & 15) + 256 * (a_y & 15);
			if (!((it->second[bit >> 6] >> (bit & 63)) & 1)) {
				return 0;
			}
			const auto sit = shapes.find(key);
			if (sit != shapes.end()) {
				const auto cell = sit->second.find(std::uint16_t(bit));
				if (cell != sit->second.end()) {
					const int n = std::min<int>(int(cell->second.size()), kMaxBoxes);
					for (int i = 0; i < n; ++i) {
						const auto& sb = cell->second[std::size_t(i)];
						for (int k = 0; k < 3; ++k) {
							a_out[i].lo[k] = sb.min[k] / 16.0f;
							a_out[i].hi[k] = sb.max[k] / 16.0f;
						}
					}
					return n;
				}
			}
			a_out[0] = { { 0, 0, 0 }, { 1, 1, 1 } };
			return 1;
		}

		int BoxesAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z, BlockBox a_out[kMaxBoxes])
		{
			std::shared_lock lock(solidsLock);
			return BoxesLocked(a_x, a_y, a_z, a_out);
		}

		void GatherBoxes(int a_x0, int a_x1, int a_y0, int a_y1, int a_z0, int a_z1, std::vector<BlockBox>& a_out)
		{
			std::shared_lock lock(solidsLock);
			if (solids.empty()) {
				return;
			}
			BlockBox local[kMaxBoxes];
			for (int y = a_y0; y <= a_y1; ++y) {
				for (int z = a_z0; z <= a_z1; ++z) {
					for (int x = a_x0; x <= a_x1; ++x) {
						const int n = BoxesLocked(x, y, z, local);
						for (int i = 0; i < n; ++i) {
							BlockBox abs = local[i];
							abs.lo[0] += float(x), abs.hi[0] += float(x);
							abs.lo[1] += float(y), abs.hi[1] += float(y);
							abs.lo[2] += float(z), abs.hi[2] += float(z);
							a_out.push_back(abs);
						}
					}
				}
			}
		}

		bool FaceCovered(const BlockBox& a_box, int a_axis, int a_sign)
		{
			constexpr float eps = 1e-3f;
			const int       a1 = (a_axis + 1) % 3, a2 = (a_axis + 2) % 3;
			const float     c = a_sign > 0 ? a_box.hi[a_axis] : a_box.lo[a_axis];
			int             lo[3], hi[3];
			lo[a_axis] = hi[a_axis] = int(std::floor(c + (a_sign > 0 ? 1e-4f : -1e-4f)));
			lo[a1] = int(std::floor(a_box.lo[a1] + 1e-4f)), hi[a1] = int(std::floor(a_box.hi[a1] - 1e-4f));
			lo[a2] = int(std::floor(a_box.lo[a2] + 1e-4f)), hi[a2] = int(std::floor(a_box.hi[a2] - 1e-4f));
			std::shared_lock lock(solidsLock);
			BlockBox         local[kMaxBoxes];
			for (int z = lo[2]; z <= hi[2]; ++z) {
				for (int y = lo[1]; y <= hi[1]; ++y) {
					for (int x = lo[0]; x <= hi[0]; ++x) {
						const int n = BoxesLocked(x, y, z, local);
						const int cell[3] = { x, y, z };
						for (int i = 0; i < n; ++i) {
							const float opposite = (a_sign > 0 ? local[i].lo[a_axis] : local[i].hi[a_axis]) + float(cell[a_axis]);
							if (std::fabs(opposite - c) > eps) {
								continue;
							}
							if (local[i].lo[a1] + float(cell[a1]) <= a_box.lo[a1] + eps && local[i].hi[a1] + float(cell[a1]) >= a_box.hi[a1] - eps &&
								local[i].lo[a2] + float(cell[a2]) <= a_box.lo[a2] + eps && local[i].hi[a2] + float(cell[a2]) >= a_box.hi[a2] - eps) {
								return true;
							}
						}
					}
				}
			}
			return false;
		}

		bool SolidAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			std::shared_lock lock(solidsLock);
			const auto       it = solids.find(SectionKey(FloorDiv16(a_x), FloorDiv16(a_y), FloorDiv16(a_z)));
			if (it == solids.end()) {
				return false;
			}
			const int bit = (a_x & 15) + 16 * (a_z & 15) + 256 * (a_y & 15);
			return (it->second[bit >> 6] >> (bit & 63)) & 1;
		}

		// How far (blocks, up to a_max) along direction (tx, tz) from (cx, cz) until the wall on the
		// side of normal (-nx, -nz) ends, so the NPC can turn the corner there.
		double WallEnd(double cx, double cz, double tx, double tz, double nx, double nz, int y0, int y1, double a_max)
		{
			for (double s = 0.5; s <= a_max; s += 0.5) {
				const double px = cx + tx * s, pz = cz + tz * s;
				if (ColumnBlocked(int(std::floor(px)), int(std::floor(pz)), y0, y1)) {
					return a_max + 1.0;  // walled in that way too
				}
				const double wx = px - nx * 1.0, wz = pz - nz * 1.0;  // one block into the wall's side
				if (!ColumnBlocked(int(std::floor(wx)), int(std::floor(wz)), y0, y1)) {
					return s;
				}
			}
			return a_max + 1.0;
		}

		void PushActorsOut(RE::PlayerCharacter* a_player, float a_delta)
		{
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists || solids.empty()) {
				return;
			}
			const auto playerPos = a_player->GetPosition();
			for (auto& handle : lists->highActorHandles) {
				auto actorPtr = handle.get();
				auto* actor = actorPtr.get();
				if (!actor || actor == a_player || actor->IsDead() || !actor->Is3DLoaded() || actor->GetPosition().GetDistance(playerPos) > kRange) {
					continue;
				}
				const auto   sky = actor->GetPosition();
				const auto   mc = SkyToMc(sky);
				const double r = std::clamp(double(actor->GetBoundRadius()) / proto::kUnitsPerBlock, 0.2, double(kMaxRadiusBlocks)) + kTouchBlocks;
				const double h = std::clamp(double(actor->GetHeight()) / proto::kUnitsPerBlock, 0.5, 4.0);
				// SkyCraft+ : standing on Minecraft's blocks. Feet sunk into a block's top go back on top of
				// it: Skyrim's AI walks NPCs at its own ground's height (a step under the ground round a
				// structure), and that block was taken for a wall, pushing them sideways every frame (a
				// mammoth stuck on it). Where Skyrim has no ground at all (the Nether, the End) one falling
				// through is caught from further down.
				double    feetY = mc.y;
				bool      lifted = false;
				{
					const double catchBlocks = State().parked ? 2.0 : 0.6;
					const int    mx = int(std::floor(mc.x)), mz = int(std::floor(mc.z));
					// From the block the feet are in, up through solid ones only (not over a gap onto a roof).
					double top = mc.y;
					for (int by = int(std::floor(mc.y)); BlocksBody(mx, by, mz) && by + 1.0 - mc.y <= catchBlocks; ++by) {
						top = by + 1.0;
					}
					// SkyCraft+ : companions and stairs. Steps (stairs, slabs, carpets) weren't counted:
					// Havok's ramps were to take NPCs up them, and didn't (a companion walked through a
					// staircase at the ground's height). Up onto a step's top under the actor, at most
					// 0.6 at a time (a stair's front half, then its back).
					for (int k = 0; k < 6; ++k) {
						double best = top;
						for (int by = int(std::floor(top - 0.01)); by <= int(std::floor(top + 0.6)); ++by) {
							const double t = StepTopAt(by, mc.x, mc.z, 0.2);
							if (t > best + 0.02 && t - top <= 0.6) {
								best = t;
							}
						}
						if (best <= top) {
							break;
						}
						top = best;
					}
					if (top - mc.y > 0.02 && !ColumnBlocked(mx, mz, int(top), int(std::floor(top + h - 0.1)))) {
						feetY = top;
						lifted = true;
					}
				}
				// From just above the feet (standing on a block is fine) to the head.
				const int y0 = int(std::floor(feetY + 0.3)), y1 = int(std::floor(feetY + h - 0.1));
				double    cx = mc.x, cz = mc.z;
				double    stepTop = -1.0e9;  // a block a step high walked into: up onto it
				bool      moved = false;
				for (int pass = 0; pass < 3; ++pass) {
					double pushX = 0.0, pushZ = 0.0;
					for (int bx = int(std::floor(cx - r)); bx <= int(std::floor(cx + r)); ++bx) {
						for (int bz = int(std::floor(cz - r)); bz <= int(std::floor(cz + r)); ++bz) {
							int lowest = INT_MAX;
							for (int by = y0; by <= y1 && lowest == INT_MAX; ++by) {
								if (BlocksBody(bx, by, bz)) {
									lowest = by;
								}
							}
							if (lowest == INT_MAX) {
								continue;
							}
							// Circle (the actor) against the block's square, seen from above.
							const double qx = std::clamp(cx, double(bx), double(bx + 1)), qz = std::clamp(cz, double(bz), double(bz + 1));
							double       dx = cx - qx, dz = cz - qz;
							double       d = std::sqrt(dx * dx + dz * dz);
							if (d >= r) {
								continue;
							}
							// SkyCraft+ : walked into a block no more than a step high, with room above it:
							// up onto it, as Minecraft's mobs do (Skyrim's can't jump), not round it.
							if (lowest == y0 && d > 1e-6 && d < r - 0.03) {
								int top = lowest + 1;
								while (top <= y1 && BlocksBody(bx, top, bz)) {
									++top;
								}
								if (top - feetY <= 1.05 && !ColumnBlocked(bx, bz, top, int(std::floor(top + h - 0.1)))) {
									stepTop = std::max(stepTop, double(top));
									continue;
								}
							}
							// The exact boxes of the blocks in this column that reach into the body (a fence from the block below too).
							const double bodyLo = feetY + 0.3, bodyHi = feetY + h - 0.1;
							for (int by = lowest; by <= y1; ++by) {
								if (!BlocksBody(bx, by, bz)) {
									continue;
								}
								BlockBox local[kMaxBoxes];
								const int n = BoxesAt(bx, by, bz, local);
								for (int i = 0; i < n; ++i) {
									if (by + local[i].hi[1] <= bodyLo || by + local[i].lo[1] >= bodyHi) {
										continue;
									}
									PushOutOfRect(cx, cz, r, bx + local[i].lo[0], bx + local[i].hi[0], bz + local[i].lo[2], bz + local[i].hi[2], pushX, pushZ);
								}
							}
						}
					}
					if (std::abs(pushX) < 1e-4 && std::abs(pushZ) < 1e-4) {
						break;
					}
					cx += pushX;
					cz += pushZ;
					moved = true;
				}
				// SkyCraft+ : companions walk through builds (found 2026-10-11). What a follower near
				// blocks gets, every 3 s, for the next test.
				if (actor->IsPlayerTeammate()) {
					static std::unordered_map<RE::FormID, float> teammateLog;
					static int                                   teammateLines = 0;
					float&                                       next = teammateLog[actor->GetFormID()];
					next -= a_delta;
					int walls = 0;
					for (int bx = int(std::floor(mc.x - 1.0)); bx <= int(std::floor(mc.x + 1.0)); ++bx) {
						for (int bz = int(std::floor(mc.z - 1.0)); bz <= int(std::floor(mc.z + 1.0)); ++bz) {
							walls += ColumnBlocked(bx, bz, y0, y1) ? 1 : 0;
						}
					}
					if (walls > 0 && next <= 0.0f && teammateLines < 60) {
						next = 3.0f;
						++teammateLines;
						logger::info("companion {} at ({:.2f}, {:.2f}, {:.2f}), feet {:.2f}, radius {:.2f}, height {:.2f}: {} block columns round it, pushed {}, lifted {}, step {:.2f}",
							actor->GetDisplayFullName(), mc.x, mc.y, mc.z, feetY, r, h, walls, moved, lifted, stepTop > -1.0e8 ? stepTop : 0.0);
					}
				}
				auto& detour = detours[actor->GetFormID()];
				detour.time -= a_delta;
				if (moved) {
					// Walking into the wall (pushed back against its heading)? Follow it around.
					const double pushX = cx - mc.x, pushZ = cz - mc.z, pushLen = std::sqrt(pushX * pushX + pushZ * pushZ);
					if (pushLen > 1e-4) {
						const double nx = pushX / pushLen, nz = pushZ / pushLen;  // out of the wall
						double       gx = 0.0, gz = 0.0;
						RE::NiPoint3 goalSky;
						if (PathAvoid::GoalOf(actor->GetFormID(), goalSky)) {
							const auto goal = SkyToMc(goalSky);
							gx = goal.x - cx, gz = goal.z - cz;
						} else {
							const float heading = actor->GetAngleZ();  // Skyrim heading -> Minecraft x, z
							gx = std::sin(heading), gz = -std::cos(heading);
						}
						const double gl = std::sqrt(gx * gx + gz * gz);
						if (gl > 1e-3 && (gx * -nx + gz * -nz) / gl > 0.2) {
							const double tx = -nz, tz = nx;  // along the wall
							if (detour.time <= 0.0f || detour.side == 0) {
								const double endA = WallEnd(cx, cz, tx, tz, nx, nz, y0, y1, 12.0);
								const double endB = WallEnd(cx, cz, -tx, -tz, nx, nz, y0, y1, 12.0);
								const double along = (gx * tx + gz * tz) / gl;  // which end is toward the goal
								detour.side = (endA - 2.0 * along) <= (endB + 2.0 * along) ? 1 : -1;
							}
							detour.time = 1.5f;
							const double step = kSlideSpeed * a_delta;
							cx += tx * detour.side * step;
							cz += tz * detour.side * step;
						}
					}
					auto pos = McToSky(cx, mc.y, cz);
					pos.z = sky.z;
					if (lifted || stepTop > feetY) {
						pos.z = McToSky(cx, std::max(feetY, stepTop), cz).z;
					}
					actor->SetPosition(pos, true);
				} else if (lifted || stepTop > feetY) {
					actor->SetPosition(McToSky(mc.x, std::max(feetY, stepTop), mc.z), true);
					if (detour.time <= 0.0f) {
						detours.erase(actor->GetFormID());
					}
				} else if (detour.time <= 0.0f) {
					detours.erase(actor->GetFormID());
				}
			}
			if (detours.size() > 256) {
				detours.clear();
			}
		}
	}

	// ---- walking up steps the way Minecraft does ----------------------------------------------------------------------------
	//
	// Minecraft lifts a walking player over anything up to 0.6 of a block (a stair, a slab, a carpet) without a jump. Skyrim's character
	// controller can't: it stops at the riser, which is what made stairs feel wrong in Skyrim's control mode though their collision is
	// exact. So each frame, when the player stands on the ground and is walking into a Minecraft block whose top is within that step,
	// with room above it for the whole body, the player is set on top of it, as Minecraft would. The block data is the same as the
	// collision models are made from (the exact boxes of every block), so it is the same stairs the model has.
	namespace
	{
		struct StepCfg
		{
			bool  on{ true };
			float height{ 0.6f };   // blocks: the highest step taken without a jump (Minecraft: 0.6)
			float radius{ 0.3f };   // blocks: how wide the player's body is, from its middle
			float reach{ 0.14f };   // blocks: how far from the body a step is looked for (the controller stops a little short of a wall)
			float bodyHeight{ 1.8f };  // blocks: room needed above the step
		};

		const StepCfg& StepSettings()
		{
			static const StepCfg settings = [] {
				CSimpleIniA ini;
				ini.SetUnicode();
				ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
				StepCfg c;
				c.on = ini.GetBoolValue("Collision", "bStepUp", true);
				c.height = static_cast<float>(std::clamp(ini.GetDoubleValue("Collision", "fStepHeight", 0.6), 0.0, 1.0));
				c.radius = static_cast<float>(std::clamp(ini.GetDoubleValue("Collision", "fStepRadius", 0.3), 0.1, 0.6));
				c.reach = static_cast<float>(std::clamp(ini.GetDoubleValue("Collision", "fStepReach", 0.14), 0.0, 0.5));
				return c;
			}();
			return settings;
		}

		// The way the player is pushing to move, from Skyrim's own movement input (relative to the way the body faces, as Skyrim reads it).
		// False when this build of the game library doesn't expose it.
		template <class Controls>
		bool ReadMoveInput(Controls* a_controls, float& a_x, float& a_y)
		{
			if constexpr (requires { a_controls->data.moveInputVec.x; a_controls->data.moveInputVec.y; }) {
				if (!a_controls) {
					return false;
				}
				a_x = a_controls->data.moveInputVec.x;
				a_y = a_controls->data.moveInputVec.y;
				return true;
			} else {
				return false;
			}
		}
	}

	namespace NpcBlocks
	{
		void StepUpPlayer(RE::PlayerCharacter* a_player, float a_delta)
		{
			const auto& cfg = StepSettings();
			if (!cfg.on || !a_player || a_player->IsDead()) {
				return;
			}

			static double lastX = 0.0, lastZ = 0.0, memX = 0.0, memZ = 0.0;
			static bool   haveLast = false;
			static float  memory = 0.0f, cooldown = 0.0f;
			static int    logged = 0;
			static bool   inputKnown = true, inputNoted = false;

			const auto sky = a_player->GetPosition();
			const auto mc = SkyToMc(sky);
			cooldown = std::max(0.0f, cooldown - a_delta);

			// Where the player has really been going lately (used when the movement input can't be read).
			if (haveLast) {
				const double dx = mc.x - lastX, dz = mc.z - lastZ;
				const double len = std::sqrt(dx * dx + dz * dz);
				if (len > 0.01 && len < 3.0) {
					memX = dx / len;
					memZ = dz / len;
					memory = 0.35f;
				} else {
					memory = std::max(0.0f, memory - a_delta);
				}
			}
			lastX = mc.x;
			lastZ = mc.z;
			haveLast = true;

			auto* controller = a_player->GetCharController();
			if (!controller || controller->context.currentState != RE::hkpCharacterStateType::kOnGround || cooldown > 0.0f) {
				return;
			}

			// The way it is trying to go, in Minecraft's horizontal axes (x east, z south).
			double wantX = 0.0, wantZ = 0.0;
			float  inX = 0.0f, inY = 0.0f;
			if (ReadMoveInput(RE::PlayerControls::GetSingleton(), inX, inY)) {
				if (!inputNoted) {
					inputNoted = true;
					logger::info("Step up: the movement input is readable, so the step is taken in the way the player is pushing");
				}
				if (std::sqrt(inX * inX + inY * inY) < 0.2f) {
					return;
				}
				const double h = a_player->GetAngleZ();
				// forward is (sin h, -cos h) and right is (cos h, sin h) in Minecraft's x, z (Skyrim's y is Minecraft's -z)
				wantX = std::sin(h) * inY + std::cos(h) * inX;
				wantZ = -std::cos(h) * inY + std::sin(h) * inX;
			} else {
				inputKnown = false;
				if (!inputNoted) {
					inputNoted = true;
					logger::info("Step up: the movement input can't be read in this build, so the step is taken in the way the player has just been moving");
				}
				if (memory <= 0.0f) {
					return;
				}
				wantX = memX;
				wantZ = memZ;
			}
			const double wantLen = std::sqrt(wantX * wantX + wantZ * wantZ);
			if (wantLen < 1e-3) {
				return;
			}
			wantX /= wantLen;
			wantZ /= wantLen;

			// Every box near the body, from a block under the feet (fences and walls are 1.5 tall) to the head after a step.
			std::vector<BlockBox> boxes;
			GatherBoxes(int(std::floor(mc.x - 1.0)), int(std::floor(mc.x + 1.0)), int(std::floor(mc.y)) - 1, int(std::floor(mc.y + cfg.height + cfg.bodyHeight)),
				int(std::floor(mc.z - 1.0)), int(std::floor(mc.z + 1.0)), boxes);
			if (boxes.empty()) {
				return;
			}

			const double feet = mc.y;
			double       bestTop = -1.0;
			for (const auto& b : boxes) {
				const double top = b.hi[1];
				if (top <= feet + 0.02 || top > feet + cfg.height + 1e-3 || top <= bestTop) {
					continue;  // not higher than where the feet are, or higher than a step, or no better than one already found
				}
				// Is the box close to the body, and in the way the player is going?
				const double qx = std::clamp(mc.x, double(b.lo[0]), double(b.hi[0]));
				const double qz = std::clamp(mc.z, double(b.lo[2]), double(b.hi[2]));
				const double vx = qx - mc.x, vz = qz - mc.z;
				const double dist = std::sqrt(vx * vx + vz * vz);
				if (dist < 1e-4 || dist >= cfg.radius + cfg.reach) {
					continue;
				}
				if ((vx * wantX + vz * wantZ) / dist < 0.5) {
					continue;  // beside the body (sliding along it), not ahead
				}
				// Room for the whole body standing on it, where the body is now?
				const double newFeet = top + 0.01;
				const double half = cfg.radius - 0.02;
				bool         roomy = true;
				for (const auto& o : boxes) {
					if (o.lo[0] < mc.x + half && o.hi[0] > mc.x - half && o.lo[2] < mc.z + half && o.hi[2] > mc.z - half && o.lo[1] < newFeet + cfg.bodyHeight &&
						o.hi[1] > newFeet + 0.001) {
						roomy = false;
						break;
					}
				}
				if (roomy) {
					bestTop = top;
				}
			}
			if (bestTop < 0.0) {
				return;
			}

			auto up = sky;
			up.z = McToSky(mc.x, bestTop + 0.01, mc.z).z;
			a_player->SetPosition(up, true);
			controller->fallStartHeight = up.z;
			controller->fallTime = 0.0f;
			cooldown = 0.05f;
			if (logged < 12) {
				++logged;
				logger::info("Step up: lifted the player {:.2f} blocks onto a step (feet {:.2f} -> {:.2f}), going ({:.2f}, {:.2f}), input {}", bestTop + 0.01 - feet, feet, bestTop + 0.01,
					wantX, wantZ, inputKnown ? "read" : "not readable");
			}
		}
	}
}
