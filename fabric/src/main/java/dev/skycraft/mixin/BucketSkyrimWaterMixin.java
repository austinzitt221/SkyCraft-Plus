// SkyCraft+ : buckets of Skyrim water
package dev.skycraft.mixin;

import dev.skycraft.world.SkyWater;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An empty bucket fills from Skyrim's water (lakes, rivers, the sea), as from Minecraft's: aimed at
 * its surface within reach, before any Minecraft block. Skyrim's water is a source that never runs
 * dry: filling a bucket leaves it as it is.
 */
@Mixin(BucketItem.class)
public abstract class BucketSkyrimWaterMixin {
	@Shadow @Final private Fluid content;

	@Inject(method = "use", at = @At("HEAD"), cancellable = true)
	private void skycraft$fillFromSkyrimWater(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (this.content != Fluids.EMPTY || !SkyWater.active()) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double reach = player.blockInteractionRange();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (double t = 0.0; t <= reach; t += 0.1) {
			Vec3 p = eye.add(look.scale(t));
			at.set(p.x, p.y, p.z);
			var state = level.getBlockState(at);
			if (!state.getCollisionShape(level, at).isEmpty() || !state.getFluidState().isEmpty()) {
				return;  // a Minecraft block (or Minecraft's own water) first: as ever
			}
			double surface = SkyWater.surfaceAt(at.getX(), at.getZ());
			if (!Double.isNaN(surface) && p.y <= surface && p.y > surface - 1.5) {
				ItemStack bucket = player.getItemInHand(hand);
				player.playSound(SoundEvents.BUCKET_FILL, 1.0F, 1.0F);
				level.playSound(player, at, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 1.0F, 1.0F);
				cir.setReturnValue(InteractionResult.SUCCESS.heldItemTransformedTo(ItemUtils.createFilledResult(bucket, player, new ItemStack(Items.WATER_BUCKET))));
				return;
			}
		}
	}
}
