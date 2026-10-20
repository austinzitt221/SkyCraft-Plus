// SkyCraft+ : mobs held where Skyrim's ground is unknown
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's ground (SkyCollision) is one set, around the host's Skyrim player, cleared whenever it
 * changes place. Mobs elsewhere in Skyrim's worlds had nothing under them: going into a house in
 * Whiterun, the villagers left in the street fell out of the world, and an animal brought back
 * into a house the moment the player came in fell through its floor before Skyrim had sent it.
 * A mob over Skyrim's ground that isn't known (or is another place's: the host is in another of
 * Skyrim's dimensions) is held still, as Minecraft holds mobs in chunks that aren't loaded, until
 * it is. One on Minecraft's own blocks isn't held. Setting: holdMobsWithoutGround in
 * config/skycraft.properties.
 */
public final class SkyHold {
	private static boolean enabled = true;
	private static @Nullable ResourceKey<Level> hostDimension;

	private SkyHold() {
	}

	public static void init() {
		dev.skycraft.SkySettings.onChange(() -> enabled = dev.skycraft.SkySettings.flag("holdMobsWithoutGround", true));  // SkyCraft+ : the settings screen
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Properties p = new Properties();
			Path config = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
			if (Files.exists(config)) {
				try (Reader in = Files.newBufferedReader(config)) {
					p.load(in);
				} catch (IOException e) {
					SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
				}
			}
			enabled = Boolean.parseBoolean(p.getProperty("holdMobsWithoutGround", "true").trim());
			hostDimension = null;
		});
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			ResourceKey<Level> host = null;
			for (var player : server.getPlayerList().getPlayers()) {
				if (dev.skycraft.net.SkyNet.isHost(player)) {
					host = player.level().dimension();
				}
			}
			hostDimension = host;
		});
	}

	/** True if this mob should stay where it is this tick (LivingEntityHoldMixin). */
	public static boolean hold(LivingEntity entity) {
		// (Not active(): right after a change of place Skyrim's ground is empty, just when it's needed.)
		if (!enabled || entity instanceof Player || !SkyCollision.linked()) {
			return false;
		}
		Level level = entity.level();
		if (level.isClientSide() || !SkyMinecraftWorlds.skyrimsWorld(level.dimension())) {
			return false;
		}
		BlockPos feet = entity.blockPosition();
		// On (or just over) Minecraft's own blocks: those hold it up anyway.
		for (int dy = 0; dy <= 2; dy++) {
			BlockPos q = feet.below(dy);
			if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
				return false;
			}
		}
		if (level.dimension() == hostDimension && SkyCollision.isKnown(feet.getX(), feet.getY(), feet.getZ())
			&& SkyCollision.isKnown(feet.getX(), feet.getY() - 8, feet.getZ())) {
			return false;
		}
		return true;
	}
}
