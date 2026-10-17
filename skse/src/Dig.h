#pragma once

#include "Clip.h"
#include "Link.h"

// Digging into Skyrim's world. Minecraft decides what's dug (the Minecraft world keeps a set of
// dug blocks, sent here per section as proto::kRenDug); in a dug block Skyrim's own geometry is
// gone: not drawn (DigMesh.cpp), not in the collision Minecraft sees (Collision.cpp), and not
// under Skyrim's NPCs (DigPhysics.cpp), who stand on and bump into Minecraft's blocks instead.
namespace skycraft::Dig
{
	// ---- the dug blocks (Minecraft block coords) ----------------------------------------------
	// Render message (render thread): a section's dug blocks.
	void OnDug(const std::uint8_t* a_data, std::uint32_t a_bytes);
	// Minecraft is sending everything again (another world, or it reconnected).
	void Clear();
	// Skyrim's current world (proto::SkyState::worldId); anything dug in another world is dropped.
	void SetWorld(std::uint32_t a_worldId);
	bool Any();  // any thread: anything dug at all (cheap)
	bool IsDug(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z);  // any thread
	// Dug blocks whose cube overlaps [a_lo, a_hi] (Minecraft coords). Any thread.
	void Collect(const float a_lo[3], const float a_hi[3], std::vector<Clip::Cube>& a_out);
	// Blocks dug (or no longer dug) since the last call. Main thread, once a frame (Game.cpp hands
	// them on to Collision and the mesh cutter).
	void TakeChanged(std::vector<Clip::Cube>& a_out);

	// ---- what can be dug ----------------------------------------------------------------------
	// Ground, rocks, cliffs, cave and dungeon walls, trees: yes. Buildings (anything built from
	// Skyrim's architecture meshes), doors, furniture, containers, activators, actors: no.
	bool IsDiggableRef(RE::TESObjectREFR* a_ref);
	// Small things standing on the ground (rocks, shrubs, flowers, plants you pick): they go whole,
	// with their collision, when the ground under them is dug.
	inline constexpr float kSmallThingRadius = 150.0f;  // Skyrim units (about 2 blocks)
	bool                   IsSmallThing(RE::TESObjectREFR* a_ref);
	// A Havok body: its layer, and the reference it belongs to (land has none). Any thread.
	bool IsDiggableCollidable(const RE::hkpCollidable* a_collidable);
	// The Minecraft block (proto::DigMaterial) a piece of Skyrim geometry digs into.
	std::uint8_t MaterialFor(RE::MATERIAL_ID a_material, RE::TESObjectREFR* a_ref, bool a_tree);

	// SkyCraft+ : a block of a Skyrim ore vein mined at this Minecraft spot: the vein's ore into the player's inventory.
	void GiveMinedOre(RE::PlayerCharacter* a_player, double a_x, double a_y, double a_z);
	// Havok's material for one part of a shape (terrain: per triangle); kNone if unknown.
	RE::MATERIAL_ID ShapeMaterial(const RE::hkpShape* a_top, RE::hkpShapeKey a_key);
	// The land's material at a point (Skyrim units, x y): from the texture painted there (grass,
	// dirt, snow, rock...). kNone outside loaded exterior cells. Main thread.
	RE::MATERIAL_ID LandMaterialAt(float a_x, float a_y);

	// ---- the parts ----------------------------------------------------------------------------
	void Install();  // Havok hooks (DigPhysics.cpp)
	// Main thread, every frame: the collidable of the player's character proxy while Minecraft
	// drives the player (null otherwise), and its feet (Havok z). Its body only stands on what's
	// under its feet: anything else (the ground over a hole it's in) would shove it out.
	void SetPuppet(const RE::hkpCollidable* a_proxy, float a_feetHavokZ);
	// SkyCraft+ : the player stood on a Minecraft block plane within the last quarter second.
	bool PlayerOnBlock();
	// Main thread, every frame: cuts dug blocks out of the meshes Skyrim draws (DigMesh.cpp).
	// a_changed: blocks were dug this frame.
	void UpdateMeshes(RE::PlayerCharacter* a_player, float a_delta, bool a_changed);
	// Render thread, inside Skyrim's frame: reads back mesh data the cutter asked for.
	void ServiceReadbacks(ID3D11Device* a_device, ID3D11DeviceContext* a_context);
	// Render thread: grass (tufts, ferns, pebbles) on dug ground is taken away (DigGrass.cpp).
	void ServiceGrass(ID3D11Device* a_device, ID3D11DeviceContext* a_context);
	// Changes whenever the dug blocks do. Any thread.
	std::uint64_t Generation();
}
