// SkyCraft+ : Skyrim crops on Minecraft farmland
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Skyrim's food grown on Minecraft's farmland. A Skyrim thing a Skyrim plant gives (a cabbage, a
 * leek, a potato...) used on farmland is planted there (Skyrim uses one up) and grows through
 * Minecraft's wheat stages, needing water and light as Minecraft's crops do. Fully grown, it
 * becomes that Skyrim plant (Skyrim places it on the farmland, kEvCropGrown), picked the Skyrim way
 * for two or three. Which Skyrim thing each crop is, by where it grows, is kept with the world
 * (skycraft-crops.txt). The plants grow in the host's Skyrim.
 */
public final class SkyCrops {
	private SkyCrops() {
	}

	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_crop"));
	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY,
		new SkyrimCropBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT).setId(KEY)));

	private record Planted(int formId, String name) {
	}

	private static final Map<Long, Planted> PLANTED = new HashMap<>();
	private static Path file;

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Plant.TYPE, Plant.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Plant.TYPE, (plant, context) -> context.server().execute(() -> plant(context.player(), plant)));
		ServerLifecycleEvents.SERVER_STARTED.register(SkyCrops::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(SkyCrops::tick);
	}

	private static void load(MinecraftServer server) {
		PLANTED.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("skycraft-crops.txt");
		if (!Files.exists(file)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(file)) {
				String[] f = line.split("\\|", 3);
				if (f.length == 3) {
					PLANTED.put(Long.parseLong(f[0]), new Planted(Integer.parseUnsignedInt(f[1], 16), f[2]));
				}
			}
		} catch (IOException | NumberFormatException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", file, e.toString());
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		PLANTED.forEach((pos, p) -> lines.add(pos + "|" + Integer.toHexString(p.formId()) + "|" + p.name()));
		try {
			Files.write(file, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}: {}", file, e.toString());
		}
	}

	/** Server: a Skyrim thing planted on the farmland at this spot (the player's Skyrim already gave one up). */
	private static void plant(ServerPlayer player, Plant plant) {
		ServerLevel level = player.level();
		BlockPos farmland = plant.farmland();
		BlockPos at = farmland.above();
		if (!level.getBlockState(farmland).is(Blocks.FARMLAND) || !level.getBlockState(at).isAir()
			|| player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at)) > 8.0 * 8.0) {
			return;
		}
		level.setBlock(at, BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		PLANTED.put(at.asLong(), new Planted(plant.formId(), plant.name()));
		save();
		level.playSound(null, at, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
		SkyCraft.LOG.info("SkyCraft: {} planted at {}", plant.name(), at.toShortString());
	}

	/** Grown: the crop becomes its Skyrim plant, standing on the farmland (the host's Skyrim places it). */
	static void grown(ServerLevel level, BlockPos pos) {
		// Only with the host's Skyrim in this world (it places the plant where its player is): a crop
		// grown meanwhile waits, ripe, until then (tick).
		if (!SkyLink.active() || !hostIn(level)) {
			return;
		}
		Planted planted = PLANTED.remove(pos.asLong());
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		save();
		if (planted == null) {
			return;
		}
		SkyLink.pushEvent(Proto.EV_CROP_GROWN, planted.formId(), pos.getX() + 0.5F, pos.getY() - 1.0F / 16.0F, pos.getZ() + 0.5F, 0.0F, 0);
		SkyCraft.LOG.info("SkyCraft: {} grew at {}", planted.name(), pos.toShortString());
	}

	private static boolean hostIn(ServerLevel level) {
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (SkyNet.isHost(player)) {
				return player.level() == level;
			}
		}
		return false;
	}

	/** Every few seconds: ripe crops that waited for the host become their Skyrim plants. */
	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 7 || PLANTED.isEmpty() || !SkyLink.active()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!SkyNet.isHost(player)) {
				continue;
			}
			ServerLevel level = player.level();
			for (Long key : PLANTED.keySet().toArray(Long[]::new)) {
				BlockPos pos = BlockPos.of(key);
				if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof SkyrimCropBlock crop && crop.isMaxAge(level.getBlockState(pos))) {
					grown(level, pos);
				}
			}
		}
	}

	/** Gone some other way (broken, trampled): forgotten. */
	static void removed(BlockPos pos) {
		if (PLANTED.remove(pos.asLong()) != null) {
			save();
		}
	}

	/** Client -> server: plant this Skyrim thing on that farmland. */
	public record Plant(BlockPos farmland, int formId, String name) implements CustomPacketPayload {
		public static final Type<Plant> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "plant_skyrim"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Plant> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Plant::farmland, ByteBufCodecs.INT,
			Plant::formId, ByteBufCodecs.STRING_UTF8, Plant::name, Plant::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A crop that grows like wheat, and grown, becomes its Skyrim plant. */
	public static final class SkyrimCropBlock extends CropBlock {
		SkyrimCropBlock(BlockBehaviour.Properties properties) {
			super(properties);
		}

		@Override
		protected ItemLike getBaseSeedId() {
			return Items.WHEAT_SEEDS;
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			super.randomTick(state, level, pos, random);
			this.checkGrown(level, pos);
		}

		@Override
		public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state, net.minecraft.world.level.block.BonemealSource source) {
			super.performBonemeal(level, random, pos, state, source);
			this.checkGrown(level, pos);
		}

		private void checkGrown(ServerLevel level, BlockPos pos) {
			BlockState now = level.getBlockState(pos);
			if (now.is(this) && this.isMaxAge(now)) {
				grown(level, pos);
			}
		}

		@Override
		protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
			super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
			if (!level.getBlockState(pos).is(this)) {
				removed(pos);
			}
		}
	}
}
