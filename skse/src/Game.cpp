#include "Game.h"

#include "Collision.h"
#include "Dig.h"
#include "Perf.h"

namespace skycraft
{
	Runtime& State()
	{
		static Runtime runtime;
		return runtime;
	}

	namespace
	{
		constexpr float kRadToDeg = 57.2957795f;
		constexpr float kDegToRad = 0.0174532925f;
		constexpr float kSkyrimTeleportThreshold = 300.0f;  // units; bigger jumps are Skyrim moving the player

		// Everything below is only touched on the main thread (PlayerCharacter::Update).
		proto::McState mc{};
		bool           mcWasAlive = false;
		// Starts somewhere new each Skyrim run, so a Minecraft still acknowledging the last run's
		// teleport can't be taken for having arrived at this run's.
		std::uint32_t  teleportSeq = [] {
			LARGE_INTEGER t;
			::QueryPerformanceCounter(&t);
			return static_cast<std::uint32_t>(t.QuadPart) | 1u;
		}();
		float          holdMismatch = 0.0f;  // seconds Minecraft has been waiting away from Skyrim's player
		bool           teleportPending = true;
		// SkyCraft+ : the Nether and the End. After a save loads or Minecraft connects, the save's
		// place wins: Minecraft's player is brought back from a world of its own (kSkyBringBack).
		bool           homeCheck = true;
		float          bringingBack = 0.0f;  // seconds left to wait for Minecraft to come back
		// SkyCraft+ : a save made in the Nether or the End: Minecraft's world and place then (the SKSE
		// co-save), and seconds left for Minecraft's player to get back there after the save loads.
		std::uint32_t  savedWorld = 0;
		double         savedX = 0.0, savedY = 0.0, savedZ = 0.0;
		float          sendingAway = 0.0f;
		bool           savedCheck = false;  // the save just loaded hasn't been checked yet
		std::uint32_t  worldId = 0;
		std::uint32_t  epoch = 0;
		RE::NiPoint3   lastSetPos{};
		bool           haveLastSet = false;
		float          hudTimer = 0.0f;
		bool           hudRightHand = false, hudLeftHand = false;  // SkyCraft+ : the hands the HUD was last set for
		// SkyCraft+ : the last SkyState the per-frame update wrote, and when (see ReportPause).
		proto::SkyState            lastSky{};
		std::atomic<std::uint64_t> lastSkyTick{ 0 };
		float          savedGravity = -1.0f;  // Skyrim's controller gravity while Minecraft drives
		// Minecraft's physics ticks, on our clock. Minecraft ticks exactly every tickMs, but stamps a
		// tick only after that frame's network work and the tick itself (a few ms; more while terrain
		// streams in), and we see it on our next frame. So the tick times are locked to that exact
		// rhythm (the stamps' noise filtered out), and we render just far enough in the past that the
		// next tick has always arrived, measured from how late ticks really turn up: too little and
		// the player stops for a frame and then jumps; that happens whenever our frames get slow.
		struct Tick
		{
			proto::McState s;
			std::int64_t   at;     // when it happened (QPC), on the locked rhythm
			int            slots;  // ticks since the one we saw before (2+: we missed one)
		};
		std::deque<Tick>       tickHistory;
		std::int64_t           lastFrameQpc = 0;
		int                    stampOutliers = 0;
		double                 renderDelayMs = 10.0;  // how far in the past we render
		std::array<double, 40> tickDue{};             // last 2 s: how long each tick was already due when we first saw it, ms
		std::size_t            tickDueNext = 0;
		bool                   tickDueInit = false;
		// The third-person zoom we use: Minecraft's, eased back out after it pulls in.
		float                  zoom = 0.0f;
		std::uint32_t          zoomMode = 0;
		// Every 10 s while Minecraft drives: how smoothly its motion reached us.
		struct MotionStats
		{
			int    ticks = 0, frames = 0, lateFrames = 0, stampSamples = 0;
			double stampErrMs = 0.0, frameMsSum = 0.0, frameMsMax = 0.0;
			float  zoomTravel = 0.0f;
		} motion;
		float          motionLogTimer = 10.0f;
		// Third person: the point the camera orbits (Minecraft's eye) and how far out it sits, and
		// how far the camera Skyrim actually renders is off that orbit (diagnostics).
		std::mutex     orbitLock;
		RE::NiPoint3   orbitPivot{};
		float          orbitUnits = 0.0f;
		bool           orbitValid = false;
		float          orbitYawSpeed = 0.0f;  // degrees per second, this frame
		struct OrbitStats
		{
			int   frames = 0, turning = 0;
			float missSum = 0.0f, missMax = 0.0f, turnMissSum = 0.0f, staleSum = 0.0f, staleMax = 0.0f;
		} orbit;
		std::string    frameOrder;
		bool           frameOrderLogged = false;
		float          captureTimer = 8.0f;  // frame-capture diagnostic countdown
		// Per-frame interpolation details for the capture.
		double         dbgT = 0.0;
		std::int64_t   dbgTickQpc = 0;
		RE::NiPoint3   dbgFeet{};
		float          dbgLift = 0.0f, dbgSide = 0.0f;
		bool           dbgJumpTrigger = false;
		float          dbgSinceJumpCapture = 99.0f;
		int            dbgClimbTicks = 0;  // consecutive ticks walking uphill in third person
		const char*    dbgTriggerReason = "jump";
		// After loads/teleports Havok bodies stream in over a few frames; exporting collision
		// before then sends empty regions, so wait for the world to settle first.
		float          settleTimer = 2.0f;
		constexpr float kSettleSeconds = 1.5f;

		RE::NiPoint3      eyePos{};
		// Meshes of Skyrim's first-person model we hid for Minecraft's third-person camera.
		std::vector<RE::NiPointer<RE::BSGeometry>> hiddenFirstPerson;
		std::atomic<bool> eyeValid{ false };

		// Camera orientation straight from Minecraft's look angles (+ its bob tilt/roll), instead of
		// Skyrim's animated first-person camera bone (which sways with run/jump/land animations).
		RE::NiMatrix3 idealRot;         // what we render with
		RE::NiMatrix3 idealRotNoRoll;   // what Skyrim should produce from the angles we give it
		bool          idealValid = false;
		// Runtime discovery of Skyrim's camera-root axis convention before we take over:
		// for each column of Skyrim's matrix, which of (+/-) forward/up/right it is.
		std::array<std::array<int, 6>, 3> axisVotes{};   // [column][candidate]
		int                               axisSamples = 0;
		std::array<int, 3>                axisMap{ 0, 1, 2 };  // candidate index per column (0..5)
		bool               rotValidated = false;
		bool               rotRejected = false;
		float              devMax = 0.0f, devSum = 0.0f, dbgRotDev = 0.0f;
		int                devCount = 0;
		float              devLogTimer = 5.0f;

		RE::NiPoint3 Col(const RE::NiMatrix3& a_m, int a_c) { return { a_m.entry[0][a_c], a_m.entry[1][a_c], a_m.entry[2][a_c] }; }

		// Skyrim's first-person model (arms, quiver, weapons) stays where Skyrim's first-person
		// camera is; with Minecraft's third-person camera it would float in view (in front view,
		// right in the lens). Only its meshes are hidden, never its nodes: Skyrim's camera and
		// animation use those. Exactly the meshes hidden here are shown again afterwards.
		void HideFirstPersonMeshes(RE::PlayerCharacter* a_player, bool a_hide)
		{
			if (!a_hide) {
				for (auto& mesh : hiddenFirstPerson) {
					if (mesh && mesh->GetAppCulled()) {
						mesh->SetAppCulled(false);
					}
				}
				hiddenFirstPerson.clear();
				return;
			}
			auto* root = a_player->Get3D(true);
			if (!root) {
				return;
			}
			RE::BSVisit::TraverseScenegraphGeometries(root, [&](RE::BSGeometry* a_mesh) {
				if (!a_mesh->GetAppCulled()) {
					a_mesh->SetAppCulled(true);
					hiddenFirstPerson.emplace_back(a_mesh);
				}
				return RE::BSVisit::BSVisitControl::kContinue;
			});
		}

		// SkyCraft+ : riding as Minecraft's player. Skyrim's third-person body is hidden on the horse
		// (Minecraft's sits in the saddle instead). Like the first-person meshes above: only meshes,
		// never nodes (the seat is read from the pelvis), checked every frame as equipping rebuilds them.
		std::vector<RE::NiPointer<RE::BSGeometry>> hiddenRider;

		void HideRiderMeshes(RE::PlayerCharacter* a_player, bool a_hide)
		{
			if (!a_hide) {
				for (auto& mesh : hiddenRider) {
					if (mesh && mesh->GetAppCulled()) {
						mesh->SetAppCulled(false);
					}
				}
				hiddenRider.clear();
				return;
			}
			auto* root = a_player->Get3D(false);
			if (!root) {
				return;
			}
			RE::BSVisit::TraverseScenegraphGeometries(root, [&](RE::BSGeometry* a_mesh) {
				if (!a_mesh->GetAppCulled()) {
					a_mesh->SetAppCulled(true);
					hiddenRider.emplace_back(a_mesh);
				}
				return RE::BSVisit::BSVisitControl::kContinue;
			});
		}

		float AngleDeg(RE::NiPoint3 a_a, RE::NiPoint3 a_b)
		{
			const float la = a_a.Length(), lb = a_b.Length();
			if (la < 1e-6f || lb < 1e-6f) {
				return 180.0f;
			}
			return std::acos(std::clamp(a_a.Dot(a_b) / (la * lb), -1.0f, 1.0f)) * kRadToDeg;
		}

		// NiCamera convention: column 0 = view direction, 1 = up, 2 = right. Skyrim: X east,
		// Y north, Z up; heading clockwise from north; positive pitch looks down.
		RE::NiMatrix3 BuildCameraRotation(float a_heading, float a_pitch, float a_roll)
		{
			const float sh = std::sin(a_heading), ch = std::cos(a_heading);
			const float sp = std::sin(a_pitch), cp = std::cos(a_pitch);
			RE::NiPoint3 f{ sh * cp, ch * cp, -sp };
			RE::NiPoint3 r{ ch, -sh, 0.0f };
			RE::NiPoint3 u = r.Cross(f);
			if (a_roll != 0.0f) {
				const float cr = std::cos(a_roll), sr = std::sin(a_roll);
				const RE::NiPoint3 u2 = u * cr + r * sr;
				const RE::NiPoint3 r2 = r * cr - u * sr;
				u = u2;
				r = r2;
			}
			RE::NiMatrix3 m;
			for (int i = 0; i < 3; ++i) {
				const float fv[3] = { f.x, f.y, f.z }, uv[3] = { u.x, u.y, u.z }, rv[3] = { r.x, r.y, r.z };
				m.entry[i][0] = fv[i];
				m.entry[i][1] = uv[i];
				m.entry[i][2] = rv[i];
			}
			return m;
		}

		// Turns the camera root to the Minecraft look (once Skyrim's axis convention is known).
		void ApplyLookRotation(RE::NiAVObject* a_root)
		{
			if (!rotValidated) {
				return;
			}
			const RE::NiPoint3 fr = Col(idealRot, 0), ur = Col(idealRot, 1), rr = Col(idealRot, 2);
			const std::array<RE::NiPoint3, 6> rolled{ fr, fr * -1.0f, ur, ur * -1.0f, rr, rr * -1.0f };
			RE::NiMatrix3 m;
			for (int c = 0; c < 3; ++c) {
				const auto& v = rolled[axisMap[c]];
				m.entry[0][c] = v.x;
				m.entry[1][c] = v.y;
				m.entry[2][c] = v.z;
			}
			a_root->local.rotate = m;
			a_root->world.rotate = m;
		}

		// Puts the camera anchor and the sky at the eye (the third-person camera position).
		void PinCameraAndSky()
		{
			auto* camera = RE::PlayerCamera::GetSingleton();
			if (!camera || !camera->cameraRoot) {
				return;
			}
			auto* root = camera->cameraRoot.get();
			const bool identityParent = !root->parent || root->parent->world.translate.Length() < 0.001f;
			if (identityParent) {
				root->local.translate = eyePos;
			}
			root->world.translate = eyePos;
			camera->GetRuntimeData2().pos = eyePos;
			if (auto* sky = RE::Sky::GetSingleton(); sky && sky->root) {
				sky->root->local.translate = eyePos;
				sky->root->world.translate = eyePos;
			}
		}

		// Skyrim FOV saved while Minecraft drives the camera.
		bool  fovSaved = false;
		float savedWorldFov = 0.0f;
		float savedFirstPersonFov = 0.0f;
		float cameraCheckTimer = 3.0f;
		std::atomic<bool> cameraCheckPending{ false };

		// Skyrim's FOV setting is the horizontal FOV of a 4:3 view; Minecraft's is vertical.
		float McFovToSkyrim(float a_mcVerticalDeg)
		{
			const float half = std::clamp(a_mcVerticalDeg, 10.0f, 170.0f) * 0.5f * kDegToRad;
			return 2.0f * std::atan(std::tan(half) * (4.0f / 3.0f)) * kRadToDeg;
		}

		void ApplyMcFov(RE::PlayerCamera* a_camera, float a_mcFov)
		{
			auto& data = a_camera->GetRuntimeData2();
			if (!fovSaved) {
				savedWorldFov = data.worldFOV;
				savedFirstPersonFov = data.firstPersonFOV;
				fovSaved = true;
				logger::info("camera FOV {} -> Minecraft {} (vertical {})", savedWorldFov, McFovToSkyrim(a_mcFov), a_mcFov);
			}
			data.worldFOV = McFovToSkyrim(a_mcFov);
		}

		void RestoreFov(RE::PlayerCamera* a_camera)
		{
			if (fovSaved && a_camera) {
				auto& data = a_camera->GetRuntimeData2();
				data.worldFOV = savedWorldFov;
				data.firstPersonFOV = savedFirstPersonFov;
				fovSaved = false;
			}
		}

		bool AnyBlockingMenuOpen(RE::UI* a_ui)
		{
			for (const auto& menu : a_ui->menuStack) {
				if (menu && menu->menuFlags.any(RE::UI_MENU_FLAGS::kPausesGame, RE::UI_MENU_FLAGS::kUsesCursor)) {
					return true;
				}
			}
			return false;
		}

		// Skyrim's NPCs only fight a player whose movement controls are on: their per-frame combat
		// update (AE ID 38565) is skipped while the player's are off, as in cutscenes. Saves keep
		// controls that were off when saving (older SkyCraft builds turned them off), so turn them
		// back on while Minecraft drives the player. Its input still never reaches Skyrim's player
		// (see Input.cpp's PlayerControls hook).
		void EnsureControlsEnabled()
		{
			auto* controls = RE::ControlMap::GetSingleton();
			if (!controls) {
				return;
			}
			using F = RE::ControlMap::UEFlag;
			constexpr auto kWanted = static_cast<std::uint32_t>(F::kMovement) | static_cast<std::uint32_t>(F::kLooking) |
			                         static_cast<std::uint32_t>(F::kActivate) | static_cast<std::uint32_t>(F::kFighting) |
			                         static_cast<std::uint32_t>(F::kSneaking) | static_cast<std::uint32_t>(F::kPOVSwitch) |
			                         static_cast<std::uint32_t>(F::kWheelZoom) | static_cast<std::uint32_t>(F::kJumping) |
			                         static_cast<std::uint32_t>(F::kVATS);
			const auto current = controls->GetRuntimeData().enabledControls.underlying();
			if ((current & kWanted) == kWanted) {
				return;
			}
			static std::uint32_t lastReported = 0xFFFFFFFF;
			if (current != lastReported) {
				lastReported = current;
				logger::info("Skyrim's player controls were off ({:#x}); turning them back on so NPCs fight", current);
			}
			controls->ToggleControls(static_cast<F>(kWanted & ~current), true, false);
		}

		// Skyrim's water surface over the block columns around Minecraft's player, so Minecraft
		// swims, floats and drowns in Skyrim's lakes and rivers.
		void WriteWaterGrid(RE::PlayerCharacter* a_player, const McVec& a_centre, std::uint32_t a_worldId)
		{
			auto* tes = RE::TES::GetSingleton();
			auto* cell = a_player->GetParentCell();
			if (!tes || !cell) {
				return;
			}
			static proto::WaterGrid grid{};
			constexpr int kSize = int(proto::kWaterGridSize);
			grid.originX = int(std::floor(a_centre.x)) - kSize / 2;
			grid.originZ = int(std::floor(a_centre.z)) - kSize / 2;
			grid.worldId = a_worldId;
			const float probeZ = float(a_centre.y * proto::kUnitsPerBlock);
			for (int dz = 0; dz < kSize; ++dz) {
				for (int dx = 0; dx < kSize; ++dx) {
					auto pos = McToSky(grid.originX + dx + 0.5, a_centre.y, grid.originZ + dz + 0.5);
					pos.z = probeZ;
					// The cell under that column (the player's own inside); TES::GetWaterHeight isn't
					// in 1.7.104's address library, the cell's own lookup is.
					auto* columnCell = cell->IsInteriorCell() ? cell : tes->GetCell(pos);
					float h = -std::numeric_limits<float>::max();
					if (columnCell) {
						columnCell->GetWaterHeight(pos, h);
					}
					// No water reads as a huge negative height (or NaN).
					grid.surface[dz * kSize + dx] = (std::isfinite(h) && h > -1.0e6f) ? float(h / proto::kUnitsPerBlock) : proto::kNoWater;
				}
			}
			Link::Get().WriteWaterGrid(grid);
		}

		// Hide Skyrim's HUD except the compass, the activation prompt and the enemy health bar
		// (Minecraft's HUD shows the player's own health, hunger and hotbar).
		// a_skyrimHands (SkyCraft+ : hand ownership): a hand Skyrim holds needs its magicka, stamina,
		// charge meters and arrow count; health stays Minecraft's hearts.
		void HideHud(RE::UI* a_ui, bool a_hide, bool a_skyrimHands)
		{
			auto hud = a_ui->GetMenu(RE::HUDMenu::MENU_NAME);
			if (!hud || !hud->uiMovie) {
				return;
			}
			static constexpr const char* kElements[] = {
				"_root.HUDMovieBaseInstance.Health",
				"_root.HUDMovieBaseInstance.Magica",
				"_root.HUDMovieBaseInstance.Stamina",
				"_root.HUDMovieBaseInstance.LeftChargeMeter",
				"_root.HUDMovieBaseInstance.RightChargeMeter",
				"_root.HUDMovieBaseInstance.ArrowInfoInstance",
			};
			for (const auto* path : kElements) {
				const bool         health = path == kElements[0];
				const RE::GFxValue visible(!a_hide || (a_skyrimHands && !health));
				hud->uiMovie->SetVariable((std::string(path) + "._visible").c_str(), visible);
			}
		}

		// Skyrim's crosshair stays gone while Minecraft drives (Minecraft's own is drawn instead).
		// Every frame, and see-through as well as hidden: Skyrim's HUD shows it again whenever it
		// updates it (menus closing, targets changing). The stealth eye shows only while sneaking.
		void HideCrosshair(RE::UI* a_ui, bool a_hide, bool a_sneaking)
		{
			auto hud = a_ui->GetMenu(RE::HUDMenu::MENU_NAME);
			if (!hud || !hud->uiMovie) {
				return;
			}
			static bool hidden = false;
			if (!a_hide && !hidden) {
				return;
			}
			static constexpr const char* kCrosshair[] = {
				"_root.HUDMovieBaseInstance.CrosshairInstance",
				"_root.HUDMovieBaseInstance.CrosshairAlert",
			};
			// SkyCraft+ : Skyrim's opening. From the escape off the block on, Skyrim's crosshair showed
			// over Minecraft's: its HUD shows it again between our frames. Its scripts set the
			// crosshair's visibility and alpha but not its size, so it's shrunk to nothing as well
			// (and given back the size it had).
			static double scales[2][2] = { { 100.0, 100.0 }, { 100.0, 100.0 } };
			static int    reshown = 0;
			for (int i = 0; i < 2; ++i) {
				const std::string path(kCrosshair[i]);
				RE::GFxValue      value;
				if (a_hide && !hidden) {
					for (int axis = 0; axis < 2; ++axis) {
						if (hud->uiMovie->GetVariable(&value, (path + (axis ? "._yscale" : "._xscale")).c_str()) && value.IsNumber() && value.GetNumber() > 0.0) {
							scales[i][axis] = value.GetNumber();
						}
					}
				} else if (a_hide && hud->uiMovie->GetVariable(&value, (path + "._visible").c_str()) && value.IsBool() && value.GetBool() && reshown++ < 10) {
					logger::info("Skyrim's HUD showed its crosshair again ({})", path);
				}
			}
			hidden = a_hide;
			const RE::GFxValue visible(!a_hide), alpha(a_hide ? 0.0 : 100.0);
			for (int i = 0; i < 2; ++i) {
				const std::string path(kCrosshair[i]);
				hud->uiMovie->SetVariable((path + "._visible").c_str(), visible);
				hud->uiMovie->SetVariable((path + "._alpha").c_str(), alpha);
				hud->uiMovie->SetVariable((path + "._xscale").c_str(), RE::GFxValue(a_hide ? 0.0 : scales[i][0]));
				hud->uiMovie->SetVariable((path + "._yscale").c_str(), RE::GFxValue(a_hide ? 0.0 : scales[i][1]));
			}
			hud->uiMovie->SetVariable("_root.HUDMovieBaseInstance.StealthMeterInstance._visible", RE::GFxValue(!a_hide || a_sneaking));
		}

		// SkyCraft+ : Whirlwind Sprint. Skyrim never shouts it while Minecraft drives the player (no
		// shout came, not even with Skyrim's own controls: tested), and its dash would be Skyrim moving
		// the player, which Minecraft undoes: the Greybeards' gate couldn't be passed in time. Here
		// the Shout key with it the voice's does it, as Skyrim would: held, a word more as each
		// charges, up to the words unlocked; let go (or all of them charged), the shout's spell is
		// cast, its recovery starts, and Minecraft dashes the player the way they look (kInDash).
		struct Whirlwind
		{
			std::uint64_t pressAt = 0;  // GetTickCount64; 0: not held
			int           words = 0;    // unlocked
		} whirlwind;

		RE::TESShout* WhirlwindSprint(RE::PlayerCharacter* a_player)
		{
			constexpr RE::FormID kWhirlwindSprint = 0x2F7BA;  // Skyrim.esm's shout
			auto*                voice = a_player->GetActorRuntimeData().selectedPower;
			auto*                shout = voice ? voice->As<RE::TESShout>() : nullptr;
			const char*          name = shout ? shout->GetName() : nullptr;
			return shout && (shout->GetFormID() == kWhirlwindSprint || (name && std::string_view(name).find("Whirlwind") != std::string_view::npos)) ? shout : nullptr;
		}

		void ShoutWhirlwind(RE::PlayerCharacter* a_player, RE::TESShout* a_shout, int a_words)
		{
			whirlwind.pressAt = 0;
			const auto& variation = a_shout->variations[a_words - 1];
			if (variation.spell) {
				if (auto* caster = a_player->GetMagicCaster(RE::MagicSystem::CastingSource::kInstant)) {
					caster->CastSpellImmediate(variation.spell, false, a_player, 1.0f, false, 0.0f, a_player);
				}
			}
			a_player->GetActorRuntimeData().voiceTimer = variation.recoveryTime;
			// (Skyrim's dash: some eight blocks for one word, twenty for all three, in a blink.)
			constexpr int kBlocks[3] = { 8, 13, 20 };
			constexpr int kTicks[3] = { 5, 7, 9 };
			Link::Get().PushInput(proto::kInDash, static_cast<std::uint16_t>(a_words), kBlocks[a_words - 1] * 100, kTicks[a_words - 1]);
			logger::info("Whirlwind Sprint ({} word{}): Minecraft dashes the player {} blocks (recovery {:.0f} s)", a_words, a_words > 1 ? "s" : "", kBlocks[a_words - 1],
				variation.recoveryTime);
		}

		int WhirlwindCharged(std::uint64_t a_held) { return a_held < 300 ? 1 : a_held < 700 ? 2 : 3; }

		// The Shout key went down or up (Input.cpp).
		void WhirlwindKey(bool a_down)
		{
			auto* player = RE::PlayerCharacter::GetSingleton();
			auto* shout = player && State().puppeting ? WhirlwindSprint(player) : nullptr;
			const auto now = GetTickCount64();
			if (!a_down) {
				if (whirlwind.pressAt != 0 && shout) {
					ShoutWhirlwind(player, shout, std::min(whirlwind.words, WhirlwindCharged(now - whirlwind.pressAt)));
				}
				whirlwind.pressAt = 0;
				return;
			}
			if (!shout || whirlwind.pressAt != 0) {
				return;
			}
			int words = 0;
			for (const auto& variation : shout->variations) {
				words += variation.word && (variation.word->formFlags & RE::TESForm::RecordFlags::kUnlocked) != 0 ? 1 : 0;
			}
			const float recovering = player->GetActorRuntimeData().voiceTimer;
			if (words == 0 || recovering > 0.0f) {
				logger::info("Whirlwind Sprint: {}", words == 0 ? "no word of it unlocked yet (Skyrim wouldn't shout it either)" : std::format("still recovering ({:.0f} s)", recovering));
				return;
			}
			whirlwind = { now, words };
		}

		// Each frame: charged to the last word unlocked while held, it goes (as Skyrim's does).
		void WhirlwindCharge(RE::PlayerCharacter* a_player)
		{
			if (whirlwind.pressAt == 0) {
				return;
			}
			auto* shout = State().puppeting ? WhirlwindSprint(a_player) : nullptr;
			if (!shout) {
				whirlwind.pressAt = 0;
			} else if (WhirlwindCharged(GetTickCount64() - whirlwind.pressAt) >= whirlwind.words) {
				ShoutWhirlwind(a_player, shout, whirlwind.words);
			}
		}

		// Skyrim takes the player for its own animations: sitting, crafting stations, beds, pull-bar
		// levers and other furniture (it walks the player into place, plays the animation and uses its
		// furniture camera), riding a horse, kill moves, and scripted scenes that drive the player.
		// Minecraft lets go meanwhile (Skyrim gets the controls and the camera) and picks the player
		// up again from wherever Skyrim leaves them.
		const char* SkyrimTakeover(RE::PlayerCharacter* a_player)
		{
			if (State().skyrimMode) {
				return "Skyrim mode";
			}
			if (a_player->AsActorState()->GetSitSleepState() != RE::SIT_SLEEP_STATE::kNormal) {
				return "furniture";
			}
			if (a_player->IsOnMount()) {
				return "riding";
			}
			if (a_player->IsInKillMove()) {
				return "kill move";
			}
			// SkyCraft+ : Skyrim's opening. Its scenes (the cart ride, the walk to the block, the
			// execution) turn the player's movement off and place them; Minecraft turned the controls
			// back on (EnsureControlsEnabled) and walked the player out of the scene, which then got
			// stuck. While Unbound (MQ101) runs with movement off, Skyrim has the player.
			if (auto* controls = RE::ControlMap::GetSingleton(); controls && !controls->IsMovementControlsEnabled()) {
				static auto* opening = RE::TESForm::LookupByID<RE::TESQuest>(0x3372B);
				if (opening && opening->IsRunning()) {
					return "Skyrim's opening";
				}
			}
			// PlayerCharacter::SetAIDriven (AE 40586) sets bit 3 of this byte (decompiled, 1.7.104).
			if (REL::Module::IsAE() && (reinterpret_cast<const std::uint8_t*>(a_player)[0xBEA] & 8) != 0) {
				return "scripted scene";
			}
			if (auto* camera = RE::PlayerCamera::GetSingleton(); camera && camera->currentState) {
				switch (camera->currentState->id) {
				case RE::CameraState::kFurniture:
				case RE::CameraState::kAnimated:
				case RE::CameraState::kBleedout:
				case RE::CameraState::kDragon:
				case RE::CameraState::kMount:
				case RE::CameraState::kVATS:
					return "Skyrim's camera";
				default:
					break;
				}
			}
			return nullptr;
		}

		// SkyCraft+ : mode switch. Skyrim just handed the player back: take the look from its camera.
		bool skyrimLookPending = false;

		// Look direction (MC degrees) from the camera Skyrim rendered last: its world rotation's
		// columns are forward, up, right (Skyrim heading 0 = +Y north, clockwise).
		void SeedLookFromCamera(Runtime& a_st, bool a_log)
		{
			auto* camera = RE::Main::WorldRootCamera();
			if (!camera) {
				return;
			}
			const auto& r = camera->world.rotate;
			const float fx = r.entry[0][0], fy = r.entry[1][0], fz = r.entry[2][0];
			const float len = std::sqrt(fx * fx + fy * fy + fz * fz);
			if (len < 0.5f) {
				return;
			}
			const float yaw = HeadingToMcYaw(std::atan2(fx, fy));
			const float pitch = std::clamp(-std::asin(std::clamp(fz / len, -1.0f, 1.0f)) * kRadToDeg, -90.0f, 90.0f);
			if (a_log) {
				logger::info("look: body faces MC yaw {:.0f}, Skyrim's camera {:.0f} / pitch {:.0f}; using the camera", a_st.yaw, yaw, pitch);
			}
			a_st.yaw = std::fmod(yaw + 360.0f, 360.0f);
			a_st.pitch = pitch;
		}

		// SkyCraft+ : hand ownership. Which hands hold something equipped in Skyrim: a weapon, spell
		// or torch in the hand, a shield on the left arm; two-handed weapons, bows and crossbows take
		// both hands. Bare fists are nothing.
		void SkyrimHands(RE::PlayerCharacter* a_player, bool& a_right, bool& a_left)
		{
			constexpr RE::FormID kUnarmed = 0x1F4;
			auto*                right = a_player->GetEquippedObject(false);
			auto*                left = a_player->GetEquippedObject(true);
			a_right = right && right->GetFormID() != kUnarmed;
			a_left = (left && left->GetFormID() != kUnarmed) || a_player->GetWornArmor(RE::BGSBipedObjectForm::BipedObjectSlot::kShield);
			if (auto* weapon = right ? right->As<RE::TESObjectWEAP>() : nullptr;
				weapon && (weapon->IsTwoHandedSword() || weapon->IsTwoHandedAxe() || weapon->IsBow() || weapon->IsCrossbow())) {
				a_left = true;
			}
		}

		// SkyCraft+ : hand mixing. The last spell the player equipped (the same one again changes
		// nothing in the hand to watch for).
		struct SpellEquipSink : RE::BSTEventSink<RE::TESEquipEvent>
		{
			static SpellEquipSink* Get()
			{
				static SpellEquipSink sink;
				return &sink;
			}
			std::atomic<RE::FormID> spell{ 0 };
			RE::BSEventNotifyControl ProcessEvent(const RE::TESEquipEvent* a_event, RE::BSTEventSource<RE::TESEquipEvent>*) override
			{
				if (a_event && a_event->equipped && a_event->actor && a_event->actor->IsPlayerRef()) {
					if (auto* form = RE::TESForm::LookupByID(a_event->baseObject); form && form->Is(RE::FormType::Spell)) {
						spell = a_event->baseObject;
					}
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// SkyCraft+ : hand mixing. Takes a weapon, shield or torch out of a hand (a spell stays: it's
		// only no longer that hand's).
		void UnequipFromHand(RE::PlayerCharacter* a_player, RE::TESForm* a_held, bool a_left)
		{
			auto* object = a_held ? a_held->As<RE::TESBoundObject>() : nullptr;
			if (!object || a_held->Is(RE::FormType::Spell) || a_held->Is(RE::FormType::Scroll)) {
				return;
			}
			const RE::BGSEquipSlot* slot = nullptr;
			if (a_held->Is(RE::FormType::Weapon)) {
				if (auto* defaults = RE::BGSDefaultObjectManager::GetSingleton()) {
#pragma push_macro("GetObject")
#undef GetObject  // (Windows' GetObjectA)
					slot = defaults->GetObject<RE::BGSEquipSlot>(a_left ? RE::DEFAULT_OBJECTS::kLeftHandEquip : RE::DEFAULT_OBJECTS::kRightHandEquip);
#pragma pop_macro("GetObject")
				}
			}
			RE::ActorEquipManager::GetSingleton()->UnequipObject(a_player, object, nullptr, 1, slot);
			logger::info("hands: Minecraft took the {} hand: {} unequipped", a_left ? "left" : "right", a_held->GetName());
		}

		// SkyCraft+ : hand mixing (Minecraft pause menu's "Hands" button). A hand holding both a Skyrim
		// item and a Minecraft one goes to Minecraft's (1) or to the last picked (2); a_right and a_left
		// come in as SkyrimHands left them.
		void MixHands(RE::PlayerCharacter* a_player, bool& a_right, bool& a_left)
		{
			const int mode = static_cast<int>((mc.hands >> 24) & 3);
			if (mode == 0) {
				return;
			}
			constexpr RE::FormID kUnarmed = 0x1F4;
			auto*                rightHeld = a_player->GetEquippedObject(false);
			auto*                leftHeld = a_player->GetEquippedObject(true);
			RE::TESForm*         right = rightHeld && rightHeld->GetFormID() != kUnarmed ? rightHeld : nullptr;
			RE::TESForm*         left = leftHeld && leftHeld->GetFormID() != kUnarmed ? leftHeld : a_player->GetWornArmor(RE::BGSBipedObjectForm::BipedObjectSlot::kShield);
			auto*                weapon = right ? right->As<RE::TESObjectWEAP>() : nullptr;
			const bool           bothHands = weapon && (weapon->IsTwoHandedSword() || weapon->IsTwoHandedAxe() || weapon->IsBow() || weapon->IsCrossbow());
			if (mode == 1) {
				a_right = a_right && (mc.hands & 1) == 0;
				a_left = bothHands ? a_right : a_left && (mc.hands & 2) == 0;
				return;
			}
			static bool          started = false;
			static std::uint64_t lastFrame = 0;
			static std::uint8_t  rightSeen = 0, leftSeen = 0;
			static RE::TESForm*  rightWas = nullptr;
			static RE::TESForm*  leftWas = nullptr;
			static bool          rightTaken = false, leftTaken = false;
			static bool          watching = false;
			if (!watching) {
				if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
					events->AddEventSink<RE::TESEquipEvent>(SpellEquipSink::Get());
					watching = true;
				}
			}
			const auto rightSeq = static_cast<std::uint8_t>(mc.hands >> 8), leftSeq = static_cast<std::uint8_t>(mc.hands >> 16);
			if (!started || mc.frameCounter < lastFrame) {  // (Minecraft started again: its counts did too)
				started = true;
				rightSeen = rightSeq;
				leftSeen = leftSeq;
				rightWas = right;
				leftWas = left;
				SpellEquipSink::Get()->spell = 0;
			}
			lastFrame = mc.frameCounter;
			// Equipped in Skyrim: that hand is Skyrim's.
			if (right != rightWas) {
				rightTaken = rightTaken && !right;
				rightWas = right;
			}
			if (left != leftWas) {
				leftTaken = leftTaken && !left;
				leftWas = left;
			}
			if (const auto spell = SpellEquipSink::Get()->spell.exchange(0); spell != 0) {
				rightTaken = rightTaken && !(right && right->GetFormID() == spell);
				leftTaken = leftTaken && !(left && left->GetFormID() == spell);
			}
			// Switched in Minecraft (hotbar slot, off-hand swap): Minecraft's, and the Skyrim item there
			// is unequipped, so equipping it again brings it back.
			if (rightSeq != rightSeen) {
				rightSeen = rightSeq;
				if (right) {
					rightTaken = true;
					UnequipFromHand(a_player, right, false);
				}
			}
			if (leftSeq != leftSeen) {
				leftSeen = leftSeq;
				if (bothHands) {
					rightTaken = true;
					UnequipFromHand(a_player, right, false);
				} else if (left) {
					leftTaken = true;
					UnequipFromHand(a_player, left, true);
				}
			}
			a_right = a_right && !rightTaken;
			a_left = bothHands ? a_right : a_left && !leftTaken;
		}

		// SkyCraft+ : Skyrim weapons break blocks. The weapon's name or model path holds one of these
		// (any case).
		bool IsTool(RE::TESObjectWEAP* a_weapon, std::string_view a_word, std::string_view a_other)
		{
			auto has = [&](const char* a_text) {
				if (!a_text) {
					return false;
				}
				std::string text(a_text);
				std::ranges::transform(text, text.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
				return text.find(a_word) != std::string::npos || text.find(a_other) != std::string::npos;
			};
			return has(a_weapon->GetName()) || has(a_weapon->GetModel());
		}

		// SkyCraft+ : what a Skyrim-held hand holds, for the Minecraft body's stand-in in third person.
		std::uint8_t HandKind(RE::PlayerCharacter* a_player, bool a_left)
		{
			auto* held = a_player->GetEquippedObject(a_left);
			if (!held || held->GetFormID() == 0x1F4) {  // Unarmed
				return a_left && a_player->GetWornArmor(RE::BGSBipedObjectForm::BipedObjectSlot::kShield) ? proto::kHandShield : proto::kHandNone;
			}
			if (held->Is(RE::FormType::Spell) || held->Is(RE::FormType::Scroll)) {
				return proto::kHandSpell;
			}
			if (held->Is(RE::FormType::Light)) {
				return proto::kHandTorch;
			}
			auto* weapon = held->As<RE::TESObjectWEAP>();
			if (!weapon) {
				return proto::kHandNone;
			}
			using T = RE::WEAPON_TYPE;
			switch (weapon->GetWeaponType()) {
			case T::kOneHandSword:
				return proto::kHandSword;
			case T::kOneHandDagger:
				return proto::kHandDagger;
			case T::kOneHandAxe:
				// SkyCraft+ : Skyrim weapons break blocks. The pickaxes and the woodcutter's axe are war
				// axes to Skyrim; known by their name or model.
				if (IsTool(weapon, "pickaxe", "pick axe")) {
					return proto::kHandPickaxe;
				}
				if (IsTool(weapon, "woodcutter", "woodaxe")) {
					return proto::kHandWoodAxe;
				}
				return proto::kHandWarAxe;
			case T::kOneHandMace:
				return proto::kHandMace;
			case T::kTwoHandSword:
				return proto::kHandGreatsword;
			case T::kTwoHandAxe:
				return weapon->HasKeywordString("WeapTypeWarhammer") ? proto::kHandWarhammer : proto::kHandBattleaxe;
			case T::kBow:
				return proto::kHandBow;
			case T::kCrossbow:
				return proto::kHandCrossbow;
			case T::kStaff:
				return proto::kHandStaff;
			default:
				return proto::kHandNone;
			}
		}

		// SkyCraft+ : living world. Where the player is and the weather, for Minecraft's spawning:
		// interiors; settlements (the location or one it's in is a city, town, settlement, home,
		// inn, castle...: no monsters); dungeons (caves, crypts, ruins: monsters at any hour); rain
		// or snow.
		std::uint32_t PlaceFlags(RE::PlayerCharacter* a_player, RE::TESObjectCELL* a_cell)
		{
			static constexpr std::string_view kSettlement[] = { "LocTypeCity", "LocTypeTown", "LocTypeSettlement", "LocTypeHabitation",
				"LocTypeDwelling", "LocTypeInn", "LocTypeHouse", "LocTypePlayerHouse", "LocTypeCastle", "LocTypeJail", "LocTypeStore",
				"LocTypeTemple", "LocTypeFarm" };
			std::uint32_t flags = a_cell && a_cell->IsInteriorCell() ? proto::kPlaceInterior : 0u;
			if (!(flags & proto::kPlaceInterior)) {
				// Tamriel itself (0x3C) or a worldspace inside it (Whiterun, Solitude...: same coordinates).
				constexpr RE::FormID kTamriel = 0x3C;
				auto*                world = a_player->GetWorldspace();
				for (int depth = 0; world && depth < 8; world = world->parentWorld, ++depth) {
					if (world->GetFormID() == kTamriel) {
						flags |= proto::kPlaceTamriel;
						break;
					}
				}
			}
			auto*         location = a_player->GetCurrentLocation();
			if (location && location->HasKeywordString("LocTypeDungeon")) {
				flags |= proto::kPlaceDungeon;
			}
			for (int depth = 0; location && depth < 8; location = location->parentLoc, ++depth) {
				if (std::ranges::any_of(kSettlement, [&](std::string_view a_keyword) { return location->HasKeywordString(a_keyword); })) {
					flags |= proto::kPlaceSettlement;
				}
				if (location->HasKeywordString("LocTypeCity") || location->HasKeywordString("LocTypeTown")) {
					flags |= proto::kPlaceTown;
					break;
				}
			}
			if (auto* sky = RE::Sky::GetSingleton(); sky && sky->currentWeather && !(flags & proto::kPlaceInterior)) {
				if (sky->currentWeather->data.flags.any(RE::TESWeather::WeatherDataFlag::kRainy)) {
					flags |= proto::kPlaceRain;
				}
				if (sky->currentWeather->data.flags.any(RE::TESWeather::WeatherDataFlag::kSnow)) {
					flags |= proto::kPlaceSnow;
				}
			}
			return flags;
		}

		// SkyCraft+ : mode switch. Sets the Skyrim player's health to this share of its maximum.
		void SetHealthFraction(RE::PlayerCharacter* a_player, float a_fraction)
		{
			auto*       av = a_player->AsActorValueOwner();
			const float max = a_player->GetActorValueMax(RE::ActorValue::kHealth);
			const float cur = av->GetActorValue(RE::ActorValue::kHealth);
			const float target = std::max(1.0f, std::clamp(a_fraction, 0.0f, 1.0f) * max);
			if (target < cur) {
				av->DamageActorValue(RE::ActorValue::kHealth, cur - target);
			} else if (target > cur) {
				av->RestoreActorValue(RE::ActorValue::kHealth, target - cur);
			}
			logger::info("health: Skyrim takes Minecraft's {:.0f}% ({:.0f} -> {:.0f})", a_fraction * 100.0f, cur, target);
		}

		// Tells the player, in Skyrim's corner notifications, what Minecraft is up to while it isn't
		// connected: starting, waiting for the first-time Microsoft sign-in, or not set up at all.
		void ReportMinecraft(bool a_connected, bool a_inGame, float a_delta)
		{
			static float waited = 0.0f, nextNote = 3.0f;
			static bool  told = false, gaveUp = false, diagnosed = false;
			if (a_connected) {
				if (told) {
					RE::SendHUDMessage::ShowHUDMessage("SkyCraft: Minecraft is ready.");
				}
				waited = 0.0f;
				nextNote = 3.0f;
				told = gaveUp = diagnosed = false;
				return;
			}
			if (!a_inGame || gaveUp) {
				return;
			}
			waited += a_delta;
			if (waited < nextNote) {
				return;
			}
			const auto status = Launcher::GetStatus();
			const bool signIn = status == Launcher::Status::kSignIn;
			// A minute on and still nothing: say which of the ways it can be stuck this is.
			if (!diagnosed && waited > 60.0f && (status == Launcher::Status::kStarting || status == Launcher::Status::kRunning)) {
				diagnosed = true;
				const bool minecraft = Launcher::MinecraftRunning();
				const bool prism = Launcher::PrismRunning();
				logger::warn("Minecraft: not connected after a minute (Minecraft running: {}, Prism Launcher running: {})", minecraft, prism);
				RE::SendHUDMessage::ShowHUDMessage(
					minecraft ? "SkyCraft: Minecraft is running but not responding. Please report it with SkyCraft.log and Minecraft's latest.log." :
					prism     ? "SkyCraft: Minecraft hasn't started yet. Alt-Tab to Prism Launcher: it may still be downloading, or need a sign-in, or show an error." :
								"SkyCraft: Minecraft closed before connecting. Its log is AppData\\Local\\SkyCraft\\Prism\\instances\\SkyCraft\\.minecraft\\logs\\latest.log");
				told = true;
				nextNote = waited + 120.0f;
				return;
			}
			if (waited > (signIn ? 600.0f : 180.0f)) {
				RE::SendHUDMessage::ShowHUDMessage("SkyCraft: Minecraft still hasn't connected. See SkyCraft.log.");
				gaveUp = true;
				return;
			}
			const char* note = nullptr;
			switch (status) {
			case Launcher::Status::kSignIn:
				note = "SkyCraft: setting up Minecraft. Alt-Tab to the Prism window and sign in with your Microsoft account.";
				break;
			case Launcher::Status::kStarting:
			case Launcher::Status::kRunning:
				note = told ? nullptr : "SkyCraft: starting Minecraft...";
				break;
			case Launcher::Status::kNoLauncher:
				note = "SkyCraft: Minecraft isn't set up. See Data/SKSE/Plugins/SkyCraft.ini.";
				gaveUp = true;
				break;
			case Launcher::Status::kFailed:
				note = "SkyCraft: couldn't start Minecraft. See SkyCraft.log.";
				gaveUp = true;
				break;
			default:
				gaveUp = true;  // the player starts Minecraft themselves
				break;
			}
			if (note) {
				RE::SendHUDMessage::ShowHUDMessage(note);
				told = true;
			}
			nextNote = waited + 120.0f;
		}

		// Minecraft's crouch is Skyrim's sneak, so NPCs' detection, the stealth eye, sneak attacks
		// and the Sneak skill all follow it. Skyrim sneaks through its ActionSneak action, exactly
		// what its Sneak key does (SneakHandler -> PlayerControls::DoAction(kActionSneak, kTry),
		// AE 42350); it toggles, so it's sent whenever the two disagree, and again after a moment
		// if the animation graph didn't take it (mid-jump, say).
		void SyncSneak(RE::PlayerCharacter* a_player, bool a_mcSneaking, float a_delta)
		{
			static float retry = 0.0f;
			static int   logged = 0;
			retry -= a_delta;
			const auto* state = a_player->AsActorState();
			const bool  sneaking = state->actorState1.sneaking;
			if (sneaking == a_mcSneaking || retry > 0.0f || a_player->IsDead() || a_player->IsOnMount() || state->IsSwimming()) {
				return;
			}
			auto* controls = RE::PlayerControls::GetSingleton();
			if (!controls) {
				return;
			}
			using DoActionFn = bool (*)(RE::PlayerControls*, RE::DEFAULT_OBJECT, std::int32_t);
			static const auto doAction = reinterpret_cast<DoActionFn>(REL::ID(42350).address());
			const bool        ok = doAction(controls, RE::DEFAULT_OBJECT::kActionSneak, 2);
			retry = 0.3f;
			if (logged < 6) {
				++logged;
				logger::info("sneak: Minecraft {} crouching, Skyrim {} sneaking: toggled ({})", a_mcSneaking ? "is" : "isn't", sneaking ? "was" : "wasn't",
					ok ? "taken" : "refused");
			}
		}

		// SkyCraft+ : the Nether and the End. Back in the open world, Skyrim's player is held where it came
		// out until Minecraft drives it again: the ground there isn't loaded yet, and it would fall
		// through (and die of Skyrim's fall damage) meanwhile.
		RE::NiPoint3 returnPin;
		float        returnPinTime = 0.0f;
		// SkyCraft+ : the underground. Seconds left of counting as under Skyrim's land after Minecraft last
		// said so (it can lose track for a moment, moving fast through a big cave).
		float underLandRecently = 0.0f;

		// SkyCraft+ : the Nether and the End. Skyrim has nothing for a world of Minecraft's own, so its
		// player waits in an empty cell (SkyCraft.ini iWaitingCell) meanwhile, following Minecraft's
		// there, and only Minecraft is drawn. When Minecraft's player comes out of a portal into the
		// open world again, Skyrim's goes there. Returns true while parked in the waiting cell.
		bool MinecraftWorlds(RE::PlayerCharacter* a_player, RE::TESObjectCELL* a_cell, bool a_loading, bool a_mcInWorld, float a_delta)
		{
			auto&        st = State();
			static float retry = 0.0f;  // seconds before a move is tried again
			static bool  toldStuck = false;
			auto*        waiting = st.minecraftWorldsOption ? RE::TESForm::LookupByID<RE::TESObjectCELL>(st.waitingCellId) : nullptr;
			const bool   parked = waiting && a_cell == waiting;
			const auto   world = mc.dimension;
			// Minecraft says it's back in the open world a moment before its new position arrives (the
			// first ones are still the Nether's or the End's): wait until it has stood still a second.
			static double settleX = 0.0, settleY = 0.0, settleZ = 0.0;
			static float  settled = -1.0f;
			// Where it last was in the Nether or the End: standing still in a portal, that position was
			// still reported while the open world loaded, and taken for where it came out (Skyrim's
			// player went to the Nether's coordinates, in the air). It has to have moved from there.
			static double awayX = 0.0, awayZ = 0.0;
			// SkyCraft+ : back into the same place. Where Skyrim's player was when it went to wait: a
			// walled city's worldspace (Whiterun...), another worldspace, or an interior.
			static RE::FormID   leftWorld = 0, leftCell = 0;
			static RE::NiPoint3 leftPos;
			static float  sinceBack = 0.0f;
			if (world >= proto::kDimNether) {
				awayX = mc.x, awayZ = mc.z;
				sinceBack = 0.0f;
			}
			if (!parked || world > proto::kDimWorldspaces) {
				settled = -1.0f;
			}
			retry -= a_delta;
			if (!waiting || a_loading || !a_mcInWorld || a_player->IsDead() || retry > 0.0f) {
				return parked;
			}
			// A save in the open world loaded (or Minecraft connected) with Minecraft's player still in the
			// Nether or the End: the save wins. Parking Skyrim's player then pulled it out of the place
			// just loaded (and smeared the picture): Minecraft's comes back to it instead.
			const bool home = std::exchange(homeCheck, false);
			if (home && !parked && world >= proto::kDimNether) {
				logger::info("Minecraft's player is in {} but this save is in Skyrim's world: bringing it back", world == proto::kDimNether ? "the Nether" : "the End");
				bringingBack = 20.0f;
				teleportPending = true;
			}
			// A save made in the Nether or the End loaded with Minecraft's player out in the open world (or
			// elsewhere in that world): it goes back to where the save was made. (Taken for its coming out of
			// a portal, Skyrim's player went to Minecraft's place in the open world, nothing loaded round it.)
			const char* savedName = savedWorld == proto::kDimNether ? "the Nether" : "the End";
			const bool fromSave = home && std::exchange(savedCheck, false);  // (not again when Minecraft reconnects later)
			if (fromSave && parked && savedWorld >= proto::kDimNether) {
				const double dx = mc.x - savedX, dz = mc.z - savedZ;
				if (world != savedWorld || dx * dx + dz * dz > 16.0 * 16.0) {
					logger::info("this save was made in {}: Minecraft's player goes back there ({:.0f}, {:.0f}, {:.0f})", savedName, savedX, savedY, savedZ);
					sendingAway = 20.0f;
					++teleportSeq;
				}
			}
			if (sendingAway > 0.0f) {
				const double dx = mc.x - savedX, dz = mc.z - savedZ;
				if (world == savedWorld && dx * dx + dz * dz < 8.0 * 8.0) {
					logger::info("Minecraft's player is back in {}", savedName);
					sendingAway = 0.0f;
				} else if ((sendingAway -= a_delta) > 0.0f) {
					return parked;
				} else {
					logger::warn("Minecraft's player didn't go back to {}; Skyrim's follows it instead", savedName);
				}
			}
			if (bringingBack > 0.0f) {
				if (world < proto::kDimNether) {
					logger::info("Minecraft's player is back in the open world");
					bringingBack = 0.0f;
				} else if ((bringingBack -= a_delta) > 0.0f) {
					return parked;
				} else {
					logger::warn("Minecraft's player didn't come back; Skyrim's follows it instead");
				}
			}
			if (!parked && world >= proto::kDimNether) {
				logger::info("Minecraft's player went into {}: Skyrim's waits in cell {:08X}", world == proto::kDimNether ? "the Nether" : world == proto::kDimEnd ? "the End" : "a world of Minecraft's own",
					waiting->GetFormID());
				if (st.skyrimMode) {
					st.skyrimMode = false;  // nothing of Skyrim's to walk on there
					logger::info("Skyrim mode off (Minecraft's own world)");
				}
				if (auto* lighting = waiting->GetLighting()) {
					// Skyrim's backdrop there: the Nether's dark red, the End's near black.
					const bool end = world == proto::kDimEnd;
					lighting->fogColorNear = end ? RE::Color(10, 8, 14, 0) : RE::Color(51, 8, 8, 0);
					lighting->fogColorFar = lighting->fogColorNear;
					lighting->ambient = end ? RE::Color(48, 44, 60, 0) : RE::Color(64, 32, 26, 0);
					// Skyrim's own things in it (the enemies brought in) seen as far as Minecraft's blocks:
					// the cell's draw distance and fog were its own (short), so they went unseen.
					lighting->clipDist = 400000.0f;
					lighting->fogNear = end ? 30000.0f : 1400.0f;
					lighting->fogFar = end ? 200000.0f : 9000.0f;
					lighting->fogPower = 1.0f;
					lighting->fogClamp = end ? 0.0f : 0.85f;
					lighting->directional = end ? RE::Color(120, 110, 140, 0) : RE::Color(140, 80, 60, 0);
					lighting->lightingTemplateInheritanceFlags.reset();
				}
				{
					auto* from = a_player->GetParentCell();
					auto* ws = a_player->GetWorldspace();
					if (from == waiting) {
						from = nullptr, ws = nullptr;  // (already waiting: keep what was noted)
					}
					leftCell = from && from->IsInteriorCell() ? from->GetFormID() : (from ? 0 : leftCell);
					leftWorld = ws ? ws->GetFormID() : (from ? 0 : leftWorld);
					if (from) {
						leftPos = a_player->GetPosition();
					}
					logger::info("left from {} {:08X}", leftCell ? "interior" : "worldspace", leftCell ? leftCell : leftWorld);
				}
				a_player->CenterOnCell(waiting);
				retry = 5.0f;
				toldStuck = false;
				return parked;
			}
			if (parked && world <= proto::kDimWorldspaces) {
				sinceBack += a_delta;
				const double adx = mc.x - awayX, adz = mc.z - awayZ;
				if (adx * adx + adz * adz < 4.0 && sinceBack < 20.0f) {
					return parked;  // still the Nether's (or End's) position: its new one hasn't come
				}
				// Still across (not up and down: one falling, with no Skyrim ground under it yet, never
				// stood still, Skyrim waited on in its empty cell and the player fell out of the world),
				// for a second; or four seconds back, whatever it's doing.
				const double sdx = mc.x - settleX, sdz = mc.z - settleZ;
				if (sinceBack < 4.0f && (settled < 0.0f || sdx * sdx + sdz * sdz > 0.25)) {
					settleX = mc.x, settleY = mc.y, settleZ = mc.z;
					settled = 0.0f;
					return parked;
				}
				settled += a_delta;
				if (settled < 1.0f && sinceBack < 4.0f) {
					return parked;
				}
				settled = -1.0f;
				// Out of a portal into the open world: Skyrim's player goes where Minecraft's came out.
				// Moved like a reference to an editor location: the position in Tamriel, anchored on
				// one of its persistent references (any will do).
				// SkyCraft+ : back into the same place. Into the worldspace or interior it went to wait
				// from, if Minecraft's player came back to that kind of place near where it left: out of a
				// portal built in Whiterun, Tamriel's own (low, no collision) Whiterun was loaded instead
				// and the player fell through it.
				const auto    pos = McToSky(mc.x, mc.y, mc.z);
				RE::TESObjectCELL*  intoCell = nullptr;
				RE::TESWorldSpace*  into = nullptr;
				const bool          nearLeft = pos.GetDistance(leftPos) < 400.0f * float(proto::kUnitsPerBlock);
				if (world == proto::kDimInteriors) {
					intoCell = leftCell ? RE::TESForm::LookupByID<RE::TESObjectCELL>(leftCell) : nullptr;
				} else if (auto* ws = leftWorld && !leftCell && nearLeft ? RE::TESForm::LookupByID<RE::TESWorldSpace>(leftWorld) : nullptr) {
					bool inTamriel = false;
					for (auto* w = ws; w && !inTamriel; w = w->parentWorld) {
						inTamriel = w->GetFormID() == 0x3C;
					}
					if (inTamriel == (world == proto::kDimTamriel)) {
						into = ws;
					}
				}
				if (!into && !intoCell && world == proto::kDimTamriel) {
					into = RE::TESForm::LookupByID<RE::TESWorldSpace>(0x3C);
				}
				RE::TESObjectREFR* anchor = nullptr;
				auto firstRef = [&](RE::TESObjectCELL* a_cell, RE::TESWorldSpace* a_ws) {
					if (!a_cell) {
						return;
					}
					a_cell->ForEachReference([&](RE::TESObjectREFR* a_ref) {
						if (a_ref && (!a_ws || a_ref->GetWorldspace() == a_ws)) {
							anchor = a_ref;
							return RE::BSContainer::ForEachResult::kStop;
						}
						return RE::BSContainer::ForEachResult::kContinue;
					});
				};
				firstRef(intoCell ? intoCell : into ? into->persistentCell : nullptr, into);
				if (!intoCell && !anchor) {
					if (!toldStuck) {
						toldStuck = true;
						logger::warn("couldn't find {} to go back to; use a door or coc", into ? "the worldspace" : "the interior");
						RE::SendHUDMessage::ShowHUDMessage("SkyCraft: couldn't take you back to Skyrim; use the console (coc) to leave.");
					}
					retry = 5.0f;
					return parked;
				}
				// (After the End's exit portal or a death, Minecraft brings its player back to the portal
				// it left by: SkyMinecraftWorlds.)
				logger::info("Minecraft's player is back in {} {:08X}: Skyrim's goes to ({:.0f}, {:.0f}, {:.0f})", intoCell ? "interior" : "worldspace",
					intoCell ? intoCell->GetFormID() : into->GetFormID(), pos.x, pos.y, pos.z);
				// TESObjectREFR::MoveTo_Impl (SE 56227, AE 56626), private in CommonLib.
				using MoveToFn = void (*)(RE::TESObjectREFR*, const RE::ObjectRefHandle&, RE::TESObjectCELL*, RE::TESWorldSpace*, const RE::NiPoint3&, const RE::NiPoint3&);
				static REL::Relocation<MoveToFn> moveTo{ RELOCATION_ID(56227, 56626) };
				const RE::ObjectRefHandle anchorHandle = anchor ? anchor->CreateRefHandle() : RE::ObjectRefHandle{};
				moveTo(a_player, anchorHandle, intoCell ? intoCell : anchor->GetParentCell(), intoCell ? nullptr : into, pos, RE::NiPoint3(0.0f, 0.0f, McYawToHeading(mc.yaw)));
				returnPin = pos;
				returnPinTime = 30.0f;
				retry = 5.0f;
				return parked;
			}
			if (parked && world < proto::kDimNether && !toldStuck) {
				// A save made in the Nether loaded with Minecraft somewhere else: nothing to follow.
				toldStuck = true;
				logger::warn("Skyrim's player is in the waiting cell but Minecraft's is in a Skyrim interior or worldspace");
				RE::SendHUDMessage::ShowHUDMessage("SkyCraft: Minecraft isn't in the Nether or the End; use the console (coc) to leave this empty cell.");
			}
			return parked;
		}

		void PerFrame(RE::PlayerCharacter* a_player, float a_delta)
		{
			auto& st = State();
			auto& link = Link::Get();
			auto* ui = RE::UI::GetSingleton();
			link.Heartbeat();

			const bool mcAlive = link.McAlive();
			const bool haveMc = mcAlive && link.ReadMcState(mc);
			// A different Minecraft process (restarted while we were paused) counts as a reconnect too.
			static std::uint32_t lastMcPid = 0;
			const auto           mcPid = link.McPid();
			const bool           newMcProcess = mcAlive && mcPid != 0 && mcPid != lastMcPid;
			if (mcAlive) {
				lastMcPid = mcPid;
			}
			if (mcAlive && (!mcWasAlive || newMcProcess)) {
				// Minecraft (re)connected: resend all collision from a fresh epoch.
				logger::info("Minecraft connected");
				link.ResetOverlay();
				settleTimer = kSettleSeconds;
				++epoch;
				Collision::Get().Reset(epoch);
				teleportPending = true;
				homeCheck = true;
			}
			mcWasAlive = mcAlive;
			st.mcInWorld = haveMc && (mc.flags & proto::kMcInWorld);
			const bool screenOpen = haveMc && (mc.flags & proto::kMcScreenOpen);
			if (screenOpen && !st.mcScreenOpen) {
				st.cursorX = st.viewportW / 2;
				st.cursorY = st.viewportH / 2;
			}
			st.mcScreenOpen = screenOpen;
			// SkyCraft+ : controller inventory snapping. A screen with slots (the pad jumps between them),
			// and the cursor follows where Minecraft put it.
			st.mcSlotScreen = screenOpen && (mc.flags & proto::kMcSlotScreen);
			if (static std::uint32_t cursorSeen = 0; haveMc && mc.cursorSeq != cursorSeen) {
				cursorSeen = mc.cursorSeq;
				if (screenOpen) {
					st.cursorX = mc.cursorX;
					st.cursorY = mc.cursorY;
				}
			}
			st.mcUseTarget = haveMc && (mc.flags & proto::kMcUseTarget);
			if (haveMc && mc.healthFrac > 0.0f) {
				st.mcHealthFrac = mc.healthFrac;
			}
			if (haveMc && mc.sensitivity > 0.0f) {
				st.sensitivity = mc.sensitivity;
			}

			auto*      cell = a_player->GetParentCell();
			const bool loading = !cell || !a_player->Is3DLoaded() || ui->IsMenuOpen(RE::LoadingMenu::MENU_NAME);
			const bool menu = AnyBlockingMenuOpen(ui);
			if ((menu || loading) && !st.skyrimMenuOpen) {
				Input::ReleaseAll();
			}
			st.skyrimMenuOpen = menu || loading;
			Input::PadFrame(a_delta);

			// World identity: exterior worldspace or interior cell. A change wipes MC's collision.
			if (cell) {
				auto*      ws = a_player->GetWorldspace();
				const auto id = cell->IsInteriorCell() ? cell->GetFormID() : (ws ? ws->GetFormID() : cell->GetFormID());
				if (id != worldId) {
					logger::info("world changed {:08X} -> {:08X}", worldId, id);
					worldId = id;
					Dig::SetWorld(id);
					++epoch;
					Collision::Get().Reset(epoch);
					teleportPending = true;
					settleTimer = kSettleSeconds;
				}
			}

			// Skyrim moved the player itself (load door, fast travel, script, loading a save).
			// SkyCraft+ : just back from the Nether or the End, it's held where Minecraft's player came out
			// (returnPin). Under Skyrim's land (a stronghold's End portal) Skyrim lifts it out every frame:
			// taken for Skyrim moving it, each lift sent Minecraft a new teleport (to the surface), it never
			// caught up and both stood frozen until the hold ran out. The held place counts instead.
			const auto current = returnPinTime > 0.0f && cell && !cell->IsInteriorCell() ? returnPin : a_player->GetPosition();
			if (loading) {
				teleportPending = true;
				haveLastSet = false;
				settleTimer = kSettleSeconds;
			} else if (!st.parked && haveLastSet && current.GetDistance(lastSetPos) > kSkyrimTeleportThreshold) {  // (parked: Minecraft leads, nothing to resync)
				const float dx = current.x - lastSetPos.x, dy = current.y - lastSetPos.y, dz = current.z - lastSetPos.z;
				// SkyCraft+ : underground. Down a mine, under Skyrim's land, Skyrim lifts the player back
				// up to it (no loading screen, same world, mostly straight up). Minecraft is driving and
				// the mine is real: the lift is ignored and Minecraft's position put back.
				// Under Skyrim's land, however far: it goes back to where the player last stood on Skyrim's
				// ground, which a staircase down leaves well behind.
				const bool lifted = st.puppeting && !teleportPending && dz > 200.0f && (underLandRecently > 0.0f || std::sqrt(dx * dx + dy * dy) < 30.0f * proto::kUnitsPerBlock);
				if (lifted) {
					static std::uint32_t lifts = 0;
					if (++lifts <= 3 || lifts % 600 == 0) {  // it can try every frame
						logger::info("Skyrim lifted the player out of a mine ({:.0f}, {:.0f}, {:.0f} units); ignored ({} times)", dx, dy, dz, lifts);
					}
					a_player->SetPosition(lastSetPos, true);
				} else {
					logger::info("Skyrim moved the player ({:.0f} units: {:.0f}, {:.0f}, {:.0f}); resyncing Minecraft", current.GetDistance(lastSetPos), dx, dy, dz);
					teleportPending = true;
					haveLastSet = false;
				}
			}
			// SkyCraft+ : the Nether and the End. While parked, Minecraft leads: it gets no teleports.
			st.mcWorld = haveMc ? mc.dimension : 0u;
			st.parked = MinecraftWorlds(a_player, cell, loading, haveMc && st.mcInWorld, a_delta);
			if (st.parked) {
				teleportPending = false;
			}
			// SkyCraft+ : the underground. More than two blocks under Skyrim's land (a mine): nothing of
			// Skyrim's may be on screen, and blocks must be drawn in the frame anyway (WorldRender).
			// (Minecraft tells: Skyrim's own land lookup gives up far below the map.)
			{
				const bool under = !loading && !st.parked && haveMc && st.mcInWorld && (mc.flags & proto::kMcUnderLand) != 0;
				if (under != st.playerUnderLand) {
					logger::info("underground: {} Skyrim's land", under ? "under" : "out from under");
				}
				st.playerUnderLand = under;
				underLandRecently = under ? 3.0f : std::max(0.0f, underLandRecently - a_delta);
				DeepHide::PerFrame(a_player, under && (mc.flags & proto::kMcDeepUnderLand) != 0, a_delta);  // SkyCraft+
			}
			if (teleportPending && !loading) {
				++teleportSeq;
				teleportPending = false;
				st.yaw = HeadingToMcYaw(a_player->data.angle.z);
				st.pitch = a_player->data.angle.x * kRadToDeg;
				st.lookInitialized = true;
				// SkyCraft+ : mode switch. Back from Skyrim's own controls, keep looking where Skyrim's
				// camera looked (its third-person camera turns without turning the body).
				if (skyrimLookPending) {
					skyrimLookPending = false;
					SeedLookFromCamera(st, true);
				}
			}

			// Mouse look (MC's formula), integrated here so the Skyrim camera has zero added latency.
			float dx = 0.0f, dy = 0.0f;
			Input::ConsumeLook(dx, dy);
			if (!st.lookInitialized) {
				st.yaw = HeadingToMcYaw(a_player->data.angle.z);
				st.pitch = a_player->data.angle.x * kRadToDeg;
				st.lookInitialized = true;
			}
			if (!st.mcScreenOpen && !st.skyrimMenuOpen) {
				const float s = st.sensitivity * 0.6f + 0.2f;
				const float factor = s * s * s * 8.0f * 0.15f;
				st.yaw = std::fmod(st.yaw + dx * factor, 360.0f);
				st.pitch = std::clamp(st.pitch + dy * factor, -90.0f, 90.0f);
			}

			// Minecraft holds its player still after a teleport until Skyrim's ground has arrived
			// around them. If it's holding somewhere Skyrim's player isn't, that ground never comes:
			// send it again to where Skyrim's player really is.
			static const char* takeover = nullptr;
			const char*        takeoverNow = a_player->IsDead() ? nullptr : SkyrimTakeover(a_player);
			if ((takeoverNow != nullptr) != (takeover != nullptr)) {
				if (takeoverNow) {
					logger::info("Skyrim takes the player ({})", takeoverNow);
					// One health pool: Skyrim carries on from Minecraft's share (Minecraft takes
					// Skyrim's back when it drives again).
					if (st.puppeting && haveMc && mc.healthFrac > 0.0f) {
						SetHealthFraction(a_player, mc.healthFrac);
					}
				} else {
					logger::info("Skyrim hands the player back");
					teleportPending = true;  // Minecraft picks up wherever Skyrim left the player
					skyrimLookPending = true;
				}
			}
			takeover = takeoverNow;
			const bool arriving = haveMc && st.mcInWorld && !loading && mc.teleportAck != teleportSeq && !takeover;
			if (arriving) {
				const auto   here = SkyToMc(current);
				const double gap = std::sqrt((here.x - mc.x) * (here.x - mc.x) + (here.y - mc.y) * (here.y - mc.y) + (here.z - mc.z) * (here.z - mc.z));
				holdMismatch = gap > 8.0 ? holdMismatch + a_delta : 0.0f;
				if (holdMismatch > 1.0f) {
					logger::info("Minecraft is waiting {:.0f} blocks from Skyrim's player; teleporting it again", gap);
					teleportPending = true;
					holdMismatch = 0.0f;
				}
			} else {
				holdMismatch = 0.0f;
			}

			// A dead Skyrim player gets Skyrim's own death camera and reload.
			const bool puppet = haveMc && st.mcInWorld && mc.teleportAck == teleportSeq && !loading && !a_player->IsDead() && !takeover;
			st.minecraftOwnsPlayer = puppet || (arriving && !a_player->IsDead());
			if (puppet != st.puppeting) {
				logger::info("puppet {}", puppet ? "on (Minecraft drives the player)" : "off");
			}
			st.puppeting = puppet;

			// SkyCraft+ : the Nether and the End. Just back: held where it came out (see returnPin).
			// Until Minecraft drives it again out here (while still in the waiting cell, Minecraft drives
			// the parked player: that doesn't count), and through the load on arrival too.
			if (returnPinTime > 0.0f) {
				const bool arrived = cell && !st.parked;
				returnPinTime = (puppet && arrived && !loading) ? 0.0f : returnPinTime - a_delta;
				if (arrived && !(puppet && !loading)) {
					a_player->SetPosition(returnPin, true);
					if (auto* controller = a_player->GetCharController()) {
						controller->SetLinearVelocityImpl(RE::hkVector4(0.0f, 0.0f, 0.0f, 0.0f));
						controller->fallStartHeight = returnPin.z;
						controller->fallTime = 0.0f;
					}
					lastSetPos = returnPin;  // not "Skyrim moved the player"
					haveLastSet = true;
				}
				if (returnPinTime <= 0.0f) {
					logger::info("back in the open world: Skyrim's player let go ({})", puppet ? "Minecraft drives it" : "timed out");
				}
			}

			// SkyCraft+ : underground. Deep under Skyrim's land, Skyrim thinks the player fell out of
			// the world and sends them back to their "last known good position" on the surface.
			// While Minecraft is playing, wherever the player is counts as good.
			if (st.mcInWorld && !loading && !a_player->IsDead() && (puppet || takeover)) {
				a_player->GetPlayerRuntimeData().lastKnownGoodPosition = puppet ? McToSky(mc.x, mc.y, mc.z) : current;  // (current may be Skyrim's lift, undone above)
				a_player->GetPlayerFlags().returnToLastKnownGoodPosition = false;
			}
			bool rightHand = false, leftHand = false;
			if (puppet && st.skyrimHandsOption) {
				SkyrimHands(a_player, rightHand, leftHand);
				MixHands(a_player, rightHand, leftHand);  // SkyCraft+ : hand mixing
			}
			if (rightHand != st.rightHandSkyrim || leftHand != st.leftHandSkyrim) {
				logger::info("hands: right {}, left {}", rightHand ? "Skyrim's" : "Minecraft's", leftHand ? "Skyrim's" : "Minecraft's");
			}
			st.rightHandSkyrim = rightHand;
			st.leftHandSkyrim = leftHand;
			const bool skyrimDrives = takeover && haveMc && st.mcInWorld && !loading && !a_player->IsDead();
			st.skyrimDrives = skyrimDrives;
			if (skyrimDrives && !menu) {
				SeedLookFromCamera(st, false);  // Minecraft's player looks where Skyrim's camera does
				// SkyCraft+ : Skyrim mode on Minecraft blocks. Havok is told the player stands on one
				// (DigPhysics), but the animation can stay in its jump/fall from a moment Skyrim thought
				// otherwise (seen in third person): standing on a block, it lands.
				// (Only once it's lasted: a real jump starts on a block too.)
				static float stuckInJump = 0.0f;
				bool         jumping = false;
				float        rising = 0.0f;
				if (auto* controller = a_player->GetCharController()) {
					RE::hkVector4 velocity;
					controller->GetLinearVelocityImpl(velocity);
					alignas(16) float v[4];
					_mm_store_ps(v, velocity.quad);
					rising = v[2];
				}
				// SkyCraft+ : the falling animation on blocks (Known bugs). Rising up to Havok's own
				// "taking off" speed still counts as standing (settling onto a block plane pushes up a
				// little), and a jump state lasting a second with no landing says why, now and then.
				const bool onBlock = Dig::PlayerOnBlock();
				const bool inJump = a_player->GetGraphVariableBool("bInJumpState", jumping) && jumping;
				const bool stuck = onBlock && rising <= 0.3f && inJump;
				stuckInJump = stuck ? stuckInJump + a_delta : 0.0f;
				static float longJump = 0.0f;
				static int   whyLogged = 0;
				longJump = inJump ? longJump + a_delta : 0.0f;
				if (longJump > 1.0f && !stuck) {
					longJump = -4.0f;  // (again in 5 s at most)
					if (whyLogged++ < 20) {
						logger::info("dig: in a jump or fall animation for a second: on a Minecraft block {}, rising {:.2f}, Havok state {}, in the air {}", onBlock, rising,
							a_player->GetCharController() ? static_cast<int>(a_player->GetCharController()->context.currentState) : -1, a_player->IsInMidair());
					}
				}
				if (stuckInJump > 0.25f) {
					stuckInJump = 0.0f;
					a_player->NotifyAnimationGraph("JumpLand");
					static int logged = 0;
					if (logged++ < 20) {
						logger::info("dig: standing on a Minecraft block in a jump or fall animation: landed");
					}
				}
			}
			if (puppet) {
				EnsureControlsEnabled();
				Game::NoteFrameStep('P');
				SyncSneak(a_player, (mc.flags & proto::kMcSneaking) != 0, a_delta);
			}
			if (ui) {
				HideCrosshair(ui, puppet, a_player->AsActorState()->actorState1.sneaking);
			}
			st.mcCrosshair = puppet && mc.cameraMode == 0 && !st.mcScreenOpen && !st.skyrimMenuOpen;
			st.mcGuiScale = haveMc ? static_cast<int>(mc.guiScale) : 0;
			Input::SetActivatePromptKey(puppet);
			Combat::PerFrame(a_player, puppet, a_delta);
			Combat::WatchPlayerHits(a_player, a_delta);
			SkyrimShots::PerFrame(a_player, (puppet || skyrimDrives) && haveMc && st.mcInWorld, a_delta);
			MinecraftEnemies::PerFrame(a_player, a_delta, st.parked, st.mcWorld, mc.x, mc.y, mc.z);  // SkyCraft+
			LootSpots::PerFrame(a_player, a_delta);  // SkyCraft+
			Crops::PerFrame(a_delta);  // SkyCraft+
			MapMarkers::PerFrame(a_player, a_delta);  // SkyCraft+
			SkyInventory::PerFrame(a_player, haveMc && (mc.flags & proto::kMcWantsSkyInventory) != 0, a_delta);  // SkyCraft+
			if (haveMc && st.mcInWorld) {
				MinecraftEnemies::StopShotsAtBlocks(a_player);  // SkyCraft+
			}
			WorldRender::UpdateRagdoll(a_player, haveMc && st.mcInWorld);
			if (puppet || skyrimDrives) {
				NpcBlocks::PushActorsOut(a_player, a_delta);
			}

			// Interpolate Minecraft's 20 Hz physics ticks on our own clock (what MC's renderer does
			// with partial ticks). Sampling MC's per-frame position instead judders, because the two
			// games' frames aren't phase-locked.
			double feetX = mc.x, feetY = mc.y, feetZ = mc.z;
			double eyeX = mc.eyeX, eyeY = mc.eyeY, eyeZ = mc.eyeZ;
			float  bobPhaseNow = mc.bobPhase, bobAmountNow = mc.bobAmount;
			if (mc.tickQpc != 0 && mc.tickMs > 0.0f) {
				static const std::int64_t qpcFreq = [] { LARGE_INTEGER f; ::QueryPerformanceFrequency(&f); return f.QuadPart; }();
				const double       qpcPerMs = double(qpcFreq) / 1000.0;
				const std::int64_t period = std::max<std::int64_t>(1, std::llround(double(mc.tickMs) * qpcPerMs));
				LARGE_INTEGER      now;
				::QueryPerformanceCounter(&now);
				// Keep a short history of Minecraft's ticks and render slightly in the past, so the
				// next tick has always arrived before we need it: pure interpolation (Minecraft's own
				// model), never extrapolation, so no hitches when velocity changes (jumps, landings).
				if (tickHistory.empty() || tickHistory.back().s.tickQpc != mc.tickQpc) {
					if (!tickHistory.empty() && mc.tickQpc < tickHistory.back().s.tickQpc) {
						tickHistory.clear();  // Minecraft restarted
					}
					Tick tick{ mc, mc.tickQpc, 1 };
					if (!tickHistory.empty()) {
						auto&              last = tickHistory.back();
						const std::int64_t n = std::llround(double(mc.tickQpc - last.at) / double(period));
						const std::int64_t err = mc.tickQpc - (last.at + n * period);
						if (n == 0 && last.slots >= 2) {
							// Minecraft ran two ticks in one frame and we saw both: the first one
							// carries the second's stamp. It belongs a tick earlier.
							last.at -= period;
							last.slots -= 1;
							tick.at = last.at + period;
						} else if (n >= 1 && n <= 10 && std::abs(err) < period * 3 / 10) {
							tick.at = last.at + n * period + err / 16;  // the rhythm is exact; the stamps are noisy
							tick.slots = static_cast<int>(n);
							stampOutliers = 0;
							motion.stampErrMs += std::abs(double(err)) / qpcPerMs;
							++motion.stampSamples;
						} else if (n <= 10 && ++stampOutliers < 3) {
							tick.slots = static_cast<int>(std::max<std::int64_t>(n, 1));
							tick.at = last.at + tick.slots * period;  // one odd stamp (a hitch): keep the rhythm
						} else {
							stampOutliers = 0;  // lost the rhythm (a pause, a new tick rate): start from this stamp
						}
					}
					// Was it already due on our last frame? Then rendering had to wait for it.
					if (lastFrameQpc != 0) {
						if (!tickDueInit) {
							tickDue.fill(renderDelayMs - 1.0);
							tickDueInit = true;
						}
						const double dueMs = double(lastFrameQpc - tick.at) / qpcPerMs;
						if (dueMs < 30.0) {  // later than that is a hitch, not a pattern to wait for
							tickDue[tickDueNext++ % tickDue.size()] = dueMs;
						}
					}
					// Auto-capture a jump (rising faster than walking up a slope), or a climb in third person.
					const double rise = mc.curY - mc.prevY, run = std::hypot(mc.curX - mc.prevX, mc.curZ - mc.prevZ);
					dbgClimbTicks = mc.cameraMode != 0 && (mc.flags & proto::kMcOnGround) && rise > 0.03 && run > 0.1 ? dbgClimbTicks + 1 : 0;
					if (!dbgJumpTrigger && ((rise > 0.3 && dbgSinceJumpCapture > 6.0f) || (dbgClimbTicks >= 10 && dbgSinceJumpCapture > 30.0f))) {
						dbgJumpTrigger = true;
						dbgTriggerReason = rise > 0.3 ? "jump" : "climbing in third person";
						dbgSinceJumpCapture = 0.0f;
					}
					tickHistory.push_back(tick);
					if (tickHistory.size() > 8) {
						tickHistory.pop_front();
					}
					++motion.ticks;
				}
				dbgSinceJumpCapture += a_delta;

				// The render delay follows how late ticks have been over the last 2 s: it grows 2%
				// slower than real time and shrinks 0.2% faster, too little to see either way.
				const double frameMs = lastFrameQpc != 0 ? double(now.QuadPart - lastFrameQpc) / qpcPerMs : 0.0;
				lastFrameQpc = now.QuadPart;
				if (tickDueInit) {
					const double target = std::clamp(*std::ranges::max_element(tickDue) + 1.0, 4.0, 30.0);
					const double dt = std::min(frameMs, 100.0) / 1000.0;
					renderDelayMs = target > renderDelayMs ? std::min(target, renderDelayMs + 20.0 * dt) : std::max(target, renderDelayMs - 2.0 * dt);
				}
				const std::int64_t renderQpc = now.QuadPart - std::llround(renderDelayMs * qpcPerMs);

				// The feet go through each tick's start (prev) and end (cur) positions.
				std::size_t i = 0;
				for (std::size_t k = tickHistory.size(); k-- > 0;) {
					if (tickHistory[k].at <= renderQpc) {
						i = k;
						break;
					}
				}
				const Tick&  tick = tickHistory[i];
				const Tick*  next = i + 1 < tickHistory.size() ? &tickHistory[i + 1] : nullptr;
				const double ticks = double(renderQpc - tick.at) / double(period);
				const double t = std::clamp(ticks, 0.0, 1.0);
				dbgT = ticks;
				dbgTickQpc = tick.s.tickQpc;
				const auto&  s = tick.s;
				feetX = s.prevX + (s.curX - s.prevX) * t;
				feetY = s.prevY + (s.curY - s.prevY) * t;
				feetZ = s.prevZ + (s.curZ - s.prevZ) * t;
				double eyeHeight = s.tickEyeO + (s.tickEye - s.tickEyeO) * t;
				bobPhaseNow = -(s.walkDist + (s.walkDist - s.walkDistO) * static_cast<float>(t));
				bobAmountNow = s.bobO + (s.bob - s.bobO) * static_cast<float>(t);
				if (ticks > 1.0 && next) {
					// Past this tick's end, and the next tick we have starts later: Minecraft ran one
					// we never saw. Carry on from this tick's end to the next one's start.
					const auto&  n = next->s;
					const double gap = double(next->at - (tick.at + period));
					const double u = gap > 0.0 ? std::clamp(double(renderQpc - (tick.at + period)) / gap, 0.0, 1.0) : 1.0;
					feetX = s.curX + (n.prevX - s.curX) * u;
					feetY = s.curY + (n.prevY - s.curY) * u;
					feetZ = s.curZ + (n.prevZ - s.curZ) * u;
					eyeHeight = s.tickEye + (n.tickEyeO - s.tickEye) * u;
					const float endPhase = -(s.walkDist + (s.walkDist - s.walkDistO));
					bobPhaseNow = endPhase + (-n.walkDist - endPhase) * static_cast<float>(u);
					bobAmountNow = s.bob + (n.bobO - s.bob) * static_cast<float>(u);
				} else if (ticks > 1.0) {
					++motion.lateFrames;  // the next tick hasn't arrived: the player stands still this frame
				}
				eyeX = feetX;
				eyeY = feetY + eyeHeight;
				eyeZ = feetZ;
				++motion.frames;
				motion.frameMsSum += frameMs;
				motion.frameMsMax = std::max(motion.frameMsMax, frameMs);
			}

			// SkyCraft+ : the Nether and the End. On the way into the waiting cell, Skyrim's player
			// stays put (Minecraft's position there means nothing in Skyrim's world).
			const bool onTheWay = st.mcWorld >= proto::kDimNether && !st.parked;
			if (puppet && !onTheWay) {
				const auto pos = McToSky(feetX, feetY, feetZ);
				a_player->SetPosition(pos, true);
				if (auto* controller = a_player->GetCharController()) {
					// Minecraft moves the player; Skyrim keeps no momentum or fall damage of its own.
					// Its gravity stays on so the body keeps ground contact between our updates:
					// Skyrim's AI won't melee a target it thinks is in the air.
					controller->SetLinearVelocityImpl(RE::hkVector4(0.0f, 0.0f, 0.0f, 0.0f));
					if (savedGravity >= 0.0f) {
						controller->gravity = savedGravity;  // undo the zero-gravity of older builds
						savedGravity = -1.0f;
					}
					controller->fallStartHeight = pos.z;
					controller->fallTime = 0.0f;
					if (mc.flags & proto::kMcOnGround) {
						controller->context.currentState = RE::hkpCharacterStateType::kOnGround;
						controller->flags.set(RE::CHARACTER_FLAGS::kSupport);
					} else {
						controller->context.currentState = RE::hkpCharacterStateType::kInAir;
					}
				}
				lastSetPos = pos;
				haveLastSet = true;
				{
					auto* proxyController = skyrim_cast<RE::bhkCharProxyController*>(a_player->GetCharController());
					auto* proxy = proxyController ? proxyController->GetCharacterProxy() : nullptr;
					Dig::SetPuppet(proxy && proxy->shapePhantom ? &proxy->shapePhantom->collidable : nullptr, pos.z * RE::bhkWorld::GetWorldScale());
				}

				// Minecraft's walk bob (GameRenderer.bobView), converted from a view-space pose on
				// the scene into the equivalent camera offset: sway sideways, lift, and dip the view.
				const float  phase = bobPhaseNow * 3.14159265f;
				const float  bob = bobAmountNow;
				const float  sideBlocks = -std::sin(phase) * bob * 0.5f;
				const float  liftBlocks = std::fabs(std::cos(phase) * bob);
				const float  bobPitchDeg = std::fabs(std::cos(phase - 0.2f) * bob) * 5.0f;
				const float  heading = McYawToHeading(st.yaw);
				const RE::NiPoint3 right{ std::cos(heading), -std::sin(heading), 0.0f };
				eyePos = McToSky(eyeX, eyeY, eyeZ);
				dbgFeet = McToSky(feetX, feetY, feetZ);
				dbgLift = liftBlocks * static_cast<float>(proto::kUnitsPerBlock);
				dbgSide = sideBlocks * static_cast<float>(proto::kUnitsPerBlock);
				eyePos += right * (sideBlocks * static_cast<float>(proto::kUnitsPerBlock));
				eyePos.z += liftBlocks * static_cast<float>(proto::kUnitsPerBlock);
				eyeValid = true;
				st.feetX = feetX, st.feetY = feetY, st.feetZ = feetZ;
				st.feetValid = true;

				// Minecraft's F5 camera: behind the player, or in front looking back at them, pulled
				// in wherever Minecraft's own zoom collision stopped it (its blocks and Skyrim's
				// triangles). Skyrim stays in its first-person state; only the camera moves.
				const bool  mirrored = mc.cameraMode == 2;
				const float camHeading = McYawToHeading(mirrored ? st.yaw + 180.0f : st.yaw);
				const float lookPitchDeg = mirrored ? -st.pitch : st.pitch;
				const float camPitchDeg = lookPitchDeg + bobPitchDeg;
				// Minecraft's zoom pulls in the moment something is behind the player (never
				// through a wall) and eases back out, so a ray grazing the ground can't shake it.
				const bool detached = mc.cameraMode != 0 && mc.cameraDistance > 0.0f;
				const float zoomBefore = zoom;
				if (!detached || zoomMode != mc.cameraMode || mc.cameraDistance < zoom) {
					zoom = detached ? mc.cameraDistance : 0.0f;
				} else {
					zoom += (mc.cameraDistance - zoom) * (1.0f - std::exp(-std::max(a_delta, 0.0f) / 0.2f));
				}
				zoomMode = mc.cameraMode;
				motion.zoomTravel += std::fabs(zoom - zoomBefore);
				{
					static float lastYaw = st.yaw;
					float        turn = std::fmod(st.yaw - lastYaw + 540.0f, 360.0f) - 180.0f;
					lastYaw = st.yaw;
					std::lock_guard lock(orbitLock);
					orbitPivot = eyePos;
					orbitUnits = zoom * static_cast<float>(proto::kUnitsPerBlock);
					orbitValid = detached;
					orbitYawSpeed = a_delta > 0.0f ? turn / a_delta : 0.0f;
				}
				if (detached) {
					// Along the look direction without the walk bob: Minecraft tilts the view for
					// the bob around the camera itself, not by swinging the camera around the head.
					const auto forward = Col(BuildCameraRotation(camHeading, lookPitchDeg * kDegToRad, 0.0f), 0);
					eyePos -= forward * (zoom * static_cast<float>(proto::kUnitsPerBlock));
				}
				// Skyrim treats a player whose weapons are away as yielding: guards and townsfolk stop
				// fighting and wait to talk instead. So the Skyrim player's weapons come out while
				// anyone is fighting them, and go away after. Its first-person arms and weapons stay
				// hidden meanwhile (Minecraft shows the hands).
				const auto weapons = a_player->AsActorState()->GetWeaponState();
				// SkyCraft+ : hand ownership. A hand Skyrim holds keeps its weapon or spell out and
				// Skyrim's first-person arms showing.
				const bool skyrimHand = rightHand || leftHand;
				const bool fighting = Combat::PlayerEngaged() || skyrimHand;
				// Always hidden while Minecraft drives (Minecraft draws the hands). Showing them again
				// after a fight un-hid arms Skyrim had hidden itself meanwhile (putting weapons away),
				// and they then floated in view until the model was rebuilt.
				// SkyCraft+ : and always in Minecraft's third-person camera, where they'd float in the lens.
				HideFirstPersonMeshes(a_player, !skyrimHand || detached);
				Footsteps::PerFrame(a_player, mc, true);  // SkyCraft+
				Animals::PerFrame(a_player, mc.heldFood, !st.skyrimMenuOpen, a_delta);  // SkyCraft+
				WhirlwindCharge(a_player);  // SkyCraft+
				MobFights::PerFrame();  // SkyCraft+

				const float bobRollDeg = std::sin(phase) * bob * 3.0f;
				idealRotNoRoll = BuildCameraRotation(camHeading, camPitchDeg * kDegToRad, 0.0f);
				idealRot = BuildCameraRotation(camHeading, camPitchDeg * kDegToRad, bobRollDeg * kDegToRad);
				idealValid = !st.skyrimMenuOpen;

				// Place and turn the camera together, here. Skyrim's camera update (where it's also
				// done, see PlayerCameraUpdateHook) runs before this in the frame, so on its own it
				// would leave the camera facing last frame's way: a third-person camera placed for
				// this frame's turn but facing the previous one swings the view off the player while
				// turning. Putting it at Minecraft's eye up front also keeps Skyrim's first-person
				// camera easing (~50 ms) from starting anywhere else.
				if (auto* camera = RE::PlayerCamera::GetSingleton(); camera && camera->cameraRoot) {
					auto* root = camera->cameraRoot.get();
					static bool loggedParent = false;
					if (!loggedParent) {
						loggedParent = true;
						logger::info("camera root parent: {}", root->parent ? root->parent->name.c_str() : "(none)");
					}
					if (idealValid) {
						ApplyLookRotation(root);
					}
					PinCameraAndSky();
					RE::NiUpdateData update{};
					root->UpdateDownwardPass(update, 0);
				}

				motionLogTimer -= a_delta;
				if (motionLogTimer <= 0.0f) {
					motionLogTimer = 10.0f;
					if (motion.frames > 0 && DiagnosticsEnabled()) {
						logger::info("motion: {} ticks, {} of {} frames waited for a late tick, render delay {:.1f} ms, tick stamp noise {:.2f} ms, frames {:.1f} ms avg {:.1f} max, zoom moved {:.2f} blocks{}",
							motion.ticks, motion.lateFrames, motion.frames, renderDelayMs, motion.stampSamples ? motion.stampErrMs / motion.stampSamples : 0.0,
							motion.frameMsSum / motion.frames, motion.frameMsMax, motion.zoomTravel, mc.cameraMode != 0 ? " (third person)" : "");
					}
					motion = {};
					std::lock_guard lock(orbitLock);
					if (orbit.frames > 0 && DiagnosticsEnabled()) {
						logger::info("third-person orbit: drawn camera off the orbit around the eye by {:.1f} units avg, {:.1f} max ({:.1f} avg over {} frames turning); drawn camera vs this frame's camera {:.1f} avg, {:.1f} max, over {} frames",
							orbit.missSum / orbit.frames, orbit.missMax, orbit.turning ? orbit.turnMissSum / orbit.turning : 0.0f, orbit.turning,
							orbit.staleSum / orbit.frames, orbit.staleMax, orbit.frames);
					}
					orbit = {};
				}

				devLogTimer -= a_delta;
				if (devLogTimer <= 0.0f && devCount > 0 && DiagnosticsEnabled()) {
					devLogTimer = 60.0f;
					logger::info("Skyrim's own camera rotation vs Minecraft look: mean {:.2f} deg, max {:.2f} deg over {} frames{}",
						devSum / devCount, devMax, devCount, rotValidated ? " (overridden)" : "");
					devSum = devMax = 0.0f;
					devCount = 0;
				}

				if (!st.skyrimMenuOpen) {
					a_player->data.angle.z = heading;
					a_player->data.angle.x = (st.pitch + bobPitchDeg) * kDegToRad;
					if (auto* camera = RE::PlayerCamera::GetSingleton()) {
						if (!camera->IsInFirstPerson()) {
							camera->ForceFirstPerson();
						}
						if (mc.fovDeg > 1.0f) {
							ApplyMcFov(camera, mc.fovDeg);
						}
						cameraCheckTimer -= a_delta;
						if (cameraCheckTimer <= 0.0f) {
							cameraCheckTimer = 10.0f;
							cameraCheckPending = true;  // measured in Present, after this frame rendered
						}
					}
					if (fighting && weapons == RE::WEAPON_STATE::kSheathed) {
						a_player->DrawWeaponMagicHands(true);
					} else if (!fighting && weapons == RE::WEAPON_STATE::kDrawn) {
						a_player->DrawWeaponMagicHands(false);
					}
				}
			} else {
				eyeValid = false;
				idealValid = false;
				st.feetValid = false;
				Dig::SetPuppet(nullptr, 0.0f);
				HideFirstPersonMeshes(a_player, false);
				Footsteps::PerFrame(a_player, mc, false);  // SkyCraft+
				Animals::PerFrame(a_player, 0, false, a_delta);  // SkyCraft+
				MobFights::PerFrame();  // SkyCraft+

				RestoreFov(RE::PlayerCamera::GetSingleton());
				if (auto* controller = a_player->GetCharController(); controller && savedGravity >= 0.0f) {
					controller->gravity = savedGravity;
					savedGravity = -1.0f;
				}
			}

			// SkyCraft+ : riding as Minecraft's player. In Skyrim's third-person riding camera, Skyrim's
			// body is hidden and Minecraft's (sent in its riding pose, facing the horse's way) is drawn
			// in the saddle: its hips (12 of its 16ths of a block above its feet) on Skyrim's pelvis.
			bool  rideAsMinecraft = false;
			float riderYaw = st.yaw;
			if (st.rideAsMinecraft && skyrimDrives && !st.skyrimMode && a_player->IsOnMount()) {
				auto* camera = RE::PlayerCamera::GetSingleton();
				auto* root = a_player->Get3D(false);
				auto* pelvis = root ? root->GetObjectByName("NPC Pelvis [Pelv]") : nullptr;
				if (camera && !camera->IsInFirstPerson() && pelvis) {
					rideAsMinecraft = true;
					RE::NiPoint3 seat = pelvis->world.translate;
					seat.z -= static_cast<float>(proto::kUnitsPerBlock * 0.75);
					const auto seatMc = SkyToMc(seat);
					st.feetX = seatMc.x, st.feetY = seatMc.y, st.feetZ = seatMc.z;
					st.feetValid = true;
					RE::NiPointer<RE::Actor> mount;
					const float heading = a_player->GetMount(mount) && mount ? mount->data.angle.z : a_player->data.angle.z;
					riderYaw = std::fmod(HeadingToMcYaw(heading) + 360.0f, 360.0f);
				}
			}
			if (rideAsMinecraft != st.ridingAsMinecraft) {
				st.ridingAsMinecraft = rideAsMinecraft;
				logger::info("riding: {}", rideAsMinecraft ? "Minecraft's player in the saddle, Skyrim's body hidden" : "stopped (or Skyrim's camera)");
				if (!rideAsMinecraft) {
					st.feetValid = false;
				}
			}
			HideRiderMeshes(a_player, rideAsMinecraft);

			captureTimer -= a_delta;
			hudTimer -= a_delta;
			if (hudTimer <= 0.0f || rightHand != hudRightHand || leftHand != hudLeftHand) {
				hudTimer = 0.5f;
				hudRightHand = rightHand;
				hudLeftHand = leftHand;
				HideHud(ui, puppet, rightHand || leftHand);
			}

			// Tell Minecraft where Skyrim's player is and where they're looking.
			proto::SkyState sky{};
			sky.flags = (cell ? proto::kSkyInGame : 0u) | (menu ? proto::kSkyMenuOpen : 0u) | (loading ? proto::kSkyLoading : 0u) |
			            (skyrimDrives ? proto::kSkyDrivesPlayer : 0u) | (rightHand ? proto::kSkyRightHandSkyrim : 0u) |
			            (leftHand ? proto::kSkyLeftHandSkyrim : 0u) | ((puppet || skyrimDrives) && a_player->IsBlocking() ? proto::kSkyBlocking : 0u) |
			            (st.parked ? proto::kSkyParked : 0u) | (bringingBack > 0.0f ? proto::kSkyBringBack : 0u) |
			            (rideAsMinecraft ? proto::kSkyRiding : 0u);
			static float         placeTimer = 0.0f;
			static std::uint32_t placeFlags = 0;
			static std::uint32_t placeWorld = 0;
			if ((placeTimer -= a_delta) <= 0.0f || placeWorld != worldId) {  // at once on a world change (Minecraft picks its dimension)
				placeWorld = worldId;
				placeTimer = 0.5f;
				const auto flags = PlaceFlags(a_player, cell);
				if (flags != placeFlags) {
					logger::info("place: {}{}{}{}{}{}{}", flags & proto::kPlaceInterior ? "interior " : "exterior ", flags & proto::kPlaceTamriel ? "tamriel " : "", flags & proto::kPlaceTown ? "town " : "",
						flags & proto::kPlaceSettlement ? "settlement " : "",
						flags & proto::kPlaceDungeon ? "dungeon " : "", flags & proto::kPlaceRain ? "rain " : "", flags & proto::kPlaceSnow ? "snow" : "");
				}
				placeFlags = flags;
			}
			sky.placeFlags = placeFlags;
			// SkyCraft+ : the underground lines up with Tamriel's sea level (cities share its coordinates).
			static float seaLevel = [] {
				auto* tamriel = RE::TESForm::LookupByID<RE::TESWorldSpace>(0x3C);
				return tamriel ? tamriel->GetDefaultWaterHeight() / float(proto::kUnitsPerBlock) : 0.0f;
			}();
			sky.seaLevel = seaLevel;
			sky.fed = static_cast<std::uint8_t>(st.fedPoints.load());  // SkyCraft+
			sky.handKinds[0] = rightHand ? HandKind(a_player, false) : proto::kHandNone;
			sky.handKinds[1] = leftHand ? HandKind(a_player, true) : proto::kHandNone;
			if (sky.handKinds[0] >= proto::kHandGreatsword && sky.handKinds[0] <= proto::kHandCrossbow) {
				sky.handKinds[1] = proto::kHandNone;  // two hands, one weapon
			}
			if (const float maxHealth = a_player->GetActorValueMax(RE::ActorValue::kHealth); maxHealth > 0.0f) {
				sky.healthFrac = std::clamp(a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kHealth) / maxHealth, 0.0f, 1.0f);
			}
			const auto skyMc = SkyToMc(current);
			sky.worldId = worldId;
			sky.collisionEpoch = epoch;
			sky.posX = skyMc.x;
			sky.posY = skyMc.y;
			sky.posZ = skyMc.z;
			if (sendingAway > 0.0f) {
				sky.sendToWorld = static_cast<std::uint8_t>(savedWorld);
				sky.posX = savedX;
				sky.posY = savedY;
				sky.posZ = savedZ;
			}
			sky.yaw = rideAsMinecraft ? riderYaw : st.yaw;  // SkyCraft+ : the rider faces the horse's way
			sky.pitch = st.pitch;
			sky.teleportSeq = teleportSeq;
			sky.viewportW = static_cast<std::uint32_t>(st.viewportW.load());
			sky.viewportH = static_cast<std::uint32_t>(st.viewportH.load());
			if (auto* calendar = RE::Calendar::GetSingleton()) {
				sky.gameHour = calendar->GetHour();
			}
			link.WriteSkyState(sky);
			lastSky = sky;
			lastSkyTick = GetTickCount64();

			settleTimer -= a_delta;
			// SkyCraft+ : not while parked: the empty cell's (or anything else's) collision would be solid
			// in the Nether and the End, and its unknown parts kept mobs there from pathing.
			if (haveMc && !loading && cell && settleTimer <= 0.0f && !st.parked) {
				Perf::Scope timer(Perf::kCollision);
				Collision::Get().Update(puppet ? McVec{ mc.x, mc.y, mc.z } : skyMc);
			}

			static int waterFrame = 0;
			if (haveMc && st.mcInWorld && !loading && cell && ++waterFrame % 3 == 0) {
				Perf::Scope timer(Perf::kWater);
				WriteWaterGrid(a_player, puppet ? McVec{ mc.x, mc.y, mc.z } : skyMc, worldId);
			}

			// Minecraft's torches, lava and glowstone light Skyrim's world while Minecraft is there.
			const McVec lightCentre = puppet ? McVec{ mc.x, mc.y, mc.z } : skyMc;
			{
				Perf::Scope timer(Perf::kLights);
				BlockLights::Update(haveMc && st.mcInWorld && !loading && cell ? &lightCentre : nullptr, a_delta);
			}

			// Blocks dug out of Skyrim's world: its collision around them goes to Minecraft again, and
			// its meshes are cut.
			static std::vector<Clip::Cube> dugChanged;
			dugChanged.clear();
			Dig::TakeChanged(dugChanged);
			if (!dugChanged.empty()) {
				Perf::Scope timer(Perf::kDigChanged);
				Collision::Get().DigChanged(dugChanged);
			}
			// Not while the world is still settling after a load (its cells are still being attached).
			static float digSettled = 0.0f;
			digSettled = (puppet || skyrimDrives) && !loading && cell ? digSettled + a_delta : 0.0f;
			{
				Perf::Scope timer(Perf::kDigMeshes);
				Dig::UpdateMeshes(digSettled > 3.0f ? a_player : nullptr, a_delta, !dugChanged.empty());
			}

			ReportMinecraft(mcAlive, cell && !loading && !menu, a_delta);
		}

		struct PlayerUpdateHook
		{
			static void thunk(RE::PlayerCharacter* a_this, float a_delta)
			{
				// SkyCraft+ : off Skyrim's world. Minecraft's player flown past Skyrim's invisible walls
				// took Skyrim's to where there are no cells, and Skyrim's own update crashed on the
				// missing cell (SkyrimSE+0x757322). Back where it last had one, without this update.
				static RE::NiPoint3 lastWithCell;
				static bool         haveLastWithCell = false;
				static int          rescues = 0;
				bool                offWorld = false;
				auto*               ui = RE::UI::GetSingleton();
				if (auto* cell = a_this->GetParentCell()) {
					lastWithCell = a_this->GetPosition();
					haveLastWithCell = !cell->IsInteriorCell();  // (only out in the open world)
				} else if (haveLastWithCell && State().puppeting && !State().parked && State().mcWorld == proto::kDimTamriel && a_this->Is3DLoaded() && ui &&
						   !ui->IsMenuOpen(RE::LoadingMenu::MENU_NAME)) {
					if (++rescues <= 5) {
						logger::warn("Skyrim's player is off its world (no cell): back to ({:.0f}, {:.0f}, {:.0f})", lastWithCell.x, lastWithCell.y, lastWithCell.z);
					}
					a_this->SetPosition(lastWithCell, true);
					offWorld = true;  // Skyrim's own update skipped this frame (ours still runs)
				}
				if (!offWorld) {
					func(a_this, a_delta);
				}
				try {
					Perf::Scope timer(Perf::kUpdate);
					PerFrame(a_this, a_delta);
				} catch (const std::exception& e) {
					logger::error("per-frame update: {}", e.what());
				}
				Perf::Report();
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		std::atomic<std::uint64_t> postCameraCalls{ 0 };



		// Runs after Skyrim's whole camera update: re-pin the camera anchor and the sky to
		// Minecraft's eye so nothing downstream uses Skyrim's own smoothed camera position.
		struct PlayerCameraUpdateHook
		{
			static void thunk(RE::PlayerCamera* a_this)
			{
				func(a_this);
				++postCameraCalls;
				Game::NoteFrameStep('H');
				if (!eyeValid || !a_this->cameraRoot) {
					return;
				}
				auto* root = a_this->cameraRoot.get();
				if (idealValid) {
					const auto& R = root->world.rotate;
					// Candidates: +f, -f, +u, -u, +r, -r from our pure look direction (no roll).
					const RE::NiPoint3 f = Col(idealRotNoRoll, 0), u = Col(idealRotNoRoll, 1), r = Col(idealRotNoRoll, 2);
					const std::array<RE::NiPoint3, 6> cand{ f, f * -1.0f, u, u * -1.0f, r, r * -1.0f };
					if (!rotValidated && !rotRejected) {
						for (int c = 0; c < 3; ++c) {
							int   best = -1;
							float bestAngle = 1e9f;
							for (int k = 0; k < 6; ++k) {
								const float a = AngleDeg(Col(R, c), cand[k]);
								if (a < bestAngle) {
									bestAngle = a;
									best = k;
								}
							}
							if (bestAngle < 6.0f) {
								++axisVotes[c][best];
							}
						}
						if (++axisSamples >= 240) {
							bool ok = true;
							std::array<bool, 3> used{};
							for (int c = 0; c < 3; ++c) {
								const auto it = std::ranges::max_element(axisVotes[c]);
								const int  k = static_cast<int>(it - axisVotes[c].begin());
								ok &= *it > axisSamples * 6 / 10 && !used[k / 2];
								used[k / 2] = true;
								axisMap[c] = k;
							}
							static constexpr const char* kNames[6] = { "+forward", "-forward", "+up", "-up", "+right", "-right" };
							rotValidated = ok;
							rotRejected = !ok;
							logger::info("camera root axes: col0={} col1={} col2={} -> {}", kNames[axisMap[0]], kNames[axisMap[1]], kNames[axisMap[2]],
								ok ? "Minecraft now drives camera rotation" : "inconsistent; leaving rotation to Skyrim");
						}
					}
					// Deviation of Skyrim's own (animated) camera from the pure look direction.
					float dev = 0.0f;
					for (int c = 0; c < 3; ++c) {
						dev = std::max(dev, AngleDeg(Col(R, c), cand[axisMap[c]]));
					}
					dbgRotDev = dev;
					devMax = std::max(devMax, dev);
					devSum += dev;
					++devCount;
					ApplyLookRotation(root);
				}
				PinCameraAndSky();
				// Push the final transform down to the render camera (NiCamera child).
				RE::NiUpdateData update{};
				root->UpdateDownwardPass(update, 0);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// First-person camera position = Minecraft's eye (includes sneaking height etc.).
		struct FirstPersonTranslationHook
		{
			static void thunk(RE::TESCameraState* a_this, RE::NiPoint3& a_out)
			{
				func(a_this, a_out);
				if (eyeValid) {
					a_out = eyePos;
				}
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	namespace Game
	{
		// High-rate capture: every 20 s, record 120 consecutive frames of where things really are.
		struct CaptureRow
		{
			std::int64_t qpc;
			RE::NiPoint3 eye, root, render, sky, actor, feet;
			double       t;
			std::int64_t tickQpc;
			float        lift, side, rotDev;
			float        delay, zoom;
		};
		std::vector<CaptureRow> capture;

		void CaptureFrame()
		{
			if (!eyeValid) {
				return;
			}
			auto* camera = RE::PlayerCamera::GetSingleton();
			auto* renderCam = RE::Main::WorldRootCamera();
			auto* sky = RE::Sky::GetSingleton();
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!camera || !camera->cameraRoot || !renderCam || !player) {
				return;
			}
			LARGE_INTEGER now;
			::QueryPerformanceCounter(&now);
			capture.push_back({ now.QuadPart, eyePos, camera->cameraRoot->world.translate, renderCam->world.translate,
				sky && sky->root ? sky->root->world.translate : RE::NiPoint3{}, player->GetPosition(), dbgFeet, dbgT, dbgTickQpc, dbgLift, dbgSide, dbgRotDev, float(renderDelayMs), zoom });
			if (capture.size() < 240) {
				return;
			}
			LARGE_INTEGER f;
			::QueryPerformanceFrequency(&f);
			logger::info("frame capture: dt | t newtick | feet step (xy, z) | eye step | bob lift side | sky | delay zoom; post-camera hook calls: {}", postCameraCalls.load());
			for (std::size_t i = 0; i < capture.size(); ++i) {
				const auto& r = capture[i];
				const auto& p = i ? capture[i - 1] : r;
				const double ms = double(r.qpc - p.qpc) * 1000.0 / double(f.QuadPart);
				const float  fxy = std::hypot(r.feet.x - p.feet.x, r.feet.y - p.feet.y);
				logger::info("  {:3} {:5.2f}ms | t {:5.3f} {} | feet {:5.2f} {:6.2f} | eye {:5.2f} | bob {:5.2f} {:5.2f} | sky {:4.1f} | {:4.1f}ms {:5.3f}",
					i, ms, r.t, r.tickQpc != p.tickQpc ? "NEW" : "   ", fxy, r.feet.z - p.feet.z, r.eye.GetDistance(p.eye), r.lift, r.side, r.sky.GetDistance(r.eye), r.delay, r.zoom);
			}
			capture.clear();
			captureTimer = 20.0f;
		}

		void NoteFrameStep(char a_step)
		{
			if (frameOrderLogged || !State().puppeting || !DiagnosticsEnabled()) {
				return;
			}
			std::lock_guard lock(orbitLock);
			frameOrder += a_step;
			if (frameOrder.size() >= 60) {
				frameOrderLogged = true;
				logger::info("frame order (P player update, H camera update, D block drawing, X present): {}", frameOrder);
			}
		}

		void NoteRenderedCamera(const RE::NiPoint3& a_pos, const RE::NiMatrix3& a_rot)
		{
			std::lock_guard lock(orbitLock);
			if (!orbitValid) {
				return;
			}
			// Where the camera would be if it orbited the eye along the direction it's looking.
			const RE::NiPoint3 forward{ a_rot.entry[0][0], a_rot.entry[1][0], a_rot.entry[2][0] };
			const float        miss = a_pos.GetDistance(orbitPivot - forward * orbitUnits);
			const float        stale = a_pos.GetDistance(eyePos);
			++orbit.frames;
			orbit.missSum += miss;
			orbit.missMax = std::max(orbit.missMax, miss);
			orbit.staleSum += stale;
			orbit.staleMax = std::max(orbit.staleMax, stale);
			if (std::fabs(orbitYawSpeed) > 60.0f) {
				++orbit.turning;
				orbit.turnMissSum += miss;
			}
		}

		void CheckRenderedCamera()
		{
			NoteFrameStep('X');
			if (!DiagnosticsEnabled()) {
				return;
			}
			if (dbgJumpTrigger && capture.empty()) {
				dbgJumpTrigger = false;
				captureTimer = 0.0f;
				logger::info("{}; capturing", dbgTriggerReason);
			}
			if (captureTimer <= 0.0f || !capture.empty()) {
				CaptureFrame();
			}
			if (!cameraCheckPending.exchange(false) || !eyeValid) {
				return;
			}
			auto* camera = RE::PlayerCamera::GetSingleton();
			if (!camera || !camera->cameraRoot) {
				return;
			}
			const auto& cam = camera->cameraRoot->world.translate;
			logger::info("camera check (same frame): rendered ({:.1f} {:.1f} {:.1f}) vs Minecraft eye ({:.1f} {:.1f} {:.1f}), off by {:.1f} units",
				cam.x, cam.y, cam.z, eyePos.x, eyePos.y, eyePos.z, cam.GetDistance(eyePos));
			if (auto* sky = RE::Sky::GetSingleton(); sky && sky->root) {
				const auto& s = sky->root->world.translate;
				logger::info("  sky root ({:.1f} {:.1f} {:.1f}), {:.1f} units from the camera", s.x, s.y, s.z, s.GetDistance(cam));
			}
		}

		void Install()
		{
			REL::Relocation<std::uintptr_t> playerVtbl{ RE::VTABLE_PlayerCharacter[0] };
			PlayerUpdateHook::func = playerVtbl.write_vfunc(0xAD, PlayerUpdateHook::thunk);

			// PlayerCamera::Update is called directly (not through the vtable), so hook its call
			// sites: scan the game's code for `call PlayerCamera::Update` and redirect each one.
			{
				const auto target = REL::Relocation<std::uintptr_t>{ RELOCATION_ID(49852, 50784) }.address();
				const auto text = REL::Module::get().segment(REL::Segment::textx);
				const auto base = text.address();
				const auto* code = reinterpret_cast<const std::uint8_t*>(base);
				std::vector<std::uintptr_t> sites;
				for (std::size_t i = 0; i + 5 <= text.size(); ++i) {
					if (code[i] != 0xE8) {
						continue;
					}
					std::int32_t rel;
					std::memcpy(&rel, code + i + 1, 4);
					if (base + i + 5 + static_cast<std::intptr_t>(rel) == target) {
						sites.push_back(base + i);
					}
				}
				auto& trampoline = SKSE::GetTrampoline();
				for (const auto site : sites) {
					PlayerCameraUpdateHook::func = trampoline.write_call<5>(site, PlayerCameraUpdateHook::thunk);
				}
				logger::info("PlayerCamera::Update: hooked {} call site(s)", sites.size());
			}

			REL::Relocation<std::uintptr_t> fpVtbl{ RE::VTABLE_FirstPersonState[0] };
			FirstPersonTranslationHook::func = fpVtbl.write_vfunc(0x5, FirstPersonTranslationHook::thunk);

			Collision::Get().Start();
			Combat::Install();
			logger::info("game hooks installed");
		}

		bool SkyrimMenuOpen()
		{
			auto* ui = RE::UI::GetSingleton();
			return ui && AnyBlockingMenuOpen(ui);
		}

		// SkyCraft+ : hand ownership. With one hand Skyrim's and the other Minecraft's, Skyrim's
		// first-person arm on Minecraft's side is collapsed (its upper arm bone scaled to almost
		// nothing, which takes the forearm and hand with it) right before the frame is drawn, after
		// all of the frame's animation: Minecraft draws that hand. Put back when it isn't needed.
		void BeforeRenderWorld()
		{
			static std::vector<RE::NiPointer<RE::NiAVObject>> collapsed;
			auto&                                             st = State();
			auto*                                             player = RE::PlayerCharacter::GetSingleton();
			std::vector<RE::NiAVObject*>                      bones;
			if (player && st.puppeting && (st.rightHandSkyrim || st.leftHandSkyrim)) {
				if (auto* root = player->Get3D(true)) {
					auto* left = root->GetObjectByName("NPC L UpperArm [LUar]");
					auto* right = root->GetObjectByName("NPC R UpperArm [RUar]");
					// SkyCraft+ : third person (Minecraft's F5): Skyrim's camera stays a first-person
					// one, so its arms floated in front of it. Both go; Minecraft draws the body.
					const bool thirdPerson = mc.cameraMode != 0;
					if ((thirdPerson || !st.leftHandSkyrim) && left) {
						bones.push_back(left);
					}
					if ((thirdPerson || !st.rightHandSkyrim) && right) {
						bones.push_back(right);
					}
				}
			}
			RE::NiUpdateData update{};
			for (auto& bone : collapsed) {
				if (bone && std::ranges::find(bones, bone.get()) == bones.end()) {
					bone->local.scale = 1.0f;
					bone->Update(update);
				}
			}
			std::erase_if(collapsed, [&](const RE::NiPointer<RE::NiAVObject>& a_bone) { return std::ranges::find(bones, a_bone.get()) == bones.end(); });
			for (auto* bone : bones) {
				if (std::ranges::none_of(collapsed, [&](const RE::NiPointer<RE::NiAVObject>& a_bone) { return a_bone.get() == bone; })) {
					logger::info("hands: a Skyrim first-person arm hidden ({})", mc.cameraMode != 0 ? "third person" : "Minecraft's hand");
					collapsed.emplace_back(bone);
				}
				bone->local.scale = 0.001f;
				bone->Update(update);
			}
		}

		// SkyCraft+ : Whirlwind Sprint (see WhirlwindKey).
		void ShoutKey(bool a_down) { WhirlwindKey(a_down); }

		void ReportPause()
		{
			auto*      ui = RE::UI::GetSingleton();
			const auto now = GetTickCount64();
			const auto last = lastSkyTick.load();
			// Only once the per-frame update has really stopped (it writes the state otherwise).
			if (!ui || !ui->GameIsPaused() || last == 0 || now - last < 150) {
				return;
			}
			auto sky = lastSky;
			sky.flags |= proto::kSkyPaused | proto::kSkyMenuOpen;
			Link::Get().WriteSkyState(sky);
		}

		// SkyCraft+ : mode switch. Skyrim mode is one more reason Skyrim takes the player
		// (SkyrimTakeover): its controls, camera, HUD, weapons and magic, while Minecraft's player
		// follows along. Back in Minecraft mode Minecraft picks the player up where Skyrim's is.
		void ToggleSkyrimMode()
		{
			auto& st = State();
			if (!st.skyrimMode && !st.mcInWorld) {
				return;  // nothing to switch from: Skyrim already has the player
			}
			if (!st.skyrimMode && (st.parked || st.mcWorld >= proto::kDimNether)) {
				// SkyCraft+ : the Nether and the End have nothing of Skyrim's to walk on.
				RE::SendHUDMessage::ShowHUDMessage("SkyCraft: Skyrim mode isn't available in Minecraft's own worlds");
				return;
			}
			st.skyrimMode = !st.skyrimMode;
			Input::ReleaseAll();
			logger::info("mode switch: {} mode", st.skyrimMode ? "Skyrim" : "Minecraft");
			RE::SendHUDMessage::ShowHUDMessage(st.skyrimMode ? "SkyCraft: Skyrim mode" : "SkyCraft: Minecraft mode");
		}

		void OnGameLoaded()
		{
			MapMarkers::OnGameLoaded();
			MinecraftEnemies::OnGameLoaded();
			MobFights::OnGameLoaded();  // SkyCraft+
			teleportPending = true;
			homeCheck = true;
			bringingBack = 0.0f;
			sendingAway = 0.0f;
			haveLastSet = false;
			State().lookInitialized = false;
		}

		// SkyCraft+ : saves made in the Nether or the End keep which, and where (the SKSE co-save).
		constexpr std::uint32_t kWorldRecord = 'MCWD';

		void OnSave(SKSE::SerializationInterface* a_intfc)
		{
			Animals::SaveBabies(a_intfc);  // SkyCraft+
			Animals::SaveWalks(a_intfc);  // SkyCraft+
			Crops::Save(a_intfc);  // SkyCraft+
			if (!State().parked || mc.dimension < proto::kDimNether) {
				return;
			}
			const std::uint32_t world = mc.dimension;
			if (!a_intfc->OpenRecord(kWorldRecord, 1) || !a_intfc->WriteRecordData(world) || !a_intfc->WriteRecordData(mc.x) || !a_intfc->WriteRecordData(mc.y) ||
				!a_intfc->WriteRecordData(mc.z)) {
				logger::warn("couldn't keep Minecraft's world in the save");
				return;
			}
			logger::info("saved in {} at ({:.0f}, {:.0f}, {:.0f})", world == proto::kDimNether ? "the Nether" : "the End", mc.x, mc.y, mc.z);
		}

		void OnLoad(SKSE::SerializationInterface* a_intfc)
		{
			std::uint32_t type = 0, version = 0, length = 0;
			while (a_intfc->GetNextRecordInfo(type, version, length)) {
				std::uint32_t world = 0;
				double        x = 0.0, y = 0.0, z = 0.0;
				if (type == 'CROP') {
					Crops::Load(a_intfc);  // SkyCraft+
					continue;
				}
				if (type == 'BABY') {
					Animals::LoadBabies(a_intfc);  // SkyCraft+
					continue;
				}
				if (type == 'WALK') {
					Animals::LoadWalks(a_intfc);  // SkyCraft+
					continue;
				}
				if (type == kWorldRecord && version == 1 && a_intfc->ReadRecordData(world) && a_intfc->ReadRecordData(x) && a_intfc->ReadRecordData(y) &&
					a_intfc->ReadRecordData(z) && (world == proto::kDimNether || world == proto::kDimEnd)) {
					savedWorld = world;
					savedX = x, savedY = y, savedZ = z;
					savedCheck = true;
					logger::info("this save was made in {} at ({:.0f}, {:.0f}, {:.0f})", world == proto::kDimNether ? "the Nether" : "the End", x, y, z);
				}
			}
		}

		void OnRevert(SKSE::SerializationInterface*)
		{
			Animals::ClearBabies();  // SkyCraft+
			Animals::ClearWalks();  // SkyCraft+
			MobFights::Clear();  // SkyCraft+
			Crops::Clear();  // SkyCraft+
			savedWorld = 0;
			savedCheck = false;
			sendingAway = 0.0f;
		}
	}
}
