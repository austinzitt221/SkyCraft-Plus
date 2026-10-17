// SkyCraft+ : Minecraft breeding for Skyrim's animals
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's animals breed the Minecraft way. Fed the food Minecraft's kind of them eats (seeds for a
 * chicken, wheat for a cow or a goat...), a Skyrim animal is in love for a while, with Minecraft's
 * hearts; two in love of the same kind come together (Skyrim walks one to the other) and a baby
 * appears: the same Skyrim animal at half size, growing up over twenty minutes (Skyrim's side). A
 * Skyrim animal in love meets a Minecraft one of its kind too (a Skyrim chicken and a Minecraft
 * chicken): the Minecraft one walks over, and the baby is either game's, at random.
 */
public final class SkyBreeding {
	private SkyBreeding() {
	}

	// Skyrim's kinds of animal (ActorRecord flags bits 8-15, Animals.cpp's Species).
	public static final int CHICKEN = 1, COW = 2, GOAT = 3, DEER = 4, ELK = 5, HARE = 6, HORSE = 7, FOX = 8, DOG = 9, MUDCRAB = 10, BOAR = 11;
	private static final int LOVE_TICKS = 600, COOLDOWN_TICKS = 6000, TOGETHER_TICKS = 50;
	private static final double FIND_RANGE = 8.0, TOUCH_RANGE = 2.5;

	private static final Map<Integer, Integer> LOVE = new HashMap<>();       // proxy formId -> ticks left
	private static final Map<Integer, Integer> COOLDOWN = new HashMap<>();   // proxy formId -> ticks left
	private static final Map<Integer, Integer> TOGETHER = new HashMap<>();   // proxy formId -> ticks close to its partner
	private static final Map<Integer, Integer> APPROACHING = new HashMap<>(); // proxy formId -> the proxy it walks to

	public static int species(int actorFlags) {
		return (actorFlags >> Proto.ACTOR_SPECIES_SHIFT) & 0xFF;
	}

	/** Whether Minecraft's kind of this animal eats this (what it breeds on). */
	static boolean food(int species, ItemStack stack) {
		return switch (species) {
			case CHICKEN -> stack.is(ItemTags.CHICKEN_FOOD);
			case COW, GOAT, DEER, ELK -> stack.is(Items.WHEAT);
			case HARE -> stack.is(Items.CARROT) || stack.is(Items.GOLDEN_CARROT) || stack.is(Items.DANDELION);
			case HORSE -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.GOLDEN_CARROT);
			case FOX -> stack.is(Items.SWEET_BERRIES) || stack.is(Items.GLOW_BERRIES);
			case DOG -> stack.is(ItemTags.MEAT);
			case MUDCRAB -> stack.is(ItemTags.FISHES);
			case BOAR -> stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT);
			default -> false;
		};
	}

	/** Minecraft's animal of the same kind, for breeding across the games; null: Minecraft has none. */
	static @Nullable EntityType<? extends Animal> minecraftKind(int species) {
		return switch (species) {
			case CHICKEN -> EntityTypes.CHICKEN;
			case COW -> EntityTypes.COW;
			case GOAT -> EntityTypes.GOAT;
			case HARE -> EntityTypes.RABBIT;
			case HORSE -> EntityTypes.HORSE;
			case FOX -> EntityTypes.FOX;
			case DOG -> EntityTypes.WOLF;
			case BOAR -> EntityTypes.PIG;
			default -> null;
		};
	}

	/** A player used something on a Skyrim animal: its food puts it in love. Null: not breeding's to answer. */
	static @Nullable InteractionResult feed(SkyrimActorEntity proxy, ServerPlayer player, ItemStack stack, SkyLink.@Nullable Actor actor) {
		if (actor == null || (actor.flags() & (Proto.ACTOR_BABY | Proto.ACTOR_HOSTILE | Proto.ACTOR_IN_COMBAT)) != 0) {
			return null;
		}
		int species = species(actor.flags());
		if (species == 0 || !food(species, stack) || LOVE.containsKey(proxy.formId()) || COOLDOWN.containsKey(proxy.formId())) {
			return null;
		}
		if (!player.hasInfiniteMaterials()) {
			stack.shrink(1);
		}
		LOVE.put(proxy.formId(), LOVE_TICKS);
		hearts(player.level(), proxy, 7);
		player.level().playSound(null, proxy.getX(), proxy.getY(), proxy.getZ(), SoundEvents.GENERIC_EAT.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
		SkyCraft.LOG.info("SkyCraft: {} is in love", proxy.getName().getString());
		return InteractionResult.SUCCESS_SERVER;
	}

	static void hearts(ServerLevel level, net.minecraft.world.entity.Entity at, int count) {
		level.sendParticles(ParticleTypes.HEART, at.getX(), at.getY() + at.getBbHeight() + 0.3, at.getZ(), count, 0.4, 0.3, 0.4, 0.0);
	}

	/** Each server tick: the ones in love find their partner, come together, and a baby comes. */
	static void tick(ServerLevel level, Collection<SkyrimActorEntity> proxies, java.util.function.IntFunction<SkyLink.@Nullable Actor> actors) {
		COOLDOWN.replaceAll((id, t) -> t - 1);
		COOLDOWN.values().removeIf(t -> t <= 0);
		if (LOVE.isEmpty()) {
			return;
		}
		Map<Integer, SkyrimActorEntity> byId = new HashMap<>();
		for (SkyrimActorEntity p : proxies) {
			byId.put(p.formId(), p);
		}
		for (Integer id : LOVE.keySet().toArray(Integer[]::new)) {
			SkyrimActorEntity proxy = byId.get(id);
			SkyLink.Actor actor = actors.apply(id);
			int left = LOVE.getOrDefault(id, 0) - 1;
			if (proxy == null || actor == null || left <= 0) {
				endLove(id);
				continue;
			}
			LOVE.put(id, left);
			if (left % 20 == 0) {
				hearts(level, proxy, 1);
			}
			int species = species(actor.flags());
			// A Skyrim partner of the same kind, in love too.
			SkyrimActorEntity partner = null;
			for (SkyrimActorEntity other : proxies) {
				SkyLink.Actor o = other == proxy ? null : actors.apply(other.formId());
				if (o != null && LOVE.containsKey(other.formId()) && species(o.flags()) == species && other.distanceTo(proxy) < FIND_RANGE) {
					partner = other;
					break;
				}
			}
			// Or a Minecraft one of its kind.
			Animal minecraft = null;
			EntityType<? extends Animal> kind = minecraftKind(species);
			if (partner == null && kind != null) {
				for (Animal animal : level.getEntitiesOfClass(Animal.class, proxy.getBoundingBox().inflate(FIND_RANGE), a -> a.isInLove() && a.getType() == kind)) {
					minecraft = animal;
					break;
				}
			}
			if (partner == null && minecraft == null) {
				continue;
			}
			double distance = partner != null ? partner.distanceTo(proxy) : minecraft.distanceTo(proxy);
			if (partner != null && id < partner.formId() && APPROACHING.get(id) == null) {
				APPROACHING.put(id, partner.formId());
				SkyLink.pushEvent(Proto.EV_APPROACH, id, 0.0F, 0.0F, 0.0F, 0.0F, partner.formId());  // Skyrim walks this one to it
			}
			if (minecraft != null) {
				minecraft.getNavigation().moveTo(proxy, 1.0);
			}
			if (distance > TOUCH_RANGE) {
				TOGETHER.remove(id);
				continue;
			}
			int together = TOGETHER.merge(id, 1, Integer::sum);
			if (together < TOGETHER_TICKS) {
				continue;
			}
			// A baby: Skyrim's (between two of Skyrim's, or half the time with a Minecraft parent) or Minecraft's.
			boolean skyrimBaby = minecraft == null || level.getRandom().nextBoolean();
			if (skyrimBaby) {
				SkyLink.pushEvent(Proto.EV_BREED, id, 0.0F, 0.0F, 0.0F, 0.0F, 0);
			} else {
				Animal baby = kind.create(level, EntitySpawnReason.BREEDING);
				if (baby != null) {
					baby.setAge(-24000);
					baby.snapTo(minecraft.getX(), minecraft.getY(), minecraft.getZ(), 0.0F, 0.0F);
					level.addFreshEntityWithPassengers(baby);
				}
			}
			hearts(level, proxy, 7);
			ExperienceOrb.award(level, proxy.position(), level.getRandom().nextInt(7) + 1);
			SkyCraft.LOG.info("SkyCraft: {} had a {} baby with {}", proxy.getName().getString(), skyrimBaby ? "Skyrim" : "Minecraft",
				partner != null ? partner.getName().getString() : "a Minecraft " + kind.getDescription().getString());
			endLove(id);
			COOLDOWN.put(id, COOLDOWN_TICKS);
			if (partner != null) {
				endLove(partner.formId());
				COOLDOWN.put(partner.formId(), COOLDOWN_TICKS);
			}
			if (minecraft != null) {
				minecraft.resetLove();
				minecraft.setAge(6000);
			}
		}
	}

	private static void endLove(int id) {
		LOVE.remove(id);
		TOGETHER.remove(id);
		if (APPROACHING.remove(id) != null) {
			SkyLink.pushEvent(Proto.EV_APPROACH, id, 0.0F, 0.0F, 0.0F, 0.0F, 0);  // Skyrim lets it go
		}
	}
}
