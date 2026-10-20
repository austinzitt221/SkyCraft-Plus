// SkyCraft+ : arrows across the games
package dev.skycraft.client;

import dev.skycraft.combat.SkyArrows;
import dev.skycraft.craft.SkyMaterials;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyShortcuts;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of SkyArrows: which of this player's Skyrim arrows a Minecraft bow shoots (the off
 * hand's shortcut, else the ones Skyrim has readied, else the most it has), told to the server and
 * used up in Skyrim as they're shot; and Minecraft arrows in the off hand lent to a Skyrim bow as iron
 * arrows (readied), as many as there are, given back when the bow or the arrows are put away. A Skyrim
 * arrow's shortcut in the off hand with a Skyrim bow readies those.
 */
public final class SkyArrowsClient {
	private SkyArrowsClient() {
	}

	private static SkyArrows.Ammo sent = SkyArrows.Ammo.NONE;
	private static int sentAt;
	private static int ticks;
	// Minecraft arrows lent to Skyrim's bow: how many Skyrim holds for them, the off hand's count last tick, the readying.
	private static boolean lending;
	private static int lent, readyTries, readyAt;
	private static SkyLink.@Nullable SkyItem standIn;
	private static String readied = "";

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SkyArrows.Fired.TYPE, (payload, context) -> {
			SkyLink.SkyItem item = find(payload.plugin(), payload.localId());
			if (item != null) {
				SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_CONSUME);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(SkyArrows.Shot.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> {
			// Skyrim used those up itself (it shot them): lent fewer now, as the off hand has fewer.
			lent = Math.max(0, lent - payload.count());
		}));
	}

	private static SkyLink.@Nullable SkyItem find(String plugin, int localId) {
		SkyLink.SkyInventory inventory = SkyShortcutsClient.inventory();
		if (inventory == null) {
			inventory = SkyLink.readSkyInventory();
		}
		if (inventory != null) {
			for (SkyLink.SkyItem item : inventory.items()) {
				if (item.is(plugin, localId)) {
					return item;
				}
			}
		}
		return null;
	}

	/** Skyrim's list is kept current while a bow is in play (SkyShortcutsClient.wanted). */
	static boolean wantsInventory() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !SkyArrows.enabled()) {
			return false;
		}
		return lending || player.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem
			|| player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem || skyrimBow();
	}

	private static boolean skyrimBow() {
		SkyLink.SkyState sky = SkyClient.sky();
		return sky.rightHandKind == Proto.HAND_BOW || sky.leftHandKind == Proto.HAND_BOW;
	}

	private static boolean minecraftArrows(ItemStack stack) {
		return stack.is(Items.ARROW) || stack.is(Items.TIPPED_ARROW) || stack.is(Items.SPECTRAL_ARROW);
	}

	static void tick(Minecraft minecraft) {
		ticks++;
		LocalPlayer player = minecraft.player;
		if (player == null || !SkyLink.active() || !SkyArrows.enabled()) {
			SkyArrows.clientAmmo = SkyArrows.Ammo.NONE;
			lending = false;
			return;
		}
		SkyLink.SkyInventory inventory = SkyShortcutsClient.inventory();
		tellAmmo(player, inventory);
		lendToSkyrimBow(player, inventory);
		readyOffHandSkyrimArrows(player);
	}

	/** Which Skyrim arrows a Minecraft bow would shoot: told to the server when it changes. */
	private static void tellAmmo(LocalPlayer player, SkyLink.@Nullable SkyInventory inventory) {
		SkyArrows.Ammo ammo = SkyArrows.Ammo.NONE;
		if (inventory != null) {
			SkyLink.SkyItem forced = SkyShortcuts.isShortcut(player.getOffhandItem()) ? SkyShortcutsClient.resolve(player.getOffhandItem()) : null;
			SkyLink.SkyItem pick = forced != null && forced.kind() == Proto.SKY_AMMO ? forced : null;
			if (pick == null) {
				for (SkyLink.SkyItem item : inventory.items()) {
					if (item.kind() == Proto.SKY_AMMO && item.count() > 0 && !item.plugin().isEmpty()
						&& (pick == null || item.has(Proto.ITEM_WORN) && !pick.has(Proto.ITEM_WORN) || item.has(Proto.ITEM_WORN) == pick.has(Proto.ITEM_WORN) && item.count() > pick.count())) {
						pick = item;
					}
				}
			}
			if (pick != null && !(lending && standIn != null && pick.formId() == standIn.formId())) {
				ammo = new SkyArrows.Ammo(pick.plugin(), pick.localId(), pick.name(), pick.stat(), pick.count(), pick == forced);
			}
		}
		SkyArrows.clientAmmo = ammo;
		if (!ammo.equals(sent) && ticks - sentAt >= 4 && ClientPlayNetworking.canSend(SkyArrows.Ammo.TYPE)) {
			ClientPlayNetworking.send(ammo);
			sent = ammo;
			sentAt = ticks;
		}
	}

	/** Minecraft arrows in the off hand with a Skyrim bow: Skyrim holds as many iron arrows for them, readied. */
	private static void lendToSkyrimBow(LocalPlayer player, SkyLink.@Nullable SkyInventory inventory) {
		ItemStack off = player.getOffhandItem();
		if (!skyrimBow() || !minecraftArrows(off)) {
			if (lending) {
				giveBack();
			}
			return;
		}
		if (standIn == null) {
			standIn = SkyMaterials.skyrimItem(Items.ARROW);
			if (standIn == null) {
				return;  // (no catalog yet)
			}
		}
		int now = Math.min(off.getCount(), 64);
		if (!lending) {
			lending = true;
			lent = 0;
			readyTries = 0;
			readyAt = ticks + 10;
		}
		// As many as the off hand holds (those shot are gone from both: Shot); more put there, or fewer, follow.
		if (now > lent) {
			give(now - lent);
		} else if (now < lent) {
			take(lent - now);
		}
		// Readied, so the bow shoots them (once they're in Skyrim's inventory).
		if (readyTries < 5 && ticks >= readyAt && inventory != null) {
			SkyLink.SkyItem mine = null;
			for (SkyLink.SkyItem item : inventory.items()) {
				if (item.formId() == standIn.formId()) {
					mine = item;
				}
			}
			if (mine != null && !mine.has(Proto.ITEM_WORN)) {
				SkyLink.pushEvent(Proto.EV_SKY_ITEM, mine.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_RIGHT);
			}
			if (mine != null && mine.has(Proto.ITEM_WORN)) {
				readyTries = 5;
			} else {
				readyTries++;
				readyAt = ticks + 20;
			}
		}
	}

	private static void give(int count) {
		if (standIn != null && count > 0 && SkyLink.pushStoreRequest(Proto.STORE_GIVE, 0, count, 1.0F, -1.0F, 0, standIn.localId(), standIn.plugin()) != 0) {
			lent += count;
		}
	}

	private static void take(int count) {
		if (standIn != null && count > 0 && SkyLink.pushStoreRequest(Proto.STORE_TAKE, standIn.formId(), count, 1.0F, -1.0F, 0, standIn.localId(), standIn.plugin()) != 0) {
			lent -= count;
		}
	}

	private static void giveBack() {
		take(lent);
		lending = false;
		lent = 0;
	}

	/** A Skyrim arrow's shortcut put in the off hand with a Skyrim bow: those arrows readied in Skyrim. */
	private static void readyOffHandSkyrimArrows(LocalPlayer player) {
		ItemStack off = player.getOffhandItem();
		SkyShortcuts.Ref ref = SkyShortcuts.ref(off);
		String key = ref == null || ref.kind() != Proto.SKY_AMMO || !skyrimBow() ? "" : ref.plugin() + ":" + ref.localId();
		if (key.equals(readied)) {
			return;
		}
		readied = key;
		SkyLink.SkyItem item = key.isEmpty() ? null : SkyShortcutsClient.resolve(off);
		if (item != null && !item.has(Proto.ITEM_WORN)) {
			SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_RIGHT);
		}
	}
}
