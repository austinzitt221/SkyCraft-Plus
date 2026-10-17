// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyShortcuts;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of Skyrim shortcuts (SkyShortcuts): each one's Skyrim thing in this player's own
 * Skyrim, its live count and tooltip, and what it does. Picked on the hotbar (or put in the off
 * hand) a weapon, spell, scroll or torch goes into that hand, a shout or power becomes the voice's;
 * right click drinks a potion, eats food, puts apparel on or takes it off.
 */
public final class SkyShortcutsClient {
	private SkyShortcutsClient() {
	}

	private static SkyLink.@Nullable SkyInventory inventory;
	private static int generation = Integer.MIN_VALUE;
	private static boolean anyShortcuts;
	/** What a hand's shortcut slot held last tick, and the Skyrim thing it put in that hand (if any). */
	private static final class Hand {
		String key = "";
		int slot = -1, formId;
		boolean holds;
	}

	private static final Hand MAIN = new Hand(), OFF = new Hand();

	/** This player's Skyrim's list, read again when Skyrim changed it (at most once a tick). */
	static SkyLink.@Nullable SkyInventory inventory() {
		return inventory;
	}

	/** The Skyrim thing a shortcut stands for, or null (not a shortcut, or no longer in Skyrim). */
	public static SkyLink.@Nullable SkyItem resolve(ItemStack stack) {
		SkyShortcuts.Ref ref = SkyShortcuts.ref(stack);
		SkyLink.SkyInventory now = inventory;
		if (ref == null || now == null) {
			return null;
		}
		for (SkyLink.SkyItem item : now.items()) {
			if (item.is(ref.plugin(), ref.localId())) {
				return item;
			}
		}
		return null;
	}

	/** True while the player has shortcuts: Skyrim keeps its list current for their counts. */
	static boolean wanted() {
		return anyShortcuts;
	}

	/** The count drawn on a shortcut (GuiGraphicsShortcutMixin): Skyrim's, for things there's more than one of. */
	public static @Nullable String countText(ItemStack stack) {
		SkyLink.SkyItem item = resolve(stack);
		return item != null && item.count() > 1 && item.kind() < Proto.SKY_SPELL ? String.valueOf(item.count()) : null;
	}

	/** Draws Skyrim's picture of a shortcut's thing in place of its stand-in (GuiGraphicsShortcutMixin); false if there's none. */
	public static boolean drawPicture(net.minecraft.client.gui.GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		SkyLink.SkyItem item = resolve(stack);
		return item != null && SkyIconTextures.draw(graphics, item.formId(), x, y);
	}

	/** A shortcut whose thing Skyrim no longer has is drawn greyed out. */
	public static boolean gone(ItemStack stack) {
		return SkyShortcuts.isShortcut(stack) && inventory != null && resolve(stack) == null;
	}

	/** The shortcut for this Skyrim thing, made by the server in slot (0-35, 40 the off hand). */
	static void make(SkyLink.SkyItem item, int slot) {
		if (!ClientPlayNetworking.canSend(SkyShortcuts.Make.TYPE)) {
			SkyCraft.LOG.info("SkyCraft: Skyrim shortcuts: the server doesn't have SkyCraft");
			return;
		}
		ItemStack icon = SkyIcons.stack(item);
		var contents = icon.get(DataComponents.POTION_CONTENTS);
		int color = contents != null ? contents.customColor().orElse(0) : 0;
		boolean glint = Boolean.TRUE.equals(icon.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
		String name = item.name().length() > 100 ? item.name().substring(0, 100) : item.name();
		ClientPlayNetworking.send(new SkyShortcuts.Make(slot, item.plugin(), item.localId(), item.kind(), BuiltInRegistries.ITEM.getKey(icon.getItem()).toString(),
			color, glint, name));
	}

	/** The shortcut on the cursor was put back on the Skyrim panels. */
	static void forgetCarried() {
		if (ClientPlayNetworking.canSend(SkyShortcuts.Forget.TYPE)) {
			ClientPlayNetworking.send(new SkyShortcuts.Forget(0));
		}
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SkyShortcuts.Dropped.TYPE, (payload, context) -> {
			SkyLink.SkyInventory now = SkyLink.readSkyInventory();
			if (now == null) {
				return;
			}
			for (SkyLink.SkyItem item : now.items()) {
				if (item.is(payload.plugin(), payload.localId())) {
					if (item.kind() < Proto.SKY_SPELL) {
						SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_DROP);
					}
					return;
				}
			}
		});
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			if (!SkyShortcuts.isShortcut(stack)) {
				return;
			}
			SkyLink.SkyItem item = resolve(stack);
			if (item == null) {
				if (inventory != null) {
					lines.add(Component.literal("Not in your Skyrim inventory").withStyle(ChatFormatting.RED));
				}
				return;
			}
			List<Component> skyrim = SkyInventoryPanels.tooltip(item, false);
			lines.clear();
			lines.addAll(skyrim);
			String hint = useHint(item);
			if (hint != null) {
				lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
			}
			lines.add(Component.literal("Skyrim shortcut: Q drops the real thing").withStyle(ChatFormatting.DARK_GRAY));
		});
	}

	private static @Nullable String useHint(SkyLink.SkyItem item) {
		return switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_SPELL, Proto.SKY_SCROLL -> "Pick it on the hotbar to hold it (off hand: left hand)";
			case Proto.SKY_SHOUT, Proto.SKY_POWER -> "Pick it on the hotbar to make it your shout";
			case Proto.SKY_POTION -> "Right click: drink";
			case Proto.SKY_FOOD, Proto.SKY_INGREDIENT -> "Right click: eat";
			case Proto.SKY_APPAREL -> "Right click: put on or take off";
			case Proto.SKY_AMMO -> "Right click: ready";
			case Proto.SKY_MISC -> item.sub() == 2 ? "Pick it on the hotbar to hold it" : null;
			default -> null;
		};
	}

	/** Goes into a hand when picked: weapons, spells, scrolls, torches; shouts and powers become the voice's. */
	static boolean equips(SkyLink.@Nullable SkyItem item) {
		if (item == null) {
			return false;
		}
		return switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_SPELL, Proto.SKY_SCROLL, Proto.SKY_SHOUT, Proto.SKY_POWER -> true;
			case Proto.SKY_MISC -> item.sub() == 2;
			case Proto.SKY_APPAREL -> item.sub() == Proto.A_SHIELD;
			default -> false;
		};
	}

	/** For SkyClient.handsCode: a shortcut that equips is Skyrim's hand, not a Minecraft item in it. */
	static boolean equipsShortcut(ItemStack stack) {
		return SkyShortcuts.isShortcut(stack) && equips(resolve(stack));
	}

	/** A shortcut right click uses (MinecraftShortcutUseMixin), or null. */
	public static boolean use(LocalPlayer player) {
		for (ItemStack stack : new ItemStack[] { player.getMainHandItem(), player.getOffhandItem() }) {
			if (!SkyShortcuts.isShortcut(stack)) {
				continue;
			}
			SkyLink.SkyItem item = resolve(stack);
			if (item == null) {
				continue;
			}
			int action = switch (item.kind()) {
				case Proto.SKY_POTION, Proto.SKY_FOOD, Proto.SKY_INGREDIENT -> Proto.SKY_ACT_USE;
				case Proto.SKY_APPAREL, Proto.SKY_AMMO -> Proto.SKY_ACT_RIGHT;
				case Proto.SKY_BOOK -> item.sub() == 1 ? Proto.SKY_ACT_USE : 0;
				default -> 0;
			};
			if (action != 0) {
				SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, action);
				return true;
			}
		}
		return false;
	}

	/** True if right click has a shortcut to use: it goes to Minecraft even with a Skyrim left hand (MC_USE_TARGET). */
	static boolean usableInHand(LocalPlayer player) {
		for (ItemStack stack : new ItemStack[] { player.getMainHandItem(), player.getOffhandItem() }) {
			SkyLink.SkyItem item = SkyShortcuts.isShortcut(stack) ? resolve(stack) : null;
			if (item != null && (item.kind() >= Proto.SKY_APPAREL && item.kind() <= Proto.SKY_INGREDIENT || item.kind() == Proto.SKY_AMMO
				|| item.kind() == Proto.SKY_BOOK && item.sub() == 1)) {
				return true;
			}
		}
		return false;
	}

	/** Each tick: Skyrim's list read again if it changed, and a shortcut newly picked or put in the off hand equipped. */
	static void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || !SkyLink.active()) {
			anyShortcuts = false;
			inventory = null;
			generation = Integer.MIN_VALUE;
			return;
		}
		boolean any = false;
		var items = player.getInventory();
		for (int i = 0; i < items.getContainerSize() && !any; i++) {
			any = SkyShortcuts.isShortcut(items.getItem(i));
		}
		anyShortcuts = any || SkyInventoryPanels.wantsSkyInventory(minecraft.gui.screen());
		if (anyShortcuts) {
			SkyIconTextures.tick(minecraft);
		}
		int now = SkyLink.skyInventoryGeneration();
		if (anyShortcuts && now != generation) {
			SkyLink.SkyInventory read = SkyLink.readSkyInventory();
			if (read != null) {
				inventory = read;
				generation = read.generation();
			}
		}
		follow(MAIN, player.getMainHandItem(), player.getInventory().getSelectedSlot(), false);
		follow(OFF, player.getOffhandItem(), 40, true);
	}

	/**
	 * A shortcut newly in a hand's slot (picked on the hotbar, put in the off hand) equips its Skyrim
	 * thing in that hand. One that put its thing there and then left the slot (taken out, swapped for
	 * something else; not another hotbar slot picked) takes it out of the hand again.
	 */
	private static void follow(Hand hand, ItemStack stack, int slot, boolean offHand) {
		SkyShortcuts.Ref ref = SkyShortcuts.ref(stack);
		String key = ref == null ? "" : ref.plugin() + ":" + ref.localId();
		if (key.equals(hand.key) && slot == hand.slot) {
			return;
		}
		SkyLink.SkyItem item = ref == null ? null : resolve(stack);
		if (ref != null && item == null && inventory == null) {
			return;  // Skyrim's list isn't here yet: tried again next tick
		}
		boolean equips = equips(item);
		if (hand.holds && slot == hand.slot && !equips) {
			SkyLink.pushEvent(Proto.EV_SKY_ITEM, hand.formId, 0.0F, 0.0F, 0.0F, 0.0F, offHand ? Proto.SKY_ACT_UNEQUIP_LEFT : Proto.SKY_ACT_UNEQUIP_RIGHT);
		}
		hand.key = key;
		hand.slot = slot;
		hand.holds = false;
		hand.formId = 0;
		if (equips) {
			SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, offHand ? Proto.SKY_ACT_EQUIP_LEFT : Proto.SKY_ACT_EQUIP_RIGHT);
			hand.holds = item.kind() != Proto.SKY_SHOUT && item.kind() != Proto.SKY_POWER;
			hand.formId = item.formId();
		}
	}
}
