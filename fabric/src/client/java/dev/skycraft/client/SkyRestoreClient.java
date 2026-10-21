// SkyCraft+ : the restoration brush
package dev.skycraft.client;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyDig;
import dev.skycraft.world.SkyRestore;
import dev.skycraft.world.SkyTri;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The client's side of the restoration brush: the first dug cell the player looks into (not the one
 * they stand in), up to five blocks away. One Skyrim's geometry passes through is given back to
 * Skyrim; one wholly inside the ground (a tunnel) is filled with what it's made of.
 */
public final class SkyRestoreClient {
	private SkyRestoreClient() {
	}

	private static final double REACH = 5.0;

	public static void register() {
		SkyRestore.clientUse = SkyRestoreClient::use;
	}

	private static boolean use(Player player) {
		Minecraft minecraft = Minecraft.getInstance();
		var level = minecraft.level;
		if (level == null || !ClientPlayNetworking.canSend(SkyRestore.Restore.TYPE)) {
			return false;
		}
		int world = SkyDigClient.world();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		SkyDig.Probe probe = new SkyDig.Probe();
		BlockPos last = null;
		for (double d = 0.2; d <= REACH; d += 0.1) {
			Vec3 p = eye.add(look.scale(d));
			BlockPos cell = BlockPos.containing(p);
			if (cell.equals(last)) {
				continue;
			}
			last = cell;
			BlockState state = level.getBlockState(cell);
			boolean dug = SkyDig.isDug(level, world, cell);
			if (!state.isAir() && !state.canBeReplaced()) {
				// SkyCraft+ : a block an earlier brush filled a dug cell of a house or rock with (it once
				// filled any cell no surface passed through: wood planks in Whiterun's walls): taken away,
				// and the Skyrim object's geometry back. (The server checks it's that block.)
				if (dug && !player.getBoundingBox().intersects(new AABB(cell))) {
					probe.around(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 1, cell.getZ() + 1);
					int material = probe.test(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
					if (material > 0 && !underLand(probe, cell)) {
						ClientPlayNetworking.send(new SkyRestore.Restore(world, cell, -2 - material, !player.getMainHandItem().is(SkyRestore.ITEM)));
						return true;
					}
				}
				break;  // a block in the way
			}
			if (!dug) {
				probe.around(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 1, cell.getZ() + 1);
				int inside = probe.test(p.x, p.y, p.z);
				if (inside != SkyDig.AIR) {
					break;  // into Skyrim's geometry that's still there
				}
				continue;
			}
			if (player.getBoundingBox().intersects(new AABB(cell))) {
				continue;  // (not the one the player stands in)
			}
			// Skyrim's own geometry passes through it: that comes back. Else it's wholly inside: its block.
			List<SkyTri> surfaces = new ArrayList<>();
			SkyCollision.originalSurfacesNear(new AABB(cell), surfaces);
			surfaces.removeIf(t -> t.stairHelper);
			int fill = -1;
			if (surfaces.isEmpty()) {
				probe.around(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 1, cell.getZ() + 1);
				int material = probe.test(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
				// (Only under the land, where Skyrim draws nothing: inside a house's wall or a rock, its own
				// geometry comes back, as everywhere else.)
				fill = material > 0 && underLand(probe, cell) ? material : -1;
			}
			boolean offHand = !player.getMainHandItem().is(SkyRestore.ITEM);
			ClientPlayNetworking.send(new SkyRestore.Restore(world, cell, fill, offHand));
			return true;
		}
		player.sendOverlayMessage(Component.literal("Nothing dug out of Skyrim here to put back"));
		return false;
	}

	/** Whether a cell is under Skyrim's land (or deep in the underground): a tunnel, where Skyrim draws nothing. */
	private static boolean underLand(SkyDig.Probe probe, BlockPos cell) {
		double x = cell.getX() + 0.5, y = cell.getY() + 0.5, z = cell.getZ() + 0.5;
		double ground = probe.landHeight(x, z);
		return Double.isNaN(ground) ? dev.skycraft.world.SkyUnderground.deep(x, y, z) : y < ground;
	}
}
