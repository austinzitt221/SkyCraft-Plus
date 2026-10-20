// SkyCraft+ : Minecraft's things at Skyrim's workbenches
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.craft.SkyEnchanting;
import dev.skycraft.craft.SkyLending;
import dev.skycraft.craft.SkyMaterials;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of SkyLending: when this player's Skyrim says they went to a workbench
 * (SkyState::workbench), the Minecraft materials that suit it are lent (the server takes them, this
 * player's Skyrim gets the same Skyrim ones), and at the enchanter the weapon or armor in hand goes
 * in as a stand-in; when its menu closes, Skyrim gives back as many as it still has (what was used
 * is gone) and the server gives those back as Minecraft's, the gear with what it was enchanted with.
 */
public final class SkyLendingClient {
	private SkyLendingClient() {
	}

	private enum Phase { IDLE, ASKED, LENT, RETURNING }

	private static final class Entry {
		final int lent;
		final SkyLink.@Nullable SkyItem sky;
		int giveId, takeId, back;
		boolean given, answered;

		Entry(int lent, SkyLink.@Nullable SkyItem sky) {
			this.lent = lent;
			this.sky = sky;
		}
	}

	private static Phase phase = Phase.IDLE;
	private static long phaseAt;
	private static int seenSeq = Integer.MIN_VALUE;
	private static int lendingSeq;
	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static String gearName = "";
	private static int gearKind, gearLendId, gearReturnId;
	private static boolean gearLent, gearAnswered;
	private static SkyEnchanting.@Nullable Enchant enchant;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SkyLending.Lent.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> lent(payload)));
	}

	/** Which of Skyrim's things a workbench wants (Proto.BENCH_*): an alchemy lab ingredients, a grindstone or workbench ingots and leather. */
	private static boolean suits(int bench, int kind) {
		return switch (bench) {
			case Proto.BENCH_ALCHEMY, 6 -> kind == Proto.SKY_INGREDIENT;
			case Proto.BENCH_SHARPEN, Proto.BENCH_ARMOR -> kind == Proto.SKY_MISC;
			case Proto.BENCH_CREATE -> true;
			default -> false;
		};
	}

	static void tick(Minecraft minecraft) {
		var player = minecraft.player;
		if (player == null || !SkyLink.active() || !ClientPlayNetworking.canSend(SkyLending.Lend.TYPE) || !dev.skycraft.SkySettings.flag("skyrimWorkbenches", true)) {
			phase = Phase.IDLE;
			return;
		}
		SkyLink.SkyState sky = SkyClient.sky();
		if (seenSeq == Integer.MIN_VALUE) {
			seenSeq = sky.workbenchSeq;  // (a workbench gone to before this world was joined isn't ours)
		}
		long now = System.currentTimeMillis();
		switch (phase) {
			case IDLE -> {
				if (sky.workbench != 0 && sky.workbenchSeq != seenSeq) {
					seenSeq = sky.workbenchSeq;
					lend(player, sky.workbench, sky.workbenchSeq);
				}
			}
			case ASKED -> {
				if (now - phaseAt > 5000L) {
					phase = Phase.IDLE;  // no answer (a late one gives everything straight back: lent())
				}
			}
			case LENT -> {
				if (sky.workbench == 0 || sky.workbenchSeq != lendingSeq) {
					startReturn();
				}
			}
			case RETURNING -> {
				for (Entry e : ENTRIES) {
					if (!e.answered && e.takeId == 0 && e.sky != null) {
						e.takeId = SkyLink.pushStoreRequest(Proto.STORE_TAKE, e.sky.formId(), e.lent, 1.0F, -1.0F, 0, e.sky.localId(), e.sky.plugin());
					}
				}
				if (gearLent && !gearAnswered && gearReturnId == 0) {
					gearReturnId = SkyLink.pushStoreRequest(Proto.STORE_RETURN_GEAR, 0, 1, 1.0F, -1.0F, 0, 0, "");
				}
				boolean all = gearAnswered && ENTRIES.stream().allMatch(e -> e.answered);
				if (all || now - phaseAt > 6000L) {
					sendReturn();
				}
			}
		}
	}

	private static void lend(net.minecraft.client.player.LocalPlayer player, int bench, int seq) {
		var inventory = player.getInventory();
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (SkyMaterials.hasSkyrimMatch(stack)) {
				SkyLink.SkyItem sky = SkyMaterials.skyrimItem(stack.getItem());
				if (sky != null && suits(bench, sky.kind())) {
					slots.add(i);
				}
			}
		}
		int gearSlot = -1;
		if (bench == Proto.BENCH_ENCHANT || bench == 4) {
			ItemStack held = player.getMainHandItem();
			if (SkyLending.gearKind(held) != 0 && SkyEnchanting.enchant(held) == null) {
				gearSlot = inventory.getSelectedSlot();
			}
		}
		if (slots.isEmpty() && gearSlot < 0) {
			return;
		}
		lendingSeq = seq;
		phase = Phase.ASKED;
		phaseAt = System.currentTimeMillis();
		ClientPlayNetworking.send(new SkyLending.Lend(seq, slots, gearSlot));
	}

	/** The server took them: Skyrim gets the same Skyrim things (and the gear's stand-in). */
	private static void lent(SkyLending.Lent payload) {
		ENTRIES.clear();
		gearName = payload.gearName();
		gearKind = payload.gearKind();
		gearLendId = gearReturnId = 0;
		gearLent = false;
		gearAnswered = gearKind == 0;
		enchant = null;
		boolean stale = phase != Phase.ASKED || payload.seq() != lendingSeq;
		lendingSeq = payload.seq();
		for (SkyLending.Entry e : payload.entries()) {
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(e.item())).orElse(null);
			SkyLink.SkyItem sky = item == null ? null : SkyMaterials.skyrimItem(item);
			Entry entry = new Entry(e.count(), sky);
			if (sky != null && !stale) {
				entry.giveId = SkyLink.pushStoreRequest(Proto.STORE_GIVE, 0, e.count(), 1.0F, -1.0F, 0, sky.localId(), sky.plugin());
			}
			ENTRIES.add(entry);
		}
		if (gearKind != 0 && !stale) {
			gearLendId = SkyLink.pushStoreRequest(Proto.STORE_LEND_GEAR, 0, 1, 1.0F, -1.0F, gearKind, 0, gearName);
		}
		phase = Phase.LENT;
		phaseAt = System.currentTimeMillis();
		SkyCraft.LOG.info("SkyCraft: lent {} stack(s){} to Skyrim's workbench{}", ENTRIES.size(), gearKind != 0 ? " and " + gearName : "", stale ? " too late: back at once" : "");
		if (stale) {
			startReturn();
		}
	}

	private static void startReturn() {
		for (Entry e : ENTRIES) {
			if (!e.given) {
				e.back = e.lent;  // Skyrim never got them: all of them come back
				e.answered = true;
			}
		}
		if (!gearLent) {
			gearAnswered = true;
		}
		phase = Phase.RETURNING;
		phaseAt = System.currentTimeMillis();
	}

	private static void sendReturn() {
		List<Integer> counts = new ArrayList<>();
		for (Entry e : ENTRIES) {
			counts.add(e.back);
		}
		ClientPlayNetworking.send(new SkyLending.Return(lendingSeq, counts, enchant));
		SkyCraft.LOG.info("SkyCraft: back from Skyrim's workbench: {}{}", counts, enchant != null ? ", enchanted with " + enchant.effects() : "");
		ENTRIES.clear();
		phase = Phase.IDLE;
	}

	/** One of Skyrim's answers (SkyStorageClient hands on the ones that aren't its own); true if it was this. */
	static boolean onStoreResult(SkyLink.StoreResult result) {
		int id = result.id();
		if (id == 0) {
			return false;
		}
		for (Entry e : ENTRIES) {
			if (e.giveId == id) {
				e.given = result.status() == Proto.STORE_OK;
				return true;
			}
			if (e.takeId == id) {
				e.back = Math.min(e.lent, e.back + (result.status() == Proto.STORE_OK ? result.count() : 0));
				e.answered = true;
				return true;
			}
		}
		if (id == gearLendId) {
			gearLent = result.status() == Proto.STORE_OK;
			return true;
		}
		if (id == gearReturnId) {
			enchant = result.status() == Proto.STORE_OK && !result.name().isEmpty() ? new SkyEnchanting.Enchant(result.formId(), result.name(), result.charge(),
				result.health(), result.stat()) : null;
			gearAnswered = true;
			return true;
		}
		return false;
	}

	/** Skyrim's inventory is kept current while things are lent (the counts it gives back come from it). */
	static boolean busy() {
		return phase != Phase.IDLE;
	}
}
