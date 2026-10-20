// SkyCraft+ : Minecraft's structures put away and brought back
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's structures in Skyrim's world turned off and on again while playing (skyrimStructures,
 * the SkyCraft+ settings screen). Turned off, each structure built so far is put away as it is: its
 * blocks, chests (what's been taken stays taken), villagers and the rest saved in the world's folder
 * (skycraft-stash/), the ground it stands on given back to Skyrim (its dug cells no longer dug), and
 * the place emptied. Turned on again, each comes back exactly as it was put away, and its ground is
 * dug out again: a looted temple doesn't fill up again. One structure a few ticks, so turning it
 * over doesn't stall the game; a structure a player stands in waits till they've left.
 */
public final class SkyStructureStash {
	private SkyStructureStash() {
	}

	/** Its ground (the pad round it, the dirt under its raised parts) is part of it. */
	private static final int AROUND = 4, BELOW = 8, ABOVE = 2;

	private static final Deque<String> QUEUE = new ArrayDeque<>();
	private static boolean target = true;  // what the queue works towards: true all out, false all away
	private static int wait;

	private static Path folder(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("skycraft-stash");
	}

	private static Path file(MinecraftServer server, String name) {
		return folder(server).resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".nbt");
	}

	/** Something queued to put away or bring back. */
	static boolean busy() {
		return !QUEUE.isEmpty();
	}

	public static boolean stashed(MinecraftServer server, String name) {
		return Files.exists(file(server, name));
	}

	/** The structures are to be out (true) or put away (false): every built one is queued to go that way. */
	static void towards(MinecraftServer server, boolean out, Map<String, BoundingBox> built) {
		target = out;
		QUEUE.clear();
		for (var entry : built.entrySet()) {
			if (entry.getValue().getXSpan() > 1 && stashed(server, entry.getKey()) == out) {
				QUEUE.add(entry.getKey());
			}
		}
		if (!QUEUE.isEmpty()) {
			SkyCraft.LOG.info("SkyCraft: {} {} Minecraft structure(s)", out ? "bringing back" : "putting away", QUEUE.size());
		}
	}

	/** Each server tick: the next queued structure, put away or brought back. */
	static void tick(MinecraftServer server, Map<String, BoundingBox> built) {
		if (QUEUE.isEmpty() || --wait > 0) {
			return;
		}
		wait = 5;
		String name = QUEUE.poll();
		BoundingBox box = built.get(name);
		if (box == null) {
			return;
		}
		ServerLevel level = server.overworld();
		BoundingBox area = new BoundingBox(box.minX() - AROUND, box.minY() - BELOW, box.minZ() - AROUND, box.maxX() + AROUND, box.maxY() + ABOVE, box.maxZ() + AROUND);
		if (!target && someoneIn(level, area)) {
			QUEUE.add(name);  // later, once they're out
			wait = 40;
			return;
		}
		try {
			if (target) {
				bringBack(level, name, area);
			} else {
				putAway(level, name, area);
			}
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't {} {}", target ? "bring back" : "put away", name, e);
		}
	}

	private static boolean someoneIn(ServerLevel level, BoundingBox area) {
		for (Player p : level.players()) {
			if (area.isInside(p.blockPosition())) {
				return true;
			}
		}
		return false;
	}

	/** The structure in a box a player is standing in, for the settings screen's refusal (null: none). */
	public static @Nullable String standingIn(MinecraftServer server, BlockPos pos, Map<String, BoundingBox> built) {
		for (var entry : built.entrySet()) {
			BoundingBox box = entry.getValue();
			if (box.getXSpan() > 1 && box.inflatedBy(AROUND).isInside(pos) && !stashed(server, entry.getKey())) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static void putAway(ServerLevel level, String name, BoundingBox area) throws IOException {
		MinecraftServer server = level.getServer();
		for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
			for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
				level.getChunk(cx, cz);  // (loaded if it wasn't: its blocks are read)
			}
		}
		BlockPos min = new BlockPos(area.minX(), area.minY(), area.minZ());
		Vec3i size = new Vec3i(area.getXSpan(), area.getYSpan(), area.getZSpan());
		StructureTemplate template = new StructureTemplate();
		template.fillFromWorld(level, min, size, true, List.of(Blocks.STRUCTURE_VOID));
		CompoundTag tag = template.save(new CompoundTag());
		SkyDig.Cells dug = SkyDig.undigBox(level, area);
		tag.putIntArray("skycraft_dug_worlds", dug.worlds());
		tag.putLongArray("skycraft_dug_cells", dug.positions());
		Files.createDirectories(folder(server));
		Path temp = file(server, name).resolveSibling(file(server, name).getFileName() + ".tmp");
		NbtIo.writeCompressed(tag, temp);
		Files.move(temp, file(server, name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		// Emptied: its people and things first, then its blocks (no drops, no neighbours told: it all goes at once).
		for (Entity e : level.getEntities((Entity) null, new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1),
			e -> !(e instanceof Player) && !(e instanceof dev.skycraft.combat.SkyrimActorEntity))) {
			e.discard();
		}
		List<BlockPos> solid = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ())) {
			if (!level.getBlockState(pos).isAir()) {
				if (level.getBlockEntity(pos) instanceof Clearable clearable) {
					clearable.clearContent();
				}
				solid.add(pos.immutable());
			}
		}
		for (BlockPos pos : solid) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
		}
		SkyCraft.LOG.info("SkyCraft: put away {} ({} blocks, {} dug cells given back to Skyrim)", name, solid.size(), dug.positions().length);
	}

	private static void bringBack(ServerLevel level, String name, BoundingBox area) throws IOException {
		MinecraftServer server = level.getServer();
		Path path = file(server, name);
		if (!Files.exists(path)) {
			return;
		}
		CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.create(1L << 30));
		StructureTemplate template = new StructureTemplate();
		template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		SkyDig.redig(level, new SkyDig.Cells(tag.getIntArray("skycraft_dug_worlds").orElse(new int[0]), tag.getLongArray("skycraft_dug_cells").orElse(new long[0])));
		BlockPos min = new BlockPos(area.minX(), area.minY(), area.minZ());
		template.placeInWorld(level, min, min, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
		Files.deleteIfExists(path);
		SkyCraft.LOG.info("SkyCraft: brought back {} as it was put away", name);
	}
}
