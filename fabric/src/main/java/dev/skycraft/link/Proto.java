package dev.skycraft.link;

/**
 * Mirror of protocol/skycraft_protocol.h. Keep the two in sync.
 */
public final class Proto {
	private Proto() {
	}

	public static final int MAGIC = 0x43594B53;
	public static final int VERSION = 41;
	// A second client on the same PC (multiplayer testing) talks to its own stand-in Skyrim:
	// -Dskycraft.link=Local\SkyCraft_guest (see tools/fake_guest.py).
	public static final String MAPPING_NAME = System.getProperty("skycraft.link", "Local\\SkyCraft_v1");
	// Linux bridge (same PC: Skyrim via Proton + native Linux Minecraft): a file both sides mmap.
	// Overridden with -Dskycraft.linkFile=<path> or SKYCRAFT_LINK_FILE=<path>.
	// The SKSE plugin opens the same file as Z:\dev\shm\skycraft_v1 (or the configured path).
	// Windows builds ignore this; the Win32 Local\ mapping above stays the default there.
	public static final String LINUX_SHM_PATH = System.getProperty("skycraft.linkFile",
		System.getenv().getOrDefault("SKYCRAFT_LINK_FILE", "/dev/shm/skycraft_v1"));
	public static final double UNITS_PER_BLOCK = 70.0;

	public static final long OFF_HEADER = 0x0;
	public static final long OFF_SKY_STATE = 0x100;
	public static final long OFF_MC_STATE = 0x200;
	public static final long OFF_WATER_GRID = 0x400;
	public static final int WATER_GRID_SIZE = 24;
	public static final long WG_SEQ = 0x0, WG_ORIGIN_X = 0x4, WG_ORIGIN_Z = 0x8, WG_WORLD_ID = 0xC, WG_SURFACE = 0x10;
	public static final long OFF_OVERLAY_CTL = 0x300;
	public static final long OFF_OVERLAY_SLOT_HDR = 0x340;
	public static final long OFF_INPUT_RING = 0x1000;
	public static final long OFF_COLLISION_RING = 0x20000;
	public static final long COLLISION_RING_BYTES = 32L << 20;
	public static final long OFF_OVERLAY_PIXELS = OFF_COLLISION_RING + COLLISION_RING_BYTES;
	public static final int MAX_OVERLAY_W = 3840;
	public static final int MAX_OVERLAY_H = 2160;
	public static final long OVERLAY_SLOT_BYTES = (long) MAX_OVERLAY_W * MAX_OVERLAY_H * 4;
	public static final int OVERLAY_SLOTS = 3;
	public static final long OFF_ACTOR_TABLE = 0x12000;
	public static final long OFF_EVENT_RING = 0x17000;
	public static final long OFF_WORLD_ENTITIES = 0x1C000;
	public static final long OFF_SKYRIM_SHOTS = 0x1B100;

	// SkyCraft+ : the Skyrim player's projectiles (relative to OFF_SKYRIM_SHOTS), see SkyrimShots
	public static final int MAX_SKYRIM_SHOTS = 64, SKYRIM_SHOT_BYTES = 48;
	public static final long SHOTS_SEQ = 0x0, SHOTS_COUNT = 0x4, SHOTS_RECORDS = 0x40;
	public static final int SHOT_PATH = 1, SHOT_BEAM = 1 << 1, SHOT_CONE = 1 << 2, SHOT_EXPLOSION = 1 << 3, SHOT_STOPS = 1 << 4, SHOT_SPRAY = 1 << 5, SHOT_REPEATS = 1 << 6,
		SHOT_FIRE = 1 << 8, SHOT_FROST = 1 << 9, SHOT_SHOCK = 1 << 10, SHOT_PUSH = 1 << 11;
	public static final long OFF_RENDER_RING = OFF_OVERLAY_PIXELS + OVERLAY_SLOT_BYTES * OVERLAY_SLOTS;
	public static final long RENDER_RING_BYTES = 64L << 20;
	// SkyCraft+ : v35, Skyrim's inventory (Skyrim -> MC, seqlock), see SkyInventory
	public static final long OFF_SKY_INVENTORY = OFF_RENDER_RING + RENDER_RING_BYTES;
	public static final long SKY_INVENTORY_BYTES = 1L << 20;
	// SkyCraft+ : v37, Skyrim's item pictures (Skyrim -> MC), see SkyIcons
	public static final long OFF_SKY_ICONS = OFF_SKY_INVENTORY + SKY_INVENTORY_BYTES;
	public static final long SKY_ICONS_BYTES = 17L << 20;
	// SkyCraft+ : v41, Skyrim items in Minecraft's containers (both ways), see SkyStoreRequest
	public static final long OFF_SKY_STORE = OFF_SKY_ICONS + SKY_ICONS_BYTES;
	public static final long SKY_STORE_BYTES = 64L << 10;
	public static final long MAPPING_BYTES = OFF_SKY_STORE + SKY_STORE_BYTES;
	public static final int STORE_REQUESTS = 32, STORE_RESULTS = 64, STORE_REQUEST_BYTES = 96, STORE_RESULT_BYTES = 224;
	public static final long STORE_REQ_HEAD = 0x00, STORE_REQ_TAIL = 0x40, STORE_RES_HEAD = 0x80, STORE_RES_TAIL = 0xC0, STORE_REQ_DATA = 0x100,
		STORE_RES_DATA = STORE_REQ_DATA + STORE_REQUESTS * STORE_REQUEST_BYTES;
	public static final int STORE_TAKE = 1, STORE_GIVE = 2, STORE_PICTURE = 3;
	public static final int STORE_OK = 1, STORE_QUEST = 2, STORE_MADE_HERE = 3, STORE_NONE = 4, STORE_MISSING = 5;
	public static final int SKY_ICON_SIZE = 64, MAX_SKY_ICONS = 1024, SKY_ICON_CATEGORY = 0xFFFFFF00;
	public static final long ICONS_GENERATION = 0x0, ICONS_SLOTS = 0x40, ICON_SLOT_BYTES = 16 + SKY_ICON_SIZE * SKY_ICON_SIZE * 4;
	public static final long ICON_SEQ = 0x0, ICON_KEY = 0x4, ICON_RGBA = 0x10;
	public static final int MAX_SKY_ITEMS = 4096, SKY_ITEM_BYTES = 192, SKY_ITEM_NAME_BYTES = 96, SKY_ITEM_PLUGIN_BYTES = 60;
	public static final long INV_SEQ = 0x0, INV_COUNT = 0x4, INV_GENERATION = 0x8, INV_GOLD = 0xC, INV_CARRIED = 0x10, INV_CARRY_MAX = 0x14, INV_ITEMS = 0x40;
	// SkyItem: formId 0, kind 4, sub 5, material 6, count 8, flags 12, weight 16, value 20, stat 24, stat2 28, name 32, localId 128, plugin 132 (v36)
	public static final int SKY_WEAPON = 1, SKY_APPAREL = 2, SKY_POTION = 3, SKY_FOOD = 4, SKY_INGREDIENT = 5, SKY_BOOK = 6, SKY_KEY = 7, SKY_MISC = 8, SKY_AMMO = 9,
		SKY_SCROLL = 10, SKY_SPELL = 20, SKY_SHOUT = 21, SKY_POWER = 22;
	public static final int W_HAND = 0, W_SWORD = 1, W_DAGGER = 2, W_WAR_AXE = 3, W_MACE = 4, W_GREATSWORD = 5, W_BATTLEAXE = 6, W_WARHAMMER = 7, W_BOW = 8, W_STAFF = 9,
		W_CROSSBOW = 10, W_PICKAXE = 11, W_WOOD_AXE = 12;
	public static final int A_BODY = 0, A_HEAD = 1, A_HANDS = 2, A_FEET = 3, A_SHIELD = 4, A_RING = 5, A_AMULET = 6, A_CIRCLET = 7;
	public static final int P_OTHER = 0, P_HEALTH = 1, P_MAGICKA = 2, P_STAMINA = 3, P_POISON = 4;
	public static final int SCHOOL_ALTERATION = 0, SCHOOL_CONJURATION = 1, SCHOOL_DESTRUCTION = 2, SCHOOL_ILLUSION = 3, SCHOOL_RESTORATION = 4;
	public static final int MAT_NONE = 0, MAT_WOOD = 1, MAT_LEATHER = 2, MAT_IRON = 3, MAT_STEEL = 4, MAT_ORCISH = 5, MAT_DWARVEN = 6, MAT_ELVEN = 7, MAT_GLASS = 8,
		MAT_EBONY = 9, MAT_DAEDRIC = 10, MAT_DRAGON = 11, MAT_SILVER = 12, MAT_GOLD = 13, MAT_STALHRIM = 14, MAT_CHAIN = 15, ELEMENT_FIRE = 1, ELEMENT_FROST = 2,
		ELEMENT_SHOCK = 3;
	public static final int ITEM_RIGHT_HAND = 1, ITEM_LEFT_HAND = 1 << 1, ITEM_WORN = 1 << 2, ITEM_VOICE = 1 << 3, ITEM_ENCHANTED = 1 << 4, ITEM_STOLEN = 1 << 5,
		ITEM_FAVORITE = 1 << 6, ITEM_HEAVY = 1 << 7, ITEM_LIGHT = 1 << 8, ITEM_FILLED = 1 << 9, ITEM_READ = 1 << 10, ITEM_QUEST = 1 << 11, ITEM_TWO_HANDED = 1 << 12;

	// Input types added in v5
	public static final int IN_HURT = 7;
	public static final int IN_OPEN_MENU = 8;
	public static final int IN_USE = 9;
	public static final int IN_SKYRIM_HIT = 10;
	public static final int IN_HEAL = 11;
	public static final int IN_SLOT_JUMP = 12; // SkyCraft+ : v34, a, b = x, y step to the next slot
	public static final int SKYRIM_HIT_POWER = 1, SKYRIM_HIT_BASH = 1 << 1, SKYRIM_HIT_LEFT = 1 << 2;
	public static final int HURT_MELEE = 0;
	public static final int HURT_PROJECTILE = 1;
	public static final int HURT_MAGIC = 2;
	public static final int HURT_OTHER = 3;
	public static final int HURT_BLOCKED_IN_SKYRIM = 1;
	public static final int HURT_POWER_ATTACK = 2;

	// Actor table (relative to OFF_ACTOR_TABLE)
	public static final int MAX_ACTORS = 256;
	public static final long AT_SEQ = 0x00;
	public static final long AT_COUNT = 0x04;
	public static final long AT_RECORDS = 0x40;
	public static final long ACTOR_RECORD_BYTES = 64;
	public static final int ACTOR_HOSTILE = 1;
	public static final int ACTOR_DEAD = 1 << 1;
	public static final int ACTOR_ESSENTIAL = 1 << 2;
	public static final int ACTOR_IN_COMBAT = 1 << 3;

	// Event ring (relative to OFF_EVENT_RING)
	public static final int EVENT_RING_ENTRIES = 512;
	public static final long ER_HEAD = 0x00;
	public static final long ER_TAIL = 0x40;
	public static final long ER_DATA = 0x80;
	public static final long EVENT_BYTES = 32;
	public static final int EV_HIT_ACTOR = 1;
	public static final int EV_PLAYER_DIED = 2;
	public static final int EV_EXPLOSION = 3;
	public static final int EV_ARROW_STUCK = 4;
	public static final int EV_SKILL_USE = 5;
	public static final int EV_PLAYER_HURT = 6;
	public static final int EV_SKY_ITEM = 7; // SkyCraft+ : v35, flags = SKY_ACT_*
	public static final int EV_DRAGON_SOUL = 8; // SkyCraft+ : v39, the Ender Dragon died near this player: a Skyrim dragon soul
	public static final int SKY_ACT_RIGHT = 1, SKY_ACT_LEFT = 2, SKY_ACT_USE = 3, SKY_ACT_MENU_STATS = 4, SKY_ACT_MENU_MAP = 5,
		SKY_ACT_EQUIP_RIGHT = 6, SKY_ACT_EQUIP_LEFT = 7, SKY_ACT_DROP = 8, // SkyCraft+ : v36, shortcuts
		SKY_ACT_UNEQUIP_RIGHT = 9, SKY_ACT_UNEQUIP_LEFT = 10; // SkyCraft+ : v38
	// Skyrim skills (ActorValue) Minecraft reports use of; weapon skills come from EV_HIT_ACTOR.
	public static final int SKILL_BLOCK = 9;
	public static final int SKILL_SMITHING = 10;
	public static final int SKILL_HEAVY_ARMOR = 11;
	public static final int SKILL_LIGHT_ARMOR = 12;
	public static final int HIT_CRITICAL = 1;
	public static final int HIT_PROJECTILE = 1 << 1;
	public static final int HIT_SWEEP = 1 << 2;
	public static final int HIT_FIRE = 1 << 3;
	public static final int WEAPON_UNARMED = 0;
	public static final int WEAPON_BLADE = 1;
	public static final int WEAPON_AXE = 2;
	public static final int WEAPON_BLUNT = 3;
	public static final int WEAPON_PIERCE = 4;
	public static final int WEAPON_ARROW = 5;

	// World entities (relative to OFF_WORLD_ENTITIES)
	public static final int MAX_WORLD_ENTITIES = 160;
	public static final long WE_SEQ = 0x00;
	public static final long WE_COUNT = 0x04;
	public static final long WE_HAS_SELECTION = 0x08;
	public static final long WE_SEL_MIN = 0x0C;
	public static final long WE_SEL_MAX = 0x18;
	public static final long WE_RECORDS = 0x40;
	public static final long WORLD_ENTITY_BYTES = 96;
	public static final int WE_ARROW = 1;
	public static final int WE_ITEM = 2;
	public static final int WE_TRIDENT = 3;
	public static final int WE_BLOCK = 4;
	public static final int WE_CRACK = 5;
	public static final int WE_SHADOW = 6;

	// Render ring (relative to OFF_RENDER_RING)
	public static final long RR_HEAD = 0x00;
	public static final long RR_TAIL = 0x40;
	public static final long RR_DATA = 0x80;
	public static final long RR_DATA_BYTES = RENDER_RING_BYTES - RR_DATA;
	public static final int REN_PAD = 0;
	public static final int REN_ATLAS = 1;
	public static final int REN_SECTION = 2;
	public static final int REN_CLEAR_ALL = 3;
	public static final int REN_TEXTURE = 4;
	public static final int REN_AVATAR = 5;
	public static final int REN_SCENE = 6;
	public static final int REN_ATLAS_REGION = 7;
	public static final int REN_LIGHTS = 8;
	public static final int REN_RAGDOLL = 9;
	public static final int REN_SOLIDS = 10;
	public static final int REN_DUG = 11;
	public static final int REN_SHAPES = 12;
	public static final int PART_HEAD = 1, PART_BODY = 2, PART_RIGHT_ARM = 3, PART_LEFT_ARM = 4, PART_RIGHT_LEG = 5, PART_LEFT_LEG = 6;
	public static final int LIGHT_STEADY = 0, LIGHT_FLAME = 1, LIGHT_LAVA = 2;
	public static final int REN_VERTEX_BYTES = 32;

	// Header
	public static final long H_MAGIC = 0x00;
	public static final long H_VERSION = 0x04;
	public static final long H_SKYRIM_PID = 0x08;
	public static final long H_MC_PID = 0x0C;
	public static final long H_SKYRIM_HEARTBEAT = 0x10;
	public static final long H_MC_HEARTBEAT = 0x18;

	// SkyState (relative to OFF_SKY_STATE)
	public static final long SS_SEQ = 0x00;
	public static final long SS_FLAGS = 0x04;
	public static final long SS_WORLD_ID = 0x08;
	public static final long SS_COLLISION_EPOCH = 0x0C;
	public static final long SS_POS_X = 0x10;
	public static final long SS_POS_Y = 0x18;
	public static final long SS_POS_Z = 0x20;
	public static final long SS_YAW = 0x28;
	public static final long SS_PITCH = 0x2C;
	public static final long SS_TELEPORT_SEQ = 0x30;
	public static final long SS_VIEWPORT_W = 0x34;
	public static final long SS_VIEWPORT_H = 0x38;
	public static final long SS_GAME_HOUR = 0x3C;
	public static final long SS_HEALTH_FRAC = 0x40;
	public static final long SS_PLACE_FLAGS = 0x44;
	public static final long SS_SEA_LEVEL = 0x48;
	public static final long SS_HAND_KINDS = 0x4C; // SkyCraft+ : v29, two bytes (right, left)
	public static final long SS_SEND_TO_WORLD = 0x4E; // SkyCraft+ : v30, one byte (a DIM_*; 0: none)

	public static final int SKY_IN_GAME = 1;
	public static final int SKY_MENU_OPEN = 1 << 1;
	public static final int SKY_LOADING = 1 << 2;
	public static final int SKY_DRIVES_PLAYER = 1 << 3;
	public static final int SKY_RIGHT_HAND_SKYRIM = 1 << 4;
	public static final int SKY_LEFT_HAND_SKYRIM = 1 << 5;
	public static final int SKY_BLOCKING = 1 << 6;
	public static final int SKY_PAUSED = 1 << 7;
	public static final int SKY_PARKED = 1 << 8; // SkyCraft+ : v26
	public static final int SKY_BRING_BACK = 1 << 9; // SkyCraft+ : v28
	public static final int SKY_RIDING = 1 << 10; // SkyCraft+ : v40, on a Skyrim horse in Skyrim's third-person camera

	// SkyState handKinds (SkyCraft+ : v29): what a Skyrim-held hand holds.
	public static final int HAND_NONE = 0, HAND_SWORD = 1, HAND_DAGGER = 2, HAND_WAR_AXE = 3, HAND_MACE = 4, HAND_GREATSWORD = 5, HAND_BATTLEAXE = 6,
		HAND_WARHAMMER = 7, HAND_BOW = 8, HAND_CROSSBOW = 9, HAND_STAFF = 10, HAND_SPELL = 11, HAND_SHIELD = 12, HAND_TORCH = 13,
		HAND_PICKAXE = 14, HAND_WOOD_AXE = 15;  // SkyCraft+ : v32, tools: they break blocks as Minecraft's

	public static final int PLACE_INTERIOR = 1, PLACE_SETTLEMENT = 1 << 1, PLACE_DUNGEON = 1 << 2, PLACE_RAIN = 1 << 3, PLACE_SNOW = 1 << 4,
		PLACE_TOWN = 1 << 5, PLACE_TAMRIEL = 1 << 6;

	// McState (relative to OFF_MC_STATE)
	public static final long MS_SEQ = 0x00;
	public static final long MS_FLAGS = 0x04;
	public static final long MS_X = 0x08;
	public static final long MS_Y = 0x10;
	public static final long MS_Z = 0x18;
	public static final long MS_YAW = 0x20;
	public static final long MS_PITCH = 0x24;
	public static final long MS_EYE_HEIGHT = 0x28;
	public static final long MS_SENSITIVITY = 0x2C;
	public static final long MS_TELEPORT_ACK = 0x30;
	public static final long MS_GUI_SCALE = 0x34;
	public static final long MS_FRAME_COUNTER = 0x38;
	public static final long MS_FOV = 0x40;
	public static final long MS_BOB_PHASE = 0x44;
	public static final long MS_BOB_AMOUNT = 0x48;
	public static final long MS_EYE_X = 0x50;
	public static final long MS_EYE_Y = 0x58;
	public static final long MS_EYE_Z = 0x60;
	public static final long MS_TICK_QPC = 0x68;
	public static final long MS_PREV_X = 0x70;
	public static final long MS_CUR_X = 0x88;
	public static final long MS_EYE_HEIGHT_O = 0xA0;
	public static final long MS_EYE_HEIGHT_T = 0xA4;
	public static final long MS_WALK_O = 0xA8;
	public static final long MS_WALK = 0xAC;
	public static final long MS_BOB_O = 0xB0;
	public static final long MS_BOB = 0xB4;
	public static final long MS_TICK_MS = 0xB8;
	public static final long MS_CAMERA_MODE = 0xC0;
	public static final long MS_CAMERA_DISTANCE = 0xC4;
	public static final long MS_HEALTH_FRAC = 0xC8;
	public static final long MS_DIMENSION = 0xCC; // SkyCraft+ : v26
	public static final long MS_HANDS = 0xD0; // SkyCraft+ : v33, hand mixing (see McState::hands)
	public static final long MS_CURSOR_SEQ = 0xD4, MS_CURSOR_X = 0xD8, MS_CURSOR_Y = 0xDC; // SkyCraft+ : v34, controller inventory snapping

	public static final int MC_IN_WORLD = 1;
	public static final int MC_SCREEN_OPEN = 1 << 1;
	public static final int MC_ON_GROUND = 1 << 2;
	public static final int MC_SNEAKING = 1 << 3;
	public static final int MC_SPRINTING = 1 << 4;
	public static final int MC_DEAD = 1 << 5;
	public static final int MC_SWIMMING = 1 << 6;
	public static final int MC_FLYING = 1 << 7;
	public static final int MC_USE_TARGET = 1 << 8;
	public static final int MC_UNDER_LAND = 1 << 9; // SkyCraft+ : v27
	public static final int MC_DEEP_UNDER_LAND = 1 << 10; // SkyCraft+ : v31, 24 blocks and more under
	public static final int MC_SLOT_SCREEN = 1 << 11; // SkyCraft+ : v34, a screen with item slots
	public static final int MC_WANTS_SKY_INVENTORY = 1 << 12; // SkyCraft+ : v35

	// SkyCraft+ : McState dimension (v26). 3 and up: Minecraft's own worlds, Skyrim parks its player.
	public static final int DIM_TAMRIEL = 0;
	public static final int DIM_INTERIORS = 1;
	public static final int DIM_WORLDSPACES = 2;
	public static final int DIM_NETHER = 3;
	public static final int DIM_END = 4;
	public static final int DIM_OTHER = 5;

	// Overlay
	public static final long OC_STATE = 0x00;
	public static final long OC_FRAMES_PUBLISHED = 0x08;
	public static final int OVERLAY_DIRTY = 1 << 2;
	public static final long SLOT_HDR_SIZE = 0x40;
	public static final long SH_WIDTH = 0x00;
	public static final long SH_HEIGHT = 0x04;
	public static final long SH_FLAGS = 0x08;
	public static final long SH_FRAME_ID = 0x10;

	// Input ring (relative to OFF_INPUT_RING)
	public static final int INPUT_RING_ENTRIES = 4096;
	public static final long IR_HEAD = 0x00;
	public static final long IR_TAIL = 0x40;
	public static final long IR_DATA = 0x80;
	public static final int IN_KEY = 1;
	public static final int IN_MOUSE_BUTTON = 2;
	public static final int IN_SCROLL = 3;
	public static final int IN_CURSOR = 4;
	public static final int IN_TEXT = 5;
	public static final int IN_RELEASE_ALL = 6;

	// Collision ring (relative to OFF_COLLISION_RING)
	public static final long CR_HEAD = 0x00;
	public static final long CR_TAIL = 0x40;
	public static final long CR_DATA = 0x80;
	public static final long CR_DATA_BYTES = COLLISION_RING_BYTES - CR_DATA;
	public static final int COL_PAD = 0;
	public static final int COL_CLEAR = 1;
	public static final int COL_REGION = 2;
	public static final int COL_TRIS = 3;
	public static final int COL_TRI_BYTES = 40;
	public static final int TRI_STAIR_HELPER = 1;
	public static final int TRI_DIGGABLE = 2;
	public static final int TRI_GHOST = 4;
	public static final int TRI_TERRAIN = 8;
	public static final int TRI_MATERIAL_SHIFT = 8;
	// DigMaterial (skycraft_protocol.h)
	public static final int DIG_NONE = 0, DIG_GRASS = 1, DIG_DIRT = 2, DIG_STONE = 3, DIG_COBBLE = 4, DIG_SNOW = 5, DIG_ICE = 6, DIG_SAND = 7,
		DIG_GRAVEL = 8, DIG_MUD = 9, DIG_OAK_LOG = 10, DIG_SPRUCE_LOG = 11, DIG_BIRCH_LOG = 12, DIG_PLANKS = 13, DIG_METAL = 14, DIG_GLASS = 15,
		DIG_ORGANIC = 16, DIG_CLOTH = 17, DIG_BONE = 18, DIG_WEB = 19, DIG_ASH = 20, DIG_BEDROCK = 21, DIG_MATERIAL_COUNT = 22;
	public static final int COL_REGION_HEADER_BYTES = 32;
	public static final int COL_BLOCK_BYTES = 80;
}
