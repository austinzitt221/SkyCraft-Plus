// SkyCraft+ : beds
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyBeds;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * The player's side of Minecraft's beds in Skyrim (SkyBeds). Sleeping the night through moves
 * Skyrim's clock on to the morning (Skyrim's time is Minecraft's, so Minecraft's own skip was
 * undone). The bed slept in is noted with the Skyrim interior or worldspace it's in (which
 * Minecraft's interiors dimension can't tell from a position: they share it); after a death, once
 * Skyrim has reloaded its save, Skyrim is asked to take its player there.
 */
public final class SkyBedsClient {
	private SkyBedsClient() {
	}

	private static final Path FILE = Path.of("skycraft-beds.properties");
	private static final Properties BEDS = new Properties();  // world key -> "dimension worldId x y z"
	private static boolean loaded;
	private static boolean wasSleeping, sleptLongEnough;
	private static SkyBeds.BedRespawn pending;
	private static long pendingSince;
	private static int pendingSeq;
	private static long seqChangedAt;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SkyBeds.BedRespawn.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> {
			pending = payload;
			pendingSince = System.currentTimeMillis();
			pendingSeq = SkyClient.sky().teleportSeq;
			seqChangedAt = 0;
		}));
	}

	private static String worldKey(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			return "world." + server.getWorldData().getLevelName();
		}
		var entry = minecraft.getCurrentServer();
		return "server." + (entry != null ? entry.ip : "?");
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (Files.isRegularFile(FILE)) {
			try (Reader in = Files.newBufferedReader(FILE)) {
				BEDS.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", FILE, e.toString());
			}
		}
	}

	private static void save() {
		try (Writer out = Files.newBufferedWriter(FILE)) {
			BEDS.store(out, "SkyCraft+ : the Minecraft bed each world's player last slept in, and the Skyrim place it's in");
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't write {}: {}", FILE, e.toString());
		}
	}

	/** Each client tick with a player (SkyClient). */
	static void tick(Minecraft minecraft, LocalPlayer player, SkyLink.SkyState sky) {
		if (!SkyLink.active() || !dev.skycraft.SkySettings.flag("bedsInSkyrim", true)) {
			pending = null;
			return;
		}
		load();
		if (player.isSleeping()) {
			var bed = player.getSleepingPos().orElse(null);
			int dimension = SkyBeds.skyrimDimension(player.level().dimension());
			if (bed != null && dimension >= 0 && sky.inGame() && !sky.loading()) {
				String value = dimension + " " + Integer.toUnsignedString(sky.worldId) + " " + bed.getX() + " " + bed.getY() + " " + bed.getZ();
				String key = worldKey(minecraft);
				if (!value.equals(BEDS.getProperty(key))) {
					BEDS.setProperty(key, value);
					save();
					SkyCraft.LOG.info("SkyCraft: beds: slept in a bed at {} in Skyrim's {}", bed.toShortString(), Integer.toHexString(sky.worldId));
				}
			}
			wasSleeping = true;
			sleptLongEnough |= player.isSleepingLongEnough();
		} else if (wasSleeping) {
			if (sleptLongEnough) {
				SkyLink.pushEvent(Proto.EV_SLEPT, 0, 0.0F, 0.0F, 0.0F, 0.0F, 0);
				SkyCraft.LOG.info("SkyCraft: beds: slept the night through; Skyrim's clock goes on to the morning");
			}
			wasSleeping = false;
			sleptLongEnough = false;
		}
		goToBed(minecraft, player, sky);
	}

	/** After a death at a bed: once Skyrim has reloaded (its teleport changed, and a moment's passed), it goes there. */
	private static void goToBed(Minecraft minecraft, LocalPlayer player, SkyLink.SkyState sky) {
		if (pending == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - pendingSince > 120_000L) {
			SkyCraft.LOG.info("SkyCraft: beds: Skyrim didn't reload after the death; staying put");
			pending = null;
			return;
		}
		if (sky.teleportSeq != pendingSeq) {
			pendingSeq = sky.teleportSeq;
			seqChangedAt = now;
		}
		if (seqChangedAt == 0 || now - seqChangedAt < 2_500L || now - pendingSince < 3_000L || !sky.inGame() || sky.loading() || player.isDeadOrDying()) {
			return;
		}
		SkyBeds.BedRespawn bed = pending;
		pending = null;
		int worldId = 0;
		String[] noted = BEDS.getProperty(worldKey(minecraft), "").split(" ");
		if (noted.length == 5) {
			try {
				BlockPos at = new BlockPos(Integer.parseInt(noted[2]), Integer.parseInt(noted[3]), Integer.parseInt(noted[4]));
				if (Integer.parseInt(noted[0]) == bed.dimension() && at.distToCenterSqr(bed.x(), bed.y(), bed.z()) < 16.0) {
					worldId = Integer.parseUnsignedInt(noted[1]);
				}
			} catch (NumberFormatException e) {
				// (an old or hand-edited line: Skyrim falls back to Tamriel)
			}
		}
		if (worldId == 0 && bed.dimension() != Proto.DIM_TAMRIEL) {
			SkyCraft.LOG.info("SkyCraft: beds: the bed's Skyrim place isn't known (slept in before this update?); staying at the save's place");
			return;
		}
		SkyLink.pushEvent(Proto.EV_GO_TO_BED, worldId, (float) bed.x(), (float) bed.y(), (float) bed.z(), bed.yaw(), bed.dimension());
		SkyCraft.LOG.info("SkyCraft: beds: Skyrim reloaded; its player goes to the bed ({} {} {}, {})", Math.round(bed.x()), Math.round(bed.y()), Math.round(bed.z()),
			Integer.toHexString(worldId));
	}
}
