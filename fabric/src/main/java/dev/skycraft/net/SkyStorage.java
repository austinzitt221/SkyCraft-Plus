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
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, new Item(new Item.Properties().setId(ITEM_KEY)));

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

	public static boolean isStored(ItemStack stack) {
		return !stack.isEmpty() && stack.is(ITEM);
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
		CompoundTag root = new CompoundTag();
		root.put(TAG, tag);
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
		stack.set(DataComponents.MAX_STACK_SIZE, s.maxStack());
		String tier = s.tier();
		boolean enchanted = (s.flags & Proto.ITEM_ENCHANTED) != 0;
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(s.name + (tier != null ? " (" + tier + ")" : ""))
			.withStyle(st -> st.withItalic(false).withColor(enchanted ? ChatFormatting.AQUA : ChatFormatting.WHITE)));
		stack.set(DataComponents.LORE, new ItemLore(lore(s)));
		return stack;
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
		while (left > 0) {
			int size = Math.min(left, stored.maxStack());
			left -= size;
			ItemStack chunk = make(stored, size);
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
				if (storageSlot(target)) {
					chunk = target.safeInsert(chunk);
				}
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
				if (stored == null) {
					continue;
				}
				giveBack(player, stored, stack.getCount());
				ItemStack shortcut = null;
				if ((i < 36 || i == 40) && !hasShortcut(inventory, stored)) {
					shortcut = SkyShortcuts.make(new SkyShortcuts.Make(i, stored.plugin(), stored.localId(), stored.kind(), stored.model(), stored.color(), stored.glint(),
						stored.name()));
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
