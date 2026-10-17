#pragma once

#include "Link.h"

namespace skycraft
{
	// Shared runtime state between the per-frame update, input sink and renderer hook.
	struct Runtime
	{
		// Minecraft is connected, in its world, has acknowledged our last teleport, and Skyrim
		// isn't loading: MC's player position drives Skyrim's player.
		std::atomic<bool> puppeting{ false };
		// The same, or waiting for Minecraft to arrive after a teleport: Skyrim's own controls
		// don't move its player either way (it would wander off from where Minecraft is going).
		std::atomic<bool> minecraftOwnsPlayer{ false };
		// A Minecraft GUI screen (inventory, chat, ...) is open: mouse moves MC's cursor.
		std::atomic<bool> mcScreenOpen{ false };
		// SkyCraft+ : controller inventory snapping. The screen open has item slots.
		std::atomic<bool> mcSlotScreen{ false };
		// A Skyrim menu (journal, dialogue, console, loading, ...) owns input.
		std::atomic<bool> skyrimMenuOpen{ false };
		// MC reports its player is in a world (overlay should be drawn).
		std::atomic<bool> mcInWorld{ false };
		// SkyCraft+ : mode switch. The player chose Skyrim mode (sModeToggleKey in SkyCraft.ini).
		std::atomic<bool> skyrimMode{ false };
		// Skyrim drives its own player (Skyrim mode, furniture, riding, scenes) while Minecraft is in
		// its world and follows: Minecraft's blocks are still drawn and still block NPCs.
		std::atomic<bool> skyrimDrives{ false };
		// SkyCraft+ : hand ownership (bSkyrimHands in SkyCraft.ini). While Minecraft drives, a hand
		// with something equipped in Skyrim (weapon, spell, shield, torch) is Skyrim's: Skyrim draws
		// it and gets its mouse button (left: right hand, right: left hand).
		std::atomic<bool> skyrimHandsOption{ false };
		std::atomic<bool> rightHandSkyrim{ false };
		std::atomic<bool> leftHandSkyrim{ false };
		// SkyCraft+ : Minecraft's crosshair is on something used by hand (chest, door, villager...):
		// right click goes to Minecraft even with a Skyrim left hand.
		std::atomic<bool> mcUseTarget{ false };
		// SkyCraft+ : Minecraft's player health / max (Skyrim's is held at it while Minecraft drives).
		std::atomic<float> mcHealthFrac{ 1.0f };
		// SkyCraft+ : the Nether and the End ([Worlds] in SkyCraft.ini). While Minecraft's player is in
		// a world of Minecraft's own, Skyrim's waits in an empty cell (parked) and only Minecraft is
		// drawn; mcWorld is where Minecraft's player is (proto::McDimension).
		std::atomic<bool>          minecraftWorldsOption{ true };
		std::atomic<std::uint32_t> waitingCellId{ 0x6F3EB };
		std::atomic<std::uint32_t> mcWorld{ 0 };
		std::atomic<bool>          parked{ false };
		// SkyCraft+ : Skyrim enemies in Minecraft's own worlds ([Worlds] in SkyCraft.ini). Base forms (leveled
		// lists or actors) for the Nether and for the End's outer islands; at most enemyCap at once, one
		// tried about every enemySeconds.
		bool                       skyrimEnemiesOption{ true };
		std::vector<RE::FormID>    netherEnemies;
		std::vector<RE::FormID>    endEnemies;
		int                        enemyCap{ 6 };
		float                      enemySeconds{ 12.0f };
		// SkyCraft+ : Minecraft's structures marked on Skyrim's map and compass ([Worlds] bStructureMarkers).
		bool                       structureMarkersOption{ true };
		// SkyCraft+ : the Nether and the End: Minecraft's blocks drawn before Skyrim's effects ([Worlds] bBlocksBeforeEffects).
		bool                       blocksBeforeEffects{ true };
		// SkyCraft+ : the lava flicker. Skyrim's ambient occlusion off in the Nether and the End ([Worlds] bNoAmbientOcclusionAway).
		bool                       noAmbientOcclusionAway{ true };
		// SkyCraft+ : most of Minecraft's health one Skyrim hit may take ([Combat] fMaxHitHealth; 0: no cap).
		float                      maxHitHealth{ 10.0f };
		// SkyCraft+ : balancing. A Minecraft hit on a Skyrim actor is worth this much Skyrim damage per point,
		// plus the second per level of the actor ([Combat] fMinecraftDamage, fMinecraftDamagePerLevel; upstream: 5, 0.25).
		float                      mcDamageBase{ 2.5f };
		float                      mcDamagePerLevel{ 0.125f };
		// SkyCraft+ : balancing. Skyrim weapons' hits on Minecraft's mobs are multiplied by this ([Combat] fSkyrimWeaponsVsMinecraft).
		float                      skyrimVsMinecraft{ 1.5f };
		// SkyCraft+ : Minecraft's attacks break Skyrim's breakable objects, spider webs and the like ([Combat] bMinecraftHitsObjects).
		bool                       minecraftHitsObjects{ true };
		bool                       enderDragonSoul{ true };  // SkyCraft+ : bEnderDragonSoul
		// SkyCraft+ : riding Skyrim's horses as Minecraft's player ([Controls] bRideAsMinecraft).
		bool                       rideAsMinecraft{ true };
		bool                       ridingAsMinecraft{ false };  // this frame: Minecraft's body in the saddle
		// SkyCraft+ : the underground. Skyrim's land is more than two blocks above the player (a mine).
		std::atomic<bool>          playerUnderLand{ false };
		// SkyCraft+ : performance. Deep down, Skyrim's world isn't drawn ([Worlds] bHideWorldDeep).
		bool                       hideWorldDeepOption{ true };

		// Look direction in MC degrees; integrated from raw mouse input (main thread).
		float yaw{ 0.0f };
		float pitch{ 0.0f };
		bool  lookInitialized{ false };
		float sensitivity{ 0.5f };

		// Virtual MC cursor (overlay pixels) while an MC screen is open.
		std::atomic<int> cursorX{ 0 };
		std::atomic<int> cursorY{ 0 };
		std::atomic<int> viewportW{ 1920 };
		std::atomic<int> viewportH{ 1080 };

		// The player's feet as the camera sees them this frame (Minecraft coordinates, Skyrim's
		// own interpolation of Minecraft's ticks). The third-person body is drawn here. Main thread.
		double feetX{ 0.0 }, feetY{ 0.0 }, feetZ{ 0.0 };
		bool   feetValid{ false };

		// Minecraft's crosshair is on screen (first person, no Minecraft screen open) at this GUI
		// scale: the overlay draws it inverted against Skyrim's picture, as Minecraft does.
		std::atomic<bool> mcCrosshair{ false };
		std::atomic<int>  mcGuiScale{ 0 };
	};

	Runtime& State();

	namespace Game
	{
		void Install();
		void OnGameLoaded();
		// SkyCraft+ : the SKSE co-save. A save made in the Nether or the End keeps which, and where.
		void OnSave(SKSE::SerializationInterface* a_intfc);
		void OnLoad(SKSE::SerializationInterface* a_intfc);
		void OnRevert(SKSE::SerializationInterface* a_intfc);
		// A Skyrim menu that takes the mouse or pauses the game is open (checked live: while it
		// pauses the game, the per-frame update that normally tracks it doesn't run).
		bool SkyrimMenuOpen();
		// SkyCraft+ : mode switch. Switches between Minecraft mode and Skyrim mode (needs Minecraft
		// in its world).
		void ToggleSkyrimMode();
		// SkyCraft+ : hand ownership. Right before Skyrim draws the frame (after all its animation):
		// hides Skyrim's first-person arm on the side Minecraft holds.
		void BeforeRenderWorld();
		// SkyCraft+ : from Present (which runs while the game is paused, unlike the player update):
		// while a Skyrim menu pauses the game, Minecraft is told to pause too.
		void ReportPause();
		// Called from Present: logs rendered camera vs Minecraft eye when a check is due.
		void CheckRenderedCamera();
		// Third-person diagnostics, from where the blocks are drawn: the camera Skyrim renders this
		// frame, and the order of the frame's steps ('P' player update, 'H' camera update hook,
		// 'D' block drawing, 'X' Present).
		void NoteRenderedCamera(const RE::NiPoint3& a_pos, const RE::NiMatrix3& a_rot);
		void NoteFrameStep(char a_step);
	}

	// SkyCraft.ini [Debug] bDiagnostics: the detailed per-frame timing, camera, combat and lighting logs
	// used while developing (several lines a second). Off by default.
	bool DiagnosticsEnabled();

	namespace CrashLog
	{
		// Logs where Skyrim crashed (and writes a minidump) if it does.
		void Install();
	}

	namespace Launcher
	{
		// At plugin load: starts Minecraft (per SkyCraft.ini) unless it's already running.
		void StartMinecraft();

		enum class Status
		{
			kOff,         // not started by SkyCraft (bStartWithSkyrim = 0)
			kRunning,     // was already running
			kStarting,    // being started
			kSignIn,      // first start of the bundled Minecraft: Prism asks for a Microsoft account
			kNoLauncher,  // nothing to start it with
			kFailed,      // starting it failed
		};
		Status GetStatus();

		// A Minecraft with the SkyCraft mod is running (it holds SkyCraft's mutex from early on).
		bool MinecraftRunning();
		// Prism Launcher is running (downloading, waiting on a sign-in or showing an error).
		bool PrismRunning();
	}

	namespace Input
	{
		void Install();
		// Mouse deltas accumulated since the last frame (main thread).
		void ConsumeLook(float& a_dx, float& a_dy);
		// SkyCraft+ : controller. Held sticks, once a frame (main thread): movement keys, look, cursor.
		void PadFrame(float a_delta);
		// Tells MC to release everything (input focus moved to Skyrim).
		void ReleaseAll();
	}

	namespace Input
	{
		// While Minecraft drives the player, Skyrim's Activate prompt shows G (our activate key).
		void SetActivatePromptKey(bool a_minecraftControls);
	}

	namespace Combat
	{
		void Install();
		// Main thread, once per frame: actor table out, Minecraft's hits in, player damage bridged.
		void PerFrame(RE::PlayerCharacter* a_player, bool a_puppeting, float a_delta);
		// Someone nearby is fighting the player (in combat with them as its target). Main thread.
		bool PlayerEngaged();
		// SkyCraft+ : main thread, every frame: keeps watching the player's melee HitFrames, so
		// Skyrim weapons hit Minecraft's mobs too.
		void WatchPlayerHits(RE::PlayerCharacter* a_player, float a_delta);
		// SkyCraft+ : Minecraft hits on Skyrim objects. Any thread: Minecraft's attack was pressed
		// (left click, RT); the next PerFrame hits the breakable Skyrim object in front, if any.
		void MinecraftSwing();
	}

	namespace SkyrimShots
	{
		// SkyCraft+ : main thread, every frame: the Skyrim player's projectiles (spells, shouts,
		// arrows) out to Minecraft, which hits its mobs with them. a_active: Minecraft is in its world.
		void PerFrame(RE::PlayerCharacter* a_player, bool a_active, float a_delta);
	}

	namespace BlockLights
	{
		// Render messages (main thread): a section's light-emitting blocks; a world change.
		void OnLights(const std::uint8_t* a_data, std::uint32_t a_bytes);
		void Clear();
		// Main thread, every frame: Skyrim point lights on the brightest emitters near the player
		// (Minecraft coords; null turns them all off).
		void Update(const McVec* a_player, float a_delta);
		// One of those lights (the block shader leaves them out: Minecraft's blocks carry
		// Minecraft's own block light).
		bool IsOurs(const RE::NiLight* a_light);
		// What a Minecraft block does to whoever stands in it (proto::BlockHazard: lava, fire, magma).
		std::uint8_t HazardAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z);
	}

	namespace PathAvoid
	{
		// Hooks Skyrim's NPC path setup so paths route around Minecraft's solid blocks.
		void Install();
		// The goal of the NPC's latest path (Skyrim position), if one was seen. Any thread.
		bool GoalOf(RE::FormID a_actor, RE::NiPoint3& a_out);
	}

	namespace NpcBlocks
	{
		// Render messages (main thread): a section's solid Minecraft blocks; a world change.
		void OnSolids(const std::uint8_t* a_data, std::uint32_t a_bytes);
		// SkyCraft+ : exact block shapes. A section's boxes for blocks that aren't full cubes.
		void OnShapes(const std::uint8_t* a_data, std::uint32_t a_bytes);
		void Clear();
		bool SolidAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z);
		// SkyCraft+ : exact block shapes (any thread). -1: no solid block; 0: a full cube; n: a block
		// made of n collision boxes, appended to a_out (if given).
		int ShapeAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z, std::vector<proto::RenShapeBox>* a_out);
		// SkyCraft+ : exact block shapes (any thread). A wall for NPCs: not a step (slab, stair,
		// carpet) and not something mobs walk through (open door).
		bool BlocksBody(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z);
		// For the blocks' contact shadows (any thread): sets a_bit in a_out[x + w*(y + h*z)] for
		// each solid block of the box at a_origin (Minecraft block coords) of a_size blocks. The
		// generation changes whenever any section's blocks do.
		void CopySolids(const std::int32_t a_origin[3], const std::int32_t a_size[3], std::uint32_t* a_out, std::uint32_t a_bit);
		std::uint32_t Generation();
		// Main thread, every frame: NPCs overlapping solid Minecraft blocks are pushed back out.
		void PushActorsOut(RE::PlayerCharacter* a_player, float a_delta);
	}

	// SkyCraft+ : performance. Deep underground Skyrim's objects and distant land aren't drawn.
	namespace DeepHide
	{
		// Main thread, every frame.
		void PerFrame(RE::PlayerCharacter* a_player, bool a_deep, float a_delta);
	}

	// SkyCraft+ : Minecraft's structures on Skyrim's map and compass.
	namespace MapMarkers
	{
		// Main thread, every frame: marks the structures Minecraft listed (structures.txt) once per loaded game.
		void PerFrame(RE::PlayerCharacter* a_player, float a_delta);
		void OnGameLoaded();
	}

	// SkyCraft+ : Skyrim's inventory in Minecraft's (SkyInventory.cpp).
	namespace SkyInventory
	{
		// Main thread, every frame: keeps proto::SkyInventory current while Minecraft wants it.
		void PerFrame(RE::PlayerCharacter* a_player, bool a_wanted, float a_delta);
		// Main thread: a click in one of Minecraft's Skyrim item screens (kEvSkyItem).
		void Apply(RE::PlayerCharacter* a_player, const proto::McEvent& a_event);
	}

	// SkyCraft+ : Skyrim's item pictures (IconRender.cpp).
	namespace IconRender
	{
		// Main thread: the things listed for Minecraft (pictures for these, and the categories').
		void Want(const proto::SkyItem* a_items, std::size_t a_count);
		// Main thread, while Minecraft wants Skyrim's inventory: loads the models still to draw.
		void PerFrame();
		// Present: draws them and hands the pictures to Minecraft.
		void Draw(ID3D11Device* a_device, ID3D11DeviceContext* a_context);
	}

	// SkyCraft+ : Skyrim enemies in Minecraft's own worlds (the Nether, the End's outer islands).
	namespace MinecraftEnemies
	{
		// Main thread, every frame: spawns, steers and clears Skyrim enemies around the parked player
		// (feet in Minecraft coordinates).
		void PerFrame(RE::PlayerCharacter* a_player, float a_delta, bool a_parked, std::uint32_t a_world, double a_x, double a_y, double a_z);
		// Main thread, every frame while Minecraft is in its world: other people's projectiles stop at
		// Minecraft's solid blocks.
		void StopShotsAtBlocks(RE::PlayerCharacter* a_player);
		void OnGameLoaded();
	}

	namespace WorldRender
	{
		// Hooks Skyrim's frame so blocks are drawn into its scene before post-processing.
		void Install();
		// A Minecraft arrow stuck in a Skyrim actor where it hit (MC coords, flight yaw/pitch):
		// pinned to the nearest bone and drawn from then on. Main thread.
		void StickArrow(RE::FormID a_actor, float a_x, float a_y, float a_z, float a_yawDeg, float a_pitchDeg);
		// Main thread, every frame: once the Skyrim player is dead, their Minecraft body replaces
		// Skyrim's on its ragdoll (a_minecraftBody: Minecraft is connected and in its world).
		void UpdateRagdoll(RE::PlayerCharacter* a_player, bool a_minecraftBody);
		// Present hook, before the overlay: drains Minecraft's meshes; draws the blocks, arrows,
		// items and the outline here only if they couldn't be drawn inside Skyrim's frame.
		void Draw(ID3D11Device* a_device, ID3D11DeviceContext* a_context, IDXGISwapChain* a_swapChain);
		// Debug: saves the frame as a PNG when <SKSE log dir>/skycraft_capture.request exists.
		void CaptureIfRequested(ID3D11DeviceContext* a_context, IDXGISwapChain* a_swapChain);
		void AfterPresent();  // a frame shown: ready the next (the Nether and the End)
	}

	namespace Overlay
	{
		void Install();
	}
}
