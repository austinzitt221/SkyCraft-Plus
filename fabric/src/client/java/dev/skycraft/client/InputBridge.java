package dev.skycraft.client;

import dev.skycraft.combat.SkyCombat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Replays Skyrim-captured input into Minecraft's own input handlers, as if the (hidden) MC
 * window had focus. Keeps a virtual keyboard so InputConstants.isKeyDown() still works.
 */
public final class InputBridge {
	private static final boolean[] KEYS = new boolean[512];
	private static final boolean[] BUTTONS = new boolean[8];
	private static double cursorX, cursorY;
	private static int modifiers;
	private static int clickLogs;

	private InputBridge() {
	}

	public static boolean isKeyDown(int scancode) {
		return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
	}

	public static void drain(Minecraft minecraft) {
		SkyLink.drainInput((type, code, a, b, c) -> dispatch(minecraft, type, code, a, b, c));
	}

	private static void dispatch(Minecraft minecraft, int type, int code, int a, int b, int c) {
		long handle = minecraft.getWindow().handle();
		switch (type) {
			case Proto.IN_KEY -> key(minecraft, handle, code, a != 0);
			case Proto.IN_MOUSE_BUTTON -> {
				if (code > 0 && code < BUTTONS.length) {
					BUTTONS[code] = a != 0;
				}
				if (a != 0 && clickLogs++ < 20) {
					var hit = minecraft.hitResult;
					dev.skycraft.SkyCraft.LOG.info("SkyCraft: click {} -> {} {} (grabbed {}, screen {})", code, hit == null ? "null" : hit.getType(),
						hit instanceof net.minecraft.world.phys.EntityHitResult eh ? eh.getEntity().getName().getString() : hit == null ? "" : hit.getLocation(),
						minecraft.mouseHandler.isMouseGrabbed(), minecraft.gui.screen());
				}
				minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(code, modifiers), a != 0 ? 1 : 0);
			}
			case Proto.IN_SCROLL -> minecraft.mouseHandler.onScroll(handle, 0.0, a / 120.0);
			case Proto.IN_CURSOR -> {
				double dx = a - cursorX;
				double dy = b - cursorY;
				cursorX = a;
				cursorY = b;
				minecraft.mouseHandler.onMove(handle, a, b, dx, dy);
			}
			case Proto.IN_SLOT_JUMP -> slotJump(minecraft, handle, a, b);  // SkyCraft+ : controller inventory snapping
			case Proto.IN_DASH -> SkyDash.start(code); // SkyCraft+ : Whirlwind Sprint (code: words)
			case Proto.IN_TEXT -> {
				if (minecraft.gui.screen() != null) {
					minecraft.keyboardHandler.textInput(handle, new String(Character.toChars(a)));
				}
			}
			case Proto.IN_RELEASE_ALL -> releaseAll();
			case Proto.IN_HURT -> hurt(minecraft, code, a / 100.0F, b, c);
			case Proto.IN_OPEN_MENU -> {
				if (minecraft.gui.screen() == null && minecraft.player != null) {
					releaseAll();
					minecraft.gui.setScreen(new PauseScreen(true));
				}
			}
			case Proto.IN_USE -> SkyClient.useBlockInView(minecraft);
			// SkyCraft+ : a Skyrim healing spell or potion heals Minecraft's hearts (one health pool).
			case Proto.IN_HEAL -> SkyClient.act(minecraft, dev.skycraft.net.SkyNet.Act.simple(dev.skycraft.net.SkyNet.Act.HEAL, 0, a / 10000.0F, 0.0F));
			case Proto.IN_SKYRIM_HIT -> {
				SkyClient.act(minecraft, dev.skycraft.net.SkyNet.Act.simple(dev.skycraft.net.SkyNet.Act.SKYRIM_HIT, code, a / 100.0F, b / 100.0F));
				SkyClient.skyrimSwingOnBlock(minecraft, code);  // SkyCraft+ : Skyrim weapons break blocks
			}
			default -> {
			}
		}
	}

	/** SkyCraft+ : controller inventory snapping. Where Minecraft last put the cursor itself (McState). */
	public static volatile int jumpSeq, jumpX, jumpY;

	/**
	 * SkyCraft+ : controller inventory snapping. The cursor jumps to the nearest slot that way
	 * (dx, dy each -1, 0 or 1; down is +1), the nearer in line the better; off any slot, to the slot
	 * nearest it. Skyrim's cursor follows (McState::cursorSeq).
	 */
	private static void slotJump(Minecraft minecraft, long handle, int dx, int dy) {
		if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)) {
			return;
		}
		var pos = (dev.skycraft.client.mixin.ContainerScreenAccessor) screen;
		double scale = (double) minecraft.getWindow().getScreenWidth() / minecraft.getWindow().getGuiScaledWidth();
		double gx = cursorX / scale, gy = cursorY / scale;
		// Slot centres (GUI pixels), and the Skyrim panels' tabs and things beside the inventory.
		java.util.List<int[]> targets = new java.util.ArrayList<>();
		for (var slot : screen.getMenu().slots) {
			if (slot.isActive()) {
				targets.add(new int[] { pos.skycraft$leftPos() + slot.x + 8, pos.skycraft$topPos() + slot.y + 8 });
			}
		}
		targets.addAll(SkyInventoryPanels.jumpTargets(screen));
		int[] here = null;
		for (int[] target : targets) {
			if (gx >= target[0] - 9 && gx < target[0] + 9 && gy >= target[1] - 9 && gy < target[1] + 9) {
				here = target;
			}
		}
		int[] best = null;
		double bestScore = Double.MAX_VALUE;
		for (int[] target : targets) {
			if (target == here) {
				continue;
			}
			double cx = target[0] - gx, cy = target[1] - gy;
			double score;
			if (here == null) {
				score = cx * cx + cy * cy;
			} else {
				double along = cx * dx + cy * dy;  // how far that way
				double across = Math.abs(cx * dy - cy * dx);  // how far off the line
				if (along < 4.0) {
					continue;
				}
				score = along + across * 3.0;
			}
			if (score < bestScore) {
				bestScore = score;
				best = target;
			}
		}
		if (best == null) {
			return;
		}
		double x = best[0] * scale, y = best[1] * scale;
		double mx = x - cursorX, my = y - cursorY;
		cursorX = x;
		cursorY = y;
		minecraft.mouseHandler.onMove(handle, x, y, mx, my);
		jumpX = (int) Math.round(x);
		jumpY = (int) Math.round(y);
		jumpSeq++;
	}

	/** Skyrim hit the player: apply it as Minecraft damage on the integrated server (or the host's). */
	private static void hurt(Minecraft minecraft, int kind, float skyrimDamage, int attacker, int flags) {
		var server = minecraft.getSingleplayerServer();
		if (minecraft.player == null) {
			return;
		}
		if (server == null) {
			// A guest in a friend's world: the host's server applies it.
			if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.skycraft.net.SkyNet.Hurt.TYPE)) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.skycraft.net.SkyNet.Hurt(kind, skyrimDamage, attacker, flags));
			}
			return;
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				SkyCombat.hurtPlayer(player, kind, skyrimDamage, attacker, flags);
			}
		});
	}

	private static void key(Minecraft minecraft, long handle, int scancode, boolean down) {
		if (scancode <= 0 || scancode >= KEYS.length) {
			return;
		}
		boolean wasDown = KEYS[scancode];
		KEYS[scancode] = down;
		updateModifiers();
		int action = down ? (wasDown ? -1 : 1) : 0; // -1 = repeat
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
		minecraft.keyboardHandler.keyPress(handle, action, new KeyEvent(scancode, keycode, modifiers));
	}

	private static void updateModifiers() {
		int m = 0;
		if (KEYS[225]) m |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) m |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) m |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) m |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) m |= 0x0100; // SDL_KMOD_LALT
		if (KEYS[230]) m |= 0x0200; // SDL_KMOD_RALT
		modifiers = m;
	}

	/** Lift every key and button we think is held (focus moved to Skyrim, link dropped, ...). */
	public static void releaseAll() {
		Minecraft minecraft = Minecraft.getInstance();
		long handle = minecraft.getWindow().handle();
		for (int sc = 0; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				KEYS[sc] = false;
				updateModifiers();
				minecraft.keyboardHandler.keyPress(handle, 0, new KeyEvent(sc, SDLKeyboard.SDL_GetKeyFromScancode(sc, (short) 0, true), modifiers));
			}
		}
		for (int button = 1; button < BUTTONS.length; button++) {
			if (BUTTONS[button]) {
				BUTTONS[button] = false;
				minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(button, 0), 0);
			}
		}
	}
}
