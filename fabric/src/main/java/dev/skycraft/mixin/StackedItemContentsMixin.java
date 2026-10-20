// SkyCraft+ : crafting across the games
package dev.skycraft.mixin;

import dev.skycraft.craft.SkyMaterials;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shapeless recipes count what's in the grid by item: a stored Skyrim material is counted as its Minecraft match (SkyMaterials). */
@Mixin(StackedItemContents.class)
public abstract class StackedItemContentsMixin {
	@Inject(method = "accountStack(Lnet/minecraft/world/item/ItemStack;I)V", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimMaterial(ItemStack stack, int maxCount, CallbackInfo ci) {
		Item match = SkyMaterials.minecraftOf(stack);
		if (match != null) {
			ci.cancel();
			((StackedItemContents) (Object) this).accountStack(new ItemStack(match, stack.getCount()), maxCount);
		}
	}
}
