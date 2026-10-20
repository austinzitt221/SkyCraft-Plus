// SkyCraft+ : the SkyCraft+ settings screen
package dev.skycraft.client;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What the settings screen needs to know about each setting: which are only taken up later (at
 * Skyrim's next start, or when the world next loads), and which can't be changed where the player
 * is right now (the Nether and the End turned off while in them would leave nowhere to come back
 * from; structures put away while standing in one, the underground while in it, and the like).
 */
final class SkySettingsGuards {
	private SkySettingsGuards() {
	}

	/** SkyCraft.ini settings Skyrim reads only when it starts. */
	private static final Set<String> SKYRIM_LIVE_COLLISION = Set.of("bstepup", "fstepheight", "fstepradius", "fstepreach");

	static boolean skyrimRestart(String section, String key) {
		String s = section.toLowerCase(java.util.Locale.ROOT), k = key.toLowerCase(java.util.Locale.ROOT);
		return switch (s) {
			case "minecraft", "crates", "debug" -> true;
			case "collision" -> !SKYRIM_LIVE_COLLISION.contains(k);
			case "worlds" -> k.equals("igroundregions");
			case "lockon" -> k.equals("blockonmobs");
			default -> false;
		};
	}

	/** skycraft.properties settings taken up only when the world next loads. */
	private static final Set<String> MINECRAFT_LATER = Set.of("deepUnderground", "separateInteriors", "caveRevealRadius", "caveMonsters", "caveMonsterCap",
		"structuresOffMountains", "skyrimArmorSlots");

	static boolean minecraftLater(String key) {
		return MINECRAFT_LATER.contains(key);
	}

	private static boolean off(String value) {
		String v = value.trim();
		return v.equals("0") || v.equalsIgnoreCase("false");
	}

	/** Why this change can't be made here and now (null: it can). */
	static @Nullable String refusal(Minecraft minecraft, @Nullable String section, String key, String value) {
		var player = minecraft.player;
		var level = minecraft.level;
		if (player == null || level == null) {
			return null;
		}
		boolean ownWorld = level.dimension() == Level.NETHER || level.dimension() == Level.END;
		if ("Worlds".equalsIgnoreCase(section) && key.equalsIgnoreCase("bMinecraftWorlds") && off(value) && ownWorld) {
			return "Leave the Nether or the End first: turned off there, there'd be no way back to Skyrim.";
		}
		if (section != null) {
			return null;
		}
		if (!minecraft.hasSingleplayerServer() && !key.equals("starterKit")) {
			return "In a friend's world their settings count: change these in your own.";
		}
		switch (key) {
			case "skyrimStructures" -> {
				String in = off(value) ? dev.skycraft.world.SkyStructures.standingIn(player.blockPosition()) : null;
				if (in != null) {
					return "You're at " + in + ": step away from it first (it would be put away with you in it).";
				}
			}
			case "deepUnderground" -> {
				Boolean under = dev.skycraft.world.SkyGround.underLoosely(player.getX(), player.getEyeY(), player.getZ(), 2.0);
				if (off(value) && Boolean.TRUE.equals(under)) {
					return "Climb out from under Skyrim's land first.";
				}
			}
			case "separateInteriors" -> {
				if (level.dimension() == dev.skycraft.world.SkyInteriors.INTERIORS) {
					return "Step outside first: the interior you're in would change under you.";
				}
			}
			default -> {
			}
		}
		return null;
	}

	/** A Minecraft setting was changed: what isn't taken up through SkySettings' listeners. */
	static void applied(Minecraft minecraft, String key, String value) {
		if (key.equals("destruction")) {
			dev.skycraft.world.SkyDig.destruction = !off(value);
		}
	}
}
