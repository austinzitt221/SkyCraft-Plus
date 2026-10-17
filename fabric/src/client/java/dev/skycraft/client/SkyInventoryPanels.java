// SkyCraft+ : Skyrim's inventory in Minecraft's
package dev.skycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skycraft.client.mixin.ContainerScreenAccessor;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyShortcuts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's inventory in Minecraft's. Skyrim's menus can't be open alongside Minecraft's inventory,
 * so Minecraft's inventory gets two Skyrim panels beside it: Skyrim's items on its right, its magic
 * (the schools, shouts, powers) on its left, each with a row of category tabs over a grid of those
 * Skyrim things drawn as Minecraft stand-ins (SkyIcons), from the list Skyrim keeps in shared memory.
 * Skyrim's skills above the inventory, its map below.
 * A click does what Skyrim's own menu would (equip in a hand, put on, drink, eat, read, make a shout
 * the voice's), sent to Skyrim as EV_SKY_ITEM. Dragged into a slot of Minecraft's inventory it
 * becomes a shortcut there (SkyShortcuts). Tab and I still open Skyrim's own menus.
 */
public final class SkyInventoryPanels {
	private SkyInventoryPanels() {
	}

	private static final int COLUMNS = 5, ROWS = 5, TAB = 18;
	static final int WIDTH = 7 + COLUMNS * 18 + 11, HEIGHT = 166;  // (room on the right for the scroll bar)
	private static final int SCROLL_X = 7 + COLUMNS * 18 + 2, SORT_WIDTH = 22;
	private static final int TABS_TOP = 6, TITLE_TOP = 45, GRID_TOP = 56, FOOTER_TOP = GRID_TOP + ROWS * 18 + 3;
	private static final int BUTTON_HEIGHT = 18, GAP = 3;

	/** One of Skyrim's categories: a tab over its panel. */
	public enum Category {
		ALL_ITEMS("All items", true, Items.CHEST, i -> i.kind() < Proto.SKY_SPELL),
		WEAPONS("Weapons", true, Items.IRON_SWORD, i -> i.kind() == Proto.SKY_WEAPON || i.kind() == Proto.SKY_AMMO),
		APPAREL("Apparel", true, Items.IRON_CHESTPLATE, i -> i.kind() == Proto.SKY_APPAREL),
		POTIONS("Potions", true, Items.POTION, i -> i.kind() == Proto.SKY_POTION),
		SCROLLS("Scrolls", true, Items.PAPER, i -> i.kind() == Proto.SKY_SCROLL),
		FOOD("Food", true, Items.BREAD, i -> i.kind() == Proto.SKY_FOOD),
		INGREDIENTS("Ingredients", true, Items.NETHER_WART, i -> i.kind() == Proto.SKY_INGREDIENT),
		BOOKS("Books", true, Items.BOOK, i -> i.kind() == Proto.SKY_BOOK),
		KEYS("Keys", true, Items.TRIPWIRE_HOOK, i -> i.kind() == Proto.SKY_KEY),
		MISC("Misc", true, Items.GOLD_NUGGET, i -> i.kind() == Proto.SKY_MISC),
		ALL_MAGIC("All magic", false, Items.ENCHANTED_BOOK, i -> i.kind() >= Proto.SKY_SPELL),
		ALTERATION("Alteration", false, Items.AMETHYST_SHARD, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_ALTERATION),
		CONJURATION("Conjuration", false, Items.ECHO_SHARD, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_CONJURATION),
		DESTRUCTION("Destruction", false, Items.FIRE_CHARGE, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_DESTRUCTION),
		ILLUSION("Illusion", false, Items.ENDER_EYE, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_ILLUSION),
		RESTORATION("Restoration", false, Items.GLISTERING_MELON_SLICE, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_RESTORATION),
		SHOUTS("Shouts", false, Items.DRAGON_BREATH, i -> i.kind() == Proto.SKY_SHOUT),
		POWERS("Powers", false, Items.NETHER_STAR, i -> i.kind() == Proto.SKY_POWER);

		final String label;
		final boolean items;
		final Item iconItem;
		private @Nullable ItemStack icon;  // (made on first use: items aren't ready when this class loads)
		final Predicate<SkyLink.SkyItem> filter;

		Category(String label, boolean items, Item icon, Predicate<SkyLink.SkyItem> filter) {
			this.label = label;
			this.items = items;
			this.iconItem = icon;
			this.filter = filter;
		}

		ItemStack icon() {
			if (this.icon == null) {
				this.icon = new ItemStack(this.iconItem);
			}
			return this.icon;
		}
	}

	/** How a panel's things are ordered (names breaking ties). */
	enum Sort {
		NAME("A-Z", "name", "name"),
		POWER("Pwr", "damage or armor, highest first", "magicka cost, highest first"),
		VALUE("Val", "value, highest first", null),
		WEIGHT("Wt", "weight, heaviest first", null),
		COUNT("#", "how many, most first", null);

		final String label, items;
		final @Nullable String magic;  // null: not for the magic panel

		Sort(String label, String items, @Nullable String magic) {
			this.label = label;
			this.items = items;
			this.magic = magic;
		}

		Comparator<SkyLink.SkyItem> order() {
			Comparator<SkyLink.SkyItem> byName = Comparator.comparing((SkyLink.SkyItem i) -> i.name().toLowerCase());
			return switch (this) {
				case NAME -> byName;
				case POWER -> Comparator.comparingDouble((SkyLink.SkyItem i) -> -i.stat()).thenComparing(byName);
				case VALUE -> Comparator.comparingInt((SkyLink.SkyItem i) -> -i.value()).thenComparing(byName);
				case WEIGHT -> Comparator.comparingDouble((SkyLink.SkyItem i) -> -i.weight()).thenComparing(byName);
				case COUNT -> Comparator.comparingInt((SkyLink.SkyItem i) -> -i.count()).thenComparing(byName);
			};
		}

		Sort next(boolean items) {
			Sort next = this;
			do {
				next = values()[(next.ordinal() + 1) % values().length];
			} while (!items && next.magic == null);
			return next;
		}
	}

	private static Sort itemsSort = Sort.NAME, magicSort = Sort.NAME;

	// The tab picked last on each side, the next time the inventory opens.
	private static Category lastItems = Category.ALL_ITEMS, lastMagic = Category.ALL_MAGIC;

	/** One side's panel: its tabs, the things of the picked one, where it is. */
	private static final class Panel {
		final boolean items;
		final List<Category> tabs = new ArrayList<>();
		final List<SkyLink.SkyItem> shown = new ArrayList<>();
		Category category;
		int scroll, generation = Integer.MIN_VALUE, x, y;
		boolean visible;

		Panel(boolean items) {
			this.items = items;
			for (Category c : Category.values()) {
				if (c.items == items) {
					this.tabs.add(c);
				}
			}
			this.category = items ? lastItems : lastMagic;
		}

		Sort sort() {
			return this.items ? itemsSort : magicSort;
		}

		void nextSort() {
			if (this.items) {
				itemsSort = itemsSort.next(true);
			} else {
				magicSort = magicSort.next(false);
			}
			this.generation = Integer.MIN_VALUE;
			this.refresh();
		}

		boolean onSort(double mx, double my) {
			int sx = this.x + WIDTH - 7 - SORT_WIDTH, sy = this.y + TITLE_TOP - 2;
			return this.visible && mx >= sx && mx < sx + SORT_WIDTH && my >= sy && my < sy + 12;
		}

		/** The scroll bar beside the grid: a click there scrolls to that point. */
		boolean onScrollBar(double mx, double my) {
			return mx >= this.x + SCROLL_X && mx < this.x + SCROLL_X + 5 && my >= this.y + GRID_TOP && my < this.y + GRID_TOP + ROWS * 18;
		}

		void scrollTo(double my) {
			double at = (my - this.y - GRID_TOP) / (ROWS * 18.0);
			this.scroll = Math.clamp((int) Math.round(at * this.maxScroll()), 0, this.maxScroll());
		}

		/** Where a controller's cursor can jump to: the tabs, the sort button and the grid's things. */
		void jumpTargets(List<int[]> out) {
			if (!this.visible) {
				return;
			}
			for (int i = 0; i < this.tabs.size(); i++) {
				out.add(new int[] { this.x + 7 + (i % COLUMNS) * TAB + 9, this.y + TABS_TOP + (i / COLUMNS) * TAB + 9 });
			}
			out.add(new int[] { this.x + WIDTH - 7 - SORT_WIDTH / 2, this.y + TITLE_TOP + 4 });
			int filled = Math.max(1, Math.min(COLUMNS * ROWS, this.shown.size() - this.scroll * COLUMNS));
			for (int i = 0; i < filled; i++) {
				out.add(new int[] { this.x + 8 + (i % COLUMNS) * 18 + 8, this.y + GRID_TOP + 1 + (i / COLUMNS) * 18 + 8 });
			}
		}

		void pick(Category category) {
			this.category = category;
			this.scroll = 0;
			this.generation = Integer.MIN_VALUE;
			if (this.items) {
				lastItems = category;
			} else {
				lastMagic = category;
			}
		}

		void refresh() {
			SkyLink.SkyInventory inventory = SkyShortcutsClient.inventory();
			if (inventory == null || inventory.generation() == this.generation) {
				return;
			}
			this.generation = inventory.generation();
			this.shown.clear();
			for (SkyLink.SkyItem item : inventory.items()) {
				if (this.category.filter.test(item)) {
					this.shown.add(item);
				}
			}
			this.shown.sort(this.sort().order());
			this.scroll = Math.clamp(this.scroll, 0, this.maxScroll());
		}

		int maxScroll() {
			return Math.max(0, (this.shown.size() + COLUMNS - 1) / COLUMNS - ROWS);
		}

		boolean contains(double mx, double my) {
			return this.visible && mx >= this.x && mx < this.x + WIDTH && my >= this.y && my < this.y + HEIGHT;
		}

		@Nullable Category tabAt(double mx, double my) {
			for (int i = 0; i < this.tabs.size(); i++) {
				int tx = this.x + 7 + (i % COLUMNS) * TAB, ty = this.y + TABS_TOP + (i / COLUMNS) * TAB;
				if (mx >= tx && mx < tx + TAB && my >= ty && my < ty + TAB) {
					return this.tabs.get(i);
				}
			}
			return null;
		}

		SkyLink.@Nullable SkyItem itemAt(double mx, double my) {
			int gx = this.x + 7, gy = this.y + GRID_TOP;
			if (mx < gx || my < gy) {
				return null;
			}
			int column = (int) ((mx - gx) / 18), row = (int) ((my - gy) / 18);
			if (column >= COLUMNS || row >= ROWS) {
				return null;
			}
			int index = (this.scroll + row) * COLUMNS + column;
			return index < this.shown.size() ? this.shown.get(index) : null;
		}
	}

	/** One open inventory's panels, and a press or drag on them. */
	private static final class State {
		final Panel magic = new Panel(false), items = new Panel(true);
		SkyLink.@Nullable SkyItem pressed;
		int pressedButton;
		double pressX, pressY;
		boolean dragging;

		Panel[] panels() {
			return new Panel[] { this.magic, this.items };
		}

		void dragFrom(double mx, double my) {
			if (this.pressed != null && this.pressedButton == InputConstants.MOUSE_BUTTON_LEFT && Math.abs(mx - this.pressX) + Math.abs(my - this.pressY) > 4) {
				this.dragging = true;
			}
		}

		@Nullable Panel at(double mx, double my) {
			for (Panel panel : this.panels()) {
				if (panel.contains(mx, my)) {
					return panel;
				}
			}
			return null;
		}
	}

	/** True while Skyrim should keep its inventory list current (SkyClient's MC_WANTS_SKY_INVENTORY). */
	public static boolean wantsSkyInventory(@Nullable Screen screen) {
		return screen instanceof InventoryScreen || SkyStorageClient.storageScreen(screen);  // SkyCraft+ : and beside chests
	}

	private static final java.util.Map<Screen, State> STATES = new java.util.WeakHashMap<>();

	/** SkyCraft+ : controller inventory snapping. The panels' places a controller's cursor jumps to (GUI pixels). */
	public static List<int[]> jumpTargets(@Nullable Screen screen) {
		List<int[]> out = new ArrayList<>();
		State state = screen == null ? null : STATES.get(screen);
		if (state != null) {
			for (Panel panel : state.panels()) {
				panel.jumpTargets(out);
			}
		}
		return out;
	}

	/** How far Minecraft's status effects move right to clear the Skyrim items panel (EffectsInInventoryMixin); 0: not shown. */
	public static int effectsShift(@Nullable Screen screen) {
		State state = screen == null ? null : STATES.get(screen);
		return state != null && state.items.visible ? GAP + WIDTH : 0;
	}

	/** A shortcut for this in the first free hotbar slot (else inventory slot), unless there's one already. */
	private static void quickShortcut(Minecraft minecraft, SkyLink.SkyItem item) {
		var items = minecraft.player.getInventory();
		int free = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack there = items.getItem(i);
			SkyShortcuts.Ref ref = SkyShortcuts.ref(there);
			if (ref != null && item.is(ref.plugin(), ref.localId())) {
				return;
			}
			if (free < 0 && there.isEmpty()) {
				free = i;
			}
		}
		if (free >= 0) {
			SkyShortcutsClient.make(item, free);
		}
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			// SkyCraft+ : Skyrim items in Minecraft's containers. Beside a chest (shulker box, hopper...)
			// the items panel too, to drag Skyrim items into it and back.
			boolean full = screen instanceof InventoryScreen;
			if (!(screen instanceof AbstractContainerScreen<?> inventory) || !(full || SkyStorageClient.storageScreen(screen)) || minecraft.player == null
				|| !SkyLink.active()) {
				return;
			}
			var accessor = (ContainerScreenAccessor) inventory;
			State state = new State();
			STATES.put(screen, state);
			int center = (width - 176) / 2;
			List<Button> buttons = new ArrayList<>();
			int top = accessor.skycraft$topPos();
			if (full && top >= 22) {
				buttons.add(Button.builder(Component.literal("Skills"), b -> openSkyrimMenu(minecraft, Proto.SKY_ACT_MENU_STATS))
					.bounds(accessor.skycraft$leftPos() + 88 - 40, top - 21, 80, BUTTON_HEIGHT).build());
			}
			if (full && top + 166 + 21 <= height) {
				buttons.add(Button.builder(Component.literal("Map"), b -> openSkyrimMenu(minecraft, Proto.SKY_ACT_MENU_MAP))
					.bounds(accessor.skycraft$leftPos() + 88 - 40, top + 166 + 3, 80, BUTTON_HEIGHT).build());
			}
			Screens.getWidgets(screen).addAll(buttons);
			int[] placedAt = { accessor.skycraft$leftPos() };
			ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				int leftPos = accessor.skycraft$leftPos(), topPos = accessor.skycraft$topPos();
				// The recipe book moves the inventory over: the buttons follow it.
				if (leftPos != placedAt[0]) {
					int dx = leftPos - placedAt[0];
					placedAt[0] = leftPos;
					for (Button button : buttons) {
						button.setX(button.getX() + dx);
					}
				}
				// Beside the inventory where there's room; the magic one makes way for the recipe book.
				state.magic.x = leftPos - GAP - WIDTH;
				state.items.x = leftPos + 176 + GAP;
				state.magic.y = state.items.y = topPos;
				state.magic.visible = full && state.magic.x >= 2 && leftPos == center;
				state.items.visible = state.items.x + WIDTH <= width - 2;
				for (Panel panel : state.panels()) {
					panel.refresh();
				}
			});
			// The panels with the inventory's background, so tooltips (Minecraft's and theirs) go over them.
			ScreenEvents.afterBackground(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				// A held left button that moved away is a drag. (Minecraft's own drag events need raw mouse
				// motion, and here the cursor is placed where Skyrim's is.)
				state.dragFrom(mouseX, mouseY);
				extract(minecraft, state, graphics, mouseX, mouseY);
			});
			// What's dragged, over everything.
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (state.dragging && state.pressed != null) {
					graphics.nextStratum();
					picture(graphics, state.pressed, mouseX - 8, mouseY - 8);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				Panel panel = state.at(event.x(), event.y());
				if (panel == null) {
					return true;
				}
				if (SkyShortcuts.isShortcut(inventory.getMenu().getCarried())) {
					SkyShortcutsClient.forgetCarried();  // a shortcut put back
					return false;
				}
				if (dev.skycraft.net.SkyStorage.isStored(inventory.getMenu().getCarried())) {
					SkyStorageClient.takeCarried();  // SkyCraft+ : out of a chest, back into Skyrim
					return false;
				}
				if (!inventory.getMenu().getCarried().isEmpty()) {
					return false;
				}
				if (panel.onSort(event.x(), event.y())) {
					panel.nextSort();
					return false;
				}
				if (panel.onScrollBar(event.x(), event.y())) {
					panel.scrollTo(event.y());
					return false;
				}
				Category tab = panel.tabAt(event.x(), event.y());
				if (tab != null) {
					panel.pick(tab);
					panel.refresh();
					return false;
				}
				// Shift-click (a controller's Y): a shortcut in the first free slot, no dragging needed.
				if (event.hasShiftDown()) {
					SkyLink.SkyItem item = panel.itemAt(event.x(), event.y());
					if (item != null) {
						quickShortcut(minecraft, item);
					}
					return false;
				}
				state.pressed = panel.itemAt(event.x(), event.y());
				state.pressedButton = event.button();
				state.pressX = event.x();
				state.pressY = event.y();
				state.dragging = false;
				return false;
			});
			ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> {
				if (state.pressed == null) {
					return true;
				}
				state.dragFrom(event.x(), event.y());
				return false;
			});
			ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
				SkyLink.SkyItem item = state.pressed;
				if (item == null) {
					return state.at(event.x(), event.y()) == null;
				}
				state.pressed = null;
				state.dragFrom(event.x(), event.y());
				if (state.dragging) {
					state.dragging = false;
					Slot slot = slotAt(inventory, accessor, event.x(), event.y());
					if (slot != null && slot.container instanceof Inventory) {
						int index = slot.getContainerSlot();
						if ((index < 36 || index == 40) && (slot.getItem().isEmpty() || SkyShortcuts.isShortcut(slot.getItem()))) {
							SkyShortcutsClient.make(item, index);
						}
					} else if (slot != null && SkyStorageClient.storageMenu(inventory.getMenu()) && dev.skycraft.net.SkyStorage.storageSlot(slot)) {
						SkyStorageClient.store(item, item.count(), slot.index, false);  // SkyCraft+ : into the chest
					}
				} else if (state.at(event.x(), event.y()) != null && item == itemUnder(state, event.x(), event.y())) {
					click(item, state.pressedButton == InputConstants.MOUSE_BUTTON_RIGHT);
				}
				return false;
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, scrollX, scrollY) -> {
				Panel panel = state.at(mx, my);
				if (panel == null) {
					return true;
				}
				if (scrollY != 0.0) {
					panel.scroll = Math.clamp(panel.scroll - (int) Math.signum(scrollY), 0, panel.maxScroll());
				}
				return false;
			});
		});
	}

	/** The inventory's slot at (mx, my), where a dragged Skyrim thing goes. */
	private static @Nullable Slot slotAt(AbstractContainerScreen<?> screen, ContainerScreenAccessor accessor, double mx, double my) {
		for (Slot slot : screen.getMenu().slots) {
			int sx = accessor.skycraft$leftPos() + slot.x, sy = accessor.skycraft$topPos() + slot.y;
			if (slot.isActive() && mx >= sx - 1 && mx < sx + 17 && my >= sy - 1 && my < sy + 17) {
				return slot;
			}
		}
		return null;
	}

	private static SkyLink.@Nullable SkyItem itemUnder(State state, double mx, double my) {
		Panel panel = state.at(mx, my);
		return panel == null ? null : panel.itemAt(mx, my);
	}

	private static void openSkyrimMenu(Minecraft minecraft, int which) {
		minecraft.gui.setScreen(null);
		SkyLink.pushEvent(Proto.EV_SKY_ITEM, 0, 0.0F, 0.0F, 0.0F, 0.0F, which);
	}

	private static void extract(Minecraft minecraft, State state, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Font font = minecraft.font;
		SkyLink.SkyInventory inventory = SkyShortcutsClient.inventory();
		List<Component> tooltip = null;
		for (Panel panel : state.panels()) {
			if (!panel.visible) {
				continue;
			}
			int x = panel.x, y = panel.y;
			frame(graphics, x, y, WIDTH, HEIGHT);
			for (int i = 0; i < panel.tabs.size(); i++) {
				Category tab = panel.tabs.get(i);
				int tx = x + 7 + (i % COLUMNS) * TAB + 1, ty = y + TABS_TOP + (i / COLUMNS) * TAB + 1;
				slot(graphics, tx, ty);
				if (tab == panel.category) {
					graphics.fill(tx, ty, tx + 16, ty + 16, 0xFFD8D8A0);
				}
				if (!SkyIconTextures.draw(graphics, Proto.SKY_ICON_CATEGORY + tab.ordinal(), tx, ty)) {
					graphics.item(tab.icon(), tx, ty);
				}
				if (mouseX >= tx - 1 && mouseX < tx + 17 && mouseY >= ty - 1 && mouseY < ty + 17) {
					graphics.fill(tx, ty, tx + 16, ty + 16, 0x80FFFFFF);
					tooltip = List.of(Component.literal(tab.label));
				}
			}
			graphics.text(font, panel.category.label, x + 7, y + TITLE_TOP, 0xFF404040, false);
			// The sort button: how the grid is ordered; a click changes it.
			int sortX = x + WIDTH - 7 - SORT_WIDTH, sortY = y + TITLE_TOP - 2;
			boolean onSort = panel.onSort(mouseX, mouseY);
			graphics.fill(sortX, sortY, sortX + SORT_WIDTH, sortY + 12, 0xFF373737);
			graphics.fill(sortX + 1, sortY + 1, sortX + SORT_WIDTH, sortY + 12, 0xFFFFFFFF);
			graphics.fill(sortX + 1, sortY + 1, sortX + SORT_WIDTH - 1, sortY + 11, onSort ? 0xFFB0B0D8 : 0xFF9C9C9C);
			graphics.centeredText(font, Component.literal(panel.sort().label), sortX + SORT_WIDTH / 2, sortY + 2, 0xFFFFFFFF);
			if (onSort) {
				Sort sort = panel.sort();
				tooltip = List.of(Component.literal("Sorted by " + (panel.items ? sort.items : sort.magic)),
					Component.literal("Click to change").withStyle(ChatFormatting.DARK_GRAY));
			}
			// The scroll bar.
			int barX = x + SCROLL_X, barTop = y + GRID_TOP, barHeight = ROWS * 18;
			graphics.fill(barX, barTop, barX + 4, barTop + barHeight, 0xFF8B8B8B);
			if (panel.maxScroll() > 0) {
				int rows = panel.maxScroll() + ROWS;
				int thumb = Math.max(8, barHeight * ROWS / rows);
				int thumbY = barTop + (barHeight - thumb) * panel.scroll / panel.maxScroll();
				graphics.fill(barX, thumbY, barX + 4, thumbY + thumb, 0xFFFFFFFF);
				graphics.fill(barX + 1, thumbY + 1, barX + 4, thumbY + thumb, 0xFF555555);
				graphics.fill(barX + 1, thumbY + 1, barX + 3, thumbY + thumb - 1, 0xFFC6C6C6);
			}
			for (int i = 0; i < COLUMNS * ROWS; i++) {
				int sx = x + 8 + (i % COLUMNS) * 18, sy = y + GRID_TOP + 1 + (i / COLUMNS) * 18;
				slot(graphics, sx, sy);
				int index = panel.scroll * COLUMNS + i;
				if (index >= panel.shown.size()) {
					continue;
				}
				SkyLink.SkyItem item = panel.shown.get(index);
				if (equipped(item)) {
					graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF70B070);
				}
				ItemStack stack = picture(graphics, item, sx, sy);
				graphics.itemDecorations(font, stack, sx, sy, item.count() > 1 && item.kind() < Proto.SKY_SPELL ? String.valueOf(item.count()) : null);
				String mark = mark(item);
				if (mark != null) {
					graphics.text(font, mark, sx, sy, 0xFFFFFF60, true);
				}
				if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17 && !state.dragging) {
					graphics.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF);
					tooltip = tooltip(item, true);
				}
			}
			if (inventory == null) {
				graphics.centeredText(font, Component.literal("Waiting..."), x + WIDTH / 2, y + GRID_TOP + 40, 0xFF404040);
			} else if (panel.shown.isEmpty()) {
				graphics.centeredText(font, Component.literal("Nothing here"), x + WIDTH / 2, y + GRID_TOP + 40, 0xFF404040);
			}
			if (panel.items && inventory != null) {
				String weight = Math.round(inventory.carried()) + "/" + Math.round(inventory.carryMax());
				String gold = inventory.gold() + "g";
				graphics.text(font, weight, x + 7, y + FOOTER_TOP, 0xFF404040, false);
				graphics.text(font, gold, x + WIDTH - 7 - font.width(gold), y + FOOTER_TOP, 0xFF8A6A00, false);
				if (mouseX >= x + 7 && mouseX < x + WIDTH - 7 && mouseY >= y + FOOTER_TOP - 1 && mouseY < y + FOOTER_TOP + 9) {
					tooltip = List.of(Component.literal("Carry weight " + weight), Component.literal("Gold " + inventory.gold()));
				}
			} else if (!panel.items) {
				graphics.text(font, "L/R click: hand", x + 7, y + FOOTER_TOP, 0xFF606060, false);
			}
		}
		if (tooltip != null && !state.dragging) {
			graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
		}
	}

	/** Skyrim's picture of a thing, or until Skyrim has drawn it its Minecraft stand-in; returns the stand-in (for the count). */
	static ItemStack picture(GuiGraphicsExtractor graphics, SkyLink.SkyItem item, int x, int y) {
		ItemStack stack = SkyIcons.stack(item);
		if (!SkyIconTextures.draw(graphics, item.formId(), x, y)) {
			graphics.item(stack, x, y);
		}
		return stack;
	}

	/** A Minecraft-style raised panel. */
	private static void frame(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
		graphics.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
		graphics.fill(x + 1, y + 1, x + w - 3, y + 3, 0xFFFFFFFF);
		graphics.fill(x + 1, y + 1, x + 3, y + h - 3, 0xFFFFFFFF);
		graphics.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
		graphics.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
	}

	/** A Minecraft-style sunken slot around the 16x16 item at (x, y). */
	private static void slot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
		graphics.fill(x, y, x + 17, y + 17, 0xFFFFFFFF);
		graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
	}

	/** What Skyrim's own menu would do with a left (right hand) or right (left hand) click. */
	private static void click(SkyLink.SkyItem item, boolean rightButton) {
		int action = switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_SPELL, Proto.SKY_SCROLL -> rightButton && !item.has(Proto.ITEM_TWO_HANDED) ? Proto.SKY_ACT_LEFT : Proto.SKY_ACT_RIGHT;
			case Proto.SKY_APPAREL, Proto.SKY_AMMO -> Proto.SKY_ACT_RIGHT;
			case Proto.SKY_MISC -> item.sub() == 2 ? Proto.SKY_ACT_LEFT : 0;  // a torch: the left hand's
			case Proto.SKY_POTION, Proto.SKY_FOOD, Proto.SKY_INGREDIENT, Proto.SKY_BOOK, Proto.SKY_SHOUT, Proto.SKY_POWER -> Proto.SKY_ACT_USE;
			default -> 0;
		};
		if (action != 0) {
			SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, action);
		}
	}

	private static boolean equipped(SkyLink.SkyItem item) {
		return item.has(Proto.ITEM_RIGHT_HAND | Proto.ITEM_LEFT_HAND | Proto.ITEM_WORN | Proto.ITEM_VOICE);
	}

	/** The corner mark of an equipped thing: which hand (R, L, or both), worn (E), the voice's (V). */
	private static @Nullable String mark(SkyLink.SkyItem item) {
		boolean right = item.has(Proto.ITEM_RIGHT_HAND), leftHand = item.has(Proto.ITEM_LEFT_HAND);
		if (right && leftHand) {
			return "RL";
		}
		if (right) {
			return "R";
		}
		if (leftHand) {
			return "L";
		}
		if (item.has(Proto.ITEM_WORN)) {
			return "E";
		}
		return item.has(Proto.ITEM_VOICE) ? "V" : null;
	}

	/** A Skyrim thing's tooltip: its stats and state, and (in the panels) what a click does. */
	static List<Component> tooltip(SkyLink.SkyItem item, boolean clickHint) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(item.name()).withStyle(item.has(Proto.ITEM_ENCHANTED) ? ChatFormatting.AQUA : ChatFormatting.WHITE));
		switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_AMMO -> lines.add(gray("Damage " + Math.round(item.stat())));
			case Proto.SKY_APPAREL -> lines.add(gray((item.has(Proto.ITEM_HEAVY) ? "Heavy armor, " : item.has(Proto.ITEM_LIGHT) ? "Light armor, " : "") + "armor " + Math.round(item.stat())));
			case Proto.SKY_POTION, Proto.SKY_FOOD -> {
				if (item.stat() > 0.0F) {
					lines.add(gray("Magnitude " + Math.round(item.stat()) + (item.stat2() > 0.0F ? " for " + Math.round(item.stat2()) + " s" : "")));
				}
			}
			case Proto.SKY_SPELL -> lines.add(gray("Magicka " + Math.round(item.stat()) + (item.stat2() > 0.0F ? ", lasts " + Math.round(item.stat2()) + " s" : "")));
			case Proto.SKY_SHOUT -> lines.add(gray(item.count() + (item.count() == 1 ? " word" : " words") + " unlocked" + (item.stat2() > 0.0F ? ", recharge " + Math.round(item.stat2()) + " s" : "")));
			default -> {
			}
		}
		if (item.kind() < Proto.SKY_SPELL) {
			lines.add(gray(String.format("Weight %s   Value %d", trim(item.weight()), item.value()) + (item.count() > 1 ? "   x" + item.count() : "")));
		}
		if (item.has(Proto.ITEM_TWO_HANDED)) {
			lines.add(gray("Two-handed"));
		}
		if (item.has(Proto.ITEM_STOLEN)) {
			lines.add(Component.literal("Stolen").withStyle(ChatFormatting.RED));
		}
		String state = item.has(Proto.ITEM_RIGHT_HAND) && item.has(Proto.ITEM_LEFT_HAND) ? "In both hands"
			: item.has(Proto.ITEM_RIGHT_HAND) ? "In the right hand" : item.has(Proto.ITEM_LEFT_HAND) ? "In the left hand"
			: item.has(Proto.ITEM_WORN) ? (item.kind() == Proto.SKY_AMMO ? "Readied" : "Worn") : item.has(Proto.ITEM_VOICE) ? "In use" : null;
		if (state != null) {
			lines.add(Component.literal(state).withStyle(ChatFormatting.GREEN));
		}
		if (!clickHint) {
			return lines;
		}
		String hint = switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_SPELL, Proto.SKY_SCROLL -> item.has(Proto.ITEM_TWO_HANDED) ? "Click: equip" : "Left click: right hand, right click: left hand";
			case Proto.SKY_APPAREL -> item.has(Proto.ITEM_WORN) ? "Click: take off" : "Click: put on";
			case Proto.SKY_AMMO -> "Click: ready";
			case Proto.SKY_POTION -> item.sub() == Proto.P_POISON ? "Click: apply to your weapon" : "Click: drink";
			case Proto.SKY_FOOD, Proto.SKY_INGREDIENT -> "Click: eat";
			case Proto.SKY_BOOK -> item.sub() == 1 ? "Click: learn the spell" : "Read it in Skyrim's inventory (I)";
			case Proto.SKY_SHOUT, Proto.SKY_POWER -> "Click: use it (shout key)";
			case Proto.SKY_MISC -> item.sub() == 2 ? "Click: hold it in the left hand" : null;
			default -> null;
		};
		if (hint != null) {
			lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		}
		lines.add(Component.literal("Drag it into your inventory for a shortcut").withStyle(ChatFormatting.DARK_GRAY));
		if (item.kind() < Proto.SKY_SPELL) {
			lines.add(Component.literal("Drag it into a chest to store it there").withStyle(ChatFormatting.DARK_GRAY));
		}
		lines.add(Component.literal("Shift-click (controller Y): shortcut in the first free slot").withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}

	private static Component gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static String trim(float value) {
		return value == Math.rint(value) ? String.valueOf(Math.round(value)) : String.format("%.1f", value);
	}
}
