// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.mixin;

import dev.skycraft.net.SkyShortcuts;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Skyrim shortcut doesn't go in a bundle (a Skyrim item put in one is stored instead: SkyStorage). */
@Mixin(BundleContents.class)
public abstract class BundleShortcutMixin {
	@Inject(method = "canItemBeInBundle", at = @At("HEAD"), cancellable = true)
	private static void skycraft$noShortcuts(ItemStack itemToAdd, CallbackInfoReturnable<Boolean> cir) {
		if (SkyShortcuts.isShortcut(itemToAdd)) {
			cir.setReturnValue(false);
		}
	}
}
