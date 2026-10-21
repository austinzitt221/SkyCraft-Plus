// SkyCraft+ : every Skyrim thing in the creative menu
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.mixin.CreativeModeTabsAccessor;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyCreative;
import dev.skycraft.net.SkyStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Makes the creative menu's Skyrim tabs from Skyrim's list of everything (SkyCreative), when Skyrim sends it. */
public final class SkyCreativeClient {
	private SkyCreativeClient() {
	}

	private static int madeFrom = 0;

	/** Each client tick: Skyrim's list, once it's there (and again if it changes). */
	public static void tick(Minecraft minecraft) {
		int generation = SkyLink.skyCatalogGeneration();
		if (generation <= 0 || generation == madeFrom) {
			return;
		}
		List<SkyLink.SkyItem> items = SkyLink.readSkyCatalog();
		if (items == null) {
			return;
		}
		madeFrom = generation;
		List<SkyLink.SkyItem> sorted = new ArrayList<>(items);
		// People before creatures; each tab's things by kind of thing, then name.
		sorted.sort(Comparator.comparingInt(SkyLink.SkyItem::kind).thenComparingInt(SkyLink.SkyItem::sub).thenComparingInt(SkyLink.SkyItem::material)
			.thenComparing(SkyLink.SkyItem::name, String.CASE_INSENSITIVE_ORDER));
		Map<SkyCreative.Tab, List<ItemStack>> byTab = new EnumMap<>(SkyCreative.Tab.class);
		byTab.computeIfAbsent(SkyCreative.Tab.MISC, t -> new ArrayList<>()).add(SkyCreative.dragonSoul());
		for (SkyLink.SkyItem item : sorted) {
			SkyCreative.Tab tab = SkyCreative.Tab.of(item.kind());
			if (item.kind() == Proto.SKY_GEO) {
				// SkyCraft+ : Skyrim blocks. Each texture Skyrim's land is painted with: a block of it.
				var piece = dev.skycraft.world.SkyGeo.flat(item.material(), item.name());
				byTab.computeIfAbsent(tab, t -> new ArrayList<>()).add(dev.skycraft.world.SkyGeo.stack(piece, false, 1));
				continue;
			}
			if (tab == null || item.plugin().isEmpty()) {
				continue;
			}
			byTab.computeIfAbsent(tab, t -> new ArrayList<>()).add(item.kind() == Proto.SKY_ACTOR ? SkyCreative.egg(item) : stored(item));
		}
		SkyCreative.setContents(byTab);
		CreativeModeTabsAccessor.skycraft$setCachedParameters(null);  // built again when the menu next opens
		SkyCraft.LOG.info("SkyCraft: the creative menu's Skyrim tabs: {} things", items.size());
	}

	/** A Skyrim thing as the stored Skyrim item that brings it into Skyrim, with its stand-in look. */
	private static ItemStack stored(SkyLink.SkyItem item) {
		ItemStack icon = SkyIcons.stack(item);
		var contents = icon.get(DataComponents.POTION_CONTENTS);
		int color = contents != null ? contents.customColor().orElse(0) : 0;
		boolean glint = Boolean.TRUE.equals(icon.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
		String name = item.name().length() > 100 ? item.name().substring(0, 100) : item.name();
		return SkyStorage.make(new SkyStorage.Stored(item.plugin(), item.localId(), item.kind(), item.sub(), item.material(), item.flags(), 1.0F, -1.0F, 0, item.weight(),
			item.value(), item.stat(), item.stat2(), name, BuiltInRegistries.ITEM.getKey(icon.getItem()).toString(), color, glint), 1);
	}
}
