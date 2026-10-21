// SkyCraft+ : beds
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Minecraft's beds in Skyrim's world. Dying reloads Skyrim's last save, which puts the player where
 * that save was made; with a bed (a respawn point set in one of Skyrim's dimensions), the player is
 * then taken on to it, as in Minecraft: the server tells the player's client where it respawned
 * (BedRespawn), and once Skyrim has reloaded the client asks its Skyrim to go there (SkyBedsClient,
 * Proto.EV_GO_TO_BED). Loading the save again goes back to its own place. Setting bedsInSkyrim.
 */
public final class SkyBeds {
	private SkyBeds() {
	}

	/** Server -> client: the player died and came back at its bed, here (MC coords) in this dimension (Proto.DIM_*). */
	public record BedRespawn(int dimension, double x, double y, double z, float yaw) implements CustomPacketPayload {
		public static final Type<BedRespawn> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "bed_respawn"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BedRespawn> CODEC = StreamCodec.of((buf, b) -> {
			buf.writeVarInt(b.dimension);
			buf.writeDouble(b.x);
			buf.writeDouble(b.y);
			buf.writeDouble(b.z);
			buf.writeFloat(b.yaw);
		}, buf -> new BedRespawn(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(BedRespawn.TYPE, BedRespawn.CODEC);
	}

	/** Proto.DIM_* of one of Skyrim's dimensions, or -1. */
	public static int skyrimDimension(ResourceKey<Level> dimension) {
		if (dimension == Level.OVERWORLD) {
			return Proto.DIM_TAMRIEL;
		}
		if (dimension == SkyInteriors.INTERIORS) {
			return Proto.DIM_INTERIORS;
		}
		return dimension.identifier().getPath().equals("worldspaces") && dimension.identifier().getNamespace().equals(SkyCraft.MOD_ID) ? Proto.DIM_WORLDSPACES : -1;
	}

	/** Where a dead player respawns was chosen (ServerPlayerRespawnMixin): at its bed, its client is told. */
	public static void respawning(ServerPlayer player, TeleportTransition chosen) {
		if (player.isAlive() || player.getRespawnConfig() == null || chosen.missingRespawnBlock() || !dev.skycraft.SkySettings.flag("bedsInSkyrim", true)) {
			return;
		}
		int dimension = skyrimDimension(chosen.newLevel().dimension());
		if (dimension < 0 || !ServerPlayNetworking.canSend(player, BedRespawn.TYPE)) {
			return;
		}
		var at = chosen.position();
		SkyCraft.LOG.info("SkyCraft: {} respawns at their bed ({}, {}, {} in {}); their Skyrim goes there after it reloads", player.getPlainTextName(),
			Math.round(at.x), Math.round(at.y), Math.round(at.z), chosen.newLevel().dimension().identifier());
		ServerPlayNetworking.send(player, new BedRespawn(dimension, at.x, at.y, at.z, chosen.yRot()));
	}
}
