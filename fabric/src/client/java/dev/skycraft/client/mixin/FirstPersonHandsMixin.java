// SkyCraft+ : hand ownership
package dev.skycraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.skycraft.client.SkyHands;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hand Skyrim holds is drawn by Skyrim (its own first-person arm), not here. SkyCraft+ : and none
 * while Skyrim drives the player (riding, furniture, scenes): Skyrim's camera and body are on screen.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimHand(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state, float frameInterp, float xRot,
		InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
		int lightCoords, CallbackInfo ci) {
		if (SkyHands.skyrimOwns(hand) || dev.skycraft.client.SkyClient.sky().skyrimDrives()) {
			ci.cancel();
		}
	}
}
