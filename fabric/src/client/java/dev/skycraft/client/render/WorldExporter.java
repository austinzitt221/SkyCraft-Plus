package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.skycraft.SkyCraft;
import dev.skycraft.client.RenderToggles;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyClip;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ships what Minecraft would draw in the world to Skyrim, which draws it in its own frame (so it is
 * locked to the world and hidden behind Skyrim geometry): block and fluid meshes built by Minecraft's
 * own block renderer (models, tint, smooth lighting), the texture atlas, arrows and dropped items,
 * and the targeted-block outline. Render thread only.
 */
public final class WorldExporter {
	private static final int SECTIONS_PER_FRAME = 12;
	private static final double ENTITY_RANGE = 96.0;

	private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
	private static final LongOpenHashSet SENT = new LongOpenHashSet(); // sections Skyrim holds a mesh for
	private static final LongOpenHashSet LIT = new LongOpenHashSet(); // sections Skyrim holds lights for
	private static final LongOpenHashSet SOLID = new LongOpenHashSet(); // sections Skyrim holds NPC collision for
	private static final long[] SOLID_BITS = new long[64]; // 4096 blocks: bit x + 16z + 256y
	// SkyCraft+ : exact block shapes. Sections Skyrim holds collision boxes for (blocks that aren't
	// full cubes: stairs, slabs, doors, fences...), and this section's boxes (RenShapeBox).
	private static final LongOpenHashSet SHAPED = new LongOpenHashSet();
	// What the solids and shapes of each section were when last sent: a section sent again with the same collision does not make Skyrim rebuild its collision model.
	// Doors, trapdoors, fence gates and the like (anything with an "open" state), per section, with the state each had when the section was
	// last sent. Checked every frame (watchOpenable): whatever way a change reaches the client, Skyrim hears of it.
	private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Openables> OPENABLES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private static final int SHAPE_STEP = 1 << 14;     // RenShapeBox cell flags (see the protocol)
	private static final int SHAPE_PASSABLE = 1 << 15;
	private static ByteBuffer shapeBoxes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
	private static final LongOpenHashSet DUG = new LongOpenHashSet(); // sections Skyrim holds dug cells for
	private static final ByteBuffer LIGHTS = ByteBuffer.allocate(16 * 16 * 16 * 8).order(ByteOrder.LITTLE_ENDIAN);
	// SkyCraft+ : performance. The last content sent for a section's lights, collision bits, shapes
	// and dug cells. Re-meshing a section for one block change used to send all of them again
	// unchanged (the lights alone are a record per torch); only what differs goes over now. Keyed
	// with the section, and kept in step with LIT/SOLID/SHAPED/DUG below (removed with them, cleared
	// with them, so a section Skyrim no longer holds anything for is sent whole again).
	private static final Long2LongMap LIGHT_SENT = new Long2LongOpenHashMap();
	private static final Long2LongMap SOLID_SENT = new Long2LongOpenHashMap();
	private static final Long2LongMap SHAPE_SENT = new Long2LongOpenHashMap();
	private static final Long2LongMap DUG_SENT = new Long2LongOpenHashMap();
	private static int sentGeneration = Integer.MIN_VALUE;
	private static int meshesSent;
	private static ClientLevel sentLevel;
	private static SkyAtlas atlas;
	private static ModelBlockRenderer blockRenderer;
	private static FluidRenderer fluidRenderer;
	private static final MeshBuilder MESH = new MeshBuilder();
	private static final List<SkyLink.WorldEntity> ENTITIES = new ArrayList<>();
	private static final ItemStackRenderState ITEM_STATE = new ItemStackRenderState();
	private static final java.util.Map<Item, float[]> ICONS = new java.util.HashMap<>();
	private static final java.util.Map<BlockState, float[]> CUBE_FACES = new java.util.HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private WorldExporter() {
	}

	/** The openable blocks of one section and the state each was in when the section was sent. */
	private static final class Openables {
		final BlockPos[] pos;
		final BlockState[] state;

		Openables(List<BlockPos> a_pos, List<BlockState> a_state) {
			this.pos = a_pos.toArray(new BlockPos[0]);
			this.state = a_state.toArray(new BlockState[0]);
		}
	}

	/** Sixteenths of a block left without collision at the bottom of a whole block sitting directly on top of a door. */
	public static final int DOOR_HEADROOM = 4;

	private static boolean sitsOnDoor(ClientLevel level, BlockPos pos) {
		BlockState below = level.getBlockState(pos.below());
		return below.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
			&& below.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
			&& below.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
	}

	private static boolean isOpenable(BlockState state) {
		// Not just anything with an "open" state: a barrel has one too (while someone is looking inside), and it must stay solid.
		Block block = state.getBlock();
		return state.hasProperty(BlockStateProperties.OPEN)
			&& (block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.TrapDoorBlock
				|| block instanceof net.minecraft.world.level.block.FenceGateBlock);
	}

	/** Any block change on this client (ClientLevelDigMixin): its section, and the one next door if it is on the edge, is sent again at once. */
	public static void blockChanged(BlockPos pos, BlockState before, BlockState after) {
		if (before == after) {
			return;
		}
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		markDirtyNow(sx, sy, sz);
		int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
		if (lx == 0) {
			markDirtyNow(sx - 1, sy, sz);
		} else if (lx == 15) {
			markDirtyNow(sx + 1, sy, sz);
		}
		if (ly == 0) {
			markDirtyNow(sx, sy - 1, sz);
		} else if (ly == 15) {
			markDirtyNow(sx, sy + 1, sz);
		}
		if (lz == 0) {
			markDirtyNow(sx, sy, sz - 1);
		} else if (lz == 15) {
			markDirtyNow(sx, sy, sz + 1);
		}
	}

	/**
	 * Opening or closing a door, trapdoor or fence gate changes its collision shape and its model, but it can reach the client in ways that
	 * don't mark the section for Skyrim. So every openable block in the sections sent is looked at each frame; one that is not in the state
	 * it was sent in sends its section again, ahead of everything else (the mesh for the picture, and the solid and shape lists, which
	 * rebuild Skyrim's collision model of the section).
	 */
	private static void watchOpenable(ClientLevel level) {
		if (OPENABLES.isEmpty()) {
			return;
		}
		for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<Openables> entry : OPENABLES.long2ObjectEntrySet()) {
			Openables o = entry.getValue();
			boolean changed = false;
			for (int i = 0; i < o.pos.length; i++) {
				// SkyCraft+ : a chunk not loaded (yet, or any more) reads as void air: that's no change to send. And a door
				// broken is no longer openable; asking it "open?" threw, every frame, before any section was sent.
				if (!level.hasChunkAt(o.pos[i])) {
					continue;
				}
				BlockState now = level.getBlockState(o.pos[i]);
				if (now != o.state[i]) {
					SkyCraft.LOG.info("SkyCraft: {} at {} is now {}; its section goes to Skyrim again", now.getBlock().getDescriptionId(), o.pos[i],
						!now.hasProperty(BlockStateProperties.OPEN) ? "gone" : now.getValue(BlockStateProperties.OPEN) ? "open" : "closed");
					o.state[i] = now;
					changed = true;
				}
			}
			if (changed) {
				long key = entry.getLongKey();
				markDirtyNow(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
			}
		}
	}

	public static void markDirty(int sx, int sy, int sz) {
		synchronized (DIRTY) {
			DIRTY.add(SectionPos.asLong(sx, sy, sz));
		}
	}

	/** A block the player just placed or broke: re-mesh ahead of everything else. */
	public static void markDirtyNow(int sx, int sy, int sz) {
		synchronized (DIRTY) {
			long key = SectionPos.asLong(sx, sy, sz);
			DIRTY.addAndMoveToFirst(key);
			URGENT.add(key);
		}
	}

	public static void frame(Minecraft minecraft, float partialTick) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || !SkyLink.active()) {
			return;
		}
		// SkyCraft+ : performance. Slow frames say where the time went (a hitch flying up to a
		// structure while it loads; the next test tells which part).
		long t0 = System.nanoTime();
		long gap = lastFrameAt == 0L ? 0L : t0 - lastFrameAt;
		lastFrameAt = t0;
		int dirtyBefore;
		synchronized (DIRTY) {
			dirtyBefore = DIRTY.size();
		}
		boolean resent = false;
		if (sentGeneration != SkyLink.generation() || sentLevel != level || atlas == null || atlas.stale(minecraft)) {
			resendEverything(minecraft, level);
			resent = true;
		}
		long t1 = System.nanoTime();
		int meshedBefore = meshedTotal;
		try {
			watchOpenable(level);
		} catch (RuntimeException e) {  // SkyCraft+ : an extra never stops the blocks going to Skyrim
			OPENABLES.clear();
			SkyCraft.LOG.error("SkyCraft: watching doors and gates failed; blocks still go to Skyrim", e);
		}
		meshDirtySections(level);
		long t2 = System.nanoTime();
		forgetFar(minecraft);
		// Animated textures (water, lava, fire, ...): the frame for this game tick.
		atlas.animate(level.getGameTime(), region -> {
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(region.x()).putInt(region.y()).putInt(region.w()).putInt(region.h()).flip();
			return SkyLink.tryWriteRender(Proto.REN_ATLAS_REGION, header, region.pixels());
		});
		long t3 = System.nanoTime();
		exportEntities(minecraft, level, partialTick);
		AvatarExporter.frame(minecraft, atlas, partialTick);
		long t4 = System.nanoTime();
		if ((gap > 150_000_000L || t4 - t0 > 40_000_000L) && slowLogged < 200 && t0 - slowLoggedAt > 1_000_000_000L && gap < 5_000_000_000L) {
			slowLogged++;
			slowLoggedAt = t0;
			SkyCraft.LOG.info("SkyCraft: slow frame: {} ms since the last (ours: {} ms resend{}, {} ms meshing {} sections of {} waiting, {} ms forget/animate, {} ms entities)",
				gap / 1_000_000L, (t1 - t0) / 1_000_000L, resent ? "" : " (none)", (t2 - t1) / 1_000_000L, meshedTotal - meshedBefore, dirtyBefore,
				(t3 - t2) / 1_000_000L, (t4 - t3) / 1_000_000L);
		}
	}

	private static long lastFrameAt, slowLoggedAt;
	private static int slowLogged, meshedTotal;

	// SkyCraft+ : the Nether and the End. Skyrim keeps every section it has been sent (the mirror
	// world's builds are few, and seen from afar); a world of Minecraft's own is terrain everywhere,
	// so there what's left well beyond the render distance goes.
	private static int forgetFrames;

	private static void forgetFar(Minecraft minecraft) {
		if (++forgetFrames % 30 != 0 || !dev.skycraft.client.SkyClient.inMinecraftWorld(minecraft) || minecraft.player == null) {
			return;
		}
		int radius = minecraft.options.getEffectiveRenderDistance() + 3;
		int pcx = SectionPos.blockToSectionCoord(minecraft.player.getBlockX()), pcz = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
		LongOpenHashSet held = new LongOpenHashSet(SENT);
		held.addAll(LIT);
		held.addAll(SOLID);
		held.addAll(SHAPED);
		int forgotten = 0;
		for (long key : held.toLongArray()) {
			if (Math.abs(SectionPos.x(key) - pcx) > radius || Math.abs(SectionPos.z(key) - pcz) > radius) {
				if (minecraft.level != null && minecraft.level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) != null) {
					continue; // still loaded here: it would never be sent again
				}
				if (!forget(key)) {
					break; // ring full: carry on next time
				}
				forgotten++;
			}
		}
		if (forgotten > 0 && SkyCraft.LOG.isDebugEnabled()) {
			SkyCraft.LOG.debug("SkyCraft: {} far sections dropped from Skyrim", forgotten);
		}
	}

	/** Empties one section in Skyrim: no mesh, lights, solids or shapes. False if the ring is full. */
	private static boolean forget(long key) {
		int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
		Object[][] kinds = {
			{ Proto.REN_SECTION, SENT, null }, { Proto.REN_LIGHTS, LIT, LIGHT_SENT }, { Proto.REN_SOLIDS, SOLID, SOLID_SENT }, { Proto.REN_SHAPES, SHAPED, SHAPE_SENT }
		};
		for (Object[] kind : kinds) {
			LongOpenHashSet set = (LongOpenHashSet) kind[1];
			if (!set.contains(key)) {
				continue;
			}
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(0).flip();
			if (!SkyLink.tryWriteRender((Integer) kind[0], header, ByteBuffer.allocate(0))) {
				return false;
			}
			set.remove(key);
			if (kind[2] != null) {
				((Long2LongMap) kind[2]).remove(key); // what Skyrim holds is gone: the next mesh sends it whole
			}
		}
		return true;
	}

	private static void resendEverything(Minecraft minecraft, ClientLevel level) {
		sentGeneration = SkyLink.generation();
		sentLevel = level;
		atlas = SkyAtlas.build(minecraft);
		SkyCraft.LOG.info("SkyCraft: {} animated textures (water, lava, fire, ...) will play in Skyrim", atlas.animatedSprites());
		AvatarExporter.reset();
		ICONS.clear();
		CUBE_FACES.clear();
		boolean ao = minecraft.options.ambientOcclusion().get();
		blockRenderer = new ModelBlockRenderer(ao, true, minecraft.getBlockColors());
		fluidRenderer = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
		SkyLink.writeRender(Proto.REN_CLEAR_ALL, ByteBuffer.allocate(0), null);
		boolean ok = SkyLink.sendAtlas(atlas.width, atlas.height, atlas.pixels);
		SkyCraft.LOG.info("SkyCraft: sent {}x{} texture atlas to Skyrim ({})", atlas.width, atlas.height, ok ? "ok" : "FAILED");
		SkyGeoTextures.atlasSent(atlas);  // SkyCraft+ : Skyrim geo (its textures drawn into the new atlas)
		SENT.clear();
		LIT.clear();
		SOLID.clear();
		SHAPED.clear();
		DUG.clear();
		LIGHT_SENT.clear();
		SOLID_SENT.clear();
		SHAPE_SENT.clear();
		DUG_SENT.clear();
		OPENABLES.clear();
		dev.skycraft.client.SkyDigClient.resendAll();
		// Everything already loaded needs meshing again; later chunk loads mark themselves dirty.
		int radius = minecraft.options.getEffectiveRenderDistance() + 1;
		int pcx = SectionPos.blockToSectionCoord(minecraft.player.getBlockX()), pcz = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					if (!sections[i].hasOnlyAir()) {
						markDirty(cx, chunk.getSectionYFromSectionIndex(i), cz);
					}
				}
			}
		}
	}

	private static void meshDirtySections(ClientLevel level) {
		// Chunk loads and light updates dirty thousands of all-air sections; those cost a lookup.
		// Real meshing is limited per frame.
		dropIdle(level);
		long[] order = nearestFirst();
		// SkyCraft+ : the time is counted from here: counted from before the sorting, a hundred thousand
		// waiting took the whole budget to sort, nothing was meshed again, and every block placed or
		// broken stayed as it was in Skyrim (only its collision changed).
		long deadline = System.nanoTime() + 3_000_000L;
		int meshed = 0;
		for (int i = 0; i < order.length && meshed < SECTIONS_PER_FRAME && System.nanoTime() < deadline; i++) {
			long key = order[i];
			synchronized (DIRTY) {
				if (!DIRTY.remove(key)) {
					continue;
				}
				URGENT.remove(key);
			}
			if (meshSection(level, key)) {
				meshedTotal++;
				meshed++;
			}
		}
	}

	/**
	 * SkyCraft+ : the oldest waiting sections that have nothing to send (all air that Skyrim holds
	 * nothing for, or in a chunk not loaded) leave the queue, about 2 ms a frame while it is long, so
	 * sorting it every frame stays cheap. The same as meshSection would decide for them.
	 */
	private static void dropIdle(ClientLevel level) {
		long[] oldest;
		synchronized (DIRTY) {
			if (DIRTY.size() <= 2048) {
				return;
			}
			oldest = new long[Math.min(DIRTY.size(), 32768)];
			var it = DIRTY.iterator();
			for (int i = 0; i < oldest.length; i++) {
				oldest[i] = it.nextLong();
			}
		}
		long deadline = System.nanoTime() + 2_000_000L;
		LongArrayList idle = new LongArrayList();
		for (int i = 0; i < oldest.length && ((i & 63) != 0 || System.nanoTime() < deadline); i++) {
			long key = oldest[i];
			if (URGENT.contains(key)) {
				continue;
			}
			int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
			LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
			if (chunk == null) {
				idle.add(key); // unloaded: Skyrim keeps what it has (a chunk loading marks itself again)
				continue;
			}
			LevelChunkSection section = sectionAt(level, chunk, sy);
			if ((section == null || section.hasOnlyAir()) && !SENT.contains(key) && !LIT.contains(key) && !SOLID.contains(key) && !DUG.contains(key)
				&& dev.skycraft.client.SkyDigClient.dugBits(chunk, sy) == null) {
				idle.add(key);
			}
		}
		synchronized (DIRTY) {
			for (int i = 0; i < idle.size(); i++) {
				long key = idle.getLong(i);
				if (!URGENT.contains(key)) {
					DIRTY.remove(key);
				}
			}
		}
	}

	/**
	 * SkyCraft+ : performance. The dirty sections, most wanted first: what the player just changed,
	 * then by distance from the camera, those behind it counting as four times as far (a cave or a
	 * structure opens in front of the player first, rather than in the order it was revealed).
	 * SkyCraft+ : Minecraft patches. Out of all of them (only the oldest 4096 were looked at: with
	 * twenty thousand waiting, the grass just placed round the player came after all of those), the
	 * nearest PICK_AT_MOST.
	 */
	private static final LongOpenHashSet URGENT = new LongOpenHashSet();
	private static final int PICK_AT_MOST = 256;

	private static long[] nearestFirst() {
		long[] keys;
		long[] urgent;
		synchronized (DIRTY) {
			keys = DIRTY.toLongArray();
			urgent = URGENT.toLongArray();
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (keys.length <= 1 || minecraft.player == null) {
			return keys;
		}
		LongOpenHashSet urgentSet = new LongOpenHashSet(urgent);
		var eye = minecraft.player.getEyePosition();
		var look = minecraft.player.getViewVector(1.0F);
		boolean inTheOpen = minecraft.level != null && minecraft.level.canSeeSky(minecraft.player.blockPosition().above());
		// (Measured from the ground: flying, the land itself was "under" the player and waited.)
		double groundY = minecraft.level == null ? eye.y
			: Math.min(eye.y, minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, minecraft.player.getBlockX(), minecraft.player.getBlockZ()) + 16.0);
		// The nearest few: a max-heap of (score << 32 | index), its worst on top.
		int k = Math.min(PICK_AT_MOST, keys.length);
		long[] heap = new long[k];
		int size = 0;
		for (int i = 0; i < keys.length; i++) {
			long key = keys[i];
			double dx = SectionPos.x(key) * 16 + 8 - eye.x, dy = SectionPos.y(key) * 16 + 8 - eye.y, dz = SectionPos.z(key) * 16 + 8 - eye.z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (dx * look.x + dy * look.y + dz * look.z < -16.0) {
				d2 *= 4.0;
			}
			// SkyCraft+ : Minecraft patches. Out in the open, what's under the ground waits: the
			// grass and trees round the player came long after the caves beneath them.
			if (inTheOpen && SectionPos.y(key) * 16 + 8 - groundY < -12.0) {
				d2 *= 8.0;
			}
			long score = urgentSet.contains(key) ? 0L : Math.min((long) d2 + 1L, 0x7FFFFFFFL);
			long packed = (score << 32) | i;
			if (size < k) {
				heap[size] = packed;
				for (int c = size++; c > 0 && heap[(c - 1) / 2] < heap[c]; c = (c - 1) / 2) {
					long t = heap[c];
					heap[c] = heap[(c - 1) / 2];
					heap[(c - 1) / 2] = t;
				}
			} else if (packed < heap[0]) {
				heap[0] = packed;
				for (int p = 0;;) {
					int l = 2 * p + 1, r = l + 1, m = p;
					if (l < k && heap[l] > heap[m]) {
						m = l;
					}
					if (r < k && heap[r] > heap[m]) {
						m = r;
					}
					if (m == p) {
						break;
					}
					long t = heap[p];
					heap[p] = heap[m];
					heap[m] = t;
					p = m;
				}
			}
		}
		java.util.Arrays.sort(heap, 0, size);
		long[] order = new long[size];
		for (int i = 0; i < size; i++) {
			order[i] = keys[(int) heap[i]];
		}
		return order;
	}

	/** One RenShapeBox: a box of a block's collision shape in sixteenths, rounded outwards. */
	private static void putShapeBox(int cell, AABB box) {
		if (shapeBoxes.remaining() < 8) {
			ByteBuffer bigger = ByteBuffer.allocate(shapeBoxes.capacity() * 2).order(ByteOrder.LITTLE_ENDIAN);
			bigger.put(shapeBoxes.flip());
			shapeBoxes = bigger;
		}
		shapeBoxes.putShort((short) cell);
		shapeBoxes.put(sixteenths(Math.floor(box.minX * 16.0))).put(sixteenths(Math.floor(box.minY * 16.0))).put(sixteenths(Math.floor(box.minZ * 16.0)));
		shapeBoxes.put(sixteenths(Math.ceil(box.maxX * 16.0))).put(sixteenths(Math.ceil(box.maxY * 16.0))).put(sixteenths(Math.ceil(box.maxZ * 16.0)));
	}

	private static byte sixteenths(double v) {
		return (byte) Mth.clamp((int) v, 0, 255);
	}

	/** FNV-1a over a buffer's first {@code length} bytes: a section's lights or its shape boxes. */
	private static long hash(ByteBuffer buf, int length) {
		long h = 0xcbf29ce484222325L;
		for (int i = 0; i < length; i++) {
			h = (h ^ (buf.get(i) & 0xFF)) * 0x100000001b3L;
		}
		return h;
	}

	/** FNV-1a over some 64-bit words: a section's collision bits (a null array is all zero). */
	private static long hash(long[] words, int world) {
		long h = 0xcbf29ce484222325L ^ world;
		if (words != null) {
			for (long w : words) {
				h = (h ^ w) * 0x100000001b3L;
			}
		}
		return h;
	}

	private static LevelChunkSection sectionAt(ClientLevel level, LevelChunk chunk, int sy) {
		int index = level.getSectionIndexFromSectionY(sy);
		return index >= 0 && index < chunk.getSections().length ? chunk.getSections()[index] : null;
	}

	/** Returns true if real meshing work was done. */
	private static boolean meshSection(ClientLevel level, long key) {
		int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) {
			return false; // unloaded: Skyrim keeps what it has
		}
		LevelChunkSection section = sectionAt(level, chunk, sy);
		boolean empty = section == null || section.hasOnlyAir();
		if (empty) {
			OPENABLES.remove(key);
		}
		long[] dug = dev.skycraft.client.SkyDigClient.dugBits(chunk, sy);
		int dugCount = 0;
		if (dug != null) {
			for (long word : dug) {
				dugCount += Long.bitCount(word);
			}
		}
		if (empty && !SENT.contains(key) && !LIT.contains(key) && !SOLID.contains(key) && dugCount == 0 && !DUG.contains(key)) {
			return false; // nothing there and nothing to remove
		}
		MESH.reset();
		LIGHTS.clear();
		int lightCount = 0;
		java.util.Arrays.fill(SOLID_BITS, 0L);
		int solidCount = 0;
		shapeBoxes.clear();
		int shapeCount = 0;
		MESH.cardinal = level.cardinalLighting();
		List<BlockPos> openPos = null;
		List<BlockState> openState = null;
		boolean underground = level.dimension() == net.minecraft.world.level.Level.OVERWORLD && dev.skycraft.world.SkyUnderground.enabled();
		for (int c = 0; c < 324; c++) {
			int cx = c % 18 - 1, cz = c / 18 - 1;
			double land = underground ? dev.skycraft.world.SkyGround.lowestAround(sx * 16 + cx + 0.5, sz * 16 + cz + 0.5) : Double.NaN;
			if (cx >= 0 && cx < 16 && cz >= 0 && cz < 16) {
				MESH.skyCut[cx + 16 * cz] = Double.isNaN(land) ? Float.NEGATIVE_INFINITY : (float) (land - 3.0 - sy * 16);
			}
			MESH.rockBelow[c] = Double.isNaN(land) ? Float.NEGATIVE_INFINITY : (float) (land - dev.skycraft.world.SkyUnderground.DEEP - sy * 16);
		}
		MESH.rockDug = underground ? dev.skycraft.world.SkyDig.clientDug : null;
		MESH.rockOrigin = SectionPos.of(sx, sy, sz).origin();
		if (!empty) {
			BlockPos origin = SectionPos.of(sx, sy, sz).origin();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
						BlockState state = chunk.getBlockState(pos);
						if (state.isAir()) {
							continue;
						}
						if (isOpenable(state)) {
							if (openPos == null) {
								openPos = new ArrayList<>();
								openState = new ArrayList<>();
							}
							openPos.add(pos.immutable());
							openState.add(state);
						}
						// Blocks Skyrim's NPCs can't walk through (anything with a collision shape).
						VoxelShape collision = state.getCollisionShape(level, pos);
						if (!collision.isEmpty()) {
							int bit = x + 16 * z + 256 * y;
							SOLID_BITS[bit >> 6] |= 1L << (bit & 63);
							solidCount++;
							// A whole block right above a door: a small gap at its bottom has no collision, so Skyrim's taller player fits through the doorway.
							if (Block.isShapeFullBlock(collision) && sitsOnDoor(level, pos)) {
								putShapeBox(bit, new AABB(0.0, DOOR_HEADROOM / 16.0, 0.0, 1.0, 1.0, 1.0));
								shapeCount++;
							}
							// Not a whole block: its real boxes, so characters fit through open doors
							// and walk up stairs and slabs.
							if (!Block.isShapeFullBlock(collision)) {
								// For NPCs: a step to walk onto (whole bottom, open top: slabs, stairs,
								// carpets), or something mobs walk through (open doors).
								int cell = bit;
								if (Block.isFaceFull(collision, Direction.DOWN) && !Block.isFaceFull(collision, Direction.UP)) {
									cell |= SHAPE_STEP;
								}
								if (state.isPathfindable(PathComputationType.LAND)) {
									cell |= SHAPE_PASSABLE;
								}
								for (AABB box : collision.toAabbs()) {
									putShapeBox(cell, box);
									shapeCount++;
								}
							}
						}
						// Light-emitting blocks (torches, lava, glowstone, ...) light Skyrim's world too.
						int emission = state.getLightEmission();
						if (emission > 0) {
							LIGHTS.put((byte) x).put((byte) y).put((byte) z).put((byte) emission).putInt(BlockLightColors.of(state));
							lightCount++;
						}
						FluidState fluid = state.getFluidState();
						if (!fluid.isEmpty()) {
							// Skyrim ground in the cell: the fluid is drawn in the space above it.
							MESH.fluidGround = dev.skycraft.world.SkyCollision.groundTop(pos);
							MESH.fluidBaseY = y;
							fluidRenderer.tesselate(level, pos, MESH, state, fluid);
							MESH.fluidGround = 0.0F;
						}
						if (state.getRenderShape() == RenderShape.MODEL) {
							var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
							// FabricBlockModels handles a model written for Fabric's renderer API (Traveler's
							// Backpack, ...): it leaves collectParts empty and draws through emitQuads, which
							// vanilla's renderer below would see as nothing. Delete these lines and the file
							// and it is gone again (RenderToggles.fabricQuads switches it off).
							if (!RenderToggles.fabricQuads
								|| !FabricBlockModels.tesselate(atlas, x, y, z, level, pos.immutable(), state, model, state.getSeed(pos), MESH::vertex)) {
								blockRenderer.tesselateBlock(MESH, x, y, z, level, pos.immutable(), state, model, state.getSeed(pos));
							}
						}
						// SkyCraft+ : Skyrim geo. Pieces of Skyrim and Skyrim-textured blocks (no model of their own).
						if (state.is(dev.skycraft.world.SkyGeo.GEO) || state.is(dev.skycraft.world.SkyGeo.BLOCK)) {
							geoFaces(level, pos.immutable(), x, y, z, key);
						}
						// SkyCraft+ : the End. End portals and gateways are drawn by a shader of Minecraft's
						// own that never reaches Skyrim: a glowing obsidian surface stands in for them.
						if (state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)) {
							portalFaces(state.is(Blocks.END_GATEWAY), x, y, z);
						}
					}
				}
			}
		}
		if (openPos != null) {
			OPENABLES.put(key, new Openables(openPos, openState));
		} else {
			OPENABLES.remove(key);
		}
		// Holes dug into Skyrim's ground: Minecraft walls where its surface still runs above them.
		var digLookup = dev.skycraft.world.SkyDig.clientDug;
		if (dugCount > 0 && digLookup != null) {
			Minecraft minecraft = Minecraft.getInstance();
			DigWalls.add(level, sx, sy, sz, dug, digLookup, st -> cubeFaces(minecraft, st), MESH::wall);
		}
		if (MESH.vertexCount() == 0 && !SENT.contains(key) && lightCount == 0 && !LIT.contains(key) && solidCount == 0 && !SOLID.contains(key) && dugCount == 0
			&& !DUG.contains(key)) {
			return true;
		}
		ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(MESH.vertexCount()).flip();
		if (SkyLink.writeRender(Proto.REN_SECTION, header, MESH.bytes())) {
			if (MESH.vertexCount() > 0) {
				SENT.add(key);
			} else {
				SENT.remove(key);
			}
			if (lightCount > 0 || LIT.contains(key)) {
				long hash = hash(LIGHTS, lightCount * 8);
				// Skyrim already has exactly this: the section was re-meshed for a block that isn't a light.
				if (!LIT.contains(key) || !LIGHT_SENT.containsKey(key) || LIGHT_SENT.get(key) != hash) {
					ByteBuffer lightHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(lightCount).flip();
					if (!SkyLink.writeRender(Proto.REN_LIGHTS, lightHeader, LIGHTS.flip())) {
						markDirty(sx, sy, sz); // ring full; send both again later
					} else {
						LIGHT_SENT.put(key, hash);
						if (lightCount > 0) {
							LIT.add(key);
						} else {
							LIT.remove(key);
						}
					}
				}
			}
			if (solidCount > 0 || SOLID.contains(key)) {
				long hash = hash(SOLID_BITS, 0);
				if (!SOLID.contains(key) || !SOLID_SENT.containsKey(key) || SOLID_SENT.get(key) != hash) {
					ByteBuffer solidHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(solidCount).flip();
					ByteBuffer bits = ByteBuffer.allocate(solidCount > 0 ? 512 : 0).order(ByteOrder.LITTLE_ENDIAN);
					if (solidCount > 0) {
						for (long word : SOLID_BITS) {
							bits.putLong(word);
						}
					}
					if (!SkyLink.writeRender(Proto.REN_SOLIDS, solidHeader, bits.flip())) {
						markDirty(sx, sy, sz);
					} else {
						SOLID_SENT.put(key, hash);
						if (solidCount > 0) {
							SOLID.add(key);
						} else {
							SOLID.remove(key);
						}
					}
				}
			}
			if (shapeCount > 0 || SHAPED.contains(key)) {
				long hash = hash(shapeBoxes, shapeCount * 8);
				if (!SHAPED.contains(key) || !SHAPE_SENT.containsKey(key) || SHAPE_SENT.get(key) != hash) {
					ByteBuffer shapeHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(shapeCount).flip();
					if (!SkyLink.writeRender(Proto.REN_SHAPES, shapeHeader, shapeBoxes.flip())) {
						markDirty(sx, sy, sz);
					} else {
						SHAPE_SENT.put(key, hash);
						if (shapeCount > 0) {
							SHAPED.add(key);
						} else {
							SHAPED.remove(key);
						}
					}
				}
			}
			// Cells dug out of Skyrim's world: its geometry there goes.
			if (dugCount > 0 || DUG.contains(key)) {
				long hash = hash(dug, dev.skycraft.client.SkyDigClient.world());
				if (!DUG.contains(key) || !DUG_SENT.containsKey(key) || DUG_SENT.get(key) != hash) {
					ByteBuffer dugHeader = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(dugCount)
						.putInt(dev.skycraft.client.SkyDigClient.world()).putInt(0).flip();
					ByteBuffer bits = ByteBuffer.allocate(dugCount > 0 ? 512 : 0).order(ByteOrder.LITTLE_ENDIAN);
					if (dugCount > 0) {
						for (long word : dug) {
							bits.putLong(word);
						}
					}
					if (!SkyLink.writeRender(Proto.REN_DUG, dugHeader, bits.flip())) {
						markDirty(sx, sy, sz);
					} else {
						DUG_SENT.put(key, hash);
						if (dugCount > 0) {
							DUG.add(key);
						} else {
							DUG.remove(key);
						}
					}
				}
			}
			if (++meshesSent <= 10 || meshesSent % 200 == 0) {
				SkyCraft.LOG.info("SkyCraft: block mesh for section {} {} {}: {} vertices ({} sections in Skyrim)", sx, sy, sz, MESH.vertexCount(), SENT.size());
			}
		} else {
			markDirty(sx, sy, sz); // ring full; try again later
		}
		return true;
	}

	/** Mobs Skyrim's player can lock on to (True Directional Movement's virtual targets): every living thing but the player, hostile or not. */
	private static final int MAX_LOCKABLE_MOBS = 24;

	private static boolean isLockable(Minecraft minecraft, net.minecraft.world.entity.LivingEntity living) {
		return living != minecraft.player && !living.isDeadOrDying() && !living.isRemoved();
	}

	/** A mob's name as the 8 floats the entity record's uv holds, which Skyrim reads back as 32 bytes of zero-ended UTF-8. */
	private static float[] nameBits(String name) {
		byte[] raw = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		int length = Math.min(raw.length, Proto.MOB_NAME_BYTES - 1);
		while (length > 0 && length < raw.length && (raw[length] & 0xC0) == 0x80) {
			--length; // don't cut a multi-byte character in half
		}
		byte[] padded = new byte[Proto.MOB_NAME_BYTES];
		System.arraycopy(raw, 0, padded, 0, length);
		float[] out = new float[Proto.MOB_NAME_BYTES / 4];
		ByteBuffer buffer = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < out.length; i++) {
			out[i] = Float.intBitsToFloat(buffer.getInt());
		}
		return out;
	}

	private static void exportEntities(Minecraft minecraft, ClientLevel level, float partialTick) {
		ENTITIES.clear();
		int lockableMobs = 0;
		Vec3 eye = minecraft.player.getEyePosition(partialTick);
		for (Entity e : level.entitiesForRendering()) {
			if (ENTITIES.size() >= Proto.MAX_WORLD_ENTITIES || e.distanceToSqr(eye) > ENTITY_RANGE * ENTITY_RANGE) {
				continue;
			}
			Vec3 p = e.getPosition(partialTick);
			if (e instanceof AbstractArrow arrow) {
				boolean trident = arrow instanceof ThrownTrident;
				int kind = trident ? Proto.WE_TRIDENT : Proto.WE_ARROW;
				float yaw = Mth.rotLerp(partialTick, arrow.yRotO, arrow.getYRot());
				float pitch = Mth.lerp(partialTick, arrow.xRotO, arrow.getXRot());
				// Arrows use Minecraft's arrow model and entity texture; tridents their item icon.
				float[] uv = trident ? ICONS.computeIfAbsent(Items.TRIDENT, i -> iconUv(minecraft, level, new ItemStack(i)))
					: atlas.arrowUv(arrow instanceof SpectralArrow ? 2 : arrow instanceof Arrow tippable && tippable.getColor() > 0 ? 1 : 0);
				if (uv != null) {
					ENTITIES.add(new SkyLink.WorldEntity(kind, e.getId(), (float) p.x, (float) p.y, (float) p.z, yaw, pitch, 1.0F, null, uv, 0));
				}
			} else if (e instanceof ItemEntity item) {
				// Off by default: RenderToggles.itemModels hands this (and thrown items) to the same
				// capture as every other entity, which draws the real model.
				if (!RenderToggles.itemModels) {
					float bob = Mth.sin((item.getAge() + partialTick) / 10.0F + item.bobOffs) * 0.1F + 0.1F;
					float spin = ItemEntity.getSpin(item.getAge() + partialTick, item.bobOffs) * Mth.RAD_TO_DEG;
					addItem(minecraft, level, e, item.getItem(), p.add(0, bob, 0), spin);
				}
			} else if (e instanceof ItemSupplier supplier) {
				if (!RenderToggles.itemModels) {
					addItem(minecraft, level, e, supplier.getItem(), p.add(0, e.getBbHeight() * 0.5 - 0.25, 0), 0.0F);
				}
			} else if (e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof dev.skycraft.combat.SkyrimActorEntity) && !e.isInvisible()
				&& (e != minecraft.player || minecraft.gameRenderer.mainCamera().isDetached())) {
				// Players and mobs: Skyrim darkens the ground softly under their feet.
				ENTITIES.add(new SkyLink.WorldEntity(Proto.WE_SHADOW, e.getId(), (float) p.x, (float) p.y, (float) p.z, 0.0F, 0.0F, e.getBbWidth(), null, null, 0));
				if (e != minecraft.player && lockableMobs < MAX_LOCKABLE_MOBS && ENTITIES.size() < Proto.MAX_WORLD_ENTITIES && isLockable(minecraft, (net.minecraft.world.entity.LivingEntity) e)) {
					net.minecraft.world.entity.LivingEntity living = (net.minecraft.world.entity.LivingEntity) e;
					float max = living.getMaxHealth();
					float health = max > 0.0F ? Mth.clamp(living.getHealth() / max, 0.0F, 1.0F) : -1.0F;
					ENTITIES.add(new SkyLink.WorldEntity(Proto.WE_MOB, e.getId(), (float) p.x, (float) p.y, (float) p.z, 0.0F, 0.0F, e.getBbHeight(),
						new float[] { health, e.getBbWidth(), 0.0F }, nameBits(e.getName().getString()), 0));
					++lockableMobs;
				}
			}
		}
		addCracks(level);
		// SkyCraft+ : each one lit by Minecraft's light where it is (a dropped item down a mine isn't in daylight).
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		SkyLink.writeWorldEntities(ENTITIES, selection(minecraft, level), w -> {
			at.set(Mth.floor(w.x()), Mth.floor(w.y() + 0.05F), Mth.floor(w.z()));
			return 0x80000000 | level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at)
				| level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at) << 8;
		});
	}

	/**
	 * A dropped item the light way (RenderToggles.itemModels off): blocks as small spinning cubes
	 * with their own face textures, everything else as its icon. {@code p} is the item's resting
	 * point (bottom).
	 */
	private static void addItem(Minecraft minecraft, ClientLevel level, Entity e, ItemStack stack, Vec3 p, float yaw) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			BlockState state = blockItem.getBlock().defaultBlockState();
			if (state.getRenderShape() == RenderShape.MODEL
				&& Block.isShapeFullBlock(state.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
				float[] faces = cubeFaces(minecraft, state);
				if (faces != null) {
					float size = 0.25F;
					int tint = cubeTint(minecraft, state);
					ENTITIES.add(new SkyLink.WorldEntity(
						Proto.WE_BLOCK, e.getId(), (float) p.x, (float) (p.y + size * 0.5 + 0.02), (float) p.z, yaw, 0.0F, size, null, faces, tint
					));
					return;
				}
			}
		}
		float[] uv = iconUv(minecraft, level, stack);
		if (uv != null) {
			ENTITIES.add(new SkyLink.WorldEntity(Proto.WE_ITEM, e.getId(), (float) p.x, (float) p.y + 0.25F, (float) p.z, yaw, 0.0F, 0.5F, null, uv, 0));
		}
	}

	/**
	 * SkyCraft+ : the End. An End portal's surface (12/16 up, seen from both sides) or a gateway's
	 * whole cube, in obsidian's dark speckled texture at full brightness. Section-relative cell.
	 */
	private static void portalFaces(boolean gateway, int x, int y, int z) {
		float[] rects = cubeFaces(Minecraft.getInstance(), Blocks.OBSIDIAN.defaultBlockState());
		if (rects == null) {
			return;
		}
		int light = net.minecraft.util.LightCoordsUtil.pack(15, 15);
		float u0 = rects[4], v0 = rects[5], u1 = rects[6], v1 = rects[7];
		float[] uv = { u0, v0, u1, v0, u1, v1, u0, v1 };
		float top = gateway ? 1.0F : 0.75F, bottom = gateway ? 0.0F : 0.75F;
		// Up and down (the portal's surface is seen from above, a gateway from anywhere).
		MESH.wall(new float[] { x, y + top, z, x + 1, y + top, z, x + 1, y + top, z + 1, x, y + top, z + 1 }, uv, light, Direction.UP);
		MESH.wall(new float[] { x, y + bottom, z, x, y + bottom, z + 1, x + 1, y + bottom, z + 1, x + 1, y + bottom, z }, uv, light, Direction.DOWN);
		if (gateway) {
			MESH.wall(new float[] { x, y, z, x, y + 1, z, x + 1, y + 1, z, x + 1, y, z }, uv, light, Direction.NORTH);
			MESH.wall(new float[] { x, y, z + 1, x + 1, y, z + 1, x + 1, y + 1, z + 1, x, y + 1, z + 1 }, uv, light, Direction.SOUTH);
			MESH.wall(new float[] { x, y, z, x, y, z + 1, x, y + 1, z + 1, x, y + 1, z }, uv, light, Direction.WEST);
			MESH.wall(new float[] { x + 1, y, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, x + 1, y, z + 1 }, uv, light, Direction.EAST);
		}
	}

	/**
	 * SkyCraft+ : Skyrim blocks. How many blocks one of Skyrim's textures covers: 5x5, as Skyrim's own
	 * scale (its ground textures repeat every five or so blocks), so a wall or floor of them looks like
	 * Skyrim's ground, not a close-up of it.
	 */
	private static final int GEO_TILE = 5;

	/**
	 * SkyCraft+ : Skyrim blocks. A Skyrim-textured block (SkyGeo): a cube in its Skyrim texture
	 * (SkyGeoTextures), each block its part of the 5x5 the picture spreads over, sampled smooth (as
	 * Skyrim draws it); with no texture known yet, in what it's made of. Faces against a whole
	 * neighbour are left out. Section-relative cell.
	 */
	private static void geoFaces(ClientLevel level, BlockPos pos, int x, int y, int z, long key) {
		if (!(level.getBlockEntity(pos) instanceof dev.skycraft.world.SkyGeo.GeoEntity entity)) {
			return;
		}
		dev.skycraft.world.SkyGeo.Piece piece = entity.piece();
		float[] sky = piece.texture().isEmpty() ? null : SkyGeoTextures.uv(atlas, piece.texture(), key);
		float[] fillRects = sky != null ? null
			: cubeFaces(Minecraft.getInstance(), dev.skycraft.world.SkyDig.materialState(piece.material() == Proto.DIG_GRASS ? Proto.DIG_DIRT : piece.material()));
		if (sky == null && fillRects == null) {
			return;
		}
		// The texture's part for this block: its place in the 5x5 the picture spreads over.
		float tu = Mth.positiveModulo(pos.getX(), GEO_TILE) / (float) GEO_TILE, tv = Mth.positiveModulo(pos.getZ(), GEO_TILE) / (float) GEO_TILE;
		float ty = Mth.positiveModulo(-pos.getY() - 1, GEO_TILE) / (float) GEO_TILE;
		for (Direction d : Direction.values()) {
			BlockPos next = pos.relative(d);
			if (level.getBlockState(next).isSolidRender()) {
				continue;
			}
			int light = geoLight(level, next);
			float[] r = sky != null ? sky : d.getAxis() == Direction.Axis.Y ? java.util.Arrays.copyOfRange(fillRects, d == Direction.UP ? 4 : 8, d == Direction.UP ? 8 : 12)
				: java.util.Arrays.copyOfRange(fillRects, 0, 4);
			float a = 0.0F, b = 0.0F, span = 1.0F;
			if (sky != null) {
				a = d.getAxis() == Direction.Axis.X ? tv : tu;
				b = d.getAxis() == Direction.Axis.Y ? tv : ty;
				span = 1.0F / GEO_TILE;
			}
			float u0 = r[0] + (r[2] - r[0]) * a, v0 = r[1] + (r[3] - r[1]) * b, u1 = u0 + (r[2] - r[0]) * span, v1 = v0 + (r[3] - r[1]) * span;
			float[] uv = { u0, v1, u1, v1, u1, v0, u0, v0 };
			if (sky != null) {
				MESH.wallSmooth(cubeFace(d, x, y, z), uv, light, d);
			} else {
				MESH.wall(cubeFace(d, x, y, z), uv, light, d);
			}
		}
	}

	/** SkyCraft+ : Skyrim blocks. The light a face gets from the cell it faces. */
	private static int geoLight(ClientLevel level, BlockPos pos) {
		return net.minecraft.util.LightCoordsUtil.pack(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos),
			level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
	}

	/** SkyCraft+ : a whole block's face (section-relative), corners in the order wall() takes them. */
	private static float[] cubeFace(Direction d, int x, int y, int z) {
		return switch (d) {
			case UP -> new float[] { x, y + 1, z, x, y + 1, z + 1, x + 1, y + 1, z + 1, x + 1, y + 1, z };
			case DOWN -> new float[] { x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1 };
			case NORTH -> new float[] { x + 1, y, z, x, y, z, x, y + 1, z, x + 1, y + 1, z };
			case SOUTH -> new float[] { x, y, z + 1, x + 1, y, z + 1, x + 1, y + 1, z + 1, x, y + 1, z + 1 };
			case WEST -> new float[] { x, y, z, x, y, z + 1, x, y + 1, z + 1, x, y + 1, z };
			case EAST -> new float[] { x + 1, y, z + 1, x + 1, y, z, x + 1, y + 1, z, x + 1, y + 1, z + 1 };
		};
	}

	/** Side, top and bottom atlas rects of a full-cube block's model, cached per block state. */
	private static float[] cubeFaces(Minecraft minecraft, BlockState state) {
		return CUBE_FACES.computeIfAbsent(state, s -> {
			var model = minecraft.getModelManager().getBlockStateModelSet().get(s);
			var parts = new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
			RANDOM.setSeed(42);
			model.collectParts(RANDOM, parts);
			float[] out = new float[12];
			Direction[] dirs = { Direction.NORTH, Direction.UP, Direction.DOWN };
			for (int f = 0; f < 3; f++) {
				TextureAtlasSprite sprite = null;
				for (var part : parts) {
					var quads = part.getQuads(dirs[f]);
					if (!quads.isEmpty()) {
						sprite = quads.getFirst().materialInfo().sprite();
						break;
					}
				}
				if (sprite == null) {
					return null;
				}
				System.arraycopy(atlas.rect(sprite), 0, out, f * 4, 4);
			}
			return out;
		});
	}

	/** Grass and leaves are grey in the atlas; Minecraft tints them. RGBA8, 0 for none. */
	private static int cubeTint(Minecraft minecraft, BlockState state) {
		var sources = minecraft.getBlockColors().getTintSources(state);
		if (sources.isEmpty() || sources.getFirst() == null) {
			return 0;
		}
		int argb = sources.getFirst().color(state);
		return 0xFF000000 | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
	}

	/** Cracks over blocks being mined (ours and anyone else's). */
	private static void addCracks(ClientLevel level) {
		for (BlockDestructionProgress progress : ((dev.skycraft.client.mixin.ClientLevelAccessor) level).skycraft$destroyingBlocks().values()) {
			int stage = progress.getProgress();
			if (stage < 0 || stage > 9 || ENTITIES.size() >= Proto.MAX_WORLD_ENTITIES) {
				continue;
			}
			BlockPos pos = progress.getPos();
			VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
			if (shape.isEmpty()) {
				continue;
			}
			AABB box = shape.bounds().move(pos).inflate(0.004);
			ENTITIES.add(new SkyLink.WorldEntity(
				Proto.WE_CRACK, pos.hashCode(), (float) box.minX, (float) box.minY, (float) box.minZ, 0.0F, 0.0F, 1.0F,
				new float[] { (float) box.getXsize(), (float) box.getYsize(), (float) box.getZsize() }, atlas.crackUv(stage), 0
			));
		}
	}

	/** The item's icon in the combined atlas {u0, v0, u1, v1}, or null. */
	private static float[] iconUv(Minecraft minecraft, ClientLevel level, ItemStack stack) {
		minecraft.getItemModelResolver().updateForTopItem(ITEM_STATE, stack, ItemDisplayContext.GROUND, level, null, 0);
		RANDOM.setSeed(0);
		var material = ITEM_STATE.pickParticleMaterial(RANDOM);
		if (material == null) {
			return null;
		}
		return atlas.rect(material.sprite());
	}

	/** Outline for Skyrim to draw: the targeted block, or where a held block would go on Skyrim ground. */
	private static float[] selection(Minecraft minecraft, ClientLevel level) {
		HitResult hit = minecraft.hitResult;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK || minecraft.gui.screen() != null) {
			return null;
		}
		BlockPos pos = blockHit.getBlockPos();
		if (blockHit instanceof SkyClip.SkyrimHitResult) {
			if (!(minecraft.player.getMainHandItem().getItem() instanceof BlockItem)) {
				return null;
			}
			return new float[] { pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1 };
		}
		VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
		if (shape.isEmpty()) {
			return null;
		}
		AABB box = shape.bounds().move(pos);
		return new float[] { (float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.maxY, (float) box.maxZ };
	}

	/**
	 * Collects Minecraft's block quads (and fluid vertices) as triangles in the RenVertex layout:
	 * section-relative position, combined-atlas UV, RGBA colour (tint and shading), block/sky light.
	 */
	private static final class MeshBuilder implements net.minecraft.client.renderer.block.BlockQuadOutput, FluidRenderer.Output, VertexConsumer {
		private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
		private int vertices;
		// fluid quad assembly
		private final float[] fq = new float[4 * 8];
		private int fqCount;
		private boolean fluidTranslucent;

		void reset() {
			this.buf.clear();
			this.vertices = 0;
			this.fqCount = 0;
		}

		int vertexCount() {
			return this.vertices;
		}

		ByteBuffer bytes() {
			return this.buf.duplicate().flip();
		}

		private void ensure(int bytes) {
			if (this.buf.remaining() < bytes) {
				ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(this.buf.capacity() * 2, this.buf.position() + bytes)).order(ByteOrder.LITTLE_ENDIAN);
				this.buf.flip();
				bigger.put(this.buf);
				this.buf = bigger;
			}
		}

		/** The level's fixed per-face brightness, which Skyrim's lighting replaces. */
		CardinalLighting cardinal = CardinalLighting.DEFAULT;

		private void vertex(float x, float y, float z, float u, float v, int argb, int light, int flags) {
			this.buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
			this.buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
			int block = (light >> 4) & 0xF;
			int sky = (light >> 20) & 0xF;
			// SkyCraft+ : the underground (see skyCut).
			int col = Mth.clamp((int) x, 0, 15) + 16 * Mth.clamp((int) z, 0, 15);
			if (y < this.skyCut[col]) {
				sky = 0;
			}
			this.buf.putInt(block | (sky << 8));
			this.buf.putInt(flags);
			this.vertices++;
		}

		/**
		 * Vertex flags: cutout or translucent, plus the face normal (Direction ordinal + 1) that
		 * Skyrim lights the face with. 0 leaves it without a normal (plants and other quads Minecraft
		 * doesn't shade by direction).
		 */
		private static int flags(boolean translucent, Direction normal) {
			return (translucent ? 2 : 1) | (normal == null ? 0 : (normal.ordinal() + 1) << 4);
		}

		/** Takes Minecraft's fixed face brightness back out of a colour, leaving tint and ambient occlusion. */
		private static int unshade(int argb, float shade) {
			if (shade >= 0.999F || shade <= 0.0F) {
				return argb;
			}
			int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) / shade));
			int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) / shade));
			int b = Math.min(255, Math.round((argb & 0xFF) / shade));
			return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
		}

		/** SkyCraft+ : Skyrim blocks. A face in one of Skyrim's own textures: sampled smooth, as Skyrim draws it (vertex flag 128). */
		void wallSmooth(float[] xyz, float[] uv, int light, Direction normal) {
			this.ensure(6 * Proto.REN_VERTEX_BYTES);
			int flags = flags(false, normal) | 128;
			for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
				this.vertex(xyz[k * 3], xyz[k * 3 + 1], xyz[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], 0xFFFFFFFF, light, flags);
			}
		}

		/** A dug hole's wall (DigWalls): an untinted opaque quad. */
		void wall(float[] xyz, float[] uv, int light, Direction normal) {
			this.ensure(6 * Proto.REN_VERTEX_BYTES);
			int flags = flags(false, normal);
			for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
				this.vertex(xyz[k * 3], xyz[k * 3 + 1], xyz[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], 0xFFFFFFFF, light, flags);
			}
		}

		// ---- block quads (BlockQuadOutput) ----
		@Override
		public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
			if (this.rockDug != null && this.intoRock((int) x, (int) y, (int) z, quad)) {
				return;
			}
			this.ensure(6 * Proto.REN_VERTEX_BYTES);
			TextureAtlasSprite sprite = quad.materialInfo().sprite();
			boolean translucent = quad.materialInfo().layer().translucent();
			int emission = quad.materialInfo().lightEmission();
			// Plants and the like are shaded as if facing up whatever way they face: no normal for them.
			Direction override = quad.materialInfo().shadeDirectionOverride();
			Direction normal = override == Direction.UP && quad.direction() != Direction.UP ? null : override != null ? override : quad.direction();
			float shade = this.cardinal.byFace(override != null ? override : quad.direction());
			int flags = flags(translucent, normal);
			float[] nudge = nudge(quad);
			for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
				var p = quad.position(k);
				long uv = quad.packedUV(k);
				float u = atlas.u(sprite, UVPair.unpackU(uv));
				float v = atlas.v(sprite, UVPair.unpackV(uv));
				this.vertex(p.x() + x + nudge[0], p.y() + y + nudge[1], p.z() + z + nudge[2], u, v, unshade(instance.getColor(k), shade),
					instance.getLightCoordsWithEmission(k, emission), flags);
			}
		}

		private static final float[] NO_NUDGE = new float[3];
		private static final float NUDGE = 1.0F / 256.0F;

		/**
		 * SkyCraft+ : plants on Skyrim's ground. Skyrim draws blocks with both sides of a face, so a
		 * plant's two faces back to back in one place (a flower's cross, a sunflower's yellow front
		 * and green back) fought over which showed, flickering as the camera moved. A face inside its
		 * block is moved a hair toward the side it faces; faces on the block's edges stay put (they'd
		 * open cracks between blocks).
		 */
		private static float[] nudge(BakedQuad quad) {
			var p0 = quad.position(0);
			var p1 = quad.position(1);
			var p2 = quad.position(2);
			float ax = p1.x() - p0.x(), ay = p1.y() - p0.y(), az = p1.z() - p0.z();
			float bx = p2.x() - p0.x(), by = p2.y() - p0.y(), bz = p2.z() - p0.z();
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length < 1.0E-6F) {
				return NO_NUDGE;
			}
			nx /= length;
			ny /= length;
			nz /= length;
			var facing = quad.direction().getUnitVec3i();
			if (nx * facing.getX() + ny * facing.getY() + nz * facing.getZ() < 0.0F) { // (the side it's seen from)
				nx = -nx;
				ny = -ny;
				nz = -nz;
			}
			float along = Math.abs(nx) > 0.999F ? p0.x() : Math.abs(ny) > 0.999F ? p0.y() : Math.abs(nz) > 0.999F ? p0.z() : 0.5F;
			if (along < 0.001F || along > 0.999F) {
				return NO_NUDGE;
			}
			return new float[] { nx * NUDGE, ny * NUDGE, nz * NUDGE };
		}

		// ---- fluids (FluidRenderer.Output + VertexConsumer) ----
		// Skyrim ground's height in the fluid's cell (0..1) and the cell's section-relative y: a
		// Minecraft fluid level counts from the cell's floor, so on Skyrim ground partway up the cell
		// the fluid is squeezed into the space above it (thin edges stay visible on the ground).
		/**
		 * SkyCraft+ : the underground. Per column of the section being meshed, the height (section
		 * relative) below which Minecraft's sky light is left out: three blocks under Skyrim's land.
		 * Breaking a block down a mine opened it onto cells that are still air until the server
		 * reveals them, and sky light poured in for a moment (a flash); and no sky reaches there.
		 */
		final float[] skyCut = new float[256];

		/**
		 * SkyCraft+ : performance. Deep under Skyrim's land a cell not dug yet is solid rock (it's only
		 * air here until the underground is revealed into it), so a block's face against it is never
		 * seen: left out. Without this every revealed cave was a closed shell drawn from outside too.
		 * Per column of the section and the ring round it (18 x 18), the section-relative height
		 * under which a cell counts as deep; and the dug cells (null: not in the underground's world).
		 */
		final float[] rockBelow = new float[324];
		dev.skycraft.world.SkyDig.@org.jspecify.annotations.Nullable DugLookup rockDug;
		BlockPos rockOrigin = BlockPos.ZERO;

		private boolean intoRock(int x, int y, int z, BakedQuad quad) {
			Direction d = quad.direction();
			// Only a face lying on the block's side (what a whole block next to it would hide).
			float side = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
			for (int k = 0; k < 4; k++) {
				var p = quad.position(k);
				float along = d.getAxis() == Direction.Axis.X ? p.x() : d.getAxis() == Direction.Axis.Y ? p.y() : p.z();
				if (Math.abs(along - side) > 1.0E-4F) {
					return false;
				}
			}
			int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
			if (ny + 0.5F > this.rockBelow[(nx + 1) + 18 * (nz + 1)]) {
				return false;
			}
			return !this.rockDug.isDug(this.rockOrigin.getX() + nx, this.rockOrigin.getY() + ny, this.rockOrigin.getZ() + nz);
		}
		float fluidGround;
		int fluidBaseY;

		@Override
		public VertexConsumer getBuilder(ChunkSectionLayer layer) {
			this.fluidTranslucent = layer.translucent();
			return this;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			int o = this.fqCount * 8;
			if (this.fluidGround > 0.0F) {
				float t = Math.max(0.0F, Math.min(1.0F, y - this.fluidBaseY));
				y = this.fluidBaseY + this.fluidGround + t * (1.0F - this.fluidGround);
			}
			this.fq[o] = x;
			this.fq[o + 1] = y;
			this.fq[o + 2] = z;
			this.fq[o + 3] = atlas.blockU(u);
			this.fq[o + 4] = atlas.blockV(v);
			this.fq[o + 5] = Float.intBitsToFloat(color);
			this.fq[o + 6] = Float.intBitsToFloat(light);
			if (++this.fqCount == 4) {
				this.fqCount = 0;
				this.ensure(6 * Proto.REN_VERTEX_BYTES);
				// Fluid faces are shaded like block faces (up, down, or the side's brightness).
				Direction normal = nx == 0 && ny == 0 && nz == 0 ? null : Direction.getApproximateNearest(nx, ny, nz);
				float shade = normal == null ? 1.0F
					: normal.getAxis() == Direction.Axis.Y ? this.cardinal.byFace(normal) : this.cardinal.up() * this.cardinal.byFace(normal);
				int flags = flags(this.fluidTranslucent, normal);
				for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
					int b = k * 8;
					this.vertex(this.fq[b], this.fq[b + 1], this.fq[b + 2], this.fq[b + 3], this.fq[b + 4],
						unshade(Float.floatToRawIntBits(this.fq[b + 5]), shade), Float.floatToRawIntBits(this.fq[b + 6]), flags);
				}
			}
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException();
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}
}
