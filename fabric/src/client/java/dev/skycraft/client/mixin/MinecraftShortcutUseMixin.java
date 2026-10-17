// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyShortcutsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right click with a Skyrim shortcut drinks, eats or puts on its Skyrim thing (SkyShortcutsClient.use). */
@Mixin(Minecraft.class)
public abstract class MinecraftShortcutUseMixin {
	@Shadow
	public @Nullable LocalPlayer player;

	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void skycraft$useShortcut(CallbackInfo ci) {
		if (this.player != null && SkyShortcutsClient.use(this.player)) {
			this.rightClickDelay = 4;
			ci.cancel();
		}
	}
}
