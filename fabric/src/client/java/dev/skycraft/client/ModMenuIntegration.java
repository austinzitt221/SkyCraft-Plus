// SkyCraft+ : Mod Menu's config screen
package dev.skycraft.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Mod Menu's "Config" button, for the two experimental rendering switches (see RenderToggles, which
 * the pause menu's buttons and config/skycraft.properties write to as well). Only Mod Menu calls
 * this class; the screen itself needs Cloth Config, so without it the button does nothing.
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
			.setSavingRunnable(RenderToggles::save);
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
				.build());
		return builder.build();
	}
}
