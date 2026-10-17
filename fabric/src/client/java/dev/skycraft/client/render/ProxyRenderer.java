// SkyCraft+ : leads on Skyrim actors
package dev.skycraft.client.render;

import dev.skycraft.combat.SkyrimActorEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;

/**
 * A Skyrim actor's stand-in draws nothing of its own (Skyrim draws the actor): only a lead on it,
 * never its name (Minecraft showed it when looked at, as a named mob's).
 */
public final class ProxyRenderer extends NoopRenderer<SkyrimActorEntity> {
	public ProxyRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected boolean shouldShowName(SkyrimActorEntity entity, double distanceToCameraSq) {
		return false;
	}
}
