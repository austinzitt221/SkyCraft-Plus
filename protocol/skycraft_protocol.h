// SkyCraft shared-memory protocol (Skyrim SKSE plugin <-> Minecraft Fabric mod).
//
// This header is the single source of truth for the byte layout. The Java side mirrors it in
// fabric/src/main/java/dev/skycraft/link/Proto.java; if you change anything here, change it
// there too and bump kVersion.
//
// All multi-byte values are little-endian. Skyrim creates the mapping; Minecraft opens it.
// Coordinates in this protocol are always Minecraft space (blocks, Y up, Z south) unless noted.
#pragma once

#include <cstdint>

namespace skycraft::proto
{
	inline constexpr std::uint32_t kMagic = 0x43594B53;  // "SKYC"
	inline constexpr std::uint32_t kVersion = 41;
	inline constexpr wchar_t       kMappingName[] = L"Local\\SkyCraft_v1";

	// 1 Minecraft block == 70 Skyrim units (Skyrim player ~128 units tall, MC player 1.8 blocks).
	inline constexpr double kUnitsPerBlock = 70.0;

	// ---- region offsets ---------------------------------------------------------------------
	inline constexpr std::uint64_t kOffHeader = 0x0;
	inline constexpr std::uint64_t kOffSkyState = 0x100;
	inline constexpr std::uint64_t kOffMcState = 0x200;
	inline constexpr std::uint64_t kOffOverlayCtl = 0x300;
	inline constexpr std::uint64_t kOffOverlaySlotHdr = 0x340;  // 3 x 0x40
	inline constexpr std::uint64_t kOffWaterGrid = 0x400;       // Skyrim -> MC, see WaterGrid
	inline constexpr std::uint64_t kOffInputRing = 0x1000;
	inline constexpr std::uint64_t kOffCollisionRing = 0x20000;
	inline constexpr std::uint64_t kCollisionRingBytes = 32ull << 20;
	inline constexpr std::uint64_t kOffOverlayPixels = kOffCollisionRing + kCollisionRingBytes;
	inline constexpr std::uint32_t kMaxOverlayW = 3840;
	inline constexpr std::uint32_t kMaxOverlayH = 2160;
	inline constexpr std::uint64_t kOverlaySlotBytes = std::uint64_t(kMaxOverlayW) * kMaxOverlayH * 4;
	inline constexpr std::uint32_t kOverlaySlots = 3;
	inline constexpr std::uint64_t kOffActorTable = 0x12000;   // Skyrim -> MC, see ActorTable
	inline constexpr std::uint64_t kOffEventRing = 0x17000;    // MC -> Skyrim, see McEvent
	inline constexpr std::uint64_t kOffSkyrimShots = 0x1B100;    // Skyrim -> MC, see SkyrimShots
	inline constexpr std::uint64_t kOffWorldEntities = 0x1C000;  // MC -> Skyrim, see WorldEntities
	inline constexpr std::uint64_t kOffRenderRing = kOffOverlayPixels + kOverlaySlotBytes * kOverlaySlots;
	inline constexpr std::uint64_t kRenderRingBytes = 64ull << 20;
	inline constexpr std::uint64_t kOffSkyInventory = kOffRenderRing + kRenderRingBytes;  // SkyCraft+ : v35, Skyrim -> MC, see SkyInventory
	inline constexpr std::uint64_t kSkyInventoryBytes = 1ull << 20;
	inline constexpr std::uint64_t kOffSkyIcons = kOffSkyInventory + kSkyInventoryBytes;  // SkyCraft+ : v37, Skyrim -> MC, see SkyIcons
	inline constexpr std::uint64_t kSkyIconsBytes = 17ull << 20;
	inline constexpr std::uint64_t kOffSkyStore = kOffSkyIcons + kSkyIconsBytes;  // SkyCraft+ : v41, both ways, see SkyStoreRequest
	inline constexpr std::uint64_t kSkyStoreBytes = 64ull << 10;
	inline constexpr std::uint64_t kMappingBytes = kOffSkyStore + kSkyStoreBytes;

	// ---- header @0x0 ------------------------------------------------------------------------
	struct Header
	{
		std::uint32_t magic;
		std::uint32_t version;
		std::uint32_t skyrimPid;
		std::uint32_t mcPid;
		std::uint64_t skyrimHeartbeatMs;  // GetTickCount64() at last Skyrim frame
		std::uint64_t mcHeartbeatMs;      // GetTickCount64() at last MC frame
	};
	static_assert(sizeof(Header) == 0x20);

	// ---- Skyrim -> MC state @0x100 (seqlock: seq odd while writing) -------------------------
	enum SkyFlags : std::uint32_t
	{
		kSkyInGame = 1u << 0,    // a save is loaded and the player exists
		kSkyMenuOpen = 1u << 1,  // a Skyrim menu owns input; MC should drop held keys
		kSkyLoading = 1u << 2,   // loading screen / cell transition in progress
		// Skyrim drives its own player (Skyrim mode, furniture, riding, scenes): MC's player follows
		// pos without input and can't be hurt; on the falling edge MC takes over healthFrac.
		kSkyDrivesPlayer = 1u << 3,
		// While Minecraft drives: Skyrim has something equipped in this hand (bSkyrimHands), so the
		// hand is Skyrim's: Minecraft doesn't draw it or use its item, and its mouse button goes to
		// Skyrim (left button: right hand, right button: left hand).
		kSkyRightHandSkyrim = 1u << 4,
		kSkyLeftHandSkyrim = 1u << 5,
		// The Skyrim player is blocking (a Skyrim shield or weapon): Minecraft attacks from the front
		// are blocked like a Minecraft shield's.
		kSkyBlocking = 1u << 6,
		// A Skyrim menu has paused the game (inventory, magic, map...): Minecraft pauses too, as its
		// own pause menu does, so its mobs don't attack the player meanwhile.
		kSkyPaused = 1u << 7,
		// SkyCraft+ : the Nether and the End (v26). Skyrim's player is parked in an empty cell while
		// Minecraft's is in a world of its own: Minecraft takes no teleports to Skyrim's position.
		kSkyParked = 1u << 8,
		// SkyCraft+ : a save loaded (or Minecraft connected) with Skyrim's player in the open world
		// while Minecraft's is in the Nether or the End (v28): the save wins. Minecraft takes the
		// teleport anyway, out of its own world to Skyrim's player.
		kSkyBringBack = 1u << 9,
		// SkyCraft+ : riding a Skyrim horse in Skyrim's third-person camera (v40): Minecraft sends its
		// player's body (kRenAvatar) in its riding pose, facing yaw; Skyrim draws it in the saddle.
		kSkyRiding = 1u << 10,
	};

	// SkyState::placeFlags: where the player is and the weather, for Minecraft's spawning.
	enum PlaceFlags : std::uint32_t
	{
		kPlaceInterior = 1u << 0,    // an interior cell
		kPlaceSettlement = 1u << 1,  // a city, town, settlement, inn, home, castle... (no monsters)
		kPlaceDungeon = 1u << 2,     // a cave, crypt, ruin or other dungeon (monsters at any hour)
		kPlaceRain = 1u << 3,        // rainy weather
		kPlaceSnow = 1u << 4,        // snowy weather
		kPlaceTown = 1u << 5,        // a city or town (villagers live there)
		kPlaceTamriel = 1u << 6,     // Tamriel or a worldspace inside it (its walled cities): Minecraft's overworld;
		                             // interiors and other worldspaces (Solstheim...) have dimensions of their own
	};

	// Skyrim's water (lakes, rivers, the sea) around the player, for Minecraft to treat as its own
	// water: swimming, floating, drowning. Seqlock like SkyState.
	inline constexpr std::uint32_t kWaterGridSize = 24;
	inline constexpr float         kNoWater = -1.0e30f;

	struct WaterGrid
	{
		std::uint32_t seq;
		std::int32_t  originX, originZ;  // Minecraft block column of surface[0]
		std::uint32_t worldId;           // as in SkyState
		float         surface[kWaterGridSize * kWaterGridSize];  // [z * size + x]: MC y of the water surface; kNoWater: none
	};
	static_assert(sizeof(WaterGrid) <= 0xC00);

	struct SkyState
	{
		std::uint32_t seq;
		std::uint32_t flags;           // SkyFlags
		std::uint32_t worldId;         // worldspace FormID (exterior) or cell FormID (interior)
		std::uint32_t collisionEpoch;  // bumps on world change; MC drops all collision data
		double        posX, posY, posZ;  // Skyrim player feet, MC coords
		float         yaw, pitch;        // authoritative look (MC degrees)
		std::uint32_t teleportSeq;       // MC teleports its player to pos when this changes
		std::uint32_t viewportW, viewportH;
		float         gameHour;
		float         healthFrac;  // Skyrim player health / max (0..1)
		std::uint32_t placeFlags;  // PlaceFlags: where the player is and the weather (spawning)
		float         seaLevel;    // Tamriel's sea level (MC y): the Minecraft underground lines up with it
		// SkyCraft+ : what a Skyrim-held hand holds (v29): [0] right, [1] left, a HandKind each. Minecraft's
		// body in third person holds a Minecraft stand-in for it.
		std::uint8_t  handKinds[2];
		// SkyCraft+ : a save made in the Nether or the End (v30): while not 0 (a Dimension), Minecraft's
		// player is sent into that world at pos (Skyrim's waits in its empty cell meanwhile).
		std::uint8_t  sendToWorld;
		std::uint8_t  pad4F;
	};

	// SkyState::handKinds.
	enum HandKind : std::uint8_t
	{
		kHandNone = 0,
		kHandSword = 1,
		kHandDagger = 2,
		kHandWarAxe = 3,
		kHandMace = 4,
		kHandGreatsword = 5,
		kHandBattleaxe = 6,
		kHandWarhammer = 7,
		kHandBow = 8,
		kHandCrossbow = 9,
		kHandStaff = 10,
		kHandSpell = 11,
		kHandShield = 12,
		kHandTorch = 13,
		kHandPickaxe = 14,   // SkyCraft+ : v32, tools (one-handed axes that are a pickaxe or a woodcutter's
		kHandWoodAxe = 15,   // axe): they break Minecraft's blocks as Minecraft's own tools do
	};
	static_assert(sizeof(SkyState) == 0x50);

	// ---- MC -> Skyrim state @0x200 (seqlock) ------------------------------------------------
	enum McFlags : std::uint32_t
	{
		kMcInWorld = 1u << 0,
		kMcScreenOpen = 1u << 1,  // an MC GUI screen (inventory, chat, ...) is open
		kMcOnGround = 1u << 2,
		kMcSneaking = 1u << 3,
		kMcSprinting = 1u << 4,
		kMcDead = 1u << 5,
		kMcSwimming = 1u << 6,
		kMcFlying = 1u << 7,
		// The crosshair is on a Minecraft thing used by hand (a chest, crafting table, furnace, door,
		// villager, horse, boat): right click goes to Minecraft even with a Skyrim left hand.
		kMcUseTarget = 1u << 8,
		// SkyCraft+ : the underground (v27). The player is under Skyrim's land (a mine): little or nothing
		// of Skyrim's is on screen, and far down Skyrim may skip drawing its world at all.
		kMcUnderLand = 1u << 9,
		// SkyCraft+ : performance (v31). Deep under Skyrim's land (24 blocks and more): none of
		// Skyrim's land, buildings or distant land can be seen, so Skyrim doesn't draw them.
		kMcDeepUnderLand = 1u << 10,
		// SkyCraft+ : controller inventory snapping (v34). The screen open has item slots (an
		// inventory, chest, furnace...): the D-pad and left stick jump slot to slot (kInSlotJump).
		kMcSlotScreen = 1u << 11,
		// SkyCraft+ : Skyrim's inventory in Minecraft's (v35). Minecraft's inventory or one of its
		// Skyrim item screens is open: Skyrim keeps SkyInventory up to date.
		kMcWantsSkyInventory = 1u << 12,
	};

	// SkyCraft+ : McState::dimension. 3 and up are Minecraft's own worlds: Skyrim parks its player in
	// an empty cell and only Minecraft is drawn.
	enum McDimension : std::uint32_t
	{
		kDimTamriel = 0,     // the open world (Tamriel and its walled cities)
		kDimInteriors = 1,   // Skyrim's interiors
		kDimWorldspaces = 2, // Skyrim's other worldspaces
		kDimNether = 3,
		kDimEnd = 4,
		kDimOther = 5,       // any other Minecraft-only world (a mod's)
	};

	struct McState
	{
		std::uint32_t seq;
		std::uint32_t flags;          // McFlags
		double        x, y, z;        // interpolated feet position (MC coords)
		float         yaw, pitch;     // MC rotation (degrees)
		float         eyeHeight;      // blocks above feet
		float         sensitivity;    // MC mouse sensitivity option (0..1)
		std::uint32_t teleportAck;    // last SkyState::teleportSeq applied
		std::uint32_t guiScale;
		std::uint64_t frameCounter;
		float         fovDeg;         // effective vertical FOV (includes sprint / fluid modifiers)
		float         bobPhase;       // MC walk-bob phase (interpolated walk distance); 0 if bobbing is off
		float         bobAmount;      // MC walk-bob amplitude
		std::uint32_t pad4C;
		double        eyeX, eyeY, eyeZ;  // MC camera position (interpolated, includes sneak eye lerp)

		// Raw 20 Hz physics ticks, so Skyrim can interpolate on its own frame clock exactly like
		// Minecraft's renderer does with partial ticks (no judder from the two games' frame phase).
		std::int64_t tickQpc;             // QueryPerformanceCounter at the (remainder-corrected) tick
		double       prevX, prevY, prevZ;  // feet at the previous tick
		double       curX, curY, curZ;     // feet at the latest tick
		float        tickEyeO, tickEye;      // Camera's smoothed eye height, previous/latest tick
		float        walkDistO, walkDist;    // walk-bob phase inputs
		float        bobO, bob;              // walk-bob amplitude inputs
		float        tickMs;                 // milliseconds per tick (50 unless /tick rate changed)
		std::uint32_t tickPad;

		// Minecraft's camera (F5): 0 first person, 1 third person behind, 2 third person in front
		// (looking back at the player). cameraDistance is how far Minecraft's camera sits from
		// the eye, after its own zoom collision (Minecraft blocks and Skyrim's triangles).
		std::uint32_t cameraMode;
		float         cameraDistance;
		float         healthFrac;  // MC player health / max (0..1); Skyrim takes it over when it drives
		// SkyCraft+ : the Nether and the End (v26). Where the Minecraft player is: one of Skyrim's
		// mirror worlds, or a world of Minecraft's own that Skyrim has nothing for (McDimension).
		std::uint32_t dimension;
		// SkyCraft+ : hand mixing (v33). Minecraft's hands by arm: bit 0 the right arm holds an item,
		// bit 1 the left; bits 8-15 and 16-23 count the player's own changes of what the right and
		// left arm hold (another hotbar slot, the off-hand swap, a move in the inventory); bits 24-25
		// who wins a hand holding both a Skyrim and a Minecraft item (0 Skyrim's, 1 Minecraft's,
		// 2 the last picked; the pause menu's "Hands" button).
		std::uint32_t hands;
		// SkyCraft+ : controller inventory snapping (v34). Minecraft moved the cursor itself (to a slot,
		// kInSlotJump): cursorSeq counts the moves, cursorX/Y is where it went (overlay pixels).
		std::uint32_t cursorSeq;
		std::int32_t  cursorX, cursorY;
	};
	static_assert(sizeof(McState) == 0xE0);
	static_assert(sizeof(McState) <= 0x100);

	// ---- overlay triple buffer @0x300 --------------------------------------------------------
	// state: bits 0-1 = index of the "middle" slot, bit 2 = middle holds an unread frame.
	// Writer (MC) renders into its private back slot, then xchg(state, back | kDirty) and keeps
	// the returned index as its new back slot. Reader (Skyrim) does xchg(state, front) only when
	// the dirty bit is set and keeps the returned index as its new front slot.
	inline constexpr std::uint32_t kOverlayDirty = 1u << 2;

	struct OverlayCtl
	{
		std::uint32_t state;
		std::uint32_t pad;
		std::uint64_t framesPublished;
	};

	struct OverlaySlotHdr
	{
		std::uint32_t width;
		std::uint32_t height;
		std::uint32_t flags;  // bit0: rows are bottom-up
		std::uint32_t pad;
		std::uint64_t frameId;
		std::uint8_t  reserved[0x40 - 0x18];
	};
	static_assert(sizeof(OverlaySlotHdr) == 0x40);

	// ---- input ring @0x1000 (Skyrim produces, MC consumes) ----------------------------------
	inline constexpr std::uint32_t kInputRingEntries = 4096;  // power of two
	inline constexpr std::uint64_t kInputRingHeadOff = 0x00;  // u64, written by Skyrim
	inline constexpr std::uint64_t kInputRingTailOff = 0x40;  // u64, written by MC
	inline constexpr std::uint64_t kInputRingDataOff = 0x80;

	enum InputType : std::uint16_t
	{
		kInKey = 1,          // code = SDL scancode, a = 1 press / 0 release
		kInMouseButton = 2,  // code = SDL button (1 L, 2 M, 3 R, 4 X1, 5 X2), a = 1 press / 0 release
		kInScroll = 3,       // a = wheel notches * 120 (positive = up)
		kInCursor = 4,       // a, b = absolute cursor position in overlay pixels
		kInText = 5,         // a = unicode code point
		kInReleaseAll = 6,   // release every held key/button (input focus left MC)
		kInHurt = 7,         // Skyrim hit the player: code = HurtKind, a = Skyrim damage * 100, b = attacker FormID, c = HurtFlags
		kInOpenMenu = 8,     // open Minecraft's pause/options menu
		kInUse = 9,          // Skyrim mode: Skyrim's Activate found nothing; use the Minecraft block the
		                     // player looks at by hand (doors, chests, crafting tables, levers)
		kInSkyrimHit = 10,   // a Skyrim melee hit landed (its HitFrame): code = SkyrimHitFlags, a = Minecraft damage * 100,
		                     // b = reach in blocks * 100. Minecraft hits the mob(s) in front of the player
		kInHeal = 11,        // a Skyrim healing effect healed the player: a = share of max health * 10000
		kInSlotJump = 12,    // SkyCraft+ : v34. In a screen with slots, the cursor jumps to the next slot that way:
		                     // a = x step (-1, 0, 1), b = y step (down is +1)
	};

	enum HurtKind : std::uint16_t
	{
		kHurtMelee = 0,
		kHurtProjectile = 1,
		kHurtMagic = 2,
		kHurtOther = 3,
	};

	enum HurtFlags : std::uint32_t
	{
		kHurtBlockedInSkyrim = 1u << 0,
		kHurtPowerAttack = 1u << 1,
	};

	// ---- actor table @0x12000 (Skyrim -> MC, seqlock) ----------------------------------------
	// Nearby Skyrim actors, mirrored in Minecraft as invisible hittable proxy entities.
	inline constexpr std::uint32_t kMaxActors = 256;

	enum ActorFlags : std::uint32_t
	{
		kActorHostile = 1u << 0,    // hostile to the player right now
		kActorDead = 1u << 1,
		kActorEssential = 1u << 2,
		kActorInCombat = 1u << 3,
	};

	struct ActorRecord
	{
		std::uint32_t formId;
		std::uint32_t flags;       // ActorFlags
		float         x, y, z;     // feet, MC coords
		float         yaw;         // MC degrees
		float         width;       // blocks
		float         height;      // blocks
		float         healthFrac;  // 0..1
		std::uint16_t level;
		std::uint16_t pad;
		char          name[24];    // display name, UTF-8, NUL-terminated (truncated)
	};
	static_assert(sizeof(ActorRecord) == 64);

	struct ActorTable
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint8_t  pad[0x40 - 8];
		ActorRecord   actors[kMaxActors];
	};
	static_assert(sizeof(ActorTable) == 0x40 + 64 * kMaxActors);

	// ---- event ring @0x17000 (MC -> Skyrim) ---------------------------------------------------
	inline constexpr std::uint32_t kEventRingEntries = 512;  // power of two
	inline constexpr std::uint64_t kEventRingHeadOff = 0x00;  // u64, written by MC
	inline constexpr std::uint64_t kEventRingTailOff = 0x40;  // u64, written by Skyrim
	inline constexpr std::uint64_t kEventRingDataOff = 0x80;

	enum McEventType : std::uint32_t
	{
		kEvHitActor = 1,    // formId, a = MC damage (after MC's own modifiers), b/c = knockback dir x/z (MC), d = knockback strength
		kEvPlayerDied = 2,  // the Minecraft player died: kill the Skyrim player
		kEvExplosion = 3,   // a Minecraft explosion (TNT, creeper, ...): a/b/c = centre (MC coords), d = radius (blocks)
		kEvArrowStuck = 4,  // an arrow stuck in a Skyrim actor: formId, a/b/c = where it hit (MC coords), d = flight yaw,
		                    // flags = flight pitch (float bits), weapon = arrow texture (0 plain, 1 tipped, 2 spectral)
		kEvSkillUse = 5,    // the player used a Skyrim skill in Minecraft: formId = Skyrim skill (ActorValue: 9 Block,
		                    // 10 Smithing, 11 Heavy Armor, 12 Light Armor), a = uses (as Skyrim's AdvanceSkill counts them)
		kEvPlayerHurt = 6,  // Skyrim mode: a Minecraft mob or explosion hurt the player: a = share of Minecraft's max
		                    // health it took; Skyrim takes that share of its own
		kEvSkyItem = 7,     // SkyCraft+ (v35): a click in a Skyrim item screen: formId = SkyItem::formId,
		                    // flags = SkyItemAction
		kEvDragonSoul = 8,  // SkyCraft+ (v39): the Ender Dragon died while this player was in its fight: one
		                    // Skyrim dragon soul
	};

	// SkyCraft+ : what a click on a Skyrim item does (McEvent::flags of kEvSkyItem).
	enum SkyItemAction : std::uint32_t
	{
		kSkyItemRight = 1,     // equip in (or, if there already, take off) the right hand; worn things: put on/take off
		kSkyItemLeft = 2,      // the same with the left hand
		kSkyItemUse = 3,       // drink, eat, read; shouts and powers: make it the voice's
		kSkyItemMenuStats = 4, // open Skyrim's skills (level up) menu (formId 0)
		kSkyItemMenuMap = 5,   // open Skyrim's map (formId 0)
		// SkyCraft+ (v36): Skyrim shortcuts on Minecraft's hotbar.
		kSkyItemEquipRight = 6,  // equip in the right hand (never takes it off): a shortcut picked on the hotbar
		kSkyItemEquipLeft = 7,   // the same with the left hand: a shortcut in the off-hand slot
		kSkyItemDrop = 8,        // drop all of it in front of the player: a shortcut thrown out
		// SkyCraft+ (v38): a shortcut taken out of the off-hand slot (or its hotbar slot).
		kSkyItemUnequipRight = 9,  // out of the right hand if it's there (never puts it in)
		kSkyItemUnequipLeft = 10,  // the same with the left hand (a shield: taken off)
	};

	// InputEvent::code for kInSkyrimHit.
	enum SkyrimHitFlags : std::uint16_t
	{
		kSkyrimHitPower = 1u << 0,  // a power attack: hits everything in the arc
		kSkyrimHitBash = 1u << 1,   // a shield or weapon bash: little damage, a big push
		kSkyrimHitLeft = 1u << 2,   // the left hand's
	};

	enum HitFlags : std::uint32_t
	{
		kHitCritical = 1u << 0,
		kHitProjectile = 1u << 1,
		kHitSweep = 1u << 2,
		kHitFire = 1u << 3,
	};

	// What landed a kEvHitActor (Skyrim plays that weapon class's impact effect and sounds).
	enum HitWeapon : std::uint32_t
	{
		kWeaponUnarmed = 0,
		kWeaponBlade = 1,   // swords
		kWeaponAxe = 2,
		kWeaponBlunt = 3,   // maces, pickaxes, shovels, hoes, anything else held
		kWeaponPierce = 4,  // tridents, spears
		kWeaponArrow = 5,   // arrows and other projectiles
	};

	struct McEvent
	{
		std::uint32_t type;
		std::uint32_t formId;
		float         a, b, c, d;
		std::uint32_t flags;
		std::uint32_t weapon;  // HitWeapon for kEvHitActor
	};
	static_assert(sizeof(McEvent) == 32);

	// ---- world entities @0x1C000 (MC -> Skyrim, seqlock) -------------------------------------
	// Minecraft things Skyrim draws itself each frame (arrows, dropped items, block cracks) + the
	// block outline.
	inline constexpr std::uint32_t kMaxWorldEntities = 160;

	enum WorldEntityKind : std::uint32_t
	{
		kWeArrow = 1,    // uv[0]: the arrow's item icon
		kWeItem = 2,     // dropped/thrown item: a flat sprite (uv[0]) turning about the vertical
		kWeTrident = 3,  // uv[0]: the trident's item icon
		kWeBlock = 4,    // dropped block item: a spinning cube of side `scale`, uv[0..2] = side, top, bottom
		kWeCrack = 5,    // block-breaking cracks over the box at (x, y, z) of size ext, uv[0] = crack stage
		kWeShadow = 6,   // a player's or mob's feet at (x, y, z), `scale` wide: its soft contact shadow
	};

	struct WorldEntity
	{
		std::uint32_t kind;        // WorldEntityKind
		std::uint32_t id;          // MC entity id (stable while it exists)
		float         x, y, z;     // MC coords (interpolated at MC's render time)
		float         yaw, pitch;  // MC degrees
		float         scale;
		float         ext[3];      // kWeCrack: box size
		float         uv[3][4];    // atlas rects {u0, v0, u1, v1}
		std::uint32_t tint;        // RGBA8 multiplier for the top face (grass, leaves); 0 = none
	};
	static_assert(sizeof(WorldEntity) == 96);

	struct WorldEntities
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint32_t hasSelection;           // draw an outline around the targeted block
		float         selMin[3], selMax[3];   // MC coords
		std::uint8_t  pad[0x40 - 36];
		WorldEntity   entities[kMaxWorldEntities];
	};
	static_assert(sizeof(WorldEntities) == 0x40 + sizeof(WorldEntity) * kMaxWorldEntities);
	static_assert(kOffWorldEntities + sizeof(WorldEntities) <= kOffCollisionRing);

	// ---- the Skyrim player's projectiles @0x1B100 (Skyrim -> MC, seqlock) ----------------------
	// SkyCraft+ : Skyrim magic, arrows and shouts on Minecraft mobs. Nearly every Skyrim spell and
	// shout is a projectile (bolts, flames, beams, cones). Each frame Skyrim lists the player's,
	// each with its whole path so far (so Minecraft can't miss a frame), and keeps each a moment
	// after it's gone (an explosion where it ended); Minecraft hits each mob once per shot.
	inline constexpr std::uint32_t kMaxSkyrimShots = 64;

	enum SkyrimShotFlags : std::uint32_t
	{
		kShotPath = 1u << 0,       // a bolt, arrow or flame: hits what its path x0..x1 (radius wide) passes through
		kShotBeam = 1u << 1,       // a beam (Sparks): x0..x1; hits the first thing on it
		kShotCone = 1u << 2,       // a cone (Unrelenting Force, breath shouts): from x0 to x1, radius = half-angle (radians)
		kShotExplosion = 1u << 3,  // an explosion at x1, radius in blocks
		kShotStops = 1u << 4,      // stops at the first thing it hits (bolts, arrows)
		kShotSpray = 1u << 5,      // a spray held at the hand (Flames): x0..x1 out along the aim; hits everything on it
		kShotRepeats = 1u << 6,    // lasts the whole cast (sprays, beams): hits each mob again every half second
		kShotFire = 1u << 8,       // sets what it hits on fire
		kShotFrost = 1u << 9,      // slows what it hits
		kShotShock = 1u << 10,
		kShotPush = 1u << 11,      // throws what it hits back (push = strength)
	};

	struct SkyrimShot
	{
		std::uint32_t id;
		std::uint32_t flags;               // SkyrimShotFlags
		float         x0, y0, z0, x1, y1, z1;  // MC coords
		float         radius;
		float         damage;              // Minecraft damage per hit
		float         push;
		std::uint32_t pad;
	};
	static_assert(sizeof(SkyrimShot) == 48);

	struct SkyrimShots
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint32_t pad[14];
		SkyrimShot    shots[kMaxSkyrimShots];
	};
	static_assert(sizeof(SkyrimShots) == 0x40 + 48 * kMaxSkyrimShots);
	static_assert(kOffEventRing + kEventRingDataOff + kEventRingEntries * sizeof(McEvent) <= kOffSkyrimShots);
	static_assert(kOffSkyrimShots + sizeof(SkyrimShots) <= kOffWorldEntities);

	// ---- render ring (MC -> Skyrim) -----------------------------------------------------------
	// Byte ring like the collision ring. Minecraft ships its own block meshes (built by Minecraft's
	// block renderer: models, tint, AO, lighting) and its block atlas; Skyrim draws them in its own
	// frame so blocks stay locked to the world and are occluded by Skyrim geometry.
	inline constexpr std::uint64_t kRenRingHeadOff = 0x00;
	inline constexpr std::uint64_t kRenRingTailOff = 0x40;
	inline constexpr std::uint64_t kRenRingDataOff = 0x80;
	inline constexpr std::uint64_t kRenRingDataBytes = kRenderRingBytes - kRenRingDataOff;

	enum RenType : std::uint32_t
	{
		kRenPad = 0,
		kRenAtlas = 1,     // RenAtlas only: begin the atlas and allocate its w x h texture; the RGBA8
		                   // pixels follow as kRenAtlasChunk bands (an atlas can dwarf one message)
		kRenSection = 2,   // RenSection + RenVertex[vertexCount] (triangle list); 0 vertices = remove
		kRenClearAll = 3,  // drop every section (world change)
		kRenTexture = 4,   // RenTexture + RGBA8 pixels: an entity texture (player skin, armour, ...)
		kRenAvatar = 5,    // RenAvatar + RenBatch[batchCount] + RenVertex[vertexCount]: the player's
		                   // model this frame; 0 batches = not shown (first person)
		kRenScene = 6,     // RenScene + RenBatch[batchCount] + RenVertex[vertexCount]: every other
		                   // entity and all particles this frame, relative to RenScene's origin
		kRenAtlasRegion = 7,  // RenAtlasRegion + RGBA8 pixels: an animated sprite's current frame
		kRenLights = 8,       // RenLights + RenLight[count]: a section's light-emitting blocks (sent
		                      // after its kRenSection; 0 = none)
		kRenSolids = 10,      // RenSolids + 512-byte bitset (bit x + 16z + 256y): which blocks of a
		                      // section NPCs collide with (sent after its kRenSection; 0 = none)
		kRenDug = 11,         // RenDug + 512-byte bitset (bit x + 16z + 256y): which blocks of a section
		                      // were dug out of Skyrim's world (its geometry there is gone); 0 = none
		kRenShapes = 12,      // RenSolids (count = boxes) + RenShapeBox[]: the collision boxes of a
		                      // section's solid blocks that aren't full cubes (stairs, slabs, doors,
		                      // fences, carpets...), which characters collide with instead of a whole
		                      // block (sent with its kRenSolids; 0 = none)
		kRenRagdoll = 9,      // RenAvatar + RenBatch[] + RenVertex[]: the player's body standing still,
		                      // relative to the feet and facing +Z, split into its parts (RenBatch
		                      // flags bits 8-11: RagdollPart). Sent about once a second while alive;
		                      // Skyrim hangs the parts on its ragdoll when the player dies.
		kRenAtlasChunk = 13,  // RenAtlasChunk + RGBA8 pixels: one horizontal band of the atlas begun by
		                      // kRenAtlas, top row first. Bands arrive in order; the last one
		                      // (y + rows == height) completes the atlas and its mipmaps.
	};

	struct RenSolids
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;       // solid blocks (0: none, and no bitset follows)
	};

	// One box of a block's collision shape, in sixteenths of a block from the block's corner on
	// Minecraft's axes (a fence reaches maxY 24).
	struct RenShapeBox
	{
		std::uint16_t cell;    // bits 0-11: x + 16z + 256y within the section; RenShapeCell flags above
		std::uint8_t  min[3];  // x, y, z
		std::uint8_t  max[3];
	};
	static_assert(sizeof(RenShapeBox) == 8);

	// RenShapeBox::cell flags, the same on every box of a block. For NPC push-out and path
	// avoidance, which work per block: such a block isn't a wall.
	enum RenShapeCell : std::uint16_t
	{
		kShapeCellMask = 0x0FFF,
		kShapeStep = 1u << 14,      // whole bottom face, open top: walked onto (slabs, stairs, carpets)
		kShapePassable = 1u << 15,  // Minecraft's mobs path through it (open doors, open trapdoors)
	};

	struct RenDug
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;       // dug blocks (0: none, and no bitset follows)
		std::uint32_t worldId;     // the Skyrim world (SkyState::worldId) the bits belong to
		std::uint32_t pad;
	};
	static_assert(sizeof(RenDug) == 24);

	// What a piece of diggable Skyrim geometry is made of, as the Minecraft block it digs into
	// (ColTri flags bits 8-15). Chosen on the Skyrim side from Havok materials and object types.
	enum DigMaterial : std::uint8_t
	{
		kDigNone = 0,  // not known: stone
		kDigGrass = 1,
		kDigDirt = 2,
		kDigStone = 3,
		kDigCobble = 4,
		kDigSnow = 5,
		kDigIce = 6,
		kDigSand = 7,
		kDigGravel = 8,
		kDigMud = 9,
		kDigOakLog = 10,
		kDigSpruceLog = 11,
		kDigBirchLog = 12,
		kDigPlanks = 13,
		kDigMetal = 14,
		kDigGlass = 15,
		kDigOrganic = 16,
		kDigCloth = 17,
		kDigBone = 18,
		kDigWeb = 19,
		kDigAsh = 20,
		kDigBedrock = 21,  // Minecraft only: a few blocks under the land
		kDigMaterialCount
	};

	enum RagdollPart : std::uint32_t
	{
		kPartNone = 0,
		kPartHead = 1,
		kPartBody = 2,
		kPartRightArm = 3,
		kPartLeftArm = 4,
		kPartRightLeg = 5,
		kPartLeftLeg = 6,
		kPartCount = 7,
	};

	struct RenLights
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;
	};

	enum LightKind : std::uint8_t
	{
		kLightSteady = 0,
		kLightFlame = 1,  // torches, fire, campfires, candles: flicker
		kLightLava = 2,   // lava, magma: a slow glow
	};

	enum BlockHazard : std::uint8_t
	{
		kHazardNone = 0,
		kHazardFire = 1,   // fire, soul fire, campfires: burns what stands in it
		kHazardLava = 2,   // lava: burns hard
		kHazardMagma = 3,  // magma block: hurts what stands on top of it
	};

	struct RenLight
	{
		std::uint8_t  x, y, z;  // block within the section
		std::uint8_t  level;    // Minecraft light emission, 1-15
		std::uint32_t color;    // RGB8 (r low byte); top byte: LightKind in bits 0-3, BlockHazard in 4-7
	};
	static_assert(sizeof(RenLight) == 8);

	struct RenAtlasRegion
	{
		std::uint32_t x, y, width, height;  // pixels in the combined atlas (kRenAtlas)
	};

	struct RenScene
	{
		double        originX, originY, originZ;  // MC block the positions are relative to
		std::uint32_t batchCount;
		std::uint32_t vertexCount;
	};

	struct RenTexture
	{
		std::uint32_t id;  // 1+, referenced by RenBatch::texture
		std::uint32_t width, height;
		std::uint32_t pad;
	};

	// The player as Minecraft's own entity renderer draws it (skin, armour, held items, cape),
	// posed and animated, with positions in blocks relative to the player's feet: Skyrim puts
	// it at its own interpolated feet position, the one its camera follows.
	struct RenAvatar
	{
		std::uint32_t batchCount;
		std::uint32_t vertexCount;
	};

	struct RenBatch
	{
		std::uint32_t texture;  // 0: the block/item atlas, else a RenTexture id
		std::uint32_t first;    // first vertex
		std::uint32_t count;    // vertices (multiple of 3)
		std::uint32_t flags;    // bit0: translucent (blended, after the solid pass; casts no shadow)
	};

	struct RenAtlas
	{
		std::uint32_t width, height;
	};

	// One horizontal band of the atlas opened by RenAtlas. width/height must match it.
	struct RenAtlasChunk
	{
		std::uint32_t width, height;
		std::uint32_t y, rows;  // rows [y, y + rows) of RGBA8 pixels; top row of the band first
	};

	struct RenSection
	{
		std::int32_t  sx, sy, sz;   // section coords (16-block cubes)
		std::uint32_t vertexCount;  // multiple of 3
	};

	struct RenVertex
	{
		float         x, y, z;  // MC coords relative to the section origin (sx*16, sy*16, sz*16)
		float         u, v;     // atlas UV
		std::uint32_t color;    // RGBA8 (tint * ambient occlusion; Minecraft's fixed face shading is left out)
		std::uint32_t light;    // low byte: block light 0-15, next byte: sky light 0-15
		std::uint32_t flags;    // bit0: cutout (alpha test), bit1: translucent,
		                        // bits 4-6: face normal as MC Direction ordinal + 1 (0 = none: lit without a normal)
	};
	static_assert(sizeof(RenVertex) == 32);

	struct InputEvent
	{
		std::uint16_t type;
		std::uint16_t code;
		std::int32_t  a;
		std::int32_t  b;
		std::int32_t  c;
	};
	static_assert(sizeof(InputEvent) == 16);

	// ---- collision ring @0x20000 (Skyrim produces, MC consumes) -----------------------------
	// Byte ring. Every message starts 8-byte aligned with {u32 type, u32 payloadBytes}.
	// A kColPad message means "skip to the start of the ring".
	inline constexpr std::uint64_t kColRingHeadOff = 0x00;  // u64 total bytes written
	inline constexpr std::uint64_t kColRingTailOff = 0x40;  // u64 total bytes consumed
	inline constexpr std::uint64_t kColRingDataOff = 0x80;
	inline constexpr std::uint64_t kColRingDataBytes = kCollisionRingBytes - kColRingDataOff;

	enum ColType : std::uint32_t
	{
		kColPad = 0,
		kColClear = 1,   // payload: u32 epoch
		kColRegion = 2,  // payload: ColRegion + ColBlock[count]
		kColTris = 3,    // payload: ColRegion (count = triangles) + ColTri[count]; sent before kColRegion
	};

	// Exact Skyrim collision triangle (MC space) for the player's smooth collider.
	enum ColTriFlags : std::uint32_t
	{
		kTriStairHelper = 1u << 0,  // Skyrim's invisible stair ramp: walkable, never a wall
		kTriDiggable = 1u << 1,     // ground, rock, trees...: can be dug into (bits 8-15: DigMaterial).
		                            // Its normal faces out of the solid side (winding is outward).
		kTriGhost = 1u << 2,        // a diggable triangle as it was before blocks were dug out of it:
		                            // not collision, only for telling what's inside Skyrim's geometry
		kTriTerrain = 1u << 3,      // the land (a height field)
	};

	inline constexpr std::uint32_t kTriMaterialShift = 8;

	struct ColTri
	{
		float         v[9];
		std::uint32_t flags;
	};
	static_assert(sizeof(ColTri) == 40);

	struct ColMsgHeader
	{
		std::uint32_t type;
		std::uint32_t payloadBytes;
	};

	// Replaces all Skyrim collision inside the inclusive block box [min, max].
	struct ColRegion
	{
		std::int32_t  minX, minY, minZ;
		std::int32_t  maxX, maxY, maxZ;
		std::uint32_t epoch;
		std::uint32_t count;
	};
	static_assert(sizeof(ColRegion) == 32);

	// One block's worth of Skyrim collision as an 8x8x8 occupancy mask.
	// bits[y] bit (z * 8 + x) is sub-voxel (x, y, z), each 1/8 block, in MC axes.
	struct ColBlock
	{
		std::int32_t  x, y, z;
		std::uint32_t pad;
		std::uint64_t bits[8];
	};
	static_assert(sizeof(ColBlock) == 80);

	// ---- SkyCraft+ : Skyrim's inventory @kOffSkyInventory (Skyrim -> MC, seqlock), v35 -------
	// The player's Skyrim items, spells, shouts and powers, for Minecraft's own Skyrim item
	// screens (Skyrim's menus can't be open alongside Minecraft's inventory). Written a few times a
	// second while McFlags::kMcWantsSkyInventory is set, and right after each kEvSkyItem.
	inline constexpr std::uint32_t kMaxSkyItems = 4096;

	enum SkyItemKind : std::uint8_t
	{
		kSkyWeapon = 1,      // sub: SkyWeaponType
		kSkyApparel = 2,     // sub: SkyApparelSlot; flags kSkyItemHeavy / kSkyItemLight
		kSkyPotion = 3,      // sub: SkyPotionKind
		kSkyFood = 4,
		kSkyIngredient = 5,
		kSkyBook = 6,        // sub: 1 spell tome, 2 note, 0 other
		kSkyKey = 7,
		kSkyMisc = 8,        // sub: 1 soul gem (filled: kSkyItemFilled), 2 torch, 3 lockpick, 0 other
		kSkyAmmo = 9,        // sub: 1 bolt, 0 arrow
		kSkyScroll = 10,
		kSkySpell = 20,      // sub: SkySchool; element: SkyElement
		kSkyShout = 21,
		kSkyPower = 22,      // sub: 1 lesser power
	};

	enum SkyWeaponType : std::uint8_t
	{
		kSkyHand = 0, kSkySword = 1, kSkyDagger = 2, kSkyWarAxe = 3, kSkyMace = 4, kSkyGreatsword = 5,
		kSkyBattleaxe = 6, kSkyWarhammer = 7, kSkyBow = 8, kSkyStaff = 9, kSkyCrossbow = 10, kSkyPickaxe = 11, kSkyWoodAxe = 12,
	};

	enum SkyApparelSlot : std::uint8_t
	{
		kSkyBody = 0, kSkyHead = 1, kSkyHands = 2, kSkyFeet = 3, kSkyShield = 4, kSkyRing = 5, kSkyAmulet = 6, kSkyCirclet = 7,
	};

	enum SkyPotionKind : std::uint8_t
	{
		kSkyPotionOther = 0, kSkyPotionHealth = 1, kSkyPotionMagicka = 2, kSkyPotionStamina = 3, kSkyPoison = 4,
	};

	enum SkySchool : std::uint8_t
	{
		kSkyAlteration = 0, kSkyConjuration = 1, kSkyDestruction = 2, kSkyIllusion = 3, kSkyRestoration = 4,
	};

	// SkyItem::material: what a Minecraft stand-in is made of. For spells, the element.
	enum SkyMaterial : std::uint8_t
	{
		kSkyMatNone = 0, kSkyMatWood = 1, kSkyMatLeather = 2, kSkyMatIron = 3, kSkyMatSteel = 4, kSkyMatOrcish = 5,
		kSkyMatDwarven = 6, kSkyMatElven = 7, kSkyMatGlass = 8, kSkyMatEbony = 9, kSkyMatDaedric = 10, kSkyMatDragon = 11,
		kSkyMatSilver = 12, kSkyMatGold = 13, kSkyMatStalhrim = 14, kSkyMatChain = 15,
		kSkyFire = 1, kSkyFrost = 2, kSkyShock = 3,
	};

	enum SkyItemFlags : std::uint32_t
	{
		kSkyItemRightHand = 1u << 0,  // equipped in the right hand
		kSkyItemLeftHand = 1u << 1,   // equipped in the left hand
		kSkyItemWorn = 1u << 2,       // apparel put on; ammo readied
		kSkyItemVoice = 1u << 3,      // the shout or power in use
		kSkyItemEnchanted = 1u << 4,
		kSkyItemStolen = 1u << 5,
		kSkyItemFavorite = 1u << 6,
		kSkyItemHeavy = 1u << 7,
		kSkyItemLight = 1u << 8,
		kSkyItemFilled = 1u << 9,     // a soul gem with a soul
		kSkyItemRead = 1u << 10,      // a book already read
		kSkyItemQuest = 1u << 11,     // a quest item (can't be dropped)
		kSkyItemTwoHanded = 1u << 12,
	};

	struct SkyItem
	{
		std::uint32_t formId;
		std::uint8_t  kind;      // SkyItemKind
		std::uint8_t  sub;
		std::uint8_t  material;  // SkyMaterial
		std::uint8_t  pad0;
		std::int32_t  count;     // spells: 1; shouts: words known
		std::uint32_t flags;     // SkyItemFlags
		float         weight;
		std::int32_t  value;
		float         stat;      // damage, armor rating, magicka cost, or the main effect's magnitude
		float         stat2;     // potions and spells: duration (s); shouts: recharge (s)
		char          name[96];  // UTF-8, zero-terminated
		// SkyCraft+ (v36): where it comes from, which stays the same across load orders (shortcuts
		// keep these): the plugin's file name and the id within it. Made in game (FF...): no plugin
		// and the form id.
		std::uint32_t localId;
		char          plugin[60];
	};
	static_assert(sizeof(SkyItem) == 192);

	struct SkyInventory
	{
		std::uint32_t seq;
		std::uint32_t count;
		std::uint32_t generation;   // changes whenever anything in it does
		std::int32_t  gold;
		float         carried, carryMax;
		std::uint32_t pad[10];
		SkyItem       items[kMaxSkyItems];
	};
	static_assert(sizeof(SkyInventory) == 0x40 + sizeof(SkyItem) * kMaxSkyItems);
	static_assert(sizeof(SkyInventory) <= kSkyInventoryBytes);

	// ---- SkyCraft+ : Skyrim's item pictures @kOffSkyIcons (Skyrim -> MC), v37 ----------------
	// Skyrim draws each listed item's model (and one for each of Minecraft's Skyrim categories) to
	// a small picture for Minecraft's Skyrim panels and shortcuts. Each slot is its own seqlock;
	// SkyIcons::generation changes whenever any slot does.
	inline constexpr std::uint32_t kSkyIconSize = 64;
	inline constexpr std::uint32_t kMaxSkyIcons = 1024;
	// SkyIconSlot::key for a category's picture: this plus its index in Minecraft's list (All items,
	// Weapons, Apparel, Potions, Scrolls, Food, Ingredients, Books, Keys, Misc, All magic, Alteration,
	// Conjuration, Destruction, Illusion, Restoration, Shouts, Powers).
	inline constexpr std::uint32_t kSkyIconCategory = 0xFFFFFF00;

	struct SkyIconSlot
	{
		std::uint32_t seq;
		std::uint32_t key;     // SkyItem::formId, or kSkyIconCategory + n; 0 = empty
		std::uint32_t pad[2];
		std::uint8_t  rgba[kSkyIconSize * kSkyIconSize * 4];  // rows top to bottom, straight alpha
	};
	static_assert(sizeof(SkyIconSlot) == 16 + kSkyIconSize * kSkyIconSize * 4);

	struct SkyIcons
	{
		std::uint32_t generation;
		std::uint32_t pad[15];
		SkyIconSlot   slots[kMaxSkyIcons];
	};
	static_assert(sizeof(SkyIcons) <= kSkyIconsBytes);

	// ---- SkyCraft+ : Skyrim items in Minecraft's containers @kOffSkyStore, v41 ---------------------
	// A Skyrim item put in a Minecraft chest (shulker box, hopper, bundle...) leaves Skyrim's inventory
	// and becomes a Minecraft item that says what it is (plugin, id, tempering, charge, soul); taken
	// back into Minecraft's inventory it goes back into Skyrim's. Minecraft asks (requests ring, MC ->
	// Skyrim), Skyrim answers each request with one result per kind of copy it took out (results
	// ring, Skyrim -> MC), or one with a status saying why not.
	inline constexpr std::uint32_t kStoreRequests = 32;   // power of two
	inline constexpr std::uint32_t kStoreResults = 64;    // power of two
	inline constexpr std::uint64_t kStoreReqHeadOff = 0x00;  // u64, written by MC
	inline constexpr std::uint64_t kStoreReqTailOff = 0x40;  // u64, written by Skyrim
	inline constexpr std::uint64_t kStoreResHeadOff = 0x80;  // u64, written by Skyrim
	inline constexpr std::uint64_t kStoreResTailOff = 0xC0;  // u64, written by MC
	inline constexpr std::uint64_t kStoreReqDataOff = 0x100;

	enum SkyStoreOp : std::uint8_t
	{
		kStoreTake = 1,  // take count of formId out of Skyrim's inventory (plain copies first)
		kStoreGive = 2,  // put count of plugin/localId, with health/charge/soul, into it
		kStorePicture = 3,  // draw plugin/localId's picture too for a while (SkyIcons; the result's formId is its key)
	};

	enum SkyStoreStatus : std::uint8_t
	{
		kStoreOk = 1,
		kStoreQuest = 2,     // a quest item: it stays
		kStoreMadeHere = 3,  // made in this game (FF..., a potion you brewed) or enchanted by you: it stays
		kStoreNone = 4,      // none of it left
		kStoreMissing = 5,   // give: no such thing (its plugin isn't loaded)
	};

	struct SkyStoreRequest
	{
		std::uint32_t id;
		std::uint8_t  op;  // SkyStoreOp
		std::uint8_t  pad[3];
		std::uint32_t formId;  // take
		std::int32_t  count;
		float         health;  // give: tempering (1 = none)
		float         charge;  // give: an enchanted weapon's charge (< 0 = none)
		std::int32_t  soul;    // give: a soul gem's soul (RE::SOUL_LEVEL, 0 = none)
		std::uint32_t localId;
		char          plugin[60];
		std::uint32_t pad2;
	};
	static_assert(sizeof(SkyStoreRequest) == 96);

	struct SkyStoreResult
	{
		std::uint32_t id;      // the request's
		std::uint8_t  status;  // SkyStoreStatus
		std::uint8_t  kind;    // SkyItemKind
		std::uint8_t  sub;
		std::uint8_t  material;
		std::int32_t  count;   // how many were taken (or given)
		float         health;
		float         charge;
		std::int32_t  soul;
		std::uint32_t flags;   // SkyItemFlags
		float         weight;
		std::int32_t  value;
		float         stat, stat2;
		std::uint32_t localId;
		char          plugin[60];
		char          name[96];
		std::uint32_t formId;
		std::uint32_t pad[4];
	};
	static_assert(sizeof(SkyStoreResult) == 224);
	inline constexpr std::uint64_t kStoreResDataOff = kStoreReqDataOff + kStoreRequests * sizeof(SkyStoreRequest);
	static_assert(kStoreResDataOff + kStoreResults * sizeof(SkyStoreResult) <= kSkyStoreBytes);
}
