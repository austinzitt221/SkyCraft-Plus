// SkyCraft+ : Skyrim food for animals
package dev.skycraft.combat;

import dev.skycraft.link.Proto;
import java.util.Locale;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * What animals eat, across the games (Proto.FOOD_* bits). Skyrim's food is told by its name (Skyrim's
 * items carry no "animal food" of their own): a Skyrim cabbage is a vegetable, venison is meat, a
 * salmon steak is fish, bone meal is a bone. Skyrim's animals eat the Skyrim food that suits them
 * (a horse an apple or a carrot, a fox berries or meat) besides the Minecraft food their Minecraft
 * kind eats (SkyBreeding.food), and Minecraft's animals eat the Skyrim food standing for theirs.
 */
public final class SkyFoods {
	private SkyFoods() {
	}

	private static final String[] SEEDS = { "seed" };
	private static final String[] GRAIN = { "wheat", "grain", "barley" };
	private static final String[] VEGETABLES = { "carrot", "cabbage", "leek", "lettuce", "gourd", "potato", "tomato", "onion", "radish" };
	private static final String[] FRUIT = { "apple", "pear" };
	private static final String[] BERRIES = { "berr", "grape", "jazbay" };
	private static final String[] MEAT = { "meat", "beef", "venison", "chicken breast", "pheasant", "leg of goat", "horker", "mammoth", "sausage", "steak", "ribs",
		"snout", "boar", "mutton", "haunch", "flank", "rabbit", "hare" };
	private static final String[] FISH = { "salmon", "fish", "char", "carp", "trout", "perch", "spadetail", "goby", "pike", "clam", "crab legs" };
	private static final String[] BONES = { "bone", "skull" };
	private static final String[] NOT_FOR_ANIMALS = { "stew", "soup", "pie", "potion", "elixir", "wine", "mead", "ale", "sweetroll", "bread", "cake", "tart" };

	/** What a Skyrim thing is as animal food: by its kind (food, ingredients, misc for bones) and name; 0 for nothing an animal eats. */
	public static int ofSkyrim(int kind, String name) {
		if (kind != Proto.SKY_FOOD && kind != Proto.SKY_INGREDIENT && kind != Proto.SKY_MISC) {
			return 0;
		}
		String n = name.toLowerCase(Locale.ROOT);
		if (any(n, BONES)) {
			return Proto.FOOD_BONE;
		}
		if (kind == Proto.SKY_MISC || any(n, NOT_FOR_ANIMALS)) {
			return 0;
		}
		int foods = 0;
		if (any(n, SEEDS)) {
			foods |= Proto.FOOD_SEEDS;
		}
		if (any(n, GRAIN)) {
			foods |= Proto.FOOD_WHEAT | Proto.FOOD_SEEDS;
		}
		if (any(n, VEGETABLES)) {
			foods |= Proto.FOOD_CARROT | Proto.FOOD_BEETROOT;
		}
		if (any(n, FRUIT)) {
			foods |= Proto.FOOD_APPLE;
		}
		if (any(n, BERRIES)) {
			foods |= Proto.FOOD_BERRIES;
		}
		if (any(n, FISH)) {
			foods |= Proto.FOOD_FISH;
		} else if (any(n, MEAT)) {
			foods |= Proto.FOOD_MEAT;
		}
		return foods;
	}

	private static boolean any(String name, String[] parts) {
		for (String part : parts) {
			if (name.contains(part)) {
				return true;
			}
		}
		return false;
	}

	/** What a Minecraft item tempts and feeds (the animals following it, McState.heldFood). */
	public static int ofMinecraft(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		Item item = stack.getItem();
		if (stack.is(ItemTags.CHICKEN_FOOD)) {
			return Proto.FOOD_SEEDS;
		}
		if (item == Items.WHEAT) {
			return Proto.FOOD_WHEAT;
		}
		if (item == Items.CARROT || item == Items.DANDELION) {
			return Proto.FOOD_CARROT;
		}
		if (item == Items.GOLDEN_CARROT) {
			return Proto.FOOD_CARROT | Proto.FOOD_APPLE;
		}
		if (item == Items.APPLE || item == Items.GOLDEN_APPLE) {
			return Proto.FOOD_APPLE;
		}
		if (item == Items.SWEET_BERRIES || item == Items.GLOW_BERRIES) {
			return Proto.FOOD_BERRIES;
		}
		if (item == Items.BONE) {
			return Proto.FOOD_MEAT | Proto.FOOD_BONE;
		}
		if (stack.is(ItemTags.MEAT)) {
			return Proto.FOOD_MEAT;
		}
		if (stack.is(ItemTags.FISHES)) {
			return Proto.FOOD_FISH;
		}
		return item == Items.BEETROOT ? Proto.FOOD_BEETROOT : 0;
	}

	/** What each of Skyrim's kinds of animal (SkyBreeding's) eats of Skyrim's food, the way it would. */
	public static int skyrimLikes(int species) {
		return switch (species) {
			case SkyBreeding.CHICKEN -> Proto.FOOD_SEEDS | Proto.FOOD_WHEAT;
			case SkyBreeding.COW, SkyBreeding.GOAT -> Proto.FOOD_WHEAT | Proto.FOOD_CARROT | Proto.FOOD_APPLE;
			case SkyBreeding.DEER, SkyBreeding.ELK -> Proto.FOOD_WHEAT | Proto.FOOD_CARROT | Proto.FOOD_APPLE | Proto.FOOD_BERRIES;
			case SkyBreeding.HARE -> Proto.FOOD_CARROT | Proto.FOOD_BERRIES;
			case SkyBreeding.HORSE -> Proto.FOOD_APPLE | Proto.FOOD_CARROT | Proto.FOOD_WHEAT;
			case SkyBreeding.FOX -> Proto.FOOD_BERRIES | Proto.FOOD_MEAT;
			case SkyBreeding.DOG, SkyBreeding.WOLF -> Proto.FOOD_MEAT;
			case SkyBreeding.MUDCRAB -> Proto.FOOD_FISH;
			case SkyBreeding.BOAR -> Proto.FOOD_BEETROOT | Proto.FOOD_CARROT | Proto.FOOD_APPLE;
			default -> 0;
		};
	}

	/** Minecraft items standing for each food, most usual first: the one a Minecraft animal is given for a Skyrim food. */
	private static final Object[][] STAND_INS = {
		{ Proto.FOOD_BONE, new Item[] { Items.BONE } },
		{ Proto.FOOD_SEEDS, new Item[] { Items.WHEAT_SEEDS } },
		{ Proto.FOOD_WHEAT, new Item[] { Items.WHEAT } },
		{ Proto.FOOD_CARROT, new Item[] { Items.CARROT } },
		{ Proto.FOOD_BEETROOT, new Item[] { Items.BEETROOT, Items.POTATO } },
		{ Proto.FOOD_APPLE, new Item[] { Items.APPLE } },
		{ Proto.FOOD_BERRIES, new Item[] { Items.SWEET_BERRIES } },
		{ Proto.FOOD_MEAT, new Item[] { Items.BEEF, Items.CHICKEN, Items.RABBIT } },
		{ Proto.FOOD_FISH, new Item[] { Items.COD, Items.SALMON } },
	};

	/**
	 * The Minecraft item a Skyrim food stands for, for this Minecraft animal: one it eats (breeds on,
	 * grows on, is tamed with); null if it eats none of these. A bone is given to a wolf whatever: its
	 * taming isn't its food.
	 */
	public static @Nullable ItemStack minecraftFood(Animal animal, int foods) {
		for (Object[] row : STAND_INS) {
			if ((foods & (Integer) row[0]) == 0) {
				continue;
			}
			for (Item item : (Item[]) row[1]) {
				ItemStack stack = new ItemStack(item);
				if (animal.isFood(stack) || item == Items.BONE && animal instanceof net.minecraft.world.entity.animal.wolf.Wolf) {
					return stack;
				}
			}
		}
		return null;
	}
}
