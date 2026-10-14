// SkyCraft+ : experimental rendering switches
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * The two experimental rendering switches. Both live in config/skycraft.properties and in Mod
 * Menu's screen; only "Item models" also has a pause menu button (the bottom right corner), because
 * it is the one that costs per frame. Fabric quads are on by default (a block that needs them is
 * invisible without them, and they cost almost nothing for ordinary blocks).
 */
public final class RenderToggles {
	private static final String QUADS_KEY = "fabricQuads";
	private static final String ITEMS_KEY = "itemModels";
	private static final String REMESH_KEY = "remeshOnChange";
	/** Shown by Mod Menu's screen and the pause menu's buttons. */
	static final String QUADS_TIP = "(experimental) Block models written for Fabric's renderer API "
		+ "(Traveler's Backpack and the like), which the plain renderer draws as nothing. Can cause lags: it builds and sends more geometry.";
	static final String ITEMS_TIP = "(experimental) Dropped and thrown items get Minecraft's own model "
		+ "(every face, any shape) instead of a spinning cube or a flat icon. Raises CPU and GPU use: more vertices and more textures, and many items on the ground can cause lags.";
	static final String REMESH_TIP = "Rebuild a section's mesh on every block the client changes - the mesh, "
		+ "its lights, its collision bits and boxes and its dug cells, all at once and ahead of the queue. Off, Minecraft's own rule decides: "
		+ "a placed redstone or a toggled lever can stay invisible until another block moves nearby.";
	private static final String QUADS_COMMENT = "# (experimental) Block models written for Fabric's renderer API "
		+ "(Traveler's Backpack and the like); off and they stay invisible. On by default (Mod Menu's screen).";
	private static final String ITEMS_COMMENT = "# (experimental) Dropped and thrown items drawn with Minecraft's own model "
		+ "instead of a cube or a flat icon; raises CPU and GPU use. Off by default (the pause menu's button, or Mod Menu).";
	private static final String REMESH_COMMENT = "# Re-mesh a section - its mesh, lights, collision and dug cells - on every block change, "
		+ "so a placed redstone or a toggled lever shows at once. On by default (Mod Menu's screen).";

	/** Off: such a block is simply invisible, as the plain renderer draws nothing for it. */
	public static volatile boolean fabricQuads;
	/** Off: dropped and thrown items keep the lighter cube/icon path. */
	public static volatile boolean itemModels;
	/** Off: Minecraft's own rule decides which changes rebuild a section (some are left out). */
	public static volatile boolean remeshOnChange;

	private RenderToggles() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
	}

	public static void register() {
		load();
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu() && minecraft.player != null) {
				Screens.getWidgets(screen).add(itemsButton(width, height));
			}
		});
	}

	private static Button itemsButton(int width, int height) {
		return Button.builder(itemsLabel(), b -> {
			itemModels = !itemModels;
			b.setMessage(itemsLabel());
			save(ITEMS_KEY, itemModels, ITEMS_COMMENT);
			SkyCraft.LOG.info("SkyCraft: item models {}", itemModels ? "on" : "off");
		}).bounds(width - 154, height - 24, 150, 20).tooltip(Tooltip.create(Component.literal(ITEMS_TIP))).build();
	}

	private static Component itemsLabel() {
		return Component.literal("Item models: " + (itemModels ? "On" : "Off") + " (exp)");
	}

	private static void load() {
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(file())) {
			props.load(in);
		} catch (NoSuchFileException e) {
			return;
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", file(), e);
			return;
		}
		// Fabric quads cost almost nothing for ordinary blocks (one lookup each) and a block that needs
		// them is invisible without them, so they are on by default; the item path costs more per frame.
		fabricQuads = !"false".equalsIgnoreCase(props.getProperty(QUADS_KEY, "true").trim());
		itemModels = "true".equalsIgnoreCase(props.getProperty(ITEMS_KEY, "false").trim());
		remeshOnChange = !"false".equalsIgnoreCase(props.getProperty(REMESH_KEY, "true").trim());
	}

	/** Writes both switches: the pause menu's buttons and Mod Menu's screen both come through here. */
	static void save() {
		save(QUADS_KEY, fabricQuads, QUADS_COMMENT);
		save(ITEMS_KEY, itemModels, ITEMS_COMMENT);
		save(REMESH_KEY, remeshOnChange, REMESH_COMMENT);
	}

	/** Rewrites only its own line, keeping the file's comments and other settings. */
	private static void save(String key, boolean value, String comment) {
		Path file = file();
		String line = key + "=" + value;
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>(List.of("# SkyCraft"));
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith(key + "=")) {
					lines.set(i, line);
					found = true;
				}
			}
			if (!found) {
				lines.add(comment);
				lines.add(line);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", file, e);
		}
	}
}
