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
		SENT.clear();
		LIT.clear();
		SOLID.clear();
		SHAPED.clear();
		DUG.clear();
		LIGHT_SENT.clear();
		SOLID_SENT.clear();
		SHAPE_SENT.clear();
		DUG_SENT.clear();
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
		long deadline = System.nanoTime() + 3_000_000L;
		int meshed = 0;
		long[] order = nearestFirst();
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
	 * SkyCraft+ : performance. The dirty sections, most wanted first: what the player just changed,
	 * then by distance from the camera, those behind it counting as four times as far (a cave or a
	 * structure opens in front of the player first, rather than in the order it was revealed).
	 */
	private static final LongOpenHashSet URGENT = new LongOpenHashSet();
	private static final int SORT_AT_MOST = 4096;

	private static long[] nearestFirst() {
		long[] keys;
		synchronized (DIRTY) {
			int n = Math.min(DIRTY.size(), SORT_AT_MOST);
			keys = new long[n];
			var it = DIRTY.iterator();
			for (int i = 0; i < n; i++) {
				keys[i] = it.nextLong();
			}
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (keys.length <= 1 || minecraft.player == null) {
			return keys;
		}
		var eye = minecraft.player.getEyePosition();
		var look = minecraft.player.getViewVector(1.0F);
		long[] packed = new long[keys.length];
		for (int i = 0; i < keys.length; i++) {
			long key = keys[i];
			double dx = SectionPos.x(key) * 16 + 8 - eye.x, dy = SectionPos.y(key) * 16 + 8 - eye.y, dz = SectionPos.z(key) * 16 + 8 - eye.z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (dx * look.x + dy * look.y + dz * look.z < -16.0) {
				d2 *= 4.0;
			}
			boolean urgent;
			synchronized (DIRTY) {
				urgent = URGENT.contains(key);
			}
			long score = urgent ? 0L : Math.min((long) d2 + 1L, 0x7FFFFFFFL);
			packed[i] = (score << 32) | i;
		}
		java.util.Arrays.sort(packed);
		long[] order = new long[keys.length];
		for (int i = 0; i < packed.length; i++) {
			order[i] = keys[(int) packed[i]];
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
						// Blocks Skyrim's NPCs can't walk through (anything with a collision shape).
						VoxelShape collision = state.getCollisionShape(level, pos);
						if (!collision.isEmpty()) {
							int bit = x + 16 * z + 256 * y;
							SOLID_BITS[bit >> 6] |= 1L << (bit & 63);
							solidCount++;
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
						// SkyCraft+ : the End. End portals and gateways are drawn by a shader of Minecraft's
						// own that never reaches Skyrim: a glowing obsidian surface stands in for them.
						if (state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)) {
							portalFaces(state.is(Blocks.END_GATEWAY), x, y, z);
						}
					}
				}
			}
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

	private static void exportEntities(Minecraft minecraft, ClientLevel level, float partialTick) {
		ENTITIES.clear();
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
			}
		}
		addCracks(level);
		SkyLink.writeWorldEntities(ENTITIES, selection(minecraft, level));
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
			for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
				var p = quad.position(k);
				long uv = quad.packedUV(k);
				float u = atlas.u(sprite, UVPair.unpackU(uv));
				float v = atlas.v(sprite, UVPair.unpackV(uv));
				this.vertex(p.x() + x, p.y() + y, p.z() + z, u, v, unshade(instance.getColor(k), shade), instance.getLightCoordsWithEmission(k, emission), flags);
			}
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
