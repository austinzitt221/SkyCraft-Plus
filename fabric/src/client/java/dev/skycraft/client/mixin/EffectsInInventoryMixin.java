// SkyCraft+ : Skyrim's inventory in Minecraft's
package dev.skycraft.client.mixin;

import com.google.common.collect.Ordering;
import dev.skycraft.client.SkyInventoryPanels;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's status effects sit to the right of the inventory, where the Skyrim items panel is:
 * with the panel there they move past it, or (no room left) to the top right corner of the screen
 * as a row of icons.
 */
@Mixin(EffectsInInventory.class)
public abstract class EffectsInInventoryMixin {
	private static final Identifier SKYCRAFT$BACKGROUND = Identifier.withDefaultNamespace("container/inventory/effect_background");
	private static final Identifier SKYCRAFT$BACKGROUND_AMBIENT = Identifier.withDefaultNamespace("container/inventory/effect_background_ambient");

	@Shadow
	@Final
	private AbstractContainerScreen<?> screen;

	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private void extractEffects(GuiGraphicsExtractor graphics, Collection<MobEffectInstance> activeEffects, int x0, int yStep, int mouseX, int mouseY, int maxWidth) {
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void skycraft$pastTheSkyrimPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		int shift = SkyInventoryPanels.effectsShift(this.screen);
		if (shift == 0) {
			return;
		}
		ci.cancel();
		Collection<MobEffectInstance> effects = this.minecraft.player.getActiveEffects();
		if (effects.isEmpty()) {
			return;
		}
		var accessor = (ContainerScreenAccessor) this.screen;
		int xo = accessor.skycraft$leftPos() + accessor.skycraft$imageWidth() + 2 + shift;
		int available = this.screen.width - xo;
		if (available >= 32) {
			int maxWidth = available >= 120 ? available - 7 : 32;
			int yStep = effects.size() > 5 ? 132 / (effects.size() - 1) : 33;
			this.extractEffects(graphics, effects, xo, yStep, mouseX, mouseY, maxWidth);
			return;
		}
		// No room: a row of icons along the top of the screen, from its right edge.
		int x = this.screen.width - 2 - 32, y = 2;
		for (MobEffectInstance effect : Ordering.natural().sortedCopy(effects)) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, effect.isAmbient() ? SKYCRAFT$BACKGROUND_AMBIENT : SKYCRAFT$BACKGROUND, x, y, 32, 32);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect.getEffect()), x + 7, y + 7, 18, 18);
			if (mouseX >= x && mouseX < x + 32 && mouseY >= y && mouseY < y + 32) {
				MutableComponent name = effect.getEffect().value().getDisplayName().copy();
				if (effect.getAmplifier() >= 1 && effect.getAmplifier() <= 9) {
					name.append(CommonComponents.SPACE).append(Component.translatable("enchantment.level." + (effect.getAmplifier() + 1)));
				}
				Component duration = MobEffectUtil.formatDuration(effect, 1.0F, this.minecraft.level.tickRateManager().tickrate());
				graphics.setTooltipForNextFrame(this.screen.getFont(), List.of(name, duration), Optional.empty(), mouseX, mouseY);
			}
			x -= 33;
			if (x < 2) {
				break;
			}
		}
	}
}
