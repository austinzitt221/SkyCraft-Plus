package dev.skycraft.client.render;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Block models written against Fabric's renderer API.
 *
 * <p>Vanilla's {@code ModelBlockRenderer} asks a model for its quads through
 * {@code BlockStateModel.collectParts}. A model built for Fabric's API may leave that empty and fill
 * {@code emitQuads} instead — Traveler's Backpack's block does exactly that — so a plain vanilla
 * renderer draws nothing and the block is invisible. Fabric's own {@code AltModelBlockRenderer}
 * reads both; this wraps it for the section mesher, which keeps vanilla's renderer for every other
 * model.
 *
 * <p>Deliberately the only file that knows about Fabric's renderer API: another loader's bridge
 * (NeoForge) can replace it whole.
 */
final class FabricBlockModels {
	private FabricBlockModels() {
	}

	/** One vertex of such a model: already in the combined atlas, section-relative. As MESH wants it. */
	interface Sink {
		void vertex(float x, float y, float z, float u, float v, int argb, int light, int flags);
	}

	private static final List<BlockStateModelPart> PARTS = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();
	/** Whether a model draws only through Fabric, by model identity: probed once each, not per block. */
	private static final java.util.Map<BlockStateModel, Boolean> FABRIC_ONLY = new java.util.IdentityHashMap<>();
	private static @Nullable AltModelBlockRenderer renderer;
	private static @Nullable MutableMesh mesh;

	/**
	 * Meshes {@code model} if it draws through Fabric (nothing in {@code collectParts}, quads in
	 * {@code emitQuads}), through {@code sink}; {@code x,y,z} are the block's section-relative
	 * position. Returns false for an ordinary model, which the vanilla renderer should take.
	 */
	static boolean tesselate(SkyAtlas atlas, float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state,
		BlockStateModel model, long seed, Sink sink) {
		if (!FABRIC_ONLY.computeIfAbsent(model, FabricBlockModels::fabricOnly)) {
			return false;
		}
		if (renderer == null) {
			renderer = Renderer.get().altModelBlockRenderer(true, true, Minecraft.getInstance().getBlockColors());
		}
		if (mesh == null) {
			mesh = Renderer.get().mutableMesh();
		}
		mesh.clear();
		// The block's own offset is baked into the quads (as the vanilla renderer does).
		renderer.tesselateBlock(mesh.emitter(), x, y, z, level, pos, state, model, seed);
		mesh.forEach(quad -> emit(atlas, quad, sink));
		return true;
	}

	/** A model that leaves collectParts empty draws nothing for the vanilla renderer. */
	private static boolean fabricOnly(BlockStateModel model) {
		PARTS.clear();
		RANDOM.setSeed(0L);
		model.collectParts(RANDOM, PARTS);
		return PARTS.isEmpty();
	}

	/** Two triangles per quad, as MESH.wall and the block path write them. */
	private static final int[] TRIANGLES = { 0, 1, 2, 0, 2, 3 };

	/** A quad: two triangles, the same vertex order and face flags the block path uses. */
	private static void emit(SkyAtlas atlas, QuadView quad, Sink sink) {
		boolean translucent = quad.chunkLayer() != null && quad.chunkLayer().translucent();
		Direction override = quad.shadeDirectionOverride();
		Direction face = quad.lightFace();
		// Plants and the like are shaded as if facing up whatever way they face: no normal for them.
		Direction normal = override == Direction.UP && face != Direction.UP ? null : override != null ? override : face;
		int flags = (translucent ? 2 : 1) | (normal == null ? 0 : (normal.ordinal() + 1) << 4);
		boolean item = quad.atlas() == QuadAtlas.ITEM;
		for (int k : TRIANGLES) {
			float u = quad.u(k), v = quad.v(k);
			if (item) {
				u = atlas.itemU(u);
				v = atlas.itemV(v);
			} else {
				u = atlas.blockU(u);
				v = atlas.blockV(v);
			}
			sink.vertex(quad.x(k), quad.y(k), quad.z(k), u, v, quad.color(k), quad.lightmap(k), flags);
		}
	}
}
