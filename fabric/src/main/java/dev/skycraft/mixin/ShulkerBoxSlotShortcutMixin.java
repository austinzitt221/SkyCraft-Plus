// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.mixin;

import dev.skycraft.net.SkyShortcuts;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shulker boxes' slots have their own check (SlotShortcutMixin doesn't reach them): no shortcuts there either. */
@Mixin(ShulkerBoxSlot.class)
public abstract class ShulkerBoxSlotShortcutMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void skycraft$noShortcuts(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (SkyShortcuts.isShortcut(stack)) {
			cir.setReturnValue(false);
		}
	}
}
