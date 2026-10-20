// SkyCraft+ : Minecraft loot chests in Skyrim's dungeons
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft loot chests in Skyrim's dungeons, caves and camps. Skyrim says where (LootSpots: one
 * beside the boss chest, one partway in, along a wall facing into the room, and the tier from the
 * dungeon's level); each is placed once per Minecraft world, on a little pad of Minecraft ground
 * (Skyrim's floor under it and round it dug out and filled level), with one of five loot tables
 * (skycraft:chests/skyrim_tier_1 .. 5: an early cave's odds and ends up to an End city's riches).
 * At most two middle chests in any one dungeon. Setting: lootChests in config/skycraft.properties
 * (and bLootChests in SkyCraft.ini).
 */
public final class SkyLootChests {
	private SkyLootChests() {
	}

	private static final int MAX_MIDDLES = 2;
	private static boolean enabled = true;
	private static @Nullable Path file;
	private static final Set<String> PLACED = new LinkedHashSet<>();
	private static final Map<String, Integer> MIDDLES = new HashMap<>();
	private static final Map<String, Integer> TRIES = new HashMap<>();
	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();

	public static ResourceKey<LootTable> table(int tier) {
		return ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "chests/skyrim_tier_" + Math.clamp(tier, 1, 5)));
	}

	public static void init() {
		dev.skycraft.SkySettings.onChange(() -> enabled = dev.skycraft.SkySettings.flag("lootChests", true));  // SkyCraft+ : the settings screen
		ServerLifecycleEvents.SERVER_STARTED.register(SkyLootChests::start);
		ServerTickEvents.END_SERVER_TICK.register(SkyLootChests::tick);
	}

	private static void start(MinecraftServer server) {
		java.util.Properties p = new java.util.Properties();
		Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		if (Files.exists(config)) {
			try (java.io.Reader in = Files.newBufferedReader(config)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
			}
		}
		enabled = Boolean.parseBoolean(p.getProperty("lootChests", "true").trim());
		PLACED.clear();
		MIDDLES.clear();
		TRIES.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("skycraft-loot-chests.txt");
		if (Files.exists(file)) {
			try {
				for (String line : Files.readAllLines(file)) {
					String[] f = line.split("\\|");
					if (f.length >= 3) {
						PLACED.add(f[0]);
						if (f[2].equals("middle")) {
							MIDDLES.merge(f[1], 1, Integer::sum);
						}
					}
				}
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}", file, e);
			}
		}
		SkyCraft.LOG.info("SkyCraft: loot chests in Skyrim's dungeons: {} ({} placed in this world)", enabled ? "on" : "off", PLACED.size());
	}

	private static void record(SkyLink.LootSpot spot) {
		PLACED.add(spot.key());
		boolean middle = spot.kind() == Proto.LOOT_MIDDLE;
		if (middle) {
			MIDDLES.merge(spot.location(), 1, Integer::sum);
		}
		if (file == null) {
			return;
		}
		try {
			Files.writeString(file, spot.key() + "|" + spot.location() + "|" + (middle ? "middle" : "end") + System.lineSeparator(),
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", file, e);
		}
	}

	private static void tick(MinecraftServer server) {
		if (!enabled || server.getTickCount() % 20 != 11 || !SkyCollision.active()) {
			return;
		}
		List<SkyLink.LootSpot> spots = SkyLink.readLootSpots();
		if (spots == null || spots.isEmpty() || !SkyLink.readSkyState(SKY)) {
			return;
		}
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
			}
		}
		if (host == null || !(host.level() instanceof ServerLevel level) || SkyDig.minecraftsOwnWorld(level)) {
			return;
		}
		for (SkyLink.LootSpot spot : spots) {
			if (PLACED.contains(spot.key()) || spot.worldId() != SKY.worldId) {
				continue;
			}
			if (spot.kind() == Proto.LOOT_MIDDLE && MIDDLES.getOrDefault(spot.location(), 0) >= MAX_MIDDLES) {
				continue;
			}
			double dx = spot.x() - host.getX(), dz = spot.z() - host.getZ();
			if (dx * dx + dz * dz > 64.0 * 64.0) {
				continue;  // Skyrim's ground there isn't known to Minecraft yet
			}
			BlockPos at = BlockPos.containing(spot.x(), spot.y() + 0.5, spot.z());
			if (!level.hasChunkAt(at) || !SkyCollision.isKnown(at.getX(), at.getY(), at.getZ())) {
				continue;
			}
			BlockPos chest = findPlace(level, at);
			if (chest == null) {
				int tries = TRIES.merge(spot.key(), 1, Integer::sum);
				if (tries == 1 || tries == 30) {
					SkyCraft.LOG.info("SkyCraft: loot chest {}: no room near ({}, {}, {}){}", spot.key(), at.getX(), at.getY(), at.getZ(), tries == 30 ? "; giving up" : "; trying again");
				}
				if (tries >= 30) {
					record(spot);  // never place it, nor keep looking
				}
				continue;
			}
			place(level, SKY.worldId, chest, spot);
			record(spot);
			return;  // one a second
		}
	}

	/** A free cell standing on Skyrim's floor near at (a little search round it), or null. */
	private static @Nullable BlockPos findPlace(ServerLevel level, BlockPos at) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					pos.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
					double floor = SkyCollision.floorUnder(pos);
					if (Double.isNaN(floor) || SkyCollision.blocksPath(pos) || SkyCollision.blocksPath(pos.above()) || !level.getBlockState(pos).isAir()
						|| !level.getBlockState(pos.above()).isAir()) {
						continue;
					}
					double distance = dx * dx + dz * dz + dy * dy * 2.0;
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new BlockPos(pos.getX(), (int) Math.round(floor), pos.getZ());
					}
				}
			}
		}
		return best;
	}

	/**
	 * The chest on a pad of Minecraft ground: under it and round it (where it isn't a wall) Skyrim's
	 * floor is dug out and filled level with the chest's bottom, so it sits on Minecraft blocks.
	 */
	private static void place(ServerLevel level, int world, BlockPos chest, SkyLink.LootSpot spot) {
		int flags = Block.UPDATE_ALL;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		SkyDig.beginBatch();
		try {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int x = chest.getX() + dx, z = chest.getZ() + dz;
					if ((dx != 0 || dz != 0) && SkyCollision.blocksPath(pos.set(x, chest.getY(), z))) {
						continue;  // a wall: left as it is
					}
					int material = SkyDig.classify(x, chest.getY() - 2, z);
					BlockState ground = material == 0 ? Blocks.COBBLESTONE.defaultBlockState() : SkyDig.materialState(material);
					if (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.SAND) || ground.is(Blocks.GRAVEL)) {
						ground = ground.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
					}
					for (int y = chest.getY() - 2; y <= chest.getY(); y++) {
						SkyDig.markDug(level, world, pos.set(x, y, z));
					}
					for (int y = chest.getY() - 2; y < chest.getY(); y++) {
						if (level.getBlockState(pos.set(x, y, z)).isAir()) {
							level.setBlock(pos, y == chest.getY() - 1 ? ground : Blocks.STONE.defaultBlockState(), flags);
						}
					}
				}
			}
		} finally {
			SkyDig.endBatch();
		}
		Direction facing = Direction.fromYRot(spot.yaw());
		level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), flags);
		if (level.getBlockEntity(chest) instanceof RandomizableContainerBlockEntity container) {
			container.setLootTable(table(spot.tier()), level.getRandom().nextLong());
		}
		SkyCraft.LOG.info("SkyCraft: loot chest {} ({}, tier {}) placed at ({}, {}, {}) facing {} in {}", spot.key(), spot.kind() == Proto.LOOT_END ? "end" : "middle", spot.tier(),
			chest.getX(), chest.getY(), chest.getZ(), facing.getName(), spot.location());
	}
}
