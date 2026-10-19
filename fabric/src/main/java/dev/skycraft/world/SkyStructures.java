// SkyCraft+ : Minecraft's structures in Skyrim's world
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's surface structures in Skyrim's open world, at fixed spots (SkyrimMap: read from the
 * player's Skyrim.esm, the same for everyone): villages of every kind, pillager outposts, the
 * woodland mansion, the temples, the witch hut, the igloo, ruined portals, trail ruins, buried
 * treasure, wrecks, ocean ruins and the ocean monument. Each is built the first time the host comes
 * near its spot, by Minecraft's own generator, on Skyrim's ground: the mirror world has no terrain,
 * so while one is laid out and placed, the ground it sees is Skyrim's land there. Villages come with
 * their villagers and golems, the mansion with its illagers, the hut with its witch; outposts, huts
 * and monuments keep spawning their own (pillagers, witches, guardians) while the host is near.
 * Underground ones (strongholds, mineshafts, trial chambers, ancient cities) come up with the
 * underground (SkyUnderground). Setting: skyrimStructures in config/skycraft.properties.
 */
public final class SkyStructures {
	private static final double BUILD_DISTANCE = 160.0;  // blocks: built when the host comes this near
	private static final double SPAWN_DISTANCE = 64.0;   // their own mobs keep coming while the host is this near
	private static final int MOBS_PER_STRUCTURE = 4;

	private static boolean enabled = true;
	private static volatile @Nullable SkyrimMap map;
	private static volatile boolean reading;
	/** SkyCraft+ : strongholds. No map is coming this run (structures off, Skyrim's Data folder unknown, a failed read). */
	private static volatile boolean noMap;
	/** Built in this world: site name -> its bounding box (and structure, for its own mobs). */
	private static final Map<String, Built> BUILT = new HashMap<>();
	private static @Nullable Path builtFile;

	/** Set while a structure is laid out and placed: the ground Level.getHeight reports (LevelHeightMixin). */
	public static volatile @Nullable SkyrimMap placing;
	/** While an ocean structure is placed: the sea level it should see (Skyrim's water there); else MIN_VALUE. */
	public static volatile int placingSea = Integer.MIN_VALUE;
	/** The level being placed in (the mixins answer for it alone, on the server thread). */
	public static volatile @Nullable ServerLevel placingLevel;

	/** A call from the level being placed in, on its server's thread: Skyrim's ground counts. */
	public static boolean placingIn(Object level) {
		ServerLevel placed = placingLevel;
		return placing != null && placed != null && level == placed && placed.getServer().isSameThread();
	}

	private record Built(String structure, BoundingBox box) {
	}

	/**
	 * SkyCraft+ : the woodland mansion off the mountain. A site that has moved since its structure was
	 * built (a better spot picked by a newer SkyCraft) is built again where it is now; the old one stays.
	 */
	private static void forgetIfMoved(SkyrimMap.Site site) {
		Built b = BUILT.get(site.name());
		if (b == null || b.box.getXSpan() <= 1) {
			return;  // (not built, or a placeholder: it couldn't be)
		}
		if (!b.box.inflatedBy(48).isInside(BlockPos.containing(site.x(), b.box.getCenter().getY(), site.z()))) {
			SkyCraft.LOG.info("SkyCraft: {} has a new spot ({} {}, was by {}): built again there", site.name(), (int) site.x(), (int) site.z(), b.box.getCenter());
			BUILT.remove(site.name());
		}
	}

	/** A structure laid out and being placed a few chunks a tick (a village at once stalled the game a second). */
	private record Pending(SkyrimMap.Site site, StructureStart start, FlatLevelSource ground, BoundingBox box, List<ChunkPos> chunks, long started) {
	}

	private static @Nullable Pending pending;
	private static int pendingNext;
	private static final long PLACE_NANOS = 15_000_000L;  // a tick's share of placing

	private SkyStructures() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(SkyStructures::start);
		ServerTickEvents.END_SERVER_TICK.register(SkyStructures::tick);
	}

	private static void start(MinecraftServer server) {
		Properties p = new Properties();
		Path config = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		if (Files.exists(config)) {
			try (Reader in = Files.newBufferedReader(config)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
			}
		}
		enabled = Boolean.parseBoolean(p.getProperty("skyrimStructures", "true").trim());
		boolean offMountains = Boolean.parseBoolean(p.getProperty("structuresOffMountains", "true").trim());
		if (offMountains != SkyrimMap.offMountains) {
			SkyrimMap.offMountains = offMountains;
			map = null;  // chosen again with the other rule
		}
		BUILT.clear();
		INSIDE.clear();  // (another world's)
		pending = null;
		pendingNext = 0;
		padColumns = null;
		WRITTEN.clear();
		BOTTOMS.clear();
		builtFile = server.getWorldPath(LevelResource.ROOT).resolve("skycraft-structures.txt");
		loadBuilt();
		writeAreasForSkyrim();  // this world's (another's were there)
		noMap = !enabled && map == null && !reading;
		if (!enabled || map != null || reading) {
			return;
		}
		Path esm = skyrimEsm();
		if (esm == null) {
			noMap = true;
			SkyCraft.LOG.info("SkyCraft: Minecraft's structures in Skyrim: Skyrim's Data folder isn't known yet (start Skyrim with SkyCraft once)");
			return;
		}
		reading = true;
		Thread thread = new Thread(() -> {
			try {
				long t0 = System.nanoTime();
				// Read again if the mountain rule changed meanwhile (a world switched to with the other setting).
				boolean rule;
				SkyrimMap read;
				do {
					rule = SkyrimMap.offMountains;
					read = SkyrimMap.read(esm);
				} while (rule != SkyrimMap.offMountains);
				map = read;
				SkyCraft.LOG.info("SkyCraft: Minecraft's structures in Skyrim: {} spots from {} in {} s", read.sites().size(), esm,
					String.format("%.1f", (System.nanoTime() - t0) / 1e9));
				for (SkyrimMap.Site s : read.sites()) {
					SkyCraft.LOG.info("SkyCraft:   {} ({}) at {} {} {}", s.name(), s.structure(), Math.round(s.x()), Math.round(s.groundY()), Math.round(s.z()));
				}
				writeForSkyrim(read);
			} catch (IOException | RuntimeException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read Skyrim's land from {}", esm, e);
				noMap = true;
			} finally {
				reading = false;
			}
		}, "SkyCraft Skyrim map");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	/**
	 * SkyCraft+ : strongholds. Skyrim's map once it's read (it starts when the server has, about 12 s):
	 * null when none is coming, or it took longer than {@code timeoutMs}.
	 */
	public static @Nullable SkyrimMap awaitMap(long timeoutMs) {
		long until = System.currentTimeMillis() + timeoutMs;
		while (map == null && !noMap && System.currentTimeMillis() < until) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return map;
	}

	/**
	 * The spots for Skyrim's map and compass (the SKSE plugin marks them): %LOCALAPPDATA%\SkyCraft\
	 * structures.txt, a line each: name|structure|x|y|z (Minecraft blocks).
	 */
	private static void writeForSkyrim(SkyrimMap read) {
		String local = System.getenv("LOCALAPPDATA");
		if (local == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		for (SkyrimMap.Site s : read.sites()) {
			// At the water's surface over one under water (fast travel lands there, not on the sea floor).
			double water = read.waterAt(s.x(), s.z());
			double y = Double.isNaN(water) ? s.groundY() : Math.max(s.groundY(), water);
			lines.add(s.name() + "|" + s.structure() + "|" + Math.round(s.x()) + "|" + Math.round(y) + "|" + Math.round(s.z()));
		}
		Path out = Path.of(local, "SkyCraft", "structures.txt");
		try {
			Path temp = out.resolveSibling("structures.txt.tmp");
			Files.write(temp, lines);
			Files.move(temp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't write {} for Skyrim's map: {}", out, e.toString());
		}
	}

	/** Skyrim.esm, from the Data folder the SKSE plugin noted (%LOCALAPPDATA%\SkyCraft\skyrim-data.txt). */
	private static @Nullable Path skyrimEsm() {
		String local = System.getenv("LOCALAPPDATA");
		if (local == null) {
			return null;
		}
		Path note = Path.of(local, "SkyCraft", "skyrim-data.txt");
		try {
			if (!Files.exists(note)) {
				return null;
			}
			Path esm = Path.of(Files.readString(note).trim()).resolve("Skyrim.esm");
			return Files.exists(esm) ? esm : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void loadBuilt() {
		if (builtFile == null || !Files.exists(builtFile)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(builtFile)) {
				String[] f = line.split("\\|");
				if (f.length == 8) {
					BUILT.put(f[0], new Built(f[1], new BoundingBox(Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
						Integer.parseInt(f[6]), Integer.parseInt(f[7]))));
				}
			}
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", builtFile, e);
		}
	}

	private static void saveBuilt() {
		if (builtFile == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		BUILT.forEach((name, b) -> lines.add(name + "|" + b.structure + "|" + b.box.minX() + "|" + b.box.minY() + "|" + b.box.minZ() + "|" + b.box.maxX() + "|"
			+ b.box.maxY() + "|" + b.box.maxZ()));
		try {
			// Whole or not at all (half a file: structures built again on top of themselves).
			Path temp = builtFile.resolveSibling(builtFile.getFileName() + ".tmp");
			Files.write(temp, lines);
			Files.move(temp, builtFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", builtFile, e);
		}
		writeAreasForSkyrim();
	}

	/**
	 * The built structures' areas for Skyrim (structure-areas.txt in %LOCALAPPDATA%/SkyCraft, a line each:
	 * minX|minY|minZ|maxX|maxY|maxZ, Minecraft blocks): Skyrim's rocks, cliffs and trees standing in one
	 * (or over it) go whole, rather than cut round its ground in pieces (DigMesh). Not the sea's.
	 */
	private static void writeAreasForSkyrim() {
		String local = System.getenv("LOCALAPPDATA");
		if (local == null || BUILD_ALL) {
			return;
		}
		List<String> lines = new ArrayList<>();
		for (Built b : BUILT.values()) {
			String id = b.structure;
			if (b.box.getXSpan() < 2 || id.contains("monument") || id.contains("ocean_ruin") || id.equals("minecraft:shipwreck") || id.contains("buried_treasure")) {
				continue;
			}
			// Its ground reaches six blocks round it; Skyrim's things floating over it, well above.
			lines.add((b.box.minX() - 6) + "|" + (b.box.minY() - 4) + "|" + (b.box.minZ() - 6) + "|" + (b.box.maxX() + 6) + "|" + (b.box.maxY() + 30) + "|" + (b.box.maxZ() + 6));
		}
		Path out = Path.of(local, "SkyCraft", "structure-areas.txt");
		try {
			Path temp = out.resolveSibling("structure-areas.txt.tmp");
			Files.write(temp, lines);
			Files.move(temp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't write {} for Skyrim: {}", out, e.toString());
		}
	}

	/** Development: SKYCRAFT_BUILD_ALL_STRUCTURES=1 builds every spot at once, with no Skyrim or player. */
	private static final boolean BUILD_ALL = "1".equals(System.getenv("SKYCRAFT_BUILD_ALL_STRUCTURES"));

	/** Where each player last was inside Skyrim's border (the open world). */
	private static final Map<java.util.UUID, net.minecraft.world.phys.Vec3> INSIDE = new HashMap<>();

	/**
	 * Skyrim's invisible walls hold Minecraft's players too: flying past them took Skyrim's player
	 * off its world (no cell there, and Skyrim crashed). Over the line, back to where they were.
	 */
	private static void keepInsideBorder(MinecraftServer server, SkyrimMap m) {
		ServerLevel overworld = server.overworld();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != overworld || !player.isAlive()) {
				continue;
			}
			var pos = player.position();
			if (m.insideBorder(pos.x, pos.z, 0.0)) {
				INSIDE.put(player.getUUID(), pos);
				continue;
			}
			var back = INSIDE.get(player.getUUID());
			if (back == null) {
				continue;  // never seen inside (came in out there): left alone
			}
			player.teleportTo(back.x, back.y, back.z);
			player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("You can't go that way."));
		}
		INSIDE.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	private static void tick(MinecraftServer server) {
		SkyrimMap m = map;
		if (m != null && SkyWorld.valid() && SkyWorld.tamriel() && !SkyWorld.interior()) {
			keepInsideBorder(server, m);
		}
		if (pending != null && m != null) {
			continueBuilding(server.overworld(), m);
			return;
		}
		if (BUILD_ALL && enabled && m != null) {
			for (SkyrimMap.Site site : m.sites()) {
				if (!BUILT.containsKey(site.name())) {
					build(server.overworld(), m, site);
					return;
				}
			}
			return;
		}
		if (!enabled || m == null || server.getTickCount() % 20 != 7 || !SkyWorld.valid() || !SkyWorld.tamriel()) {
			return;
		}
		ServerLevel level = server.overworld();
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() == level && dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
			}
		}
		if (host == null) {
			return;
		}
		for (SkyrimMap.Site site : m.sites()) {
			forgetIfMoved(site);
		}
		for (SkyrimMap.Site site : m.sites()) {
			double dx = site.x() - host.getX(), dz = site.z() - host.getZ();
			double distance = Math.sqrt(dx * dx + dz * dz);
			if (!BUILT.containsKey(site.name()) && distance < BUILD_DISTANCE && !Double.isNaN(site.groundY()) && pending == null) {
				build(level, m, site);
				return;  // one a second at most
			}
		}
		if (server.getTickCount() % 100 == 7) {
			spawnTheirOwn(level, host);
		}
	}

	/** Builds one structure at its spot, as /place structure would, on Skyrim's ground. */
	private static void build(ServerLevel level, SkyrimMap m, SkyrimMap.Site site) {
		var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Optional<Holder.Reference<Structure>> holder = registry.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(site.structure())));
		if (holder.isEmpty() || !(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat)) {
			BUILT.put(site.name(), new Built(site.structure(), BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO)));
			saveBuilt();
			return;
		}
		// Laid out at an ordinary Minecraft height (vanilla's structures want ground above sea level,
		// the mansion above y 60), then moved down to Skyrim's ground.
		int shift = 80 - (int) Math.floor(site.groundY());
		boolean ocean = site.structure().contains("ocean") || site.structure().contains("monument") || site.structure().contains("shipwreck");
		Holder<Biome> biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biomeFor(site.structure()));
		// The sea there: Skyrim's water at the spot (a monument built to the world's usual sea level stood
		// over water lower than that, its own water hanging above Skyrim's).
		double localWater = m.waterAt(site.x(), site.z());
		double sea = Double.isNaN(localWater) ? m.seaLevel() : localWater;
		Ground ground = new Ground(flat.settings().withBiomeAndLayers(List.of(), Optional.empty(), biome), m, shift, ocean, sea);
		BlockPos at = BlockPos.containing(site.x(), site.groundY(), site.z());
		long t0 = System.nanoTime();
		StructureStart start;
		placing = m;
		placingLevel = level;
		try {
			start = holder.get().value().generate(holder.get(), level.dimension(), level.registryAccess(), ground, ground.getBiomeSource(),
				level.getChunkSource().randomState().createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED),
				level.getChunkSource().randomState(), level.getStructureTemplateManager(), level.getSeed() ^ site.name().hashCode(), ChunkPos.containing(at), 0, level,
				b -> true);
			if (!start.isValid()) {
				SkyCraft.LOG.warn("SkyCraft: {} ({}) couldn't be laid out at {}", site.name(), site.structure(), at.toShortString());
				BUILT.put(site.name(), new Built(site.structure(), BoundingBox.fromCorners(at, at)));
				saveBuilt();
				return;
			}
			for (StructurePiece piece : start.getPieces()) {
				piece.move(0, -shift, 0);
			}
			if (site.structure().contains("monument")) {
				// Minecraft builds a monument at a fixed height (y 39, 24 under its usual sea), whatever the
				// ground: moved down to Skyrim's ground it was buried. 24 under Skyrim's sea there.
				int lowest = start.getPieces().stream().mapToInt(piece -> piece.getBoundingBox().minY()).min().orElse(0);
				int by = (int) Math.round(sea) - 24 - lowest;
				for (StructurePiece piece : start.getPieces()) {
					piece.move(0, by, 0);
				}
			}
			BoundingBox box = start.getBoundingBox();
			ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
			ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
			// Its neighbours too: blocks placed look round them (a pumpkin for a golem to make). Kept
			// loaded while it's placed over the next ticks.
			int reach = (Math.max(max.x() - min.x(), max.z() - min.z()) + 1) / 2 + 1;
			level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
				new ChunkPos((min.x() + max.x()) / 2, (min.z() + max.z()) / 2), Math.min(reach, 8));
			List<ChunkPos> chunks = new ArrayList<>();
			for (int cx = min.x(); cx <= max.x(); cx++) {
				for (int cz = min.z(); cz <= max.z(); cz++) {
					chunks.add(new ChunkPos(cx, cz));
				}
			}
			// SkyCraft+ : performance. The chunks nearest a player first: the side being walked up to
			// is finished before the far one.
			var near = level.getNearestPlayer(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, -1.0, false);
			if (near != null) {
				double px = near.getX(), pz = near.getZ();
				chunks.sort(java.util.Comparator.comparingDouble(c -> {
					double dx = c.getMiddleBlockX() - px, dz = c.getMiddleBlockZ() - pz;
					return dx * dx + dz * dz;
				}));
			}
			pending = new Pending(site, start, ground, box, chunks, t0);
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't build {} ({})", site.name(), site.structure(), e);
			BUILT.put(site.name(), new Built(site.structure(), BoundingBox.fromCorners(at, at)));
			saveBuilt();
		} finally {
			placing = null;
			placingLevel = null;
			placingSea = Integer.MIN_VALUE;
		}
	}

	private static long waitingSince;

	private static boolean loadedAround(ServerLevel level, ChunkPos c) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (level.getChunkSource().getChunkNow(c.x() + dx, c.z() + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	// ---- the ground round a structure -----------------------------------------------------------

	/**
	 * The cells a structure wrote while placed (air too: its rooms), so its ground doesn't fill
	 * them (LevelSetBlockMixin, while placing).
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet WRITTEN = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static @Nullable List<int[]> padColumns;
	/** Each pad column's pieces' bottoms (a house's floor over a path below it). */
	private static final Map<Long, List<Integer>> BOTTOMS = new HashMap<>();
	private static int padNext;

	/** The structure being placed wrote this cell (its rooms' air too). */
	public static boolean wrote(BlockPos pos) {
		return WRITTEN.contains(pos.asLong());
	}

	/** SkyCraft+ : structure sand. Sand the structure being placed put here waits for its ground. */
	public static boolean holdsFall(BlockPos pos) {
		return pending != null && WRITTEN.contains(pos.asLong());
	}

	public static void noteWritten(Level level, BlockPos pos) {
		if (!level.isClientSide()) {
			WRITTEN.add(pos.asLong());
		}
	}

	private static final int UNDER = 0, EDGE = 1, SPECK = 2;  // what a column of the pad is
	private static final int EDGE_BLOCKS = 3, SPECKS_TO = 6;

	/**
	 * The pad's columns: under the structure's pieces (with how low and high they go), a ring of
	 * EDGE_BLOCKS round them, and specks scattered a few blocks further out. None at sea.
	 */
	private static List<int[]> padPlan(Pending job) {
		List<int[]> columns = new ArrayList<>();
		String id = job.site().structure();
		if (id.contains("monument") || id.contains("ocean_ruin") || id.equals("minecraft:shipwreck") || id.contains("buried_treasure")) {
			return columns;
		}
		Map<Long, int[]> under = new HashMap<>();
		for (StructurePiece piece : job.start().getPieces()) {
			BoundingBox b = piece.getBoundingBox();
			for (int x = b.minX(); x <= b.maxX(); x++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					int[] range = under.computeIfAbsent(ChunkPos.pack(x, z), k -> new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE });
					range[0] = Math.min(range[0], b.minY());
					range[1] = Math.max(range[1], b.maxY());
					BOTTOMS.computeIfAbsent(ChunkPos.pack(x, z), k -> new ArrayList<>()).add(b.minY());
				}
			}
		}
		Map<Long, Integer> around = new HashMap<>();
		for (long key : under.keySet()) {
			int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
			for (int dx = -SPECKS_TO; dx <= SPECKS_TO; dx++) {
				for (int dz = -SPECKS_TO; dz <= SPECKS_TO; dz++) {
					long n = ChunkPos.pack(x + dx, z + dz);
					if (under.containsKey(n)) {
						continue;
					}
					int ring = Math.max(Math.abs(dx), Math.abs(dz)) <= EDGE_BLOCKS ? EDGE : SPECK;
					around.merge(n, ring, Math::min);
				}
			}
		}
		under.forEach((key, range) -> columns.add(new int[] { ChunkPos.getX(key), ChunkPos.getZ(key), UNDER, range[0], range[1] }));
		around.forEach((key, ring) -> {
			int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
			if (ring == EDGE || Math.floorMod((x * 73856093) ^ (z * 19349663), 10) < 3) {  // specks: about one in three
				columns.add(new int[] { x, z, ring, 0, 0 });
			}
		});
		return columns;
	}

	/** Ground blocks for this structure's land: top, under it, and deeper. */
	private static BlockState[] groundFor(String structure, double groundY) {
		if (structure.contains("desert_pyramid") || structure.contains("village_desert")) {  // (not a "desert" ruined portal out on the tundra)
			return new BlockState[] { Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), Blocks.STONE.defaultBlockState() };
		}
		if (structure.contains("snowy") || structure.contains("igloo") || groundY > 120) {
			return new BlockState[] { Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState() };
		}
		return new BlockState[] { Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState() };
	}

	/**
	 * Lays the pad's next columns, until the deadline: Skyrim's ground there is dug out (its land,
	 * grass and clutter go, and the underground never fills the structure's rooms), and Minecraft
	 * ground put in its place, level with Skyrim's land round it; under a structure standing above
	 * the land, ground up to it (no floating cages). True when done.
	 */
	private static boolean pad(ServerLevel level, SkyrimMap m, Pending job, long deadline) {
		List<int[]> columns = padColumns;
		int world = 0x3C;  // Tamriel's: the host may be in a city's worldspace or a house meanwhile
		BlockState[] ground = groundFor(job.site().structure(), job.site().groundY());
		int flags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		SkyDig.beginBatch();
		try {
			while (padNext < columns.size() && System.nanoTime() < deadline) {
				int[] c = columns.get(padNext++);
				int x = c[0], z = c[1];
				double g = m.groundAt(x + 0.5, z + 0.5);
				if (Double.isNaN(g)) {
					continue;
				}
				int top = (int) Math.round(g);  // ground cells are below this
				// Round it, none where Skyrim's water comes up to the ground (a river's bank): its water
				// showed over the blocks, or flickered on their tops.
				double w = m.waterAt(x + 0.5, z + 0.5);
				if (c[2] != UNDER && !Double.isNaN(w) && w > top - 0.25) {
					continue;
				}
				int lo, hi;
				if (c[2] == UNDER) {
					// SkyCraft+ : structure sand. At least three deep (under a piece standing on the land
					// it was one: a desert's sand on top of nothing fell, and broke).
					lo = Math.min(top - 3, c[3] - 1);
					hi = Math.max(top + 1, c[4]);
				} else if (c[2] == EDGE) {
					lo = top - 3;
					hi = top + 1;
				} else {
					lo = top - 2;  // two deep: sand on top has something under it (it fell, and broke)
					hi = top + 1;
				}
				for (int y = lo; y <= hi; y++) {
					SkyDig.markDug(level, world, pos.set(x, y, z));
				}
				// Its rooms below its box too (a temple's cellar, its stairs down): the underground
				// never fills them.
				for (int y = lo - 1; c[2] == UNDER && y >= lo - 48; y--) {
					if (WRITTEN.contains(pos.set(x, y, z).asLong())) {
						SkyDig.markDug(level, world, pos);
					}
				}
				// Minecraft ground below Skyrim's land (not where the structure put anything).
				for (int y = lo; y < top; y++) {
					pos.set(x, y, z);
					if (!WRITTEN.contains(pos.asLong()) && level.getBlockState(pos).isAir()) {
						level.setBlock(pos, y == top - 1 ? ground[0] : y >= top - 4 ? ground[1] : ground[2], flags);
					}
				}
				// And up to a structure standing above the land, where its bottom does (an allay's cage),
				// not under what juts out higher up (a tower's roof).
				if (c[2] == UNDER) {
					int lowest = Integer.MAX_VALUE;
					for (int y = c[3]; y <= c[4]; y++) {
						pos.set(x, y, z);
						if (WRITTEN.contains(pos.asLong()) && !level.getBlockState(pos).isAir()) {
							lowest = y;
							break;
						}
					}
					final int bottom = lowest;
					boolean onItsBottom = BOTTOMS.getOrDefault(ChunkPos.pack(x, z), List.of()).stream().anyMatch(b -> bottom - b >= 0 && bottom - b <= 1);
					for (int y = top; y < lowest && onItsBottom; y++) {
						pos.set(x, y, z);
						if (!WRITTEN.contains(pos.asLong()) && level.getBlockState(pos).isAir()) {
							level.setBlock(pos, ground[1], flags);
						}
					}
				}
			}
		} finally {
			SkyDig.endBatch();
		}
		return padNext >= columns.size();
	}

	/** Places the pending structure's next chunks, as many as PLACE_NANOS allows; done: it's recorded. */
	private static void continueBuilding(ServerLevel level, SkyrimMap m) {
		Pending job = pending;
		long deadline = System.nanoTime() + PLACE_NANOS;
		placing = m;
		placingLevel = level;
		placingSea = ((Ground) job.ground()).seaBlock();
		try {
			while (pendingNext < job.chunks().size() && System.nanoTime() < deadline) {
				ChunkPos c = job.chunks().get(pendingNext);
				// Its chunk and those round it loaded (in the background, by the ticket set when it
				// was laid out): loading them all at once stalled the game.
				if (!loadedAround(level, c)) {
					if (waitingSince == 0L) {
						waitingSince = System.nanoTime();
					}
					if (System.nanoTime() - waitingSince < 10_000_000_000L) {
						return;
					}
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							level.getChunk(c.x() + dx, c.z() + dz);  // waited long enough: now
						}
					}
				}
				waitingSince = 0L;
				pendingNext++;
				job.start().placeInChunk(level, level.structureManager(), job.ground(), level.getRandom(),
					new BoundingBox(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX(), level.getMaxY() + 1, c.getMaxBlockZ()), c);
			}
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't finish building {} ({})", job.site().name(), job.site().structure(), e);
			pendingNext = job.chunks().size();
		} finally {
			placing = null;
			placingLevel = null;
			placingSea = Integer.MIN_VALUE;
		}
		if (pendingNext < job.chunks().size()) {
			return;
		}
		// Placed: then the ground round it (a few columns a tick too).
		if (padColumns == null) {
			padColumns = padPlan(job);
			// SkyCraft+ : performance. Nearest a player first, as the chunks.
			var near = level.getNearestPlayer(job.box().getCenter().getX(), job.box().getCenter().getY(), job.box().getCenter().getZ(), -1.0, false);
			if (near != null) {
				double px = near.getX(), pz = near.getZ();
				padColumns.sort(java.util.Comparator.comparingDouble(c -> (c[0] - px) * (c[0] - px) + (c[1] - pz) * (c[1] - pz)));
			}
			padNext = 0;
		}
		if (!pad(level, m, job, deadline)) {
			return;
		}
		padColumns = null;
		WRITTEN.clear();
		BOTTOMS.clear();
		pending = null;
		pendingNext = 0;
		// Where it ended up: temples and huts settle onto the ground as they're placed.
		BoundingBox placed = BoundingBox.encapsulatingBoxes(job.start().getPieces().stream().map(StructurePiece::getBoundingBox).toList()).orElse(job.box());
		BUILT.put(job.site().name(), new Built(job.site().structure(), placed));
		saveBuilt();
		var area = new net.minecraft.world.phys.AABB(placed.minX(), placed.minY(), placed.minZ(), placed.maxX() + 1, placed.maxY() + 1, placed.maxZ() + 1);
		int mobs = level.getEntitiesOfClass(Mob.class, area, Mob::isAlive).size();
		SkyCraft.LOG.info("SkyCraft: built {} ({}) at {} in {} ms ({} chunks, {} mobs with it)", job.site().name(), job.site().structure(),
			placed.getCenter().toShortString(), (System.nanoTime() - job.started()) / 1_000_000, job.chunks().size(), mobs);
		if (!BUILD_ALL) {
			String what = job.site().name().replaceAll(" [0-9]+$", "");
			level.getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("A " + what.toLowerCase(java.util.Locale.ROOT) + " stands nearby."), false);
		}
	}

	private static ResourceKey<Biome> biomeFor(String structure) {
		if (structure.contains("monument") || structure.contains("ocean_ruin")) {
			return Biomes.DEEP_COLD_OCEAN;
		}
		if (structure.contains("shipwreck") || structure.contains("buried_treasure")) {
			return Biomes.BEACH;
		}
		if (structure.contains("snowy") || structure.contains("igloo")) {
			return Biomes.SNOWY_PLAINS;
		}
		if (structure.contains("desert_pyramid") || structure.contains("village_desert")) {  // (not a "desert" ruined portal out on the tundra)
			return Biomes.DESERT;
		}
		if (structure.contains("savanna")) {
			return Biomes.SAVANNA;
		}
		if (structure.contains("taiga")) {
			return Biomes.TAIGA;
		}
		if (structure.contains("jungle")) {
			return Biomes.JUNGLE;
		}
		if (structure.contains("swamp")) {
			return Biomes.SWAMP;
		}
		if (structure.contains("mansion")) {
			return Biomes.DARK_FOREST;
		}
		return Biomes.PLAINS;
	}

	/**
	 * Outposts, huts and monuments keep spawning their own (pillagers, witches, guardians) as in
	 * Minecraft, which spawns these by structure: mob spawning is off in the mirror world (SkySpawner
	 * fills Skyrim's), so here they're brought in near the host now and then, inside the structure.
	 */
	private static void spawnTheirOwn(ServerLevel level, ServerPlayer host) {
		RandomSource random = level.getRandom();
		for (Built built : BUILT.values()) {
			BoundingBox box = built.box;
			if (box.getXSpan() < 2 || host.blockPosition().distManhattan(box.getCenter()) > SPAWN_DISTANCE * 2) {
				continue;
			}
			var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(built.structure)));
			if (holder.isEmpty()) {
				continue;
			}
			var override = holder.get().value().spawnOverrides().get(MobCategory.MONSTER);
			if (override == null || override.spawns().isEmpty()) {
				continue;
			}
			var area = new net.minecraft.world.phys.AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1).inflate(8.0);
			if (level.getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && mob.getType().getCategory() == MobCategory.MONSTER).size() >= MOBS_PER_STRUCTURE) {
				continue;
			}
			MobSpawnSettings.SpawnerData data = override.spawns().getRandom(random).orElse(null);
			if (data == null) {
				continue;
			}
			for (int tries = 0; tries < 32; tries++) {
				int x = box.minX() + random.nextInt(box.getXSpan()), z = box.minZ() + random.nextInt(box.getZSpan());
				for (int y = box.maxY(); y >= box.minY(); y--) {
					BlockPos pos = new BlockPos(x, y, z);
					// Standing on a block with room (Minecraft's own rules turned down most of an outpost's floor);
					// in water, for those that live there (a monument's guardians).
					boolean swims = net.minecraft.world.entity.SpawnPlacements.getPlacementType(data.type()) == net.minecraft.world.entity.SpawnPlacementTypes.IN_WATER;
					boolean room = swims ? level.getFluidState(pos).isSource() && level.getFluidState(pos.above()).isSource()
						: level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP) && level.getBlockState(pos).isAir();
					if (!room || !level.noCollision(data.type().getSpawnAABB(x + 0.5, y, z + 0.5))) {
						continue;
					}
					Entity entity = data.type().create(level, EntitySpawnReason.STRUCTURE);
					if (entity instanceof Mob mob) {
						mob.snapTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
						mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.STRUCTURE, null);
						level.addFreshEntityWithPassengers(mob);
					} else if (entity != null) {
						entity.discard();
					}
					tries = 99;
					break;
				}
			}
		}
	}

	/** The ground near a spot (Minecraft y), as Level.getHeight reports it while placing (LevelHeightMixin). */
	public static int heightWhilePlacing(SkyrimMap m, Heightmap.Types type, int x, int z, int vanilla) {
		double ground = m.groundAt(x + 0.5, z + 0.5);
		if (Double.isNaN(ground)) {
			return vanilla;
		}
		double top = ground;
		if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) {
			top = Math.max(top, m.waterAt(x + 0.5, z + 0.5));
		}
		return Math.max(vanilla, (int) Math.round(top));
	}

	/**
	 * The mirror world's generator, but its ground is Skyrim's land near a spot (shifted up by
	 * {@code shift}, so vanilla's structures find it at an ordinary height), with water up to
	 * Skyrim's where there is some.
	 */
	private static final class Ground extends FlatLevelSource {
		private final SkyrimMap m;
		private final int shift;
		private final boolean ocean;
		private final double sea;

		Ground(FlatLevelGeneratorSettings settings, SkyrimMap m, int shift, boolean ocean, double sea) {
			super(settings);
			this.m = m;
			this.shift = shift;
			this.ocean = ocean;
			this.sea = sea;
		}

		private double ground(int x, int z) {
			double g = this.m.groundAt(x + 0.5, z + 0.5);
			return Double.isNaN(g) ? 64 - this.shift : g;
		}

		private double water(int x, int z) {
			return this.m.waterAt(x + 0.5, z + 0.5);
		}

		@Override
		public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
			double top = this.ground(x, z);
			if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) {
				top = Math.max(top, this.water(x, z));
			}
			return (int) Math.round(top) + this.shift + 1;
		}

		@Override
		public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
			int minY = heightAccessor.getMinY();
			BlockState[] column = new BlockState[heightAccessor.getHeight()];
			int groundTop = (int) Math.round(this.ground(x, z)) + this.shift;
			int waterTop = (int) Math.round(this.water(x, z)) + this.shift;
			for (int i = 0; i < column.length; i++) {
				int y = minY + i;
				column[i] = y <= groundTop ? Blocks.STONE.defaultBlockState() : y <= waterTop ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
			}
			return new NoiseColumn(minY, column);
		}

		@Override
		public int getSeaLevel() {
			// Above water: as low as can be, so nothing counts as under the sea; at sea: Skyrim's.
			return this.ocean ? (int) Math.round(this.sea) + this.shift : -1000;
		}

		/** The sea level an ocean structure sees while placed (MIN_VALUE: Minecraft's own). */
		int seaBlock() {
			return this.ocean ? (int) Math.round(this.sea) : Integer.MIN_VALUE;
		}

		@Override
		public net.minecraft.world.level.biome.BiomeSource getBiomeSource() {
			return super.getBiomeSource();
		}
	}
}
