package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer: every player has their own Skyrim, talking to their own Minecraft client. The host's
 * Skyrim reaches the host's integrated server through shared memory; a guest's Skyrim reaches the
 * host's server through these packets instead.
 */
public final class SkyNet {
	private SkyNet() {
	}

	/** Guest -> server: the guest's Skyrim hit them (as proto::InputEvent kInHurt). */
	public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) implements CustomPacketPayload {
		public static final Type<Hurt> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "hurt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hurt> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hurt::kind,
			ByteBufCodecs.FLOAT, Hurt::skyrimDamage,
			ByteBufCodecs.INT, Hurt::attackerFormId,
			ByteBufCodecs.VAR_INT, Hurt::flags,
			Hurt::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> guest: the guest died in Minecraft, so their Skyrim player dies too. */
	public record Died(int attackerFormId) implements CustomPacketPayload {
		public static final Type<Died> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "died"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Died> CODEC = StreamCodec.composite(ByteBufCodecs.INT, Died::attackerFormId, Died::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** SkyCraft+ : server -> guest: the Ender Dragon died in the guest's fight; their Skyrim gets a dragon soul. */
	public record DragonSoul() implements CustomPacketPayload {
		public static final Type<DragonSoul> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dragon_soul"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DragonSoul> CODEC = StreamCodec.unit(new DragonSoul());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** SkyCraft+ : one Skyrim dragon soul for this player: the host's through the link, a guest's through theirs. */
	public static void giveDragonSoul(ServerPlayer player) {
		if (isHost(player)) {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_DRAGON_SOUL, 0, 0.0F, 0.0F, 0.0F, 0.0F, 0);
			}
		} else if (ServerPlayNetworking.canSend(player, DragonSoul.TYPE)) {
			ServerPlayNetworking.send(player, new DragonSoul());
		}
		SkyCraft.LOG.info("SkyCraft: the Ender Dragon died; a dragon soul for {}", player.getPlainTextName());
	}

	/** Client -> server: the player hit Skyrim's geometry in this cell (SkyDig.open). */
	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cells around a broken dug block that are inside Skyrim's geometry (SkyDig.reveal). */
	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * SkyCraft+ : multiplayer. Something a player's Skyrim did that Minecraft's server carries out:
	 * use a block by hand (Skyrim mode's E), heal (a Skyrim spell or potion), take over Skyrim's share
	 * of health, a Skyrim melee hit, which hands Skyrim holds. The host's client runs it on its own
	 * server; a guest's sends it to the host's.
	 */
	public record Act(int kind, int code, float a, float b, BlockPos pos, int face, float hx, float hy, float hz) implements CustomPacketPayload {
		public static final int USE_BLOCK = 0, HEAL = 1, SKYRIM_HIT = 2, SET_HEALTH = 3, HANDS = 4, BLOCK_HIT = 5, INTO = 6, FEED = 7, POISON = 8, RECHARGE = 9,
			USE_ENTITY = 10, SELF = 11;
		public static final Type<Act> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "act"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Act> CODEC = StreamCodec.of((buf, act) -> {
			buf.writeVarInt(act.kind);
			buf.writeVarInt(act.code);
			buf.writeFloat(act.a);
			buf.writeFloat(act.b);
			buf.writeBlockPos(act.pos);
			buf.writeVarInt(act.face);
			buf.writeFloat(act.hx);
			buf.writeFloat(act.hy);
			buf.writeFloat(act.hz);
		}, buf -> new Act(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readBlockPos(), buf.readVarInt(), buf.readFloat(), buf.readFloat(),
			buf.readFloat()));

		public static Act simple(int kind, int code, float a, float b) {
			return new Act(kind, code, a, b, BlockPos.ZERO, 0, 0.0F, 0.0F, 0.0F);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}


	/** SkyCraft+ : interiors in multiplayer. Skyrim's own kinds of world only (Tamriel, interiors, other worldspaces): never into or out of the Nether or the End. */
	private static void into(ServerPlayer player, int dimension, double x, double y, double z, float yaw, float pitch) {
		var server = player.level().getServer();
		var target = server == null ? null : server.getLevel(skyrimDimension(dimension));
		if (target == null || skyrimDimension(player.level().dimension()) == null || target == player.level() || !Double.isFinite(x + y + z)) {
			return;
		}
		var pets = dev.skycraft.world.SkyPets.company(player);  // SkyCraft+ : pets come through the door too
		player.teleportTo(target, x, y, z, java.util.Set.of(), yaw, pitch, true);
		dev.skycraft.world.SkyPets.bringAlong(player, pets);
		player.resetFallDistance();
		SkyCraft.LOG.info("SkyCraft: {} into {}", player.getName().getString(), target.dimension().identifier());
	}

	private static @org.jspecify.annotations.Nullable ResourceKey<net.minecraft.world.level.Level> skyrimDimension(int code) {
		return switch (code) {
			case dev.skycraft.link.Proto.DIM_TAMRIEL -> net.minecraft.world.level.Level.OVERWORLD;
			case dev.skycraft.link.Proto.DIM_INTERIORS -> dev.skycraft.world.SkyInteriors.INTERIORS;
			case dev.skycraft.link.Proto.DIM_WORLDSPACES -> WORLDSPACES;
			default -> null;
		};
	}

	private static @org.jspecify.annotations.Nullable Integer skyrimDimension(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
		return dimension == net.minecraft.world.level.Level.OVERWORLD ? Integer.valueOf(dev.skycraft.link.Proto.DIM_TAMRIEL)
			: dimension == dev.skycraft.world.SkyInteriors.INTERIORS ? Integer.valueOf(dev.skycraft.link.Proto.DIM_INTERIORS)
			: dimension == WORLDSPACES ? Integer.valueOf(dev.skycraft.link.Proto.DIM_WORLDSPACES) : null;
	}

	private static final ResourceKey<net.minecraft.world.level.Level> WORLDSPACES = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
		Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "worldspaces"));
	/** SkyCraft+ : multiplayer. Which hands each guest's Skyrim holds (bit 1: right, 2: left). */
	private static final java.util.Map<java.util.UUID, Integer> GUEST_HANDS = new java.util.concurrent.ConcurrentHashMap<>();

	public static int guestHands(ServerPlayer player) {
		return GUEST_HANDS.getOrDefault(player.getUUID(), 0) & 3;
	}

	/** SkyCraft+ : Skyrim weapons break blocks. As guestHands, with what they hold (Proto.HAND_*) in bits 8-15 (right) and 16-23 (left). */
	public static int guestHandsAndKinds(ServerPlayer player) {
		return GUEST_HANDS.getOrDefault(player.getUUID(), 0);
	}

	/**
	 * SkyCraft+ : held items in multiplayer. Server -> every player: which hands a player's Skyrim
	 * holds and with what (as guestHandsAndKinds), so their body holds its Minecraft stand-in on
	 * everyone's screen, not only on their own.
	 */
	public record HandsOf(int entityId, int hands) implements CustomPacketPayload {
		public static final Type<HandsOf> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "hands_of"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HandsOf> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, HandsOf::entityId, ByteBufCodecs.INT, HandsOf::hands, HandsOf::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static final java.util.Map<java.util.UUID, Integer> SENT_HANDS = new java.util.HashMap<>();

	/** A player's Skyrim hands and what they hold: the host's are read here directly, a guest's came in an Act. */
	private static int handsOf(ServerPlayer player) {
		if (isHost(player)) {
			return (SkyCombat.rightHandSkyrim ? 1 : 0) | (SkyCombat.leftHandSkyrim ? 2 : 0) | (SkyCombat.rightHandKind & 0xFF) << 8
				| (SkyCombat.leftHandKind & 0xFF) << 16;
		}
		return guestHandsAndKinds(player);
	}

	/** Sends each player's hands to everyone when they change, and all of them now and then (for players who just joined). */
	private static void sendHands(net.minecraft.server.MinecraftServer server) {
		var players = server.getPlayerList().getPlayers();
		if (players.size() < 2) {
			SENT_HANDS.clear();
			return;
		}
		boolean all = server.getTickCount() % 40 == 0;
		for (ServerPlayer player : players) {
			int hands = handsOf(player);
			Integer sent = SENT_HANDS.put(player.getUUID(), hands);
			if (!all && sent != null && sent == hands) {
				continue;
			}
			var payload = new HandsOf(player.getId(), hands);
			for (ServerPlayer to : players) {
				if (to != player && ServerPlayNetworking.canSend(to, HandsOf.TYPE)) {
					ServerPlayNetworking.send(to, payload);
				}
			}
		}
	}

	/** Carries out a player's Skyrim action on the server thread (amounts kept sane: a guest's client sent them). */
	public static void apply(ServerPlayer player, Act act) {
		switch (act.kind) {
			case Act.USE_BLOCK -> {
				var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(act.hx, act.hy, act.hz),
					net.minecraft.core.Direction.from3DDataValue(act.face), act.pos, false);
				if (player.getEyePosition().distanceToSqr(hit.getLocation()) > 8.0 * 8.0
					|| player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(act.pos)) > 8.0 * 8.0
					|| !player.level().mayInteract(player, act.pos)) {
					return;  // (the block itself in reach, and not spawn-protected)
				}
				var state = player.level().getBlockState(act.pos);
				var result = state.useWithoutItem(player.level(), player, hit);
				SkyCraft.LOG.info("SkyCraft: Skyrim mode use: {} at {}: {}", state.getBlock().getName().getString(), act.pos.toShortString(), result);
			}
			// SkyCraft+ : Skyrim mode's Activate on a Minecraft villager, item frame, chest minecart or tamed animal (code: its id):
			// used by hand (an empty one: the item held isn't), never ridden (horses, boats and minecarts are Minecraft mode's).
			case Act.USE_ENTITY -> {
				var entity = player.level().getEntity(act.code);
				if (entity == null || player.distanceToSqr(entity) > 8.0 * 8.0) {
					return;
				}
				boolean rides = entity instanceof net.minecraft.world.entity.animal.equine.AbstractHorse
					|| entity instanceof net.minecraft.world.entity.vehicle.VehicleEntity && !(entity instanceof net.minecraft.world.entity.vehicle.ContainerEntity);
				if (rides) {
					player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Ride it in Minecraft mode"));
					return;
				}
				var held = player.getMainHandItem();
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
				try {
					var result = player.interactOn(entity, net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.Vec3(act.hx, act.hy, act.hz));
					SkyCraft.LOG.info("SkyCraft: Skyrim mode use: {}: {}", entity.getName().getString(), result);
				} finally {
					player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
				}
			}
			case Act.HEAL -> {
				if (player.isAlive()) {
					player.heal(Math.max(0.0F, Math.min(act.a, 1.0F)) * player.getMaxHealth());
				}
			}
			case Act.SET_HEALTH -> {
				if (!player.isDeadOrDying()) {
					float before = player.getHealth();
					player.setHealth(Math.max(1.0F, Math.max(0.0F, Math.min(act.a, 1.0F)) * player.getMaxHealth()));
					SkyCraft.LOG.info("SkyCraft: health {} -> {} (Skyrim's {}%)", before, player.getHealth(), Math.round(act.a * 100.0F));
				}
			}
			// SkyCraft+ : Skyrim food and stamina potions feed Minecraft's hunger (code: points).
			case Act.FEED -> {
				if (player.isAlive()) {
					player.getFoodData().eat(Math.max(0, Math.min(act.code, 20)), 0.6F);
				}
			}
			// SkyCraft+ : a Skyrim poison onto the Minecraft weapon in hand (a: its damage).
			case Act.POISON -> SkyPoison.coat(player, Math.max(0.0F, Math.min(act.a, 24.0F)));
			// SkyCraft+ : a soul gem's charge into the Minecraft weapon's Skyrim enchantment (a: the charge).
			case Act.RECHARGE -> dev.skycraft.craft.SkyEnchanting.recharge(player, Math.max(0.0F, Math.min(act.a, 3000.0F)));
			case Act.SKYRIM_HIT -> SkyCombat.skyrimMelee(player, act.code, Math.max(0.0F, Math.min(act.a, 1000.0F)), Math.max(0.0F, Math.min(act.b, 8.0F)));
			case Act.HANDS -> GUEST_HANDS.put(player.getUUID(), act.code & 0xFFFF03);
			case Act.SELF -> dev.skycraft.combat.SkyShouts.guestSelf(player, act.code & 0xFF);  // SkyCraft+ : the shouts on a guest
			// SkyCraft+ : Skyrim weapons break blocks. The swing landed on this Minecraft block.
			case Act.BLOCK_HIT -> dev.skycraft.combat.SkyWeaponTools.hit(player, act.pos, (act.code & dev.skycraft.link.Proto.SKYRIM_HIT_LEFT) != 0, (act.code & dev.skycraft.link.Proto.SKYRIM_HIT_POWER) != 0);
			// SkyCraft+ : interiors in multiplayer. The guest's Skyrim went into an interior, another
			// worldspace or back out: its Minecraft player goes to that dimension, as the host's does.
			case Act.INTO -> into(player, act.code, act.hx, act.hy, act.hz, act.a, act.b);
			default -> {
			}
		}
	}

	/** SkyCraft+ : client (a guest) -> server: the guest's Skyrim spells, shouts and arrows now (SkyMagic). */
	public record Shots(java.util.List<dev.skycraft.link.SkyLink.Shot> shots) implements CustomPacketPayload {
		public static final Type<Shots> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "shots"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Shots> CODEC = StreamCodec.of((buf, payload) -> {
			int count = Math.min(payload.shots().size(), 64);
			buf.writeVarInt(count);
			for (int i = 0; i < count; i++) {
				var s = payload.shots().get(i);
				buf.writeInt(s.id());
				buf.writeInt(s.flags());
				buf.writeFloat(s.x0());
				buf.writeFloat(s.y0());
				buf.writeFloat(s.z0());
				buf.writeFloat(s.x1());
				buf.writeFloat(s.y1());
				buf.writeFloat(s.z1());
				buf.writeFloat(s.radius());
				buf.writeFloat(s.damage());
				buf.writeFloat(s.push());
				buf.writeVarInt(s.seconds());
			}
		}, buf -> {
			int count = Math.min(buf.readVarInt(), 64);
			var list = new java.util.ArrayList<dev.skycraft.link.SkyLink.Shot>(count);
			for (int i = 0; i < count; i++) {
				list.add(new dev.skycraft.link.SkyLink.Shot(buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));
			}
			return new Shots(list);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Shots.TYPE, Shots.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Shots.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// Within reason, whatever the guest's client claims (friends only, but still).
			var shots = payload.shots().stream().map(s -> new dev.skycraft.link.SkyLink.Shot(s.id(), s.flags(), s.x0(), s.y0(), s.z0(), s.x1(), s.y1(), s.z1(),
				Math.min(Math.max(s.radius(), 0.0F), 32.0F), Math.min(Math.max(s.damage(), 0.0F), 200.0F), Math.min(Math.max(s.push(), 0.0F), 10.0F), Math.min(Math.max(s.seconds(), 0), 60))).toList();
			context.server().execute(() -> dev.skycraft.combat.SkyMagic.guestShots(player, shots));
		});
		PayloadTypeRegistry.serverboundPlay().register(Act.TYPE, Act.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Act.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> apply(player, payload));
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			GUEST_HANDS.remove(handler.player.getUUID());
			server.execute(() -> SENT_HANDS.remove(handler.player.getUUID()));
		});
		PayloadTypeRegistry.serverboundPlay().register(Hurt.TYPE, Hurt.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigOpen.TYPE, DigOpen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigReveal.TYPE, DigReveal.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DigOpen.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> SkyDig.open(player, payload.world(), payload.pos(), payload.material()));
		});
		ServerPlayNetworking.registerGlobalReceiver(DigReveal.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int[] materials = payload.materials().stream().mapToInt(Integer::intValue).toArray();
			context.server().execute(() -> SkyDig.reveal(player, payload.world(), payload.cells(), materials));
		});
		PayloadTypeRegistry.clientboundPlay().register(Died.TYPE, Died.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DragonSoul.TYPE, DragonSoul.CODEC); // SkyCraft+
		// SkyCraft+ : held items in multiplayer.
		PayloadTypeRegistry.clientboundPlay().register(HandsOf.TYPE, HandsOf.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 == 0) {
				sendHands(server);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Hurt.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// A hit's worth of damage, whatever the guest's client claims (friends only, but still).
			float damage = Math.max(0.0F, Math.min(payload.skyrimDamage(), 10000.0F));
			context.server().execute(() -> SkyCombat.hurtPlayer(player, payload.kind(), damage, payload.attackerFormId(), payload.flags()));
		});
	}

	/** True if this player plays on this machine (their Skyrim is on the shared-memory link). */
	public static boolean isHost(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.isSingleplayerOwner(player.nameAndId());
	}
}
