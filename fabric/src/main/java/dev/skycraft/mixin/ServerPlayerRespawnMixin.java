// SkyCraft+ : the Nether and the End
package dev.skycraft.mixin;

import dev.skycraft.world.SkyMinecraftWorlds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Back from the End's exit portal or a death away from Skyrim, with no bed: to the portal they left by (SkyMinecraftWorlds). */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnMixin {
	@Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
	private void skycraft$backToThePortal(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition post, CallbackInfoReturnable<TeleportTransition> cir) {
		TeleportTransition instead = SkyMinecraftWorlds.comingBack((ServerPlayer) (Object) this, cir.getReturnValue());
		if (instead != null) {
			cir.setReturnValue(instead);
		} else {
			dev.skycraft.world.SkyBeds.respawning((ServerPlayer) (Object) this, cir.getReturnValue());  // SkyCraft+ : beds
		}
	}
}
