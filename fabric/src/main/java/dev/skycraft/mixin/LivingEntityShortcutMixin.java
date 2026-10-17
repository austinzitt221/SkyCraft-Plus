// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.mixin;

import dev.skycraft.net.SkyShortcuts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Skyrim shortcut never becomes an item in the world. Thrown out (Q, or out of an inventory
 * screen), its player's Skyrim drops the real thing; otherwise (death, a full inventory) it's gone.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityShortcutMixin {
	@Inject(method = "createItemStackToDrop", at = @At("HEAD"), cancellable = true)
	private void skycraft$shortcutsNeverDrop(ItemStack stack, boolean randomly, boolean thrownFromHand, CallbackInfoReturnable<ItemEntity> cir) {
		if (!SkyShortcuts.isShortcut(stack)) {
			return;
		}
		if (thrownFromHand && (Object) this instanceof ServerPlayer player) {
			SkyShortcuts.thrownOut(player, stack);
		}
		cir.setReturnValue(null);
	}
}
