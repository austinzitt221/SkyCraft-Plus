// SkyCraft+ : every Skyrim thing in Minecraft's creative menu
package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's things in Minecraft's creative menu, on tabs of their own (Fabric gives them a page of
 * their own): weapons, apparel, potions and food, ingredients, books and scrolls, everything else
 * (with a dragon soul), spells, shouts, and spawn eggs for Skyrim's people and creatures. Skyrim
 * lists them all (SkyCatalog); the client makes the tabs' items from it (SkyCreativeClient). An
 * item taken into the inventory is a stored Skyrim item (SkyStorage), so it goes into that
 * player's Skyrim at once: spells and shouts are learned (every word), a dragon soul absorbed. A
 * spawn egg brings one of that person or creature in the host's Skyrim where it's used.
 */
public final class SkyCreative {
	private SkyCreative() {
	}

	/** The tabs, in order: id, title, icon. */
	public enum Tab {
		WEAPONS("weapons", "Skyrim Weapons", () -> new ItemStack(Items.IRON_SWORD)),
		APPAREL("apparel", "Skyrim Apparel", () -> new ItemStack(Items.IRON_CHESTPLATE)),
		POTIONS("potions", "Skyrim Potions & Food", () -> new ItemStack(Items.POTION)),
		INGREDIENTS("ingredients", "Skyrim Ingredients", () -> new ItemStack(Items.NETHER_WART)),
		BOOKS("books", "Skyrim Books & Scrolls", () -> new ItemStack(Items.BOOK)),
		MISC("misc", "Skyrim Misc", () -> new ItemStack(Items.EMERALD)),
		SPELLS("spells", "Skyrim Spells", () -> new ItemStack(Items.ENCHANTED_BOOK)),
		SHOUTS("shouts", "Skyrim Shouts", () -> new ItemStack(Items.DRAGON_BREATH)),
		EGGS("spawn_eggs", "Skyrim Spawn Eggs", () -> new ItemStack(Items.VILLAGER_SPAWN_EGG)),
		GEO("geo", "Skyrim Blocks", () -> new ItemStack(Items.GRASS_BLOCK));  // SkyCraft+ : Skyrim's textures as blocks (SkyGeo)

		final String id;
		final String title;
		final Supplier<ItemStack> icon;

		Tab(String id, String title, Supplier<ItemStack> icon) {
			this.id = id;
			this.title = title;
			this.icon = icon;
		}

		/** Which tab a Skyrim thing goes on. */
		public static @Nullable Tab of(int kind) {
			return switch (kind) {
				case Proto.SKY_WEAPON -> WEAPONS;
				case Proto.SKY_APPAREL -> APPAREL;
				case Proto.SKY_POTION, Proto.SKY_FOOD -> POTIONS;
				case Proto.SKY_INGREDIENT -> INGREDIENTS;
				case Proto.SKY_BOOK, Proto.SKY_SCROLL -> BOOKS;
				case Proto.SKY_KEY, Proto.SKY_MISC, Proto.SKY_AMMO -> MISC;
				case Proto.SKY_SPELL -> SPELLS;
				case Proto.SKY_SHOUT -> SHOUTS;
				case Proto.SKY_ACTOR -> EGGS;
				case Proto.SKY_GEO -> GEO;
				default -> null;
			};
		}
	}

	/** Each tab's items, as the client made them from Skyrim's list (empty until Skyrim has sent it). */
	private static volatile Map<Tab, List<ItemStack>> contents = Map.of();

	public static final ResourceKey<Item> EGG_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_spawn_egg"));
	public static final Item EGG = Registry.register(BuiltInRegistries.ITEM, EGG_KEY, new Egg(new Item.Properties().setId(EGG_KEY)));
	private static final String EGG_TAG = "skycraft_egg";

	/** The dragon soul's stand-in Skyrim item (Skyrim's side: plugin "SkyCraft", id 1). */
	public static final String SOUL_PLUGIN = "SkyCraft";
	public static final int SOUL_ID = 1;

	public static void init() {
		for (Tab tab : Tab.values()) {
			CreativeModeTab built = FabricCreativeModeTab.builder()
				.title(Component.literal(tab.title))
				.icon(tab.icon)
				.displayItems((parameters, output) -> {
					for (ItemStack stack : contents.getOrDefault(tab, List.of())) {
						output.accept(stack.copy());
					}
				})
				.build();
			Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_" + tab.id), built);
		}
	}

	/** From the client: the tabs' items (the creative menu rebuilds them when it next opens). */
	public static void setContents(Map<Tab, List<ItemStack>> byTab) {
		contents = Map.copyOf(byTab);
	}

	/** A dragon soul for the creative menu: taken into the inventory, Skyrim's player absorbs it. */
	public static ItemStack dragonSoul() {
		ItemStack stack = SkyStorage.make(new SkyStorage.Stored(SOUL_PLUGIN, SOUL_ID, Proto.SKY_MISC, 0, 0, 0, 1.0F, -1.0F, 0, 0.0F, 0, 0.0F, 0.0F, "Dragon Soul",
			"minecraft:dragon_breath", 0, true), 1);
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Take it into your inventory: your Skyrim self absorbs it").withStyle(st -> st.withItalic(false)
			.withColor(ChatFormatting.GRAY)))));
		return stack;
	}

	// ---- spawn eggs ----

	/** Minecraft's spawn egg each kind of Skyrim creature looks like (SkyItem::material of a SKY_ACTOR; Skyrim's EggLook). */
	private static final String[] EGG_LOOKS = { "villager", "wolf", "polar_bear", "spider", "zombie", "skeleton", "chicken", "cow", "goat", "goat", "horse", "fox", "rabbit",
		"turtle", "ravager", "iron_golem", "blaze", "ender_dragon", "silverfish", "cod", "dolphin", "ocelot", "creaking", "vex", "zombie_villager", "cave_spider", "evoker",
		"copper_golem", "witch", "ghast" };

	/** A spawn egg for a Skyrim person or creature. */
	public static ItemStack egg(SkyLink.SkyItem actor) {
		ItemStack stack = new ItemStack(EGG);
		CompoundTag tag = new CompoundTag();
		tag.putString("plugin", actor.plugin());
		tag.putInt("id", actor.localId());
		tag.putString("name", actor.name());
		CompoundTag root = new CompoundTag();
		root.put(EGG_TAG, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		String look = actor.sub() == 0 ? "villager" : actor.material() > 0 && actor.material() < EGG_LOOKS.length ? EGG_LOOKS[actor.material()] : "zombie";
		Item standIn = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(look + "_spawn_egg")).orElse(Items.VILLAGER_SPAWN_EGG);
		Identifier model = standIn.components().get(DataComponents.ITEM_MODEL);
		if (model != null) {
			stack.set(DataComponents.ITEM_MODEL, model);
		}
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(actor.name() + " Spawn Egg").withStyle(st -> st.withItalic(false).withColor(ChatFormatting.WHITE)));
		List<Component> lore = new java.util.ArrayList<>(List.of(Component.literal(actor.sub() == 0 ? "Skyrim person" : "Skyrim creature")
			.withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GRAY)), Component.literal(actor.plugin()).withStyle(st -> st.withItalic(false)
			.withColor(ChatFormatting.DARK_GRAY))));
		// SkyCraft+ : a quest's person (a quest names them, or they're essential): another of them can break it.
		if ((actor.flags() & Proto.ITEM_QUEST) != 0) {
			lore.add(Component.literal("Warning: a quest NPC. Spawning another").withStyle(st -> st.withItalic(false).withColor(ChatFormatting.RED)));
			lore.add(Component.literal("may break your game's quests.").withStyle(st -> st.withItalic(false).withColor(ChatFormatting.RED)));
		}
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/** Skyrim's form ids of the actors in its list, by "plugin:id" (this run's load order). */
	private static volatile Map<String, Integer> actorIds = Map.of();
	private static int actorIdsGeneration = -2;

	private static synchronized int formIdOf(String plugin, int localId) {
		int generation = SkyLink.skyCatalogGeneration();
		if (generation != actorIdsGeneration && generation > 0) {
			List<SkyLink.SkyItem> items = SkyLink.readSkyCatalog();
			if (items != null) {
				Map<String, Integer> ids = new HashMap<>();
				for (SkyLink.SkyItem item : items) {
					if (item.kind() == Proto.SKY_ACTOR) {
						ids.put(item.plugin().toLowerCase(Locale.ROOT) + ":" + item.localId(), item.formId());
					}
				}
				actorIds = ids;
				actorIdsGeneration = generation;
			}
		}
		return actorIds.getOrDefault(plugin.toLowerCase(Locale.ROOT) + ":" + localId, 0);
	}

	/** Spawns its Skyrim person or creature (in the host's Skyrim: this server's) at a place. */
	private static InteractionResult spawn(Level level, @Nullable Player player, ItemStack stack, Vec3 at) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty(EGG_TAG);
		int formId = SkyLink.active() ? formIdOf(tag.getStringOr("plugin", ""), tag.getIntOr("id", 0)) : 0;
		if (formId == 0) {
			if (player != null) {
				player.sendOverlayMessage(Component.literal("This Skyrim doesn't have " + tag.getStringOr("name", "them") + " (is its mod loaded?)").withStyle(ChatFormatting.YELLOW));
			}
			return InteractionResult.FAIL;
		}
		SkyLink.pushEvent(Proto.EV_SPAWN_ACTOR, formId, (float) at.x, (float) at.y, (float) at.z, 0.0F, 0);
		SkyCraft.LOG.info("SkyCraft: spawn egg: {} at {}", tag.getStringOr("name", "?"), BlockPos.containing(at));
		if (player == null || !player.hasInfiniteMaterials()) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	/** A Skyrim spawn egg: on a block (Minecraft's), or used in the air (on Skyrim's ground, which isn't a block: a few steps ahead). */
	private static final class Egg extends Item {
		Egg(Properties properties) {
			super(properties);
		}

		@Override
		public InteractionResult useOn(UseOnContext context) {
			BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
			return spawn(context.getLevel(), context.getPlayer(), context.getItemInHand(), Vec3.atBottomCenterOf(pos));
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			Vec3 look = player.getLookAngle();
			double flat = Math.max(1.0E-4, Math.sqrt(look.x * look.x + look.z * look.z));
			Vec3 at = player.position().add(look.x / flat * 3.0, 0.5, look.z / flat * 3.0);
			return spawn(level, player, player.getItemInHand(hand), at);
		}
	}
}
