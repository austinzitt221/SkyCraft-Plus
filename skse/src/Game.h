#pragma once

#include "Link.h"

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

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
		// Minecraft's world things (placed blocks, mobs, dropped items, arrows, particles, chests, ...) are drawn into Skyrim's frame: while
		// Minecraft drives the player (puppeting), and also in Skyrim's control mode, where Minecraft's player only follows Skyrim's but its
		// world is still there to see. (Not Minecraft's hand, HUD or screens, which are the overlay's and stay off in Skyrim's mode.)
		std::atomic<bool> worldVisible{ false };
		// SkyCraft+ : hand ownership (bSkyrimHands in SkyCraft.ini). While Minecraft drives, a hand
		// with something equipped in Skyrim (weapon, spell, shield, torch) is Skyrim's: Skyrim draws
		// it and gets its mouse button (left: right hand, right: left hand).
		std::atomic<bool> skyrimHandsOption{ false };
		std::atomic<bool> rightHandSkyrim{ false };
		std::atomic<bool> leftHandSkyrim{ false };
		// SkyCraft+ : Minecraft's crosshair is on something used by hand (chest, door, villager...):
		// right click goes to Minecraft even with a Skyrim left hand.
		std::atomic<bool> mcUseTarget{ false };
		std::atomic<float> mcUseDistance{ 0.0f };  // SkyCraft+ : how far (blocks) that is (McState::useDistance; 0 none)
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
		// SkyCraft+ : Skyrim footsteps for Minecraft's walking ([Controls] bMinecraftFootsteps).
		bool                       minecraftFootsteps{ true };
		// SkyCraft+ : Skyrim animals follow Minecraft food ([Controls] bAnimalsFollowFood).
		bool                       animalsFollowFood{ true };
		// SkyCraft+ : hunger points Skyrim food and stamina potions gave (SkyState::fed; [Combat] bSkyrimFoodFeeds).
		std::atomic<std::uint32_t> fedPoints{ 0 };
		bool                       skyrimFoodFeeds{ true };
		// SkyCraft+ : the settings screen. Up each time SkyCraft.ini is changed from it (settings read once take it up again).
		std::atomic<std::uint32_t> iniGeneration{ 0 };
		float                      skyrimFoodHunger{ 1.5f };  // SkyCraft+ : [Combat] fSkyrimFoodHunger
		// SkyCraft+ : Minecraft loot chests in Skyrim's dungeons ([Worlds] bLootChests).
		bool                       lootChestsOption{ true };
		// SkyCraft+ : the underground. Skyrim's land is more than two blocks above the player (a mine).
		std::atomic<bool>          playerUnderLand{ false };
		// SkyCraft+ : performance. Deep down, Skyrim's world isn't drawn ([Worlds] bHideWorldDeep).
		bool                       hideWorldDeepOption{ true };
		// SkyCraft+ : underground, Minecraft blocks lit as in Minecraft ([Worlds] bMinecraftLightUnderground).
		bool                       minecraftLightUnderground{ true };

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
		// SkyCraft+ : Whirlwind Sprint. Skyrim's Shout key went down or up (acted on in Minecraft mode).
		void ShoutKey(bool a_down);
		// SkyCraft+ : beds (kEvSlept, kEvGoToBed).
		void SleptInMinecraft();
		void GoToBed(RE::PlayerCharacter* a_player, const proto::McEvent& a_ev);
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
		// Adds the input sink early (kInputLoaded) so it runs ahead of other mods' sinks.
		void InstallSink();
		// Mouse deltas accumulated since the last frame (main thread).
		void ConsumeLook(float& a_dx, float& a_dy);
		// SkyCraft+ : controller. Held sticks, once a frame (main thread): movement keys, look, cursor.
		void PadFrame(float a_delta);
		// Tells MC to release everything (input focus moved to Skyrim).
		void ReleaseAll();
		// The key that switches between Minecraft's controls and Skyrim's, as the player wrote it in SkyCraft.ini ("F8"), for the corner messages.
		const std::string& ToggleKeyName();
		// SkyCraft+ : SkyCraft.ini changed (the settings screen): its [Controls], [Worlds] and [Combat] again.
		void ReloadSettings();
	}

	// SkyCraft+ : Skyrim geo placed as Minecraft blocks (GeoTextures.cpp).
	namespace GeoTextures
	{
		// Render thread: Minecraft asked for one of Skyrim's textures in a rect of the atlas (kRenSkyrimTexture).
		void Request(const std::uint8_t* a_data, std::uint32_t a_bytes);
		// Main thread, every frame: the textures asked for, loaded.
		void PerFrameMain();
		// Render thread: one loaded, to draw into its rect.
		bool TakeReady(std::uint32_t& a_x, std::uint32_t& a_y, std::uint32_t& a_w, std::uint32_t& a_h, RE::NiPointer<RE::NiTexture>& a_texture);
		// Main thread: the diffuse texture of Skyrim's geometry at a Minecraft point (a_kind 1 land, 2 an object; empty: none known).
		std::string TextureAt(double a_x, double a_y, double a_z, int& a_kind, int a_hit);  // a_hit: what Minecraft saw there, 1 the land, 2 something on it, 0 not known
		// Every land texture, for the creative menu's Skyrim geo (kSkyGeo).
		void Catalog(std::vector<proto::SkyItem>& a_items);
	}

	// SkyCraft+ : the SkyCraft+ settings screen in Minecraft's pause menu (Settings.cpp).
	namespace Settings
	{
		// Main thread, every frame: SkyCraft.ini's text for Minecraft (at first), and the changes it sends.
		void PerFrame();
	}

	namespace Input
	{
		// While Minecraft drives the player, Skyrim's Activate prompt shows G (our activate key).
		void SetActivatePromptKey(bool a_minecraftControls);
	}

	namespace LockOn
	{
		// At kDataLoaded: finds True Directional Movement's virtual-target interface, if it has one.
		void Install();
		// Main thread, every frame: hands Minecraft's mobs to True Directional Movement as lock-on targets while a_active (Skyrim's
		// own control mode, Minecraft's world around the player), and takes them back when not.
		void Update(RE::PlayerCharacter* a_player, bool a_active);
		// SkyCraft+ : SkyCraft.ini changed: [LockOn]'s aim and range again (turning it on needs a restart).
		void Reload();
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
		// SkyCraft+ : the shouts and spells on the player Minecraft acts out (proto::SelfEffects).
		std::uint32_t SelfEffects(RE::PlayerCharacter* a_player);
		// SkyCraft+ : Minecraft enchantments on the Skyrim bow in hand (kEvBowEnchants flags).
		void SetBowEnchants(std::uint32_t a_flags);
		// SkyCraft+ : a shout on the player that reaches all around them, for Minecraft's mobs.
		void AroundPlayer(RE::PlayerCharacter* a_player, RE::MagicItem* a_spell);
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

		// Minecraft's exact collision boxes (blocks, absolute Minecraft coordinates, y up). A block that isn't a full cube (slab, stair, fence,
		// carpet...) has its own boxes; a full one has one cube. They come from the same per-section data as ShapeAt (proto::kRenShapes).
		struct BlockBox
		{
			float lo[3], hi[3];
		};
		static constexpr int kMaxBoxes = 16;  // (a block with more has the rest dropped; vanilla blocks have at most a handful)
		// Boxes of block (x, y, z) in block-local coordinates (0..1, fences up to 1.5); 0 if not solid.
		int BoxesAt(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z, BlockBox a_out[kMaxBoxes]);
		// Every box of every solid block in the given block range (inclusive), absolute coordinates.
		void GatherBoxes(int a_x0, int a_x1, int a_y0, int a_y1, int a_z0, int a_z1, std::vector<BlockBox>& a_out);
		// Every section that has solid blocks, with a stamp that changes whenever its blocks (or their shapes) do.
		struct SectionStamp
		{
			std::int32_t  sx, sy, sz;
			std::uint32_t stamp;
		};
		void ListSections(std::vector<SectionStamp>& a_out);
		// Is the face of a_box (absolute coords) on a_axis (0 x, 1 y, 2 z), direction a_sign, flush against another solid box that covers it
		// (so the face is inside a wall and mustn't snag anything)?
		bool FaceCovered(const BlockBox& a_box, int a_axis, int a_sign);
		// Main thread, every frame in Skyrim's control mode: the player walking into a block no higher than Minecraft's step (0.6) is lifted
		// onto it, as Minecraft's player is (stairs, slabs, carpets), without a jump.
		void StepUpPlayer(RE::PlayerCharacter* a_player, float a_delta);
	}

	// The key of a block's collision shape: FNV-1a over "x0,y0,z0,x1,y1,z1;..." (boxes in sixteenths of a block, sorted by y0, x0, z0, y1,
	// x1, z1), as 8 hex digits. It is ShapeCatalog.canonical/key in the Fabric mod and fnv_key in tools/make_collision_library.py,
	// and names the library file shp_<key>.nif.
	struct ShapeBox16
	{
		int v[6];  // x0 y0 z0 x1 y1 z1
	};
	inline std::string ShapeKey(std::vector<ShapeBox16> a_boxes)
	{
		std::sort(a_boxes.begin(), a_boxes.end(), [](const ShapeBox16& a, const ShapeBox16& b) {
			static constexpr int order[6] = { 1, 0, 2, 4, 3, 5 };
			for (const int k : order) {
				if (a.v[k] != b.v[k]) {
					return a.v[k] < b.v[k];
				}
			}
			return false;
		});
		std::uint32_t h = 0x811C9DC5u;
		bool          first = true;
		for (const auto& box : a_boxes) {
			for (int k = 0; k < 6; ++k) {
				const std::string part = std::to_string(box.v[k]) + (k == 5 ? "" : ",");
				for (const char c : (first || k ? std::string() : std::string(";")) + part) {
					h = (h ^ static_cast<std::uint8_t>(c)) * 0x01000193u;
				}
			}
			first = false;
		}
		char out[9];
		std::snprintf(out, sizeof out, "%08x", h);
		return out;
	}

	namespace BlockCollision
	{
		// Skyrim's own control mode: real collision for every Minecraft block Minecraft has sent, as models of merged boxes loaded as
		// invisible statics (BlockCollision.cpp), so that the player, NPCs and projectiles all meet the blocks. False when it is off in
		// SkyCraft.ini or has given up on this install; BlockCrates then holds the player up instead.
		bool Wanted();
		// A section model has been seen with its collision in Skyrim's Havok world, so the models work on this install; until then the crates
		// BlockCrates.cpp makes round the player are kept as well, so there is always something to stand on.
		bool Verified();
		bool NeedCrates();
		// At plugin load, before the game reads its files: Skyrim loads only model files that existed when it started, so the files the
		// section models will be written into are made now (BlockCollision.cpp).
		void PrepareFiles();
		// Main thread, every frame, while Skyrim's controls are on.
		void Update(RE::PlayerCharacter* a_player, float a_delta);
		// Puts every section model away (leaving Skyrim's control mode, another cell).
		void Release();
	}

	namespace BlockCrates
	{
		void Update(RE::PlayerCharacter* a_player, float a_delta);
		void Release();
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
		// SkyCraft+ : a Skyrim spawn egg used in Minecraft: one of this actor base (a person or creature) there.
		void Spawn(RE::PlayerCharacter* a_player, RE::FormID a_base, double a_x, double a_y, double a_z);
		// SkyCraft+ : Minecraft's requests (SkyStoreRequest) handled now: the player update has stopped (a paused menu).
		void ProcessStoreNow(RE::PlayerCharacter* a_player);
	}

	// SkyCraft+ : Skyrim's item pictures (IconRender.cpp).
	namespace IconRender
	{
		// Main thread: the things listed for Minecraft (pictures for these, and the categories').
		void Want(const proto::SkyItem* a_items, std::size_t a_count, const std::uint64_t* a_askedAt);  // a_askedAt: 0, always kept; else when last asked for
		// Main thread, while Minecraft wants Skyrim's inventory: loads the models still to draw.
		void PerFrame();
		// Present: draws them and hands the pictures to Minecraft.
		void Draw(ID3D11Device* a_device, ID3D11DeviceContext* a_context);
		// SkyCraft+ : Skyrim blocks' items. Main thread: the key of a picture of this texture (flat, filling it) to
		// list with Want, and the texture a key is for (null: not one of these).
		std::uint32_t TextureKey(std::string_view a_path, float a_span);
		const char*   TexturePath(std::uint32_t a_key);
	}

	// SkyCraft+ : Skyrim animals and Minecraft's ways with animals.
	// SkyCraft+ : Skyrim's people fight Minecraft's mobs. kEvMobFight: flags 1 an attack played facing
	// the mob at (x, y, z) (MC coords; Minecraft deals the damage), 0 a follower sent to it.
	namespace MobFights
	{
		void OnFight(RE::PlayerCharacter* a_player, RE::FormID a_actor, std::uint32_t a_flags, double a_x, double a_y, double a_z);
		void Clear();
		// Holds a bow or crossbow (fights from afar: kActorRanged).
		bool Ranged(RE::Actor* a_actor);
		// Lets go of attackers held still (each frame: once their attack is over; a game loaded: a
		// follower saved while held).
		void PerFrame();
		void OnGameLoaded();
	}

	namespace Animals
	{
		// SkyCraft+ : Papyrus PathToReference (a follower sent to a Minecraft mob).
		bool PathTo(RE::Actor* a_actor, RE::TESObjectREFR* a_to);
		// SkyCraft+ : Papyrus SetDontMove (a follower held still through its attack on a Minecraft mob).
		void HoldStill(RE::Actor* a_actor, bool a_hold);
		// SkyCraft+ : held still through its attack, its AI paused (its follow package ran it on the spot,
		// breaking off the swing) and its body played on by us.
		void HoldForAttack(RE::Actor* a_actor, bool a_hold);
		// Main thread, every frame: Skyrim's animals that like the food held follow (McState::heldFood).
		void PerFrame(RE::PlayerCharacter* a_player, std::uint32_t a_heldFood, bool a_driving, float a_delta);
		// Skyrim's own KeepOffsetFromActor / ClearKeepOffsetFromActor on an actor.
		void Follow(RE::Actor* a_actor, RE::Actor* a_target, float a_offsetX, float a_offsetY);
		void LetGo(RE::Actor* a_actor);
		// A Minecraft lead on this actor (kEvLeash): 0 off, 1 the player's, 2 a fence post at this Minecraft spot.
		void OnLeash(RE::PlayerCharacter* a_player, RE::FormID a_actor, std::uint32_t a_mode, double a_x, double a_y, double a_z);
		bool OnPlayersLead(RE::FormID a_actor);
		// In a quest (an alias), essential or protected: leading it can break quests.
		bool InQuest(RE::Actor* a_actor);
		// Minecraft's breeding: which kind of animal (kActorSpeciesShift), a baby still growing, a baby made, one walked to another.
		std::uint32_t Species(RE::Actor* a_actor);
		bool          IsBaby(RE::FormID a_actor);
		void          OnBreed(RE::FormID a_parent);
		void          OnApproach(RE::FormID a_actor, RE::FormID a_to);
		void          SaveBabies(SKSE::SerializationInterface* a_intfc);
		void          LoadBabies(SKSE::SerializationInterface* a_intfc);
		void          ClearBabies();
		void          SaveWalks(SKSE::SerializationInterface* a_intfc);
		void          LoadWalks(SKSE::SerializationInterface* a_intfc);
		void          ClearWalks();
		// SkyCraft+ : a fed baby grows a share of what it has left (kEvGrowBaby).
		void          OnGrowBaby(RE::FormID a_baby, float a_share);
		// SkyCraft+ : tamed with bones (kEvTame: 1 tamed, 2 sits, 3 stands); follows the player and fights for them.
		void          OnTame(RE::PlayerCharacter* a_player, RE::FormID a_actor, std::uint32_t a_mode);
		bool          IsTamed(RE::FormID a_actor);
		bool          IsSitting(RE::FormID a_actor);
		void          SavePets(SKSE::SerializationInterface* a_intfc);
		void          LoadPets(SKSE::SerializationInterface* a_intfc);
		void          ClearPets();
	}

	// SkyCraft+ : Skyrim crops on Minecraft farmland.
	namespace Crops
	{
		bool Plantable(RE::TESBoundObject* a_produce);
		void OnGrown(RE::PlayerCharacter* a_player, RE::FormID a_produce, double a_x, double a_y, double a_z);
		void PerFrame(float a_delta);
		void Save(SKSE::SerializationInterface* a_intfc);
		void Load(SKSE::SerializationInterface* a_intfc);
		void Clear();
	}

	// SkyCraft+ : Minecraft's things at Skyrim's workbenches (Workbench.cpp).
	namespace Workbench
	{
		void          Install();
		std::uint8_t  Kind();  // the workbench the player is at (BenchType), 0 none
		std::uint32_t Seq();   // up by one at each
		void          Given();  // something was lent: an open workbench menu makes its lists again
		void          WhilePaused();  // Present, every frame: lent things handled while its menu pauses the game
		void          PerFrame(float a_delta);
		// How Minecraft acts a magic effect out ("fire", "absorb_health", "fortify_health"...), or null.
		const char*   EffectKind(RE::EffectSetting* a_effect);
		// A Minecraft weapon with a Skyrim enchantment hit this actor (kEvEnchantHit).
		void          EnchantHit(RE::PlayerCharacter* a_player, RE::FormID a_target, RE::FormID a_enchantment);
	}

	// SkyCraft+ : footsteps.
	namespace Footsteps
	{
		// Main thread, every frame: a Skyrim footstep (sound, and the noise the AI hears) every step Minecraft's player walks.
		void PerFrame(RE::PlayerCharacter* a_player, const proto::McState& a_mc, bool a_driving);
	}

	// SkyCraft+ : Minecraft loot chests in Skyrim's dungeons.
	namespace LootSpots
	{
		// Main thread, every frame: where the chests go in the dungeon the player is in (every 2 s).
		void PerFrame(RE::PlayerCharacter* a_player, float a_delta);
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
