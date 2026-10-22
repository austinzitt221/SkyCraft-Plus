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
	private static final java.util.Set<Integer> USE_AFTER = new java.util.HashSet<>();  // SkyCraft+ : gives to use once Skyrim has them
	private static final java.util.Map<SkyStorage.Give, Long> PLANT_FOR = new java.util.IdentityHashMap<>();  // SkyCraft+ : a give to plant on this farmland
	private static final Map<Integer, Long> PLANT_AFTER = new HashMap<>();
	private static final ArrayDeque<SkyStorage.Give> TO_GIVE = new ArrayDeque<>();
	// Skyrim's pictures of stored things: "plugin:id" -> the picture's key (Skyrim's form id), and when each was last asked for.
	private static final Map<String, Integer> PICTURE_KEYS = new HashMap<>();
	private static final Map<String, Long> ASKED = new HashMap<>();
	private static final Map<Integer, String> PICTURE_ASKS = new HashMap<>();
	private static int asksThisTick;

	private static @Nullable Boolean enabled;
	// SkyCraft+ : the settings screen. Read again after a change.
	static {
		dev.skycraft.SkySettings.onChange(() -> enabled = null);
	}

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
		return enabled() && screen instanceof AbstractContainerScreen<?> container && !(screen instanceof CreativeModeInventoryScreen)
			&& (storageMenu(container.getMenu()) || craftingMenu(container.getMenu()) && !(container.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu));
	}

	/**
	 * SkyCraft+ : crafting across the games. Menus whose input slots a Skyrim material goes in to be made into
	 * something (SkyMaterials): crafting tables and the inventory's own grid, furnaces, the conversion table.
	 */
	public static boolean craftingMenu(AbstractContainerMenu menu) {
		return menu instanceof net.minecraft.world.inventory.CraftingMenu || menu instanceof net.minecraft.world.inventory.InventoryMenu
			|| menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu || menu instanceof dev.skycraft.craft.SkyConversionTable.Menu
			|| menu instanceof net.minecraft.world.inventory.EnchantmentMenu;  // (a Skyrim weapon goes on as its stand-in: SkyStorage.standIn)
	}

	/** Whether a Skyrim thing put on this slot of this menu is stored there: a container's, or a crafting input's. */
	public static boolean storesInto(AbstractContainerMenu menu, Slot slot) {
		if (storageMenu(menu)) {
			return SkyStorage.storageSlot(slot);
		}
		return craftingMenu(menu) && !(slot.container instanceof Inventory) && !(slot.container instanceof net.minecraft.world.inventory.ResultContainer);
	}

	/** SkyCraft+ : Skyrim items carried in Minecraft. Whether this thing, put in the player's own inventory, leaves Skyrim for it (SkyStorage.carriedInMinecraft). */
	static String carriesWhy(SkyLink.SkyItem item) {
		return !enabled() ? "a shortcut (skyrimStorage is off)" : item.plugin().isEmpty() ? "a shortcut (made in this game)"
			: !SkyStorage.carriedInMinecraft(item.kind(), item.sub(), item.flags(), item.name()) ? "a shortcut (not a thing carried in Minecraft, or skyrimItemsInMinecraft off)"
			: !ClientPlayNetworking.canSend(SkyStorage.Deposit.TYPE) ? "a shortcut (the server can't store it)" : "out of Skyrim into Minecraft";
	}

	static boolean carries(SkyLink.SkyItem item) {
		return enabled() && !item.plugin().isEmpty() && SkyStorage.carriedInMinecraft(item.kind(), item.sub(), item.flags(), item.name())
			&& ClientPlayNetworking.canSend(SkyStorage.Deposit.TYPE);
	}

	/** SkyCraft+ : Skyrim items carried in Minecraft. A stored food, potion or ingredient in a hand used: Skyrim gets one and uses it (true), or it isn't one (false). */
	static boolean useStored(net.minecraft.client.player.LocalPlayer player) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult block && block.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
			&& minecraft.level != null && usable(minecraft.level, block.getBlockPos())) {
			return false;  // (a chest, door, bed... in front: that, as Minecraft does with food in hand)
		}
		for (var hand : net.minecraft.world.InteractionHand.values()) {
			SkyStorage.Stored stored = SkyStorage.stored(player.getItemInHand(hand));
			long farmland = farmland(minecraft, stored);
			if (stored == null || farmland == SkyStorage.UseStored.NO_FARMLAND && !SkyStorage.usedFromHand(stored.kind())
				|| hand == net.minecraft.world.InteractionHand.OFF_HAND && !player.getMainHandItem().isEmpty() || !ClientPlayNetworking.canSend(SkyStorage.UseStored.TYPE)) {
				continue;
			}
			// To an animal under the crosshair: fed to it (SkyFeeding uses up the one in hand).
			int foods = dev.skycraft.SkySettings.flag("skyrimFoodForAnimals", true) ? dev.skycraft.combat.SkyFoods.ofSkyrim(stored.kind(), stored.name()) : 0;
			if (foods != 0 && minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
				&& (hit.getEntity() instanceof net.minecraft.world.entity.animal.Animal || hit.getEntity() instanceof dev.skycraft.combat.SkyrimActorEntity)) {
				ClientPlayNetworking.send(new dev.skycraft.net.SkyFeeding.Feed(hit.getEntity().getId(), foods, stored.plugin(), stored.localId()));
				return true;
			}
			if (farmland == SkyStorage.UseStored.NO_FARMLAND && player.getItemInHand(hand).has(net.minecraft.core.component.DataComponents.CONSUMABLE)) {
				return false;  // SkyCraft+ : (eaten or drunk as Minecraft's food is, held: SkyStorage.StoredItem)
			}
			ClientPlayNetworking.send(new SkyStorage.UseStored(hand == net.minecraft.world.InteractionHand.OFF_HAND ? 40 : player.getInventory().getSelectedSlot(), farmland));
			return true;
		}
		return false;
	}

	/** A plantable stored thing pointed at farmland with room above it: that farmland (as a long), else NO_FARMLAND. */
	private static long farmland(Minecraft minecraft, SkyStorage.@Nullable Stored stored) {
		if (stored == null || (stored.flags() & Proto.ITEM_PLANTABLE) == 0 || !(minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
			|| hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || minecraft.level == null) {
			return SkyStorage.UseStored.NO_FARMLAND;
		}
		var at = hit.getBlockPos();
		return minecraft.level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.FARMLAND) && minecraft.level.getBlockState(at.above()).isAir()
			? at.asLong() : SkyStorage.UseStored.NO_FARMLAND;
	}

	/** SkyCraft+ : Skyrim items carried in Minecraft. Right-clicked in the inventory screen (this index of the player's inventory): eaten or drunk, as on the Skyrim panel (true). */
	static boolean useStoredAt(net.minecraft.world.entity.player.Player player, int index) {
		SkyStorage.Stored stored = index >= 0 && index < player.getInventory().getContainerSize() ? SkyStorage.stored(player.getInventory().getItem(index)) : null;
		if (stored == null || !SkyStorage.usedFromHand(stored.kind())
			|| !ClientPlayNetworking.canSend(SkyStorage.UseStored.TYPE)) {
			return false;
		}
		ClientPlayNetworking.send(new SkyStorage.UseStored(index, SkyStorage.UseStored.NO_FARMLAND));
		return true;
	}

	/** A block a right click does something with (a container, door, bed, button...). */
	private static boolean usable(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		var state = level.getBlockState(pos);
		var block = state.getBlock();
		return state.getMenuProvider(level, pos) != null || level.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider
			|| block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.TrapDoorBlock
			|| block instanceof net.minecraft.world.level.block.FenceGateBlock || block instanceof net.minecraft.world.level.block.ButtonBlock
			|| block instanceof net.minecraft.world.level.block.LeverBlock || block instanceof net.minecraft.world.level.block.BedBlock
			|| block instanceof net.minecraft.world.level.block.CraftingTableBlock || block instanceof net.minecraft.world.level.block.BellBlock;
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

	/**
	 * SkyCraft+ : creative. The stored item on the cursor put on the Skyrim panel, given to Skyrim from here: in creative the cursor is
	 * the client's alone (the server's is empty, so takeCarried found nothing). A Skyrim item from the creative tabs, no shortcut.
	 */
	static void giveCarriedHere(AbstractContainerMenu menu) {
		ItemStack carried = menu.getCarried();
		SkyStorage.Stored stored = SkyStorage.stored(carried);
		if (stored != null) {
			TO_GIVE.add(new SkyStorage.Give(carried.getCount(), stored));
			menu.setCarried(ItemStack.EMPTY);
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
			int id = SkyLink.pushStoreRequest(Proto.STORE_GIVE, 0, Math.max(1, give.count()), s.health(), s.charge(), s.soul(), s.localId(), s.plugin());
			if (id == 0) {
				break;  // Skyrim's queue is full: next tick
			}
			GIVES.put(id, System.nanoTime());
			Long plantOn = PLANT_FOR.remove(give);
			if (plantOn != null) {
				PLANT_AFTER.put(id, plantOn);
			} else if (give.count() < 0) {
				USE_AFTER.add(id);
			}
			TO_GIVE.poll();
		}
		for (SkyLink.StoreResult result : SkyLink.popStoreResults()) {
			if (SkyLendingClient.onStoreResult(result) || SkyGeoClient.onStoreResult(result) || SkyBlockItemRenderer.onStoreResult(result)) {
				continue;  // SkyCraft+ : lent to a Skyrim workbench; Skyrim geo's texture
			}
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
				} else if (PLANT_AFTER.containsKey(result.id()) && result.formId() != 0) {
					// SkyCraft+ : carried in Minecraft, planted from the hand: on the farmland, as a shortcut plants it.
					ClientPlayNetworking.send(new dev.skycraft.world.SkyCrops.Plant(net.minecraft.core.BlockPos.of(PLANT_AFTER.remove(result.id())), result.formId(), result.name()));
					SkyLink.pushEvent(Proto.EV_SKY_ITEM, result.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_CONSUME);
				} else if (USE_AFTER.remove(result.id()) && result.formId() != 0) {
					// SkyCraft+ : carried in Minecraft, used from the hand: eaten or drunk in Skyrim now it has it.
					SkyLink.pushEvent(Proto.EV_SKY_ITEM, result.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_USE);
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
				default -> SkyCraft.LOG.info("SkyCraft: Skyrim items: Skyrim didn't give {} up (status {})", result.name(), result.status());
			}
		}
		long old = System.nanoTime() - 30_000_000_000L;
		for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext();) {
			if (it.next().at() < old) {
				it.remove();
			}
		}
		GIVES.values().removeIf(at -> at < old);
		USE_AFTER.retainAll(GIVES.keySet());
		PLANT_AFTER.keySet().retainAll(GIVES.keySet());
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SkyStorage.Give.TYPE, (payload, context) -> {
			TO_GIVE.add(payload);
			SkyCraft.LOG.info("SkyCraft: {} x{} out of storage, back into Skyrim", payload.stored().name(), payload.count());
		});
		// SkyCraft+ : carried in Minecraft, used from the hand (a count of -1 marks it: given as one, then used).
		ClientPlayNetworking.registerGlobalReceiver(SkyStorage.GiveAndUse.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> {
			SkyStorage.Give give = new SkyStorage.Give(-1, payload.stored());
			if (payload.farmland() != SkyStorage.UseStored.NO_FARMLAND) {
				PLANT_FOR.put(give, payload.farmland());
			}
			TO_GIVE.add(give);
		}));
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			SkyStorage.Stored stored = SkyStorage.stored(stack);
			if (stored != null && SkyStorage.carriedInMinecraft(stored)) {
				lines.add(Component.literal(SkyStorage.heldInSkyrim(stored.kind()) ? "Carried in Minecraft: put it in your hand" + (stored.kind() == Proto.SKY_APPAREL ? " or an armor slot" : "") + " to have it back in Skyrim"
					: SkyStorage.usedFromHand(stored.kind()) ? "Carried in Minecraft: use it to " + (stored.kind() == Proto.SKY_BOOK ? "read it" : "eat or drink it") + "; drop it on the Skyrim panel to put it back"
					: "Carried in Minecraft: drop it on the Skyrim panel to put it back in Skyrim").withStyle(ChatFormatting.DARK_GRAY));
			} else if (stored != null) {
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
					if (storesInto(menu, hovered)) {
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
