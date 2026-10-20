// SkyCraft+ : hand ownership
package dev.skycraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Which of the player's hands Skyrim holds: equipping a Skyrim weapon, spell, shield or torch in
 * a hand (from Skyrim's inventory) makes it Skyrim's until it's unequipped. Minecraft then neither
 * draws that hand nor uses its item, and Skyrim gets that hand's mouse button.
 */
public final class SkyHands {
	private SkyHands() {
	}

	public static boolean skyrimOwns(HumanoidArm arm) {
		if (!SkyClient.linked()) {
			return false;
		}
		var sky = SkyClient.sky();
		return arm == HumanoidArm.RIGHT ? sky.rightHandSkyrim() : sky.leftHandSkyrim();
	}

	/**
	 * The crosshair is on something used by hand: a block with a screen (chest, crafting table,
	 * furnace...), a door, trapdoor, gate, button, lever or bed, or a villager, horse, boat,
	 * minecart or item frame. Right click then goes to Minecraft even with a Skyrim left hand.
	 */
	public static boolean usableTargetInView(Minecraft minecraft) {
		var hit = minecraft.hitResult;
		var level = minecraft.level;
		if (hit == null || level == null) {
			return false;
		}
		if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
			var e = entityHit.getEntity();
			return e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager || e instanceof net.minecraft.world.entity.animal.equine.AbstractHorse
				|| e instanceof net.minecraft.world.entity.vehicle.VehicleEntity || e instanceof net.minecraft.world.entity.decoration.ItemFrame
				|| e instanceof net.minecraft.world.entity.TamableAnimal tame && tame.isTame();  // SkyCraft+ : (sit or stand)
		}
		if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var pos = blockHit.getBlockPos();
			var state = level.getBlockState(pos);
			var block = state.getBlock();
			return state.getMenuProvider(level, pos) != null || block instanceof net.minecraft.world.level.block.DoorBlock
				|| block instanceof net.minecraft.world.level.block.TrapDoorBlock || block instanceof net.minecraft.world.level.block.FenceGateBlock
				|| block instanceof net.minecraft.world.level.block.ButtonBlock || block instanceof net.minecraft.world.level.block.LeverBlock
				|| block instanceof net.minecraft.world.level.block.BedBlock;
		}
		return false;
	}

	/**
	 * In third person, what Minecraft's body holds in an arm Skyrim holds: a Minecraft stand-in for the
	 * Skyrim weapon, shield or torch there (Skyrim's own is on its hidden body). Empty: nothing to show
	 * (a spell, or Minecraft's own arm).
	 */
	public static net.minecraft.world.item.ItemStack standIn(HumanoidArm arm) {
		if (!skyrimOwns(arm)) {
			return net.minecraft.world.item.ItemStack.EMPTY;
		}
		var sky = SkyClient.sky();
		return standInFor(arm == HumanoidArm.RIGHT ? sky.rightHandKind : sky.leftHandKind);
	}

	/** SkyCraft+ : held items in multiplayer. Other players' Skyrim hands, by entity id (SkyNet.HandsOf). */
	private static final java.util.Map<Integer, Integer> OTHERS = new java.util.concurrent.ConcurrentHashMap<>();

	public static void otherPlayerHands(int entityId, int hands) {
		if (hands == 0) {
			OTHERS.remove(entityId);
		} else {
			OTHERS.put(entityId, hands);
		}
	}

	public static void forgetOtherPlayers() {
		OTHERS.clear();
	}

	/** As standIn, for another player: what their body holds in an arm their Skyrim holds. */
	public static net.minecraft.world.item.ItemStack standIn(net.minecraft.world.entity.Entity player, HumanoidArm arm) {
		Integer hands = OTHERS.get(player.getId());
		if (hands == null || (hands & (arm == HumanoidArm.RIGHT ? 1 : 2)) == 0) {
			return net.minecraft.world.item.ItemStack.EMPTY;
		}
		return standInFor(arm == HumanoidArm.RIGHT ? hands >> 8 & 0xFF : hands >> 16 & 0xFF);
	}

	private static net.minecraft.world.item.ItemStack standInFor(int kind) {
		net.minecraft.world.item.Item item = switch (kind) {
			case dev.skycraft.link.Proto.HAND_SWORD -> net.minecraft.world.item.Items.IRON_SWORD;
			case dev.skycraft.link.Proto.HAND_DAGGER -> net.minecraft.world.item.Items.STONE_SWORD;
			case dev.skycraft.link.Proto.HAND_WAR_AXE, dev.skycraft.link.Proto.HAND_WOOD_AXE -> net.minecraft.world.item.Items.IRON_AXE;
			case dev.skycraft.link.Proto.HAND_PICKAXE -> net.minecraft.world.item.Items.IRON_PICKAXE;  // SkyCraft+ : v32
			case dev.skycraft.link.Proto.HAND_MACE, dev.skycraft.link.Proto.HAND_WARHAMMER -> net.minecraft.world.item.Items.MACE;
			case dev.skycraft.link.Proto.HAND_GREATSWORD -> net.minecraft.world.item.Items.DIAMOND_SWORD;
			case dev.skycraft.link.Proto.HAND_BATTLEAXE -> net.minecraft.world.item.Items.DIAMOND_AXE;
			case dev.skycraft.link.Proto.HAND_BOW -> net.minecraft.world.item.Items.BOW;
			case dev.skycraft.link.Proto.HAND_CROSSBOW -> net.minecraft.world.item.Items.CROSSBOW;
			case dev.skycraft.link.Proto.HAND_STAFF -> net.minecraft.world.item.Items.BLAZE_ROD;
			case dev.skycraft.link.Proto.HAND_SHIELD -> net.minecraft.world.item.Items.SHIELD;
			case dev.skycraft.link.Proto.HAND_TORCH -> net.minecraft.world.item.Items.TORCH;
			default -> null;
		};
		return item == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item);
	}

	public static boolean skyrimOwns(InteractionHand hand) {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		HumanoidArm main = player.getMainArm();
		return skyrimOwns(hand == InteractionHand.MAIN_HAND ? main : main.getOpposite());
	}
}
