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
		// SkyCraft+ : Skyrim geo. A placed piece's data arrived after its block: its section drawn again with it.
		dev.skycraft.world.SkyGeo.clientChanged = pos -> dev.skycraft.client.render.WorldExporter.markDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
	}

	/** A filled soul gem used with a Skyrim-enchanted Minecraft weapon in hand: its soul goes into the enchantment's charge (Skyrim gives the gem up). */
	static boolean recharge(dev.skycraft.link.SkyLink.SkyItem item) {
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || item.kind() != dev.skycraft.link.Proto.SKY_MISC || item.sub() != 1 || !item.has(dev.skycraft.link.Proto.ITEM_FILLED)) {
			return false;
		}
		var weapon = player.getMainHandItem();
		var enchant = dev.skycraft.craft.SkyEnchanting.enchant(weapon);
		float charge = dev.skycraft.craft.SkyEnchanting.soulCharge(Math.round(item.stat()));
		if (enchant == null || enchant.max() <= 0.0F || charge <= 0.0F) {
			return false;
		}
		if (enchant.charge() >= enchant.max()) {
			player.sendOverlayMessage(Component.literal(weapon.getHoverName().getString() + " is fully charged"));
			return true;
		}
		dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, dev.skycraft.link.Proto.SKY_ACT_CONSUME);
		SkyClient.act(minecraft, dev.skycraft.net.SkyNet.Act.simple(dev.skycraft.net.SkyNet.Act.RECHARGE, 0, charge, 0.0F));
		return true;
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
