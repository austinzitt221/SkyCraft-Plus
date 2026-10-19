// SkyCraft+ : little Minecraft patches in Skyrim's ground
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.net.SkyNet;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SegmentableBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Little bits of Minecraft in Skyrim's open country, so its plants and every kind of wood can be had
 * in survival. On flat, plain land: in woods, now and then a Minecraft tree on a Minecraft grass
 * block cut into Skyrim's ground (its kind after Skyrim's trees round it: spruce and pine by spruce
 * and in snow, birch, cherry and poplar by aspens and birches, oak, dark and pale oak, jungle,
 * acacia, mangrove and azalea by the rest; out in the open now and then an oak, birch, acacia or
 * cherry), and leaf litter; on grass, grass (seeds), ferns, bushes and flowers. The plants stand
 * straight on Skyrim's ground (VegetationBlockMixin). Each eight by eight blocks is decided once,
 * as soon as the host's Skyrim has sent its ground (some fifty blocks out: iGroundRegions), and kept with the world
 * (skycraft-patches-8.txt); not in towns and settlements, nor with Skyrim destruction off. Setting:
 * skyrimPatches in config/skycraft.properties.
 */
public final class SkyPatches {
	private SkyPatches() {
	}

	private static boolean enabled = true;
	private static final Set<Long> DONE = new HashSet<>();
	private static final java.util.Map<Long, Integer> LANDLESS = new java.util.HashMap<>(); // areas with no land in reach: the host's height then
	private static @Nullable Path file;
	private static int logged;

	private static final int CELL = SkyCollision.REGION_SIZE; // blocks per side of an area (as Skyrim sends its ground)
	private static final int RADIUS = 10; // areas round the host (as far as Skyrim sends its ground: iGroundRegions, at most ten)
	private static final int TRIES = 24; // columns looked at per area
	private static final int PER_TICK = 8; // areas decided a tick at most
	private static final long BUDGET = 3_000_000L; // nanoseconds a tick

	private record Kind(Block sapling, TreeGrower grower, boolean twoByTwo, int weight) {
	}

	private static final Kind[] BY_SPRUCE = { new Kind(Blocks.SPRUCE_SAPLING, TreeGrower.SPRUCE, false, 1) };
	private static final Kind[] BY_BIRCH = { new Kind(Blocks.BIRCH_SAPLING, TreeGrower.BIRCH, false, 5), new Kind(Blocks.CHERRY_SAPLING, TreeGrower.CHERRY, false, 2),
		new Kind(Blocks.POPLAR_SAPLING, TreeGrower.POPLAR, false, 2), new Kind(Blocks.ACACIA_SAPLING, TreeGrower.ACACIA, false, 1) };
	private static final Kind[] BY_OAK = { new Kind(Blocks.OAK_SAPLING, TreeGrower.OAK, false, 6), new Kind(Blocks.DARK_OAK_SAPLING, TreeGrower.DARK_OAK, true, 3),
		new Kind(Blocks.JUNGLE_SAPLING, TreeGrower.JUNGLE, false, 2), new Kind(Blocks.ACACIA_SAPLING, TreeGrower.ACACIA, false, 2),
		new Kind(Blocks.MANGROVE_PROPAGULE, TreeGrower.MANGROVE, false, 1), new Kind(Blocks.PALE_OAK_SAPLING, TreeGrower.PALE_OAK, true, 1),
		new Kind(Blocks.AZALEA, TreeGrower.AZALEA, false, 1), new Kind(Blocks.CHERRY_SAPLING, TreeGrower.CHERRY, false, 1),
		new Kind(Blocks.POPLAR_SAPLING, TreeGrower.POPLAR, false, 1) };
	private static final Kind[] IN_THE_OPEN = { new Kind(Blocks.OAK_SAPLING, TreeGrower.OAK, false, 5), new Kind(Blocks.BIRCH_SAPLING, TreeGrower.BIRCH, false, 3),
		new Kind(Blocks.ACACIA_SAPLING, TreeGrower.ACACIA, false, 1), new Kind(Blocks.CHERRY_SAPLING, TreeGrower.CHERRY, false, 1) };

	private static final Block[] FLOWERS = { Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.AZURE_BLUET, Blocks.ALLIUM,
		Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.BLUE_ORCHID, Blocks.LILY_OF_THE_VALLEY, Blocks.WILDFLOWERS,
		Blocks.PINK_PETALS, Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY };

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(SkyPatches::load);
		ServerTickEvents.END_SERVER_TICK.register(SkyPatches::tick);
	}

	private static void load(MinecraftServer server) {
		Path config = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		Properties p = new Properties();
		if (Files.exists(config)) {
			try (Reader in = Files.newBufferedReader(config)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
			}
		}
		enabled = Boolean.parseBoolean(p.getProperty("skyrimPatches", "true").trim());
		DONE.clear();
		logged = 0;
		file = server.getWorldPath(LevelResource.ROOT).resolve("skycraft-patches-8.txt");
		if (Files.exists(file)) {
			try {
				for (String line : Files.readAllLines(file)) {
					if (!line.isBlank()) {
						DONE.add(Long.parseLong(line.trim()));
					}
				}
			} catch (IOException | NumberFormatException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", file, e.toString());
			}
		}
		SkyCraft.LOG.info("SkyCraft: Minecraft patches in Skyrim's ground {} ({} areas done)", enabled ? "on" : "off", DONE.size());
	}

	private static long key(int world, int ax, int az) {
		return ChunkPos.pack(ax, az) ^ (world * 0x9E3779B97F4A7C15L);
	}

	private static void done(long key) {
		DONE.add(key);
		if (file == null) {
			return;
		}
		try {
			Files.writeString(file, key + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}: {}", file, e.toString());
		}
	}

	/**
	 * Each tick: the nearest areas round the host that Skyrim has sent and aren't decided yet, as soon
	 * as they're sent, far out, so they're there before the host comes (one chunk a second, close
	 * by, popped in round them).
	 */
	private static void tick(MinecraftServer server) {
		if (!enabled || !SkyWorld.valid() || !SkyCollision.active() || !SkyDig.destruction || SkyWorld.interior() || SkyWorld.settlement() || SkyWorld.town()) {
			return;
		}
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (SkyNet.isHost(player)) {
				host = player;
				break;
			}
		}
		if (host == null || !host.isAlive() || host.isSpectator() || SkyDig.minecraftsOwnWorld(host.level())) {
			return;
		}
		ServerLevel level = host.level();
		int world = SkyDig.worldFor(level);
		int hx = Math.floorDiv(host.getBlockX(), CELL), hz = Math.floorDiv(host.getBlockZ(), CELL), hy = host.getBlockY();
		// SkyCraft+ : flying. Up in the air, the land under the host is where to look (Skyrim sends
		// the ground round it then), not round the host's own height.
		if (!host.onGround()) {
			double ground = land(host.getBlockX(), host.getBlockZ(), hy - 96, hy + 1, new int[1]);
			if (!Double.isNaN(ground) && hy - ground > CELL) {
				hy = Mth.floor(ground);
			}
		}
		long began = System.nanoTime();
		int decided = 0;
		for (int r = 0; r <= RADIUS; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int ax = hx + dx, az = hz + dz;
					long key = key(world, ax, az);
					if (DONE.contains(key) || !sent(ax, hy, az) || !level.hasChunk((ax * CELL) >> 4, (az * CELL) >> 4)) {
						continue;
					}
					Integer tried = LANDLESS.get(key);
					if (tried != null && Math.abs(tried - hy) < CELL) {
						continue;
					}
					// (No land in reach of the host's height, flying over it say: tried again when there is,
					// not left bare for good.)
					if (decide(level, world, ax, az, hy)) {
						done(key);
						LANDLESS.remove(key);
					} else {
						if (LANDLESS.size() > 20000) {
							LANDLESS.clear();
						}
						LANDLESS.put(key, hy);
					}
					if (++decided >= PER_TICK || System.nanoTime() - began > BUDGET) {
						return;
					}
				}
			}
		}
	}

	/** Skyrim has sent this area's ground round the host's height (its region, and the ones over and under it). */
	private static boolean sent(int ax, int y, int az) {
		int x = ax * CELL, z = az * CELL;
		return SkyCollision.isKnown(x, y, z) && SkyCollision.isKnown(x, y - CELL, z) && SkyCollision.isKnown(x, y + CELL, z);
	}

	/** Decides an area's patches; false if none of its land was in reach (nothing decided). */
	private static boolean decide(ServerLevel level, int world, int ax, int az, int hostY) {
		RandomSource random = RandomSource.create(level.getSeed() ^ ChunkPos.pack(ax, az) * 0x5DEECE66DL ^ world ^ 0x8L);
		int trees = 0, grass = 0, flowers = 0, litter = 0;
		int wantGrass = 2 + random.nextInt(6), wantFlowers = random.nextBoolean() ? 1 + random.nextInt(3) : 0;
		int treeRoll = random.nextInt(100);
		boolean anyLand = false;
		int[] material = new int[1];
		for (int i = 0; i < TRIES; i++) {
			int x = ax * CELL + random.nextInt(CELL), z = az * CELL + random.nextInt(CELL);
			double h = land(x, z, hostY - 24, hostY + 16, material);
			anyLand |= !Double.isNaN(h);
			double rough = Double.isNaN(h) ? Double.NaN : roughness(x, z, h);
			if (Double.isNaN(rough) || rough > 0.5 || !SkyCollision.isKnown(x, Mth.floor(h), z)) {
				continue;
			}
			int ground = material[0];
			int logs = logsNear(x, z, h);
			boolean forest = logs != Proto.DIG_NONE;
			if (trees == 0 && treeRoll < (forest ? 25 : 3) && (ground == Proto.DIG_GRASS || ground == Proto.DIG_SNOW) && clearForTree(x, z, h)) {
				Kind kind = ground == Proto.DIG_SNOW || logs == Proto.DIG_SPRUCE_LOG ? pick(BY_SPRUCE, random)
					: !forest ? pick(IN_THE_OPEN, random) : logs == Proto.DIG_BIRCH_LOG ? pick(BY_BIRCH, random) : pick(BY_OAK, random);
				if (tree(level, world, x, z, h, kind, random)) {
					trees++;
					continue;
				}
			}
			if (forest && litter < 3 && (ground == Proto.DIG_GRASS || ground == Proto.DIG_DIRT) && random.nextBoolean()) {
				BlockState leaves = Blocks.LEAF_LITTER.defaultBlockState().trySetValue(SegmentableBlock.AMOUNT, 1 + random.nextInt(4))
					.trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random));
				if (rough <= 0.2 && plant(level, x, z, h, leaves, true)) {
					litter++;
				}
				continue;
			}
			if (ground != Proto.DIG_GRASS) {
				continue;
			}
			if (flowers < wantFlowers && random.nextInt(3) == 0) {
				if (plant(level, x, z, h, flower(random), false)) {
					flowers++;
				}
			} else if (grass < wantGrass) {
				// Plain grass, short and tall, a third as thick as the rest (it was everywhere).
				BlockState plant = grassy(random, forest, logs);
				if ((plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.TALL_GRASS)) && random.nextInt(3) != 0) {
					grass++;
				} else if (plant(level, x, z, h, plant, false)) {
					grass++;
				}
			}
		}
		if (trees + grass + flowers + litter > 0 && logged++ < 40) {
			SkyCraft.LOG.info("SkyCraft: Minecraft patches at {} {}: {} tree(s), {} grass, {} flower(s), {} leaf litter", ax * CELL, az * CELL, trees, grass, flowers, litter);
		}
		return anyLand;
	}

	private static Kind pick(Kind[] kinds, RandomSource random) {
		int total = 0;
		for (Kind k : kinds) {
			total += k.weight();
		}
		int roll = random.nextInt(total);
		for (Kind k : kinds) {
			if ((roll -= k.weight()) < 0) {
				return k;
			}
		}
		return kinds[0];
	}

	private static BlockState flower(RandomSource random) {
		BlockState state = FLOWERS[random.nextInt(FLOWERS.length)].defaultBlockState();
		return state.trySetValue(SegmentableBlock.AMOUNT, 1 + random.nextInt(4))
			.trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random));
	}

	private static BlockState grassy(RandomSource random, boolean forest, int logs) {
		int roll = random.nextInt(100);
		if (forest && logs == Proto.DIG_SPRUCE_LOG && roll < 12) {
			return Blocks.SWEET_BERRY_BUSH.defaultBlockState().trySetValue(BlockStateProperties.AGE_3, 2 + random.nextInt(2));
		}
		if (forest && roll < 30) {
			return (roll < 22 ? Blocks.FERN : roll < 26 ? Blocks.LARGE_FERN : Blocks.FIREFLY_BUSH).defaultBlockState();
		}
		return (roll < 75 ? Blocks.SHORT_GRASS : roll < 90 ? Blocks.TALL_GRASS : Blocks.BUSH).defaultBlockState();
	}

	/**
	 * The height of Skyrim's land at this column's middle (and what it's made of), if it's plain land
	 * there: nothing of Skyrim's (a rock, a wall, a tree) over it within three blocks. NaN otherwise.
	 */
	private static double land(int x, int z, double lo, double hi, int[] material) {
		List<SkyTri> tris = new ArrayList<>();
		double px = x + 0.5, pz = z + 0.5;
		SkyCollision.trianglesNear(new AABB(px - 0.01, lo, pz - 0.01, px + 0.01, hi, pz + 0.01), tris);
		double best = Double.NaN;
		for (SkyTri t : tris) {
			double h = t.terrain ? t.heightAt(px, pz) : Double.NaN;
			if (!Double.isNaN(h) && (Double.isNaN(best) || h > best)) {
				best = h;
				material[0] = t.material;
			}
		}
		if (Double.isNaN(best)) {
			return best;
		}
		tris.clear();
		SkyCollision.trianglesNear(new AABB(x, best + 0.05, z, x + 1.0, best + 3.0, z + 1.0), tris);
		for (SkyTri t : tris) {
			if (!t.terrain) {
				return Double.NaN;
			}
		}
		return best;
	}

	/** How far (blocks) the land round the column is above or below its height, the most; NaN if it isn't plain land round it. */
	private static double roughness(int x, int z, double h) {
		int[] unused = new int[1];
		int[][] around = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		double most = 0.0;
		for (int[] d : around) {
			double n = land(x + d[0], z + d[1], h - 2.0, h + 2.0, unused);
			if (Double.isNaN(n)) {
				return Double.NaN;
			}
			most = Math.max(most, Math.abs(n - h));
		}
		return most;
	}

	/** The kind of Skyrim's tree trunks within ten blocks (Proto.DIG_*_LOG, the most of), or DIG_NONE. */
	private static int logsNear(int x, int z, double h) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(x - 10.0, h - 2.0, z - 10.0, x + 11.0, h + 8.0, z + 11.0), tris);
		int oak = 0, spruce = 0, birch = 0;
		for (SkyTri t : tris) {
			switch (t.material) {
				case Proto.DIG_OAK_LOG -> oak++;
				case Proto.DIG_SPRUCE_LOG -> spruce++;
				case Proto.DIG_BIRCH_LOG -> birch++;
				default -> {
				}
			}
		}
		if (oak + spruce + birch == 0) {
			return Proto.DIG_NONE;
		}
		return spruce >= oak && spruce >= birch ? Proto.DIG_SPRUCE_LOG : birch >= oak ? Proto.DIG_BIRCH_LOG : Proto.DIG_OAK_LOG;
	}

	/** Room for a tree: nothing of Skyrim's (a trunk, a rock) within two blocks of it, ten up. */
	private static boolean clearForTree(int x, int z, double h) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(x - 2.0, h + 0.3, z - 2.0, x + 3.0, h + 10.0, z + 3.0), tris);
		for (SkyTri t : tris) {
			if (!t.terrain) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Where a tree's grass block goes in this column: the cell its top is within half a block of the
	 * land in. Null if a Minecraft block or a dug hole is there already, or water.
	 */
	private static @Nullable BlockPos base(ServerLevel level, int world, int x, int z, double h) {
		int fy = Mth.floor(h);
		BlockPos base = new BlockPos(x, h - fy < 0.5 ? fy - 1 : fy, z);
		BlockPos above = base.above();
		if (!level.isLoaded(base) || !level.getBlockState(base).isAir() || !level.getBlockState(above).isAir() || !level.getBlockState(above.above()).isAir()
			|| SkyDig.isDug(level, world, base) || SkyDig.isDug(level, world, above) || SkyWater.depthIn(above) > 0.0F) {
			return null;
		}
		return base;
	}

	/** Skyrim's ground out of the base cell (and the cell over it, if that's still under the land): a Minecraft block in it. */
	private static void dig(ServerLevel level, int world, BlockPos base, double h, BlockState ground) {
		SkyDig.markDug(level, world, base);
		if (base.getY() + 1 <= h) {
			SkyDig.markDug(level, world, base.above());
		}
		level.setBlock(base, ground, Block.UPDATE_ALL);
	}

	/**
	 * A plant straight on Skyrim's ground, in the cell the land's top is low in (sunk 0.4 of a block
	 * at most, which the land hides) or just under (0.2 over it at most); leaf litter, flat, only
	 * just in or over the land's top.
	 */
	private static boolean plant(ServerLevel level, int x, int z, double h, BlockState plant, boolean flat) {
		int fy = Mth.floor(h);
		double frac = h - fy;
		BlockPos at;
		if (frac <= (flat ? 0.04 : 0.4)) {
			at = new BlockPos(x, fy, z);
		} else if (frac >= (flat ? 0.88 : 0.8)) {
			at = new BlockPos(x, fy + 1, z);
		} else {
			return false;
		}
		if (!level.isLoaded(at) || !level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir() || SkyWater.depthIn(at) > 0.0F
			|| !plant.canSurvive(level, at)) {
			return false;
		}
		if (plant.getBlock() instanceof DoublePlantBlock) {
			DoublePlantBlock.placeAt(level, plant, at, Block.UPDATE_ALL);
		} else {
			level.setBlock(at, plant, Block.UPDATE_ALL);
		}
		return true;
	}

	/**
	 * Skyrim's grass, dirt or snow holds up a plant in this cell, with no Minecraft block under it:
	 * the land's top from a quarter of a block under the cell to 0.45 up in it (as patches are
	 * planted).
	 */
	public static boolean onSkyrimGround(LevelReader level, BlockPos pos) {
		if (!enabled || !(level instanceof Level world) || !SkyCollision.active() || SkyDig.minecraftsOwnWorld(world) || !level.getBlockState(pos.below()).isAir()) {
			return false;
		}
		List<SkyTri> tris = new ArrayList<>();
		double px = pos.getX() + 0.5, pz = pos.getZ() + 0.5, lo = pos.getY() - 0.25, hi = pos.getY() + 0.45;
		SkyCollision.trianglesNear(new AABB(px - 0.01, lo, pz - 0.01, px + 0.01, hi, pz + 0.01), tris);
		for (SkyTri t : tris) {
			double h = t.terrain ? t.heightAt(px, pz) : Double.NaN;
			if (!Double.isNaN(h) && h >= lo && h <= hi && (t.material == Proto.DIG_GRASS || t.material == Proto.DIG_DIRT || t.material == Proto.DIG_SNOW)) {
				return true;
			}
		}
		return false;
	}

	/** A Minecraft tree grown from its sapling on a patch (two by two for dark and pale oak); a sapling left if it couldn't grow. */
	private static boolean tree(ServerLevel level, int world, int x, int z, double h, Kind kind, RandomSource random) {
		int[][] cells = kind.twoByTwo() ? new int[][] { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } } : new int[][] { { 0, 0 } };
		List<BlockPos> bases = new ArrayList<>();
		int[] unused = new int[1];
		for (int[] c : cells) {
			double ch = c[0] == 0 && c[1] == 0 ? h : land(x + c[0], z + c[1], h - 1.0, h + 1.0, unused);
			if (Double.isNaN(ch) || Math.abs(ch - h) > 0.4) {
				return false;
			}
			BlockPos base = base(level, world, x + c[0], z + c[1], h);
			if (base == null) {
				return false;
			}
			bases.add(base);
		}
		BlockState sapling = kind.sapling().defaultBlockState();
		for (BlockPos base : bases) {
			dig(level, world, base, h, Blocks.GRASS_BLOCK.defaultBlockState());
			level.setBlock(base.above(), sapling, Block.UPDATE_ALL);
		}
		boolean grew = kind.grower().growTree(level, level.getChunkSource().getGenerator(), bases.get(0).above(), sapling, random);
		if (logged < 40) {
			SkyCraft.LOG.info("SkyCraft: a Minecraft {} {} at {}", kind.sapling().getName().getString(), grew ? "tree grew" : "stayed a sapling", bases.get(0).above().toShortString());
		}
		return true;
	}
}
