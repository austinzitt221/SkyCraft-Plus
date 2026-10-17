// SkyCraft+ : the Ender Dragon gives a dragon soul.
package dev.skycraft.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

/**
 * The Ender Dragon dies like one of Skyrim's dragons: every player in its fight (those who see its
 * health bar) absorbs its soul in their own Skyrim.
 */
@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightMixin {
	@Shadow
	private @Nullable UUID dragonUUID;

	@Shadow
	private ServerBossEvent dragonEvent;

	@Shadow
	private ServerLevel level;

	@Inject(method = "setDragonKilled", at = @At("HEAD"))
	private void skycraft$dragonSoul(EnderDragon dragon, CallbackInfo ci) {
		if (!dragon.getUUID().equals(this.dragonUUID)) {
			return;
		}
		List<ServerPlayer> players = new ArrayList<>(this.dragonEvent.getPlayers());
		if (players.isEmpty()) {
			players.addAll(this.level.players());
		}
		for (ServerPlayer player : players) {
			dev.skycraft.net.SkyNet.giveDragonSoul(player);
		}
	}
}
