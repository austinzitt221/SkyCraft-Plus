package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The list of every distinct collision shape in the game, for the pre-built collision model library.
 *
 * A shape is a list of boxes in sixteenths of a block (0..32; fences are 1.5 tall), sorted. Its key is the FNV-1a hash of
 * {@link #canonical} written as 8 hex digits, and that key is the library file name ({@code shp_<key>.nif}). The SKSE side
 * (ShapeKey in Game.h) must compute the same key from the boxes Minecraft sends, so {@link #canonical} is the one definition of it.
 *
 * Written by itself to {@code skycraft/shape_catalog.json} in the game folder the first time the world starts, and again when the registered
 * blocks change ({@code -Dskycraft.dumpShapes=false} turns it off, {@code force} writes it every start); tools/make_collision_library.py turns that file into the .nif files.
 */
public final class ShapeCatalog {
	private ShapeCatalog() {}

	/** One box in sixteenths: x0 y0 z0 x1 y1 z1. */
	public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {}

	public static int sixteenths(double v) {
		return Math.max(0, Math.min(32, (int) Math.round(v * 16.0)));
	}

	/** The shape's boxes, quantised and sorted; empty boxes (thinner than a sixteenth) are dropped. */
	public static List<Box> boxesOf(VoxelShape shape) {
		List<Box> out = new ArrayList<>();
		for (AABB b : shape.toAabbs()) {
			Box box = new Box(sixteenths(b.minX), sixteenths(b.minY), sixteenths(b.minZ), sixteenths(b.maxX), sixteenths(b.maxY), sixteenths(b.maxZ));
			if (box.x1 > box.x0 && box.y1 > box.y0 && box.z1 > box.z0) {
				out.add(box);
			}
		}
		out.sort(Comparator.comparingInt(Box::y0).thenComparingInt(Box::x0).thenComparingInt(Box::z0)
			.thenComparingInt(Box::y1).thenComparingInt(Box::x1).thenComparingInt(Box::z1));
		return out;
	}

	/** "x0,y0,z0,x1,y1,z1;..." of the sorted boxes. */
	public static String canonical(List<Box> boxes) {
		StringBuilder sb = new StringBuilder();
		for (Box b : boxes) {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(b.x0).append(',').append(b.y0).append(',').append(b.z0).append(',').append(b.x1).append(',').append(b.y1).append(',').append(b.z1);
		}
		return sb.toString();
	}

	public static String key(List<Box> boxes) {
		int h = 0x811C9DC5;
		for (byte c : canonical(boxes).getBytes(StandardCharsets.US_ASCII)) {
			h ^= (c & 0xFF);
			h *= 0x01000193;
		}
		return String.format("%08x", h);
	}

	private static final class Entry {
		final List<Box> boxes;
		int states;
		final List<String> examples = new ArrayList<>();

		Entry(List<Box> boxes) {
			this.boxes = boxes;
		}
	}

	/** The number of registered blocks, written into the catalogue so a later start can tell whether blocks were added or removed. */
	private static int blockCount() {
		int n = 0;
		for (Block ignored : BuiltInRegistries.BLOCK) {
			n++;
		}
		return n;
	}

	/** Whether the catalogue in a_file was made for the blocks that are registered now. */
	public static boolean upToDate(Path a_file) {
		try {
			String text = Files.readString(a_file, StandardCharsets.UTF_8);
			return text.contains("\"blocks\": " + blockCount() + ",");
		} catch (IOException ex) {
			return false;
		}
	}

	/** Walks every state of every registered block (modded ones too) and writes the distinct collision shapes as JSON. */
	public static void dump(Path file) {
		Map<String, Entry> shapes = new TreeMap<>();
		int states = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				if (shape.isEmpty()) {
					continue;
				}
				List<Box> boxes = boxesOf(shape);
				if (boxes.isEmpty()) {
					continue;
				}
				states++;
				Entry e = shapes.computeIfAbsent(key(boxes), k -> new Entry(boxes));
				e.states++;
				if (e.examples.size() < 4 && !e.examples.contains(id)) {
					e.examples.add(id);
				}
			}
		}

		// A whole block right above a door has a gap at its bottom in Skyrim (WorldExporter.DOOR_HEADROOM).
		List<Box> headroom = List.of(new Box(0, 4, 0, 16, 16, 16));
		Entry headroomEntry = shapes.computeIfAbsent(key(headroom), k -> new Entry(headroom));
		headroomEntry.states++;
		headroomEntry.examples.add("skycraft:door_headroom");

		StringBuilder json = new StringBuilder("{\n  \"blocks\": " + blockCount() + ",\n  \"unit\": \"sixteenths\",\n  \"shapes\": [\n");
		boolean first = true;
		int maxBoxes = 0;
		for (Map.Entry<String, Entry> entry : shapes.entrySet()) {
			Entry e = entry.getValue();
			maxBoxes = Math.max(maxBoxes, e.boxes.size());
			json.append(first ? "" : ",\n").append("    {\"key\": \"").append(entry.getKey()).append("\", \"states\": ").append(e.states).append(", \"examples\": [");
			for (int i = 0; i < e.examples.size(); i++) {
				json.append(i == 0 ? "" : ", ").append('"').append(e.examples.get(i)).append('"');
			}
			json.append("], \"boxes\": [");
			for (int i = 0; i < e.boxes.size(); i++) {
				Box b = e.boxes.get(i);
				json.append(i == 0 ? "" : ", ").append('[').append(b.x0).append(", ").append(b.y0).append(", ").append(b.z0).append(", ")
					.append(b.x1).append(", ").append(b.y1).append(", ").append(b.z1).append(']');
			}
			json.append("]}");
			first = false;
		}
		json.append("\n  ]\n}\n");

		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
			SkyCraft.LOG.info("SkyCraft: shape catalog: {} distinct collision shapes over {} block states (most boxes in one: {}) -> {}", shapes.size(), states, maxBoxes, file);
		} catch (IOException ex) {
			SkyCraft.LOG.error("SkyCraft: couldn't write the shape catalog", ex);
		}
	}
}
