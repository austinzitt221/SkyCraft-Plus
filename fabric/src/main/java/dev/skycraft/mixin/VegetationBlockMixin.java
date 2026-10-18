// SkyCraft+ : Minecraft plants on Skyrim's ground
package dev.skycraft.mixin;

import dev.skycraft.world.SkyPatches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A plant that grows on grass (short and tall grass, ferns, flowers, bushes) grows on Skyrim's
 * grass, dirt and snow too, with no Minecraft block under it (SkyPatches.onSkyrimGround). Not
 * saplings: Minecraft's trees need a dirt block to grow from.
 */
@Mixin(VegetationBlock.class)
public abstract class VegetationBlockMixin {
	@Shadow
	protected abstract boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos);

	@Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
	private void skycraft$onSkyrimGround(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof SaplingBlock) && SkyPatches.onSkyrimGround(level, pos) && this.mayPlaceOn(Blocks.GRASS_BLOCK.defaultBlockState(), level, pos.below())) {
			cir.setReturnValue(true);
		}
	}
}
