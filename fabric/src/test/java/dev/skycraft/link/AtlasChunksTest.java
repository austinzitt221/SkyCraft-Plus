package dev.skycraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The atlas used to be one render message, so a resource pack with big textures (Doku's RPG, issue
 * #1) pushed it past the ring's limit, the message was dropped and Skyrim showed no blocks or
 * entities. {@link AtlasChunks} splits it into bands instead; these tests pin the band maths.
 */
class AtlasChunksTest {
	private static final long LIMIT = AtlasChunks.MAX_MESSAGE_BYTES;

	@Test
	void coversEveryRowInOrderWithNoGaps() {
		List<int[]> bands = AtlasChunks.plan(1024, 1000);
		int y = 0;
		for (int[] band : bands) {
			assertEquals(y, band[0], "each band starts where the last ended");
			assertTrue(band[1] >= 1, "a band always has at least one row");
			y += band[1];
		}
		assertEquals(1000, y, "every row is covered");
	}

	@Test
	void clipsTheLastBandToTheAtlas() {
		int rows = AtlasChunks.rowsPerChunk(1024);
		List<int[]> bands = AtlasChunks.plan(1024, rows * 2 + 20);
		assertEquals(3, bands.size());
		assertEquals(rows, bands.get(0)[1]);
		assertEquals(rows, bands.get(1)[1]);
		assertEquals(20, bands.get(2)[1]);
	}

	@Test
	void everyChunkFitsTheRenderMessageLimit() {
		for (int width : new int[] { 16, 256, 1024, 4096, 8192, 16384 }) {
			for (int[] band : AtlasChunks.plan(width, 8192)) {
				long bytes = AtlasChunks.messageBytes(width, band[1]);
				assertTrue(bytes <= LIMIT, "width " + width + ", " + band[1] + " rows: " + bytes + " > " + LIMIT);
			}
		}
	}

	@Test
	void theAtlasFromTheBugReportNowArrives() {
		// Doku's RPG pack: a 4096x3088 atlas (50 593 792 bytes RAW) did not fit one message.
		int width = 4096, height = 3088;
		assertTrue((long) width * height * 4 > LIMIT, "the pixel data alone exceeds one message");
		List<int[]> bands = AtlasChunks.plan(width, height);
		assertEquals(2, bands.size());
		int y = 0;
		for (int[] band : bands) {
			assertEquals(y, band[0]);
			assertTrue(AtlasChunks.messageBytes(width, band[1]) <= LIMIT, "every band fits the ring");
			y += band[1];
		}
		assertEquals(height, y);
	}

	@Test
	void aSingleVeryWideRowIsStillOneBand() {
		// Wider than any message can hold: rowsPerChunk floors at 1 and writeRender reports the failure
		// rather than looping forever.
		int width = 1 << 26; // 256 MB of pixels in one row
		assertEquals(1, AtlasChunks.rowsPerChunk(width));
		assertEquals(4, AtlasChunks.plan(width, 4).size());
		assertTrue(AtlasChunks.messageBytes(width, 1) > LIMIT);
	}

	@Test
	void respectsAnExplicitSmallerLimit() {
		// 100-byte messages: budget 100 - 16 - 8 - 7 = 69, 16 bytes per row at width 4 -> 4 rows.
		assertEquals(4, AtlasChunks.rowsPerChunk(4, 100));
	}

	@Test
	void anEmptyAtlasPlansNoChunks() {
		assertTrue(AtlasChunks.plan(0, 100).isEmpty());
		assertTrue(AtlasChunks.plan(100, 0).isEmpty());
		assertEquals(0, AtlasChunks.rowsPerChunk(0));
	}
}
