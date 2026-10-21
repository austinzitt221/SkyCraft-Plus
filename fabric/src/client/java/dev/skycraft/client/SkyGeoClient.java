// SkyCraft+ : Skyrim geo placed as Minecraft blocks
package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyGeo;
import dev.skycraft.world.SkyTri;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The miner's side of Skyrim blocks (SkyGeo): mining a Skyrim cell with silk touch asks this player's
 * Skyrim, as soon as the mining starts, which texture is there (the land's painted one, or the rock's
 * or road's own: whichever was hit); when the cell comes out, the server drops a block with that
 * texture instead of its Minecraft block.
 */
public final class SkyGeoClient {
	private SkyGeoClient() {
	}

	private static final Map<Integer, Long> ASKED = new HashMap<>();   // request id -> cell
	private static final Map<Long, String> TEXTURES = new HashMap<>(); // cell -> Skyrim's texture there

	private static boolean silkTouch(Minecraft minecraft) {
		return minecraft.player != null && minecraft.level != null && SkyGeo.silkTouch(minecraft.level, minecraft.player.getMainHandItem());
	}

	/** Mining a new cell began: with silk touch, Skyrim is asked what texture it has at the point being mined. */
	static void miningStarted(Minecraft minecraft, BlockPos pos, double x, double y, double z) {
		if (!silkTouch(minecraft) || TEXTURES.containsKey(pos.asLong())) {
			return;
		}
		// (count: what was hit, 1 Skyrim's land, 2 something on it: a road, rock, wall; 0 not known.)
		int id = SkyLink.pushStoreRequest(Proto.STORE_TEXTURE_AT, 0, surfaceKind(x, y, z), (float) x, (float) y, Float.floatToRawIntBits((float) z), 0, "");
		if (id != 0) {
			ASKED.put(id, pos.asLong());
		}
		if (TEXTURES.size() > 256) {
			TEXTURES.clear();
		}
	}

	/** SkyStorageClient hands on Skyrim's answers that aren't its own; true if it was this. */
	static boolean onStoreResult(SkyLink.StoreResult result) {
		Long cell = ASKED.remove(result.id());
		if (cell == null) {
			return false;
		}
		if (result.status() == Proto.STORE_OK && !result.name().isEmpty()) {
			TEXTURES.put(cell, result.name());
		}
		return true;
	}

	/** The cell came out: with silk touch, its piece is sent (true), else it's an ordinary dig (false). */
	static boolean mined(Minecraft minecraft, int world, BlockPos pos, int material) {
		if (!silkTouch(minecraft) || !ClientPlayNetworking.canSend(SkyGeo.Mine.TYPE) || !dev.skycraft.SkySettings.flag("silkTouchSkyrimGeo", true)) {
			return false;
		}
		// A block with the texture Skyrim had there (its answer came while it was mined); none known: its Minecraft block.
		String texture = TEXTURES.getOrDefault(pos.asLong(), "");
		if (texture.isEmpty()) {
			return false;
		}
		ClientPlayNetworking.send(new SkyGeo.Mine(world, pos, material, new int[0], texture));
		return true;
	}

	/** Whether the surface at a point is Skyrim's land (1) or something standing on it (2): the nearest original surface's. */
	private static int surfaceKind(double x, double y, double z) {
		List<SkyTri> found = new ArrayList<>();
		SkyCollision.originalSurfacesNear(new AABB(x - 0.3, y - 0.3, z - 0.3, x + 0.3, y + 0.3, z + 0.3), found);
		SkyTri nearest = null;
		double best = Double.MAX_VALUE;
		for (SkyTri t : found) {
			if (t.stairHelper) {
				continue;
			}
			double cx = (t.ax + t.bx + t.cx) / 3.0, cy = (t.ay + t.by + t.cy) / 3.0, cz = (t.az + t.bz + t.cz) / 3.0;
			// Distance to its plane, plus how far outside its box (a cheap nearest-triangle).
			double plane = Math.abs((x - cx) * t.nx + (y - cy) * t.ny + (z - cz) * t.nz);
			double ex = Math.max(0.0, Math.max(t.minX - x, x - t.maxX)), ey = Math.max(0.0, Math.max(t.minY - y, y - t.maxY)), ez = Math.max(0.0, Math.max(t.minZ - z, z - t.maxZ));
			double d = plane + ex + ey + ez;
			if (d < best) {
				best = d;
				nearest = t;
			}
		}
		return nearest == null ? 0 : nearest.terrain ? 1 : 2;
	}
}
