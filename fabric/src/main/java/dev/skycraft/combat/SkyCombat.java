package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/**
 * Combat between the Minecraft player and Skyrim actors, server side.
 *
 * <p>Every Skyrim actor near the player gets an invisible {@link SkyrimActorEntity} at its exact
 * position. Minecraft weapons hit those like any mob; the resulting damage is sent to Skyrim, which
 * applies it to the real actor (scaled by level) and makes it fight back. Skyrim's hits on the player
 * come back as Minecraft damage from the attacker's stand-in, so armor, shields, knockback, hurt
 * sounds and death all work the Minecraft way.
 */
public final class SkyCombat {
	public static final ResourceKey<EntityType<?>> SKYRIM_ACTOR_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_actor"));
	public static final EntityType<SkyrimActorEntity> SKYRIM_ACTOR = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		SKYRIM_ACTOR_KEY,
		EntityType.Builder.<SkyrimActorEntity>of(SkyrimActorEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.noSave()
			.noSummon()
			.noLootTable()
			.clientTrackingRange(10)
			.updateInterval(1)
			.build(SKYRIM_ACTOR_KEY)
	);

	/** Skyrim damage is divided by this for Minecraft (a 15-damage bandit swing = 3 = 1.5 hearts). */
	public static final float SKYRIM_TO_MC_DAMAGE = 5.0F;

	/**
	 * SkyCraft+ : mode switch. Skyrim drives the host's player (Skyrim mode, furniture, riding,
	 * scenes): its Minecraft player only follows along and can't be hurt meanwhile.
	 */
	public static volatile boolean skyrimDrives;

	/** SkyCraft+ : Skyrim combat on Minecraft mobs. The host's Skyrim player is blocking. */
	public static volatile boolean skyrimBlocking;

	/**
	 * SkyCraft+ : Skyrim combat on Minecraft mobs. A Skyrim melee hit landed (its HitFrame): it hits
	 * the nearest Minecraft mob in front of the player within reach and in sight, or with a power
	 * attack everything in the arc, as the player's own attack (drops and experience). A bash
	 * mostly pushes. Skyrim's NPCs aren't touched here: Skyrim hits those itself.
	 */
	public static void skyrimMelee(net.minecraft.server.level.ServerPlayer player, int flags, float damage, float reach) {
		var level = player.level();
		boolean power = (flags & Proto.SKYRIM_HIT_POWER) != 0;
		boolean bash = (flags & Proto.SKYRIM_HIT_BASH) != 0;
		var eye = player.getEyePosition();
		var look = player.getLookAngle();
		double arcCos = power ? 0.35 : 0.6; // about 70 / 53 degrees either side
		java.util.List<LivingEntity> targets = new java.util.ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(reach + 1.0),
			e -> e != player && e.isAlive() && !(e instanceof SkyrimActorEntity) && !(e instanceof net.minecraft.world.entity.player.Player))) {
			var centre = e.getBoundingBox().getCenter();
			var to = centre.subtract(eye);
			double distance = Math.max(0.0, to.length() - e.getBbWidth() * 0.5);
			if (distance <= reach && to.normalize().dot(look) >= arcCos && player.hasLineOfSight(e)) {
				targets.add(e);
			}
		}
		// SkyCraft+ : the End. Its crystals aren't living, but a sword breaks them as in Minecraft.
		for (var crystal : level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, player.getBoundingBox().inflate(reach + 1.0), net.minecraft.world.entity.Entity::isAlive)) {
			var to = crystal.getBoundingBox().getCenter().subtract(eye);
			if (to.length() - 1.0 <= reach && to.normalize().dot(look) >= arcCos && player.hasLineOfSight(crystal)) {
				crystal.hurtServer(level, player.damageSources().playerAttack(player), Math.max(1.0F, damage));
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		targets.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(player)));
		if (!power) {
			targets = targets.subList(0, 1);
		}
		var source = player.damageSources().playerAttack(player);
		double kx = net.minecraft.util.Mth.sin(player.getYRot() * (float) (Math.PI / 180.0));
		double kz = -net.minecraft.util.Mth.cos(player.getYRot() * (float) (Math.PI / 180.0));
		for (LivingEntity e : targets) {
			if (e.hurtServer(level, source, damage)) {
				e.knockback(bash ? 1.2 : power ? 0.8 : 0.4, kx, kz, source, damage);
			}
		}
		SkyCraft.LOG.info("SkyCraft: Skyrim {} hit {} Minecraft mob(s) for {}", bash ? "bash" : power ? "power attack" : "attack", targets.size(), damage);
	}

	/** SkyCraft+ : hand ownership. The host's hands Skyrim holds (set by the client each frame). */
	public static volatile boolean rightHandSkyrim, leftHandSkyrim;

	/** SkyCraft+ : Skyrim weapons break blocks. What the host's Skyrim hands hold (Proto.HAND_*). */
	public static volatile int rightHandKind, leftHandKind;

	/**
	 * SkyCraft+ : Skyrim weapons break blocks. The Minecraft tool the Skyrim weapon in this hand
	 * mines as (SkyWeaponTools.tool), or empty: Minecraft's hand, or nothing that mines.
	 */
	public static net.minecraft.world.item.ItemStack skyrimTool(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
		if (!(player instanceof net.minecraft.server.level.ServerPlayer sp) || !skyrimHolds(player, hand)) {
			return net.minecraft.world.item.ItemStack.EMPTY;
		}
		var main = player.getMainArm();
		boolean right = (hand == net.minecraft.world.InteractionHand.MAIN_HAND ? main : main.getOpposite()) == net.minecraft.world.entity.HumanoidArm.RIGHT;
		int kind;
		if (dev.skycraft.net.SkyNet.isHost(sp)) {
			kind = right ? rightHandKind : leftHandKind;
		} else {
			int hands = dev.skycraft.net.SkyNet.guestHandsAndKinds(sp);
			kind = right ? (hands >> 8) & 0xFF : (hands >> 16) & 0xFF;
		}
		return SkyWeaponTools.tool(kind);
	}

	public static boolean skyrimHolds(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
		if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
			return false;
		}
		// SkyCraft+ : multiplayer. A guest's own Skyrim hands, as their client told the server.
		boolean right, left;
		if (dev.skycraft.net.SkyNet.isHost(sp)) {
			right = rightHandSkyrim;
			left = leftHandSkyrim;
		} else {
			int hands = dev.skycraft.net.SkyNet.guestHands(sp);
			right = (hands & 1) != 0;
			left = (hands & 2) != 0;
		}
		if (!(right || left)) {
			return false;
		}
		var main = player.getMainArm();
		var arm = hand == net.minecraft.world.InteractionHand.MAIN_HAND ? main : main.getOpposite();
		return arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? right : left;
	}

	private static final Map<Integer, SkyrimActorEntity> PROXIES = new HashMap<>();
	private static final List<SkyLink.Actor> ACTORS = new ArrayList<>();

	private SkyCombat() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(SKYRIM_ACTOR, LivingEntity.createLivingAttributes());
		ServerTickEvents.END_SERVER_TICK.register(SkyCombat::serverTick);
	}

	public static @Nullable SkyrimActorEntity proxy(int formId) {
		return PROXIES.get(formId);
	}

	private static void serverTick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (!SkyLink.active() || players.isEmpty()) {
			removeAll();
			return;
		}
		ServerLevel level = players.getFirst().level();
		for (ServerPlayer player : players) {
			pickUpNearby(player);
		}
		if (SkyLink.readActors(ACTORS)) {
			sync(level);
		}
		// Hits land during the tick (melee, sweeps, arrows, fire); send one combined hit per actor.
		for (SkyrimActorEntity proxy : PROXIES.values()) {
			float[] hit = proxy.takeHit();
			if (hit != null && (hit[0] > 0.0F || hit[3] > 0.0F)) {
				SkyLink.pushEvent(
					Proto.EV_HIT_ACTOR, proxy.formId(), hit[0], hit[1], hit[2], hit[3], Float.floatToRawIntBits(hit[4]), Float.floatToRawIntBits(hit[5])
				);
				SkyCraft.LOG.info("SkyCraft: hit {} for {} (knockback {})", proxy.getName().getString(), hit[0], hit[3]);
			}
		}
	}

	// SkyCraft+ : leads on Skyrim actors. What Skyrim was last told of each proxy's lead: 0 none, 1 the
	// host's, 2 a fence post (with where).
	private static final Map<Integer, Long> LEADS = new HashMap<>();

	private static void syncLeads(ServerLevel level) {
		ServerPlayer host = null;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
			}
		}
		for (SkyrimActorEntity proxy : PROXIES.values()) {
			Entity holder = proxy.getLeashHolder();
			int mode = holder == null ? 0 : holder == host ? 1 : holder instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity ? 2 : 0;
			long key = mode == 2 ? 2L | (holder.blockPosition().asLong() << 2) : mode;
			Long was = LEADS.get(proxy.formId());
			if (was != null && was == key || was == null && mode == 0) {
				continue;
			}
			LEADS.put(proxy.formId(), key);
			float x = mode == 2 ? (float) holder.getX() : 0.0F, y = mode == 2 ? (float) holder.getY() : 0.0F, z = mode == 2 ? (float) holder.getZ() : 0.0F;
			SkyLink.pushEvent(Proto.EV_LEASH, proxy.formId(), x, y, z, 0.0F, mode);
			SkyLink.Actor actor = actorById(proxy.formId());
			if (mode == 1 && host != null && actor != null && (actor.flags() & Proto.ACTOR_QUEST) != 0 && (was == null || was != 2)) {
				host.sendSystemMessage(Component.literal("Careful: " + proxy.getName().getString()
					+ " is in a quest. Leading quest characters away can break quests.").withStyle(net.minecraft.ChatFormatting.YELLOW));
			}
		}
	}

	public static SkyLink.@org.jspecify.annotations.Nullable Actor actor(int formId) {
		return actorById(formId);
	}

	private static SkyLink.@org.jspecify.annotations.Nullable Actor actorById(int formId) {
		for (SkyLink.Actor a : ACTORS) {
			if (a.formId() == formId) {
				return a;
			}
		}
		return null;
	}

	private static void sync(ServerLevel level) {
		Map<Integer, SkyLink.Actor> live = new HashMap<>();
		for (SkyLink.Actor a : ACTORS) {
			if (!a.dead()) {
				live.put(a.formId(), a);
			}
		}
		for (Iterator<Map.Entry<Integer, SkyrimActorEntity>> it = PROXIES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, SkyrimActorEntity> e = it.next();
			SkyrimActorEntity proxy = e.getValue();
			if (!live.containsKey(e.getKey()) || proxy.isRemoved() || proxy.level() != level) {
				proxy.discard();
				it.remove();
			}
		}
		int before = PROXIES.size();
		for (SkyLink.Actor a : live.values()) {
			SkyrimActorEntity proxy = PROXIES.get(a.formId());
			if (proxy == null) {
				proxy = new SkyrimActorEntity(SKYRIM_ACTOR, level);
				proxy.setFormId(a.formId());
				proxy.setSize(a.width(), a.height());
				proxy.snapTo(a.x(), a.y(), a.z(), a.yaw(), 0.0F);
				if (!a.name().isEmpty()) {
					proxy.setCustomName(Component.literal(a.name()));
				}
				if (!level.addFreshEntity(proxy)) {
					continue;
				}
				PROXIES.put(a.formId(), proxy);
				// SkyCraft+ : on the player's lead in Skyrim (it came through a door with them): on it here too.
				if ((a.flags() & Proto.ACTOR_LEASHED) != 0) {
					for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
						if (dev.skycraft.net.SkyNet.isHost(player) && player.level() == level) {
							proxy.setLeashedTo(player, true);
							LEADS.put(a.formId(), 1L);
						}
					}
				}
				continue;
			}
			proxy.setSize(a.width(), a.height());
			proxy.setPos(a.x(), a.y(), a.z());
			proxy.setYRot(a.yaw());
			proxy.setYHeadRot(a.yaw());
			stepOnTriggers(level, proxy);
		}
		syncLeads(level);  // SkyCraft+
		SkyBreeding.tick(level, PROXIES.values(), SkyCombat::actorById);  // SkyCraft+
		SkyMobFights.tick(level, PROXIES.values(), SkyCombat::actorById);  // SkyCraft+ : Skyrim's people fight Minecraft's mobs
		if (PROXIES.size() != before && (PROXIES.size() % 5 == 0 || PROXIES.size() < 5)) {
			SkyCraft.LOG.info("SkyCraft: {} Skyrim actors mirrored as hittable stand-ins", PROXIES.size());
		}
	}

	/**
	 * Skyrim's NPCs press pressure plates and trip tripwires. Their stand-ins are placed, not moved
	 * (no physics), so Minecraft never checks what they step into; do it for those blocks here.
	 */
	private static void stepOnTriggers(ServerLevel level, SkyrimActorEntity proxy) {
		var box = proxy.getBoundingBox().deflate(1.0E-5);
		var from = net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ);
		var to = net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ);
		for (var pos : net.minecraft.core.BlockPos.betweenClosed(from, to)) {
			var state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) {
				state.entityInside(level, pos, proxy, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, true);
			}
		}
	}

	/**
	 * Items and stuck arrows on Skyrim ground rest on its collision voxels, which on steep or rough
	 * terrain can sit a little off from where the player (on Skyrim's exact triangles) stands.
	 * Touch them over a slightly bigger area than vanilla's so walking over them picks them up.
	 * playerTouch applies all of Minecraft's own rules (pickup delay, owner, inventory space).
	 */
	private static void pickUpNearby(ServerPlayer player) {
		if (!player.isAlive() || player.isSpectator()) {
			return;
		}
		for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(1.25, 1.0, 1.25))) {
			if (!entity.isRemoved() && (entity instanceof net.minecraft.world.entity.item.ItemEntity
				|| entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)) {
				entity.playerTouch(player);
			}
		}
	}

	private static void removeAll() {
		if (PROXIES.isEmpty()) {
			return;
		}
		PROXIES.values().forEach(Entity::discard);
		PROXIES.clear();
	}

	/**
	 * Skyrim hit the player. Runs on the server thread. {@code kind} is a Proto.HURT_* value and
	 * {@code skyrimDamage} is what Skyrim would have taken off the player's health.
	 */
	public static void hurtPlayer(ServerPlayer player, int kind, float skyrimDamage, int attackerFormId, int flags) {
		if (!player.isAlive() || skyrimDamage <= 0.0F) {
			return;
		}
		ServerLevel level = player.level();
		SkyrimActorEntity attacker = PROXIES.get(attackerFormId);
		if (attacker != null && attacker.distanceToSqr(player) > 24.0 * 24.0) {
			attacker = null; // a guest's own NPC with the same form id as one of the host's
		}
		DamageSources sources = level.damageSources();
		DamageSource source = switch (kind) {
			case Proto.HURT_MELEE -> attacker != null ? sources.mobAttack(attacker) : sources.generic();
			case Proto.HURT_PROJECTILE -> attacker != null ? sources.mobProjectile(attacker, attacker) : sources.generic();
			case Proto.HURT_MAGIC -> attacker != null ? sources.indirectMagic(attacker, attacker) : sources.magic();
			default -> sources.generic();
		};
		float damage = skyrimDamage / SKYRIM_TO_MC_DAMAGE;
		float healthBefore = player.getHealth();
		boolean blocking = player.isBlocking();
		// SkyCraft+ : Skyrim armor in Minecraft's slots already counted in Skyrim (SkyArmor).
		boolean hurt;
		dev.skycraft.net.SkyArmor.skyrimHit = true;
		try {
			hurt = player.hurtServer(level, source, damage);
		} finally {
			dev.skycraft.net.SkyArmor.skyrimHit = false;
		}
		trainDefence(player, damage, blocking && player.getHealth() >= healthBefore - 1.0E-3F);
		SkyCraft.LOG.info("SkyCraft: Skyrim hit the player for {} ({} Minecraft): health {} -> {}{}", skyrimDamage, damage, healthBefore, player.getHealth(),
			hurt ? "" : " (blocked/immune)");
		if (hurt && attacker != null && (flags & Proto.HURT_POWER_ATTACK) != 0 && !player.isBlocking()) {
			// Power attacks shove harder, like a sprint hit does in Minecraft.
			player.knockback(0.5, attacker.getX() - player.getX(), attacker.getZ() - player.getZ(), source, damage);
		}
	}

	/**
	 * Skyrim skills for taking a hit: Block when the shield caught it, otherwise Light or Heavy
	 * Armor by what the player mostly wears (leather, chainmail, gold, copper and turtle count as
	 * light; iron, diamond and netherite as heavy). Only the host's own Skyrim is told.
	 */
	private static void trainDefence(ServerPlayer player, float damage, boolean blocked) {
		if (!dev.skycraft.net.SkyNet.isHost(player) || damage <= 0.0F) {
			return;
		}
		if (blocked) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, Proto.SKILL_BLOCK, damage, 0.0F, 0.0F, 0.0F, 0);
			return;
		}
		int light = 0, heavy = 0;
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
			net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET }) {
			var stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_")) {
				heavy++;
			} else {
				light++;
			}
		}
		if (light + heavy > 0) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, heavy > light ? Proto.SKILL_HEAVY_ARMOR : Proto.SKILL_LIGHT_ARMOR, damage * (light + heavy) / 4.0F, 0.0F, 0.0F,
				0.0F, 0);
		}
	}

	/** Form id of the Skyrim actor behind a damage source, or 0. */
	public static int attackerFormId(DamageSource source) {
		return source.getEntity() instanceof SkyrimActorEntity proxy ? proxy.formId() : 0;
	}
}
