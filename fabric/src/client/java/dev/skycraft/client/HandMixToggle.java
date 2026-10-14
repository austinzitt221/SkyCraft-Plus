// SkyCraft+ : hand mixing
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
 * The pause menu's "Hands" button (its top right corner, clear of the menu at any GUI scale): who
 * gets a hand holding both a Skyrim item and a Minecraft one. Skyrim reads it from McState::hands;
 * saved as {@code handMix=} in config/skycraft.properties.
 */
public final class HandMixToggle {
	private static final String KEY = "handMix";
	/** 0 Skyrim's item wins the hand, 1 Minecraft's, 2 the last picked. */
	public static volatile int mode;

	private HandMixToggle() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
	}

	/** The three modes in order (the pause menu's "Hands" button and Mod Menu's screen). */
	static final String[] MODE_NAMES = { "Skyrim item wins", "Minecraft item wins", "Last picked wins" };
	/** Shown by Mod Menu's screen. */
	static final String TIP = "Who gets a hand holding both a Skyrim item and a Minecraft item. "
		+ "The pause menu's \"Hands\" button switches it too.";

	public static void register() {
		load();
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu() && minecraft.player != null) {
				Screens.getWidgets(screen).add(button(minecraft, width));
			}
		});
	}

	private static Button button(Minecraft minecraft, int width) {
		return Button.builder(label(), b -> {
			mode = (mode + 1) % 3;
			b.setMessage(label());
			b.setTooltip(tooltip());
			save();
			SkyCraft.LOG.info("SkyCraft: hands: {}", name());
		}).bounds(width - 154, 4, 150, 20).tooltip(tooltip()).build();
	}

	private static String name() {
		return MODE_NAMES[Math.clamp(mode, 0, MODE_NAMES.length - 1)];
	}

	private static Component label() {
		return Component.literal("Hands: " + name());
	}

	private static Tooltip tooltip() {
		return Tooltip.create(Component.literal(switch (mode) {
			case 1 -> "A hand holding a Minecraft item is Minecraft's. A Skyrim weapon, spell or shield shows only while that Minecraft hand is empty.";
			case 2 -> "Equipping in Skyrim takes the hand. Switching hotbar slot (or the off hand) takes it back and unequips the Skyrim weapon; equip it again to bring it back.";
			default -> "A Skyrim weapon, spell, shield or torch takes its hand; the Minecraft item waits behind it until you unequip it.";
		}));
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
		try {
			mode = Math.clamp(Integer.parseInt(props.getProperty(KEY, "0").trim()), 0, 2);
		} catch (NumberFormatException e) {
			mode = 0;
		}
	}

	/** Rewrites only its own line, keeping the file's comments and other settings. */
	static void save() {
		Path file = file();
		String line = KEY + "=" + mode;
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>(List.of("# SkyCraft"));
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith(KEY + "=")) {
					lines.set(i, line);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Who gets a hand with both a Skyrim and a Minecraft item: 0 Skyrim's, 1 Minecraft's, 2 the last picked (the pause menu's \"Hands\" button).");
				lines.add(line);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", file, e);
		}
	}
}
