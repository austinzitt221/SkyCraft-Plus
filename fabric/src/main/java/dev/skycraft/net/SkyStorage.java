// SkyCraft+ : Skyrim items in Minecraft's containers
package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim items in Minecraft's containers: chests, barrels, shulker boxes, hoppers, droppers,
 * bundles, ender chests. Put in one, a Skyrim item leaves Skyrim's inventory (and any shortcut for
 * it goes) and becomes a Minecraft item of its own (skycraft:skyrim_item) that says what it is:
 * plugin and id, tempering, charge, soul, and its stand-in look and stats. Being a real Minecraft
 * item, hoppers, droppers and sorting systems move it like any other. Back in a player's own
 * inventory (taken out, shift-clicked, picked up off the ground) it goes back into that player's
 * Skyrim at once, and leaves a shortcut where it landed. A friend taking it gets it in their own
 * Skyrim. Kept with the Minecraft world, so it doesn't care which Skyrim save is loaded.
 *
 * Storing goes through the player's own Skyrim (it decides what can go and which copies), so the
 * client asks Skyrim first and then tells the server what to put where (Deposit).
 */
public final class SkyStorage {
	private SkyStorage() {
	}

	private static final String TAG = "skycraft_stored";

	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_item"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, new StoredItem(new Item.Properties().setId(ITEM_KEY)));

	/**
	 * SkyCraft+ : Skyrim items carried in Minecraft. Food, potions and ingredients are eaten or drunk from the hand as
	 * Minecraft's are (held, with its animation: make() gives them CONSUMABLE, no hunger needed); when done, one goes back
	 * into Skyrim and is used there.
	 */
	private static final class StoredItem extends Item {
		StoredItem(Item.Properties properties) {
			super(properties);
		}

		@Override
		public ItemStack finishUsingItem(ItemStack stack, net.minecraft.world.level.Level level, net.minecraft.world.entity.LivingEntity entity) {
			Stored stored = stored(stack);
			if (entity instanceof ServerPlayer player && stored != null && usedFromHand(stored.kind()) && ServerPlayNetworking.canSend(player, GiveAndUse.TYPE)) {
				ServerPlayNetworking.send(player, new GiveAndUse(stored, UseStored.NO_FARMLAND));
				if (!player.hasInfiniteMaterials()) {
					stack.shrink(1);
				}
			}
			return stack;
		}
	}

	/** What a stored Skyrim item is: Skyrim's side (plugin, id, copy data, stats) and its Minecraft stand-in look. */
	public record Stored(String plugin, int localId, int kind, int sub, int material, int flags, float health, float charge, int soul, float weight, int value,
		float stat, float stat2, String name, String model, int color, boolean glint) {

		static final StreamCodec<RegistryFriendlyByteBuf, Stored> CODEC = StreamCodec.of((buf, s) -> {
			buf.writeUtf(s.plugin, 64);
			buf.writeInt(s.localId);
			buf.writeVarInt(s.kind);
			buf.writeVarInt(s.sub);
			buf.writeVarInt(s.material);
			buf.writeInt(s.flags);
			buf.writeFloat(s.health);
			buf.writeFloat(s.charge);
			buf.writeVarInt(s.soul);
			buf.writeFloat(s.weight);
			buf.writeInt(s.value);
			buf.writeFloat(s.stat);
			buf.writeFloat(s.stat2);
			buf.writeUtf(s.name, 128);
			buf.writeUtf(s.model, 128);
			buf.writeInt(s.color);
			buf.writeBoolean(s.glint);
		}, buf -> new Stored(buf.readUtf(64), buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readFloat(), buf.readFloat(),
			buf.readVarInt(), buf.readFloat(), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readUtf(128), buf.readUtf(128), buf.readInt(), buf.readBoolean()));

		/** Skyrim's tempering tiers. */
		String tier() {
			if (this.health >= 1.595F) {
				return "Legendary";
			}
			if (this.health >= 1.495F) {
				return "Epic";
			}
			if (this.health >= 1.395F) {
				return "Flawless";
			}
			if (this.health >= 1.295F) {
				return "Exquisite";
			}
			if (this.health >= 1.195F) {
				return "Superior";
			}
			return this.health > 1.0001F ? "Fine" : null;
		}

		/** Weapons and apparel stack one to a slot, as Minecraft's own do; the rest 64. */
		int maxStack() {
			return this.kind == Proto.SKY_WEAPON || this.kind == Proto.SKY_APPAREL ? 1 : 64;
		}
	}

	/** Client -> server: Skyrim took this many of a thing out for a container; put them there. */
	public record Deposit(int menu, int slot, boolean intoBundle, boolean forgetShortcuts, int count, Stored stored) implements CustomPacketPayload {
		public static final Type<Deposit> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "storage_deposit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Deposit> CODEC = StreamCodec.of((buf, d) -> {
			buf.writeVarInt(d.menu);
			buf.writeVarInt(d.slot + 1);
			buf.writeBoolean(d.intoBundle);
			buf.writeBoolean(d.forgetShortcuts);
			buf.writeVarInt(d.count);
			Stored.CODEC.encode(buf, d.stored);
		}, buf -> new Deposit(buf.readVarInt(), buf.readVarInt() - 1, buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), Stored.CODEC.decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the stored item on the cursor was put on the Skyrim panels: back into Skyrim, no shortcut. */
	public record TakeCarried(int unused) implements CustomPacketPayload {
		public static final Type<TakeCarried> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "storage_take_carried"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TakeCarried> CODEC = StreamCodec.of((buf, t) -> buf.writeVarInt(t.unused),
			buf -> new TakeCarried(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: this many came out of a container into the player's hands: back into their Skyrim. */
	public record Give(int count, Stored stored) implements CustomPacketPayload {
		public static final Type<Give> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "storage_give"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Give> CODEC = StreamCodec.of((buf, g) -> {
			buf.writeVarInt(g.count);
			Stored.CODEC.encode(buf, g.stored);
		}, buf -> new Give(buf.readVarInt(), Stored.CODEC.decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * SkyCraft+ : Skyrim items carried in Minecraft. Things dragged into the player's own inventory leave
	 * Skyrim's as they would for a chest: then they're the only ones (their weight gone from Skyrim's too),
	 * and stay where they are; dropped on the Skyrim panel, back they go. Food, potions, ingredients and
	 * books used from the hand: one goes back into Skyrim and is used (read) there. Weapons, arrows, scrolls
	 * and armor go back into Skyrim once in a hand or an armor slot (heldInSkyrim): Skyrim has to have
	 * those to use them, and a shortcut takes their place. Spells, keys, soul gems, torches and quest
	 * things stay shortcuts. Setting skyrimItemsInMinecraft.
	 */
	public static boolean carriedInMinecraft(int kind, int sub, int flags, String name) {
		if (!dev.skycraft.SkySettings.flag("skyrimItemsInMinecraft", true) || (flags & Proto.ITEM_QUEST) != 0) {
			return false;
		}
		return switch (kind) {
			case Proto.SKY_FOOD, Proto.SKY_POTION, Proto.SKY_INGREDIENT, Proto.SKY_BOOK, Proto.SKY_WEAPON, Proto.SKY_APPAREL, Proto.SKY_AMMO, Proto.SKY_SCROLL -> true;
			case Proto.SKY_MISC -> sub != 2 && !name.toLowerCase(java.util.Locale.ROOT).contains("soul gem");
			default -> false;
		};
	}

	/** SkyCraft+ : carried things Skyrim has to have to use: back into Skyrim once in a hand or an armor slot. */
	public static boolean heldInSkyrim(int kind) {
		return kind == Proto.SKY_WEAPON || kind == Proto.SKY_APPAREL || kind == Proto.SKY_AMMO || kind == Proto.SKY_SCROLL;
	}

	/** SkyCraft+ : carried things used from the hand (or right-clicked in the inventory): eaten, drunk or read in Skyrim. */
	public static boolean usedFromHand(int kind) {
		return kind == Proto.SKY_FOOD || kind == Proto.SKY_POTION || kind == Proto.SKY_INGREDIENT || kind == Proto.SKY_BOOK;
	}

	/** Deposit.slot for anywhere in the player's own inventory (a thing carried in Minecraft, shift-clicked out of the Skyrim panel). */
	public static final int OWN_INVENTORY = 9999;
	/** Deposit.slot for this index of the player's own inventory plus this (creative's inventory tab numbers its slots its own way). */
	public static final int INVENTORY_INDEX = 10000;

	public static boolean carriedInMinecraft(Stored s) {
		return carriedInMinecraft(s.kind(), s.sub(), s.flags(), s.name());
	}

	/**
	 * SkyCraft+ : Skyrim items carried in Minecraft. Client -> server: the stored food, potion or ingredient at this index of the
	 * player's inventory (a hand's, or right-clicked in the inventory screen) is used: one goes back into Skyrim for it. With
	 * farmland (a BlockPos as a long; NO_FARMLAND: none), a plantable one is planted there instead.
	 */
	public record UseStored(int index, long farmland) implements CustomPacketPayload {
		public static final long NO_FARMLAND = Long.MIN_VALUE;
		public static final Type<UseStored> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "storage_use"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UseStored> CODEC = StreamCodec.of((buf, u) -> {
			buf.writeVarInt(u.index);
			buf.writeLong(u.farmland);
		}, buf -> new UseStored(buf.readVarInt(), buf.readLong()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** SkyCraft+ : Skyrim items carried in Minecraft. Server -> client: this one, out of the hand, into Skyrim and used there (or planted on that farmland). */
	public record GiveAndUse(Stored stored, long farmland) implements CustomPacketPayload {
		public static final Type<GiveAndUse> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "storage_give_use"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GiveAndUse> CODEC = StreamCodec.of((buf, g) -> {
			Stored.CODEC.encode(buf, g.stored);
			buf.writeLong(g.farmland);
		}, buf -> new GiveAndUse(Stored.CODEC.decode(buf), buf.readLong()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static void useStored(ServerPlayer player, int index, long farmland) {
		if (index < 0 || index >= player.getInventory().getContainerSize()) {
			return;
		}
		ItemStack held = player.getInventory().getItem(index);
		Stored stored = stored(held);
		boolean plant = farmland != UseStored.NO_FARMLAND && stored != null && (stored.flags() & Proto.ITEM_PLANTABLE) != 0;
		if (stored == null || !(plant || usedFromHand(stored.kind()))
			|| !ServerPlayNetworking.canSend(player, GiveAndUse.TYPE)) {
			return;
		}
		if (!player.hasInfiniteMaterials()) {
			held.shrink(1);
		}
		ServerPlayNetworking.send(player, new GiveAndUse(stored, plant ? farmland : UseStored.NO_FARMLAND));
	}

	/** SkyCraft+ : Skyrim items carried in Minecraft. A stored thing of this kind in a hand was fed to an animal: one fewer (true), else false. */
	public static boolean usedUpFromHand(ServerPlayer player, String plugin, int localId) {
		for (var hand : net.minecraft.world.InteractionHand.values()) {
			ItemStack held = player.getItemInHand(hand);
			Stored stored = stored(held);
			if (stored != null && stored.localId() == localId && stored.plugin().equalsIgnoreCase(plugin)) {
				if (!player.hasInfiniteMaterials()) {
					held.shrink(1);
				}
				return true;
			}
		}
		return false;
	}

	public static boolean isStored(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (stack.is(ITEM)) {
			return true;
		}
		// SkyCraft+ : enchanting across the games. A Skyrim weapon or apparel at Minecraft's enchanting table is its stand-in (standIn).
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(TAG);
	}

	/**
	 * SkyCraft+ : enchanting across the games. A stored Skyrim weapon or apparel as its Minecraft stand-in
	 * itself (an iron sword, a bow, a chestplate), so Minecraft's enchanting table offers it what it offers
	 * that; taken back into the inventory it goes into Skyrim as ever, and its shortcut keeps what it was
	 * enchanted with (the Skyrim weapon's hits and mining use them: SkyCombat).
	 */
	public static ItemStack standIn(Stored s) {
		Identifier modelId = Identifier.tryParse(s.model);
		var item = modelId == null ? null : BuiltInRegistries.ITEM.getOptional(modelId).orElse(null);
		if (item == null || item == net.minecraft.world.item.Items.PAPER) {
			return make(s, 1);
		}
		ItemStack stack = new ItemStack(item);
		CompoundTag root = new CompoundTag();
		root.put(TAG, tagOf(s));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(s.name).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.WHITE)));
		stack.set(DataComponents.LORE, new ItemLore(lore(s)));
		return stack;
	}

	/** Whether a Skyrim thing goes onto this slot as its stand-in: the enchanting table's (weapons and apparel). */
	private static boolean asStandIn(AbstractContainerMenu menu, @Nullable Slot slot, Stored stored) {
		return slot != null && menu instanceof net.minecraft.world.inventory.EnchantmentMenu && slot.index == 0
			&& (stored.kind == Proto.SKY_WEAPON || stored.kind == Proto.SKY_APPAREL);
	}

	public static @Nullable Stored stored(ItemStack stack) {
		if (!isStored(stack)) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty(TAG);
		if (tag.isEmpty()) {
			return null;
		}
		return new Stored(tag.getStringOr("plugin", ""), tag.getIntOr("id", 0), tag.getIntOr("kind", 0), tag.getIntOr("sub", 0), tag.getIntOr("material", 0),
			tag.getIntOr("flags", 0), tag.getFloatOr("health", 1.0F), tag.getFloatOr("charge", -1.0F), tag.getIntOr("soul", 0), tag.getFloatOr("weight", 0.0F),
			tag.getIntOr("value", 0), tag.getFloatOr("stat", 0.0F), tag.getFloatOr("stat2", 0.0F), tag.getStringOr("name", "Skyrim item"),
			tag.getStringOr("model", "minecraft:paper"), tag.getIntOr("color", 0), tag.getBooleanOr("glint", false));
	}

	/** The Minecraft item for count of a stored Skyrim thing (count up to its stack size). */
	public static ItemStack make(Stored s, int count) {
		ItemStack stack = new ItemStack(ITEM, count);
		CompoundTag root = new CompoundTag();
		root.put(TAG, tagOf(s));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		Identifier modelId = Identifier.tryParse(s.model);
		var standIn = modelId == null ? null : BuiltInRegistries.ITEM.getOptional(modelId).orElse(null);
		Identifier model = standIn == null ? null : standIn.components().get(DataComponents.ITEM_MODEL);
		if (model != null) {
			stack.set(DataComponents.ITEM_MODEL, model);
		}
		if (s.color != 0) {
			stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(s.color), List.of(), Optional.empty()));
			stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.POTION_CONTENTS, true));
		}
		if (s.glint) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		if (s.kind == Proto.SKY_FOOD || s.kind == Proto.SKY_POTION || s.kind == Proto.SKY_INGREDIENT) {
			// SkyCraft+ : eaten or drunk from the hand as Minecraft's food is (StoredItem).
			boolean drink = s.kind == Proto.SKY_POTION;
			stack.set(DataComponents.CONSUMABLE, net.minecraft.world.item.component.Consumable.builder().consumeSeconds(1.6F)
				.animation(drink ? net.minecraft.world.item.ItemUseAnimation.DRINK : net.minecraft.world.item.ItemUseAnimation.EAT)
				.sound(drink ? net.minecraft.sounds.SoundEvents.GENERIC_DRINK : net.minecraft.sounds.SoundEvents.GENERIC_EAT).hasConsumeParticles(!drink).build());
		}
		stack.set(DataComponents.MAX_STACK_SIZE, s.maxStack());
		String tier = s.tier();
		boolean enchanted = (s.flags & Proto.ITEM_ENCHANTED) != 0;
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(s.name + (tier != null ? " (" + tier + ")" : ""))
			.withStyle(st -> st.withItalic(false).withColor(enchanted ? ChatFormatting.AQUA : ChatFormatting.WHITE)));
		stack.set(DataComponents.LORE, new ItemLore(lore(s)));
		return stack;
	}

	private static CompoundTag tagOf(Stored s) {
		CompoundTag tag = new CompoundTag();
		tag.putString("plugin", s.plugin);
		tag.putInt("id", s.localId);
		tag.putInt("kind", s.kind);
		tag.putInt("sub", s.sub);
		tag.putInt("material", s.material);
		tag.putInt("flags", s.flags);
		tag.putFloat("health", s.health);
		tag.putFloat("charge", s.charge);
		tag.putInt("soul", s.soul);
		tag.putFloat("weight", s.weight);
		tag.putInt("value", s.value);
		tag.putFloat("stat", s.stat);
		tag.putFloat("stat2", s.stat2);
		tag.putString("name", s.name);
		tag.putString("model", s.model);
		tag.putInt("color", s.color);
		tag.putBoolean("glint", s.glint);
		return tag;
	}

	/** The stats Skyrim's menu would show, so the item says what it is wherever it is. */
	private static List<Component> lore(Stored s) {
		List<Component> lines = new ArrayList<>();
		switch (s.kind) {
			case Proto.SKY_WEAPON, Proto.SKY_AMMO -> lines.add(gray("Damage " + Math.round(s.stat)));
			case Proto.SKY_APPAREL -> lines.add(gray(((s.flags & Proto.ITEM_HEAVY) != 0 ? "Heavy armor, " : (s.flags & Proto.ITEM_LIGHT) != 0 ? "Light armor, " : "") + "armor "
				+ Math.round(s.stat)));
			case Proto.SKY_POTION, Proto.SKY_FOOD -> {
				if (s.stat > 0.0F) {
					lines.add(gray("Magnitude " + Math.round(s.stat) + (s.stat2 > 0.0F ? " for " + Math.round(s.stat2) + " s" : "")));
				}
			}
			default -> {
			}
		}
		String weight = s.weight == Math.rint(s.weight) ? String.valueOf(Math.round(s.weight)) : String.format("%.1f", s.weight);
		lines.add(gray("Weight " + weight + "   Value " + s.value));
		if (s.charge >= 0.0F) {
			lines.add(gray("Charge " + Math.round(s.charge)));
		}
		lines.add(Component.literal("Skyrim item").withStyle(st -> st.withItalic(false).withColor(0x808080)));
		return lines;
	}

	private static Component gray(String text) {
		return Component.literal(text).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GRAY));
	}

	/** True for a container slot a Skyrim item can be stored in by hand (not the player's own inventory). */
	public static boolean storageSlot(Slot slot) {
		return !(slot.container instanceof Inventory);
	}

	/** Puts what Skyrim took out where the player put it; whatever doesn't fit goes back into their Skyrim. */
	private static void deposit(ServerPlayer player, Deposit deposit) {
		AbstractContainerMenu menu = player.containerMenu;
		Stored stored = deposit.stored();
		int left = Math.max(0, Math.min(deposit.count(), 100_000));
		if (menu.containerId != deposit.menu()) {
			giveBack(player, stored, left);  // the container was closed meanwhile
			return;
		}
		Slot target = deposit.slot() >= 0 && deposit.slot() < menu.slots.size() ? menu.getSlot(deposit.slot()) : null;
		boolean carried = carriedInMinecraft(stored);  // SkyCraft+ : into the player's own inventory too
		while (left > 0) {
			int size = Math.min(left, stored.maxStack());
			left -= size;
			ItemStack chunk = asStandIn(menu, target, stored) ? standIn(stored) : make(stored, size);
			if (asStandIn(menu, target, stored)) {
				left += size - 1;  // (one at a time onto the enchanting table)
			}
			if (target != null && deposit.intoBundle()) {
				ItemStack bundle = target.getItem();
				BundleContents contents = bundle.get(DataComponents.BUNDLE_CONTENTS);
				if (contents != null) {
					BundleContents.Mutable mutable = contents.asMutable();
					mutable.tryInsert(chunk);
					bundle.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
					target.setChanged();
				}
			} else if (target != null) {
				if (storageSlot(target) || carried && target.container instanceof Inventory) {
					chunk = target.safeInsert(chunk);
				}
				if (carried && !chunk.isEmpty()) {
					player.getInventory().add(chunk);  // (the rest beside it)
				}
			} else if (carried && deposit.slot() >= INVENTORY_INDEX && deposit.slot() - INVENTORY_INDEX < player.getInventory().getContainerSize()) {
				// SkyCraft+ : dragged onto that slot of the player's own inventory (on it, or beside it if it's taken).
				Inventory inventory = player.getInventory();
				int index = deposit.slot() - INVENTORY_INDEX;
				ItemStack there = inventory.getItem(index);
				if (there.isEmpty()) {
					inventory.setItem(index, chunk);
					chunk = ItemStack.EMPTY;
				} else if (ItemStack.isSameItemSameComponents(there, chunk)) {
					int moved = Math.min(chunk.getCount(), there.getMaxStackSize() - there.getCount());
					there.grow(moved);
					chunk.shrink(moved);
				}
				if (!chunk.isEmpty()) {
					inventory.add(chunk);
				}
			} else if (carried && deposit.slot() == OWN_INVENTORY) {
				player.getInventory().add(chunk);  // SkyCraft+ : shift-clicked out of the Skyrim panel
			} else {
				// Shift-click: stacks of the same first, then empty slots, as Minecraft's own does.
				for (boolean empty : new boolean[] { false, true }) {
					for (Slot slot : menu.slots) {
						if (!chunk.isEmpty() && storageSlot(slot) && (empty ? slot.getItem().isEmpty() : ItemStack.isSameItemSameComponents(slot.getItem(), chunk))) {
							chunk = slot.safeInsert(chunk);
						}
					}
				}
			}
			if (!chunk.isEmpty()) {
				giveBack(player, stored, chunk.getCount());  // no room: back into Skyrim
			}
		}
		if (deposit.forgetShortcuts()) {
			forgetShortcuts(player, stored);
		}
		menu.broadcastChanges();
	}

	/** The shortcuts for a thing Skyrim no longer has (all of it went into a container). */
	private static void forgetShortcuts(ServerPlayer player, Stored stored) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (sameThing(SkyShortcuts.ref(inventory.getItem(i)), stored)) {
				inventory.setItem(i, ItemStack.EMPTY);
			}
		}
		if (sameThing(SkyShortcuts.ref(player.containerMenu.getCarried()), stored)) {
			player.containerMenu.setCarried(ItemStack.EMPTY);
		}
	}

	private static boolean sameThing(SkyShortcuts.@Nullable Ref ref, Stored stored) {
		return ref != null && ref.localId() == stored.localId() && ref.plugin().equalsIgnoreCase(stored.plugin());
	}

	private static void giveBack(ServerPlayer player, Stored stored, int count) {
		if (count > 0 && ServerPlayNetworking.canSend(player, Give.TYPE)) {
			ServerPlayNetworking.send(player, new Give(count, stored));
		}
	}

	/**
	 * Each tick: a stored Skyrim item in a player's own inventory goes back into their Skyrim, and
	 * leaves a shortcut in its slot (unless they have one for it already).
	 */
	private static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!ServerPlayNetworking.canSend(player, Give.TYPE)) {
				continue;  // no SkyCraft there: it stays a Minecraft item
			}
			Inventory inventory = player.getInventory();
			boolean changed = false;
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				Stored stored = stored(stack);
				// SkyCraft+ : (one Skyrim has to have to use it, in a hand or an armor slot: back it goes, its shortcut there)
				boolean held = stored != null && heldInSkyrim(stored.kind()) && (i == inventory.getSelectedSlot() || i >= 36);
				if (stored == null || carriedInMinecraft(stored) && !held && stack.getEnchantments().isEmpty()) {  // (every item has an empty enchantments component)
					continue;  // SkyCraft+ : (carried in Minecraft: it stays)
				}
				giveBack(player, stored, stack.getCount());
				ItemStack shortcut = null;
				// SkyCraft+ : enchanting across the games. Minecraft enchantments it got (as its stand-in, at the
				// enchanting table) go onto its shortcut: the one it has, or a new one here.
				var enchantments = stack.get(DataComponents.ENCHANTMENTS);
				boolean enchanted = enchantments != null && !enchantments.isEmpty();
				ItemStack existing = enchanted ? shortcutFor(inventory, stored) : null;
				if (existing != null) {
					existing.set(DataComponents.ENCHANTMENTS, enchantments);
				}
				// (SkyCraft+ : a dragon soul from the creative menu is absorbed: nothing to keep a shortcut for.)
				if ((i < 36 || i == 40) && existing == null && (enchanted || held || !hasShortcut(inventory, stored)) && !SkyCreative.SOUL_PLUGIN.equals(stored.plugin())) {
					shortcut = SkyShortcuts.make(new SkyShortcuts.Make(i, stored.plugin(), stored.localId(), stored.kind(), stored.model(), stored.color(), stored.glint(),
						stored.name()));
					if (shortcut != null && enchanted) {
						shortcut.set(DataComponents.ENCHANTMENTS, enchantments);
					}
				}
				inventory.setItem(i, shortcut != null ? shortcut : ItemStack.EMPTY);
				changed = true;
				SkyCraft.LOG.info("SkyCraft: {} took {} x{} out of storage; back into their Skyrim", player.getPlainTextName(), stored.name(), stack.getCount());
			}
			if (changed) {
				player.containerMenu.broadcastChanges();
			}
		}
	}

	private static @Nullable ItemStack shortcutFor(Inventory inventory, Stored stored) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (sameThing(SkyShortcuts.ref(inventory.getItem(i)), stored)) {
				return inventory.getItem(i);
			}
		}
		return null;
	}

	private static boolean hasShortcut(Inventory inventory, Stored stored) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (sameThing(SkyShortcuts.ref(inventory.getItem(i)), stored)) {
				return true;
			}
		}
		return false;
	}

	public static void init() {
		SkyCraft.LOG.debug("SkyCraft: {} registered", ITEM_KEY.identifier());
		PayloadTypeRegistry.serverboundPlay().register(Deposit.TYPE, Deposit.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TakeCarried.TYPE, TakeCarried.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Give.TYPE, Give.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UseStored.TYPE, UseStored.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GiveAndUse.TYPE, GiveAndUse.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UseStored.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> useStored(player, payload.index(), payload.farmland()));
		});
		ServerPlayNetworking.registerGlobalReceiver(Deposit.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> deposit(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(TakeCarried.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack carried = player.containerMenu.getCarried();
				Stored stored = stored(carried);
				if (stored != null) {
					giveBack(player, stored, carried.getCount());
					player.containerMenu.setCarried(ItemStack.EMPTY);
					player.containerMenu.broadcastChanges();
				}
			});
		});
		ServerTickEvents.END_SERVER_TICK.register(SkyStorage::tick);
	}
}
