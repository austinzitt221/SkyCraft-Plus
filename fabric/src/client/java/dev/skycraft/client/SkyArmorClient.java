// SkyCraft+ : Skyrim armor in Minecraft's armor slots
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyArmor;
import dev.skycraft.net.SkyShortcuts;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of SkyArmor: Minecraft's armor slots and what Skyrim wears kept together, both
 * ways. A Skyrim piece put in its armor slot (dragged there, or clicked in the Skyrim panel) is put
 * on in Skyrim; taken out of the slot, it comes off. Put on or taken off in Skyrim (its own menu),
 * its shortcut goes into the slot (a Minecraft piece there moves to the inventory) or leaves it.
 * Gauntlets, rings and necklaces have no Minecraft slot: they show in the panels' own slots, and
 * gauntlets add their armor to the player's. Setting: skyrimArmorSlots in config/skycraft.properties.
 */
public final class SkyArmorClient {
	private SkyArmorClient() {
	}

	private static final long GRACE = 2_000_000_000L, RESEND = 1_500_000_000L;

	/** What an armor slot held last tick, and the Skyrim piece it put on. */
	private static final class Track {
		String key = "";
		int formId;
		long since;
	}

	private static final Track[] TRACK = { new Track(), new Track(), new Track(), new Track() };  // inventory 36 .. 39
	private static Set<Integer> wornBefore = new HashSet<>();
	private static final Map<Integer, Long> WEAR_SENT = new HashMap<>();
	private static float sentHands = -1.0F, sentHandsTough = -1.0F;
	private static @Nullable Boolean enabled;
	// SkyCraft+ : the settings screen. Read again after a change.
	static {
		dev.skycraft.SkySettings.onChange(() -> enabled = null);
	}

	public static boolean enabled() {
		if (enabled == null) {
			java.util.Properties p = new java.util.Properties();
			java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
			if (java.nio.file.Files.exists(config)) {
				try (java.io.Reader in = java.nio.file.Files.newBufferedReader(config)) {
					p.load(in);
				} catch (java.io.IOException e) {
					SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
				}
			}
			enabled = Boolean.parseBoolean(p.getProperty("skyrimArmorSlots", "true").trim());
			SkyCraft.LOG.info("SkyCraft: Skyrim armor in Minecraft's armor slots: {}", enabled ? "on" : "off");
		}
		return enabled;
	}

	/** The Minecraft armor slot a Skyrim thing goes in, or null. */
	static @Nullable EquipmentSlot slotOf(SkyLink.SkyItem item) {
		return enabled() && item.kind() == Proto.SKY_APPAREL ? SkyArmor.slotFor(item.sub()) : null;
	}

	private static boolean canSend() {
		return ClientPlayNetworking.canSend(SkyArmor.Wear.TYPE);
	}

	private static void wear(SkyLink.SkyItem item, EquipmentSlot slot) {
		if (canSend()) {
			ClientPlayNetworking.send(new SkyArmor.Wear(SkyShortcutsClient.makeOf(item, SkyArmor.inventoryIndex(slot))));
			WEAR_SENT.put(item.formId(), System.nanoTime());
		}
	}

	/**
	 * A click on Skyrim apparel in the panel: a piece with a Minecraft slot goes into it (and on), or
	 * (worn) comes off and out of it. False: not one of those (Skyrim's own click does it).
	 */
	static boolean click(SkyLink.SkyItem item) {
		EquipmentSlot slot = slotOf(item);
		LocalPlayer player = Minecraft.getInstance().player;
		if (slot == null || player == null || !canSend()) {
			return false;
		}
		int index = SkyArmor.inventoryIndex(slot);
		SkyShortcuts.Ref there = SkyShortcuts.ref(player.getInventory().getItem(index));
		if (item.has(Proto.ITEM_WORN)) {
			if (there != null && item.is(there.plugin(), there.localId())) {
				ClientPlayNetworking.send(new SkyArmor.Clear(index));  // out of the slot: it comes off (tick)
			} else {
				SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_UNEQUIP_RIGHT);
			}
		} else {
			wear(item, slot);
		}
		return true;
	}

	/** A Skyrim thing dragged onto Minecraft's inventory slot index (36-39: armor). True if it went on. */
	static boolean dropOn(SkyLink.SkyItem item, int index) {
		EquipmentSlot slot = slotOf(item);
		if (slot == null || SkyArmor.inventoryIndex(slot) != index || !canSend()) {
			return false;
		}
		wear(item, slot);
		return true;
	}

	private static SkyLink.@Nullable SkyItem find(SkyLink.SkyInventory inventory, int formId) {
		for (SkyLink.SkyItem item : inventory.items()) {
			if (item.formId() == formId) {
				return item;
			}
		}
		return null;
	}

	/** Each tick: the armor slots and Skyrim's worn apparel kept together; the gauntlets' armor sent. */
	static void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		SkyLink.SkyInventory inventory = SkyShortcutsClient.inventory();
		if (player == null || inventory == null || !SkyLink.active() || !enabled() || !canSend()) {
			return;
		}
		long now = System.nanoTime();
		var items = player.getInventory();
		// What's in the slots: something new put on, something gone taken off; Skyrim took it off: out.
		for (int i = 0; i < 4; i++) {
			int index = 36 + i;
			ItemStack stack = items.getItem(index);
			SkyShortcuts.Ref ref = SkyShortcuts.ref(stack);
			String key = ref != null ? ref.plugin() + ":" + ref.localId() : stack.isEmpty() ? "" : "minecraft";
			Track track = TRACK[i];
			if (!key.equals(track.key)) {
				if (track.formId != 0) {
					SkyLink.SkyItem old = find(inventory, track.formId);
					if (old != null && old.has(Proto.ITEM_WORN)) {
						SkyLink.pushEvent(Proto.EV_SKY_ITEM, old.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_UNEQUIP_RIGHT);
					}
				}
				track.key = key;
				track.since = now;
				track.formId = 0;
				SkyLink.SkyItem item = ref == null ? null : SkyShortcutsClient.resolve(stack);
				if (item != null && item.kind() == Proto.SKY_APPAREL) {
					track.formId = item.formId();
					if (!item.has(Proto.ITEM_WORN)) {
						SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_EQUIP_RIGHT);
					}
				}
			} else if (track.formId != 0 && now - track.since > GRACE) {
				SkyLink.SkyItem item = find(inventory, track.formId);
				if (item == null || !item.has(Proto.ITEM_WORN)) {
					ClientPlayNetworking.send(new SkyArmor.Clear(index));  // taken off (or gone) in Skyrim
					track.since = now;
				}
			}
		}
		// Put on in Skyrim: into its slot (over a Minecraft piece only when just put on).
		Set<Integer> wornNow = new HashSet<>();
		float hands = 0.0F, handsTough = 0.0F;
		for (SkyLink.SkyItem item : inventory.items()) {
			if (item.kind() != Proto.SKY_APPAREL || !item.has(Proto.ITEM_WORN)) {
				continue;
			}
			wornNow.add(item.formId());
			if (item.sub() == Proto.A_HANDS) {
				hands += (float) SkyArmor.points(item.sub(), item.stat());
				handsTough += (float) SkyArmor.toughness(item.sub(), item.stat(), item.flags());
			}
			EquipmentSlot slot = SkyArmor.slotFor(item.sub());
			if (slot == null) {
				continue;
			}
			int index = SkyArmor.inventoryIndex(slot);
			if (TRACK[index - 36].formId == item.formId()) {
				continue;
			}
			boolean fresh = !wornBefore.contains(item.formId());
			Long sent = WEAR_SENT.get(item.formId());
			if ((fresh || items.getItem(index).isEmpty()) && (sent == null || now - sent > RESEND)) {
				wear(item, slot);
			}
		}
		wornBefore = wornNow;
		if (hands != sentHands || handsTough != sentHandsTough) {
			sentHands = hands;
			sentHandsTough = handsTough;
			ClientPlayNetworking.send(new SkyArmor.HandsArmor(hands, handsTough));
		}
	}
}
