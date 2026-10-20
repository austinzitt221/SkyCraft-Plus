// SkyCraft+ : enchanting across the games
package dev.skycraft.craft;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim enchantments on Minecraft weapons and armor (enchanted at Skyrim's enchanter: SkyLending,
 * with a soul gem, as Skyrim's own). The enchantment's effects ride on the Minecraft item: a weapon's
 * hit on one of Skyrim's people or creatures is the enchantment itself, cast by the player; on a
 * Minecraft mob each effect is acted out the Minecraft way (fire sets it alight, frost slows it,
 * absorb heals the player, paralysis holds it still...). A weapon's hits use up its charge as
 * Skyrim's do; a filled soul gem used with it in hand (SkyEnchantingClient) charges it again. Armor
 * gives its effects while worn (fortify health more hearts, waterbreathing, fire resistance...).
 * The enchantment exists only in the Skyrim save it was made in: in another, a hit on Skyrim's
 * people is acted out as on a mob.
 */
public final class SkyEnchanting {
	private SkyEnchanting() {
	}

	private static final String TAG = "skycraft_enchant";

	/** A Skyrim enchantment: its form (player-made, FF...), its effects ("kind magnitude seconds;..."), charge, most charge, and cost a hit. */
	public record Enchant(int formId, String effects, float charge, float max, float cost) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Enchant> CODEC = StreamCodec.of((buf, e) -> {
			buf.writeInt(e.formId);
			buf.writeUtf(e.effects, 128);
			buf.writeFloat(e.charge);
			buf.writeFloat(e.max);
			buf.writeFloat(e.cost);
		}, buf -> new Enchant(buf.readInt(), buf.readUtf(128), buf.readFloat(), buf.readFloat(), buf.readFloat()));

		Enchant withCharge(float now) {
			return new Enchant(this.formId, this.effects, Math.max(0.0F, Math.min(now, this.max)), this.max, this.cost);
		}

		/** Each effect: kind, magnitude, seconds. */
		public List<Effect> list() {
			List<Effect> out = new ArrayList<>();
			for (String part : this.effects.split(";")) {
				String[] bits = part.trim().split(" ");
				if (bits.length >= 2) {
					try {
						out.add(new Effect(bits[0], Float.parseFloat(bits[1]), bits.length > 2 ? Integer.parseInt(bits[2]) : 0));
					} catch (NumberFormatException ignored) {
					}
				}
			}
			return out;
		}
	}

	public record Effect(String kind, float magnitude, int seconds) {
		/** How it reads on the item. */
		String describe() {
			String what = switch (this.kind) {
				case "fire" -> "Fire Damage";
				case "frost" -> "Frost Damage";
				case "shock" -> "Shock Damage";
				case "absorb_health" -> "Absorb Health";
				case "absorb_stamina" -> "Absorb Stamina";
				case "absorb_magicka" -> "Absorb Magicka";
				case "paralyze" -> "Paralyze";
				case "fear" -> "Fear";
				case "frenzy" -> "Frenzy";
				case "calm" -> "Calm";
				case "soultrap" -> "Soul Trap";
				case "banish" -> "Banish";
				case "turn" -> "Turn Undead";
				case "stamina" -> "Stamina Damage";
				case "magicka" -> "Magicka Damage";
				case "fortify_health" -> "Fortify Health";
				case "fortify_stamina" -> "Fortify Stamina";
				case "fortify_magicka" -> "Fortify Magicka";
				case "regen" -> "Fortify Healing";
				case "resist_fire" -> "Resist Fire";
				case "resist_frost" -> "Resist Frost";
				case "resist_shock" -> "Resist Shock";
				case "resist_magic" -> "Resist Magic";
				case "water_breathing" -> "Waterbreathing";
				case "speed" -> "Fortify Speed";
				case "fortify_attack" -> "Fortify Attack";
				case "fortify_armor" -> "Fortify Armor";
				case "sneak" -> "Muffle";
				case "carry" -> "Fortify Carry Weight";
				default -> this.kind.substring(0, 1).toUpperCase(Locale.ROOT) + this.kind.substring(1).replace('_', ' ');
			};
			return what + (this.magnitude > 0.0F ? " " + Math.round(this.magnitude) : "") + (this.seconds > 0 ? " for " + this.seconds + " s" : "");
		}
	}

	public static @Nullable Enchant enchant(ItemStack stack) {
		CustomData data = stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag().getCompoundOrEmpty(TAG);
		if (tag.isEmpty()) {
			return null;
		}
		return new Enchant(tag.getIntOr("id", 0), tag.getStringOr("effects", ""), tag.getFloatOr("charge", 0.0F), tag.getFloatOr("max", 0.0F), tag.getFloatOr("cost", 0.0F));
	}

	/** The Minecraft item takes the Skyrim enchantment on: kept in it, shown in its tooltip, with the glint. */
	public static void apply(ItemStack stack, Enchant enchant) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag tag = new CompoundTag();
			tag.putInt("id", enchant.formId());
			tag.putString("effects", enchant.effects());
			tag.putFloat("charge", enchant.charge());
			tag.putFloat("max", enchant.max());
			tag.putFloat("cost", enchant.cost());
			root.put(TAG, tag);
		});
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		List<Component> lines = new ArrayList<>();
		for (Effect effect : enchant.list()) {
			lines.add(Component.literal(effect.describe()).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.AQUA)));
		}
		if (enchant.max() > 0.0F && !worn(stack)) {
			lines.add(Component.literal("Charge " + Math.round(enchant.charge()) + " / " + Math.round(enchant.max())).withStyle(st -> st.withItalic(false)
				.withColor(ChatFormatting.GRAY)));
		}
		lines.add(Component.literal("Skyrim enchantment").withStyle(st -> st.withItalic(false).withColor(0x808080)));
		stack.set(DataComponents.LORE, new ItemLore(lines));
	}

	private static boolean worn(ItemStack stack) {
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot().isArmor();
	}

	/** A filled soul gem's soul (1 petty .. 5 grand): the charge it gives, as Skyrim's. */
	public static float soulCharge(int soul) {
		return switch (soul) {
			case 1 -> 250.0F;
			case 2 -> 500.0F;
			case 3 -> 1000.0F;
			case 4 -> 2000.0F;
			default -> soul >= 5 ? 3000.0F : 0.0F;
		};
	}

	/** Server: a soul gem's charge into the main-hand weapon's Skyrim enchantment. */
	public static void recharge(ServerPlayer player, float charge) {
		ItemStack weapon = player.getMainHandItem();
		Enchant enchant = enchant(weapon);
		if (enchant == null || charge <= 0.0F) {
			return;
		}
		apply(weapon, enchant.withCharge(enchant.charge() + charge));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
		player.sendOverlayMessage(Component.literal(weapon.getHoverName().getString() + " recharged"));
	}

	public static void init() {
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer server) || !(entity instanceof LivingEntity target)) {
				return InteractionResult.PASS;
			}
			ItemStack weapon = player.getMainHandItem();
			Enchant enchant = enchant(weapon);
			if (enchant == null || worn(weapon)) {
				return InteractionResult.PASS;
			}
			if (enchant.max() > 0.0F && enchant.charge() < enchant.cost()) {
				return InteractionResult.PASS;  // out of charge (a soul gem charges it)
			}
			if (enchant.max() > 0.0F && enchant.cost() > 0.0F) {
				apply(weapon, enchant.withCharge(enchant.charge() - enchant.cost()));
			}
			// After the weapon's own hit (which makes the target briefly unhurtable).
			server.level().getServer().execute(() -> {
				if (!target.isAlive()) {
					return;
				}
				if (target instanceof SkyrimActorEntity proxy && SkyNet.isHost(server) && SkyLink.active() && enchant.formId() != 0) {
					SkyLink.pushEvent(Proto.EV_ENCHANT_HIT, proxy.formId(), 0.0F, 0.0F, 0.0F, 0.0F, enchant.formId());  // Skyrim's own enchantment on Skyrim's own
				} else {
					for (Effect effect : enchant.list()) {
						actOut(server, target, effect);
					}
				}
			});
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(SkyEnchanting::wornTick);
	}

	/** A weapon's effect, the Minecraft way. Skyrim's numbers are a tenth of a Minecraft heart's worth or so (a mob has 20 health, a bandit 100). */
	private static void actOut(ServerPlayer player, LivingEntity target, Effect effect) {
		ServerLevel level = (ServerLevel) target.level();
		float damage = effect.magnitude() * 0.2F;
		int ticks = Math.max(20, effect.seconds() * 20);
		switch (effect.kind()) {
			case "fire" -> {
				target.igniteForSeconds(Math.max(3.0F, effect.seconds()));
				hurt(player, target, level.damageSources().onFire(), damage);
			}
			case "frost" -> {
				target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 1), player);
				target.setTicksFrozen(Math.max(target.getTicksFrozen(), 140));
				hurt(player, target, level.damageSources().freeze(), damage);
			}
			case "shock" -> hurt(player, target, level.damageSources().lightningBolt(), damage * 1.25F);
			case "damage", "stamina", "magicka" -> hurt(player, target, level.damageSources().indirectMagic(player, player), damage);
			case "absorb_health" -> {
				hurt(player, target, level.damageSources().indirectMagic(player, player), damage);
				player.heal(damage);
			}
			case "absorb_stamina", "absorb_magicka" -> {
				target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0), player);
				player.getFoodData().eat(1, 0.1F);
			}
			case "paralyze" -> {
				int held = Math.min(ticks, 100);
				target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, held, 9), player);
				target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, held, 9), player);
			}
			case "fear", "calm" -> {
				if (target instanceof Mob mob) {
					mob.setTarget(null);
				}
				target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0), player);
			}
			case "frenzy" -> target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0), player);
			case "turn", "banish" -> {
				if (target.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD)) {
					hurt(player, target, level.damageSources().indirectMagic(player, player), Math.max(4.0F, damage * 2.0F));
				}
			}
			default -> {
			}
		}
	}

	private static void hurt(ServerPlayer player, LivingEntity target, net.minecraft.world.damagesource.DamageSource source, float damage) {
		if (damage > 0.0F) {
			target.setInvulnerableTime(0);
			target.hurtServer((ServerLevel) target.level(), source, damage);
		}
	}

	/** Once a second: worn armor's Skyrim enchantments, as Minecraft effects lasting a little longer than that. */
	private static void wornTick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND }) {
				ItemStack worn = player.getItemBySlot(slot);
				Enchant enchant = slot == EquipmentSlot.OFFHAND && !worn.is(net.minecraft.world.item.Items.SHIELD) ? null : enchant(worn);
				if (enchant == null) {
					continue;
				}
				for (Effect effect : enchant.list()) {
					Holder<MobEffect> mob = null;
					int amplifier = 0;
					switch (effect.kind()) {
						case "fortify_health" -> {
							mob = MobEffects.HEALTH_BOOST;
							amplifier = Math.clamp(Math.round(effect.magnitude() / 20.0F) - 1, 0, 4);
						}
						case "regen" -> mob = MobEffects.REGENERATION;
						case "resist_fire" -> mob = effect.magnitude() >= 25.0F ? MobEffects.FIRE_RESISTANCE : null;
						case "water_breathing" -> mob = MobEffects.WATER_BREATHING;
						case "speed" -> mob = MobEffects.SPEED;
						case "fortify_attack" -> mob = MobEffects.STRENGTH;
						case "fortify_armor", "resist_magic", "resist_frost", "resist_shock" -> mob = MobEffects.RESISTANCE;
						case "fortify_stamina" -> mob = MobEffects.HASTE;
						case "sneak" -> mob = MobEffects.INVISIBILITY;
						default -> {
						}
					}
					if (mob != null) {
						MobEffectInstance now = player.getEffect(mob);
						if (now == null || now.getDuration() < 60 || now.getAmplifier() < amplifier) {
							player.addEffect(new MobEffectInstance(mob, mob == MobEffects.REGENERATION ? 50 : 100, amplifier, true, false, true));
						}
					}
				}
			}
		}
	}
}
