package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.render.WorldExporter;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;

/**
 * Per-frame glue between the Minecraft client and Skyrim. Everything here runs on the render
 * thread, called from MinecraftMixin.
 */
public final class SkyClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("skycraft.showWindow");
	// Started by Skyrim (SkyCraft's bundled instance passes -Dskycraft.startHidden=true): no window and
	// no title-screen music from the first frame, even while Skyrim is paused (Alt-Tabbed) and the
	// two haven't linked up yet. Otherwise the window only goes once Skyrim is there.
	private static final boolean START_HIDDEN = Boolean.getBoolean("skycraft.startHidden");
	private static boolean startedHidden;

	private static final SkyLink.SkyState sky = new SkyLink.SkyState();
	private static final java.util.List<SkyLink.Shot> GUEST_SHOTS = new java.util.ArrayList<>();
	private static long shotsSentAt;
	private static long intoAskedAt;  // SkyCraft+ : interiors in multiplayer
	private static final SkyLink.McState mc = new SkyLink.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;

	// Teleport / hold state: Skyrim decides where the player is after loads, doors and respawns.
	private static int lastTeleportSeq = -1;
	private static int teleportAck;
	private static boolean teleportPending;
	private static LocalPlayer lastPlayer;
	private static Vec3 holdPos;
	private static int sentHands = -1; // SkyCraft+ : multiplayer (the guest's Skyrim hands, as last sent)
	private static boolean underLand; // SkyCraft+ : the underground (kept while no land is known around)
	private static boolean deepUnderLand; // SkyCraft+ : performance
	private static Vec3 unlinkedHold;
	private static long holdSince;
	private static long qpcFreq;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean skyrimStalled;
	private static int exporterErrors;
	// SkyCraft+ : mode switch. Skyrim drives its own player and ours follows (SkyState flag).
	private static boolean skyrimDriving;

	private SkyClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * True once Skyrim has connected in this session. From then on Minecraft never touches the
	 * real mouse or keyboard again (even if Skyrim closes), since its window is hidden.
	 */
	public static boolean tookOver() {
		return tookOver;
	}

	public static SkyLink.SkyState sky() {
		return sky;
	}

	/** Start of Minecraft.runTick: pull state and input from Skyrim before anything else runs. */
	public static void beginFrame() {
		SkyLink.poll();
		quitWithSkyrim(Minecraft.getInstance());
		if (START_HIDDEN && !startedHidden) {
			startedHidden = true;
			Minecraft minecraft = Minecraft.getInstance();
			hideWindowOnce(minecraft);
			minecraft.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			minecraft.getMusicManager().stopPlaying();
		}
		boolean nowLinked = SkyLink.active();
		if (nowLinked) {
			SkyLink.readSkyState(sky); // on a torn read we simply keep last frame's state
			dev.skycraft.world.SkyWater.refresh();
		} else {
			dev.skycraft.world.SkyWater.clear();
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
			if (linked) {
				tookOver = true;
				unlinkedHold = null;
				SkyCollision.startConsumer();
				applyLinkedOptions();
			} else {
				InputBridge.releaseAll();
				skyrimDriving = false;
				SkyCombat.skyrimDrives = false;
				SkyCombat.rightHandSkyrim = false;
				SkyCombat.leftHandSkyrim = false;
				SkyCombat.skyrimBlocking = false;
				dev.skycraft.world.SkyWorld.clear();
				LocalPlayer player = Minecraft.getInstance().player;
				unlinkedHold = player != null ? player.position() : null;
			}
		}
		if (!linked) {
			openStandaloneWhenDue(Minecraft.getInstance());
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		MirrorWorld.openWhenReady(minecraft);

		if (sky.menuOpen() || sky.loading()) {
			InputBridge.releaseAll();
		}
		InputBridge.drain(minecraft);
		ProxySync.frame(minecraft);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}

		// A new player object means we just joined or respawned: put it where Skyrim's player is.
		if (player != lastPlayer) {
			lastPlayer = player;
			teleportPending = true;
			sentHands = -1;  // SkyCraft+ : a server just joined (or rejoined) has forgotten them
		}
		if (sky.teleportSeq != lastTeleportSeq) {
			lastTeleportSeq = sky.teleportSeq;
			teleportPending = true;
		}
		// SkyCraft+ : a save made in the Nether or the End was loaded: the host goes back into it (sendToWorld).
		boolean sendAway = sky.sendToWorld != 0 && minecraft.getSingleplayerServer() != null;
		if (teleportPending && sky.inGame() && !sky.loading() && !sendAway
			&& ((inMinecraftWorld(minecraft) && (!sky.bringBack() || minecraft.getSingleplayerServer() == null)) || sky.parked())) {
			// SkyCraft+ : the Nether and the End. Skyrim has nowhere there to put us: it follows
			// (it parks its player in an empty cell), so its teleports are acknowledged, not taken.
			teleportAck = sky.teleportSeq;
			teleportPending = false;
		}
		if (teleportPending && sky.inGame() && !sky.loading()) {
			requestTeleport(minecraft, sky.x, sky.y, sky.z, sky.yaw, sky.pitch);
			teleportAck = sky.teleportSeq;
			teleportPending = false;
			holdPos = new Vec3(sky.x, sky.y, sky.z);
		}
		// SkyCraft+ : interiors in multiplayer. A guest's request to change dimension sent too early
		// (just joined) is lost: while its Skyrim is in another kind of world than its Minecraft, ask again.
		if (minecraft.getSingleplayerServer() == null && sky.inGame() && !sky.loading() && minecraft.level != null && System.nanoTime() - intoAskedAt > 2_000_000_000L) {
			var want = dimensionFor(sky);
			if (want != minecraft.level.dimension() && dimensionCode(want) <= Proto.DIM_WORLDSPACES && dimensionCode(minecraft.level.dimension()) <= Proto.DIM_WORLDSPACES) {
				intoAskedAt = System.nanoTime();
				requestTeleport(minecraft, sky.x, sky.y, sky.z, sky.yaw, sky.pitch);
			}
		}

		dev.skycraft.world.SkyWorld.update(sky.inGame() && !sky.loading(), sky.gameHour, sky.placeFlags, sky.seaLevel);
		SkyCombat.skyrimBlocking = sky.skyrimBlocking();
		SkyCombat.rightHandSkyrim = sky.rightHandSkyrim();
		SkyCombat.leftHandSkyrim = sky.leftHandSkyrim();
		// SkyCraft+ : multiplayer. A guest tells the host's server which hands its Skyrim holds.
		// SkyCraft+ : Skyrim weapons break blocks. And what they hold (the tool each mines as).
		SkyCombat.rightHandKind = sky.rightHandKind;
		SkyCombat.leftHandKind = sky.leftHandKind;
		int hands = (sky.rightHandSkyrim() ? 1 : 0) | (sky.leftHandSkyrim() ? 2 : 0) | (sky.rightHandKind & 0xFF) << 8 | (sky.leftHandKind & 0xFF) << 16;
		if (hands != sentHands && minecraft.getSingleplayerServer() == null
			&& net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.skycraft.net.SkyNet.Act.TYPE)) {
			sentHands = hands;  // (only once it can go: sent too early, it was dropped and never sent again)
			act(minecraft, dev.skycraft.net.SkyNet.Act.simple(dev.skycraft.net.SkyNet.Act.HANDS, hands, 0.0F, 0.0F));
		}
		// SkyCraft+ : multiplayer. A guest's Skyrim spells, shouts and arrows go to the host's server,
		// which hits Minecraft's mobs with them (the host's are read there directly).
		if (minecraft.getSingleplayerServer() == null && System.nanoTime() - shotsSentAt > 50_000_000L
			&& net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.skycraft.net.SkyNet.Shots.TYPE)
			&& SkyLink.readSkyrimShots(GUEST_SHOTS) && !GUEST_SHOTS.isEmpty()) {
			shotsSentAt = System.nanoTime();
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.skycraft.net.SkyNet.Shots(java.util.List.copyOf(GUEST_SHOTS)));
		}
		boolean drives = sky.skyrimDrives() && sky.inGame() && !sky.loading();
		if (drives != skyrimDriving) {
			skyrimDriving = drives;
			SkyCombat.skyrimDrives = drives;
			SkyCraft.LOG.info("SkyCraft: {}", drives ? "Skyrim drives the player; following it" : "Minecraft drives the player again");
			InputBridge.releaseAll();
			if (!drives) {
				takeSkyrimHealth(minecraft, sky.healthFrac);
			}
		}

		// Look direction is driven by Skyrim (zero-latency camera); MC uses it for everything else.
		if (minecraft.gui.screen() == null) {
			player.setYRot(sky.yaw);
			player.setXRot(sky.pitch);
			player.yRotO = sky.yaw;
			player.xRotO = sky.pitch;
		}
	}

	// Minecraft is started with Skyrim (the SKSE plugin launches it), so it goes when that Skyrim has
	// closed for good: saved and shut down the normal way. -Dskycraft.quitWithSkyrim=false keeps it
	// running instead (development: restarting Skyrim without restarting Minecraft).
	private static final boolean QUIT_WITH_SKYRIM = Boolean.parseBoolean(System.getProperty("skycraft.quitWithSkyrim", "true"));
	private static long skyrimGoneSince;
	private static long nextSkyrimCheck;
	// Started hidden by Skyrim but never connected: nobody can see or use this Minecraft, and it
	// would stop the next Skyrim from starting a fresh one ("already running"). It goes after this.
	private static final long NEVER_CONNECTED_QUIT_MS = 10 * 60 * 1000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static boolean gaveUpWaiting;

	// Linux bridge only (Windows untouched): Skyrim is started by hand there, so Minecraft can come
	// up without it. Rather than wait on the title screen forever, after a grace period a normal
	// Minecraft world opens and a chat note explains why. -Dskycraft.standalone=true (or
	// SKYCRAFT_STANDALONE) skips the wait; skycraft.standaloneAfterMs / SKYCRAFT_STANDALONE_AFTER_MS
	// change the grace period (ms).
	private static final boolean STANDALONE_ALLOWED = !SkyLink.isWindows();
	private static final boolean STANDALONE_NOW =
		Boolean.getBoolean("skycraft.standalone") || System.getenv("SKYCRAFT_STANDALONE") != null;
	private static final long STANDALONE_AFTER_MS = standaloneAfterMs();
	private static boolean standalone;

	private static long standaloneAfterMs() {
		String value = System.getProperty("skycraft.standaloneAfterMs");
		if (value == null) {
			value = System.getenv("SKYCRAFT_STANDALONE_AFTER_MS");
		}
		try {
			return value != null ? Long.parseLong(value.trim()) : 60_000L;
		} catch (NumberFormatException e) {
			return 60_000L;
		}
	}

	/**
	 * Linux bridge without Skyrim: open a normal Minecraft world (the mod's content, no Skyrim
	 * terrain) after the grace period, and say so in chat. Never runs on Windows, where Skyrim
	 * starts Minecraft and the link is expected.
	 */
	private static void openStandaloneWhenDue(Minecraft minecraft) {
		if (!STANDALONE_ALLOWED) {
			return;
		}
		if (!standalone) {
			if (!STANDALONE_NOW && System.currentTimeMillis() - STARTED_AT < STANDALONE_AFTER_MS) {
				return;
			}
			standalone = true;
			SkyCraft.LOG.warn("SkyCraft: no Skyrim link; opening a normal Minecraft world (standalone)");
			MirrorWorld.setStandalone(true);
			MirrorWorld.setPendingNote("SkyCraft: Skyrim isn't connected, so a normal Minecraft world opened. "
				+ "Start Skyrim with the SkyCraft SKSE plugin and restart Minecraft to play in Skyrim's world.");
		}
		MirrorWorld.openWhenReady(minecraft);
	}

	private static void quitWithSkyrim(Minecraft minecraft) {
		int pid = SkyLink.skyrimPid();
		long now = System.currentTimeMillis();
		if (QUIT_WITH_SKYRIM && START_HIDDEN && pid == 0 && !tookOver && !standalone && !gaveUpWaiting && now - STARTED_AT > NEVER_CONNECTED_QUIT_MS) {
			gaveUpWaiting = true;
			SkyCraft.LOG.warn("SkyCraft: started hidden but Skyrim never connected in {} minutes; quitting", NEVER_CONNECTED_QUIT_MS / 60000);
			minecraft.stop();
			return;
		}
		if (!QUIT_WITH_SKYRIM || pid == 0 || now < nextSkyrimCheck) {
			return;
		}
		nextSkyrimCheck = now + 1000;
		if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			skyrimGoneSince = 0;
			return;
		}
		if (skyrimGoneSince == 0) {
			skyrimGoneSince = now;
		} else if (now - skyrimGoneSince > 5000) {
			SkyCraft.LOG.info("SkyCraft: Skyrim (pid {}) has closed; saving and quitting", pid);
			minecraft.stop();
		}
	}

	/** Called at the end of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		MirrorWorld.tick(minecraft);
		WorldSwitcher.tick(minecraft); // SkyCraft+ : world switcher
		DiscordPresence.tick(minecraft);
		SkyDigClient.tick(minecraft);
		freezeWhileUnlinked(minecraft);
		holdUntilReady(minecraft);
		followSkyrim(minecraft);
		publishTick(minecraft);
	}

	/**
	 * Skyrim drives its own player (Skyrim mode, a chair, a horse, a scene): ours stays at its feet,
	 * so blocks, items and mobs stay loaded around it and handing back needs no jump.
	 */
	private static void followSkyrim(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || !skyrimDriving || player == null || holdPos != null) {
			return;
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(sky.x, sky.y, sky.z);
		player.resetFallDistance();
	}

	/**
	 * Skyrim mode: Skyrim's Activate found nothing of its own, so use the Minecraft block in view by
	 * hand (doors, chests, crafting tables, levers), never the held item: a block in hand isn't placed.
	 * Our player looks where Skyrim's camera does. The host only: a guest's server only takes a
	 * normal use.
	 */
	public static void useBlockInView(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.gui.screen() != null) {
			return;
		}
		if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			SkyCraft.LOG.info("SkyCraft: Skyrim mode use: no Minecraft block in view");
			return;
		}
		var at = hit.getLocation();
		act(minecraft, new dev.skycraft.net.SkyNet.Act(dev.skycraft.net.SkyNet.Act.USE_BLOCK, 0, 0.0F, 0.0F, hit.getBlockPos(), hit.getDirection().get3DDataValue(),
			(float) at.x, (float) at.y, (float) at.z));
	}

	// SkyCraft+ : hand mixing. What each arm held last frame, and the player's changes counted.
	private static int lastSlot = -1, rightChanges, leftChanges;
	private static net.minecraft.world.item.ItemStack lastRight = net.minecraft.world.item.ItemStack.EMPTY, lastLeft = net.minecraft.world.item.ItemStack.EMPTY;

	/**
	 * SkyCraft+ : hand mixing (McState::hands). Which arms hold a Minecraft item, and a count of the
	 * player's own changes to each: another hotbar slot, the off-hand swap, a move made in an inventory.
	 * (Not a pickup filling the empty hand, nor food eaten or blocks placed running out.)
	 */
	private static int handsCode(Minecraft minecraft, LocalPlayer player) {
		boolean rightMain = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		var right = rightMain ? player.getMainHandItem() : player.getOffhandItem();
		var left = rightMain ? player.getOffhandItem() : player.getMainHandItem();
		// SkyCraft+ : Skyrim shortcuts. One that goes into a hand is Skyrim's there, not a Minecraft item:
		// picking it equips the Skyrim thing, which the change mustn't take out again.
		boolean rightShortcut = SkyShortcutsClient.equipsShortcut(right), leftShortcut = SkyShortcutsClient.equipsShortcut(left);
		if (rightShortcut) {
			right = net.minecraft.world.item.ItemStack.EMPTY;
		}
		if (leftShortcut) {
			left = net.minecraft.world.item.ItemStack.EMPTY;
		}
		int slot = player.getInventory().getSelectedSlot();
		boolean rightChanged = !net.minecraft.world.item.ItemStack.isSameItem(right, lastRight);
		boolean leftChanged = !net.minecraft.world.item.ItemStack.isSameItem(left, lastLeft);
		boolean swapped = rightChanged && leftChanged && net.minecraft.world.item.ItemStack.isSameItem(right, lastLeft)
			&& net.minecraft.world.item.ItemStack.isSameItem(left, lastRight);
		boolean byHand = minecraft.gui.screen() != null || swapped;
		if (lastSlot >= 0) {
			boolean mainSlot = slot != lastSlot;
			if ((rightMain && mainSlot || rightChanged && byHand) && !rightShortcut) {
				rightChanges++;
			}
			if ((!rightMain && mainSlot || leftChanged && byHand) && !leftShortcut) {
				leftChanges++;
			}
		}
		lastSlot = slot;
		lastRight = right.copy();
		lastLeft = left.copy();
		return (right.isEmpty() ? 0 : 1) | (left.isEmpty() ? 0 : 2) | (rightChanges & 0xFF) << 8 | (leftChanges & 0xFF) << 16 | (HandMixToggle.mode & 3) << 24;
	}

	/**
	 * SkyCraft+ : Skyrim weapons break blocks. A Skyrim swing (its hit frame) with a block under the
	 * crosshair: a Minecraft block is worn down by the server, Skyrim's ground mined here as a held
	 * button mines it. Shield bashes, spells and bows don't.
	 */
	public static void skyrimSwingOnBlock(Minecraft minecraft, int flags) {
		if ((flags & Proto.SKYRIM_HIT_BASH) != 0 || minecraft.player == null || minecraft.gui.screen() != null) {
			return;
		}
		var sky = sky();
		var tool = dev.skycraft.combat.SkyWeaponTools.tool((flags & Proto.SKYRIM_HIT_LEFT) != 0 ? sky.leftHandKind : sky.rightHandKind);
		if (tool.isEmpty()) {
			return;
		}
		if (minecraft.hitResult instanceof dev.skycraft.world.SkyClip.SkyrimHitResult) {
			if (dev.skycraft.combat.SkyWeaponTools.enabled || minecraft.getSingleplayerServer() == null) {
				SkyDigClient.weaponHit(minecraft, tool, (flags & Proto.SKYRIM_HIT_POWER) != 0
					? dev.skycraft.combat.SkyWeaponTools.POWER_SWING_TICKS : dev.skycraft.combat.SkyWeaponTools.SWING_TICKS);
			}
		} else if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			var at = hit.getLocation();
			act(minecraft, new dev.skycraft.net.SkyNet.Act(dev.skycraft.net.SkyNet.Act.BLOCK_HIT, flags, 0.0F, 0.0F, hit.getBlockPos(), hit.getDirection().get3DDataValue(),
				(float) at.x, (float) at.y, (float) at.z));
		}
	}

	/**
	 * SkyCraft+ : multiplayer. Something this player's Skyrim did, carried out by Minecraft's server:
	 * our own (as host), or the host's (as a guest).
	 */
	public static void act(Minecraft minecraft, dev.skycraft.net.SkyNet.Act act) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.skycraft.net.SkyNet.Act.TYPE)) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(act);
			}
			return;
		}
		var uuid = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null) {
				dev.skycraft.net.SkyNet.apply(sp, act);
			}
		});
	}

	/** One health pool: back in Minecraft's hands, the player keeps Skyrim's share of their health. */
	private static void takeSkyrimHealth(Minecraft minecraft, float fraction) {
		if (fraction > 0.0F) {
			act(minecraft, dev.skycraft.net.SkyNet.Act.simple(dev.skycraft.net.SkyNet.Act.SET_HEALTH, 0, Math.min(fraction, 1.0F), 0.0F));
		}
	}

	/**
	 * Skyrim went quiet (a long loading screen, a stall, or it closed). Its collision around the
	 * player may be about to change (interior doors), so keep the player exactly where they were
	 * instead of letting them fall; Skyrim puts them where they belong when it's back.
	 */
	private static void freezeWhileUnlinked(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (linked || !tookOver || player == null) {
			return;
		}
		if (unlinkedHold == null) {
			unlinkedHold = player.position();
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(unlinkedHold.x, unlinkedHold.y, unlinkedHold.z);
		player.xo = unlinkedHold.x;
		player.yo = unlinkedHold.y;
		player.zo = unlinkedHold.z;
		player.resetFallDistance();
	}

	/**
	 * Hands Skyrim the raw physics tick (previous + latest feet, smoothed eye height, walk bob) with a
	 * QueryPerformanceCounter timestamp. Skyrim interpolates between them on its own frame clock,
	 * exactly like Minecraft's renderer does with partial ticks.
	 */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (qpcFreq == 0) {
			qpcFreq = SkyLink.qpcFrequency();
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (DeltaTracker keeps the remainder).
		float remainder = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		mc.tickQpc = SkyLink.qpc() - (long) (remainder * tickMs * qpcFreq / 1000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.eyeHeightO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.eyeHeightT = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get();
		var avatar = player.avatarState();
		mc.walkDistO = bob ? avatar.getInterpolatedWalkDistance(0.0F) : 0.0F;
		mc.walkDist = bob ? avatar.getInterpolatedWalkDistance(1.0F) : 0.0F;
		mc.bobO = bob ? avatar.getInterpolatedBob(0.0F) : 0.0F;
		mc.bob = bob ? avatar.getInterpolatedBob(1.0F) : 0.0F;
		SkyLink.writeMcState(mc);
	}

	/** Freeze the player until Skyrim's collision around them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!sky.inGame() || sky.loading()) {
			// Skyrim is on its main menu or a loading screen: park the player where they are.
			if (holdPos == null) {
				holdPos = player.position();
			}
			teleportPending = true;
		}
		if (holdPos == null) {
			holdSince = 0;
			return;
		}
		if (holdSince == 0) {
			holdSince = System.currentTimeMillis();
		}
		if (inMinecraftWorld(minecraft) && sky.inGame() && !sky.loading()) {
			holdPos = null; // SkyCraft+ : the Nether and the End are all Minecraft's blocks: nothing to wait for
			return;
		}
		int bx = (int) Math.floor(holdPos.x), by = (int) Math.floor(holdPos.y), bz = (int) Math.floor(holdPos.z);
		boolean known = SkyCollision.isKnown(bx, by - 1, bz) && SkyCollision.isKnown(bx, by, bz)
			&& SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);
		// Release once there is actual ground below (or after a timeout, e.g. when mid-air on purpose).
		boolean ready = known && (SkyCollision.hasSolidBelow(bx, by, bz, 12) || System.currentTimeMillis() - holdSince > 6000);
		if (ready && sky.inGame() && !sky.loading()) {
			// Skyrim's feet can sit a fraction of a voxel inside our ground layer. Minecraft's
			// collision never pushes you out of a shape, so you'd drop through: lift out first.
			Vec3 safe = liftOutOfGeometry(player, holdPos);
			if (safe.y != holdPos.y) {
				player.setPos(safe.x, safe.y, safe.z);
				player.yo = safe.y;
				SkyCraft.LOG.info("SkyCraft: lifted player {} blocks out of the ground", String.format("%.3f", safe.y - holdPos.y));
			}
			holdPos = null;
			return;
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(holdPos.x, holdPos.y, holdPos.z);
		player.xo = holdPos.x;
		player.yo = holdPos.y;
		player.zo = holdPos.z;
		player.resetFallDistance();
	}

	private static Vec3 liftOutOfGeometry(LocalPlayer player, Vec3 pos) {
		// Stand on the exact Skyrim ground if it is slightly above the feet (up to 2.5 blocks).
		double ground = SkyCollider.groundAt(pos.x, pos.y, pos.z, 2.5);
		return !Double.isNaN(ground) && ground > pos.y ? new Vec3(pos.x, ground, pos.z) : pos;
	}

	/** SkyCraft+ : interiors. Each kind of Skyrim world has its own Minecraft dimension, so they don't share blocks. */
	public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> INTERIORS = net.minecraft.resources.ResourceKey.create(
		net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "interiors"));
	public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> WORLDSPACES = net.minecraft.resources.ResourceKey.create(
		net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "worldspaces"));

	/**
	 * Tamriel and its walled cities (same coordinates): the overworld, where the underground goes.
	 * Interiors: their own dimension. Other worldspaces (Solstheim, Blackreach, Sovngarde...): another,
	 * since their coordinates overlap Tamriel's.
	 */
	private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionFor(SkyLink.SkyState sky) {
		if (sky.sendToWorld == Proto.DIM_NETHER) {
			return net.minecraft.world.level.Level.NETHER;
		}
		if (sky.sendToWorld == Proto.DIM_END) {
			return net.minecraft.world.level.Level.END;
		}
		if ((sky.placeFlags & Proto.PLACE_INTERIOR) != 0) {
			return INTERIORS;
		}
		return (sky.placeFlags & Proto.PLACE_TAMRIEL) != 0 ? net.minecraft.world.level.Level.OVERWORLD : WORLDSPACES;
	}

	/** SkyCraft+ : the Nether and the End. Where the player is, as Skyrim is told (Proto.DIM_*). */
	public static int dimensionCode(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
		if (dimension == net.minecraft.world.level.Level.OVERWORLD) {
			return Proto.DIM_TAMRIEL;
		}
		if (dimension == INTERIORS) {
			return Proto.DIM_INTERIORS;
		}
		if (dimension == WORLDSPACES) {
			return Proto.DIM_WORLDSPACES;
		}
		if (dimension == net.minecraft.world.level.Level.NETHER) {
			return Proto.DIM_NETHER;
		}
		return dimension == net.minecraft.world.level.Level.END ? Proto.DIM_END : Proto.DIM_OTHER;
	}

	/** SkyCraft+ : in a world of Minecraft's own (the Nether, the End): Skyrim follows, never the other way. */
	public static boolean inMinecraftWorld(Minecraft minecraft) {
		return minecraft.level != null && dimensionCode(minecraft.level.dimension()) >= Proto.DIM_NETHER;
	}

	private static void requestTeleport(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer player = minecraft.player;
		player.setPos(x, y, z);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		var dimension = dimensionFor(sky);
		boolean bySave = sky.sendToWorld != 0;
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					var target = server.getLevel(dimension);
					if (target != null && target != sp.level()) {
						// SkyCraft+ : interiors and other worldspaces have dimensions of their own.
						dev.skycraft.world.SkyMinecraftWorlds.sentBySave = bySave;
						try {
							sp.teleportTo(target, x, y, z, java.util.Set.of(), yaw, pitch, true);
						} finally {
							dev.skycraft.world.SkyMinecraftWorlds.sentBySave = false;
						}
						SkyCraft.LOG.info("SkyCraft: into {}", dimension.identifier());
					} else {
						// Turn first: teleportTo sends the player its rotation along with the position.
						sp.setYRot(yaw);
						sp.setXRot(pitch);
						sp.teleportTo(x, y, z);
					}
					sp.resetFallDistance();
				}
			});
		} else if (minecraft.level != null && dimension != minecraft.level.dimension() && dimensionCode(dimension) <= Proto.DIM_WORLDSPACES
			&& dimensionCode(minecraft.level.dimension()) <= Proto.DIM_WORLDSPACES) {
			// SkyCraft+ : interiors in multiplayer. A guest's own Skyrim went into an interior (or
			// another worldspace, or back out): the host's server moves its Minecraft player there,
			// so players in the same interior see each other (it stayed in the overworld before).
			act(minecraft, new dev.skycraft.net.SkyNet.Act(dev.skycraft.net.SkyNet.Act.INTO, dimensionCode(dimension), yaw, pitch,
				net.minecraft.core.BlockPos.ZERO, 0, (float) x, (float) y, (float) z));
			intoAskedAt = System.nanoTime();
			SkyCraft.LOG.info("SkyCraft: asked the server to go into {}", dimension.identifier());
		}
		SkyCraft.LOG.info("SkyCraft: teleported to {} {} {}", x, y, z);
	}

	/** After GameRenderer.render(): report the player to Skyrim and ship the overlay frame. */
	public static void afterRender() {
		if (!linked) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.mainCamera();
			flags |= Proto.MC_IN_WORLD;
			if (player.onGround()) {
				flags |= Proto.MC_ON_GROUND;
			}
			if (player.isShiftKeyDown()) {
				flags |= Proto.MC_SNEAKING;
			}
			if (player.isSprinting()) {
				flags |= Proto.MC_SPRINTING;
			}
			if (player.isDeadOrDying()) {
				flags |= Proto.MC_DEAD;
			}
			if (player.isSwimming()) {
				flags |= Proto.MC_SWIMMING;
			}
			if (player.getAbilities().flying) {
				flags |= Proto.MC_FLYING;
			}
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			// The eye, not the camera: in third person Minecraft's camera sits behind or in front.
			Vec3 eye = camera.isDetached() ? player.getEyePosition(partial) : camera.position();
			mc.eyeHeight = (float) (eye.y - feet.y);
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = camera.getFov();
			// Minecraft's F5 camera: Skyrim puts its camera where Minecraft's would be.
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			mc.cameraDistance = camera.isDetached() ? (float) camera.position().distanceTo(player.getEyePosition(partial)) : 0.0F;
			// Walk bob, exactly what GameRenderer.bobView() uses this frame.
			var entityState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.entityRenderState;
			boolean bob = minecraft.options.bobView().get() && entityState.isPlayer;
			mc.bobPhase = bob ? entityState.backwardsInterpolatedWalkDistance : 0.0F;
			mc.bobAmount = bob ? entityState.bob : 0.0F;
			mc.healthFrac = player.getMaxHealth() > 0.0F ? Math.clamp(player.getHealth() / player.getMaxHealth(), 0.0F, 1.0F) : 0.0F;
			mc.dimension = dimensionCode(minecraft.level.dimension());
			mc.hands = handsCode(minecraft, player);  // SkyCraft+ : hand mixing
		}
		if (minecraft.gui.screen() != null) {
			flags |= Proto.MC_SCREEN_OPEN;
			// SkyCraft+ : controller inventory snapping. A screen with slots: the pad jumps between them.
			if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
				flags |= Proto.MC_SLOT_SCREEN;
			}
			mc.cursorSeq = InputBridge.jumpSeq;
			mc.cursorX = InputBridge.jumpX;
			mc.cursorY = InputBridge.jumpY;
		} else if (player != null && (SkyHands.usableTargetInView(minecraft) || SkyShortcutsClient.usableInHand(player))) {
			flags |= Proto.MC_USE_TARGET;  // (SkyCraft+ : Skyrim shortcuts: a potion's right click is Minecraft's too)
		}
		// SkyCraft+ : Skyrim's inventory in Minecraft's (and the shortcuts' counts). Skyrim keeps its list current meanwhile.
		if (SkyShortcutsClient.wanted()) {
			flags |= Proto.MC_WANTS_SKY_INVENTORY;
		}
		// SkyCraft+ : the underground. Under Skyrim's land, by the land heights it has sent (where it
		// hasn't sent any around here yet, as before).
		if (player != null && minecraft.level != null && minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			Boolean under = dev.skycraft.world.SkyGround.underLoosely(player.getX(), player.getEyeY(), player.getZ(), 2.0);
			if (under != null) {
				underLand = under;
			}
			// SkyCraft+ : performance. Deep down Skyrim stops drawing its world (in at 24 blocks, out at 20).
			Boolean deep = dev.skycraft.world.SkyGround.underLoosely(player.getX(), player.getEyeY(), player.getZ(), deepUnderLand ? 20.0 : 24.0);
			if (deep != null) {
				deepUnderLand = deep;
			}
		} else {
			underLand = false;
			deepUnderLand = false;
		}
		if (underLand) {
			flags |= Proto.MC_UNDER_LAND;
			if (deepUnderLand) {
				flags |= Proto.MC_DEEP_UNDER_LAND;
			}
		}
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportAck = holdPos == null ? teleportAck : teleportAck - 1; // not "arrived" until we are released
		mc.guiScale = minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		SkyLink.writeMcState(mc);

		if ((flags & Proto.MC_IN_WORLD) != 0) {
			try {
				WorldExporter.frame(minecraft, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			} catch (RuntimeException e) {
				if (exporterErrors++ < 5) {
					SkyCraft.LOG.error("SkyCraft: world export failed", e);
				}
			}
			FrameExporter.capture(minecraft);
		}
	}

	/** End of the frame: render at most once per Skyrim frame instead of spinning freely. */
	public static void paceFrame() {
		if (!linked) {
			return;
		}
		if (skyrimStalled && (SkyLink.skyStateSeq() >>> 1) == lastPacedSeq) {
			return; // Skyrim is paused (menu / alt-tab): don't block every frame waiting for it
		}
		skyrimStalled = false;
		long deadline = System.nanoTime() + 25_000_000L;
		// SkyState.seq advances by 2 per Skyrim frame (odd while writing).
		while ((SkyLink.skyStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				Thread.yield();
			}
		}
		int seqNow = SkyLink.skyStateSeq() >>> 1;
		skyrimStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
	}

	private static void applyLinkedOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		var options = minecraft.options;
		options.pauseOnLostFocus = false;
		options.vignette().set(false);
		options.enableVsync().set(false);
		options.framerateLimit().set(260);
		// Minecraft doesn't draw the world itself; these only decide how far out placed blocks,
		// arrows and Skyrim NPC stand-ins stay loaded and simulated.
		options.renderDistance().set(8);
		options.simulationDistance().set(8);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		if (options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		}
		options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
		SkyCraft.LOG.info("SkyCraft: game window hidden (run with -Dskycraft.showWindow=true to keep it)");
	}

	private static void applyViewportSize(Minecraft minecraft) {
		int w = Math.min(sky.viewportW, Proto.MAX_OVERLAY_W);
		int h = Math.min(sky.viewportH, Proto.MAX_OVERLAY_H);
		if (w <= 0 || h <= 0 || (w == appliedViewportW && h == appliedViewportH)) {
			return;
		}
		appliedViewportW = w;
		appliedViewportH = h;
		minecraft.getWindow().setWindowed(w, h);
		SkyCraft.LOG.info("SkyCraft: sizing overlay to Skyrim viewport {}x{}", w, h);
	}
}
