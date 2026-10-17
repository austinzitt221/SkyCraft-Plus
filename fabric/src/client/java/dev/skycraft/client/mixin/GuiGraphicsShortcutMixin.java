// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyShortcutsClient;
import dev.skycraft.net.SkyShortcuts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Skyrim shortcut shows how many Skyrim has, and greys out when Skyrim has none left. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsShortcutMixin {
	/** Skyrim's picture of a shortcut's thing, once Skyrim has drawn it, instead of its stand-in. */
	@Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimPicture(@Nullable LivingEntity owner, @Nullable Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		if (SkyShortcuts.isShortcut(stack) && SkyShortcutsClient.drawPicture((GuiGraphicsExtractor) (Object) this, stack, x, y)
			|| dev.skycraft.net.SkyStorage.isStored(stack) && dev.skycraft.client.SkyStorageClient.drawPicture((GuiGraphicsExtractor) (Object) this, stack, x, y)) {
			ci.cancel();
		}
	}

	@ModifyVariable(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
	private @Nullable String skycraft$skyrimCount(@Nullable String countText, Font font, ItemStack stack, int x, int y, @Nullable String original) {
		return countText == null && SkyShortcuts.isShortcut(stack) ? SkyShortcutsClient.countText(stack) : countText;
	}

	@Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
	private void skycraft$greyWhenGone(Font font, ItemStack stack, int x, int y, @Nullable String countText, CallbackInfo ci) {
		if (SkyShortcutsClient.gone(stack)) {
			((GuiGraphicsExtractor) (Object) this).fill(x, y, x + 16, y + 16, 0xA0303030);
		}
	}
}
