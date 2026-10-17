// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.mixin;

import dev.skycraft.net.SkyShortcuts;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Skyrim shortcut stays in its player's own inventory: chests, crafting grids and the rest refuse it. */
@Mixin(Slot.class)
public abstract class SlotShortcutMixin {
	@Shadow
	@Final
	public Container container;

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void skycraft$shortcutsStayHome(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (!(this.container instanceof Inventory) && SkyShortcuts.isShortcut(stack)) {
			cir.setReturnValue(false);
		}
	}
}
