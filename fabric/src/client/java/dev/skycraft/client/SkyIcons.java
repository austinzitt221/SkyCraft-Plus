// SkyCraft+ : Skyrim's inventory in Minecraft's
package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Minecraft stand-ins for Skyrim's things in SkyInventoryPanels (Skyrim has no 2D icons; its menu
 * spins the 3D model): the weapon or armour piece of the nearest Minecraft material, potions
 * coloured by what they restore, a spell school's own item. Enchanted things shimmer.
 */
final class SkyIcons {
	private static final Map<Long, ItemStack> CACHE = new HashMap<>();

	private SkyIcons() {
	}

	static ItemStack stack(SkyLink.SkyItem item) {
		long key = (long) item.kind() << 40 | (long) item.sub() << 32 | (long) item.material() << 24 | (item.flags() & (Proto.ITEM_ENCHANTED | Proto.ITEM_FILLED | Proto.ITEM_HEAVY | Proto.ITEM_LIGHT))
			| (item.kind() == Proto.SKY_FOOD || item.kind() == Proto.SKY_INGREDIENT || item.kind() == Proto.SKY_MISC || item.kind() == Proto.SKY_BOOK ? (long) item.name().hashCode() << 48 : 0L);
		return CACHE.computeIfAbsent(key, k -> make(item));
	}

	private static ItemStack make(SkyLink.SkyItem item) {
		ItemStack stack = switch (item.kind()) {
			case Proto.SKY_WEAPON -> new ItemStack(weapon(item.sub(), tier(item.material())));
			case Proto.SKY_APPAREL -> new ItemStack(apparel(item));
			case Proto.SKY_POTION -> potion(item.sub());
			case Proto.SKY_FOOD -> new ItemStack(food(item.name().toLowerCase()));
			case Proto.SKY_INGREDIENT -> new ItemStack(ingredient(item.name().toLowerCase()));
			case Proto.SKY_BOOK -> new ItemStack(item.sub() == 1 ? Items.ENCHANTED_BOOK : item.sub() == 2 ? Items.PAPER : Items.BOOK);
			case Proto.SKY_KEY -> new ItemStack(Items.TRIPWIRE_HOOK);
			case Proto.SKY_MISC -> new ItemStack(misc(item));
			case Proto.SKY_AMMO -> new ItemStack(item.sub() == 1 ? Items.SPECTRAL_ARROW : Items.ARROW);
			case Proto.SKY_SCROLL -> glint(new ItemStack(Items.PAPER));
			case Proto.SKY_SPELL -> new ItemStack(spell(item));
			case Proto.SKY_SHOUT -> new ItemStack(Items.DRAGON_BREATH);
			case Proto.SKY_POWER -> new ItemStack(item.sub() == 1 ? Items.TOTEM_OF_UNDYING : Items.NETHER_STAR);
			default -> new ItemStack(Items.BARRIER);
		};
		if (item.has(Proto.ITEM_ENCHANTED) || item.kind() == Proto.SKY_MISC && item.has(Proto.ITEM_FILLED)) {
			glint(stack);
		}
		return stack;
	}

	private static ItemStack glint(ItemStack stack) {
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	/** Minecraft's tiers, low to high: 0 wood, 1 stone, 2 copper, 3 iron, 4 gold, 5 diamond, 6 netherite. */
	private static int tier(int material) {
		return switch (material) {
			case Proto.MAT_WOOD, Proto.MAT_LEATHER -> 0;
			case Proto.MAT_DWARVEN -> 2;
			case Proto.MAT_ELVEN, Proto.MAT_GOLD -> 4;
			case Proto.MAT_GLASS, Proto.MAT_STALHRIM -> 5;
			case Proto.MAT_EBONY, Proto.MAT_DAEDRIC, Proto.MAT_DRAGON, Proto.MAT_ORCISH -> 6;
			case Proto.MAT_NONE -> 1;
			default -> 3;  // iron, steel, silver, chain
		};
	}

	private static Item weapon(int type, int tier) {
		Item[] swords = { Items.WOODEN_SWORD, Items.STONE_SWORD, Items.COPPER_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD };
		Item[] axes = { Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE };
		Item[] pickaxes = { Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE };
		return switch (type) {
			case Proto.W_SWORD, Proto.W_DAGGER, Proto.W_GREATSWORD -> swords[tier];
			case Proto.W_WAR_AXE, Proto.W_BATTLEAXE, Proto.W_WOOD_AXE -> axes[tier];
			case Proto.W_PICKAXE -> pickaxes[tier];
			case Proto.W_MACE, Proto.W_WARHAMMER -> Items.MACE;
			case Proto.W_BOW -> Items.BOW;
			case Proto.W_CROSSBOW -> Items.CROSSBOW;
			case Proto.W_STAFF -> Items.BLAZE_ROD;
			default -> Items.STICK;
		};
	}

	private static Item apparel(SkyLink.SkyItem item) {
		int slot = item.sub();
		switch (slot) {
			case Proto.A_SHIELD:
				return Items.SHIELD;
			case Proto.A_RING:
				return Items.GOLD_NUGGET;
			case Proto.A_AMULET:
				return Items.HEART_OF_THE_SEA;
			case Proto.A_CIRCLET:
				return Items.GOLDEN_HELMET;
			case Proto.A_HANDS:
				return item.has(Proto.ITEM_HEAVY) ? Items.IRON_INGOT : Items.LEATHER;
			default:
				break;
		}
		// Clothes are leather; armour by its material, Minecraft's armour sets being fewer.
		int set = !item.has(Proto.ITEM_HEAVY) && !item.has(Proto.ITEM_LIGHT) ? 0 : switch (item.material()) {
			case Proto.MAT_LEATHER, Proto.MAT_WOOD -> 0;
			case Proto.MAT_CHAIN, Proto.MAT_STEEL, Proto.MAT_ORCISH -> item.has(Proto.ITEM_LIGHT) ? 1 : 3;
			case Proto.MAT_DWARVEN -> 2;
			case Proto.MAT_ELVEN, Proto.MAT_GOLD -> 4;
			case Proto.MAT_GLASS, Proto.MAT_STALHRIM -> 5;
			case Proto.MAT_EBONY, Proto.MAT_DAEDRIC, Proto.MAT_DRAGON -> 6;
			default -> item.has(Proto.ITEM_LIGHT) ? 0 : 3;
		};
		Item[][] sets = {
			{ Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET, null, Items.LEATHER_BOOTS },
			{ Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_HELMET, null, Items.CHAINMAIL_BOOTS },
			{ Items.COPPER_CHESTPLATE, Items.COPPER_HELMET, null, Items.COPPER_BOOTS },
			{ Items.IRON_CHESTPLATE, Items.IRON_HELMET, null, Items.IRON_BOOTS },
			{ Items.GOLDEN_CHESTPLATE, Items.GOLDEN_HELMET, null, Items.GOLDEN_BOOTS },
			{ Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET, null, Items.DIAMOND_BOOTS },
			{ Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET, null, Items.NETHERITE_BOOTS },
		};
		return sets[set][slot == Proto.A_HEAD ? 1 : slot == Proto.A_FEET ? 3 : 0];
	}

	private static ItemStack potion(int kind) {
		int color = switch (kind) {
			case Proto.P_HEALTH -> 0xD8302C;
			case Proto.P_MAGICKA -> 0x2F5BD8;
			case Proto.P_STAMINA -> 0x3FA83A;
			case Proto.P_POISON -> 0x4E5A1C;
			default -> 0x9A4FC4;
		};
		ItemStack stack = new ItemStack(kind == Proto.P_POISON ? Items.SPLASH_POTION : Items.POTION);
		stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(color), List.of(), Optional.empty()));
		return stack;
	}

	private static Item food(String name) {
		if (name.contains("apple")) {
			return Items.APPLE;
		}
		if (name.contains("bread") || name.contains("roll") || name.contains("pie") || name.contains("cake") || name.contains("sweetroll")) {
			return name.contains("pie") ? Items.PUMPKIN_PIE : name.contains("sweetroll") || name.contains("cake") ? Items.COOKIE : Items.BREAD;
		}
		if (name.contains("potato")) {
			return Items.BAKED_POTATO;
		}
		if (name.contains("carrot")) {
			return Items.CARROT;
		}
		if (name.contains("salmon") || name.contains("fish") || name.contains("slaughterfish")) {
			return Items.COOKED_SALMON;
		}
		if (name.contains("mead") || name.contains("ale") || name.contains("wine") || name.contains("milk") || name.contains("skooma") || name.contains("brandy")) {
			return Items.HONEY_BOTTLE;
		}
		if (name.contains("cheese") || name.contains("butter")) {
			return Items.HONEYCOMB;  // (yellow, like a wheel of cheese)
		}
		if (name.contains("soup") || name.contains("stew")) {
			return Items.MUSHROOM_STEW;
		}
		if (name.contains("cabbage") || name.contains("leek") || name.contains("lettuce") || name.contains("gourd") || name.contains("tomato")) {
			return Items.BEETROOT;
		}
		if (name.contains("chicken") || name.contains("pheasant")) {
			return Items.COOKED_CHICKEN;
		}
		if (name.contains("mutton") || name.contains("goat")) {
			return Items.COOKED_MUTTON;
		}
		if (name.contains("horker") || name.contains("mammoth") || name.contains("venison") || name.contains("beef") || name.contains("dog")) {
			return Items.COOKED_BEEF;
		}
		if (name.contains("rabbit") || name.contains("hare")) {
			return Items.COOKED_RABBIT;
		}
		if (name.contains("raw") || name.contains("meat")) {
			return Items.BEEF;
		}
		return Items.COOKED_BEEF;
	}

	private static Item ingredient(String name) {
		if (name.contains("mushroom") || name.contains("cap") || name.contains("fungus")) {
			return name.contains("red") || name.contains("fly amanita") ? Items.RED_MUSHROOM : Items.BROWN_MUSHROOM;
		}
		if (name.contains("salt")) {
			return Items.SUGAR;
		}
		if (name.contains("wing") || name.contains("moth") || name.contains("butterfly")) {
			return Items.PHANTOM_MEMBRANE;
		}
		if (name.contains("eye")) {
			return Items.SPIDER_EYE;
		}
		if (name.contains("egg")) {
			return Items.EGG;
		}
		if (name.contains("bone") || name.contains("tooth") || name.contains("tusk") || name.contains("claw") || name.contains("horn")) {
			return Items.BONE;
		}
		if (name.contains("heart")) {
			return Items.FERMENTED_SPIDER_EYE;
		}
		if (name.contains("dust") || name.contains("ash") || name.contains("powder")) {
			return Items.GUNPOWDER;
		}
		if (name.contains("scale") || name.contains("skin") || name.contains("hide") || name.contains("pelt")) {
			return Items.RABBIT_HIDE;
		}
		if (name.contains("root") || name.contains("bark") || name.contains("wood")) {
			return Items.STICK;
		}
		if (name.contains("berr") || name.contains("grape")) {
			return Items.SWEET_BERRIES;
		}
		if (name.contains("honeycomb")) {
			return Items.HONEYCOMB;
		}
		if (name.contains("feather")) {
			return Items.FEATHER;
		}
		if (name.contains("frost") || name.contains("ice")) {
			return Items.SNOWBALL;
		}
		if (name.contains("fire") || name.contains("salts")) {
			return Items.BLAZE_POWDER;
		}
		if (name.contains("pearl") || name.contains("crystal")) {
			return Items.PRISMARINE_CRYSTALS;
		}
		return Items.POPPY;  // flowers and the rest of Skyrim's plants
	}

	private static Item misc(SkyLink.SkyItem item) {
		if (item.sub() == 1) {
			return item.has(Proto.ITEM_FILLED) ? Items.ENDER_EYE : Items.ENDER_PEARL;  // soul gems
		}
		if (item.sub() == 2) {
			return Items.TORCH;
		}
		if (item.sub() == 3) {
			return Items.IRON_NUGGET;  // lockpicks
		}
		String name = item.name().toLowerCase();
		if (name.contains("ingot")) {
			return name.contains("gold") ? Items.GOLD_INGOT : name.contains("copper") || name.contains("dwarven") ? Items.COPPER_INGOT : Items.IRON_INGOT;
		}
		if (name.contains("ore")) {
			return name.contains("gold") ? Items.RAW_GOLD : name.contains("copper") ? Items.RAW_COPPER : Items.RAW_IRON;
		}
		if (name.contains("diamond")) {
			return Items.DIAMOND;
		}
		if (name.contains("emerald")) {
			return Items.EMERALD;
		}
		if (name.contains("amethyst") || name.contains("garnet") || name.contains("ruby") || name.contains("sapphire")) {
			return Items.AMETHYST_SHARD;
		}
		if (name.contains("leather") || name.contains("hide") || name.contains("pelt")) {
			return Items.LEATHER;
		}
		if (name.contains("firewood") || name.contains("wood")) {
			return Items.STICK;
		}
		if (name.contains("dragon bone") || name.contains("bone")) {
			return Items.BONE;
		}
		if (name.contains("dragon scale")) {
			return Items.TURTLE_SCUTE;
		}
		if (name.contains("claw")) {
			return Items.GOLD_INGOT;
		}
		if (name.contains("goblet") || name.contains("tankard") || name.contains("cup") || name.contains("bowl") || name.contains("plate") || name.contains("pot")) {
			return Items.FLOWER_POT;
		}
		return Items.FLINT;
	}

	private static Item spell(SkyLink.SkyItem item) {
		return switch (item.sub()) {
			case Proto.SCHOOL_DESTRUCTION -> switch (item.material()) {
				case Proto.ELEMENT_FIRE -> Items.FIRE_CHARGE;
				case Proto.ELEMENT_FROST -> Items.SNOWBALL;
				case Proto.ELEMENT_SHOCK -> Items.BREEZE_ROD;
				default -> Items.BLAZE_POWDER;
			};
			case Proto.SCHOOL_RESTORATION -> Items.GLISTERING_MELON_SLICE;
			case Proto.SCHOOL_ALTERATION -> Items.AMETHYST_SHARD;
			case Proto.SCHOOL_ILLUSION -> Items.ENDER_EYE;
			case Proto.SCHOOL_CONJURATION -> Items.ECHO_SHARD;
			default -> Items.GLOWSTONE_DUST;
		};
	}
}
