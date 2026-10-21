// SkyCraft+ : Skyrim blocks with their own texture as items
package dev.skycraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyGeo;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A Skyrim block as an item (in the hotbar, the hand, dropped) is a cube in the very texture it
 * places: Skyrim draws a picture of that texture (kStoreTexturePicture, into its item-picture table:
 * SkyIconTextures), and each face shows it. Until it has, the cube is in the Minecraft block it's
 * made of. The item model (assets/skycraft/items/skyrim_block.json) puts it as any block is put.
 */
public final class SkyBlockItemRenderer implements SpecialModelRenderer<SkyBlockItemRenderer.Look> {
	/** What one is drawn with: the picture of its Skyrim texture, or the Minecraft texture until that's there (a new look when it comes: Minecraft keeps icons by it). */
	public record Look(Identifier texture) {
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked<Look> {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<Look> bake(SpecialModelRenderer.BakingContext context) {
			return new SkyBlockItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}

	public static void register() {
		dev.skycraft.client.mixin.SpecialModelRenderersAccessor.skycraft$idMapper().put(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_block"), Unbaked.MAP_CODEC);
	}

	// ---- the pictures of Skyrim's textures -------------------------------------------------------

	private static final Map<String, Integer> KEYS = new HashMap<>();   // texture -> its picture's key
	private static final Map<String, Long> ASKED = new HashMap<>();     // texture -> when it was last asked for
	private static final Map<Integer, String> ASKING = new HashMap<>(); // request id -> texture

	/** The texture of Skyrim's picture of this texture, asking for it now and then (Skyrim keeps a picture a minute after); null if none yet. */
	static @Nullable Identifier picture(String texture) {
		String key = texture.toLowerCase(Locale.ROOT);
		long now = System.nanoTime();
		Long asked = ASKED.get(key);
		if ((asked == null || now - asked > 20_000_000_000L) && SkyLink.active() && key.length() < Proto.SKY_ITEM_PLUGIN_BYTES) {
			int id = SkyLink.pushStoreRequest(Proto.STORE_TEXTURE_PICTURE, 0, 0, 1.0F, -1.0F, 0, 0, key);
			if (id != 0) {
				ASKED.put(key, now);
				ASKING.put(id, key);
			}
		}
		Integer picture = KEYS.get(key);
		return picture != null ? SkyIconTextures.texture(picture) : null;
	}

	/** SkyStorageClient hands on Skyrim's answers; true if it was one of these. */
	static boolean onStoreResult(SkyLink.StoreResult result) {
		String texture = ASKING.remove(result.id());
		if (texture == null) {
			return false;
		}
		if (result.status() == Proto.STORE_OK && result.formId() != 0) {
			KEYS.put(texture, result.formId());
		}
		return true;
	}

	// ---- drawing ----------------------------------------------------------------------------------

	@Override
	public @Nullable Look extractArgument(ItemStack stack) {
		SkyGeo.Piece piece = SkyGeo.piece(stack);
		if (piece == null) {
			return null;
		}
		Identifier skyrim = piece.texture().isEmpty() ? null : picture(piece.texture());
		return new Look(skyrim != null ? skyrim : Identifier.withDefaultNamespace("textures/block/" + fallback(piece.material()) + ".png"));
	}

	private static String fallback(int material) {
		return switch (material) {
			case Proto.DIG_GRASS, Proto.DIG_DIRT, Proto.DIG_MUD -> "coarse_dirt";
			case Proto.DIG_SNOW, Proto.DIG_ICE -> "snow";
			case Proto.DIG_SAND -> "sand";
			case Proto.DIG_GRAVEL -> "gravel";
			case Proto.DIG_COBBLE -> "cobblestone";
			case Proto.DIG_OAK_LOG, Proto.DIG_SPRUCE_LOG, Proto.DIG_BIRCH_LOG, Proto.DIG_PLANKS -> "spruce_planks";
			default -> "stone";
		};
	}

	// Each face: its four corners (0..1), counter-clockwise seen from outside, and its normal.
	private static final float[][] FACES = {
		{ 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1 },  // south
		{ 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0, -1 }, // north
		{ 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 0 },  // east
		{ 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, -1, 0, 0 }, // west
		{ 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1, 0 },  // up
		{ 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, -1, 0 }, // down
	};
	private static final float[] U = { 0, 1, 1, 0 }, V = { 1, 1, 0, 0 };

	@Override
	public void submit(@Nullable Look look, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outlineColor) {
		if (look == null) {
			return;
		}

		collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(look.texture()), (pose, consumer) -> {
			for (float[] f : FACES) {
				for (int k = 0; k < 4; k++) {
					consumer.addVertex(pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2]).setColor(-1).setUv(U[k], V[k]).setOverlay(overlay).setLight(light)
						.setNormal(pose, f[12], f[13], f[14]);
				}
			}
		});
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		for (int i = 0; i < 8; i++) {
			output.accept(new Vector3f(i & 1, (i >> 1) & 1, (i >> 2) & 1));
		}
	}
}
