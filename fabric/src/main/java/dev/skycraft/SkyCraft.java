package dev.skycraft;

import dev.skycraft.combat.SkyCombat;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.entity.EquipmentSlot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkyCraft implements ModInitializer {
	public static final String MOD_ID = "skycraft";
	public static final String WORLD_NAME = "SkyCraft";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	private static final String KIT2_TAG = "skycraft_builder_kit";

	@Override
	public void onInitialize() {
		SkyCombat.init();
		dev.skycraft.combat.SkyWeaponTools.init();
		dev.skycraft.net.SkyNet.init();
		dev.skycraft.net.SkyShortcuts.init(); // SkyCraft+ : Skyrim shortcuts
		dev.skycraft.net.SkyStorage.init(); // SkyCraft+ : Skyrim items in Minecraft's containers
		dev.skycraft.world.SkyDig.init();
		dev.skycraft.world.SkySpawner.init();
		dev.skycraft.combat.SkyMagic.init();
		dev.skycraft.world.SkyUnderground.init();
		dev.skycraft.world.SkyMinecraftWorlds.init();
		dev.skycraft.world.SkyStructures.init();
		dev.skycraft.world.SkyInteriors.init();
		dev.skycraft.world.SkyHold.init();
		// SkyCraft+ : performance. A slow server tick says so (a hitch flying up to a structure while it
		// loads: which side it's on, for the next test).
		final long[] tickStart = { 0L, 0L, 0L };  // start, last logged, lines
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> tickStart[0] = System.nanoTime());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.nanoTime(), ms = (now - tickStart[0]) / 1_000_000L;
			if (ms > 150 && tickStart[2] < 200 && now - tickStart[1] > 1_000_000_000L) {
				tickStart[1] = now;
				tickStart[2]++;
				LOG.info("SkyCraft: slow server tick: {} ms", ms);
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(SkyCraft::configureServer);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (starterKits()) {
				giveStarterKit(handler.getPlayer());
				giveBuilderKit(handler.getPlayer());
			}
			dressTestGuest(handler.getPlayer());
		});
	}

	/**
	 * SkyCraft+ : no starter kit. Minecraft's ores, trees and mobs are in Skyrim's world now, so a
	 * new player starts empty-handed as in Minecraft. Setting: starterKit in config/skycraft.properties
	 * (true gives upstream's kits again).
	 */
	private static boolean starterKits() {
		java.util.Properties p = new java.util.Properties();
		java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		if (java.nio.file.Files.exists(config)) {
			try (java.io.Reader in = java.nio.file.Files.newBufferedReader(config)) {
				p.load(in);
			} catch (java.io.IOException e) {
				LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
			}
		}
		return Boolean.parseBoolean(p.getProperty("starterKit", "false").trim());
	}

	/** The mirror world is a void that only exists to host the player; Skyrim drives time and spawning. */
	private static void configureServer(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.PLAYER_MOVEMENT_CHECK, false, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
		rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
		LOG.info("SkyCraft: mirror world configured");
	}

	/**
	 * Local multiplayer test guests (tools/fake_guest.py; named Guest, Guest2, ...) wear a random
	 * mix of iron and diamond armour, so they're easy to tell apart.
	 */
	private static void dressTestGuest(ServerPlayer player) {
		if (!player.getName().getString().startsWith("Guest")) {
			return;
		}
		var random = player.getRandom();
		EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
		net.minecraft.world.item.Item[][] pieces = {
			{ Items.IRON_HELMET, Items.DIAMOND_HELMET },
			{ Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE },
			{ Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS },
			{ Items.IRON_BOOTS, Items.DIAMOND_BOOTS },
		};
		for (int i = 0; i < slots.length; i++) {
			player.setItemSlot(slots[i], new ItemStack(pieces[i][random.nextBoolean() ? 1 : 0]));
		}
		LOG.info("SkyCraft: dressed test guest {} in iron and diamond", player.getName().getString());
	}

	private static void giveStarterKit(ServerPlayer player) {
		if (!player.getInventory().isEmpty()) {
			return;
		}
		player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
		player.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
		player.getInventory().add(new ItemStack(Items.BOW));
		player.getInventory().add(new ItemStack(Items.COOKED_BEEF, 32));
		player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
		player.getInventory().add(new ItemStack(Items.TORCH, 32));
		player.getInventory().add(new ItemStack(Items.ARROW, 64));
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		LOG.info("SkyCraft: gave starter kit to {}", player.getName().getString());
	}

	/**
	 * Once per player: armor (Skyrim's enemies hit back now) and building materials, since there is
	 * no Minecraft terrain to mine in Skyrim.
	 */
	private static void giveBuilderKit(ServerPlayer player) {
		if (player.entityTags().contains(KIT2_TAG)) {
			return;
		}
		equipIfEmpty(player, EquipmentSlot.HEAD, Items.IRON_HELMET);
		equipIfEmpty(player, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
		equipIfEmpty(player, EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
		equipIfEmpty(player, EquipmentSlot.FEET, Items.IRON_BOOTS);
		var inventory = player.getInventory();
		inventory.add(new ItemStack(Items.COBBLESTONE, 64));
		inventory.add(new ItemStack(Items.STONE_BRICKS, 64));
		inventory.add(new ItemStack(Items.OAK_LOG, 64));
		inventory.add(new ItemStack(Items.GLASS, 64));
		inventory.add(new ItemStack(Items.OAK_STAIRS, 64));
		inventory.add(new ItemStack(Items.OAK_SLAB, 64));
		inventory.add(new ItemStack(Items.OAK_DOOR, 8));
		inventory.add(new ItemStack(Items.LADDER, 32));
		inventory.add(new ItemStack(Items.LANTERN, 16));
		inventory.add(new ItemStack(Items.CRAFTING_TABLE));
		inventory.add(new ItemStack(Items.WATER_BUCKET));
		inventory.add(new ItemStack(Items.ARROW, 64));
		inventory.add(new ItemStack(Items.GOLDEN_APPLE, 4));
		player.addTag(KIT2_TAG);
		LOG.info("SkyCraft: gave builder kit to {}", player.getName().getString());
	}

	private static void equipIfEmpty(ServerPlayer player, EquipmentSlot slot, net.minecraft.world.item.Item item) {
		if (player.getItemBySlot(slot).isEmpty()) {
			player.setItemSlot(slot, new ItemStack(item));
		} else {
			player.getInventory().add(new ItemStack(item));
		}
	}
}
