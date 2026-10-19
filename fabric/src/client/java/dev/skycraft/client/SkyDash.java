// SkyCraft+ : Whirlwind Sprint
package dev.skycraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Whirlwind Sprint's dash. Skyrim's would be Skyrim moving the player, which Minecraft (driving the
 * player) undid, so Skyrim shouts it and Minecraft dashes: the way the player looks, level, as far
 * and as fast as Skyrim's own (its four dash animations' root motion, from Skyrim's animation data:
 * 500, 1000, 1500 and 2000 units in 0.567 s; a word more each, one more sprinting, as Skyrim's
 * shout behavior picks them). Walls stop it as they stop the player.
 */
public final class SkyDash {
	private SkyDash() {
	}

	private static final double UNITS_PER_BLOCK = 70.0;
	/** Each dash's root motion: time (s), distance forward (Skyrim units). WhirlwindSprint_Short, _Medium, _Long, _Longest. */
	private static final double[][][] CURVES = {
		{ { 0.0333333, 0 }, { 0.0666667, 13.8261 }, { 0.1, 51.2034 }, { 0.133333, 105.98 }, { 0.166667, 172.005 }, { 0.233333, 313.19 }, { 0.266667, 376.048 },
			{ 0.3, 425.546 }, { 0.333333, 455.534 }, { 0.366667, 470.682 }, { 0.433333, 486.81 }, { 0.566667, 500 } },
		{ { 0.0333333, 0 }, { 0.0666667, 35.59 }, { 0.1, 77.19 }, { 0.2, 235.973 }, { 0.333333, 455.534 }, { 0.566667, 999.998 } },
		{ { 0.0333333, 0 }, { 0.0666667, 32.5281 }, { 0.1, 77.19 }, { 0.133333, 145.393 }, { 0.166667, 223.485 }, { 0.2, 310.28 }, { 0.233333, 404.591 },
			{ 0.266667, 505.231 }, { 0.333333, 720.752 }, { 0.5, 1287.25 }, { 0.533333, 1395.8 }, { 0.566667, 1500 } },
		{ { 0.0333333, 0 }, { 0.0666667, 32.7385 }, { 0.1, 77.19 }, { 0.133333, 149.587 }, { 0.166667, 241.199 }, { 0.2, 349.72 }, { 0.233333, 472.845 },
			{ 0.266667, 608.267 }, { 0.3, 753.68 }, { 0.333333, 906.78 }, { 0.4, 1226.81 }, { 0.466667, 1549.92 }, { 0.5, 1706.86 }, { 0.533333, 1857.66 },
			{ 0.566667, 2000 } } };

	private static volatile double[] curve;
	private static volatile int tick = -1;
	private static int logged;

	/** From Skyrim (InputBridge): words shouted (1-3). */
	public static void start(int words) {
		var player = Minecraft.getInstance().player;
		int clip = Math.clamp(words - 1 + (player != null && player.isSprinting() ? 1 : 0), 0, CURVES.length - 1);
		double[][] c = CURVES[clip];
		double[] at = new double[c.length * 2];
		for (int i = 0; i < c.length; i++) {
			at[2 * i] = c[i][0];
			at[2 * i + 1] = c[i][1];
		}
		curve = at;
		tick = 0;
		if (logged++ < 20) {
			dev.skycraft.SkyCraft.LOG.info("SkyCraft: Whirlwind Sprint: {} word(s){}: Skyrim's {} dash, {} blocks", words, player != null && player.isSprinting() ? " sprinting" : "",
				new String[] { "short", "medium", "long", "longest" }[clip], Math.round(c[c.length - 1][1] / UNITS_PER_BLOCK * 10.0) / 10.0);
		}
	}

	/** Forward distance (Skyrim units) at a time into the dash. */
	private static double along(double[] c, double t) {
		if (t <= c[0]) {
			return 0.0;
		}
		for (int i = 2; i < c.length; i += 2) {
			if (t <= c[i]) {
				double f = (t - c[i - 2]) / (c[i] - c[i - 2]);
				return c[i - 1] + (c[i + 1] - c[i - 1]) * f;
			}
		}
		return c[c.length - 1];
	}

	/** Each client tick (the movement takes effect the next): this tick's share of the dash. */
	public static void tick(Minecraft minecraft) {
		var player = minecraft.player;
		double[] c = curve;
		if (tick < 0 || c == null) {
			return;
		}
		double from = tick * 0.05, to = (tick + 1) * 0.05;
		if (player == null || !player.isAlive() || player.isPassenger() || from >= c[c.length - 2]) {
			tick = -1;
			return;
		}
		tick++;
		double step = (along(c, to) - along(c, from)) / UNITS_PER_BLOCK;
		Vec3 look = player.getLookAngle();
		double flat = Math.sqrt(look.x * look.x + look.z * look.z);
		if (flat < 1.0E-4) {
			tick = -1;
			return;
		}
		Vec3 now = player.getDeltaMovement();
		player.setDeltaMovement(look.x / flat * step, Math.min(now.y, 0.0), look.z / flat * step);
		player.resetFallDistance();
	}
}
