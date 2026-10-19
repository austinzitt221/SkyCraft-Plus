// SkyCraft+ : Skyrim's people fight Minecraft's mobs
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.IntFunction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's mobs are only drawn in Skyrim, so Skyrim's people never fought them: a follower (Lydia)
 * stood by while the player fought zombies, and a bandit a tamed wolf bit never bit back. Here, by
 * their stand-ins: the player's followers go for Minecraft's monsters near them, and anyone a
 * Minecraft mob hurt (not the farm animals) hits back. Within reach a stand-in swings (Skyrim plays
 * the attack facing the mob, kEvMobFight) and the blow lands a moment later, a mob attack by the
 * stand-in, so the mob turns on it (its blows reach Skyrim as a mob's, HIT_BY_MOB); a follower out
 * of reach is sent to it. Setting: skyrimFightsMobs in config/skycraft.properties.
 */
public final class SkyMobFights {
	private SkyMobFights() {
	}

	private static final double FOLLOWER_RANGE = 10.0; // blocks from the follower to a monster it goes for
	private static final double PLAYER_RANGE = 16.0; // and from the player (it doesn't wander off fighting)
	private static final int SWING_TICKS = 30; // a swing every second and a half
	private static final int LAND_TICKS = 6; // the blow lands this long after the swing starts
	private static final int GO_TICKS = 20; // a follower out of reach is sent again this often
	private static final int GRUDGE_TICKS = 200; // a mob that hurt it is fought this long after

	private static final class Fight {
		@Nullable Mob target;
		long nextSwing, landAt = -1L, nextGo;
	}

	private static final Map<Integer, Fight> FIGHTS = new HashMap<>();
	private static @Nullable Boolean enabled;
	private static int logged;

	private static boolean enabled() {
		if (enabled == null) {
			Path config = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
			Properties p = new Properties();
			if (Files.exists(config)) {
				try (Reader in = Files.newBufferedReader(config)) {
					p.load(in);
				} catch (IOException e) {
					SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
				}
			}
			enabled = Boolean.parseBoolean(p.getProperty("skyrimFightsMobs", "true").trim());
			SkyCraft.LOG.info("SkyCraft: Skyrim's people fight Minecraft's mobs: {}", enabled ? "on" : "off");
		}
		return enabled;
	}

	static void tick(ServerLevel level, Collection<SkyrimActorEntity> proxies, IntFunction<SkyLink.@Nullable Actor> actors) {
		if (!enabled()) {
			return;
		}
		ServerPlayer host = null;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (player.level() == level && (host == null || dev.skycraft.net.SkyNet.isHost(player))) {
				host = player;
			}
		}
		long now = level.getGameTime();
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (SkyrimActorEntity proxy : proxies) {
			SkyLink.Actor actor = actors.apply(proxy.formId());
			if (actor == null || actor.dead()) {
				continue;
			}
			Mob target = target(level, proxy, actor, host);
			if (target == null) {
				continue;
			}
			seen.add(proxy.formId());
			Fight fight = FIGHTS.computeIfAbsent(proxy.formId(), id -> new Fight());
			if (fight.target != target) {
				fight.target = target;
				fight.landAt = -1L;
				if (logged++ < 30) {
					SkyCraft.LOG.info("SkyCraft: {} fights a {}", proxy.getName().getString(), target.getType().getDescription().getString());
				}
			}
			double reach = 1.4 + (proxy.getBbWidth() + target.getBbWidth()) / 2.0;
			double dx = target.getX() - proxy.getX(), dz = target.getZ() - proxy.getZ();
			boolean inReach = dx * dx + dz * dz <= reach * reach && Math.abs(target.getY() - proxy.getY()) < 2.5;
			if (fight.landAt >= 0L && now >= fight.landAt) {
				fight.landAt = -1L;
				if (dx * dx + dz * dz <= (reach + 1.0) * (reach + 1.0)) {
					target.hurtServer(level, level.damageSources().mobAttack(proxy), damage(actor));
				}
			}
			if (inReach && now >= fight.nextSwing) {
				fight.nextSwing = now + SWING_TICKS;
				fight.landAt = now + LAND_TICKS;
				SkyLink.pushEvent(Proto.EV_MOB_FIGHT, proxy.formId(), (float) target.getX(), (float) target.getY(), (float) target.getZ(), 0.0F, 1);
			} else if (!inReach && (actor.flags() & Proto.ACTOR_TEAMMATE) != 0 && now >= fight.nextGo) {
				fight.nextGo = now + GO_TICKS;
				SkyLink.pushEvent(Proto.EV_MOB_FIGHT, proxy.formId(), (float) target.getX(), (float) target.getY(), (float) target.getZ(), 0.0F, 0);
			}
		}
		FIGHTS.keySet().retainAll(seen);
	}

	/** Who this actor fights: a mob that hurt it lately, or (a follower) the nearest monster near it and the player. */
	private static @Nullable Mob target(ServerLevel level, SkyrimActorEntity proxy, SkyLink.Actor actor, @Nullable ServerPlayer host) {
		int species = (actor.flags() >> Proto.ACTOR_SPECIES_SHIFT) & 0xFF;
		boolean fightsBack = species == 0 || species == 9 || species == 10 || species == 11; // (people and beasts; dogs, mudcrabs, boars: not the farm's)
		LivingEntity hurtBy = proxy.getLastHurtByMob();
		if (fightsBack && hurtBy instanceof Mob mob && mob.isAlive() && mob.level() == level && proxy.tickCount - proxy.getLastHurtByMobTimestamp() < GRUDGE_TICKS
			&& mob.distanceToSqr(proxy) < FOLLOWER_RANGE * FOLLOWER_RANGE) {
			return mob;
		}
		if ((actor.flags() & Proto.ACTOR_TEAMMATE) == 0 || host == null) {
			return null;
		}
		Mob best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Mob mob : level.getEntitiesOfClass(Mob.class, new AABB(proxy.blockPosition()).inflate(FOLLOWER_RANGE),
			m -> m instanceof Enemy && m.isAlive() && !m.isSpectator())) {
			double d = mob.distanceToSqr(proxy);
			if (d < bestDistance && mob.distanceToSqr(host) < PLAYER_RANGE * PLAYER_RANGE) {
				best = mob;
				bestDistance = d;
			}
		}
		return best;
	}

	/** A Skyrim actor's blow in Minecraft's hearts: by its level, a sword's worth or so at the start. */
	private static float damage(SkyLink.Actor actor) {
		return Math.clamp(3.0F + actor.level() * 0.2F, 3.0F, 14.0F);
	}
}
