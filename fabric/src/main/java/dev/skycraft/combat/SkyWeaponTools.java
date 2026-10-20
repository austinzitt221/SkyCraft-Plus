// SkyCraft+ : Skyrim weapons break blocks
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A swing of a Skyrim weapon that lands on a Minecraft block wears it down, as the Minecraft tool
 * standing in for the weapon would (a pickaxe mines stone, an axe chops wood, a sword cuts webs and
 * the rest slowly); enough swings and it breaks, with that tool's drops. The hotbar item hidden behind
 * the Skyrim hand is never used (nor worn). Setting: skyrimWeaponsBreakBlocks in
 * config/skycraft.properties.
 */
public final class SkyWeaponTools {
	/** One swing is worth this many ticks of holding the button with the tool (a power attack more). */
	public static final float SWING_TICKS = 20.0F, POWER_SWING_TICKS = 30.0F;
	private static final int FORGET_TICKS = 100;  // swings further apart start the block over

	public static volatile boolean enabled = true;

	private record Mining(BlockPos pos, float progress, long tick) {
	}

	private static final Map<UUID, Mining> MINING = new HashMap<>();

	private SkyWeaponTools() {
	}

	public static void init() {
		dev.skycraft.SkySettings.onChange(() -> enabled = dev.skycraft.SkySettings.flag("skyrimWeaponsBreakBlocks", true));  // SkyCraft+ : the settings screen
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			MINING.clear();
			Path file = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
			Properties p = new Properties();
			if (Files.exists(file)) {
				try (Reader in = Files.newBufferedReader(file)) {
					p.load(in);
				} catch (IOException e) {
					SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", file, e.toString());
				}
			}
			enabled = Boolean.parseBoolean(p.getProperty("skyrimWeaponsBreakBlocks", "true").trim());
		});
	}

	/** The Minecraft tool a Skyrim hand's kind (Proto.HAND_*) mines as; empty for spells, bows and the like. */
	public static ItemStack tool(int kind) {
		var item = switch (kind) {
			case Proto.HAND_SWORD, Proto.HAND_DAGGER -> Items.IRON_SWORD;
			case Proto.HAND_GREATSWORD -> Items.DIAMOND_SWORD;
			case Proto.HAND_WAR_AXE, Proto.HAND_WOOD_AXE -> Items.IRON_AXE;
			case Proto.HAND_BATTLEAXE -> Items.DIAMOND_AXE;
			case Proto.HAND_MACE, Proto.HAND_WARHAMMER -> Items.MACE;
			case Proto.HAND_PICKAXE -> Items.IRON_PICKAXE;
			default -> null;
		};
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** How much of the block a tick of mining it with this tool does (vanilla's sums, without effects). */
	public static float progressPerTick(BlockState state, BlockGetter level, BlockPos pos, ItemStack tool) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F) {
			return 0.0F;  // bedrock and the like
		}
		if (hardness == 0.0F) {
			return 1.0F;
		}
		return tool.getDestroySpeed(state) / hardness / (correctTool(state, tool) ? 30.0F : 100.0F);
	}

	public static boolean correctTool(BlockState state, ItemStack tool) {
		return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
	}

	/** A Skyrim swing (its left hand's or right's) landed on this Minecraft block. */
	public static void hit(ServerPlayer player, BlockPos pos, boolean left, boolean power) {
		if (!enabled || player.isSpectator()) {
			return;
		}
		var hand = left == (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT)
			? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
		ItemStack tool = SkyCombat.skyrimTool(player, hand);
		ServerLevel level = player.level();
		if (tool.isEmpty() || !level.isLoaded(pos) || !player.isWithinBlockInteractionRange(pos, 1.0)
			|| !level.mayInteract(player, pos) || player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() && state.canBeReplaced()) {
			return;
		}
		long now = level.getGameTime();
		Mining was = MINING.get(player.getUUID());
		float progress = was != null && was.pos().equals(pos) && now - was.tick() <= FORGET_TICKS ? was.progress() : 0.0F;
		progress += player.isCreative() ? 1.0F : progressPerTick(state, level, pos, tool) * (power ? POWER_SWING_TICKS : SWING_TICKS);
		var sound = state.getSoundType();
		level.playSound(null, pos, sound.getHitSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		if (progress < 1.0F) {
			MINING.put(player.getUUID(), new Mining(pos.immutable(), progress, now));
			level.destroyBlockProgress(player.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
			return;
		}
		MINING.remove(player.getUUID());
		level.destroyBlockProgress(player.getId(), pos, -1);
		destroy(player, level, pos, state, tool);
	}

	/** As vanilla's breaking by a player (ServerPlayerGameMode.destroyBlock), with the stand-in tool. */
	private static void destroy(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
		Block block = state.getBlock();
		if (block instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
			return;
		}
		var blockEntity = level.getBlockEntity(pos);
		BlockState adjusted = block.playerWillDestroy(level, pos, state, player);
		boolean changed = level.removeBlock(pos, false);
		if (changed) {
			block.destroy(level, pos, adjusted);
		}
		// Its own client didn't break it (the button went to Skyrim): the cracks and sound there too.
		player.connection.send(new ClientboundLevelEventPacket(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state), false));
		if (changed && !player.preventsBlockDrops() && correctTool(adjusted, tool)) {
			block.playerDestroy(level, player, pos, adjusted, blockEntity, tool);
		}
	}
}
