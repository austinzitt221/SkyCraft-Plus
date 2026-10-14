package dev.skycraft.link;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the block/item atlas into render messages that fit the render ring's per-message limit.
 *
 * <p>A resource pack with large textures can grow the atlas past half the render ring, the largest
 * message {@link SkyLink#writeRender} accepts. The atlas used to be a single message, so a pack
 * like Doku's RPG made it too large, the whole message was dropped (with only a log line) and
 * Skyrim showed no block or entity textures at all. Now the atlas is a size-only "begin" plus
 * horizontal bands, each comfortably inside the limit.
 */
public final class AtlasChunks {
	private AtlasChunks() {
	}

	/** RenAtlasChunk header: width, height, y, rows (see protocol/skycraft_protocol.h). */
	public static final int HEADER_BYTES = 16;
	/** Every render message starts with {u32 type, u32 payloadBytes}. */
	public static final int MSG_HEADER_BYTES = 8;
	/** The cap writeRender enforces on one message. */
	public static final long MAX_MESSAGE_BYTES = Proto.RR_DATA_BYTES / 2;

	/** Rows one chunk may carry at this atlas width, within the limit. 0 for a degenerate width. */
	public static int rowsPerChunk(int width) {
		return rowsPerChunk(width, MAX_MESSAGE_BYTES);
	}

	static int rowsPerChunk(int width, long maxMessageBytes) {
		if (width <= 0) {
			return 0;
		}
		long bytesPerRow = (long) width * 4L;
		// The 8-byte message header and the up-to-7 bytes of 8-byte alignment count too.
		long budget = maxMessageBytes - HEADER_BYTES - MSG_HEADER_BYTES - 7L;
		if (budget < bytesPerRow) {
			return 1; // a single row may still be too wide: writeRender will say so
		}
		return (int) Math.min(Integer.MAX_VALUE, budget / bytesPerRow);
	}

	/** Horizontal bands {@code {y, rows}} covering a height-row atlas, top to bottom. */
	public static List<int[]> plan(int width, int height) {
		return plan(width, height, MAX_MESSAGE_BYTES);
	}

	static List<int[]> plan(int width, int height, long maxMessageBytes) {
		List<int[]> bands = new ArrayList<>();
		int rows = rowsPerChunk(width, maxMessageBytes);
		if (rows <= 0 || height <= 0) {
			return bands;
		}
		for (int y = 0; y < height; y += rows) {
			bands.add(new int[] { y, Math.min(rows, height - y) });
		}
		return bands;
	}

	/** Bytes of one chunk message (8-byte header included, 8-byte aligned), as writeRender sees it. */
	public static long messageBytes(int width, int rows) {
		long payload = (long) HEADER_BYTES + (long) width * rows * 4L;
		return (MSG_HEADER_BYTES + payload + 7L) & ~7L;
	}
}
