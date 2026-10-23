// SkyCraft+ : Minecraft's things at Skyrim's workbenches
package dev.skycraft.craft;

import dev.skycraft.SkyCraft;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's things lent to Skyrim's workbenches (the client's side: SkyLendingClient). While the
 * player is at Skyrim's forge, tanning rack, cooking pot, grindstone, workbench or alchemy lab, the
 * Minecraft materials with a Skyrim match (SkyMaterials) leave Minecraft's inventory and the same
 * Skyrim ones go into Skyrim's, for its recipes; at Skyrim's enchanter, the weapons and armor go in as
 * stand-ins to be enchanted (SkyCraft+ : every one in the inventory, each by its own name; setting
 * enchanterAllGear, off: the main hand's only). When the workbench's menu closes, as many as
 * Skyrim still has come back as Minecraft's (what was used is gone), and the stand-in comes back as
 * the Minecraft thing with any enchantment it got (SkyEnchanting). Kept here meanwhile.
 */
public final class SkyLending {
	private SkyLending() {
	}

	/** Client -> server: lend these inventory slots' stacks (materials), and these slots' gear (weapons, armor). */
	public record Lend(int seq, List<Integer> slots, List<Integer> gearSlots) implements CustomPacketPayload {
		public static final Type<Lend> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "lend"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Lend> CODEC = StreamCodec.of((buf, l) -> {
			buf.writeVarInt(l.seq);
			buf.writeVarInt(Math.min(l.slots.size(), 64));
			for (int i = 0; i < l.slots.size() && i < 64; i++) {
				buf.writeVarInt(l.slots.get(i));
			}
			buf.writeVarInt(Math.min(l.gearSlots.size(), 64));
			for (int i = 0; i < l.gearSlots.size() && i < 64; i++) {
				buf.writeVarInt(l.gearSlots.get(i));
			}
		}, buf -> {
			int seq = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 64);
			List<Integer> slots = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				slots.add(buf.readVarInt());
			}
			int g = Math.min(buf.readVarInt(), 64);
			List<Integer> gear = new ArrayList<>(g);
			for (int i = 0; i < g; i++) {
				gear.add(buf.readVarInt());
			}
			return new Lend(seq, slots, gear);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One lent stack: which Minecraft item, how many. */
	public record Entry(String item, int count) {
	}

	/** One lent piece of gear: its name (its own among them) and what it stands as at the enchanter (Proto.GEAR_*). */
	public record Gear(String name, int kind) {
	}

	/** Server -> client: what was lent (in the order Return answers), materials and gear. */
	public record Lent(int seq, List<Entry> entries, List<Gear> gear) implements CustomPacketPayload {
		public static final Type<Lent> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "lent"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Lent> CODEC = StreamCodec.of((buf, l) -> {
			buf.writeVarInt(l.seq);
			buf.writeVarInt(l.entries.size());
			for (Entry e : l.entries) {
				buf.writeUtf(e.item, 128);
				buf.writeVarInt(e.count);
			}
			buf.writeVarInt(l.gear.size());
			for (Gear g : l.gear) {
				buf.writeUtf(g.name, 64);
				buf.writeVarInt(g.kind);
			}
		}, buf -> {
			int seq = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 64);
			List<Entry> entries = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				entries.add(new Entry(buf.readUtf(128), buf.readVarInt()));
			}
			int g = Math.min(buf.readVarInt(), 64);
			List<Gear> gear = new ArrayList<>(g);
			for (int i = 0; i < g; i++) {
				gear.add(new Gear(buf.readUtf(64), buf.readVarInt()));
			}
			return new Lent(seq, entries, gear);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the workbench is done: this many of each lent stack come back; each piece of gear with its Skyrim enchantment (null: none). */
	public record Return(int seq, List<Integer> counts, List<SkyEnchanting.@Nullable Enchant> enchants) implements CustomPacketPayload {
		public static final Type<Return> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "lend_return"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Return> CODEC = StreamCodec.of((buf, r) -> {
			buf.writeVarInt(r.seq);
			buf.writeVarInt(r.counts.size());
			for (int c : r.counts) {
				buf.writeVarInt(Math.max(0, c));
			}
			buf.writeVarInt(r.enchants.size());
			for (SkyEnchanting.Enchant e : r.enchants) {
				buf.writeBoolean(e != null);
				if (e != null) {
					SkyEnchanting.Enchant.CODEC.encode(buf, e);
				}
			}
		}, buf -> {
			int seq = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 64);
			List<Integer> counts = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				counts.add(buf.readVarInt());
			}
			int g = Math.min(buf.readVarInt(), 64);
			List<SkyEnchanting.@Nullable Enchant> enchants = new ArrayList<>(g);
			for (int i = 0; i < g; i++) {
				enchants.add(buf.readBoolean() ? SkyEnchanting.Enchant.CODEC.decode(buf) : null);
			}
			return new Return(seq, counts, enchants);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private record Lending(int seq, List<ItemStack> stacks, List<ItemStack> gear) {
	}

	private static final Map<UUID, Lending> LENT = new HashMap<>();

	/** What a Minecraft weapon or piece of armor stands as at Skyrim's enchanter (Proto.GEAR_*), 0 for anything else. */
	public static int gearKind(ItemStack stack) {
		if (stack.isEmpty() || !stack.isDamageableItem()) {
			return 0;
		}
		var item = stack.getItem();
		var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable != null) {
			return switch (equippable.slot()) {
				case HEAD -> dev.skycraft.link.Proto.GEAR_HELMET;
				case CHEST -> dev.skycraft.link.Proto.GEAR_CHEST;
				case LEGS -> dev.skycraft.link.Proto.GEAR_LEGS;
				case FEET -> dev.skycraft.link.Proto.GEAR_BOOTS;
				case OFFHAND -> dev.skycraft.link.Proto.GEAR_SHIELD;
				default -> 0;
			};
		}
		if (stack.is(net.minecraft.tags.ItemTags.SWORDS) || item == net.minecraft.world.item.Items.TRIDENT) {
			return dev.skycraft.link.Proto.GEAR_SWORD;
		}
		if (stack.is(net.minecraft.tags.ItemTags.AXES)) {
			return dev.skycraft.link.Proto.GEAR_AXE;
		}
		if (item == net.minecraft.world.item.Items.MACE) {
			return dev.skycraft.link.Proto.GEAR_MACE;
		}
		if (stack.is(net.minecraft.tags.ItemTags.PICKAXES) || stack.is(net.minecraft.tags.ItemTags.SHOVELS) || stack.is(net.minecraft.tags.ItemTags.HOES)) {
			return dev.skycraft.link.Proto.GEAR_PICKAXE;
		}
		if (item == net.minecraft.world.item.Items.BOW) {
			return dev.skycraft.link.Proto.GEAR_BOW;
		}
		if (item == net.minecraft.world.item.Items.CROSSBOW) {
			return dev.skycraft.link.Proto.GEAR_CROSSBOW;
		}
		if (item == net.minecraft.world.item.Items.SHIELD) {
			return dev.skycraft.link.Proto.GEAR_SHIELD;
		}
		return 0;
	}

	private static void lend(ServerPlayer player, Lend lend) {
		giveBack(player, LENT.remove(player.getUUID()), null, null);  // (one left over: all of it back)
		Inventory inventory = player.getInventory();
		List<ItemStack> stacks = new ArrayList<>();
		List<Entry> entries = new ArrayList<>();
		for (int slot : lend.slots()) {
			if (slot < 0 || slot >= inventory.getContainerSize() || lend.gearSlots().contains(slot)) {
				continue;
			}
			ItemStack stack = inventory.getItem(slot);
			if (!SkyMaterials.hasSkyrimMatch(stack)) {
				continue;
			}
			stacks.add(stack.copy());
			entries.add(new Entry(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
			inventory.setItem(slot, ItemStack.EMPTY);
		}
		List<ItemStack> gear = new ArrayList<>();
		List<Gear> lentGear = new ArrayList<>();
		java.util.Set<String> names = new java.util.HashSet<>();
		for (int slot : lend.gearSlots()) {
			if (slot < 0 || slot >= inventory.getContainerSize()) {
				continue;
			}
			ItemStack held = inventory.getItem(slot);
			int kind = gearKind(held);
			if (kind == 0 || SkyEnchanting.enchant(held) != null) {
				continue;
			}
			// Each by a name of its own in Skyrim (two of a kind: "2"), so the one enchanted is known.
			String name = held.getHoverName().getString();
			name = name.length() > 44 ? name.substring(0, 44) : name;
			String unique = name;
			for (int n = 2; !names.add(unique); n++) {
				unique = name + " " + n;
			}
			gear.add(held.copy());
			lentGear.add(new Gear(unique, kind));
			inventory.setItem(slot, ItemStack.EMPTY);
		}
		if (stacks.isEmpty() && gear.isEmpty()) {
			return;
		}
		LENT.put(player.getUUID(), new Lending(lend.seq(), stacks, gear));
		player.containerMenu.broadcastChanges();
		ServerPlayNetworking.send(player, new Lent(lend.seq(), entries, lentGear));
		SkyCraft.LOG.info("SkyCraft: {} lent {} stack(s){} to a Skyrim workbench", player.getPlainTextName(), stacks.size(), gear.isEmpty() ? "" : " and " + gear.size() + " piece(s) of gear");
	}

	private static void giveBack(ServerPlayer player, @Nullable Lending lending, @Nullable List<Integer> counts, @Nullable List<SkyEnchanting.@Nullable Enchant> enchants) {
		if (lending == null) {
			return;
		}
		int back = 0;
		for (int i = 0; i < lending.stacks().size(); i++) {
			ItemStack stack = lending.stacks().get(i);
			int count = counts == null ? stack.getCount() : i < counts.size() ? Math.min(counts.get(i), stack.getCount()) : 0;
			if (count > 0) {
				ItemStack copy = stack.copyWithCount(count);
				back += count;
				player.getInventory().placeItemBackInInventory(copy, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		int enchanted = 0;
		for (int i = 0; i < lending.gear().size(); i++) {
			ItemStack gear = lending.gear().get(i);
			SkyEnchanting.Enchant enchant = enchants != null && i < enchants.size() ? enchants.get(i) : null;
			if (enchant != null) {
				SkyEnchanting.apply(gear, enchant);
				enchanted++;
			}
			player.getInventory().placeItemBackInInventory(gear, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		player.containerMenu.broadcastChanges();
		SkyCraft.LOG.info("SkyCraft: {} got {} lent item(s) and {} piece(s) of gear back from a Skyrim workbench ({} enchanted)", player.getPlainTextName(), back,
			lending.gear().size(), enchanted);
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Lend.TYPE, Lend.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Lent.TYPE, Lent.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Return.TYPE, Return.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Lend.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> lend(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(Return.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				Lending lending = LENT.get(player.getUUID());
				if (lending != null && lending.seq() == payload.seq()) {
					LENT.remove(player.getUUID());
					giveBack(player, lending, payload.counts(), payload.enchants());
				}
			});
		});
		// Gone before the workbench was done: Skyrim kept the Skyrim copies, so the materials don't come back
		// (nothing twice); the gear does (its stand-in was only an iron one).
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Lending lending = LENT.remove(handler.getPlayer().getUUID());
			if (lending != null) {
				for (ItemStack gear : lending.gear()) {
					handler.getPlayer().getInventory().placeItemBackInInventory(gear, net.minecraft.util.Prediction.SERVER_ONLY);
				}
			}
		});
	}
}
