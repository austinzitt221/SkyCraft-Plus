// SkyCraft+ : Skyrim's ore veins
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's ore veins, mined the Minecraft way. A vein's cells are this block (it looks like iron
 * ore); mined with a pickaxe (Minecraft's, or Skyrim's own) it drops nothing in Minecraft: the
 * vein's own ore goes into the miner's Skyrim inventory instead, iron, corundum, ebony, whatever the
 * vein holds (as Skyrim's own mining gives it), a piece a block.
 */
public final class SkyOreVein extends Block {
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_ore_vein"));
	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY,
		new SkyOreVein(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).requiresCorrectToolForDrops().strength(3.0F, 3.0F).setId(KEY)));

	private SkyOreVein(BlockBehaviour.Properties properties) {
		super(properties);
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(MinedOre.TYPE, MinedOre.CODEC);
	}

	@Override
	public void playerDestroy(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack destroyedWith) {
		super.playerDestroy(level, player, pos, state, blockEntity, destroyedWith);
		give(player, pos);
	}

	/** This player's Skyrim gets the ore of the vein at this cell: the host's through the link, a guest's through theirs. */
	static void give(ServerPlayer player, BlockPos pos) {
		if (SkyNet.isHost(player)) {
			if (SkyLink.active()) {
				SkyLink.pushEvent(Proto.EV_MINED_ORE, 0, pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F, 0.0F, 0);
			}
		} else if (ServerPlayNetworking.canSend(player, MinedOre.TYPE)) {
			ServerPlayNetworking.send(player, new MinedOre(pos));
		}
	}

	/** Server -> guest: a block of a Skyrim ore vein mined here; the guest's Skyrim gets its ore. */
	public record MinedOre(BlockPos pos) implements CustomPacketPayload {
		public static final Type<MinedOre> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "mined_ore"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MinedOre> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, MinedOre::pos, MinedOre::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
