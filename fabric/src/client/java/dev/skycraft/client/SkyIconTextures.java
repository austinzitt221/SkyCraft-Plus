// SkyCraft+ : Skyrim's item pictures
package dev.skycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The pictures Skyrim draws of its items (proto::SkyIcons), as Minecraft textures: one per slot of
 * the shared table, each refreshed when Skyrim rewrites its slot. Drawn in place of the Minecraft
 * stand-ins (SkyIcons) in the Skyrim panels and on shortcuts, once Skyrim has drawn them.
 */
public final class SkyIconTextures {
	private SkyIconTextures() {
	}

	private static final int SIZE = Proto.SKY_ICON_SIZE, SLOTS = Proto.MAX_SKY_ICONS, UPLOADS_PER_TICK = 48;
	private static final DynamicTexture[] TEXTURES = new DynamicTexture[SLOTS];
	private static final Identifier[] IDS = new Identifier[SLOTS];
	private static final int[] SEQ = new int[SLOTS], KEY = new int[SLOTS];
	private static final Map<Integer, Integer> SLOT_OF = new HashMap<>();
	private static final byte[] PIXELS = new byte[SIZE * SIZE * 4];
	private static int generation = Integer.MIN_VALUE;
	private static boolean linked;

	static {
		Arrays.fill(SEQ, -1);
	}

	/** Each tick: pictures Skyrim drew or redrew since, made into textures (a few dozen a tick at most). */
	static void tick(Minecraft minecraft) {
		if (!SkyLink.active()) {
			if (linked) {
				linked = false;
				Arrays.fill(SEQ, -1);
				Arrays.fill(KEY, 0);
				SLOT_OF.clear();
				generation = Integer.MIN_VALUE;
			}
			return;
		}
		linked = true;
		int now = SkyLink.skyIconsGeneration();
		if (now == generation) {
			return;
		}
		generation = now;
		int uploads = 0;
		for (int slot = 0; slot < SLOTS; slot++) {
			long header = SkyLink.skyIconHeader(slot);
			int seq = (int) (header >>> 32);
			if (header == -1L || seq == SEQ[slot] || (seq & 1) != 0 || (int) header == 0) {
				continue;
			}
			if (uploads++ >= UPLOADS_PER_TICK) {
				generation = Integer.MIN_VALUE;  // the rest next tick
				break;
			}
			int key = SkyLink.readSkyIcon(slot, PIXELS);
			if (key == 0) {
				generation = Integer.MIN_VALUE;
				continue;
			}
			upload(minecraft, slot);
			SEQ[slot] = seq;
			if (KEY[slot] != 0) {
				SLOT_OF.remove(KEY[slot]);
			}
			KEY[slot] = key;
			SLOT_OF.put(key, slot);
		}
	}

	private static void upload(Minecraft minecraft, int slot) {
		DynamicTexture texture = TEXTURES[slot];
		if (texture == null) {
			texture = new DynamicTexture(() -> "SkyCraft Skyrim item picture", SIZE, SIZE, true);
			try {
				// Drawn at 16 GUI pixels: smoothed when that's fewer screen pixels than the picture's.
				var field = net.minecraft.client.renderer.texture.AbstractTexture.class.getDeclaredField("sampler");
				field.setAccessible(true);
				field.set(texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			} catch (ReflectiveOperationException | RuntimeException e) {
				SkyCraft.LOG.debug("SkyCraft: Skyrim pictures stay unsmoothed", e);
			}
			TEXTURES[slot] = texture;
			IDS[slot] = Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "sky_icon/" + slot);
			minecraft.getTextureManager().register(IDS[slot], texture);
		}
		NativeImage image = texture.getPixels();
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int i = (y * SIZE + x) * 4;
				int argb = (PIXELS[i + 3] & 0xFF) << 24 | (PIXELS[i] & 0xFF) << 16 | (PIXELS[i + 1] & 0xFF) << 8 | PIXELS[i + 2] & 0xFF;
				image.setPixel(x, y, argb);
			}
		}
		texture.upload();
	}

	/** True if Skyrim has drawn this (SkyItem::formId, or SKY_ICON_CATEGORY + n). */
	public static boolean has(int key) {
		return SLOT_OF.containsKey(key);
	}

	/** Draws Skyrim's picture of this in a 16 x 16 slot at (x, y); false if there's none yet. */
	public static boolean draw(GuiGraphicsExtractor graphics, int key, int x, int y) {
		Integer slot = SLOT_OF.get(key);
		if (slot == null || IDS[slot] == null) {
			return false;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, IDS[slot], x, y, 0.0F, 0.0F, 16, 16, SIZE, SIZE, SIZE, SIZE);
		return true;
	}
}
