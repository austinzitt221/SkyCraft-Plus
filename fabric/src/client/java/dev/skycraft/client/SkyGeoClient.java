// SkyCraft+ : Skyrim geo placed as Minecraft blocks
package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyGeo;
import dev.skycraft.world.SkyTri;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The miner's side of Skyrim geo (SkyGeo): mining a Skyrim cell with silk touch asks this player's
 * Skyrim, as soon as the mining starts, which texture is there (the land's painted one, or the rock's
 * or road's own); when the cell comes out, its surface (Skyrim's triangles cut to the cell) goes with
 * it, and the server drops that piece instead of its Minecraft block.
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
		int id = SkyLink.pushStoreRequest(Proto.STORE_TEXTURE_AT, 0, 0, (float) x, (float) y, Float.floatToRawIntBits((float) z), 0, "");
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
		if (!silkTouch(minecraft) || !ClientPlayNetworking.canSend(SkyGeo.Mine.TYPE)) {
			return false;
		}
		int[] tris = capture(pos);
		String texture = TEXTURES.getOrDefault(pos.asLong(), "");
		ClientPlayNetworking.send(new SkyGeo.Mine(world, pos, material, tris, texture));
		return true;
	}

	/** Skyrim's surfaces in the cell (as they were before it was dug), cut to it, in the cell's own 0..1 (packed: SkyGeo.Piece). */
	static int[] capture(BlockPos pos) {
		AABB cell = new AABB(pos);
		List<SkyTri> found = new ArrayList<>();
		SkyCollision.originalSurfacesNear(cell, found);
		IntArrayList out = new IntArrayList();
		for (SkyTri t : found) {
			if (t.stairHelper || out.size() >= SkyGeo.MAX_TRIS * 3) {
				continue;
			}
			List<double[]> poly = new ArrayList<>(List.of(new double[] { t.ax, t.ay, t.az }, new double[] { t.bx, t.by, t.bz }, new double[] { t.cx, t.cy, t.cz }));
			double[] lo = { pos.getX(), pos.getY(), pos.getZ() }, hi = { pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1 };
			for (int axis = 0; axis < 3 && poly.size() >= 3; axis++) {
				poly = clip(poly, axis, lo[axis], true);
				poly = clip(poly, axis, hi[axis], false);
			}
			for (int k = 1; k + 1 < poly.size() && out.size() < SkyGeo.MAX_TRIS * 3; k++) {
				for (double[] v : new double[][] { poly.get(0), poly.get(k), poly.get(k + 1) }) {
					out.add(SkyGeo.Piece.pack(v[0] - lo[0], v[1] - lo[1], v[2] - lo[2]));
				}
			}
		}
		return out.toIntArray();
	}

	/** Sutherland-Hodgman against one side of the cell: keep what's above (keepAbove) or below that plane. */
	private static List<double[]> clip(List<double[]> poly, int axis, double plane, boolean keepAbove) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < poly.size(); i++) {
			double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
			boolean ina = keepAbove ? a[axis] >= plane : a[axis] <= plane;
			boolean inb = keepAbove ? b[axis] >= plane : b[axis] <= plane;
			if (ina) {
				out.add(a);
			}
			if (ina != inb) {
				double f = (plane - a[axis]) / (b[axis] - a[axis]);
				out.add(new double[] { a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f });
			}
		}
		return out;
	}
}
