// SkyCraft+ : arrows across the games
package dev.skycraft.mixin;

import dev.skycraft.combat.SkyArrows;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Skyrim arrow drawn by a Minecraft bow: Skyrim uses one up, and the arrow made is Skyrim's (SkyArrows). */
@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponItemMixin {
	@Inject(method = "useAmmo", at = @At("RETURN"))
	private static void skycraft$skyrimArrowUsed(ItemStack weapon, ItemStack projectile, LivingEntity holder, boolean forceInfinite, CallbackInfoReturnable<ItemStack> cir) {
		if (!holder.level().isClientSide()) {
			SkyArrows.used(holder, cir.getReturnValue());
		}
	}

	@Inject(method = "createProjectile", at = @At("RETURN"))
	private void skycraft$skyrimArrowMade(Level level, LivingEntity shooter, ItemStack weapon, ItemStack projectile, boolean isCrit, CallbackInfoReturnable<Projectile> cir) {
		if (!level.isClientSide()) {
			SkyArrows.made(cir.getReturnValue(), projectile);
		}
	}
}
