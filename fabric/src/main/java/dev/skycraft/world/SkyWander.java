// SkyCraft+ : mobs wandering on Skyrim's ground
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.jspecify.annotations.Nullable;

/**
 * Mobs wandering about on Skyrim's ground kept climbing: up a town's stairs, onto its highest
 * terrace, and piled up there for good (seen with villagers and cats). Mainly: every path's
 * destination was sent up to the sky before planning (GroundPathNavigationMixin), so every mob
 * walked to the highest point it could reach. Also: Minecraft picks a wander
 * spot at random and, if the spot is inside something solid, moves it straight up until it's
 * clear. Inside a Skyrim wall, cliff or building that's its top, so mobs kept aiming at rooftops
 * and wall tops and walked as close as they could get: the highest point around, which they then
 * never left. Now a spot that would have to climb more than two blocks out of Skyrim's geometry
 * is thrown away (another is picked), and a wander goal doesn't set off for a spot it can't reach.
 * Setting: mobWanderFix in config/skycraft.properties.
 *
 * mobPathLog (off unless asked for) writes what wandering mobs pick and how their paths turn out to
 * latest.log ("wander:" lines), and a summary every 30 seconds.
 */
public final class SkyWander {
	private SkyWander() {
	}

	private static boolean loaded, fix = true, log = true;
	private static int logged;
	private static final int LOG_LINES = 400;

	// Counted since the last summary.
	private static int candidates, climbRejected, strolls, unreachableSkipped, goingUp, goingDown, level, brainPaths, brainPartial;
	private static final Map<Mob, double[]> FIRST_SEEN = new WeakHashMap<>();

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		java.util.Properties p = new java.util.Properties();
		java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		if (java.nio.file.Files.exists(config)) {
			try (java.io.Reader in = java.nio.file.Files.newBufferedReader(config)) {
				p.load(in);
			} catch (java.io.IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
			}
		}
		fix = Boolean.parseBoolean(p.getProperty("mobWanderFix", "true").trim());
		log = Boolean.parseBoolean(p.getProperty("mobPathLog", "false").trim());
		SkyCraft.LOG.info("SkyCraft: wandering mobs: fix {}, path log {}", fix ? "on" : "off", log ? "on" : "off");
	}

	/** Destinations onto Skyrim's ground, not the sky (GroundPathNavigationMixin). */
	public static boolean surfaceFix() {
		return fix();
	}

	public static boolean fix() {
		load();
		return fix && SkyCollision.active();
	}

	private static boolean logging() {
		load();
		return log && SkyCollision.active();
	}

	private static void line(String format, Object... args) {
		if (logged < LOG_LINES) {
			logged++;
			SkyCraft.LOG.info("SkyCraft: wander: " + format, args);
		}
	}

	private static String name(Mob mob) {
		return mob.getType().builtInRegistryHolder().key().identifier().getPath() + "#" + mob.getId();
	}

	/** A wander spot that had to climb this far out of Skyrim's geometry (LandRandomPosMixin); true = throw it away. */
	public static boolean tooHigh(Mob mob, BlockPos raw, BlockPos moved) {
		int climb = moved.getY() - raw.getY();
		if (logging()) {
			candidates++;
		}
		if (climb <= 2) {
			return false;
		}
		if (logging()) {
			climbRejected++;
			if (climbRejected <= 30) {
				line("{} at y {}: spot ({}, {}, {}) is inside Skyrim geometry; climbing out puts it {} blocks up at y {}{}", name(mob), String.format("%.1f", mob.getY()),
					raw.getX(), raw.getY(), raw.getZ(), climb, moved.getY(), fix ? ": thrown away" : " (kept: fix off)");
			}
		}
		return fix();
	}

	/** A wander goal's spot and the path to it (RandomStrollGoalMixin); true = don't go (it can't be reached). */
	public static boolean skipStroll(Mob mob, double x, double y, double z, @Nullable Path path) {
		// Reached, or ending within a couple of blocks of it (Skyrim's floors aren't whole blocks).
		boolean reach = path != null && (path.canReach() || path.getDistToTarget() <= 2.0F);
		if (logging()) {
			strolls++;
			track(mob);
			double dy = y - mob.getY();
			if (dy > 1.5) {
				goingUp++;
			} else if (dy < -1.5) {
				goingDown++;
			} else {
				level++;
			}
			Node end = path == null ? null : path.getEndNode();
			line("{} at ({}, {}, {}) strolls to ({}, {}, {}), {} {}, planned to y {}: {}", name(mob), Math.round(mob.getX()), String.format("%.1f", mob.getY()), Math.round(mob.getZ()),
				Math.round(x), String.format("%.1f", y), Math.round(z), dy >= 0 ? "up" : "down", String.format("%.1f", Math.abs(dy)),
				path == null ? "-" : path.getTarget().getY(),
				path == null ? "no path" : reach ? "reachable (" + path.getNodeCount() + " steps)"
					: "can't reach, path ends at y " + (end == null ? "?" : end.y) + " " + String.format("%.1f", path.getDistToTarget()) + " blocks short");
		}
		if (!reach && fix()) {
			if (logging()) {
				unreachableSkipped++;
			}
			return true;
		}
		return false;
	}

	/** A brain's walk target (villagers, goats...) and its path (MoveToTargetSinkMixin): logged only. */
	public static void brainPath(Mob mob, BlockPos target, @Nullable Path path) {
		if (!logging()) {
			return;
		}
		track(mob);
		brainPaths++;
		boolean reach = path != null && (path.canReach() || path.getDistToTarget() <= 2.0F);
		if (!reach) {
			brainPartial++;
		}
		Node end = path == null ? null : path.getEndNode();
		line("{} at ({}, {}, {}) walks to ({}, {}, {}) [{}], planned to y {}: {}", name(mob), Math.round(mob.getX()), String.format("%.1f", mob.getY()), Math.round(mob.getZ()), target.getX(),
			target.getY(), target.getZ(), mob.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("?"),
			path == null ? "-" : path.getTarget().getY(),
			path == null ? "no path" : reach ? "reachable (" + path.getNodeCount() + " steps)"
				: "can't reach, path ends at y " + (end == null ? "?" : end.y) + " " + String.format("%.1f", path.getDistToTarget()) + " blocks short");
	}

	private static void track(Mob mob) {
		FIRST_SEEN.computeIfAbsent(mob, m -> new double[] { m.getY(), System.nanoTime() });
	}

	/** Every 30 seconds: what wandering looked like, and how far the mobs seen have risen since first seen. */
	private static void summary(MinecraftServer server) {
		if (server.getTickCount() % 600 != 0 || !logging() || FIRST_SEEN.isEmpty() && strolls == 0 && brainPaths == 0) {
			return;
		}
		int mobs = 0, rose = 0, fell = 0;
		double total = 0.0;
		for (Map.Entry<Mob, double[]> entry : FIRST_SEEN.entrySet()) {
			Mob mob = entry.getKey();
			if (mob.isRemoved()) {
				continue;
			}
			double dy = mob.getY() - entry.getValue()[0];
			mobs++;
			total += dy;
			if (dy > 4.0) {
				rose++;
			} else if (dy < -4.0) {
				fell++;
			}
		}
		SkyCraft.LOG.info("SkyCraft: wander summary: {} mobs tracked, average height change {} blocks ({} rose more than 4, {} fell more than 4); goal strolls {} (up {}, down {}, level {}), "
			+ "{} unreachable skipped; spots looked at {}, {} thrown away for climbing walls; brain walks {} ({} partial)", mobs,
			String.format("%+.1f", mobs == 0 ? 0.0 : total / mobs), rose, fell, strolls, goingUp, goingDown, level, unreachableSkipped, candidates, climbRejected, brainPaths,
			brainPartial);
		candidates = climbRejected = strolls = unreachableSkipped = goingUp = goingDown = level = brainPaths = brainPartial = 0;
	}

	public static void init() {
		dev.skycraft.SkySettings.onChange(() -> loaded = false);  // SkyCraft+ : the settings screen (read again)
		ServerTickEvents.END_SERVER_TICK.register(SkyWander::summary);
	}
}
