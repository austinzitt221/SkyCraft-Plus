// SkyCraft+ : the restoration brush
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The restoration brush: puts back what was dug out of Skyrim's world, a block's worth a use. Used
 * at a hole in Skyrim's ground, a rock or a cave wall, the first dug cell it points into gets
 * Skyrim's own geometry back (its ground, its stone path, its rock, drawn and walked on as before);
 * a dug cell deep inside the ground (a tunnel) is filled with the block it was made of instead, as
 * nothing of Skyrim's is seen there. Each use wears it a little; made from a stick, cobblestone and
 * dirt. The digging player's client picks the cell (it knows Skyrim's geometry around it).
 */
public final class SkyRestore extends Item {
	public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "restoration_brush"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, KEY, new SkyRestore(new Item.Properties().setId(KEY).durability(96)
		.component(DataComponents.LORE, new ItemLore(List.of(Component.literal("Use it on a hole dug in Skyrim's ground or rock:").withStyle(s -> s.withItalic(false)
			.withColor(ChatFormatting.GRAY)), Component.literal("it puts back what was there").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))))));

	/** The client's side (SkyRestoreClient): picks the cell and asks for it. True if there was one. */
	public static volatile java.util.function.Predicate<Player> clientUse = player -> false;

	private SkyRestore(Item.Properties properties) {
		super(properties);
	}

	/** Client -> server: put back this dug cell: Skyrim's geometry (fill -1), or the block a Proto.DIG_* material is (deep in the ground). */
	public record Restore(int world, BlockPos pos, int fill, boolean offHand) implements CustomPacketPayload {
		public static final Type<Restore> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "restore"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Restore> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, Restore::world,
			BlockPos.STREAM_CODEC, Restore::pos,
			ByteBufCodecs.INT, Restore::fill,
			ByteBufCodecs.BOOL, Restore::offHand,
			Restore::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Restore.TYPE, Restore.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Restore.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> restore(player, payload));
		});
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(output -> output.accept(ITEM));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return level.isClientSide() ? (clientUse.test(player) ? InteractionResult.SUCCESS : InteractionResult.PASS) : InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		return context.getLevel().isClientSide() ? (clientUse.test(player) ? InteractionResult.SUCCESS : InteractionResult.PASS) : InteractionResult.SUCCESS;
	}

	private static void restore(ServerPlayer player, Restore restore) {
		ServerLevel level = player.level();
		BlockPos pos = restore.pos();
		InteractionHand hand = restore.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack brush = player.getItemInHand(hand);
		if (!brush.is(ITEM) || !level.isLoaded(pos) || player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 8.0 * 8.0 || !SkyDig.isDug(level, restore.world(), pos)) {
			return;
		}
		BlockState here = level.getBlockState(pos);
		if (!here.isAir() && !here.canBeReplaced()) {
			return;
		}
		if (restore.fill() >= 0) {
			// Deep in the ground: the block it was, as a revealed one is (still dug: Skyrim has nothing here to draw).
			if (!level.getEntities(null, new AABB(pos)).stream().noneMatch(e -> e.isAlive() && e.blocksBuilding)) {
				return;
			}
			BlockState block = SkyDig.blockFor(level, pos, restore.fill());
			level.setBlock(pos, block, 3);
			level.playSound(null, pos, block.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
		} else {
			if (!SkyDig.unmarkDug(level, restore.world(), pos)) {
				return;
			}
			if (!here.isAir()) {
				level.removeBlock(pos, false);  // (grass, snow layers: gone with the ground that's back)
			}
			level.playSound(null, pos, SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 6, 0.35, 0.3, 0.35, 0.0);
		brush.hurtAndBreak(1, player, hand);
		SkyCraft.LOG.info("SkyCraft: {} restored {} at {}", player.getPlainTextName(), restore.fill() >= 0 ? "a block deep in the ground" : "Skyrim's geometry", pos);
	}
}
