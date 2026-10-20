// SkyCraft+ : Skyrim magic, arrows and shouts on Minecraft mobs
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Skyrim player's spells, shouts and arrows on Minecraft's mobs. Skyrim lists its projectiles
 * each frame (SkyrimShots in the protocol), each with its whole path so far; every server tick
 * this hits the mobs each one reaches, once per shot: damage as the player's own (drops and
 * experience), fire (a mob that dies burning drops cooked meat, as with Fire Aspect), frost slows,
 * Unrelenting Force and the like throw them back. Skyrim's NPCs aren't touched: Skyrim hits those.
 */
public final class SkyMagic {
	private static final List<SkyLink.Shot> SHOTS = new ArrayList<>();
	/** Shot id -> the mobs it has hit and when (server tick; a stopping shot that hit something holds STOPPED). */
	private static final Map<Long, Map<Integer, Integer>> HIT = new HashMap<>();  // (by whose shot: player entity id << 32 | shot id)
	/** SkyCraft+ : multiplayer. Guests' shots, sent by their clients (SkyNet.Shots), and the tick they came. */
	private static final Map<java.util.UUID, List<SkyLink.Shot>> GUEST_SHOTS = new HashMap<>();
	private static final Map<java.util.UUID, Integer> GUEST_SHOTS_AT = new HashMap<>();
	private static final int REPEAT_TICKS = 10; // sprays and beams: each mob again every half second
	private static final int STOPPED = Integer.MIN_VALUE;
	private static int logged;

	private SkyMagic() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(SkyMagic::tick);
	}

	/** A guest's Skyrim shots now (their client reads its own Skyrim's, as the host's server does here). */
	public static void guestShots(ServerPlayer player, List<SkyLink.Shot> shots) {
		GUEST_SHOTS.put(player.getUUID(), List.copyOf(shots.subList(0, Math.min(shots.size(), 64))));
		GUEST_SHOTS_AT.put(player.getUUID(), player.level().getServer().getTickCount());
	}

	private static long key(ServerPlayer player, SkyLink.Shot shot) {
		return ((long) player.getId() << 32) | (shot.id() & 0xFFFFFFFFL);
	}

	private static void tick(MinecraftServer server) {
		Set<Long> live = new HashSet<>();
		// Guests first: theirs come over the network (gone if none came for half a second).
		int now = server.getTickCount();
		for (ServerPlayer guest : server.getPlayerList().getPlayers()) {
			Integer at = GUEST_SHOTS_AT.get(guest.getUUID());
			if (at == null || now - at > 10 || dev.skycraft.net.SkyNet.isHost(guest)) {
				continue;
			}
			for (SkyLink.Shot shot : GUEST_SHOTS.getOrDefault(guest.getUUID(), List.of())) {
				live.add(key(guest, shot));
				apply(guest, shot);
			}
		}
		GUEST_SHOTS_AT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		GUEST_SHOTS.keySet().retainAll(GUEST_SHOTS_AT.keySet());
		if (!SkyLink.active()) {
			HIT.keySet().retainAll(live);
			return;
		}
		if (!SkyLink.readSkyrimShots(SHOTS)) {
			return;  // Skyrim mid-write: next tick (forgetting the host's hits now, its shots hit again)
		}
		ServerPlayer hostPlayer = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(player)) {
				hostPlayer = player;
				break;
			}
		}
		for (SkyLink.Shot shot : SHOTS) {
			if (hostPlayer != null) {
				live.add(key(hostPlayer, shot));
			}
		}
		HIT.keySet().retainAll(live);
		if (SHOTS.isEmpty()) {
			return;
		}
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
				break;
			}
		}
		if (host == null) {
			return;
		}
		for (SkyLink.Shot shot : SHOTS) {
			apply(host, shot);
		}
	}

	private static void apply(ServerPlayer player, SkyLink.Shot shot) {
		if ((shot.flags() & Proto.SHOT_ARROW) != 0) {
			SkyArrows.skyrimShot(player, shot.id());  // SkyCraft+ : a Minecraft arrow lent to Skyrim's bow used up
		}
		Map<Integer, Integer> hit = HIT.computeIfAbsent(key(player, shot), k -> new HashMap<>());
		int now = player.level().getServer().getTickCount();
		boolean repeats = (shot.flags() & Proto.SHOT_REPEATS) != 0;
		if (hit.containsKey(STOPPED)) {
			return;
		}
		ServerLevel level = player.level();
		Vec3 a = new Vec3(shot.x0(), shot.y0(), shot.z0());
		Vec3 b = new Vec3(shot.x1(), shot.y1(), shot.z1());
		int flags = shot.flags();
		double reach = (flags & Proto.SHOT_CONE) != 0 ? 0.0 : shot.radius();
		AABB area = new AABB(a, b).inflate((flags & (Proto.SHOT_CONE | Proto.SHOT_EXPLOSION)) != 0 ? Math.max(shot.radius(), a.distanceTo(b)) + 1.0 : reach + 1.0);
		hitCrystals(level, player, shot, a, b, area, reach, hit, now, repeats);
		List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, area,
			e -> e.isAlive() && !(e instanceof SkyrimActorEntity) && !(e instanceof Player) && !recentlyHit(hit, e.getId(), now, repeats));
		if (candidates.isEmpty()) {
			return;
		}
		List<LivingEntity> targets = new ArrayList<>();
		if ((flags & Proto.SHOT_EXPLOSION) != 0) {
			for (LivingEntity e : candidates) {
				if (e.getBoundingBox().getCenter().distanceTo(b) <= shot.radius() + e.getBbWidth() * 0.5) {
					targets.add(e);
				}
			}
		} else if ((flags & Proto.SHOT_CONE) != 0) {
			Vec3 axis = b.subtract(a);
			double length = axis.length();
			if (length < 0.1) {
				return;
			}
			axis = axis.scale(1.0 / length);
			for (LivingEntity e : candidates) {
				Vec3 to = e.getBoundingBox().getCenter().subtract(a);
				double distance = to.length();
				if (distance <= length + e.getBbWidth() && distance > 0.01 && Math.acos(Math.max(-1.0, Math.min(1.0, to.dot(axis) / distance))) <= shot.radius()) {
					targets.add(e);
				}
			}
		} else {
			// A path or a beam: what its swept width passes through, nearest first.
			LivingEntity nearest = null;
			double best = Double.MAX_VALUE;
			for (LivingEntity e : candidates) {
				AABB box = e.getBoundingBox().inflate(reach);
				var at = box.contains(a) ? java.util.Optional.of(a) : box.clip(a, b);
				if (at.isEmpty()) {
					continue;
				}
				double d = at.get().distanceTo(a);
				if ((flags & (Proto.SHOT_STOPS | Proto.SHOT_BEAM)) != 0) {
					if (d < best) {
						best = d;
						nearest = e;
					}
				} else {
					targets.add(e);
				}
			}
			if (nearest != null) {
				targets.add(nearest);
				if ((flags & Proto.SHOT_STOPS) != 0) {
					hit.put(STOPPED, now);
				}
			}
		}
		for (LivingEntity e : targets) {
			hit.put(e.getId(), now);
			affect(level, player, e, shot, a);
		}
	}

	/**
	 * SkyCraft+ : the End. Its crystals aren't living, but Skyrim's fire, frost, shock and shouts
	 * break them as Minecraft's arrows and explosions do: whatever the shot passes through or reaches.
	 */
	private static void hitCrystals(ServerLevel level, ServerPlayer player, SkyLink.Shot shot, Vec3 a, Vec3 b, AABB area, double reach, Map<Integer, Integer> hit,
		int now, boolean repeats) {
		int flags = shot.flags();
		for (var crystal : level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, area,
			e -> e.isAlive() && !recentlyHit(hit, e.getId(), now, repeats))) {
			Vec3 centre = crystal.getBoundingBox().getCenter();
			boolean reached;
			if ((flags & Proto.SHOT_EXPLOSION) != 0) {
				reached = centre.distanceTo(b) <= shot.radius() + 1.0;
			} else if ((flags & Proto.SHOT_CONE) != 0) {
				Vec3 axis = b.subtract(a), to = centre.subtract(a);
				double length = axis.length(), distance = to.length();
				reached = length > 0.1 && distance > 0.01 && distance <= length + 1.0
					&& Math.acos(Math.max(-1.0, Math.min(1.0, to.dot(axis) / (distance * length)))) <= shot.radius();
			} else {
				AABB box = crystal.getBoundingBox().inflate(reach);
				reached = box.contains(a) || box.clip(a, b).isPresent();
			}
			if (reached) {
				hit.put(crystal.getId(), now);
				crystal.hurtServer(level, player.damageSources().indirectMagic(player, player), Math.max(1.0F, shot.damage()));
			}
		}
	}

	/** Hit by this shot already (a lasting spray or beam: within the last half second). */
	private static boolean recentlyHit(Map<Integer, Integer> hit, int entity, int now, boolean repeats) {
		Integer at = hit.get(entity);
		return at != null && (!repeats || now - at < REPEAT_TICKS);
	}

	private static void affect(ServerLevel level, ServerPlayer player, LivingEntity e, SkyLink.Shot shot, Vec3 from) {
		int flags = shot.flags();
		if ((flags & Proto.SHOT_FIRE) != 0) {
			e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 100));
		}
		if ((flags & Proto.SHOT_FROST) != 0) {
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1), player);
		}
		if (shot.damage() > 0.0F) {
			e.hurtServer(level, player.damageSources().indirectMagic(player, player), shot.damage());
		}
		if ((flags & Proto.SHOT_ARROW) != 0) {
			SkyArrows.skyrimArrowHit(player, e);  // SkyCraft+ : a tipped or spectral Minecraft arrow's effect
		}
		if ((flags & (Proto.SHOT_PARALYZE | Proto.SHOT_DISARM | Proto.SHOT_FEAR | Proto.SHOT_CALM | Proto.SHOT_FRENZY | Proto.SHOT_WITHER)) != 0) {
			SkyShouts.affect(player, e, flags, shot.seconds());  // SkyCraft+ : every shout in Minecraft mode
		}
		if ((flags & Proto.SHOT_PUSH) != 0 && shot.push() > 0.0F) {
			Vec3 away = e.position().subtract(from);
			double h = Math.sqrt(away.x * away.x + away.z * away.z);
			if (h > 1.0e-3) {
				e.knockback(shot.push(), -away.x / h, -away.z / h, player.damageSources().playerAttack(player), shot.damage());
				e.setDeltaMovement(e.getDeltaMovement().add(0.0, 0.25 * shot.push(), 0.0));
			}
		}
		if (logged < 10) {
			logged++;
			SkyCraft.LOG.info("SkyCraft: a Skyrim {} hit a {} (damage {}{}{}{})", describe(flags), e.getType().toShortString(), shot.damage(),
				(flags & Proto.SHOT_FIRE) != 0 ? ", fire" : "", (flags & Proto.SHOT_FROST) != 0 ? ", frost" : "",
				(flags & Proto.SHOT_PUSH) != 0 ? ", push " + shot.push() : "");
		}
	}

	private static String describe(int flags) {
		if ((flags & Proto.SHOT_EXPLOSION) != 0) {
			return "explosion";
		}
		if ((flags & Proto.SHOT_CONE) != 0) {
			return "cone (shout)";
		}
		if ((flags & Proto.SHOT_BEAM) != 0) {
			return "beam";
		}
		if ((flags & Proto.SHOT_SPRAY) != 0) {
			return "spray";
		}
		return (flags & Proto.SHOT_STOPS) != 0 ? "bolt or arrow" : "stream";
	}
}
