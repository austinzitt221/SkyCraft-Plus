// SkyCraft+ : arrows across the games
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.net.SkyStorage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;

/**
 * Arrows across the games. A Minecraft bow (or crossbow) shoots Skyrim's arrows when Minecraft has
 * none, or always when a Skyrim arrow's shortcut is in the off hand: the arrow is Skyrim's (its
 * damage, fire, frost or shock), Skyrim uses one up a shot, and picked up again it goes back into
 * Skyrim's inventory. A Skyrim bow shoots Minecraft's arrows when they're in the off hand: they're
 * lent to Skyrim as iron arrows (SkyArrowsClient), one Minecraft arrow is used up a shot, and a
 * tipped or spectral one puts its effect on what it hits. In both games the off hand's arrows are
 * the ones a bow uses. Setting: arrowsAcrossGames in config/skycraft.properties.
 */
public final class SkyArrows {
	private SkyArrows() {
	}

	private static final String TAG = "skycraft_skyrim_arrow";

	/** Client -> server: the Skyrim arrows a Minecraft bow would shoot now (count 0: none); forced: their shortcut is in the off hand. */
	public record Ammo(String plugin, int localId, String name, float damage, int count, boolean forced) implements CustomPacketPayload {
		public static final Type<Ammo> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_ammo"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Ammo> CODEC = StreamCodec.of((buf, a) -> {
			buf.writeUtf(a.plugin, 64);
			buf.writeInt(a.localId);
			buf.writeUtf(a.name, 96);
			buf.writeFloat(a.damage);
			buf.writeVarInt(Math.max(0, a.count));
			buf.writeBoolean(a.forced);
		}, buf -> new Ammo(buf.readUtf(64), buf.readInt(), buf.readUtf(96), buf.readFloat(), buf.readVarInt(), buf.readBoolean()));

		public static final Ammo NONE = new Ammo("", 0, "", 0.0F, 0, false);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: a Minecraft bow shot one of this Skyrim arrow: Skyrim uses one up. */
	public record Fired(String plugin, int localId) implements CustomPacketPayload {
		public static final Type<Fired> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_arrow_fired"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Fired> CODEC = StreamCodec.of((buf, f) -> {
			buf.writeUtf(f.plugin, 64);
			buf.writeInt(f.localId);
		}, buf -> new Fired(buf.readUtf(64), buf.readInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: Skyrim's bow shot this many of the Minecraft arrows lent to it (they're gone from the off hand). */
	public record Shot(int count) implements CustomPacketPayload {
		public static final Type<Shot> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "minecraft_arrows_shot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Shot> CODEC = StreamCodec.of((buf, s) -> buf.writeVarInt(s.count), buf -> new Shot(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static final Map<UUID, Ammo> AMMO = new HashMap<>();
	/** The client's own (its Minecraft asks too, to draw the bow): SkyArrowsClient keeps it. */
	public static volatile Ammo clientAmmo = Ammo.NONE;
	private static volatile boolean enabled = true;

	public static boolean enabled() {
		return enabled;
	}

	private static Ammo ammo(Player player) {
		return player.level().isClientSide() ? clientAmmo : AMMO.getOrDefault(player.getUUID(), Ammo.NONE);
	}

	/** A Minecraft arrow standing for this many of a Skyrim one (what a Minecraft bow draws); EMPTY if there are none. */
	private static ItemStack standIn(Ammo ammo) {
		if (ammo.count() <= 0 || ammo.plugin().isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = new ItemStack(Items.ARROW, Math.min(ammo.count(), 64));
		CompoundTag tag = new CompoundTag();
		tag.putString("plugin", ammo.plugin());
		tag.putInt("id", ammo.localId());
		tag.putString("name", ammo.name());
		tag.putFloat("damage", ammo.damage());
		CompoundTag root = new CompoundTag();
		root.put(TAG, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(ammo.name()).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.WHITE)));
		return stack;
	}

	private static @Nullable CompoundTag tagOf(ItemStack stack) {
		CustomData data = stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? null : data.copyTag().getCompoundOrEmpty(TAG);
		return tag == null || tag.isEmpty() ? null : tag;
	}

	/** PlayerProjectileMixin, before Minecraft looks: the off hand's Skyrim arrows, if their shortcut is there (else null: as ever). */
	public static @Nullable ItemStack forced(Player player) {
		Ammo ammo = enabled ? ammo(player) : Ammo.NONE;
		return ammo.forced() && ammo.count() > 0 ? standIn(ammo) : null;
	}

	/** PlayerProjectileMixin, after: Minecraft found no arrows of its own (or only a creative player's free one): Skyrim's, if any. */
	public static ItemStack orSkyrims(Player player, ItemStack found) {
		boolean none = found.isEmpty() || player.hasInfiniteMaterials() && found.is(Items.ARROW) && !player.getInventory().contains(found);
		if (!enabled || !none) {
			return found;
		}
		ItemStack skyrim = standIn(ammo(player));
		return skyrim.isEmpty() ? found : skyrim;
	}

	/** ProjectileWeaponItemMixin: a Skyrim arrow was drawn (not a free one): its Skyrim uses one up. */
	public static void used(LivingEntity shooter, ItemStack drawn) {
		CompoundTag tag = tagOf(drawn);
		if (tag == null || drawn.has(DataComponents.INTANGIBLE_PROJECTILE) || !(shooter instanceof ServerPlayer player)) {
			return;
		}
		String plugin = tag.getStringOr("plugin", "");
		int id = tag.getIntOr("id", 0);
		Ammo ammo = AMMO.get(player.getUUID());
		if (ammo != null && ammo.plugin().equalsIgnoreCase(plugin) && ammo.localId() == id) {
			AMMO.put(player.getUUID(), new Ammo(ammo.plugin(), ammo.localId(), ammo.name(), ammo.damage(), Math.max(0, ammo.count() - 1), ammo.forced()));
		}
		if (ServerPlayNetworking.canSend(player, Fired.TYPE)) {
			ServerPlayNetworking.send(player, new Fired(plugin, id));
		}
	}

	/**
	 * ProjectileWeaponItemMixin: the arrow a Minecraft bow made from a Skyrim one hits with its damage
	 * (a quarter of Skyrim's: iron 2 as Minecraft's own, Daedric 6), flies alight if it's a fire arrow,
	 * and picked up it's the Skyrim arrow again (back into Skyrim's inventory).
	 */
	public static void made(Projectile projectile, ItemStack from) {
		CompoundTag tag = tagOf(from);
		if (tag == null || !(projectile instanceof AbstractArrow arrow)) {
			return;
		}
		float damage = tag.getFloatOr("damage", 8.0F);
		arrow.setBaseDamage(Math.clamp(damage * 0.25, 1.0, 8.0));
		String name = tag.getStringOr("name", "Skyrim arrow");
		if (name.toLowerCase(Locale.ROOT).contains("fire")) {
			arrow.igniteForSeconds(100.0F);
		}
		SkyStorage.Stored stored = new SkyStorage.Stored(tag.getStringOr("plugin", ""), tag.getIntOr("id", 0), Proto.SKY_AMMO, 0, 0, 0, 1.0F, -1.0F, 0, 0.0F, 0, damage,
			0.0F, name, "minecraft:arrow", 0, false);
		((dev.skycraft.mixin.AbstractArrowAccessor) arrow).skycraft$setPickupItemStack(SkyStorage.make(stored, 1));
	}

	/** AbstractArrowMixin: a Skyrim arrow from a Minecraft bow hit this: frost slows it, shock hurts it more. */
	public static void hit(AbstractArrow arrow, LivingEntity target) {
		SkyStorage.Stored stored = SkyStorage.stored(arrow.getPickupItemStackOrigin());
		if (stored == null || stored.kind() != Proto.SKY_AMMO || target.level().isClientSide()) {
			return;
		}
		String name = stored.name().toLowerCase(Locale.ROOT);
		if (name.contains("frost") || name.contains("ice")) {
			target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
		}
		if (name.contains("shock") || name.contains("lightning")) {
			target.setInvulnerableTime(0);
			target.hurtServer((ServerLevel) target.level(), target.damageSources().lightningBolt(), 3.0F);
		}
	}

	// ---- Minecraft's arrows on Skyrim's bows --------------------------------------------------

	private static final Map<UUID, Set<Integer>> SHOTS_SEEN = new HashMap<>();

	public static boolean minecraftArrows(ItemStack stack) {
		return stack.is(Items.ARROW) || stack.is(Items.TIPPED_ARROW) || stack.is(Items.SPECTRAL_ARROW);
	}

	/**
	 * The Minecraft arrows a Skyrim bow shoots (lent to it: SkyArrowsClient), or EMPTY: the off hand's;
	 * or, when Skyrim has no arrows of its own, the first ones in the inventory (as a Minecraft bow
	 * takes them). The same on both sides: the client lends them, the server uses them up.
	 */
	public static ItemStack lentArrows(Player player, boolean skyrimHasArrows) {
		if (!enabled) {
			return ItemStack.EMPTY;
		}
		ItemStack off = player.getOffhandItem();
		if (minecraftArrows(off)) {
			return off;
		}
		if (skyrimHasArrows || dev.skycraft.net.SkyShortcuts.isShortcut(off)) {
			return ItemStack.EMPTY;  // (a Skyrim arrow's shortcut in the off hand: those)
		}
		var items = player.getInventory().getNonEquipmentItems();
		for (int i = 0; i < items.size(); i++) {
			if (minecraftArrows(items.get(i))) {
				return items.get(i);
			}
		}
		return ItemStack.EMPTY;
	}

	/** The arrows this player's Skyrim bow is shooting from Minecraft (EMPTY: none). */
	private static ItemStack lending(ServerPlayer player) {
		if (!skyrimBow(player)) {
			return ItemStack.EMPTY;
		}
		return lentArrows(player, AMMO.getOrDefault(player.getUUID(), Ammo.NONE).count() > 0);
	}

	private static boolean skyrimBow(ServerPlayer player) {
		int kinds = dev.skycraft.net.SkyNet.isHost(player) ? SkyCombat.rightHandKind | SkyCombat.leftHandKind << 8 : dev.skycraft.net.SkyNet.guestHandsAndKinds(player) >> 8;
		return (kinds & 0xFF) == Proto.HAND_BOW || (kinds >> 8 & 0xFF) == Proto.HAND_BOW || (kinds & 0xFF) == Proto.HAND_CROSSBOW;
	}

	// ---- Minecraft enchantments on Skyrim's bows (enchanting across the games) ---------------

	/** The Skyrim bow's shortcut in a hand, if it holds Minecraft enchantments (EMPTY: none). */
	public static ItemStack enchantedSkyrimBow(Player player) {
		for (ItemStack held : new ItemStack[] { player.getMainHandItem(), player.getOffhandItem() }) {
			var ref = dev.skycraft.net.SkyShortcuts.ref(held);
			var enchantments = ref != null && ref.kind() == Proto.SKY_WEAPON ? held.get(DataComponents.ENCHANTMENTS) : null;
			if (enchantments != null && !enchantments.isEmpty()) {
				return held;
			}
		}
		return ItemStack.EMPTY;
	}

	/** This enchantment's level on a stack (0: none). */
	public static int level(ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> enchantment) {
		var enchantments = stack.isEmpty() ? null : stack.get(DataComponents.ENCHANTMENTS);
		if (enchantments == null) {
			return 0;
		}
		for (var entry : enchantments.entrySet()) {
			if (entry.getKey().is(enchantment)) {
				return entry.getIntValue();
			}
		}
		return 0;
	}

	/**
	 * SkyMagic: a Skyrim arrow from an enchanted Skyrim bow hit a Minecraft mob: Power more damage,
	 * Punch further back, Flame alight (Infinity and Multishot are Skyrim's: SkyrimShots). Returns the damage.
	 */
	public static float bowHit(ServerPlayer player, LivingEntity target, float damage) {
		ItemStack bow = enchantedSkyrimBow(player);
		if (bow.isEmpty()) {
			return damage;
		}
		int power = level(bow, net.minecraft.world.item.enchantment.Enchantments.POWER);
		int punch = level(bow, net.minecraft.world.item.enchantment.Enchantments.PUNCH);
		if (level(bow, net.minecraft.world.item.enchantment.Enchantments.FLAME) > 0) {
			target.igniteForSeconds(5.0F);
		}
		if (punch > 0) {
			double yaw = player.getYRot() * (Math.PI / 180.0);
			target.knockback(0.6 * punch, Math.sin(yaw), -Math.cos(yaw), player.damageSources().playerAttack(player), damage);
		}
		return power > 0 ? damage * (1.0F + 0.25F * (power + 1)) : damage;
	}

	/** SkyMagic: how many more mobs a Skyrim arrow goes through (Piercing on a Skyrim crossbow or bow). */
	public static int piercing(ServerPlayer player) {
		return level(enchantedSkyrimBow(player), net.minecraft.world.item.enchantment.Enchantments.PIERCING);
	}

	/** SkyMagic: a Skyrim arrow shot (new this frame): one of the off hand's Minecraft arrows is used up for it. */
	public static void skyrimShot(ServerPlayer player, int shotId) {
		Set<Integer> seen = SHOTS_SEEN.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
		if (!seen.add(shotId)) {
			return;
		}
		if (seen.size() > 256) {
			seen.clear();
			seen.add(shotId);
		}
		ItemStack lent = lending(player);
		if (lent.isEmpty()) {
			return;
		}
		// (Infinity on the Skyrim bow: Skyrim gives its arrow back, and the Minecraft one stays.)
		if (level(enchantedSkyrimBow(player), net.minecraft.world.item.enchantment.Enchantments.INFINITY) > 0 && lent.is(Items.ARROW)) {
			return;
		}
		if (!player.hasInfiniteMaterials()) {
			lent.shrink(1);
		}
		if (ServerPlayNetworking.canSend(player, Shot.TYPE)) {
			ServerPlayNetworking.send(player, new Shot(1));
		}
	}

	/** SkyMagic: a Skyrim arrow hit a Minecraft mob: the off hand's tipped arrows' effects, or a spectral arrow's glow, go on it. */
	public static void skyrimArrowHit(ServerPlayer player, LivingEntity target) {
		ItemStack off = lending(player);
		if (off.isEmpty()) {
			return;
		}
		if (off.is(Items.SPECTRAL_ARROW)) {
			target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0), player);
		}
		PotionContents potion = off.get(DataComponents.POTION_CONTENTS);
		if (potion != null) {
			potion.forEachEffect(effect -> target.addEffect(effect, player), 0.125F);
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Ammo.TYPE, Ammo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Fired.TYPE, Fired.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Shot.TYPE, Shot.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Ammo.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> AMMO.put(player.getUUID(), payload));
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			AMMO.remove(handler.getPlayer().getUUID());
			SHOTS_SEEN.remove(handler.getPlayer().getUUID());
		});
		dev.skycraft.SkySettings.onChange(SkyArrows::load);
		load();
	}

	private static void load() {
		enabled = dev.skycraft.SkySettings.flag("arrowsAcrossGames", true);
	}
}
