package dev.skycraft.client;

import dev.skycraft.combat.SkyCombat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class SkyCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		dev.skycraft.link.SkyLink.announceRunning();
		DiscordPresence.start();
		DestructionToggle.register();
		WorldSwitcher.register(); // SkyCraft+ : world switcher
		HandMixToggle.register(); // SkyCraft+ : hand mixing
		SkyInventoryPanels.register(); // SkyCraft+ : Skyrim's inventory in Minecraft's
		SkyShortcutsClient.register(); // SkyCraft+ : Skyrim shortcuts
		ClientTickEvents.END_CLIENT_TICK.register(SkyShortcutsClient::tick);
		SkyStorageClient.register(); // SkyCraft+ : Skyrim items in Minecraft's containers
		SkyBlockItemRenderer.register(); // SkyCraft+ : Skyrim blocks with their own texture as items
		ClientTickEvents.END_CLIENT_TICK.register(SkyStorageClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(SkyArmorClient::tick); // SkyCraft+ : Skyrim armor in Minecraft's armor slots
		ClientTickEvents.END_CLIENT_TICK.register(SkyDash::tick); // SkyCraft+ : Whirlwind Sprint
		ClientTickEvents.END_CLIENT_TICK.register(SkyCreativeClient::tick); // SkyCraft+ : every Skyrim thing in the creative menu
		RenderToggles.register(); // SkyCraft+ : experimental rendering switches
		SkyCraftingClient.register(); // SkyCraft+ : crafting across the games
		SkyLendingClient.register(); // SkyCraft+ : Minecraft's things at Skyrim's workbenches
		SkyRestoreClient.register(); // SkyCraft+ : the restoration brush
		SkySettingsScreen.register(); // SkyCraft+ : the SkyCraft+ settings screen
		SkyArrowsClient.register(); // SkyCraft+ : arrows across the games
		SkyBedsClient.register(); // SkyCraft+ : beds
		ClientTickEvents.END_CLIENT_TICK.register(SkyArrowsClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(SkyLendingClient::tick);
		// Multiplayer without editing files: the host opens their world to LAN (O, Open to LAN) and
		// e4mc gives them a link; friends type /join <link> in chat, and /leave to come back.
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("join")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("link", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> {
						String link = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "link");
						c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Joining " + link.trim() + "..."));
						// After the chat screen has closed: this leaves the current world.
						net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.joinFriend(net.minecraft.client.Minecraft.getInstance(), link));
						return 1;
					})));
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("leave").executes(c -> {
				net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.leaveFriend(net.minecraft.client.Minecraft.getInstance()));
				return 1;
			}));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SkyClient::clientTick);
		// Multiplayer testing on one PC: SKYCRAFT_LAN_PORT opens the world to LAN on that port as soon
		// as it's loaded, and SKYCRAFT_LAN_OFFLINE lets offline (dev) clients join it.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
			String port = System.getenv("SKYCRAFT_LAN_PORT");
			var server = minecraft.getSingleplayerServer();
			if (port == null || port.isBlank() || server == null || server.isPublished()) {
				return;
			}
			minecraft.execute(() -> {
				if (System.getenv("SKYCRAFT_LAN_OFFLINE") != null) {
					server.setUsesAuthentication(false);
				}
				boolean ok = server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, Integer.parseInt(port.trim()));
				dev.skycraft.SkyCraft.LOG.info("SkyCraft: world opened to LAN on port {} ({}{})", port.trim(), ok ? "ok" : "FAILED",
					System.getenv("SKYCRAFT_LAN_OFFLINE") != null ? ", offline logins allowed" : "");
			});
		});
		// A guest in a friend's world: dying there kills this player's own Skyrim character.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.Died.TYPE, (payload, context) -> {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_PLAYER_DIED, payload.attackerFormId(), 0, 0, 0, 0, 0);
			}
		});
		// SkyCraft+ : a guest in a friend's End: the Ender Dragon died, a dragon soul for this player's Skyrim.
		// SkyCraft+ : a Skyrim ore vein mined in the host's world: this guest's Skyrim gets its ore.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.world.SkyOreVein.MinedOre.TYPE, (payload, context) -> {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_MINED_ORE, 0, payload.pos().getX() + 0.5F, payload.pos().getY() + 0.5F,
					payload.pos().getZ() + 0.5F, 0.0F, 0);
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.DragonSoul.TYPE, (payload, context) -> {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_DRAGON_SOUL, 0, 0, 0, 0, 0, 0);
			}
		});
		// SkyCraft+ : held items in multiplayer. What other players' Skyrim hands hold.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.HandsOf.TYPE,
			(payload, context) -> SkyHands.otherPlayerHands(payload.entityId(), payload.hands()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> SkyHands.forgetOtherPlayers());
		// Skyrim draws the real NPC; its Minecraft stand-in is only a hitbox.
		EntityRendererRegistry.register(SkyCombat.SKYRIM_ACTOR, dev.skycraft.client.render.ProxyRenderer::new);  // SkyCraft+ : leads, no names
		// Players (client-side movement AND the integrated server's re-check of it) use the smooth
		// triangle collider, never Skyrim's voxels; otherwise the server sees the smooth position
		// dip into a voxel and teleports the player back every few ticks.
		dev.skycraft.world.SkyCollision.setSmoothCollider(e -> e instanceof net.minecraft.world.entity.player.Player && SkyClient.linked());
	}
}
