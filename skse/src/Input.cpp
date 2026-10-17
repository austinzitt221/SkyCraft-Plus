#include "Game.h"

#include <SimpleIni.h>

namespace skycraft
{
	namespace
	{
		// DirectInput scan code (what Skyrim reports) -> SDL scancode / USB HID usage (what MC uses).
		constexpr auto kDikToSdl = [] {
			std::array<std::uint16_t, 256> t{};
			t[0x01] = 41;  // Esc
			for (int i = 0; i < 9; ++i) t[0x02 + i] = static_cast<std::uint16_t>(30 + i);  // 1-9
			t[0x0B] = 39;  // 0
			t[0x0C] = 45, t[0x0D] = 46, t[0x0E] = 42, t[0x0F] = 43;  // - = Backspace Tab
			t[0x10] = 20, t[0x11] = 26, t[0x12] = 8, t[0x13] = 21, t[0x14] = 23;  // Q W E R T
			t[0x15] = 28, t[0x16] = 24, t[0x17] = 12, t[0x18] = 18, t[0x19] = 19;  // Y U I O P
			t[0x1A] = 47, t[0x1B] = 48, t[0x1C] = 40, t[0x1D] = 224;               // [ ] Enter LCtrl
			t[0x1E] = 4, t[0x1F] = 22, t[0x20] = 7, t[0x21] = 9, t[0x22] = 10;     // A S D F G
			t[0x23] = 11, t[0x24] = 13, t[0x25] = 14, t[0x26] = 15;                // H J K L
			t[0x27] = 51, t[0x28] = 52, t[0x29] = 53, t[0x2A] = 225, t[0x2B] = 49;  // ; ' ` LShift backslash
			t[0x2C] = 29, t[0x2D] = 27, t[0x2E] = 6, t[0x2F] = 25, t[0x30] = 5;    // Z X C V B
			t[0x31] = 17, t[0x32] = 16, t[0x33] = 54, t[0x34] = 55, t[0x35] = 56;  // N M , . /
			t[0x36] = 229, t[0x37] = 85, t[0x38] = 226, t[0x39] = 44, t[0x3A] = 57;  // RShift KP* LAlt Space Caps
			for (int i = 0; i < 10; ++i) t[0x3B + i] = static_cast<std::uint16_t>(58 + i);  // F1-F10
			t[0x45] = 83, t[0x46] = 71;                                             // NumLock ScrollLock
			t[0x47] = 95, t[0x48] = 96, t[0x49] = 97, t[0x4A] = 86;                 // KP7 KP8 KP9 KP-
			t[0x4B] = 92, t[0x4C] = 93, t[0x4D] = 94, t[0x4E] = 87;                 // KP4 KP5 KP6 KP+
			t[0x4F] = 89, t[0x50] = 90, t[0x51] = 91, t[0x52] = 98, t[0x53] = 99;   // KP1 KP2 KP3 KP0 KP.
			t[0x56] = 100, t[0x57] = 68, t[0x58] = 69;                              // OEM102 F11 F12
			t[0x9C] = 88, t[0x9D] = 228, t[0xB5] = 84, t[0xB7] = 70, t[0xB8] = 230;  // KPEnter RCtrl KP/ PrtSc RAlt
			t[0xC5] = 72, t[0xC7] = 74, t[0xC8] = 82, t[0xC9] = 75, t[0xCB] = 80;  // Pause Home Up PgUp Left
			t[0xCD] = 79, t[0xCF] = 77, t[0xD0] = 81, t[0xD1] = 78, t[0xD2] = 73;  // Right End Down PgDn Insert
			t[0xD3] = 76, t[0xDB] = 227, t[0xDC] = 231, t[0xDD] = 101;             // Delete LWin RWin Menu
			return t;
		}();

		// Keys Skyrim keeps while Minecraft drives the player. Everything else is Minecraft's.
		constexpr std::uint32_t kDikEscape = 0x01;   // Skyrim journal / system menu (or closes an MC screen)
		constexpr std::uint32_t kDikConsole = 0x29;  // Skyrim console
		constexpr std::uint32_t kDikG = 0x22;        // Skyrim activate: doors, NPCs, containers, items
		constexpr std::uint32_t kDikH = 0x23;        // Skyrim wait (T is Minecraft chat)
		constexpr std::uint32_t kDikJ = 0x24;        // Skyrim quest journal
		constexpr std::uint32_t kDikM = 0x32;        // Skyrim map
		constexpr std::uint32_t kDikO = 0x18;        // Minecraft pause / options menu (Esc is Skyrim's)
		constexpr std::uint32_t kDikF9 = 0x43;       // Skyrim quickload

		// SkyCraft+ : extra keys from sSkyrimMenuKeys in SkyCraft.ini (e.g. Tab, I, P) that reach
		// Skyrim's own menus (Tab menu, inventory, magic) instead of Minecraft.
		std::array<bool, 256> extraMenuKeys{};
		// SkyCraft+ : mode switch. sModeToggleKey: Minecraft mode <-> Skyrim mode (0: none).
		std::uint32_t modeToggleKey = 0;
		// SkyCraft+ : controller. bController: a gamepad plays Minecraft mode too (see Pad below).
		bool padEnabled = true;
		// SkyCraft+ : controller inventory snapping. bControllerSlots: in a screen with item slots the
		// D-pad and left stick jump slot to slot.
		bool padSlots = true;
		bool activateInView = true;  // SkyCraft+ : G with no Skyrim crosshair target

		// "Tab", "I", "F1", "0x0F" -> DirectInput scan code, or 0 if unknown.
		std::uint32_t DikFromName(std::string a_name)
		{
			std::ranges::transform(a_name, a_name.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
			if (a_name.size() == 1) {
				static constexpr std::string_view kRows[] = { "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM" };
				static constexpr std::uint32_t    kRowStart[] = { 0x10, 0x1E, 0x2C };
				for (std::size_t r = 0; r < 3; ++r) {
					if (const auto i = kRows[r].find(a_name[0]); i != std::string_view::npos) {
						return kRowStart[r] + static_cast<std::uint32_t>(i);
					}
				}
				if (a_name[0] >= '1' && a_name[0] <= '9') {
					return 0x02 + static_cast<std::uint32_t>(a_name[0] - '1');
				}
				if (a_name[0] == '0') {
					return 0x0B;
				}
				return 0;
			}
			if (a_name == "TAB") {
				return 0x0F;
			}
			if (a_name.size() <= 3 && a_name[0] == 'F') {
				const int n = std::atoi(a_name.c_str() + 1);
				if (n >= 1 && n <= 10) {
					return 0x3B + static_cast<std::uint32_t>(n - 1);
				}
				if (n == 11 || n == 12) {
					return 0x57 + static_cast<std::uint32_t>(n - 11);
				}
				return 0;
			}
			if (a_name.starts_with("0X")) {
				const auto v = std::strtoul(a_name.c_str() + 2, nullptr, 16);
				return v > 0 && v < 256 ? static_cast<std::uint32_t>(v) : 0;
			}
			return 0;
		}

		void LoadControls()
		{
			CSimpleIniA ini;
			ini.SetUnicode();
			ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
			const std::string list = ini.GetValue("Controls", "sSkyrimMenuKeys", "");
			std::string       shown;
			std::size_t       start = 0;
			while (start <= list.size()) {
				const auto  end = std::min(list.find(',', start), list.size());
				std::string name = list.substr(start, end - start);
				start = end + 1;
				name.erase(0, name.find_first_not_of(" \t"));
				name.erase(name.find_last_not_of(" \t") + 1);
				if (name.empty()) {
					continue;
				}
				const auto code = DikFromName(name);
				if (!code) {
					logger::warn("sSkyrimMenuKeys: unknown key '{}' (use a letter, digit, F1-F12, Tab, or a scan code like 0x0F)", name);
				} else if (code == kDikG || code == kDikH || code == kDikO) {
					logger::warn("sSkyrimMenuKeys: '{}' is already a SkyCraft key; ignored", name);
				} else {
					extraMenuKeys[code] = true;
					shown += shown.empty() ? name : ", " + name;
				}
			}
			if (!shown.empty()) {
				logger::info("keys that open Skyrim menus: Esc, ~, J, M, F9, {}", shown);
			}

			State().skyrimHandsOption = ini.GetBoolValue("Controls", "bSkyrimHands", false);
			logger::info("Skyrim hands (equip Skyrim gear in a Minecraft hand): {}", State().skyrimHandsOption ? "on" : "off");
			State().rideAsMinecraft = ini.GetBoolValue("Controls", "bRideAsMinecraft", true);  // SkyCraft+
			logger::info("riding Skyrim's horses: {}", State().rideAsMinecraft ? "as Minecraft's player" : "as Skyrim's");

			// SkyCraft+ : the Nether and the End.
			State().minecraftWorldsOption = ini.GetBoolValue("Worlds", "bMinecraftWorlds", true);
			State().waitingCellId = static_cast<std::uint32_t>(ini.GetLongValue("Worlds", "iWaitingCell", 0x6F3EB));
			logger::info("Minecraft's own worlds (Nether, End): {} (waiting cell {:08X})", State().minecraftWorldsOption ? "on" : "off",
				State().waitingCellId.load());

			// SkyCraft+ : Skyrim enemies in the Nether and the End's outer islands.
			auto formList = [&](const char* a_key, const char* a_default) {
				std::vector<RE::FormID> ids;
				const std::string list = ini.GetValue("Worlds", a_key, a_default);
				std::size_t       start = 0;
				while (start <= list.size()) {
					const auto  end = std::min(list.find(',', start), list.size());
					std::string item = list.substr(start, end - start);
					start = end + 1;
					item.erase(0, item.find_first_not_of(" \t"));
					item.erase(item.find_last_not_of(" \t") + 1);
					if (!item.empty()) {
						ids.push_back(static_cast<RE::FormID>(std::strtoul(item.c_str(), nullptr, 0)));
					}
				}
				return ids;
			};
			State().skyrimEnemiesOption = ini.GetBoolValue("Worlds", "bSkyrimEnemies", true);
			State().netherEnemies = formList("sNetherEnemies", "0x1E79B, 0x25D1A, 0x25D1B, 0x23AA6, 0x2D2D4, 0x23AB0, 0x33973");
			State().endEnemies = formList("sEndEnemies", "0x23AA7, 0x23ABD, 0x10FCE5, 0x10EC90, 0x10EC8F, 0x23AA8, 0x23AB3");
			State().enemyCap = static_cast<int>(ini.GetLongValue("Worlds", "iEnemyCap", 6));
			State().enemySeconds = static_cast<float>(ini.GetDoubleValue("Worlds", "fEnemySeconds", 12.0));
			State().structureMarkersOption = ini.GetBoolValue("Worlds", "bStructureMarkers", true);
			State().blocksBeforeEffects = ini.GetBoolValue("Worlds", "bBlocksBeforeEffects", true);
			State().noAmbientOcclusionAway = ini.GetBoolValue("Worlds", "bNoAmbientOcclusionAway", true);
			State().hideWorldDeepOption = ini.GetBoolValue("Worlds", "bHideWorldDeep", true);
			State().maxHitHealth = static_cast<float>(ini.GetDoubleValue("Combat", "fMaxHitHealth", 10.0));
			State().mcDamageBase = std::max(0.0f, static_cast<float>(ini.GetDoubleValue("Combat", "fMinecraftDamage", 2.5)));
			State().mcDamagePerLevel = std::max(0.0f, static_cast<float>(ini.GetDoubleValue("Combat", "fMinecraftDamagePerLevel", 0.125)));
			State().skyrimVsMinecraft = std::max(0.0f, static_cast<float>(ini.GetDoubleValue("Combat", "fSkyrimWeaponsVsMinecraft", 1.5)));
			State().minecraftHitsObjects = ini.GetBoolValue("Combat", "bMinecraftHitsObjects", true);
			State().enderDragonSoul = ini.GetBoolValue("Worlds", "bEnderDragonSoul", true);
			logger::info("the Ender Dragon gives a dragon soul: {}", State().enderDragonSoul ? "on" : "off");
			logger::info("damage: Minecraft hits on Skyrim's actors x({:.2f} + {:.3f} per level), Skyrim weapons on Minecraft's mobs x{:.2f}", State().mcDamageBase,
				State().mcDamagePerLevel, State().skyrimVsMinecraft);
			logger::info("Minecraft's structures on Skyrim's map: {}", State().structureMarkersOption ? "on" : "off");
			logger::info("Skyrim enemies in the Nether and the End: {} ({} and {} kinds, up to {}, about every {:.0f} s)",
				State().skyrimEnemiesOption ? "on" : "off", State().netherEnemies.size(), State().endEnemies.size(), State().enemyCap, State().enemySeconds);

			padEnabled = ini.GetBoolValue("Controls", "bController", true);
			logger::info("controller in Minecraft mode: {}", padEnabled ? "on (right stick click switches modes)" : "off");
			padSlots = ini.GetBoolValue("Controls", "bControllerSlots", true);
			logger::info("controller in Minecraft's inventories: {}", padSlots ? "jumps slot to slot (left stick, D-pad)" : "a free cursor");
			activateInView = ini.GetBoolValue("Controls", "bActivateInView", true);

			const std::string toggle = ini.GetValue("Controls", "sModeToggleKey", "");
			if (!toggle.empty()) {
				const auto code = DikFromName(toggle);
				if (!code) {
					logger::warn("sModeToggleKey: unknown key '{}'; no mode switch", toggle);
				} else if (code == kDikG || code == kDikH || code == kDikO || code == kDikEscape || code == kDikConsole || extraMenuKeys[code]) {
					logger::warn("sModeToggleKey: '{}' already has a job; no mode switch", toggle);
				} else {
					modeToggleKey = code;
					logger::info("mode switch key: {}", toggle);
				}
			}
		}

		bool IsSkyrimMenuKey(std::uint32_t a_code)
		{
			return a_code == kDikEscape || a_code == kDikConsole || a_code == kDikJ || a_code == kDikM || a_code == kDikF9 ||
			       (a_code < extraMenuKeys.size() && extraMenuKeys[a_code]);
		}

		float lookDx = 0.0f;
		float lookDy = 0.0f;

		// SkyCraft+ : Skyrim's opening. After the escape from the block Skyrim's crosshair found
		// nothing (no prompt) even facing Helgen Keep's door, and the way on was shut. With no target
		// of Skyrim's, G takes the nearest door, NPC, container, activator, furniture, plant or item
		// along Minecraft's look within reach. Setting: bActivateInView in [Controls].
		RE::TESObjectREFR* NearestActivatableInView(RE::PlayerCharacter* a_player)
		{
			proto::McState mc{};
			auto*          tes = RE::TES::GetSingleton();
			if (!tes || !Link::Get().ReadMcState(mc)) {
				return nullptr;
			}
			constexpr double kDeg = 3.14159265358979 / 180.0;
			const double     yaw = mc.yaw * kDeg, pitch = mc.pitch * kDeg;
			const double     lx = -std::sin(yaw) * std::cos(pitch), ly = -std::sin(pitch), lz = std::cos(yaw) * std::cos(pitch);
			const RE::NiPoint3 eye = McToSky(mc.eyeX, mc.eyeY, mc.eyeZ);
			RE::NiPoint3       dir = McToSky(mc.eyeX + lx, mc.eyeY + ly, mc.eyeZ + lz) - eye;
			if (dir.Length() < 1e-3f) {
				return nullptr;
			}
			dir /= dir.Length();
			constexpr float    kReach = 3.0f * float(proto::kUnitsPerBlock);
			RE::TESObjectREFR* best = nullptr;
			float              bestAlong = std::numeric_limits<float>::max();
			tes->ForEachReferenceInRange(a_player, kReach + 400.0f, [&](RE::TESObjectREFR* a_ref) {
				if (!a_ref || a_ref == a_player || a_ref->IsDisabled() || a_ref->IsDeleted() || !a_ref->Is3DLoaded()) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				auto* base = a_ref->GetBaseObject();
				if (!base) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				switch (base->GetFormType()) {
				case RE::FormType::Door:
				case RE::FormType::Container:
				case RE::FormType::Activator:
				case RE::FormType::TalkingActivator:
				case RE::FormType::Furniture:
				case RE::FormType::Flora:
				case RE::FormType::NPC:
					break;
				default:
					if (!base->IsInventoryObject()) {
						return RE::BSContainer::ForEachResult::kContinue;
					}
				}
				auto* root3D = a_ref->Get3D();
				if (!root3D) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				const auto&        bound = root3D->worldBound;
				const RE::NiPoint3 to = bound.center - eye;
				const float        along = to.Dot(dir);
				const RE::NiPoint3 side = to - dir * along;
				const float        radius = std::max(bound.radius, 20.0f);
				if (along < -radius || along - radius > kReach || side.Length() > radius * 0.8f + 15.0f || along >= bestAlong) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				best = a_ref;
				bestAlong = along;
				return RE::BSContainer::ForEachResult::kContinue;
			});
			return best;
		}

		// G on something Skyrim can activate (door, NPC, container, item, furniture) activates it in
		// Skyrim. Furniture (chairs, beds, crafting stations, pull-bar levers) hands the player to
		// Skyrim while it's used (Game.cpp, SkyrimTakeover).
		void ActivateSkyrimTarget()
		{
			auto* pick = RE::CrosshairPickData::GetSingleton();
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!pick || !player) {
				return;
			}
			auto target = pick->GetActiveTarget().get();
			if (!target || target.get() == player) {
				if (auto* inView = activateInView ? NearestActivatableInView(player) : nullptr) {
					inView->ActivateRef(player, 0, nullptr, 1, false);
					logger::info("activated {:08X} ({}): in view, Skyrim's crosshair had no target", inView->GetFormID(), inView->GetDisplayFullName());
					return;
				}
				logger::info("G: nothing to activate under the crosshair");
				return;
			}
			// SkyCraft+ : items off the floor and shelves. A target count of 0 took the item out of the
			// world and gave the player none of it; 1 is what Papyrus's Activate passes.
			auto* item = target->GetBaseObject();
			const bool pickup = item && item->IsInventoryObject();
			const auto before = pickup ? player->GetItemCount(item) : 0;
			const auto name = std::string(target->GetDisplayFullName());
			const auto id = target->GetFormID();
			target->ActivateRef(player, 0, nullptr, 1, false);
			if (pickup) {
				logger::info("activated {:08X} ({}): the player has {} (had {})", id, name, player->GetItemCount(item), before);
			} else {
				logger::info("activated {:08X} ({})", id, name);
			}
		}

		// SkyCraft+ : a right click with a Skyrim left hand goes to Minecraft when its crosshair is on
		// something used by hand (a chest, crafting table, door, villager...). Decided on the press
		// and kept until the release, for both the input sink and PlayerControls (whichever sees the
		// press first decides; the same event object within a few milliseconds is the same press).
		bool RightClickToMinecraft(RE::ButtonEvent* a_button)
		{
			static bool                  toMinecraft = false;
			static const RE::ButtonEvent* decidedFor = nullptr;
			static std::uint64_t         decidedAt = 0;
			if (a_button && a_button->IsDown()) {
				const auto now = GetTickCount64();
				if (a_button != decidedFor || now - decidedAt > 50) {
					decidedFor = a_button;
					decidedAt = now;
					toMinecraft = State().mcUseTarget;
				}
			}
			return toMinecraft;
		}

		// SkyCraft+ : Skyrim mode block use. Skyrim's own Activate (E by default, or whatever it's
		// bound to), pressed.
		bool IsActivatePress(RE::InputEvent* a_event)
		{
			if (a_event->GetEventType() != RE::INPUT_EVENT_TYPE::kButton) {
				return false;
			}
			auto* button = a_event->AsButtonEvent();
			if (!button->IsDown()) {
				return false;
			}
			auto* events = RE::UserEvents::GetSingleton();
			if (events && button->QUserEvent() == events->activate) {
				return true;
			}
			auto* controls = RE::ControlMap::GetSingleton();
			return controls && controls->GetMappedKey("Activate", button->GetDevice()) == button->GetIDCode();
		}

		// Skyrim mode: Activate on nothing Skyrim can activate uses the Minecraft block under the
		// crosshair instead (Minecraft's player looks the same way, see Game.cpp).
		void UseMinecraftBlockIfNothingElse()
		{
			auto* pick = RE::CrosshairPickData::GetSingleton();
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!pick || !player) {
				return;
			}
			if (auto target = pick->GetActiveTarget().get(); target && target.get() != player) {
				return;  // Skyrim activates that itself
			}
			Link::Get().PushInput(proto::kInUse, 0);
			logger::info("Skyrim mode: Activate on no Skyrim target; using the Minecraft block in view");
		}

		void OpenWaitMenu()
		{
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!player || !player->CanSleepWait(nullptr)) {
				return;  // CanSleepWait already showed Skyrim's reason (enemies nearby, ...)
			}
			if (auto* queue = RE::UIMessageQueue::GetSingleton()) {
				queue->AddMessage(RE::SleepWaitMenu::MENU_NAME, RE::UI_MESSAGE_TYPE::kShow, nullptr);
			}
		}

		// SkyCraft+ : controller. Skyrim reads the gamepad; in Minecraft mode its buttons and sticks
		// become the keys, mouse buttons, wheel and look Minecraft already gets from the keyboard and
		// mouse, laid out like console Minecraft:
		//   left stick move (click: sprint), right stick look, RT attack/break, LT use/place,
		//   A jump, B sneak, X Skyrim activate (G), Y inventory, LB/RB hotbar, LB+RB together Skyrim's
		//   shout, right stick click: mode switch, D-pad up Minecraft camera (F5), right Skyrim's
		//   menu (items, magic, map, skills), down Minecraft's pause menu, left drop; Start and Back
		//   stay Skyrim's (journal, wait).
		// In a Minecraft screen (inventory, chest, crafting) either stick moves the cursor: A clicks,
		// X right-clicks, Y moves a whole stack (shift-click), B closes, LB/RB scroll. SkyCraft+ : in
		// one with item slots the D-pad and left stick jump slot to slot; the right stick stays a cursor.
		namespace Pad
		{
			using K = RE::BSWin32GamepadDevice::Key;

			constexpr std::uint16_t kSdlW = 26, kSdlA = 4, kSdlS = 22, kSdlD = 7;
			constexpr std::uint16_t kSdlSpace = 44, kSdlShift = 225, kSdlCtrl = 224, kSdlQ = 20, kSdlE = 8, kSdlEsc = 41, kSdlF5 = 62;
			constexpr float         kMoveAt = 0.35f;        // stick past this: that way's key down
			constexpr float         kLookRate = 1200.0f;    // mouse counts a second at full tilt (~180 degrees/s at default sensitivity)
			constexpr float         kCursorRate = 1.1f;     // screen heights a second at full tilt

			float        left[2]{}, right[2]{};
			std::uint8_t moveHeld = 0;  // W A S D
			float        cursorRestX = 0.0f, cursorRestY = 0.0f;
			// SkyCraft+ : controller inventory snapping. The left stick's jump between slots.
			constexpr float kJumpAt = 0.5f, kJumpFirst = 0.35f, kJumpRepeat = 0.12f;
			int             jumpDir = 0;
			float           jumpWait = 0.0f;
			bool         wasRouting = false;

			struct Held
			{
				bool          mouse;
				std::uint16_t code;
			};
			// What each held button pressed in Minecraft, released with it whatever opened in between.
			std::unordered_map<std::uint32_t, Held> held;

			void Send(const Held& a_what, bool a_down)
			{
				Link::Get().PushInput(a_what.mouse ? proto::kInMouseButton : proto::kInKey, a_what.code, a_down ? 1 : 0);
				if (a_what.mouse && a_what.code == 1 && a_down && !State().mcScreenOpen) {
					Combat::MinecraftSwing();  // SkyCraft+ : Minecraft hits on Skyrim objects (RT)
				}
			}

			void Hold(std::uint32_t a_button, Held a_what)
			{
				Send(a_what, true);
				held[a_button] = a_what;
			}

			void Tap(Held a_what)
			{
				Send(a_what, true);
				Send(a_what, false);
			}

			void Scroll(int a_notches)
			{
				Link::Get().PushInput(proto::kInScroll, 0, a_notches * 120);
			}

			void OpenSkyrimMenu(const RE::BSFixedString& a_menu)
			{
				if (auto* queue = RE::UIMessageQueue::GetSingleton()) {
					queue->AddMessage(a_menu, RE::UI_MESSAGE_TYPE::kShow, nullptr);
				}
			}

			// LB and RB together shout in Skyrim: whichever went down second goes to Skyrim as its
			// Shout (held to charge, let go to shout) until it's let go. Each one still steps the
			// hotbar when pressed, one each way, so the hotbar ends where it was.
			std::uint32_t shoulders = 0;    // LB, RB down (as PlayerControls saw them)
			std::uint32_t shoutButton = 0;  // the one shouting, or 0

			bool IsShoutChord(RE::InputEvent* a_event)
			{
				if (a_event->GetEventType() != RE::INPUT_EVENT_TYPE::kButton || a_event->GetDevice() != RE::INPUT_DEVICE::kGamepad) {
					return false;
				}
				auto*      button = a_event->AsButtonEvent();
				const auto code = button->GetIDCode();
				if (code != K::kLeftShoulder && code != K::kRightShoulder) {
					return false;
				}
				if (button->IsDown()) {
					if (!shoutButton && (shoulders & ~code & (K::kLeftShoulder | K::kRightShoulder))) {
						shoutButton = code;
						logger::info("controller: LB+RB, shouting");
					}
					shoulders |= code;
				}
				const bool shout = shoutButton == code;
				if (button->IsUp()) {
					shoulders &= ~code;
					if (shout) {
						shoutButton = 0;
					}
				}
				return shout;
			}

			float Curve(float a_v)
			{
				return a_v * std::abs(a_v);  // fine aim near the middle, fast turns at the edge
			}

			/** A gamepad button, pressed or released, while Minecraft takes the input. */
			void Button(RE::ButtonEvent* a_button, bool a_down)
			{
				auto&      st = State();
				const auto code = a_button->GetIDCode();
				if (!a_down) {
					if (const auto it = held.find(code); it != held.end()) {
						Send(it->second, false);
						held.erase(it);
					}
					return;
				}
				if (st.mcScreenOpen) {
					switch (code) {
					case K::kA:
						Hold(code, { true, 1 });
						break;
					case K::kX:
						Hold(code, { true, 3 });
						break;
					case K::kY:
						Send({ false, kSdlShift }, true);
						Tap({ true, 1 });
						Send({ false, kSdlShift }, false);
						break;
					case K::kB:
						Tap({ false, kSdlEsc });
						break;
					case K::kLeftShoulder:
						Scroll(1);
						break;
					case K::kRightShoulder:
						Scroll(-1);
						break;
					// SkyCraft+ : controller inventory snapping. The D-pad jumps slot to slot.
					case K::kUp:
					case K::kDown:
					case K::kLeft:
					case K::kRight:
						if (st.mcSlotScreen && padSlots) {
							const int dx = code == K::kLeft ? -1 : code == K::kRight ? 1 : 0;
							const int dy = code == K::kUp ? -1 : code == K::kDown ? 1 : 0;
							Link::Get().PushInput(proto::kInSlotJump, 0, dx, dy);
						}
						break;
					default:
						break;
					}
					return;
				}
				switch (code) {
				case K::kA:
					Hold(code, { false, kSdlSpace });
					break;
				case K::kB:
					Hold(code, { false, kSdlShift });
					break;
				case K::kX:
					ActivateSkyrimTarget();
					break;
				case K::kY:
					Tap({ false, kSdlE });
					break;
				case K::kLeftThumb:
					Hold(code, { false, kSdlCtrl });
					break;
				case K::kRightTrigger:
					if (!st.rightHandSkyrim) {  // a Skyrim right hand attacks in Skyrim (PlayerControlsHook)
						Hold(code, { true, 1 });
					}
					break;
				case K::kLeftTrigger:
					if (!st.leftHandSkyrim || RightClickToMinecraft(a_button)) {
						Hold(code, { true, 3 });
					}
					break;
				case K::kLeftShoulder:
					Scroll(1);
					break;
				case K::kRightShoulder:
					Scroll(-1);
					break;
				case K::kUp:
					Tap({ false, kSdlF5 });
					break;
				case K::kRight:
					Input::ReleaseAll();
					held.clear();
					OpenSkyrimMenu(RE::TweenMenu::MENU_NAME);
					break;
				case K::kDown:
					Input::ReleaseAll();
					held.clear();
					Link::Get().PushInput(proto::kInOpenMenu, 0);
					break;
				case K::kLeft:
					Tap({ false, kSdlQ });
					break;
				default:
					break;  // Start, Back: Skyrim's (MenuControlsHook lets them through)
				}
			}

			void Stick(RE::ThumbstickEvent* a_stick)
			{
				float* s = a_stick->IsLeft() ? left : a_stick->IsRight() ? right : nullptr;
				if (s) {
					s[0] = a_stick->xValue;
					s[1] = a_stick->yValue;
				}
			}

			/** Once a frame (main thread): held sticks move, look and steer the cursor. */
			void Frame(float a_delta, float& a_lookDx, float& a_lookDy)
			{
				auto&      st = State();
				const bool routing = padEnabled && (st.puppeting || (st.skyrimDrives && st.mcScreenOpen)) && !Game::SkyrimMenuOpen();
				if (!routing) {
					shoulders = shoutButton = 0;
					if (wasRouting) {
						// Into Skyrim's hands or a menu: let go of everything held.
						for (int k = 0; k < 4; ++k) {
							if (moveHeld & (1 << k)) {
								Send({ false, std::array{ kSdlW, kSdlA, kSdlS, kSdlD }[k] }, false);
							}
						}
						for (const auto& [button, what] : held) {
							Send(what, false);
						}
						held.clear();
						moveHeld = 0;
						left[0] = left[1] = right[0] = right[1] = 0.0f;
					}
					wasRouting = false;
					return;
				}
				wasRouting = true;
				if (st.mcScreenOpen) {
					shoulders = shoutButton = 0;  // PlayerControls doesn't see the shoulders meanwhile
				}
				std::uint8_t want = 0;
				if (!st.mcScreenOpen) {
					want |= left[1] > kMoveAt ? 1 : 0;
					want |= left[0] < -kMoveAt ? 2 : 0;
					want |= left[1] < -kMoveAt ? 4 : 0;
					want |= left[0] > kMoveAt ? 8 : 0;
					a_lookDx += Curve(right[0]) * kLookRate * a_delta;
					a_lookDy -= Curve(right[1]) * kLookRate * a_delta * 0.75f;
				} else {
					// SkyCraft+ : controller inventory snapping. In a screen with slots the left stick jumps
					// slot to slot (held: again after a moment, then steadily); the right one stays a cursor
					// (buttons, tabs, the recipe book).
					const bool slots = st.mcSlotScreen && padSlots;
					if (slots) {
						int dx = 0, dy = 0;
						if (std::max(std::abs(left[0]), std::abs(left[1])) > kJumpAt) {
							if (std::abs(left[0]) >= std::abs(left[1])) {
								dx = left[0] > 0.0f ? 1 : -1;
							} else {
								dy = left[1] > 0.0f ? -1 : 1;  // (stick up is +y, the screen's up is -y)
							}
						}
						if (dx == 0 && dy == 0) {
							jumpDir = 0;
						} else if (const int dir = (dx + 2) * 4 + (dy + 2); dir != jumpDir) {
							jumpDir = dir;
							jumpWait = kJumpFirst;
							Link::Get().PushInput(proto::kInSlotJump, 0, dx, dy);
						} else if ((jumpWait -= a_delta) <= 0.0f) {
							jumpWait = kJumpRepeat;
							Link::Get().PushInput(proto::kInSlotJump, 0, dx, dy);
						}
					}
					const float vx = std::clamp((slots ? 0.0f : left[0]) + right[0], -1.0f, 1.0f), vy = std::clamp((slots ? 0.0f : left[1]) + right[1], -1.0f, 1.0f);
					if (vx != 0.0f || vy != 0.0f) {
						const float speed = kCursorRate * static_cast<float>(st.viewportH.load());
						cursorRestX += Curve(vx) * speed * a_delta;
						cursorRestY -= Curve(vy) * speed * a_delta;
						const int stepX = static_cast<int>(cursorRestX), stepY = static_cast<int>(cursorRestY);
						if (stepX != 0 || stepY != 0) {
							cursorRestX -= static_cast<float>(stepX);
							cursorRestY -= static_cast<float>(stepY);
							const int x = std::clamp(st.cursorX.load() + stepX, 0, st.viewportW.load() - 1);
							const int y = std::clamp(st.cursorY.load() + stepY, 0, st.viewportH.load() - 1);
							st.cursorX = x;
							st.cursorY = y;
							Link::Get().PushInput(proto::kInCursor, 0, x, y);
						}
					}
				}
				for (int k = 0; k < 4; ++k) {
					const bool on = want & (1 << k);
					if (on != bool(moveHeld & (1 << k))) {
						Send({ false, std::array{ kSdlW, kSdlA, kSdlS, kSdlD }[k] }, on);
					}
				}
				moveHeld = want;
			}

			bool IsModeToggle(RE::InputEvent* a_event)
			{
				return padEnabled && a_event->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && a_event->GetDevice() == RE::INPUT_DEVICE::kGamepad &&
				       a_event->AsButtonEvent()->GetIDCode() == K::kRightThumb;
			}
		}

		class InputSink final : public RE::BSTEventSink<RE::InputEvent*>
		{
		public:
			static InputSink* Get()
			{
				static InputSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>*) override
			{
				if (!a_event) {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto& st = State();
				auto& link = Link::Get();
				// Menus that pause the game stop the per-frame update, so check them here.
				const bool menuOpen = Game::SkyrimMenuOpen();
				if (menuOpen && !st.skyrimMenuOpen) {
					Input::ReleaseAll();
				}
				if (menuOpen) {
					st.skyrimMenuOpen = true;
				}
				// A Minecraft screen opened from Skyrim mode (a chest, a crafting table) takes the input
				// like it does in Minecraft mode.
				const bool route = (st.puppeting || (st.skyrimDrives && st.mcScreenOpen)) && !menuOpen;

				for (auto* e = *a_event; e; e = e->next) {
					if (st.skyrimMode && st.skyrimDrives && !menuOpen && !st.mcScreenOpen && IsActivatePress(e)) {
						UseMinecraftBlockIfNothingElse();
					}
					// The mode switch key works in both modes (Skyrim mode doesn't route), never
					// reaches Minecraft, and waits while a menu or Minecraft screen is open.
					if ((modeToggleKey && e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && e->GetDevice() == RE::INPUT_DEVICE::kKeyboard &&
							e->AsButtonEvent()->GetIDCode() == modeToggleKey) ||
						Pad::IsModeToggle(e)) {
						if (e->AsButtonEvent()->IsDown() && !menuOpen && !st.mcScreenOpen) {
							Game::ToggleSkyrimMode();
						}
						continue;
					}
					switch (e->GetEventType()) {
					case RE::INPUT_EVENT_TYPE::kMouseMove:
						{
							if (!route) {
								break;
							}
							auto* mm = e->AsMouseMoveEvent();
							if (st.mcScreenOpen) {
								const int x = std::clamp(st.cursorX.load() + mm->mouseInputX, 0, st.viewportW.load() - 1);
								const int y = std::clamp(st.cursorY.load() + mm->mouseInputY, 0, st.viewportH.load() - 1);
								st.cursorX = x;
								st.cursorY = y;
								link.PushInput(proto::kInCursor, 0, x, y);
							} else {
								lookDx += static_cast<float>(mm->mouseInputX);
								lookDy += static_cast<float>(mm->mouseInputY);
							}
							break;
						}
					case RE::INPUT_EVENT_TYPE::kButton:
						{
							auto* button = e->AsButtonEvent();
							const bool down = button->IsDown();
							const bool up = button->IsUp();
							if (!route || (!down && !up)) {
								break;
							}
							const auto code = button->GetIDCode();
							if (button->GetDevice() == RE::INPUT_DEVICE::kKeyboard) {
								// With a Minecraft screen up (chat, inventory, options) every key is
								// Minecraft's, so typing works and Esc closes the screen.
								if (!st.mcScreenOpen) {
									if (IsSkyrimMenuKey(code)) {
										break;  // MenuControls (see the hook below) opens Skyrim's menu
									}
									if (code == kDikG || code == kDikH || code == kDikO) {
										if (down) {
											if (code == kDikG) {
												ActivateSkyrimTarget();
											} else if (code == kDikH) {
												OpenWaitMenu();
											} else {
												Input::ReleaseAll();
												link.PushInput(proto::kInOpenMenu, 0);
											}
										}
										break;
									}
									// SkyCraft+ : Skyrim's Shout key (Z, or whatever it's bound to) shouts in
									// Skyrim (PlayerControlsHook lets it through).
									if (auto* events = RE::UserEvents::GetSingleton(); events && button->QUserEvent() == events->shout) {
										break;
									}
								}
								if (const auto sdl = kDikToSdl[code & 0xFF]) {
									link.PushInput(proto::kInKey, sdl, down ? 1 : 0);
								}
							} else if (button->GetDevice() == RE::INPUT_DEVICE::kGamepad) {
								if (padEnabled) {
									Pad::Button(button, down);
								}
							} else if (button->GetDevice() == RE::INPUT_DEVICE::kMouse) {
								if (code == 8 || code == 9) {
									if (down) {
										link.PushInput(proto::kInScroll, 0, code == 8 ? 120 : -120);
									}
									break;
								}
								// A hand Skyrim holds: its button is Skyrim's (PlayerControlsHook lets it through),
								// except right click on something Minecraft uses by hand (a chest, a villager).
								if (!st.mcScreenOpen && ((code == 0 && st.rightHandSkyrim) || (code == 1 && st.leftHandSkyrim && !RightClickToMinecraft(button)))) {
									break;
								}
								static constexpr std::uint16_t kButtons[8] = { 1, 3, 2, 4, 5, 0, 0, 0 };
								const std::uint16_t sdlButton = code < 8 ? kButtons[code] : std::uint16_t(0);
								if (sdlButton) {
									link.PushInput(proto::kInMouseButton, sdlButton, down ? 1 : 0);
									if (sdlButton == 1 && down && !st.mcScreenOpen) {
										Combat::MinecraftSwing();  // SkyCraft+ : Minecraft hits on Skyrim objects
									}
								}
							}
							break;
						}
					case RE::INPUT_EVENT_TYPE::kThumbstick:
						if (route && padEnabled) {
							Pad::Stick(e->AsThumbstickEvent());
						}
						break;
					case RE::INPUT_EVENT_TYPE::kChar:
						if (route && st.mcScreenOpen) {
							link.PushInput(proto::kInText, 0, static_cast<std::int32_t>(e->AsCharEvent()->keyCode));
						}
						break;
					default:
						break;
					}
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// MenuControls opens Skyrim's menus (Tab, I, J, M, Wait, quicksave, ...). While Minecraft
		// drives the player only Esc, the console, M (map), J (journal), F9 (quickload) and the
		// keys in sSkyrimMenuKeys reach it (F5 is Minecraft's camera); while an MC screen is open
		// nothing does.
		struct MenuControlsHook
		{
			static RE::BSEventNotifyControl thunk(RE::MenuControls* a_this, RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>* a_source)
			{
				auto& st = State();
				const bool mcScreenInSkyrimMode = st.skyrimDrives && st.mcScreenOpen;
				if (!a_event || !*a_event || !(st.puppeting || mcScreenInSkyrimMode) || st.skyrimMenuOpen || Game::SkyrimMenuOpen()) {
					return func(a_this, a_event, a_source);  // a Skyrim menu is up: all its input
				}
				std::vector<RE::InputEvent*> keep;
				std::vector<RE::InputEvent*> all;
				for (auto* e = *a_event; e; e = e->next) {
					all.push_back(e);
					if (st.mcScreenOpen) {
						continue;
					}
					if (e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && e->GetDevice() == RE::INPUT_DEVICE::kKeyboard) {
						if (IsSkyrimMenuKey(e->AsButtonEvent()->GetIDCode())) {
							keep.push_back(e);
						}
					}
					// SkyCraft+ : controller. Start (journal) and Back (wait) stay Skyrim's.
					if (padEnabled && e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && e->GetDevice() == RE::INPUT_DEVICE::kGamepad) {
						const auto code = e->AsButtonEvent()->GetIDCode();
						if (code == Pad::K::kStart || code == Pad::K::kBack) {
							keep.push_back(e);
						}
					}
				}
				if (keep.empty()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> savedNext;
				for (auto* e : all) {
					savedNext.push_back(e->next);
				}
				for (std::size_t i = 0; i < keep.size(); ++i) {
					keep[i]->next = i + 1 < keep.size() ? keep[i + 1] : nullptr;
				}
				RE::InputEvent* head = keep.front();
				const auto      result = func(a_this, &head, a_source);
				for (std::size_t i = 0; i < all.size(); ++i) {
					all[i]->next = savedNext[i];
				}
				return result;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// PlayerControls turns input into the Skyrim player's own actions: moving, looking,
		// attacking, jumping, sneaking, the camera. While Minecraft drives the player it gets none.
		// (Disabling Skyrim's player controls instead would also stop its NPCs fighting the player.)
		struct PlayerControlsHook
		{
			static RE::BSEventNotifyControl thunk(RE::PlayerControls* a_this, RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>* a_source)
			{
				auto& st = State();
				const bool mcScreenInSkyrimMode = st.skyrimDrives && st.mcScreenOpen;
				if ((!st.minecraftOwnsPlayer && !mcScreenInSkyrimMode) || Game::SkyrimMenuOpen()) {
					// SkyCraft+ : controller. In Skyrim mode the right stick click switches back to
					// Minecraft mode, not Skyrim's camera view.
					if (a_event && *a_event && !Game::SkyrimMenuOpen()) {
						bool toggle = false;
						for (auto* e = *a_event; e; e = e->next) {
							toggle = toggle || Pad::IsModeToggle(e);
						}
						if (toggle) {
							std::vector<RE::InputEvent*> all, keep;
							for (auto* e = *a_event; e; e = e->next) {
								all.push_back(e);
								if (!Pad::IsModeToggle(e)) {
									keep.push_back(e);
								}
							}
							if (keep.empty()) {
								return RE::BSEventNotifyControl::kContinue;
							}
							std::vector<RE::InputEvent*> savedNext;
							for (auto* e : all) {
								savedNext.push_back(e->next);
							}
							for (std::size_t i = 0; i < keep.size(); ++i) {
								keep[i]->next = i + 1 < keep.size() ? keep[i + 1] : nullptr;
							}
							RE::InputEvent* head = keep.front();
							const auto      result = func(a_this, &head, a_source);
							for (std::size_t i = 0; i < all.size(); ++i) {
								all[i]->next = savedNext[i];
							}
							return result;
						}
					}
					return func(a_this, a_event, a_source);
				}
				// SkyCraft+ : hand ownership. A hand Skyrim holds still attacks, blocks and casts in
				// Skyrim: its Attack/Block event gets through (Minecraft never sees that button), unless
				// right click went to something Minecraft uses by hand. Shouts always get through.
				auto* events = RE::UserEvents::GetSingleton();
				if (!a_event || !*a_event || !events || st.mcScreenOpen) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> keep;
				std::vector<RE::InputEvent*> all;
				std::vector<std::pair<RE::ButtonEvent*, RE::BSFixedString>> renamed;  // LB+RB: told to Skyrim as its Shout
				for (auto* e = *a_event; e; e = e->next) {
					all.push_back(e);
					if (e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton) {
						const auto& name = e->QUserEvent();
						// (A controller's shout button, RB, is Minecraft's hotbar; LB and RB together shout.)
						const bool pad = padEnabled && e->GetDevice() == RE::INPUT_DEVICE::kGamepad;
						if (pad && Pad::IsShoutChord(e)) {
							auto* button = e->AsButtonEvent();
							renamed.emplace_back(button, button->userEvent);
							button->userEvent = events->shout;
							keep.push_back(e);
							continue;
						}
						if ((st.rightHandSkyrim && name == events->rightAttack) || (name == events->shout && !pad) ||
							(st.leftHandSkyrim && name == events->leftAttack && !RightClickToMinecraft(e->AsButtonEvent()))) {
							keep.push_back(e);
						}
					}
				}
				if (keep.empty()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> savedNext;
				for (auto* e : all) {
					savedNext.push_back(e->next);
				}
				for (std::size_t i = 0; i < keep.size(); ++i) {
					keep[i]->next = i + 1 < keep.size() ? keep[i + 1] : nullptr;
				}
				RE::InputEvent* head = keep.front();
				const auto      result = func(a_this, &head, a_source);
				for (std::size_t i = 0; i < all.size(); ++i) {
					all[i]->next = savedNext[i];
				}
				for (auto& [button, name] : renamed) {
					button->userEvent = name;
				}
				return result;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	namespace Input
	{
		void Install()
		{
			LoadControls();
			if (auto* devices = RE::BSInputDeviceManager::GetSingleton()) {
				devices->AddEventSink(InputSink::Get());
			}
			REL::Relocation<std::uintptr_t> vtbl{ RE::VTABLE_MenuControls[0] };
			MenuControlsHook::func = vtbl.write_vfunc(0x1, MenuControlsHook::thunk);
			REL::Relocation<std::uintptr_t> playerVtbl{ RE::VTABLE_PlayerControls[0] };
			PlayerControlsHook::func = playerVtbl.write_vfunc(0x1, PlayerControlsHook::thunk);
			logger::info("input hooks installed");
		}

		void PadFrame(float a_delta)
		{
			Pad::Frame(a_delta, lookDx, lookDy);
		}

		void ConsumeLook(float& a_dx, float& a_dy)
		{
			a_dx = lookDx;
			a_dy = lookDy;
			lookDx = lookDy = 0.0f;
		}

		void ReleaseAll()
		{
			Link::Get().PushInput(proto::kInReleaseAll, 0);
		}

		void SetActivatePromptKey(bool a_minecraftControls)
		{
			static std::uint16_t savedKey = 0xFFFF;
			auto*                controls = RE::ControlMap::GetSingleton();
			auto*                context = controls ? controls->controlMap[RE::UserEvents::INPUT_CONTEXT_ID::kGameplay] : nullptr;
			if (!context) {
				return;
			}
			for (auto& mapping : context->deviceMappings[RE::INPUT_DEVICE::kKeyboard]) {
				if (mapping.eventID != "Activate") {
					continue;
				}
				if (a_minecraftControls && savedKey == 0xFFFF) {
					savedKey = mapping.inputKey;
					mapping.inputKey = static_cast<std::uint16_t>(kDikG);
				} else if (!a_minecraftControls && savedKey != 0xFFFF) {
					mapping.inputKey = savedKey;
					savedKey = 0xFFFF;
				}
			}
		}
	}
}
