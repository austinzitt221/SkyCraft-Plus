// SkyCraft+ : Whirlwind Sprint
package dev.skycraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Whirlwind Sprint's dash. Skyrim's would be Skyrim moving the player, which Minecraft (driving the
 * player) undid, so Skyrim shouts it and Minecraft dashes: so many blocks the way the player looks,
 * level, over a few ticks (Proto.IN_DASH). Walls stop it as they stop the player.
 */
public final class SkyDash {
	private SkyDash() {
	}

	private static volatile double perTick;
	private static volatile int ticksLeft;
	private static int logged;

	/** From Skyrim (InputBridge): dash this many blocks over this many ticks. */
	public static void start(double blocks, int ticks) {
		ticks = Math.max(1, ticks);
		perTick = blocks / ticks;
		ticksLeft = ticks;
		if (logged++ < 20) {
			dev.skycraft.SkyCraft.LOG.info("SkyCraft: Whirlwind Sprint: dashing {} blocks", blocks);
		}
	}

	/** Each client tick (the movement takes effect the next). */
	public static void tick(Minecraft minecraft) {
		var player = minecraft.player;
		if (ticksLeft <= 0) {
			return;
		}
		if (player == null || !player.isAlive() || player.isPassenger()) {
			ticksLeft = 0;
			return;
		}
		ticksLeft--;
		Vec3 look = player.getLookAngle();
		double flat = Math.sqrt(look.x * look.x + look.z * look.z);
		if (flat < 1.0E-4) {
			ticksLeft = 0;
			return;
		}
		Vec3 now = player.getDeltaMovement();
		player.setDeltaMovement(look.x / flat * perTick, Math.min(now.y, 0.0), look.z / flat * perTick);
		player.resetFallDistance();
	}
}
