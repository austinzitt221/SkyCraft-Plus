// SkyCraft+ : Minecraft plants on Skyrim's ground
package dev.skycraft.mixin;

import dev.skycraft.world.SkyPatches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Leaf litter lies on Skyrim's ground too, with no Minecraft block under it (SkyPatches.onSkyrimGround). */
@Mixin(LeafLitterBlock.class)
public abstract class LeafLitterBlockMixin {
	@Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
	private void skycraft$onSkyrimGround(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (SkyPatches.onSkyrimGround(level, pos)) {
			cir.setReturnValue(true);
		}
	}
}
