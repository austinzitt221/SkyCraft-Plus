// SkyCraft+ : crafting across the games
package dev.skycraft.craft;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyStorage;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Crafting materials that are the same thing in both games: Skyrim's iron ingot and Minecraft's,
 * Skyrim's leather and Minecraft's, wheat, eggs, feathers, gems, mountain flowers and poppies. A
 * Skyrim one counts as its Minecraft match in Minecraft's recipes (IngredientMixin; a crafting grid
 * or furnace holds it as a stored Skyrim item), a Minecraft one as its Skyrim match at Skyrim's
 * forge, tanning rack, cooking pot and alchemy lab (SkyLending), and the conversion table turns one
 * into the other. Skyrim's things are told by name (from Skyrim.esm, English), which is how the
 * catalog (SkyCatalog) finds them in any load order. Some go one way only (Skyrim's steel ingot is
 * iron to Minecraft, but Minecraft's iron is Skyrim's iron ingot).
 */
public final class SkyMaterials {
	private SkyMaterials() {
	}

	/** A Skyrim thing (its name, lower case) and the Minecraft item it is; both: Minecraft's item is it too. */
	private record Match(String skyrim, Item minecraft, boolean both) {
	}

	private static final List<Match> MATCHES = List.of(
		// Metals and ore.
		new Match("iron ingot", Items.IRON_INGOT, true),
		new Match("gold ingot", Items.GOLD_INGOT, true),
		new Match("dwarven metal ingot", Items.COPPER_INGOT, true),
		new Match("ebony ingot", Items.NETHERITE_INGOT, true),
		new Match("steel ingot", Items.IRON_INGOT, false),
		new Match("iron ore", Items.RAW_IRON, true),
		new Match("gold ore", Items.RAW_GOLD, true),
		new Match("nails", Items.IRON_NUGGET, true),
		new Match("charcoal", Items.CHARCOAL, true),
		// Leather, wood, stone.
		new Match("leather", Items.LEATHER, true),
		new Match("leather strips", Items.STRING, true),
		new Match("firewood", Items.OAK_LOG, true),
		new Match("sawn log", Items.OAK_LOG, false),
		new Match("quarried stone", Items.COBBLESTONE, true),
		new Match("clay", Items.CLAY_BALL, true),
		new Match("cow hide", Items.LEATHER, false),
		new Match("deer hide", Items.LEATHER, false),
		new Match("elk hide", Items.LEATHER, false),
		new Match("goat hide", Items.LEATHER, false),
		new Match("horse hide", Items.LEATHER, false),
		new Match("wolf pelt", Items.LEATHER, false),
		new Match("bear pelt", Items.LEATHER, false),
		// Gems.
		new Match("diamond", Items.DIAMOND, true),
		new Match("flawless diamond", Items.DIAMOND, false),
		new Match("emerald", Items.EMERALD, true),
		new Match("flawless emerald", Items.EMERALD, false),
		new Match("amethyst", Items.AMETHYST_SHARD, true),
		new Match("flawless amethyst", Items.AMETHYST_SHARD, false),
		// Bones, feathers, eggs and the like.
		new Match("bone meal", Items.BONE_MEAL, true),
		new Match("dragon bone", Items.BONE, false),
		new Match("hawk feathers", Items.FEATHER, true),
		new Match("chicken's egg", Items.EGG, true),
		new Match("honeycomb", Items.HONEYCOMB, true),
		new Match("fire salts", Items.BLAZE_POWDER, true),
		new Match("glow dust", Items.GLOWSTONE_DUST, true),
		new Match("torch", Items.TORCH, true),
		new Match("iron arrow", Items.ARROW, true),
		// Food.
		new Match("wheat", Items.WHEAT, true),
		new Match("potato", Items.POTATO, true),
		new Match("carrot", Items.CARROT, true),
		new Match("red apple", Items.APPLE, true),
		new Match("green apple", Items.APPLE, false),
		new Match("bread", Items.BREAD, true),
		new Match("raw beef", Items.BEEF, true),
		new Match("cooked beef", Items.COOKED_BEEF, true),
		new Match("chicken breast", Items.CHICKEN, true),
		new Match("grilled chicken breast", Items.COOKED_CHICKEN, true),
		new Match("pheasant breast", Items.CHICKEN, false),
		new Match("raw rabbit leg", Items.RABBIT, true),
		new Match("salmon meat", Items.SALMON, true),
		new Match("salmon steak", Items.COOKED_SALMON, true),
		// Flowers and mushrooms.
		new Match("red mountain flower", Items.POPPY, true),
		new Match("blue mountain flower", Items.CORNFLOWER, true),
		new Match("purple mountain flower", Items.ALLIUM, true),
		new Match("lavender", Items.LILAC, true),
		new Match("dragon's tongue", Items.ORANGE_TULIP, true),
		new Match("fly amanita", Items.RED_MUSHROOM, true),
		new Match("white cap", Items.BROWN_MUSHROOM, true));

	private static final Map<String, Item> TO_MINECRAFT = new HashMap<>();
	private static final Map<Item, String> TO_SKYRIM = new HashMap<>();

	static {
		for (Match m : MATCHES) {
			TO_MINECRAFT.put(m.skyrim, m.minecraft);
			if (m.both) {
				TO_SKYRIM.putIfAbsent(m.minecraft, m.skyrim);
			}
		}
	}

	/** The Minecraft item a Skyrim thing is (by its name), or null. */
	public static @Nullable Item minecraftOf(String skyrimName) {
		return TO_MINECRAFT.get(skyrimName.toLowerCase(Locale.ROOT).trim());
	}

	/** Setting: crossCrafting in config/skycraft.properties (false: each game's materials only in its own recipes; the conversion table still works). */
	private static volatile boolean crossCrafting = true;

	public static void init() {
		dev.skycraft.SkySettings.onChange(SkyMaterials::load);
		load();
	}

	private static void load() {
		crossCrafting = dev.skycraft.SkySettings.flag("crossCrafting", true);
	}

	/** The Minecraft item a stored Skyrim item is in Minecraft's recipes, or null (not stored, not a material, or crossCrafting off). */
	public static @Nullable Item minecraftOf(ItemStack stack) {
		return crossCrafting ? convertible(stack) : null;
	}

	/** The Minecraft item a stored Skyrim material turns into on the conversion table, or null. */
	public static @Nullable Item convertible(ItemStack stack) {
		SkyStorage.Stored stored = SkyStorage.isStored(stack) ? SkyStorage.stored(stack) : null;
		return stored == null || stored.kind() == Proto.SKY_WEAPON || stored.kind() == Proto.SKY_APPAREL ? null : minecraftOf(stored.name());
	}

	/** The name of the Skyrim thing a Minecraft item is, or null. */
	public static @Nullable String skyrimOf(Item item) {
		return TO_SKYRIM.get(item);
	}

	public static boolean hasSkyrimMatch(ItemStack stack) {
		return !stack.isEmpty() && !SkyStorage.isStored(stack) && !stack.isDamaged() && stack.getComponentsPatch().isEmpty() && skyrimOf(stack.getItem()) != null;
	}

	// Skyrim's things by name (lower case), from its catalog: read again when Skyrim writes a new one.
	private static volatile Map<String, SkyLink.SkyItem> byName = Map.of();
	private static int byNameGeneration = -2;

	/** The Skyrim thing a Minecraft item is, from Skyrim's catalog (this Skyrim's), or null. */
	public static SkyLink.@Nullable SkyItem skyrimItem(Item item) {
		String name = skyrimOf(item);
		return name == null ? null : skyrimItemByName(name);
	}

	private static synchronized SkyLink.@Nullable SkyItem skyrimItemByName(String name) {
		int generation = SkyLink.skyCatalogGeneration();
		if (generation != byNameGeneration && generation > 0) {
			List<SkyLink.SkyItem> items = SkyLink.readSkyCatalog();
			if (items != null) {
				Map<String, SkyLink.SkyItem> names = new HashMap<>();
				Map<String, Integer> forms = new HashMap<>();
				for (SkyLink.SkyItem i : items) {
					if (!i.plugin().isEmpty()) {
						forms.put(i.plugin().toLowerCase(Locale.ROOT) + "|" + i.localId(), i.formId());
					}
					if (i.kind() < Proto.SKY_SPELL && !i.plugin().isEmpty()) {
						String n = i.name().toLowerCase(Locale.ROOT).trim();
						// Skyrim.esm's own first: a mod's look-alike of the same name stays a mod's.
						if (!names.containsKey(n) || "skyrim.esm".equalsIgnoreCase(i.plugin())) {
							names.put(n, i);
						}
					}
				}
				byName = names;
				byForm = forms;
				byNameGeneration = generation;
			}
		}
		return name.isEmpty() ? null : byName.get(name);
	}

	// SkyCraft+ : this Skyrim's form IDs by plugin and local ID (from the same catalog).
	private static volatile Map<String, Integer> byForm = Map.of();

	/** This Skyrim's form ID of plugin's localId (0: not in its catalog). */
	public static int formIdOf(String plugin, int localId) {
		skyrimItemByName("");  // (the catalog read, if it's new)
		return byForm.getOrDefault(plugin.toLowerCase(Locale.ROOT) + "|" + localId, 0);
	}

	/** A stored Skyrim item (SkyStorage) of the Skyrim thing a Minecraft item is, looking like that item; EMPTY if Skyrim has none. */
	public static ItemStack storedOf(Item item, int count) {
		SkyLink.SkyItem sky = skyrimItem(item);
		if (sky == null || count <= 0) {
			return ItemStack.EMPTY;
		}
		String model = BuiltInRegistries.ITEM.getKey(item).toString();
		return SkyStorage.make(new SkyStorage.Stored(sky.plugin(), sky.localId(), sky.kind(), sky.sub(), sky.material(), sky.flags() & (Proto.ITEM_HEAVY | Proto.ITEM_LIGHT
			| Proto.ITEM_TWO_HANDED | Proto.ITEM_PLANTABLE), 1.0F, -1.0F, 0, sky.weight(), sky.value(), sky.stat(), sky.stat2(), sky.name(), model, 0, false), count);
	}
}
