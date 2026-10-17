// SkyCraft+ : pets through Skyrim's doors
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pets go through Skyrim's doors with the player. A load door moves the player to another place
 * (often another of Skyrim's dimensions), and a pet left behind stayed in the place left: put away
 * with that interior's animals (SkyInteriors), or falling where Skyrim's ground was gone. Now the
 * pets following the player (tamed and not told to sit) and the mobs on the player's lead come
 * along, next to the player; until Skyrim's ground there arrives they're held still (SkyHold), and
 * for a while they're the player's company, not the interior's (SkyInteriors leaves them be).
 * Setting: petsThroughDoors in config/skycraft.properties.
 */
public final class SkyPets {
	private SkyPets() {
	}

	/** On a pet that's come along with its player: kept out of the place left's animals until this server tick. */
	public static final String TRAVELLING_TAG = "skycraft_travelling";
	private static final double RANGE = 32.0;
	private static final int TRAVEL_TICKS = 20 * 15;
	private static boolean enabled = true;
	private static final List<Mob> TRAVELLING = new ArrayList<>();
	private static int travelEnds;

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(SkyPets::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			java.util.Properties p = new java.util.Properties();
			java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
			if (java.nio.file.Files.exists(config)) {
				try (java.io.Reader in = java.nio.file.Files.newBufferedReader(config)) {
					p.load(in);
				} catch (java.io.IOException e) {
					SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
				}
			}
			enabled = Boolean.parseBoolean(p.getProperty("petsThroughDoors", "true").trim());
			TRAVELLING.clear();
		});
	}

	/** The pets and led mobs that go with this player through a door (taken before the player moves). */
	public static List<Mob> company(ServerPlayer player) {
		List<Mob> pets = new ArrayList<>();
		if (!enabled) {
			return pets;
		}
		AABB around = player.getBoundingBox().inflate(RANGE);
		for (Mob mob : player.level().getEntitiesOfClass(Mob.class, around, Mob::isAlive)) {
			if (mob.getVehicle() != null || mob.isVehicle()) {
				continue;
			}
			boolean led = mob.isLeashed() && mob.getLeashHolder() == player;
			boolean follows = mob instanceof TamableAnimal tame && tame.isTame() && tame.getOwner() == player && !tame.isOrderedToSit();
			if (led || follows) {
				pets.add(mob);
			}
		}
		return pets;
	}

	/** After the player moved: the company comes along, next to them. */
	public static void bringAlong(ServerPlayer player, List<Mob> pets) {
		if (pets.isEmpty()) {
			return;
		}
		ServerLevel level = player.level();
		Vec3 at = player.position();
		int moved = 0;
		for (Mob pet : pets) {
			if (!pet.isAlive()) {
				continue;
			}
			pet.addTag(TRAVELLING_TAG);
			pet.resetFallDistance();
			Entity there = pet.teleport(new TeleportTransition(level, at, Vec3.ZERO, pet.getYRot(), pet.getXRot(), TeleportTransition.DO_NOTHING));
			if (there instanceof Mob mob) {
				mob.addTag(TRAVELLING_TAG);
				mob.resetFallDistance();
				mob.getNavigation().stop();
				TRAVELLING.add(mob);
				moved++;
			}
		}
		travelEnds = level.getServer().getTickCount() + TRAVEL_TICKS;
		SkyCraft.LOG.info("SkyCraft: {} pet(s) came along through the door", moved);
	}

	public static boolean travelling(Entity entity) {
		return entity.entityTags().contains(TRAVELLING_TAG);
	}

	private static void tick(MinecraftServer server) {
		if (TRAVELLING.isEmpty() || server.getTickCount() < travelEnds) {
			return;
		}
		for (Mob mob : TRAVELLING) {
			mob.removeTag(TRAVELLING_TAG);
		}
		TRAVELLING.clear();
	}
}
