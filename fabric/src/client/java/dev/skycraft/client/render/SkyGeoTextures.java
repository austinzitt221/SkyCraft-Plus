// SkyCraft+ : Skyrim geo placed as Minecraft blocks
package dev.skycraft.client.render;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The atlas slots Skyrim's own textures go in (SkyAtlas.GEO_SLOTS), for Skyrim geo placed as blocks:
 * each texture a geo block uses gets a slot (the least used one if they're all taken), and Skyrim is
 * asked to draw that texture there (kRenSkyrimTexture). When the atlas is sent again they're asked
 * for again; when a slot changes hands, the sections drawn with it are made again.
 */
final class SkyGeoTextures {
	private SkyGeoTextures() {
	}

	private static final String[] PATHS = new String[SkyAtlas.GEO_SLOTS];
	private static final long[] USED = new long[SkyAtlas.GEO_SLOTS];
	private static final LongOpenHashSet SECTIONS = new LongOpenHashSet();  // sections with geo blocks in them
	private static long clock;

	/** The atlas rect {u0, v0, u1, v1} a Skyrim texture is drawn in (asked for now if it has no slot yet). */
	static float[] uv(SkyAtlas atlas, String path, long section) {
		String key = path.toLowerCase(Locale.ROOT);
		SECTIONS.add(section);
		int free = -1, oldest = 0;
		for (int i = 0; i < PATHS.length; i++) {
			if (key.equals(PATHS[i])) {
				USED[i] = ++clock;
				return atlas.geoUv(i);
			}
			if (PATHS[i] == null && free < 0) {
				free = i;
			}
			if (USED[i] < USED[oldest]) {
				oldest = i;
			}
		}
		int slot = free >= 0 ? free : oldest;
		if (PATHS[slot] != null) {
			// Taken over: the sections drawn with the old texture are made again (with theirs back in a slot).
			for (long s : SECTIONS) {
				WorldExporter.markDirty(net.minecraft.core.SectionPos.x(s), net.minecraft.core.SectionPos.y(s), net.minecraft.core.SectionPos.z(s));
			}
			SECTIONS.clear();
			SECTIONS.add(section);
		}
		PATHS[slot] = key;
		USED[slot] = ++clock;
		send(atlas, slot);
		return atlas.geoUv(slot);
	}

	private static void send(SkyAtlas atlas, int slot) {
		int[] r = atlas.geoRect(slot);
		ByteBuffer msg = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
		msg.putInt(r[0]).putInt(r[1]).putInt(r[2]).putInt(r[3]);
		byte[] path = PATHS[slot].getBytes(StandardCharsets.UTF_8);
		msg.put(path, 0, Math.min(path.length, 239));
		msg.position(256).flip();
		if (!SkyLink.writeRender(Proto.REN_SKYRIM_TEXTURE, msg, null)) {
			SkyCraft.LOG.info("SkyCraft: Skyrim geo: the render ring was full; {} asked for again later", PATHS[slot]);
			PATHS[slot] = null;
		}
	}

	/** The atlas went to Skyrim again: every slot's texture is asked for again. */
	static void atlasSent(SkyAtlas atlas) {
		for (int i = 0; i < PATHS.length; i++) {
			if (PATHS[i] != null) {
				send(atlas, i);
			}
		}
	}
}
