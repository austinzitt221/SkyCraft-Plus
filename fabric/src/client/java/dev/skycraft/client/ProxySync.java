package dev.skycraft.client;

import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Puts the client's copies of the Skyrim actor stand-ins exactly where Skyrim has the actors this
 * frame, so the crosshair and melee reach line up with what's on screen (the server copy only
 * moves once per tick and reaches the client a tick or two later).
 */
final class ProxySync {
	private static final List<SkyLink.Actor> ACTORS = new ArrayList<>();
	private static final Map<Integer, SkyLink.Actor> BY_ID = new HashMap<>();

	private ProxySync() {
	}

	static void frame(Minecraft minecraft) {
		if (minecraft.level == null || !SkyLink.readActors(ACTORS)) {
			return;
		}
		BY_ID.clear();
		for (SkyLink.Actor a : ACTORS) {
			BY_ID.put(a.formId(), a);
		}
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (entity instanceof SkyrimActorEntity proxy) {
				SkyLink.Actor a = BY_ID.get(proxy.formId());
				if (a == null) {
					continue;
				}
				proxy.setSize(a.width(), a.height());
				proxy.setPos(a.x(), a.y(), a.z());
				proxy.xo = a.x();
				proxy.yo = a.y();
				proxy.zo = a.z();
				proxy.setYRot(a.yaw());
				proxy.yRotO = a.yaw();
				logLead(proxy);
			}
		}
	}

	// SkyCraft+ : leads on Skyrim actors. When this client's copy of a stand-in gains or loses its lead
	// (testing leads that weren't drawn at first).
	private static final Map<Integer, Boolean> LEASHED = new HashMap<>();

	private static void logLead(SkyrimActorEntity proxy) {
		boolean leashed = proxy.isLeashed();
		Boolean was = LEASHED.put(proxy.formId(), leashed);
		if (was != null && was != leashed || was == null && leashed) {
			var holder = proxy.getLeashHolder();
			dev.skycraft.SkyCraft.LOG.info("SkyCraft: lead on {} here: {} (holder {}, {} blocks away)", proxy.getName().getString(), leashed ? "on" : "off",
				holder == null ? "none" : holder.getName().getString(), holder == null ? 0 : Math.round(holder.distanceTo(proxy)));
		}
	}
}
