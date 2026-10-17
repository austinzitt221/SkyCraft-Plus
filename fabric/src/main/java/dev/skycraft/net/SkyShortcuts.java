// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim shortcuts in Minecraft's inventory: a Skyrim item, spell, shout or power dragged from the
 * Skyrim panels by Minecraft's inventory into a slot of it. The thing itself stays in Skyrim; the
 * shortcut is a sheet of paper that looks like its stand-in and says which Skyrim thing it is (by
 * plugin and id, so it survives load-order changes). Each player's client shows its live count and
 * equips or uses it in their own Skyrim. It never leaves its player's inventory: containers refuse
 * it (SlotShortcutMixin), and thrown out it drops the real thing in Skyrim instead of an item in
 * Minecraft (LivingEntityShortcutMixin).
 */
public final class SkyShortcuts {
	private SkyShortcuts() {
	}

	private static final String TAG = "skycraft_shortcut";

	/** What a shortcut stands for: Skyrim's SkyItem plugin and localId, and its SkyItemKind. */
	public record Ref(String plugin, int localId, int kind) {
	}

	/** Client -> server: put a shortcut in a slot of the player's own inventory (0-35, 40 the off hand). */
	public record Make(int slot, String plugin, int localId, int kind, String model, int color, boolean glint, String name) implements CustomPacketPayload {
		public static final Type<Make> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "shortcut_make"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Make> CODEC = StreamCodec.of((buf, make) -> {
			buf.writeVarInt(make.slot);
			buf.writeUtf(make.plugin, 64);
			buf.writeInt(make.localId);
			buf.writeVarInt(make.kind);
			buf.writeUtf(make.model, 128);
			buf.writeInt(make.color);
			buf.writeBoolean(make.glint);
			buf.writeUtf(make.name, 128);
		}, buf -> new Make(buf.readVarInt(), buf.readUtf(64), buf.readInt(), buf.readVarInt(), buf.readUtf(128), buf.readInt(), buf.readBoolean(), buf.readUtf(128)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the shortcut held on the cursor was put back on the Skyrim panels: it goes. */
	public record Forget(int unused) implements CustomPacketPayload {
		public static final Type<Forget> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "shortcut_forget"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Forget> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Forget::unused, Forget::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: the player threw a shortcut out: their Skyrim drops the real thing. */
	public record Dropped(String plugin, int localId) implements CustomPacketPayload {
		public static final Type<Dropped> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "shortcut_dropped"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Dropped> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Dropped::plugin, ByteBufCodecs.INT, Dropped::localId, Dropped::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static boolean isShortcut(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.PAPER)) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(TAG);
	}

	public static @Nullable Ref ref(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.PAPER)) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag().getCompoundOrEmpty(TAG);
		if (tag.isEmpty()) {
			return null;
		}
		return new Ref(tag.getStringOr("plugin", ""), tag.getIntOr("id", 0), tag.getIntOr("kind", 0));
	}

	/** The shortcut stack: paper drawn as the stand-in (model, a potion's colour, the glint), named after the Skyrim thing. */
	static @Nullable ItemStack make(Make make) {
		Identifier modelId = Identifier.tryParse(make.model);
		var item = modelId == null ? null : BuiltInRegistries.ITEM.getOptional(modelId).orElse(null);
		if (item == null) {
			return null;
		}
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag tag = new CompoundTag();
		tag.putString("plugin", make.plugin);
		tag.putInt("id", make.localId);
		tag.putInt("kind", make.kind);
		CompoundTag root = new CompoundTag();
		root.put(TAG, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		Identifier model = item.components().get(DataComponents.ITEM_MODEL);
		if (model != null) {
			stack.set(DataComponents.ITEM_MODEL, model);
		}
		if (make.color != 0) {
			stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(make.color), List.of(), Optional.empty()));
			stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.POTION_CONTENTS, true));
		}
		if (make.glint) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(make.name).withStyle(s -> s.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Skyrim shortcut").withStyle(s -> s.withItalic(false).withColor(0x808080)))));
		return stack;
	}

	/** A shortcut thrown out (LivingEntityShortcutMixin): its player's Skyrim drops the thing. */
	public static void thrownOut(ServerPlayer player, ItemStack stack) {
		Ref ref = ref(stack);
		if (ref != null && ServerPlayNetworking.canSend(player, Dropped.TYPE)) {
			ServerPlayNetworking.send(player, new Dropped(ref.plugin(), ref.localId()));
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Make.TYPE, Make.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Forget.TYPE, Forget.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Dropped.TYPE, Dropped.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Make.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				var inventory = player.getInventory();
				int slot = payload.slot();
				if (!(slot >= 0 && slot < 36 || slot == 40)) {
					return;
				}
				ItemStack there = inventory.getItem(slot);
				if (!there.isEmpty() && !isShortcut(there)) {
					return;  // a Minecraft item is there: it isn't replaced
				}
				ItemStack stack = make(payload);
				if (stack != null) {
					inventory.setItem(slot, stack);
					player.containerMenu.broadcastChanges();
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(Forget.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				if (isShortcut(player.containerMenu.getCarried())) {
					player.containerMenu.setCarried(ItemStack.EMPTY);
					player.containerMenu.broadcastChanges();
				}
			});
		});
	}
}
