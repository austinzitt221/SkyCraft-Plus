// SkyCraft+ : arrows across the games
package dev.skycraft.mixin;

import dev.skycraft.combat.SkyArrows;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Minecraft bow's arrows: a Skyrim arrow's shortcut in the off hand picks Skyrim's; with no Minecraft arrows, Skyrim's (SkyArrows). */
@Mixin(Player.class)
public abstract class PlayerProjectileMixin {
	@Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
	private void skycraft$offHandSkyrimArrows(ItemStack weapon, CallbackInfoReturnable<ItemStack> cir) {
		if (weapon.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) {
			ItemStack forced = SkyArrows.forced((Player) (Object) this);
			if (forced != null) {
				cir.setReturnValue(forced);
			}
		}
	}

	@Inject(method = "getProjectile", at = @At("RETURN"), cancellable = true)
	private void skycraft$skyrimArrows(ItemStack weapon, CallbackInfoReturnable<ItemStack> cir) {
		if (weapon.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) {
			cir.setReturnValue(SkyArrows.orSkyrims((Player) (Object) this, cir.getReturnValue()));
		}
	}
}
