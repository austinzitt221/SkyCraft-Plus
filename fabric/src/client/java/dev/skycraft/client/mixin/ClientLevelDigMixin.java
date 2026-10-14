package dev.skycraft.client.mixin;

import dev.skycraft.client.RenderToggles;
import dev.skycraft.client.SkyDigClient;
import dev.skycraft.client.render.WorldExporter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every block the client changes: the dug-block reveal (SkyDigClient), and the section mesh. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelDigMixin {
	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void skycraft$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		SkyDigClient.blockChanged((ClientLevel) (Object) this, pos, oldState, newState);
		// SkyCraft's mesh is built from a whole section's blocks and sent per section - mesh, lights,
		// collision and the dug cells - so any block change has to rebuild it. Minecraft decides for
		// itself whether a change is worth re-drawing (ModelManager.requiresRender, and a non-player
		// flag that only queues the section behind the thousands a world load leaves): a placed
		// redstone or a toggled lever was left out until some other block moved nearby. Every client
		// block change rebuilds its section here, and its neighbours too (connected models, lights,
		// our own culling), ahead of everything else. RenderToggles.remeshOnChange switches it off.
		if (!RenderToggles.remeshOnChange) {
			return;
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					WorldExporter.markDirtyNow((pos.getX() + dx) >> 4, (pos.getY() + dy) >> 4, (pos.getZ() + dz) >> 4);
				}
			}
		}
	}
}
