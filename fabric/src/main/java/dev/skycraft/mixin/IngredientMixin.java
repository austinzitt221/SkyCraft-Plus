// SkyCraft+ : crafting across the games
package dev.skycraft.mixin;

import dev.skycraft.craft.SkyMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A stored Skyrim material (Skyrim's iron ingot in a crafting grid or a furnace) counts as its Minecraft match (SkyMaterials). */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {
	@Inject(method = "test(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimMaterial(ItemStack input, CallbackInfoReturnable<Boolean> cir) {
		Item match = SkyMaterials.minecraftOf(input);
		if (match != null) {
			cir.setReturnValue(((Ingredient) (Object) this).test(new ItemStack(match)));
		}
	}
}
