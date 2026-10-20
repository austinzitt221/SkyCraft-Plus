// SkyCraft+ : crafting across the games
package dev.skycraft.client;

import dev.skycraft.craft.SkyConversionTable;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/** The client's side of crafting across the games: the conversion table's look and screen. */
public final class SkyCraftingClient {
	private SkyCraftingClient() {
	}

	public static void register() {
		BlockColorRegistry.register(List.of(BlockTintSources.constant(SkyConversionTable.TINT)), SkyConversionTable.BLOCK);
		MenuScreens.register(SkyConversionTable.MENU, ConversionScreen::new);
	}

	/** Laid out as a grindstone's: Minecraft's material on top, Skyrim's below, the other game's on the right. */
	static final class ConversionScreen extends AbstractContainerScreen<SkyConversionTable.Menu> {
		private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/grindstone.png");
		private static final Identifier ERROR_SPRITE = Identifier.withDefaultNamespace("container/grindstone/error");

		ConversionScreen(SkyConversionTable.Menu menu, Inventory inventory, Component title) {
			super(menu, inventory, title);
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			super.extractBackground(graphics, mouseX, mouseY, a);
			int xo = (this.width - this.imageWidth) / 2;
			int yo = (this.height - this.imageHeight) / 2;
			graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, xo, yo, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
			if ((this.menu.getSlot(SkyConversionTable.Menu.MINECRAFT_SLOT).hasItem() || this.menu.getSlot(SkyConversionTable.Menu.SKYRIM_SLOT).hasItem())
				&& !this.menu.getSlot(SkyConversionTable.Menu.RESULT_SLOT).hasItem()) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ERROR_SPRITE, xo + 92, yo + 31, 28, 21);
			}
		}
	}
}
