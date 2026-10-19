// SkyCraft+ : Minecraft's structures in Skyrim's world
package dev.skycraft.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Skyrim's open world (Tamriel) as read from the player's own Skyrim.esm: the land's height in
 * every cell, its water, and where Skyrim's buildings and trees stand. From it, fixed spots for
 * Minecraft's surface structures: on ground flat enough for each, dry and clear of Skyrim's
 * buildings for villages, outposts, temples and the mansion; deep water for the monument, ruins and
 * wrecks; shores for beached wrecks, buried treasure and the witch hut. The same Skyrim.esm gives
 * the same spots for everyone. Nothing of it is kept on disk or shipped (it's Skyrim's own data).
 *
 * Plain Java (no Minecraft): {@code java SkyrimMap.java <Skyrim.esm>} prints the spots.
 */
public final class SkyrimMap {
	/** Skyrim units per Minecraft block (proto kUnitsPerBlock). */
	public static final double UNITS = 70.0;
	private static final int TAMRIEL = 0x3C;
	private static final int CELL_UNITS = 4096;
	private static final int GRID = 33;  // land heights per cell side, 128 units apart

	/** A Minecraft structure's fixed spot: its own name, the structure (minecraft:...), Minecraft x/z and ground y. */
	public record Site(String name, String structure, double x, double z, double groundY) {
	}

	private final Map<Long, float[]> heights = new HashMap<>();  // cell -> 33x33 land heights (Skyrim units)
	private final Map<Long, Cell> cells = new HashMap<>();
	private final List<Site> sites = new ArrayList<>();
	private float defaultWater = -14000.0f;
	// SkyCraft+ : strongholds. Cells whose land is all above their water (Skyrim's water reaches all the
	// way down: under it, a stronghold flooded and couldn't be walled off).
	private final Set<Long> dry = new HashSet<>();

	private static final class Cell {
		int x, y;
		float water = Float.NaN;
		boolean land;
		boolean navmesh;  // Skyrim's NPCs can walk it: inside the borders, not the land beyond them
		final List<float[]> obstacles = new ArrayList<>();  // Skyrim buildings, walls, cliffs, doors (x, y)
		final List<float[]> trees = new ArrayList<>();
		final List<float[]> mountains = new ArrayList<>();  // mountain and cliff pieces
		final List<float[]> roadPieces = new ArrayList<>();  // Skyrim's paved roads: meshes laid on the land
		final List<float[]> rocks = new ArrayList<>();  // boulders and the like (two blocks across or more)
		final boolean[] road = new boolean[4];  // its quarters painted with a road texture
	}

	private SkyrimMap() {
	}

	public List<Site> sites() {
		return sites;
	}

	/** Skyrim's sea level, in Minecraft's y. */
	public double seaLevel() {
		return defaultWater / UNITS;
	}

	/** The land's height (Minecraft y) at a Minecraft x/z near a site; NaN where it isn't kept. */
	public double groundAt(double x, double z) {
		double h = heightUnits(x * UNITS, -z * UNITS);
		return Double.isNaN(h) ? h : h / UNITS;
	}

	/** Water's surface (Minecraft y) over a Minecraft x/z. */
	public double waterAt(double x, double z) {
		return waterUnits(x * UNITS, -z * UNITS) / UNITS;
	}

	private static long key(int x, int y) {
		return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
	}

	private static int cellOf(double units) {
		return Math.floorDiv((int) Math.floor(units), CELL_UNITS);
	}

	private double heightUnits(double sx, double sy) {
		int cx = cellOf(sx), cy = cellOf(sy);
		float[] h = heights.get(key(cx, cy));
		if (h == null) {
			return Double.NaN;
		}
		double gx = (sx - cx * (double) CELL_UNITS) / 128.0, gy = (sy - cy * (double) CELL_UNITS) / 128.0;
		int ix = Math.min((int) gx, GRID - 2), iy = Math.min((int) gy, GRID - 2);
		double fx = gx - ix, fy = gy - iy;
		double a = h[iy * GRID + ix], b = h[iy * GRID + ix + 1], c = h[(iy + 1) * GRID + ix], d = h[(iy + 1) * GRID + ix + 1];
		return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
	}

	private double waterUnits(double sx, double sy) {
		Cell c = cells.get(key(cellOf(sx), cellOf(sy)));
		return c == null || Float.isNaN(c.water) ? defaultWater : c.water;
	}

	// ---- reading Skyrim.esm --------------------------------------------------------------------

	private MappedByteBuffer buf;
	private final Set<Integer> obstacleBases = new HashSet<>();
	private final Set<Integer> treeBases = new HashSet<>();
	private final Set<Integer> rockBases = new HashSet<>();
	private final Set<Integer> roadBases = new HashSet<>();
	private final Set<Integer> mountainBases = new HashSet<>();
	/** Structures keep off mountains (skycraft.properties structuresOffMountains): fewer villages, cleaner ground. */
	public static volatile boolean offMountains = true;
	/** BorderRegionSkyrim: Skyrim's invisible walls ("You cannot go that way"), its outlines (x, y pairs, units). */
	private static final int BORDER_REGION = 0xC5859;
	private final List<float[]> border = new ArrayList<>();
	private final Set<Integer> roadTextures = new HashSet<>();
	private final Map<Integer, Cell> cellsByForm = new HashMap<>();

	public static SkyrimMap read(Path esm) throws IOException {
		SkyrimMap map = new SkyrimMap();
		try (FileChannel ch = FileChannel.open(esm)) {
			map.buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
			map.buf.order(ByteOrder.LITTLE_ENDIAN);
			map.walk(0, (int) ch.size(), false, -1);
		}
		map.buf = null;
		map.cellsByForm.clear();
		map.choose();
		map.findDry();
		map.keepHeightsNearSites();
		return map;
	}

	private String type(int p) {
		return new String(new byte[] { buf.get(p), buf.get(p + 1), buf.get(p + 2), buf.get(p + 3) }, StandardCharsets.US_ASCII);
	}

	private byte[] data(int p, int size, int flags) throws IOException {
		byte[] d = new byte[size];
		buf.get(p + 24, d);
		if ((flags & 0x40000) == 0) {
			return d;
		}
		int length = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt();
		Inflater inflater = new Inflater();
		inflater.setInput(d, 4, d.length - 4);
		byte[] out = new byte[length];
		try {
			inflater.inflate(out);
		} catch (DataFormatException e) {
			throw new IOException(e);
		} finally {
			inflater.end();
		}
		return out;
	}

	/** Groups in order; inside Tamriel's (WRLD children, label 0x3C) its cells and their children are read. */
	private void walk(int start, int end, boolean tamriel, int cell) throws IOException {
		int p = start;
		while (p < end) {
			String t = type(p);
			int size = buf.getInt(p + 4);
			if (t.equals("GRUP")) {
				int label = buf.getInt(p + 8), groupType = buf.getInt(p + 12);
				String top = type(p + 8);
				boolean wanted = groupType != 0 || top.equals("STAT") || top.equals("TREE") || top.equals("DOOR") || top.equals("MSTT") || top.equals("WRLD") || top.equals("LTEX") || top.equals("REGN");
				if (wanted && (groupType != 1 || label == TAMRIEL)) {
					int children = groupType == 6 ? label : groupType == 8 || groupType == 9 || groupType == 10 ? cell : -1;
					walk(p + 24, p + size, tamriel || (groupType == 1 && label == TAMRIEL), children);
				}
				p += size;
				continue;
			}
			int flags = buf.getInt(p + 8), form = buf.getInt(p + 12);
			switch (t) {
				case "STAT", "MSTT" -> {
					byte[] d = data(p, size, flags);
					int footprint = footprint(d);
					// Skyrim's land itself (cliffs, mountainsides, boulders: models under Landscape) stands everywhere:
					// only where a structure would stand on it. Buildings and walls keep their distance.
					String model = model(d);
					boolean landscape = model.startsWith("landscape\\");
					if (model.contains("\\roads\\")) {
						roadBases.add(form);
					} else if (model.startsWith("landscape\\mountains\\") || model.startsWith("landscape\\dirtcliffs\\") || model.startsWith("landscape\\ice\\")
						|| (landscape && model.contains("cliff") && footprint >= 150)) {
						mountainBases.add(form);  // mountains and cliffs: dug through for a structure's ground, they stood up in it in pieces
					} else if ((footprint >= 1000 && !landscape) || (footprint >= 150 && model.startsWith("architecture\\"))) {
						obstacleBases.add(form);  // buildings, walls, farms' fences and stables (not rocks and clutter)
					} else if (footprint >= 150) {
						rockBases.add(form);  // they'd stand over a structure's ground, floating
					}
				}
				case "DOOR" -> obstacleBases.add(form);
				case "TREE" -> treeBases.add(form);
				case "LTEX" -> {
					// Skyrim's roads are painted on its land, not built: their land textures.
					String[] name = { "" };
					byte[] d = data(p, size, flags);
					subrecords(d, (st, b, pos, len) -> {
						if (st.equals("EDID") && len > 1) {
							name[0] = new String(d, pos, len - 1, StandardCharsets.US_ASCII);
						}
					});
					if (name[0].toLowerCase(java.util.Locale.ROOT).contains("road") || name[0].toLowerCase(java.util.Locale.ROOT).contains("path")) {  // dirt paths (the roads are meshes)
						roadTextures.add(form);
					}
				}
				case "WRLD" -> {
					if (form == TAMRIEL) {
						readWorld(data(p, size, flags));
					}
				}
				case "CELL" -> {
					if (tamriel) {
						readCell(form, data(p, size, flags));
					}
				}
				case "LAND" -> {
					if (tamriel && cell != -1) {
						readLand(cell, data(p, size, flags));
					}
				}
				case "REFR" -> {
					if (tamriel && cell != -1) {
						readRef(cell, data(p, size, flags));
					}
				}
				case "REGN" -> {
					if (form == BORDER_REGION) {
						byte[] d = data(p, size, flags);
						subrecords(d, (st, b, pos, len) -> {
							if (st.equals("RPLD") && len >= 24) {
								float[] points = new float[len / 4];
								for (int i = 0; i < points.length; i++) {
									points[i] = b.getFloat(pos + 4 * i);
								}
								border.add(points);
							}
						});
					}
				}
				case "NAVM" -> {
					Cell c = tamriel && cell != -1 ? cellsByForm.get(cell) : null;
					if (c != null) {
						c.navmesh = true;
					}
				}
				default -> {
				}
			}
			p += 24 + size;
		}
	}

	private interface Sub {
		void at(String type, ByteBuffer b, int pos, int len);
	}

	private static void subrecords(byte[] d, Sub sub) {
		ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
		int extended = -1;
		while (b.remaining() >= 6) {
			byte[] tb = new byte[4];
			b.get(tb);
			String st = new String(tb, StandardCharsets.US_ASCII);
			int len = b.getShort() & 0xFFFF;
			if (st.equals("XXXX")) {
				extended = b.getInt(b.position());
				b.position(b.position() + len);
				continue;
			}
			if (extended >= 0) {
				len = extended;
				extended = -1;
			}
			int pos = b.position();
			if (pos + len > d.length) {
				return;
			}
			sub.at(st, b, pos, len);
			b.position(pos + len);
		}
	}

	/** A form's model path, lower case ("" if none). */
	private static String model(byte[] d) {
		String[] out = { "" };
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("MODL") && len > 1) {
				out[0] = new String(d, pos, len - 1, StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT);
			}
		});
		return out[0];
	}

	private static int footprint(byte[] d) {
		int[] out = { 0 };
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("OBND") && len >= 12) {
				out[0] = Math.max(b.getShort(pos + 6) - b.getShort(pos), b.getShort(pos + 8) - b.getShort(pos + 2));
			}
		});
		return out[0];
	}

	private void readWorld(byte[] d) {
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("DNAM") && len >= 8) {
				defaultWater = b.getFloat(pos + 4);
			}
		});
	}

	private void readCell(int form, byte[] d) {
		Cell c = new Cell();
		boolean[] grid = { false };
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("XCLC") && len >= 8) {
				c.x = b.getInt(pos);
				c.y = b.getInt(pos + 4);
				grid[0] = true;
			} else if (t.equals("XCLW") && len >= 4) {
				float w = b.getFloat(pos);
				c.water = Math.abs(w) < 1.0e6f ? w : Float.NaN;  // (FLT_MAX: no water of its own)
			}
		});
		if (grid[0]) {
			cellsByForm.put(form, c);
			cells.put(key(c.x, c.y), c);
		}
	}

	private void readLand(int cellForm, byte[] d) {
		Cell c = cellsByForm.get(cellForm);
		if (c == null) {
			return;
		}
		subrecords(d, (t, b, pos, len) -> {
			if ((t.equals("BTXT") || t.equals("ATXT")) && len >= 5 && roadTextures.contains(b.getInt(pos))) {
				int quarter = d[pos + 4] & 3;
				c.road[quarter] = true;
			}
			if (t.equals("VHGT") && len >= 4 + GRID * GRID) {
				float[] h = new float[GRID * GRID];
				float offset = b.getFloat(pos);
				for (int row = 0; row < GRID; row++) {
					offset += d[pos + 4 + row * GRID];
					float value = offset;
					for (int col = 0; col < GRID; col++) {
						if (col > 0) {
							value += d[pos + 4 + row * GRID + col];
						}
						h[row * GRID + col] = value * 8.0f;
					}
				}
				heights.put(key(c.x, c.y), h);
				c.land = true;
			}
		});
	}

	private void readRef(int cellForm, byte[] d) {
		Cell c = cellsByForm.get(cellForm);
		if (c == null) {
			return;
		}
		int[] base = { 0 };
		float[] at = null;
		float[][] pos = { null };
		subrecords(d, (t, b, p, len) -> {
			if (t.equals("NAME") && len >= 4) {
				base[0] = b.getInt(p);
			} else if (t.equals("DATA") && len >= 12) {
				pos[0] = new float[] { b.getFloat(p), b.getFloat(p + 4) };
			}
		});
		if (pos[0] == null) {
			return;
		}
		if (obstacleBases.contains(base[0])) {
			c.obstacles.add(pos[0]);
		} else if (treeBases.contains(base[0])) {
			c.trees.add(pos[0]);
		} else if (rockBases.contains(base[0])) {
			c.rocks.add(pos[0]);
		} else if (roadBases.contains(base[0])) {
			c.roadPieces.add(pos[0]);
		} else if (mountainBases.contains(base[0])) {
			c.mountains.add(pos[0]);
		}
	}

	// ---- choosing the spots ---------------------------------------------------------------------

	/** The ground in a circle (Skyrim units): its heights' range, dryness, and what stands on it. */
	private record Ground(boolean dry, boolean wet, double min, double max, double mean, double depth, int obstacles, int trees, int nearTrees, int roads,
		int rocks, int mountains, boolean shore) {
	}

	private Ground ground(double cx, double cy, double radius) {
		boolean dry = true, wet = true, any = false;
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0, depth = Double.POSITIVE_INFINITY;
		int n = 0, above = 0, below = 0, roads = 0;
		double step = Math.max(128.0, radius / 8.0);
		for (double dx = -radius; dx <= radius; dx += step) {
			for (double dy = -radius; dy <= radius; dy += step) {
				if (dx * dx + dy * dy > radius * radius) {
					continue;
				}
				double h = heightUnits(cx + dx, cy + dy);
				if (Double.isNaN(h)) {
					return null;
				}
				double w = waterUnits(cx + dx, cy + dy);
				roads += onRoad(cx + dx, cy + dy) ? 1 : 0;
				any = true;
				if (h < w + 96) {
					dry = false;
					below++;
				} else {
					above++;
				}
				if (h > w - 96) {
					wet = false;
				}
				depth = Math.min(depth, w - h);
				min = Math.min(min, h);
				max = Math.max(max, h);
				sum += h;
				n++;
			}
		}
		if (!any) {
			return null;
		}
		int obstacles = 0, trees = 0, nearTrees = 0, rocks = 0, mountains = 0;
		double reach = radius + 700.0;  // ten blocks round it too
		double around = radius + 2800.0;  // forty blocks round it, for the woods it stands in
		// Roads as far as it may spread (a village's houses go well past the ground judged), and ten
		// blocks more for a road piece's length: one ran through the village by Whiterun.
		double roadReach = Math.min(2.0 * radius + 700.0, around);
		for (int x = cellOf(cx - around); x <= cellOf(cx + around); x++) {
			for (int y = cellOf(cy - around); y <= cellOf(cy + around); y++) {
				Cell c = cells.get(key(x, y));
				if (c == null) {
					continue;
				}
				for (float[] o : c.obstacles) {
					if (sq(o[0] - cx) + sq(o[1] - cy) <= roadReach * roadReach) {  // (as far as roads: it spreads that far)
						obstacles++;
					}
				}
				for (float[] o : c.mountains) {
					if (sq(o[0] - cx) + sq(o[1] - cy) <= radius * radius) {
						mountains++;  // (on the structure itself: they stand everywhere round it)
					}
				}
				for (float[] o : c.roadPieces) {
					if (sq(o[0] - cx) + sq(o[1] - cy) <= roadReach * roadReach) {
						roads++;
					}
				}
				for (float[] o : c.rocks) {
					if (sq(o[0] - cx) + sq(o[1] - cy) <= (radius + 300.0) * (radius + 300.0)) {
						rocks++;
					}
				}
				for (float[] t : c.trees) {
					double d2 = sq(t[0] - cx) + sq(t[1] - cy);
					if (d2 <= (radius + 300.0) * (radius + 300.0)) {
						trees++;  // in it (or within a few blocks)
					} else if (d2 <= around * around) {
						nearTrees++;
					}
				}
			}
		}
		return new Ground(dry, wet, min, max, sum / n, depth, obstacles, trees, nearTrees, roads, rocks, mountains, above > 0 && below > 0);
	}

	/** On a quarter of a cell painted with a road texture. */
	private boolean onRoad(double sx, double sy) {
		int cx = cellOf(sx), cy = cellOf(sy);
		Cell c = cells.get(key(cx, cy));
		if (c == null) {
			return false;
		}
		int qx = sx - cx * (double) CELL_UNITS >= CELL_UNITS / 2 ? 1 : 0, qy = sy - cy * (double) CELL_UNITS >= CELL_UNITS / 2 ? 1 : 0;
		return c.road[qx + 2 * qy];
	}

	private static double sq(double v) {
		return v * v;
	}

	private interface Score {
		/** Higher is better; NaN: not here. */
		double of(Ground g, double x, double y);
	}

	/** One kind of structure: how far it reaches (blocks), how many more after the first, where it goes. */
	private record Kind(String name, String structure, double radiusBlocks, int extra, Score score) {
	}

	private void choose() {
		double sea = defaultWater;
		List<Kind> kinds = List.of(
			// Big and rare first.
			// SkyCraft+ : the flattest wide ground there is (it stood half in a mountain): the slope counts
			// for the most, the woods round it a little.
			new Kind("Woodland mansion", "minecraft:mansion", 32, 0, (g, x, y) -> flat(28, 8).of(g, x, y) - (g.max - g.min) / UNITS * 0.3 + Math.min(g.nearTrees, 60) * 0.01),
			new Kind("Ocean monument", "minecraft:monument", 32, 0, deep(32)),
			new Kind("Snowy village", "minecraft:village_snowy", 24, 4, (g, x, y) -> g.mean > sea + 16000 ? flat(28, 4).of(g, x, y) : Double.NaN),
			new Kind("Taiga village", "minecraft:village_taiga", 24, 4, (g, x, y) -> y < -12 * CELL_UNITS ? flat(28, 8).of(g, x, y) + Math.min(g.nearTrees, 80) * 0.02 : Double.NaN),  // the southern forests (Falkreath, the Rift)
			new Kind("Plains village", "minecraft:village_plains", 24, 6, flat(28, 4)),
			new Kind("Savanna village", "minecraft:village_savanna", 24, 3, flat(28, 4)),
			new Kind("Desert village", "minecraft:village_desert", 24, 2, flat(28, 4)),
			new Kind("Pillager outpost", "minecraft:pillager_outpost", 22, 10, flat(16, 1)),
			new Kind("Desert temple", "minecraft:desert_pyramid", 14, 2, flat(6, 0, 18)),  // its cellar goes 14 down
			new Kind("Jungle temple", "minecraft:jungle_pyramid", 10, 2, (g, x, y) -> g.nearTrees >= 10 ? flat(8, 0, 10).of(g, x, y) + Math.min(g.nearTrees, 40) * 0.05 : Double.NaN),
			new Kind("Witch hut", "minecraft:swamp_hut", 10, 2, (g, x, y) -> g.shore && g.obstacles == 0 && g.roads == 0 && g.trees == 0 && g.mean < sea + 2500 && g.max - g.min < 700 ? 5 : Double.NaN),
			// (Igloos are left out for now: high up on the mountains they stood in the air, and fast travel
			// to them landed under the ground.)
			new Kind("Ruined portal", "minecraft:ruined_portal_desert", 8, 3, flat(8, 0, 8)),  // partly buried
			new Kind("Overgrown ruined portal", "minecraft:ruined_portal_jungle", 8, 3, flat(8, 0)),  // on the surface
			new Kind("Trail ruins", "minecraft:trail_ruins", 15, 2, flat(10, 0, 16)),  // buried
			new Kind("Buried treasure", "minecraft:buried_treasure", 3, 3, (g, x, y) -> g.shore && g.obstacles == 0 && g.roads == 0 ? 1 : Double.NaN),
			new Kind("Shipwreck", "minecraft:shipwreck", 18, 2, deep(8)),  // its length, whichever way it lies
			new Kind("Beached shipwreck", "minecraft:shipwreck_beached", 16, 2, (g, x, y) -> g.shore && g.obstacles == 0 && g.roads == 0 && g.max - g.min < 4 * UNITS ? 2 : Double.NaN),  // a flat shore: on a slope it sank out of sight
			new Kind("Ocean ruins", "minecraft:ocean_ruin_cold", 10, 3, deep(10)));
		// Candidate spots: every half cell inside the part of Tamriel one can walk to.
		List<double[]> spots = new ArrayList<>();
		for (Cell c : cells.values()) {
			if (c.land && c.navmesh && playable(c.x, c.y) && insideUnits(c.x * (double) CELL_UNITS + 2048, c.y * (double) CELL_UNITS + 2048, 0)) {
				for (int i = 0; i < 2; i++) {
					for (int j = 0; j < 2; j++) {
						spots.add(new double[] { c.x * (double) CELL_UNITS + 1024 + i * 2048, c.y * (double) CELL_UNITS + 1024 + j * 2048 });
					}
				}
			}
		}
		spots.sort(Comparator.<double[]>comparingDouble(s -> s[0]).thenComparingDouble(s -> s[1]));  // the same order everywhere
		List<double[]> chosen = new ArrayList<>();
		// Each spot's ground for each size, worked out once (it was again for every pick: 46 s).
		Map<Long, java.util.Optional<Ground>> grounds = new HashMap<>();
		for (int round = 0; round < 2; round++) {
			for (Kind kind : kinds) {
				int count = round == 0 ? 1 : kind.extra;
				for (int i = 0; i < count; i++) {
					double[] best = null;
					double bestScore = Double.NEGATIVE_INFINITY;
					// Never on a mountain (dug into, it stood up through the structure's ground in pieces) but the
					// first of a kind, if there's nowhere else for it: one of every structure.
					for (int pass = 0; pass < (round == 0 ? 2 : 1) && best == null; pass++) {
					final boolean onMountains = pass == 1;
					for (int si = 0; si < spots.size(); si++) {
						double[] s = spots.get(si);
						// Well inside Skyrim's invisible walls, as far as it may spread and a little more (one
						// beyond them couldn't be fast travelled to, and flying there left Skyrim's world).
						if (tooClose(chosen, s[0], s[1]) || !insideUnits(s[0], s[1], (kind.radiusBlocks * 2.0 + 24.0) * UNITS)) {
							continue;
						}
						long cacheKey = si * 1024L + (long) kind.radiusBlocks;
						Ground g = grounds.computeIfAbsent(cacheKey, k -> java.util.Optional.ofNullable(ground(s[0], s[1], kind.radiusBlocks * UNITS))).orElse(null);
						if (g == null || (g.mountains > 0 && !onMountains && offMountains)) {
							continue;
						}
						// (SkyCraft+ : the mansion on the mountains: as few of their pieces in it as can be.)
						double score = kind.score.of(g, s[0], s[1]) - crowding(kind.structure, s[0] / UNITS, -s[1] / UNITS) - (onMountains && kind.structure.equals("minecraft:mansion") ? g.mountains * 3.0 : 0.0);
						if (!Double.isNaN(score) && score > bestScore) {
							bestScore = score;
							best = s;
						}
					}
					}
					if (best == null) {
						break;
					}
					chosen.add(best);
					sites.add(new Site(kind.name + (round == 0 ? "" : " " + (i + 2)), kind.structure, best[0] / UNITS, -best[1] / UNITS, Double.NaN));
				}
			}
		}
	}

	/**
	 * Spread over Skyrim: less good the nearer others of its sort already are (villages of any
	 * kind count together). The flattest land is in a few places, which took nearly all of them.
	 */
	private double crowding(String structure, double x, double z) {
		String sort = structure.contains("village") ? "village" : structure;
		double crowd = 0;
		for (Site t : sites) {
			if ((t.structure().contains("village") ? "village" : t.structure()).equals(sort)) {
				double d = Math.sqrt(sq(t.x() - x) + sq(t.z() - z));
				crowd += Math.max(0.0, 1.0 - d / 1000.0) * 6.0;
			}
		}
		return crowd;
	}

	/** Dry ground no more than {@code rangeBlocks} up and down, clear of Skyrim's buildings, with few enough trees. */
	private static Score flat(double rangeBlocks, int maxTrees) {
		return flat(rangeBlocks, maxTrees, 0);
	}

	/**
	 * The same, with its ground at least {@code dryBlocks} above Skyrim's water there: a structure
	 * going that far down (a temple's cellar) has no water showing in it once the land is dug out, and
	 * its Minecraft ground stays clear of the water's surface.
	 */
	private static Score flat(double rangeBlocks, int maxTrees, double dryBlocks) {
		return (g, x, y) -> {
			double range = (g.max - g.min) / UNITS;
			if (!g.dry || -g.depth < dryBlocks * UNITS || g.obstacles > 0 || g.roads > 0 || g.trees > maxTrees || range > rangeBlocks
				|| g.rocks > Math.max(1, rangeBlocks / 5)) {
				return Double.NaN;
			}
			return 10.0 - range / rangeBlocks * 5.0 - g.trees * 0.02 - g.rocks * 0.5;
		};
	}

	/** Under water at least {@code blocks} deep all over. */
	private static Score deep(double blocks) {
		return (g, x, y) -> g.wet && g.depth >= blocks * UNITS ? 10.0 - Math.abs(g.depth / UNITS - blocks * 1.5) * 0.05 : Double.NaN;
	}

	/**
	 * Inside the part of Tamriel one can walk to (its outer cells are mountains and sea behind
	 * Skyrim's invisible borders), in cells.
	 */
	/**
	 * Inside Skyrim's border (its invisible walls) by at least {@code marginBlocks} (Minecraft x, z).
	 * True when Skyrim.esm has no border (a total conversion): nothing to keep inside.
	 */
	public boolean insideBorder(double x, double z, double marginBlocks) {
		return insideUnits(x * UNITS, -z * UNITS, marginBlocks * UNITS);
	}

	private boolean insideUnits(double sx, double sy, double margin) {
		if (border.isEmpty()) {
			return true;
		}
		boolean inside = false;
		double nearest = Double.MAX_VALUE;
		for (float[] poly : border) {
			int n = poly.length / 2;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				double xi = poly[2 * i], yi = poly[2 * i + 1], xj = poly[2 * j], yj = poly[2 * j + 1];
				if ((yi > sy) != (yj > sy) && sx < (xj - xi) * (sy - yi) / (yj - yi) + xi) {
					inside = !inside;
				}
				double ex = xj - xi, ey = yj - yi, len2 = ex * ex + ey * ey;
				double t = len2 > 0 ? Math.max(0, Math.min(1, ((sx - xi) * ex + (sy - yi) * ey) / len2)) : 0;
				nearest = Math.min(nearest, Math.hypot(sx - (xi + t * ex), sy - (yi + t * ey)));
			}
		}
		return inside && nearest >= margin;
	}

	private static boolean playable(int x, int y) {
		return x >= -50 && x <= 50 && y >= -32 && y <= 32;
	}

	/** At least about 220 blocks from every other spot. */
	private static boolean tooClose(List<double[]> chosen, double x, double y) {
		double spacing = 220.0 * UNITS;
		for (double[] o : chosen) {
			if (sq(o[0] - x) + sq(o[1] - y) < spacing * spacing) {
				return true;
			}
		}
		return false;
	}

	private void findDry() {
		for (Cell c : cells.values()) {
			float[] h = heights.get(key(c.x, c.y));
			if (h == null || !c.land || !c.navmesh || !playable(c.x, c.y)) {
				continue;
			}
			double water = Float.isNaN(c.water) ? defaultWater : c.water;
			float low = Float.MAX_VALUE;
			for (float v : h) {
				low = Math.min(low, v);
			}
			if (low > water + 4 * UNITS) {
				dry.add(key(c.x, c.y));
			}
		}
	}

	/**
	 * SkyCraft+ : strongholds. The nearest spot to a Minecraft x/z with dry land three cells (about 175
	 * blocks) all round it and at least 400 blocks from those {@code taken}: Minecraft x, z. Null when
	 * there's none within about 2800 blocks.
	 */
	public double[] dryGroundNear(double x, double z, List<double[]> taken) {
		int ox = cellOf(x * UNITS), oy = cellOf(-z * UNITS);
		if (ox < -50 - 48 || ox > 50 + 48 || oy < -32 - 48 || oy > 32 + 48) {
			return null;  // nowhere near Skyrim's land
		}
		for (int r = 0; r <= 48; r++) {
			double[] best = null;
			int bestD = Integer.MAX_VALUE;
			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r || dx * dx + dy * dy >= bestD || !dryAround(ox + dx, oy + dy, 3)) {
						continue;
					}
					double sx = (ox + dx) * (double) CELL_UNITS + CELL_UNITS / 2.0, sy = (oy + dy) * (double) CELL_UNITS + CELL_UNITS / 2.0;
					double mx = sx / UNITS, mz = -sy / UNITS;
					boolean near = false;
					for (double[] t : taken) {
						near |= sq(t[0] - mx) + sq(t[1] - mz) < 400.0 * 400.0;
					}
					if (!near && insideUnits(sx, sy, 0)) {
						best = new double[] { mx, mz };
						bestD = dx * dx + dy * dy;
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	private boolean dryAround(int cx, int cy, int reach) {
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dy = -reach; dy <= reach; dy++) {
				if (!dry.contains(key(cx + dx, cy + dy))) {
					return false;
				}
			}
		}
		return true;
	}

	/** Only the land within three cells of a spot is kept (for building there); the rest goes. */
	private void keepHeightsNearSites() {
		Set<Long> keep = new HashSet<>();
		for (Site s : sites) {
			int cx = cellOf(s.x * UNITS), cy = cellOf(-s.z * UNITS);
			for (int dx = -3; dx <= 3; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					keep.add(key(cx + dx, cy + dy));
				}
			}
		}
		heights.keySet().retainAll(keep);
		List<Site> withGround = new ArrayList<>();
		for (Site s : sites) {
			withGround.add(new Site(s.name, s.structure, s.x, s.z, groundAt(s.x, s.z)));
		}
		sites.clear();
		sites.addAll(withGround);
		for (Cell c : cells.values()) {
			c.obstacles.clear();
			c.trees.clear();
		}
	}

	public static void main(String[] args) throws IOException {
		long t0 = System.nanoTime();
		SkyrimMap map = read(Path.of(args[0]));
		System.out.printf("read in %.1f s; sea level y %.1f; %d spots%n", (System.nanoTime() - t0) / 1e9, map.seaLevel(), map.sites().size());
		for (Site s : map.sites()) {
			System.out.printf("%-22s %-30s x %7.0f z %7.0f ground y %6.1f water y %6.1f%n", s.name(), s.structure(), s.x(), s.z(), s.groundY(),
				map.waterAt(s.x(), s.z()));
		}
	}
}
