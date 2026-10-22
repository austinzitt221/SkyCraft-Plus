// SkyCraft+ : every shout in Minecraft mode
package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's own white outline round glowing mobs (Aura Whisper's Glowing) isn't drawn while
 * Skyrim draws the world: Skyrim shows them glowing its own way (AvatarExporter), and Minecraft's
 * outline was left stuck on the screen where a villager had been.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftGlowOutlineMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void skycraft$noOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.linked()) {
			cir.setReturnValue(false);
		}
	}
}
