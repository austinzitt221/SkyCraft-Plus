// SkyCraft+ : the SkyCraft+ settings screen
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.SkySettings;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The pause menu's "SkyCraft+ settings" (its bottom left corner): every setting of SkyCraft.ini
 * (Skyrim's side: its text comes from Skyrim, with the file's own comments as each one's tooltip,
 * and changes go back to Skyrim, which saves the file and takes them up at once) and of
 * config/skycraft.properties (Minecraft's side: SkySettings), a section a page. On and off settings
 * change as they're clicked; numbers and words when "Done" (or "Save") is pressed. A few are only
 * taken up at Skyrim's next start or the world's next load, and say so. Some can't be turned off
 * where the player is (the Nether and the End while in them, structures while in one): the screen
 * says why instead (SkySettingsGuards).
 */
public final class SkySettingsScreen extends Screen {
	private static final int ROWS = 8;

	/** One setting: where it lives (an ini section, or null for skycraft.properties), its key, value and description. */
	record Setting(@Nullable String section, String key, String value, String description, boolean later) {
		boolean flag() {
			return this.key.startsWith("b") && this.section != null ? !"0".equals(this.value.trim()) && !"false".equalsIgnoreCase(this.value.trim())
				: "true".equalsIgnoreCase(this.value.trim()) || "1".equals(this.value.trim());
		}

		boolean isFlag() {
			return this.section != null ? this.key.length() > 1 && this.key.charAt(0) == 'b' && Character.isUpperCase(this.key.charAt(1))
				: "true".equalsIgnoreCase(this.value.trim()) || "false".equalsIgnoreCase(this.value.trim());
		}

		/** "bMinecraftLightUnderground" -> "Minecraft Light Underground", "spawnAnimals" -> "Spawn Animals". */
		String label() {
			String k = this.key;
			if (this.section != null && k.length() > 1 && "bifs".indexOf(k.charAt(0)) >= 0 && Character.isUpperCase(k.charAt(1))) {
				k = k.substring(1);
			}
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < k.length(); i++) {
				char c = k.charAt(i);
				if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(k.charAt(i - 1))) {
					out.append(' ');
				}
				out.append(i == 0 ? Character.toUpperCase(c) : c);
			}
			return out.toString();
		}
	}

	private final Screen parent;
	private final Map<String, List<Setting>> pages = new LinkedHashMap<>();
	private final Map<String, String> pending = new LinkedHashMap<>();  // "section\0key" -> value (null section: "")
	// SkyCraft+ : each row's tooltip, drawn by this screen (Minecraft's put a long one above its row, off the top of the screen).
	private final List<Map.Entry<net.minecraft.client.gui.components.AbstractWidget, String>> tips = new ArrayList<>();
	private String page = "";
	private int scroll;
	private int seenGeneration = Integer.MIN_VALUE;
	private LinearLayout layout = LinearLayout.vertical().spacing(4);
	private @Nullable Component status;

	public SkySettingsScreen(Screen parent) {
		super(Component.literal("SkyCraft+ settings"));
		this.parent = parent;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu() && minecraft.player != null) {
				Screens.getWidgets(screen).add(Button.builder(Component.literal("SkyCraft+ settings..."), b -> minecraft.gui.setScreen(new SkySettingsScreen(screen)))
					.bounds(4, height - 24, 150, 20)
					.tooltip(Tooltip.create(Component.literal("Every SkyCraft.ini and SkyCraft+ setting, changed while you play.")))
					.build());
			}
		});
	}

	// ---- the settings ------------------------------------------------------------------------

	/** SkyCraft.ini's settings by section, with the comment above each as its description. */
	static Map<String, List<Setting>> parseIni(String text) {
		Map<String, List<Setting>> out = new LinkedHashMap<>();
		String section = "";
		List<String> comment = new ArrayList<>();
		String lastDescription = "";
		boolean blank = true;
		for (String raw : text.split("\r?\n")) {
			String line = raw.trim();
			if (line.isEmpty()) {
				comment.clear();
				blank = true;
				continue;
			}
			if (line.startsWith(";") || line.startsWith("#")) {
				comment.add(line.substring(1).trim());
				continue;
			}
			if (line.startsWith("[") && line.endsWith("]")) {
				section = line.substring(1, line.length() - 1).trim();
				comment.clear();
				lastDescription = "";
				blank = true;
				continue;
			}
			int eq = line.indexOf('=');
			if (eq <= 0 || section.isEmpty()) {
				continue;
			}
			String key = line.substring(0, eq).trim();
			String value = line.substring(eq + 1).trim();
			String description = !comment.isEmpty() ? String.join(" ", comment) : blank ? "" : lastDescription;
			lastDescription = description;
			comment.clear();
			blank = false;
			out.computeIfAbsent(section, s -> new ArrayList<>()).add(new Setting(section, key, value, description, SkySettingsGuards.skyrimRestart(section, key)));
		}
		return out;
	}

	/** Minecraft's side, config/skycraft.properties: by page, with descriptions. */
	private static final String[][] MINECRAFT = {
		{ "Spawning", "spawnAnimals", "true", "Minecraft's animals come on Skyrim's ground (grass, snow, sand pick which)." },
		{ "Spawning", "spawnMonsters", "true", "Minecraft's monsters come at Skyrim's night, and in dungeons at any hour." },
		{ "Spawning", "spawnVillagers", "true", "Villagers in Skyrim's cities and towns, already in a job." },
		{ "Spawning", "animalCap", "8", "The most Minecraft animals around you at once." },
		{ "Spawning", "animalSpawnRate", "100", "How fast animals come, in percent (100: a try every second; 50: half as fast; 200: twice). The cap is how many at most." },
		{ "Spawning", "monsterCap", "6", "The most Minecraft monsters around you at night." },
		{ "Spawning", "monsterSpawnRate", "100", "How fast monsters come at night and in dungeons, in percent (100: a try every second; 50: half as fast; 200: twice)." },
		{ "Spawning", "dungeonMonsterCap", "5", "The most Minecraft monsters in a dungeon around you." },
		{ "Spawning", "villagerCap", "6", "The most villagers in a city around you." },
		{ "Spawning", "villagerSpawnRate", "100", "How fast villagers come in towns, in percent (100: a try every ten seconds; 50: half as fast; 200: twice)." },
		{ "Spawning", "wanderingTraders", "true", "Wandering traders come by now and then, with their llamas." },
		{ "Spawning", "wanderingTraderChance", "10", "The chance (percent) of a wandering trader every two minutes." },
		{ "Spawning", "caveMonsters", "true", "Monsters in dark caves you've dug into (torches keep them away)." },
		{ "Spawning", "caveMonsterCap", "6", "The most monsters in caves around you." },
		{ "Spawning", "caveMonsterSpawnRate", "100", "How fast monsters come in caves you've dug, in percent (100: a try every second; 50: half as fast; 200: twice)." },
		{ "World", "skyrimStructures", "true", "Minecraft's villages, temples, outposts and the rest in Skyrim's world. Off: they're put away as they are (loot taken stays taken) and come back as they were when on again." },
		{ "World", "structuresOffMountains", "true", "Structures keep off steep mountainsides (fewer villages, none on a slope)." },
		{ "World", "deepUnderground", "true", "Real Minecraft underground beneath Skyrim (caves, ores, mineshafts, strongholds). Taken up when the world next loads." },
		{ "World", "caveRevealRadius", "48", "How far around you (blocks) caves open up as you dig." },
		{ "World", "separateInteriors", "true", "Each Skyrim interior keeps its own builds. Taken up when the world next loads." },
		{ "World", "lootChests", "true", "Minecraft loot chests in Skyrim's dungeons, caves and camps." },
		{ "World", "skyrimBlockTextureBlocks", "8", "How many blocks one Skyrim texture spreads over on the Skyrim blocks you place (more: zoomed out further). Blocks redrawn after take it up." },
		{ "World", "skyrimPatches", "true", "Little patches of Minecraft grass, flowers and trees in Skyrim's countryside. Off: all of them taken away round you as you go (explored ground too); on again: back." },
		{ "World", "patchTrees", "true", "Minecraft trees among the patches (turned off: taken away round you as you go, explored ground too; back on: they come back)." },
		{ "World", "patchFlowers", "true", "Minecraft flowers among the patches (turned off: taken away round you as you go, explored ground too; back on: they come back)." },
		{ "World", "patchGrass", "true", "Minecraft grass, ferns, bushes and leaf litter among the patches (turned off: taken away round you as you go, explored ground too; back on: they come back)." },
		{ "World", "destruction", "true", "Mining Skyrim's ground and rocks, and explosions, dig into Skyrim's world (holes already dug stay)." },
		{ "World", "holdMobsWithoutGround", "true", "Mobs are held still where Skyrim's ground isn't known yet, so they don't fall out of the world." },
		{ "World", "mobWanderFix", "true", "Mobs don't pile up on high spots when they wander." },
		{ "World", "bedsInSkyrim", "true", "Sleeping the night through in a Minecraft bed moves Skyrim's clock to the morning; after a death and the reload you're taken to your bed (load the save again to go back)." },
		{ "Items & combat", "skyrimArmorSlots", "true", "Skyrim armor goes in Minecraft's armor slots (and more slots for gauntlets, necklace, ring)." },
		{ "Items & combat", "skyrimStorage", "true", "Skyrim items can be put in Minecraft's chests, barrels, bundles and the like." },
		{ "Items & combat", "skyrimItemsInMinecraft", "true", "Skyrim things dragged into your Minecraft inventory leave Skyrim's (no doubles, no weight there; weapons, arrows and armor go back once in your hand or an armor slot); drop them on the Skyrim panel to put them back. Off: shortcuts, as before." },
		{ "Items & combat", "crossCrafting", "true", "Skyrim materials count in Minecraft's recipes (Skyrim iron makes a Minecraft sword)." },
		{ "Items & combat", "skyrimWorkbenches", "true", "Skyrim's forge, cooking pot, enchanter... see your Minecraft materials (and the weapon in hand) while you use them." },
		{ "Items & combat", "enchanterAllGear", "true", "At Skyrim's enchanter, every Minecraft weapon and piece of armor in your inventory can be enchanted (worn armor stays on). Off: only what's in your hand." },
		{ "Items & combat", "arrowsAcrossGames", "true", "Minecraft bows shoot Skyrim arrows and Skyrim bows Minecraft arrows (the off hand's arrows win)." },
		{ "Items & combat", "skyrimWeaponsBreakBlocks", "true", "Skyrim weapons break Minecraft blocks (pickaxes and wood axes as tools)." },
		{ "Items & combat", "skyrimFightsMobs", "true", "Your followers fight Minecraft's monsters; anyone a mob hurts hits back." },
		{ "Items & combat", "petsThroughDoors", "true", "Your pets and led animals come through Skyrim's doors with you." },
		{ "Items & combat", "starterKit", "false", "New players get a Minecraft starter kit (sword, pickaxe, bow, food)." },
		{ "Items & combat", "silkTouchSkyrimGeo", "true", "Mining Skyrim's ground or rock with silk touch gives a block with that Skyrim texture (else its Minecraft block)." },
		{ "Items & combat", "skyrimFoodForAnimals", "true", "Skyrim food feeds animals (Skyrim's and Minecraft's), and bones tame Skyrim's dogs and wolves." },
	};

	private void reload() {
		this.pages.clear();
		String ini = SkyLink.readSettingsText();
		this.seenGeneration = SkyLink.settingsGeneration();
		if (ini != null && !ini.isBlank()) {
			for (var entry : parseIni(ini).entrySet()) {
				this.pages.put("Skyrim: " + entry.getKey(), entry.getValue());
			}
		}
		var properties = SkySettings.properties();
		for (String[] row : MINECRAFT) {
			String value = properties.getProperty(row[1], row[2]).trim();
			this.pages.computeIfAbsent(row[0], s -> new ArrayList<>()).add(new Setting(null, row[1], value, row[3], SkySettingsGuards.minecraftLater(row[1])));
		}
		if (this.page.isEmpty() || !this.pages.containsKey(this.page)) {
			this.page = this.pages.keySet().stream().findFirst().orElse("");
		}
	}

	// ---- the screen --------------------------------------------------------------------------

	/** How many settings fit at once: up to ROWS, fewer on a short screen (a big GUI scale), so nothing goes off it. */
	private int rows() {
		return Math.clamp((this.height - 150) / 24, 3, ROWS);
	}

	@Override
	protected void init() {
		this.tips.clear();
		if (this.pages.isEmpty() || SkyLink.settingsGeneration() != this.seenGeneration) {
			this.reload();
		}
		this.layout = LinearLayout.vertical().spacing(4);
		this.layout.defaultCellSetting().alignHorizontallyCenter();
		this.layout.addChild(new StringWidget(this.title, this.font));
		if (SkyLink.readSettingsText() == null) {
			this.layout.addChild(new StringWidget(Component.literal("Skyrim isn't connected: only Minecraft's settings here.").withStyle(ChatFormatting.GRAY), this.font));
		}
		// The pages: a row of buttons (two rows when there are many).
		List<String> names = new ArrayList<>(this.pages.keySet());
		int perRow = (names.size() + 1) / 2;
		for (int r = 0; r < 2; r++) {
			LinearLayout row = this.layout.addChild(LinearLayout.horizontal().spacing(2));
			for (int i = r * perRow; i < Math.min(names.size(), (r + 1) * perRow); i++) {
				String name = names.get(i);
				Button b = row.addChild(Button.builder(Component.literal(name.replace("Skyrim: ", "")), x -> this.open(name)).width(Math.max(52, Math.min(90, 470 / perRow))).build());
				b.active = !name.equals(this.page);
				b.setTooltip(Tooltip.create(Component.literal(name.startsWith("Skyrim: ") ? "SkyCraft.ini [" + name.substring(8) + "] (Skyrim's side)"
					: "config/skycraft.properties (Minecraft's side)")));
			}
		}
		List<Setting> settings = this.pages.getOrDefault(this.page, List.of());
		this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, settings.size() - this.rows())));
		for (int i = this.scroll; i < Math.min(settings.size(), this.scroll + this.rows()); i++) {
			this.addRow(settings.get(i));
		}
		LinearLayout paging = this.layout.addChild(LinearLayout.horizontal().spacing(4));
		Button up = paging.addChild(Button.builder(Component.literal("Up"), b -> this.move(-this.rows())).width(40).build());
		paging.addChild(new StringWidget(Component.literal(settings.isEmpty() ? "" : (this.scroll + 1) + "-" + Math.min(settings.size(), this.scroll + this.rows()) + " of " + settings.size()),
			this.font));
		Button down = paging.addChild(Button.builder(Component.literal("Down"), b -> this.move(this.rows())).width(40).build());
		up.active = this.scroll > 0;
		down.active = this.scroll + this.rows() < settings.size();
		if (this.status != null) {
			this.layout.addChild(new StringWidget(this.status, this.font));
		}
		LinearLayout bottom = this.layout.addChild(LinearLayout.horizontal().spacing(4));
		bottom.addChild(Button.builder(Component.literal("Save"), b -> this.save()).width(98).build());
		bottom.addChild(Button.builder(Component.literal("Done"), b -> this.onClose()).width(98).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private void addRow(Setting setting) {
		LinearLayout row = this.layout.addChild(LinearLayout.horizontal().spacing(4));
		String tip = (setting.description().isEmpty() ? setting.key() : setting.description())
			+ (setting.later() ? (setting.section() != null ? "\n(Taken up when Skyrim next starts.)" : "\n(Taken up when the world next loads.)") : "");
		StringWidget label = row.addChild(new StringWidget(170, 20, Component.literal(setting.label() + (setting.later() ? " *" : "")), this.font));
		this.tips.add(Map.entry(label, tip));
		String id = (setting.section() == null ? "" : setting.section()) + "\0" + setting.key();
		if (setting.isFlag()) {
			boolean now = this.pending.containsKey(id) ? new Setting(setting.section(), setting.key(), this.pending.get(id), "", false).flag() : setting.flag();
			CycleButton<Boolean> toggle = row.addChild(CycleButton.onOffBuilder(now).displayOnlyValue().create(0, 0, 120, 20, Component.literal(setting.label()),
				(button, value) -> this.change(setting, value ? (setting.section() != null ? "1" : "true") : (setting.section() != null ? "0" : "false"), button)));
			this.tips.add(Map.entry(toggle, tip));
		} else {
			EditBox box = row.addChild(new EditBox(this.font, 120, 20, Component.literal(setting.label())));
			box.setMaxLength(170);
			box.setValue(this.pending.getOrDefault(id, setting.value()));
			box.setResponder(text -> {
				if (!text.equals(setting.value())) {
					this.pending.put(id, text);
				} else {
					this.pending.remove(id);
				}
			});
			this.tips.add(Map.entry(box, tip));
		}
	}

	@Override
	public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		for (var tip : this.tips) {
			if (tip.getKey().visible && tip.getKey().isMouseOver(mouseX, mouseY)) {
				// (By the mouse, kept on the screen: Minecraft's default placing.)
				graphics.setTooltipForNextFrame(this.font, this.font.split(Component.literal(tip.getValue()), Math.min(260, this.width / 2)), mouseX, mouseY);
				break;
			}
		}
	}

	/** An on/off setting clicked: changed now (unless where the player is says no: it flips back). */
	private void change(Setting setting, String value, CycleButton<Boolean> button) {
		String refusal = SkySettingsGuards.refusal(this.minecraft, setting.section(), setting.key(), value);
		if (refusal != null) {
			this.status = Component.literal(refusal).withStyle(ChatFormatting.YELLOW);
			this.rebuildWidgets();
			return;
		}
		this.apply(setting.section(), setting.key(), value);
		boolean on = value.equals("1") || value.equalsIgnoreCase("true");
		this.status = Component.literal(setting.label() + ": " + (on ? "on" : "off") + (setting.later() ? " (taken up later)" : "")).withStyle(ChatFormatting.GRAY);
		this.reloadSoon();
	}

	private void apply(@Nullable String section, String key, String value) {
		if (section != null) {
			if (!SkyLink.pushSettingsEdit(section, key, value)) {
				this.status = Component.literal("Skyrim didn't take the change (is it running?)").withStyle(ChatFormatting.RED);
			}
		} else {
			SkySettings.set(key, value);
			SkySettingsGuards.applied(this.minecraft, key, value);
		}
		SkyCraft.LOG.info("SkyCraft: settings screen: {}{} = {}", section == null ? "" : "[" + section + "] ", key, value);
	}

	/** Numbers and words typed: sent now. */
	private void save() {
		for (var entry : new ArrayList<>(this.pending.entrySet())) {
			String[] id = entry.getKey().split("\0", 2);
			String section = id[0].isEmpty() ? null : id[0];
			String refusal = SkySettingsGuards.refusal(this.minecraft, section, id[1], entry.getValue());
			if (refusal != null) {
				this.status = Component.literal(refusal).withStyle(ChatFormatting.YELLOW);
				continue;
			}
			this.apply(section, id[1], entry.getValue());
			this.pending.remove(entry.getKey());
		}
		this.reloadSoon();
	}

	/** Skyrim writes the file again a frame or two later: shown again with what it now says. */
	private void reloadSoon() {
		this.minecraft.execute(() -> {
			this.reload();
			this.rebuildWidgets();
		});
	}

	@Override
	public void tick() {
		super.tick();
		if (SkyLink.settingsGeneration() != this.seenGeneration && this.pending.isEmpty()) {
			this.reload();
			this.rebuildWidgets();
		}
	}

	private void open(String name) {
		this.page = name;
		this.scroll = 0;
		this.status = null;
		this.rebuildWidgets();
	}

	private void move(int by) {
		this.scroll += by;
		this.rebuildWidgets();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		FrameLayout.centerInRectangle(this.layout, this.getRectangle());
	}

	@Override
	public void onClose() {
		this.save();
		this.minecraft.gui.setScreen(this.parent);
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
