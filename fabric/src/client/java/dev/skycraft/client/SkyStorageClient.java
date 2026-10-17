// SkyCraft+ : Skyrim items in Minecraft's containers
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.mixin.ContainerScreenAccessor;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyShortcuts;
import dev.skycraft.net.SkyStorage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of SkyStorage. Putting a Skyrim thing in a container (a shortcut clicked onto
 * a chest's slot or a bundle, shift-clicked in a chest screen, or dragged there from the Skyrim
 * panel) first asks this player's Skyrim to take it out (it says which copies went, or why not),
 * then tells the server to put those there. Taking one back into the inventory, the server sends
 * it here and it goes back into Skyrim.
 */
public final class SkyStorageClient {
	private SkyStorageClient() {
	}

	/** Where Skyrim's answer goes: the menu slot (-1: anywhere in the container), into a bundle there, and the stand-in's look. */
	private record Pending(int menu, int slot, boolean intoBundle, boolean forgetShortcuts, String model, int color, boolean glint, long at) {
	}

	private static final Map<Integer, Pending> PENDING = new HashMap<>();
	private static final Map<Integer, Long> GIVES = new HashMap<>();
	private static final ArrayDeque<SkyStorage.Give> TO_GIVE = new ArrayDeque<>();
	// Skyrim's pictures of stored things: "plugin:id" -> the picture's key (Skyrim's form id), and when each was last asked for.
	private static final Map<String, Integer> PICTURE_KEYS = new HashMap<>();
	private static final Map<String, Long> ASKED = new HashMap<>();
	private static final Map<Integer, String> PICTURE_ASKS = new HashMap<>();
	private static int asksThisTick;

	private static @Nullable Boolean enabled;

	/** Setting: skyrimStorage in config/skycraft.properties (false: Skyrim items can't be put in Minecraft's containers; ones already there still come back). */
	public static boolean enabled() {
		if (enabled == null) {
			java.util.Properties properties = new java.util.Properties();
			java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
			if (java.nio.file.Files.exists(config)) {
				try (java.io.Reader in = java.nio.file.Files.newBufferedReader(config)) {
					properties.load(in);
				} catch (java.io.IOException e) {
					SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
				}
			}
			enabled = Boolean.parseBoolean(properties.getProperty("skyrimStorage", "true").trim());
			SkyCraft.LOG.info("SkyCraft: Skyrim items in Minecraft's containers: {}", enabled ? "on" : "off");
		}
		return enabled;
	}

	/** Containers a Skyrim item is stored in by hand: chests, barrels, ender chests, shulker boxes, hoppers, droppers, dispensers. */
	public static boolean storageMenu(AbstractContainerMenu menu) {
		return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
	}

	public static boolean storageScreen(@Nullable Screen screen) {
		return enabled() && screen instanceof AbstractContainerScreen<?> container && !(screen instanceof CreativeModeInventoryScreen) && storageMenu(container.getMenu());
	}

	/** Asks Skyrim to take count of item out, for slot of the open menu (-1: anywhere in its container), or into the bundle in that slot. */
	static void store(SkyLink.SkyItem item, int count, int slot, boolean intoBundle) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!enabled() || item.kind() >= Proto.SKY_SPELL) {
			return;
		}
		if (item.has(Proto.ITEM_QUEST)) {
			tell(minecraft, "Quest items stay in Skyrim");
			return;
		}
		if (item.plugin().isEmpty()) {
			tell(minecraft, "Things made in this game (your own potions and the like) stay in Skyrim");
			return;
		}
		if (!ClientPlayNetworking.canSend(SkyStorage.Deposit.TYPE)) {
			tell(minecraft, "This server doesn't have SkyCraft+'s Skyrim storage");
			return;
		}
		int id = SkyLink.pushStoreRequest(Proto.STORE_TAKE, item.formId(), Math.max(1, Math.min(count, item.count())), 1.0F, -1.0F, 0, item.localId(), item.plugin());
		if (id == 0) {
			return;
		}
		ItemStack icon = SkyIcons.stack(item);
		var contents = icon.get(DataComponents.POTION_CONTENTS);
		int color = contents != null ? contents.customColor().orElse(0) : 0;
		boolean glint = Boolean.TRUE.equals(icon.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
		PENDING.put(id, new Pending(minecraft.player.containerMenu.containerId, slot, intoBundle, count >= item.count(), BuiltInRegistries.ITEM.getKey(icon.getItem()).toString(), color, glint, System.nanoTime()));
	}

	/** Draws Skyrim's picture of a stored thing (GuiGraphicsShortcutMixin), asking Skyrim to draw it now and then; false if there's none yet. */
	public static boolean drawPicture(net.minecraft.client.gui.GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		SkyStorage.Stored stored = SkyStorage.stored(stack);
		if (stored == null || stored.plugin().isEmpty()) {
			return false;
		}
		String key = stored.plugin().toLowerCase(java.util.Locale.ROOT) + ":" + stored.localId();
		long now = System.nanoTime();
		Long asked = ASKED.get(key);
		if ((asked == null || now - asked > 20_000_000_000L) && asksThisTick < 8 && SkyLink.active()) {
			int id = SkyLink.pushStoreRequest(Proto.STORE_PICTURE, 0, 0, 1.0F, -1.0F, 0, stored.localId(), stored.plugin());
			if (id != 0) {
				ASKED.put(key, now);
				PICTURE_ASKS.put(id, key);
				asksThisTick++;
			}
		}
		Integer formId = PICTURE_KEYS.get(key);
		return formId != null && SkyIconTextures.draw(graphics, formId, x, y);
	}

	/** The stored item on the cursor was put on the Skyrim panel: back into Skyrim, no shortcut. */
	static void takeCarried() {
		if (ClientPlayNetworking.canSend(SkyStorage.TakeCarried.TYPE)) {
			ClientPlayNetworking.send(new SkyStorage.TakeCarried(0));
		}
	}

	private static void tell(Minecraft minecraft, String text) {
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Each tick: Skyrim's answers turned into deposits, and things taken out of containers given to Skyrim. */
	static void tick(Minecraft minecraft) {
		asksThisTick = 0;
		if (!SkyLink.active()) {
			PICTURE_KEYS.clear();
			ASKED.clear();
			return;
		}
		while (!TO_GIVE.isEmpty()) {
			SkyStorage.Give give = TO_GIVE.peek();
			SkyStorage.Stored s = give.stored();
			int id = SkyLink.pushStoreRequest(Proto.STORE_GIVE, 0, give.count(), s.health(), s.charge(), s.soul(), s.localId(), s.plugin());
			if (id == 0) {
				break;  // Skyrim's queue is full: next tick
			}
			GIVES.put(id, System.nanoTime());
			TO_GIVE.poll();
		}
		for (SkyLink.StoreResult result : SkyLink.popStoreResults()) {
			String pictureOf = PICTURE_ASKS.remove(result.id());
			if (pictureOf != null) {
				if (result.status() == Proto.STORE_OK) {
					PICTURE_KEYS.put(pictureOf, result.formId());
				}
				continue;
			}
			if (GIVES.remove(result.id()) != null) {
				if (result.status() != Proto.STORE_OK) {
					tell(minecraft, "A Skyrim item from storage couldn't be found in this Skyrim (is its mod loaded?)");
				}
				continue;
			}
			Pending pending = PENDING.get(result.id());
			if (pending == null) {
				continue;
			}
			switch (result.status()) {
				case Proto.STORE_OK -> {
					SkyStorage.Stored stored = new SkyStorage.Stored(result.plugin(), result.localId(), result.kind(), result.sub(), result.material(), result.flags(),
						result.health(), result.charge(), result.soul(), result.weight(), result.value(), result.stat(), result.stat2(), result.name(), pending.model(),
						pending.color(), pending.glint());
					ClientPlayNetworking.send(new SkyStorage.Deposit(pending.menu(), pending.slot(), pending.intoBundle(), pending.forgetShortcuts(), result.count(), stored));
				}
				case Proto.STORE_QUEST -> tell(minecraft, "Quest items stay in Skyrim");
				case Proto.STORE_MADE_HERE -> tell(minecraft, "Things you made or enchanted yourself stay in Skyrim");
				default -> {
				}
			}
		}
		long old = System.nanoTime() - 30_000_000_000L;
		for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext();) {
			if (it.next().at() < old) {
				it.remove();
			}
		}
		GIVES.values().removeIf(at -> at < old);
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SkyStorage.Give.TYPE, (payload, context) -> {
			TO_GIVE.add(payload);
			SkyCraft.LOG.info("SkyCraft: {} x{} out of storage, back into Skyrim", payload.stored().name(), payload.count());
		});
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			if (SkyStorage.isStored(stack)) {
				lines.add(Component.literal("Take it into your inventory to put it back in Skyrim").withStyle(ChatFormatting.DARK_GRAY));
			}
		});
		// A Skyrim shortcut put into a container (or a bundle) stores its Skyrim thing there.
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> container) || screen instanceof CreativeModeInventoryScreen || !SkyLink.active() || !enabled()) {
				return;
			}
			var accessor = (ContainerScreenAccessor) container;
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				AbstractContainerMenu menu = container.getMenu();
				Slot hovered = accessor.skycraft$hoveredSlot();
				if (hovered == null) {
					return true;
				}
				ItemStack carried = menu.getCarried();
				boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
				if (SkyShortcuts.isShortcut(carried)) {
					SkyLink.SkyItem item = SkyShortcutsClient.resolve(carried);
					if (item == null || item.kind() >= Proto.SKY_SPELL) {
						return true;
					}
					if (right && hovered.getItem().has(DataComponents.BUNDLE_CONTENTS)) {
						store(item, item.count(), hovered.index, true);
						return false;
					}
					if (storageMenu(menu) && SkyStorage.storageSlot(hovered)) {
						store(item, right ? 1 : item.count(), hovered.index, false);
						return false;
					}
					return true;
				}
				if (event.hasShiftDown() && storageMenu(menu) && hovered.container instanceof Inventory && SkyShortcuts.isShortcut(hovered.getItem())) {
					SkyLink.SkyItem item = SkyShortcutsClient.resolve(hovered.getItem());
					if (item != null && item.kind() < Proto.SKY_SPELL) {
						store(item, item.count(), -1, false);
						return false;
					}
				}
				return true;
			});
		});
	}
}
