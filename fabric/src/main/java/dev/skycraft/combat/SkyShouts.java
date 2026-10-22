// SkyCraft+ : every shout in Minecraft mode
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Skyrim's shouts (and the spells like them) acted out on Minecraft's side, so each does in
 * Minecraft mode what it does in Skyrim. What hits mobs comes with the shot (SkyMagic): Ice Form and
 * paralysis hold a mob still, Disarm makes it drop what it holds, Dismay and fear send it running,
 * Kyne's Peace, Animal Allegiance and Bend Will leave the player be, frenzy sets it on whatever is
 * near, Marked for Death withers it. What's on the player comes with Skyrim's state (selfEffects):
 * Become Ethereal can't be hurt, Slow Time slows the mobs round them, Aura Whisper makes them glow
 * through walls, Dragon Aspect makes the player stronger and tougher, Elemental Fury swing faster,
 * Storm Call brings lightning down on the monsters near. (Unrelenting Force, the breaths, Whirlwind
 * Sprint, Clear Skies and the summoning shouts already work through Skyrim itself.)
 */
public final class SkyShouts {
	private SkyShouts() {
	}

	private enum Mood { CALM, FEAR, FRENZY, STILL }

	private record Held(Mood mood, long until, UUID from) {
	}

	private static final Map<Integer, Held> HELD = new HashMap<>();
	private static final Map<UUID, Integer> SELF = new HashMap<>();
	/** The host's own (its client keeps it: SkyClient). */
	public static volatile int hostSelf;

	/** A shot's other effects on a Minecraft mob it hit (SkyMagic.affect). */
	static void affect(ServerPlayer player, LivingEntity e, int flags, int seconds) {
		long until = player.level().getGameTime() + Math.max(2, seconds) * 20L;
		int ticks = Math.max(2, seconds) * 20;
		if ((flags & Proto.SHOT_PARALYZE) != 0) {
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, Math.min(ticks, 200), 7), player);
			e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, Math.min(ticks, 200), 9), player);
			e.setTicksFrozen(Math.max(e.getTicksFrozen(), 160));
			hold(e, Mood.STILL, player.level().getGameTime() + Math.min(ticks, 200), player);
		}
		if ((flags & Proto.SHOT_DISARM) != 0 && e instanceof Mob mob) {
			ItemStack held = mob.getItemBySlot(EquipmentSlot.MAINHAND);
			if (!held.isEmpty()) {
				mob.spawnAtLocation(player.level(), held.copy());
				mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
			}
		}
		if ((flags & Proto.SHOT_FEAR) != 0) {
			hold(e, Mood.FEAR, until, player);
		}
		if ((flags & Proto.SHOT_CALM) != 0) {
			hold(e, Mood.CALM, until, player);
		}
		if ((flags & Proto.SHOT_FRENZY) != 0) {
			hold(e, Mood.FRENZY, until, player);
		}
		if ((flags & Proto.SHOT_WITHER) != 0) {
			e.addEffect(new MobEffectInstance(MobEffects.WITHER, ticks, 1), player);
			e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1), player);
		}
	}

	private static void hold(LivingEntity e, Mood mood, long until, ServerPlayer from) {
		if (e instanceof Mob) {
			HELD.put(e.getId(), new Held(mood, until, from.getUUID()));
		}
	}

	/** A guest's Skyrim's effects (SkyNet.Act.SELF). */
	public static void guestSelf(ServerPlayer player, int bits) {
		if (bits == 0) {
			SELF.remove(player.getUUID());
		} else {
			SELF.put(player.getUUID(), bits);
		}
	}

	private static int self(ServerPlayer player) {
		return dev.skycraft.net.SkyNet.isHost(player) ? hostSelf : SELF.getOrDefault(player.getUUID(), 0);
	}

	public static void init() {
		// Become Ethereal: nothing of Minecraft's hurts the player meanwhile.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayer player && (self(player) & Proto.SELF_ETHEREAL) != 0
			&& !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)));
		ServerTickEvents.END_SERVER_TICK.register(SkyShouts::tick);
	}

	private static int loggedBits;  // (what was last logged)
	private static boolean loggedAura;

	private static void tick(MinecraftServer server) {
		holdMoods(server);
		if (server.getTickCount() % 20 != 3) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			int bits = self(player);
			if (bits == 0 || !player.isAlive()) {
				continue;
			}
			ServerLevel level = player.level();
			if ((bits & Proto.SELF_SLOW_TIME) != 0) {
				for (LivingEntity e : around(player, 32.0)) {
					e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 3, true, false), player);
				}
			}
			if (bits != loggedBits) {
				loggedBits = bits;
				SkyCraft.LOG.info("SkyCraft: shouts on {} now acted out in Minecraft: bits {}", player.getPlainTextName(), bits);
			}
			if ((bits & Proto.SELF_AURA) != 0) {
				for (LivingEntity e : around(player, 48.0)) {
					e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, true, false), player);
				}
				if (!loggedAura) {
					loggedAura = true;
					SkyCraft.LOG.info("SkyCraft: Aura Whisper: {} Minecraft mob(s) round {} glow", around(player, 48.0).size(), player.getPlainTextName());
				}
			}
			if ((bits & Proto.SELF_ASPECT) != 0) {
				give(player, MobEffects.STRENGTH, 0);
				give(player, MobEffects.RESISTANCE, 0);
			}
			if ((bits & Proto.SELF_FURY) != 0) {
				give(player, MobEffects.HASTE, 1);
			}
			if ((bits & Proto.SELF_STORM) != 0 && server.getTickCount() % 60 == 3) {
				for (LivingEntity e : around(player, 24.0)) {
					if (e instanceof Enemy && e.distanceTo(player) > 4.0F && level.getRandom().nextInt(3) == 0) {
						var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
						if (bolt != null) {
							bolt.snapTo(e.getX(), e.getY(), e.getZ());
							bolt.setCause(player);
							level.addFreshEntity(bolt);
						}
						break;
					}
				}
			}
		}
	}

	private static java.util.List<LivingEntity> around(ServerPlayer player, double range) {
		return player.level().getEntitiesOfClass(LivingEntity.class, new AABB(player.blockPosition()).inflate(range),
			e -> e.isAlive() && !(e instanceof Player) && !(e instanceof SkyrimActorEntity));
	}

	private static void give(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
		MobEffectInstance now = player.getEffect(effect);
		if (now == null || now.getDuration() < 30) {
			player.addEffect(new MobEffectInstance(effect, 50, amplifier, true, false, true));
		}
	}

	/** Each tick: calmed mobs let the player be, frightened ones run from them, frenzied ones turn on the nearest, held ones stay still. */
	private static void holdMoods(MinecraftServer server) {
		if (HELD.isEmpty()) {
			return;
		}
		for (Iterator<Map.Entry<Integer, Held>> it = HELD.entrySet().iterator(); it.hasNext();) {
			var entry = it.next();
			Held held = entry.getValue();
			Mob mob = null;
			for (ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(entry.getKey()) instanceof Mob m) {
					mob = m;
					break;
				}
			}
			if (mob == null || !mob.isAlive() || mob.level().getGameTime() > held.until()) {
				it.remove();
				continue;
			}
			Player from = server.getPlayerList().getPlayer(held.from());
			switch (held.mood()) {
				case CALM, STILL -> {
					if (mob.getTarget() instanceof Player) {
						mob.setTarget(null);
					}
					if (held.mood() == Mood.STILL) {
						mob.getNavigation().stop();
					}
				}
				case FEAR -> {
					mob.setTarget(null);
					if (from != null && mob.getNavigation().isDone()) {
						Vec3 away = mob.position().subtract(from.position()).normalize().scale(12.0);
						mob.getNavigation().moveTo(mob.getX() + away.x, mob.getY(), mob.getZ() + away.z, 1.4);
					}
				}
				case FRENZY -> {
					if (mob.getTarget() == null || mob.getTarget() instanceof Player) {
						Mob self = mob;
						LivingEntity nearest = null;
						for (LivingEntity other : self.level().getEntitiesOfClass(LivingEntity.class, self.getBoundingBox().inflate(12.0),
							o -> o != self && o.isAlive() && !(o instanceof Player) && !(o instanceof SkyrimActorEntity))) {
							if (nearest == null || other.distanceToSqr(self) < nearest.distanceToSqr(self)) {
								nearest = other;
							}
						}
						self.setTarget(nearest);
					}
				}
			}
		}
	}
}
