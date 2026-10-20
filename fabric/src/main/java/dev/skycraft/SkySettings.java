// SkyCraft+ : settings
package dev.skycraft;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/skycraft.properties, read once and kept, for settings that are looked at often (crafting
 * across the games is asked about every recipe check) or changed while playing (the SkyCraft+
 * settings screen): set() writes the file and tells whoever listens. Older settings still read the
 * file themselves when their world starts.
 */
public final class SkySettings {
	private SkySettings() {
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
	private static volatile Properties properties;
	private static final List<Runnable> LISTENERS = new ArrayList<>();

	public static Path file() {
		return FILE;
	}

	/** The file's settings as they are now (read again after set or reload). */
	public static Properties properties() {
		Properties p = properties;
		if (p == null) {
			p = read();
			properties = p;
		}
		return p;
	}

	private static Properties read() {
		Properties p = new Properties();
		if (Files.exists(FILE)) {
			try (Reader in = Files.newBufferedReader(FILE)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", FILE, e.toString());
			}
		}
		return p;
	}

	public static boolean flag(String key, boolean otherwise) {
		String value = properties().getProperty(key);
		return value == null ? otherwise : Boolean.parseBoolean(value.trim());
	}

	public static int number(String key, int otherwise) {
		String value = properties().getProperty(key);
		try {
			return value == null ? otherwise : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return otherwise;
		}
	}

	/** Reads the file again (someone else wrote it) and tells the listeners. */
	public static void reload() {
		properties = read();
		changed();
	}

	/** Changes one setting, writes the file (its other lines and comments as they were) and tells the listeners. */
	public static synchronized void set(String key, String value) {
		String line = key + "=" + value;
		try {
			List<String> lines = Files.exists(FILE) ? new ArrayList<>(Files.readAllLines(FILE)) : new ArrayList<>(List.of("# SkyCraft"));
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith(key + "=")) {
					lines.set(i, line);
					found = true;
				}
			}
			if (!found) {
				lines.add(line);
			}
			Files.createDirectories(FILE.getParent());
			Files.write(FILE, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't write {}: {}", FILE, e.toString());
		}
		properties = read();
		SkyCraft.LOG.info("SkyCraft: setting {} = {}", key, value);
		changed();
	}

	/** Called (on the thread that changed them) whenever the settings change. */
	public static void onChange(Runnable listener) {
		synchronized (LISTENERS) {
			LISTENERS.add(listener);
		}
	}

	private static void changed() {
		List<Runnable> now;
		synchronized (LISTENERS) {
			now = List.copyOf(LISTENERS);
		}
		for (Runnable r : now) {
			try {
				r.run();
			} catch (RuntimeException e) {
				SkyCraft.LOG.warn("SkyCraft: a setting's listener failed", e);
			}
		}
	}
}
