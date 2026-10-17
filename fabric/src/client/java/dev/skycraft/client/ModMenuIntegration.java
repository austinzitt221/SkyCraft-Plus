// SkyCraft+ : Mod Menu's config screen
package dev.skycraft.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.skycraft.world.SkyDig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Mod Menu's "Config" button: every SkyCraft setting, the same values the pause menu's buttons and
 * config/skycraft.properties hold (RenderToggles, DestructionToggle, HandMixToggle). Only Mod Menu
 * calls this class; the screen itself needs Cloth Config, so without it the button does nothing.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return new ConfigScreenFactory<Screen>() {
			@Override
			public Screen create(Screen parent) {
				return FabricLoader.getInstance().isModLoaded("cloth-config") ? config(parent) : null;
			}
		};
	}

	private static Screen config(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.literal("SkyCraft"))
			.setSavingRunnable(() -> {
				RenderToggles.save();
				DestructionToggle.save();
				HandMixToggle.save();
			});
		ConfigEntryBuilder entries = builder.entryBuilder();
		builder.getOrCreateCategory(Component.literal("Rendering"))
			.addEntry(entries.startBooleanToggle(Component.literal("Fabric quads (experimental)"), RenderToggles.fabricQuads)
				.setDefaultValue(true)
				.setTooltip(Component.literal(RenderToggles.QUADS_TIP))
				.setSaveConsumer(on -> RenderToggles.fabricQuads = on)
				.build())
			.addEntry(entries.startBooleanToggle(Component.literal("Item models (experimental)"), RenderToggles.itemModels)
				.setDefaultValue(false)
				.setTooltip(Component.literal(RenderToggles.ITEMS_TIP))
				.setSaveConsumer(on -> RenderToggles.itemModels = on)
				.build())
			.addEntry(entries.startBooleanToggle(Component.literal("Re-mesh on every block change"), RenderToggles.remeshOnChange)
				.setDefaultValue(true)
				.setTooltip(Component.literal(RenderToggles.REMESH_TIP))
				.setSaveConsumer(on -> RenderToggles.remeshOnChange = on)
				.build());
		String[] hands = HandMixToggle.MODE_NAMES;
		builder.getOrCreateCategory(Component.literal("Gameplay"))
			.addEntry(entries.startBooleanToggle(Component.literal("Skyrim destruction"), SkyDig.destruction)
				.setDefaultValue(true)
				.setTooltip(Component.literal(DestructionToggle.TIP))
				.setSaveConsumer(on -> SkyDig.destruction = on)
				.build())
			.addEntry(entries.startSelector(Component.literal("Hands"), hands, hands[Math.clamp(HandMixToggle.mode, 0, hands.length - 1)])
				.setDefaultValue(hands[0])
				.setTooltip(Component.literal(HandMixToggle.TIP))
				.setSaveConsumer(name -> HandMixToggle.mode = java.util.Arrays.asList(hands).indexOf(name))
				.build());
		return builder.build();
	}
}
