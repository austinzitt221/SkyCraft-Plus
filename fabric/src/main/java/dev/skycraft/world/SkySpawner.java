// SkyCraft+ : living world
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * A living Minecraft world in Skyrim. The mirror world is a void with no biomes, so vanilla
 * spawning finds nowhere to put anything. This spawns around the host on Skyrim's ground instead:
 * animals by what the ground is (grass, snow, sand, rock, forest), monsters at Skyrim's night in
 * the wilds and at any hour in dungeons, never in settlements. Minecraft's clock and weather follow
 * Skyrim's. Settings in config/skycraft.properties (spawnAnimals, spawnMonsters, animalCap,
 * monsterCap).
 */
public final class SkySpawner {
	/** Animals this spawned: put away again when the player is far (unless tamed, leashed or named). */
	public static final String SPAWNED_TAG = "skycraft_spawned";

	private static final int RANGE = 48;           // blocks around the player that count against the caps
	private static final int MIN_DISTANCE = 24;    // outdoors: out of sight, as vanilla
	private static final int MAX_DISTANCE = 42;    // inside the Skyrim ground the player has had sent
	private static final int DESPAWN_DISTANCE = 64;

	private static boolean spawnAnimals = true;
	private static boolean spawnMonsters = true;
	private static boolean spawnVillagers = true;
	private static int animalCap = 8;
	private static int monsterCap = 6; // about half of a vanilla night around one player
	// SkyCraft+ : fewer in Skyrim's dungeons and caves (their own draugr and bandits are there too):
	// a room has one or two (ROOM_MONSTERS within ROOM_RADIUS), the dungeon around the player a few.
	private static int dungeonMonsterCap = 5;
	private static final int ROOM_MONSTERS = 2;
	private static final double ROOM_RADIUS = 12.0;
	// How dungeon spawns go (the log, once a minute): tries, no ground there, out of reach, room full, spawned.
	private static final int[] DUNGEON_TRIES = new int[5];
	// SkyCraft+ : wandering traders, now and then anywhere outdoors: the chance every two minutes (percent).
	private static boolean wanderingTraders = true;
	private static int wanderingTraderChance = 10;
	private static int villagerCap = 6; // within VILLAGER_RANGE: a city's worth, a few streets apart
	private static final int VILLAGER_RANGE = 64;
	private static final List<ResourceKey<VillagerProfession>> JOBS = List.of(VillagerProfession.ARMORER, VillagerProfession.BUTCHER,
		VillagerProfession.CARTOGRAPHER, VillagerProfession.CLERIC, VillagerProfession.FARMER, VillagerProfession.FISHERMAN,
		VillagerProfession.FLETCHER, VillagerProfession.LEATHERWORKER, VillagerProfession.LIBRARIAN, VillagerProfession.MASON,
		VillagerProfession.SHEPHERD, VillagerProfession.TOOLSMITH, VillagerProfession.WEAPONSMITH);

	private static int lastTimeSet = -1;
	private static int lastWeather = -1;

	private record Weighted(EntityType<? extends Mob> type, int weight, int minGroup, int maxGroup) {
	}

	private SkySpawner() {
	}

	public static void init() {
		dev.skycraft.SkySettings.onChange(SkySpawner::loadSettings);  // SkyCraft+ : the settings screen
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			loadSettings();
			lastTimeSet = -1;
			lastWeather = -1;
		});
		ServerTickEvents.END_SERVER_TICK.register(SkySpawner::tick);
	}

	private static void loadSettings() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader in = Files.newBufferedReader(file)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", file, e.toString());
			}
		}
		spawnAnimals = Boolean.parseBoolean(p.getProperty("spawnAnimals", "true").trim());
		spawnMonsters = Boolean.parseBoolean(p.getProperty("spawnMonsters", "true").trim());
		spawnVillagers = Boolean.parseBoolean(p.getProperty("spawnVillagers", "true").trim());
		animalCap = parseInt(p.getProperty("animalCap"), animalCap);
		monsterCap = parseInt(p.getProperty("monsterCap"), monsterCap);
		dungeonMonsterCap = parseInt(p.getProperty("dungeonMonsterCap"), dungeonMonsterCap);
		villagerCap = parseInt(p.getProperty("villagerCap"), villagerCap);
		wanderingTraders = Boolean.parseBoolean(p.getProperty("wanderingTraders", "true").trim());
		wanderingTraderChance = parseInt(p.getProperty("wanderingTraderChance"), wanderingTraderChance);
		SkyCraft.LOG.info("SkyCraft: spawning animals {} (up to {}), monsters {} (up to {}, {} in dungeons), villagers in towns {} (up to {})", spawnAnimals, animalCap,
			spawnMonsters, monsterCap, dungeonMonsterCap, spawnVillagers, villagerCap);
	}

	private static int parseInt(@Nullable String s, int fallback) {
		try {
			return s == null ? fallback : Math.max(0, Integer.parseInt(s.trim()));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static void tick(MinecraftServer server) {
		if (!SkyWorld.valid() || !SkyCollision.active() || server.getTickCount() % 20 != 0) {
			return;
		}
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
				break;
			}
		}
		syncTimeAndWeather(server);
		if (host == null || !host.isAlive() || host.isSpectator()) {
			return;
		}
		ServerLevel level = host.level();
		if (server.getTickCount() % 100 == 0) {
			despawnFarAnimals(level);
		}
		RandomSource random = level.getRandom();
		boolean outdoors = !SkyWorld.interior();
		if (spawnAnimals && outdoors && count(level, host, MobCategory.CREATURE) < animalCap) {
			trySpawn(level, host, random, false);
		}
		if (wanderingTraders && outdoors && server.getTickCount() % 2400 == 0 && random.nextInt(100) < wanderingTraderChance) {
			trySpawnTrader(level, host, random);
		}
		if (spawnVillagers && outdoors && SkyWorld.town() && server.getTickCount() % 200 == 0 && countVillagers(level, host) < villagerCap) {
			trySpawnVillager(level, host, random);
		}
		boolean monsterTime = SkyWorld.dungeon() || (outdoors && SkyWorld.night());
		boolean inDungeon = !outdoors || SkyWorld.dungeon();
		int cap = inDungeon ? dungeonMonsterCap : monsterCap;
		if (spawnMonsters && monsterTime && !SkyWorld.settlement() && count(level, host, MobCategory.MONSTER) < cap) {
			trySpawn(level, host, random, true);
		}
		// SkyCraft+ : the underground. Its caves have monsters of their own, at any hour.
		if (spawnMonsters && SkyUnderground.caveMonsterCap() > 0) {
			SkyCaves.tick(level, host, random, SkyUnderground.caveMonsterCap());
		}
	}

	/** Minecraft's clock and weather follow Skyrim's (its time doesn't run on its own here). */
	private static void syncTimeAndWeather(MinecraftServer server) {
		// Minecraft's day starts at 6am (0) with noon at 6000 and midnight at 18000.
		int ticks = Math.floorMod(Math.round((SkyWorld.hour() - 6.0F) * 1000.0F), 24000);
		if (lastTimeSet < 0 || Math.abs(ticks - lastTimeSet) >= 10) {
			lastTimeSet = ticks;
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set " + ticks);
		}
		int weather = SkyWorld.rain() || SkyWorld.snow() ? 1 : 0;
		if (weather != lastWeather) {
			lastWeather = weather;
			if (weather == 1) {
				server.setWeatherParameters(0, 12000, true, false);
			} else {
				server.setWeatherParameters(12000, 0, false, false);
			}
		}
	}

	/**
	 * SkyCraft+ : a wandering trader, with its two llamas, somewhere near the player outdoors (out
	 * of sight, as Minecraft's): it wanders about there for a couple of Minecraft days and leaves,
	 * as Minecraft's do. Never more than one around.
	 */
	private static void trySpawnTrader(ServerLevel level, ServerPlayer player, RandomSource random) {
		if (!level.getEntitiesOfClass(net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class, player.getBoundingBox().inflate(160.0),
			net.minecraft.world.entity.LivingEntity::isAlive).isEmpty()) {
			return;
		}
		for (int attempt = 0; attempt < 10; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
			BlockPos ground = findGround(level, x, (int) Math.floor(player.getY()), z);
			if (ground == null) {
				continue;
			}
			var trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.EVENT);
			if (trader == null) {
				return;
			}
			trader.snapTo(ground.getX() + 0.5, SkyCollision.floorUnder(ground) + 0.01, ground.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
			if (!level.noCollision(trader)) {
				trader.discard();
				continue;
			}
			trader.finalizeSpawn(level, level.getCurrentDifficultyAt(ground), EntitySpawnReason.EVENT, null);
			trader.setDespawnDelay(48000);
			trader.setWanderTarget(ground);
			trader.setHomeTo(ground, 16);
			if (!level.tryAddFreshEntityWithPassengers(trader)) {
				return;
			}
			int llamas = 0;
			for (int i = 0; i < 8 && llamas < 2; i++) {
				BlockPos at = findGround(level, ground.getX() + random.nextInt(7) - 3, ground.getY(), ground.getZ() + random.nextInt(7) - 3);
				var llama = at == null ? null : EntityTypes.TRADER_LLAMA.create(level, EntitySpawnReason.EVENT);
				if (llama == null) {
					continue;
				}
				llama.snapTo(at.getX() + 0.5, SkyCollision.floorUnder(at) + 0.01, at.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
				if (!level.noCollision(llama)) {
					llama.discard();
					continue;
				}
				llama.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
				if (level.tryAddFreshEntityWithPassengers(llama)) {
					llama.setLeashedTo(trader, true);
					llamas++;
				}
			}
			SkyCraft.LOG.info("SkyCraft: a wandering trader came by ({} llamas) at {}", llamas, ground.toShortString());
			return;
		}
	}

	private static int countVillagers(ServerLevel level, ServerPlayer player) {
		return level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(VILLAGER_RANGE), Villager::isAlive).size();
	}

	/**
	 * Skyrim's cities and towns are villager trading posts: villagers spawn there already in a
	 * job (no workstations in Skyrim to take one at). One point of experience, as after a first
	 * trade, keeps the job for good. Their look follows the ground (snow, forest, sand, plains),
	 * and they stay.
	 */
	private static void trySpawnVillager(ServerLevel level, ServerPlayer player, RandomSource random) {
		double angle = random.nextDouble() * Math.PI * 2.0;
		double distance = 8.0 + random.nextDouble() * 22.0;
		int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
		int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
		BlockPos ground = findGround(level, x, (int) Math.floor(player.getY()), z);
		if (ground == null) {
			return;
		}
		Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.NATURAL);
		if (villager == null) {
			return;
		}
		villager.snapTo(ground.getX() + 0.5, SkyCollision.floorUnder(ground) + 0.01, ground.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
		if (!level.noCollision(villager)) {
			villager.discard();
			return;
		}
		villager.finalizeSpawn(level, level.getCurrentDifficultyAt(villager.blockPosition()), EntitySpawnReason.NATURAL, null);
		int material = groundMaterial(ground);
		ResourceKey<VillagerType> type = material == Proto.DIG_SNOW || material == Proto.DIG_ICE ? VillagerType.SNOW
			: material == Proto.DIG_SAND ? VillagerType.DESERT : treesNear(ground) ? VillagerType.TAIGA : VillagerType.PLAINS;
		ResourceKey<VillagerProfession> job = JOBS.get(random.nextInt(JOBS.size()));
		villager.setVillagerData(villager.getVillagerData().withType(level.registryAccess(), type).withProfession(level.registryAccess(), job).withLevel(1));
		villager.setVillagerXp(1);
		villager.setPersistenceRequired();
		level.tryAddFreshEntityWithPassengers(villager);
		SkyCraft.LOG.info("SkyCraft: a {} villager ({}) moved into town at {}", job.identifier().getPath(), type.identifier().getPath(), ground.toShortString());
	}

	private static int count(ServerLevel level, ServerPlayer player, MobCategory category) {
		AABB box = player.getBoundingBox().inflate(RANGE);
		return level.getEntitiesOfClass(Mob.class, box, mob -> mob.isAlive() && mob.getType().getCategory() == category).size();
	}

	private static void trySpawn(ServerLevel level, ServerPlayer player, RandomSource random, boolean monster) {
		boolean indoors = SkyWorld.interior();
		int minDistance = indoors ? 10 : MIN_DISTANCE, maxDistance = indoors ? 28 : MAX_DISTANCE;
		double angle = random.nextDouble() * Math.PI * 2.0;
		double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
		int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
		int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
		BlockPos ground = findGround(level, x, (int) Math.floor(player.getY()), z);
		boolean dungeon = monster && (indoors || SkyWorld.dungeon());
		if (dungeon) {
			DUNGEON_TRIES[0]++;
			if (level.getServer().getTickCount() % 1200 == 0) {
				SkyCraft.LOG.info("SkyCraft: dungeon spawns this minute: {} tries, {} no ground, {} out of reach ({} spots in reach), {} room full, {} spawned",
					DUNGEON_TRIES[0], DUNGEON_TRIES[1], DUNGEON_TRIES[2], REACH.size(), DUNGEON_TRIES[3], DUNGEON_TRIES[4]);
				java.util.Arrays.fill(DUNGEON_TRIES, 0);
			}
		}
		if (ground == null) {
			DUNGEON_TRIES[1] += dungeon ? 1 : 0;
			return;
		}
		if (!walkableTo(level, player, ground)) {
			DUNGEON_TRIES[2] += dungeon ? 1 : 0;
			return;
		}
		int room = dungeon ? level.getEntitiesOfClass(Mob.class, new AABB(ground).inflate(ROOM_RADIUS),
			mob -> mob.isAlive() && mob.getType().getCategory() == MobCategory.MONSTER).size() : 0;
		if (dungeon && room >= ROOM_MONSTERS) {
			DUNGEON_TRIES[3]++;
			return;
		}
		Weighted pick = monster ? pickMonster(level, ground, random) : pickAnimal(level, ground, random);
		if (pick == null) {
			return;
		}
		int group = pick.minGroup() + random.nextInt(pick.maxGroup() - pick.minGroup() + 1);
		if (dungeon) {
			group = Math.min(group, ROOM_MONSTERS - room);
		}
		double floor = SkyCollision.floorUnder(ground);
		for (int i = 0; i < group; i++) {
			double px = ground.getX() + 0.5 + (i == 0 ? 0.0 : random.nextInt(5) - 2);
			double pz = ground.getZ() + 0.5 + (i == 0 ? 0.0 : random.nextInt(5) - 2);
			BlockPos at = i == 0 ? ground : findGround(level, (int) Math.floor(px), ground.getY(), (int) Math.floor(pz));
			if (at == null || !walkableTo(level, player, at)) {
				continue;
			}
			double y = i == 0 ? floor : SkyCollision.floorUnder(at);
			if (spawn(level, pick.type(), px, y, pz, random, !monster) && dungeon) {
				DUNGEON_TRIES[4]++;
			}
		}
	}

	private static boolean spawn(ServerLevel level, EntityType<? extends Mob> type, double x, double y, double z, RandomSource random, boolean animal) {
		Mob mob = type.create(level, EntitySpawnReason.NATURAL);
		if (mob == null) {
			return false;
		}
		mob.snapTo(x, y + 0.01, z, random.nextFloat() * 360.0F, 0.0F);
		if (!level.noCollision(mob)) {
			mob.discard();
			return false;
		}
		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.NATURAL, null);
		if (animal) {
			mob.addTag(SPAWNED_TAG);
		}
		return level.tryAddFreshEntityWithPassengers(mob);
	}

	// SkyCraft+ : no monsters in the walls. Indoors (and in dungeons) a spot only counts if the
	// player could walk there: Skyrim's ground behind a wall (the outside of a cave, the room next
	// door, the space between) is floor too, and mobs spawned there were stuck in the walls.
	private static final HashSet<Long> REACH = new HashSet<>();
	private static long reachAt = Long.MIN_VALUE;
	private static @Nullable BlockPos reachFrom;
	private static final int REACH_RADIUS = 34, REACH_MOST = 12000;

	private static boolean walkableTo(ServerLevel level, ServerPlayer player, BlockPos pos) {
		if (!SkyWorld.interior() && !SkyWorld.dungeon()) {
			return true;
		}
		long now = level.getServer().getTickCount();
		BlockPos feet = player.blockPosition();
		if (now - reachAt > 100 || reachFrom == null || reachFrom.distManhattan(feet) > 6) {
			reachAt = now;
			reachFrom = feet;
			findReach(level, feet);
		}
		return REACH.contains(pos.asLong());
	}

	private static boolean standable(ServerLevel level, BlockPos pos) {
		return SkyCollision.isKnown(pos.getX(), pos.getY(), pos.getZ()) && !SkyCollision.blocksPath(pos) && !SkyCollision.blocksPath(pos.above())
			&& !Double.isNaN(SkyCollision.floorUnder(pos)) && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
	}

	/** Where the player could walk from their feet (a step up, a drop of up to three), near them. */
	private static void findReach(ServerLevel level, BlockPos feet) {
		REACH.clear();
		BlockPos start = null;
		for (int dy : new int[] { 0, 1, -1, 2, -2 }) {
			if (standable(level, feet.above(dy))) {
				start = feet.above(dy);
				break;
			}
		}
		if (start == null) {
			return;
		}
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		REACH.add(start.asLong());
		int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		while (!queue.isEmpty() && REACH.size() < REACH_MOST) {
			BlockPos at = queue.poll();
			for (int[] side : sides) {
				int x = at.getX() + side[0], z = at.getZ() + side[1];
				if (Math.abs(x - feet.getX()) > REACH_RADIUS || Math.abs(z - feet.getZ()) > REACH_RADIUS) {
					continue;
				}
				for (int dy : new int[] { 0, 1, -1, -2, -3 }) {
					BlockPos next = new BlockPos(x, at.getY() + dy, z);
					if (dy == 1 && SkyCollision.blocksPath(at.above(2))) {
						continue;  // (no head room to step up)
					}
					if (standable(level, next)) {
						if (REACH.add(next.asLong())) {
							queue.add(next);
						}
						break;
					}
					if (dy < 0 && SkyCollision.blocksPath(next)) {
						break;  // (a wall or floor in the way down)
					}
				}
			}
		}
	}

	/**
	 * A spot on Skyrim's ground near (x, z), within a few blocks of the player's height: held up by
	 * Skyrim ground, room for a mob above it, not under water and inside what Skyrim has sent.
	 */
	private static @Nullable BlockPos findGround(ServerLevel level, int x, int playerY, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = playerY + 10; y >= playerY - 16; y--) {
			pos.set(x, y, z);
			if (!SkyCollision.isKnown(x, y, z) || SkyCollision.blocksPath(pos) || Double.isNaN(SkyCollision.floorUnder(pos))) {
				continue;
			}
			BlockPos above = pos.above();
			if (SkyCollision.blocksPath(above) || !level.getBlockState(pos).isAir() || !level.getBlockState(above).isAir() || SkyWater.depthIn(pos) > 0.0F) {
				continue;
			}
			return pos.immutable();
		}
		return null;
	}

	/** What Skyrim's ground under this spot is made of (Proto.DIG_*), and whether trees are near. */
	private static int groundMaterial(BlockPos pos) {
		List<SkyTri> tris = new ArrayList<>();
		double floor = SkyCollision.floorUnder(pos);
		SkyCollision.trianglesNear(new AABB(pos.getX(), floor - 1.0, pos.getZ(), pos.getX() + 1.0, floor + 0.25, pos.getZ() + 1.0), tris);
		int material = Proto.DIG_NONE;
		double best = Double.NEGATIVE_INFINITY;
		for (SkyTri t : tris) {
			if (t.walkable && t.maxY > best) {
				best = t.maxY;
				material = t.material;
			}
		}
		return material;
	}

	private static boolean treesNear(BlockPos pos) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(pos).inflate(6.0, 2.0, 6.0).expandTowards(0.0, 6.0, 0.0), tris);
		for (SkyTri t : tris) {
			if (t.material == Proto.DIG_OAK_LOG || t.material == Proto.DIG_SPRUCE_LOG || t.material == Proto.DIG_BIRCH_LOG) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable Weighted pickAnimal(ServerLevel level, BlockPos pos, RandomSource random) {
		int material = groundMaterial(pos);
		List<Weighted> list = new ArrayList<>();
		switch (material) {
			case Proto.DIG_SNOW, Proto.DIG_ICE -> {
				list.add(new Weighted(EntityTypes.RABBIT, 8, 2, 3));
				list.add(new Weighted(EntityTypes.FOX, 6, 1, 2));
				list.add(new Weighted(EntityTypes.GOAT, 3, 1, 2));
				list.add(new Weighted(EntityTypes.WOLF, 3, 2, 4));
				list.add(new Weighted(EntityTypes.POLAR_BEAR, 1, 1, 2));
			}
			case Proto.DIG_SAND -> list.add(new Weighted(EntityTypes.RABBIT, 6, 2, 3));
			case Proto.DIG_STONE, Proto.DIG_COBBLE, Proto.DIG_GRAVEL -> {
				list.add(new Weighted(EntityTypes.GOAT, 3, 1, 2));
				list.add(new Weighted(EntityTypes.RABBIT, 2, 1, 2));
			}
			case Proto.DIG_GRASS, Proto.DIG_DIRT, Proto.DIG_MUD -> {
				list.add(new Weighted(EntityTypes.SHEEP, 12, 2, 4));
				list.add(new Weighted(EntityTypes.PIG, 10, 2, 4));
				list.add(new Weighted(EntityTypes.COW, 8, 2, 4));
				list.add(new Weighted(EntityTypes.CHICKEN, 10, 2, 4));
				list.add(new Weighted(EntityTypes.RABBIT, 3, 2, 3));
				if (treesNear(pos)) {
					list.add(new Weighted(EntityTypes.WOLF, 5, 2, 4));
					list.add(new Weighted(EntityTypes.FOX, 4, 1, 2));
				} else {
					list.add(new Weighted(EntityTypes.HORSE, 2, 2, 4));
				}
			}
			default -> {
				return null; // roads, wood, metal...: nothing grazes there
			}
		}
		return pick(list, random);
	}

	private static @Nullable Weighted pickMonster(ServerLevel level, BlockPos pos, RandomSource random) {
		boolean snowy = groundMaterial(pos) == Proto.DIG_SNOW || SkyWorld.snow();
		List<Weighted> list = new ArrayList<>();
		list.add(new Weighted(EntityTypes.ZOMBIE, 40, 1, 2));
		list.add(new Weighted(snowy ? EntityTypes.STRAY : EntityTypes.SKELETON, 30, 1, 1));
		list.add(new Weighted(EntityTypes.SPIDER, 25, 1, 1));
		list.add(new Weighted(EntityTypes.CREEPER, 25, 1, 1));
		list.add(new Weighted(EntityTypes.ENDERMAN, 6, 1, 1));
		if (!SkyWorld.interior()) {
			list.add(new Weighted(EntityTypes.WITCH, 2, 1, 1));
		}
		return pick(list, random);
	}

	private static @Nullable Weighted pick(List<Weighted> list, RandomSource random) {
		int total = 0;
		for (Weighted w : list) {
			total += w.weight();
		}
		if (total <= 0) {
			return null;
		}
		int r = random.nextInt(total);
		for (Weighted w : list) {
			r -= w.weight();
			if (r < 0) {
				return w;
			}
		}
		return null;
	}

	/**
	 * Animals this spawned don't pile up all over Skyrim: far from every player (beyond the Skyrim
	 * ground Minecraft knows about), they go, unless tamed, leashed, named or ridden.
	 */
	private static void despawnFarAnimals(ServerLevel level) {
		List<Entity> gone = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (!(e instanceof Mob mob) || !mob.entityTags().contains(SPAWNED_TAG)) {
				continue;
			}
			if (mob.hasCustomName() || mob.isVehicle() || mob.isPersistenceRequired() || (mob instanceof TamableAnimal tame && tame.isTame())
				|| (mob instanceof Leashable leash && leash.isLeashed())) {
				mob.removeTag(SPAWNED_TAG); // the player has taken it on: it stays for good
				continue;
			}
			if (level.getNearestPlayer(mob, DESPAWN_DISTANCE) == null) {
				gone.add(mob);
			}
		}
		for (Entity e : gone) {
			e.discard();
		}
	}
}
