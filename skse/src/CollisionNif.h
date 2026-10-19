#pragma once

// Writes a Skyrim SE .nif that holds nothing but collision: an invisible static (BSFadeNode) with a fixed rigid body whose shape is a list
// of boxes. BlockCollision.cpp makes one of these for every section of Minecraft's blocks, Skyrim loads it as the model of a static
// reference, and its own loader turns the collision objects into Havok bodies in the cell's world, as it does for any building or rock.
//
// No Skyrim or CommonLib code in here: just bytes, so it can be (and was) checked on its own against an independent NIF reader.
//
// Layout (version 20.2.0.7, user version 12, user version 2 100), blocks in this order (children before the blocks that refer to them, as Skyrim's own files):
//   0 BSFadeNode (the root)   1 BSXFlags (havok)   2.. bhkConvexVerticesShape (one per box)   then bhkListShape, bhkRigidBody, bhkCollisionObject
// A box is a convex-vertices shape rather than a bhkBoxShape because a list's children can't be moved without a transform shape, and a
// box with its own corners needs none.

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace skycraft::collisionnif
{
	struct Box
	{
		float lo[3];  // corners, in game units from the model's origin (Skyrim's axes: x east, y north, z up)
		float hi[3];
	};

	// One Havok unit is this many game units.
	inline constexpr float kHavokScale = 69.99124f;

	inline constexpr std::uint32_t kMaxBoxes = 4000;

	// Details of the file that Skyrim might be particular about; BlockCollision.cpp tries variants of these side by side and keeps the first
	// that Skyrim loads (the defaults are the layout written from the NIF format's description).
	struct Options
	{
		bool          plainNode{ false };      // the root is a NiNode, not a BSFadeNode
		bool          rigidBodyT{ false };     // the body's type is named bhkRigidBodyT (same fields; most of Skyrim's static bodies are)
		std::uint16_t collisionFlags{ 129 };     // bhkCollisionObject flags (Skyrim's own often have 129)
		std::uint32_t bsxFlags{ 130 };         // BSXFlags value (0x82 as on vanilla statics: havok + static)
		int           preambleExtra{ 0 };      // bytes more (or fewer) in the body before its translation
		int           tailExtra{ 0 };          // bytes more (or fewer) in the body after its flags and before its constraints
		std::uint8_t  layer{ 1 };              // Havok collision layer of the body: 1 static, 8 biped (what Skyrim's weapons, arrows and spells hit on an actor)
		std::uint8_t  motion{ 5 };             // motion system: 5 fixed, 6 keyframed (follows its node, as an actor's bones do)
		bool          actorNodes{ false };     // an actor skeleton: the root is "NPC Root [Root]" with "NPC COM [COM ]" and "NPC Head [Head]" under it
		float         headHeight{ 0.0f };      // game units above the feet for the head node (actorNodes)
		int           level{ 3 };              // 0: a bare node, 1: with BSXFlags, 2: with a body whose shape is one box, 3: the real thing (a list of boxes)
	};

	// Havok material of the whole shape (the hash of its name). 3741512247 is stone: its footsteps, its sounds, its impacts.
	inline constexpr std::uint32_t kMaterialStone = 3741512247u;

	namespace detail
	{
		class Out
		{
		public:
			std::vector<std::uint8_t> bytes;

			template <class T>
			void Put(T a_value)
			{
				const auto at = bytes.size();
				bytes.resize(at + sizeof(T));
				std::memcpy(bytes.data() + at, &a_value, sizeof(T));
			}

			void Zeros(std::size_t a_count) { bytes.resize(bytes.size() + a_count, 0); }

			void Text(const std::string& a_text) { bytes.insert(bytes.end(), a_text.begin(), a_text.end()); }
		};

		// hkWorldObjCInfoProperty: the array header Havok keeps in the file (data pointer, size, capacity | "don't free")
		inline void Property(Out& a_out, std::uint32_t a_size)
		{
			a_out.Put<std::uint32_t>(0);
			a_out.Put<std::uint32_t>(a_size);
			a_out.Put<std::uint32_t>(0x80000000u | a_size);
		}

		// HavokFilter: layer (1 = static), flags and part number, group
		inline void Filter(Out& a_out, std::uint8_t a_layer = 1)
		{
			a_out.Put<std::uint8_t>(a_layer);
			a_out.Put<std::uint8_t>(0);
			a_out.Put<std::uint16_t>(0);
		}

		inline void Vec4(Out& a_out, float a_x, float a_y, float a_z, float a_w)
		{
			a_out.Put(a_x);
			a_out.Put(a_y);
			a_out.Put(a_z);
			a_out.Put(a_w);
		}

		// the bhkRigidBody block: fixed in place (a static), its shape the block a_shapeRef
		inline void RigidBody(Out& b, std::int32_t a_shapeRef, const Options& a_opt)
		{
			b.Put<std::int32_t>(a_shapeRef);  // shape
			Filter(b, a_opt.layer);
			b.Put<std::uint32_t>(0);  // (unused)
			b.Put<std::uint8_t>(1);   // broad phase: entity
			b.Zeros(3);
			Property(b, 0);
			b.Put<std::uint8_t>(1);       // collision response: simple contact
			b.Put<std::uint8_t>(0);
			b.Put<std::uint16_t>(0xFFFF); // process contact callback delay
			b.Put<std::uint32_t>(0);
			Filter(b, a_opt.layer);       // filter copy
			// Revision 35: these twelve bytes were all zeros. In every one of Skyrim's own bodies (58 checked in vanilla_dump) the last four are
			// 01 xx FF FF: the SECOND collision response and callback delay, the pair Havok really builds the body from. Zero there is response
			// type INVALID, which has no contact handler, and the first thing to touch the body (the player's controller standing in the section)
			// calls through it.
			b.Zeros(static_cast<std::size_t>(8 + a_opt.preambleExtra));
			b.Put<std::uint8_t>(1);       // collision response 2: simple contact
			b.Put<std::uint8_t>(0);
			b.Put<std::uint16_t>(0xFFFF); // process contact callback delay 2
			Vec4(b, 0, 0, 0, 0);          // translation
			Vec4(b, 0, 0, 0, 1);          // rotation (x y z w)
			Vec4(b, 0, 0, 0, 0);          // linear velocity
			Vec4(b, 0, 0, 0, 0);          // angular velocity
			b.Zeros(48);                  // inertia tensor
			Vec4(b, 0, 0, 0, 0);          // centre
			b.Put(0.0f);                  // mass (0: fixed)
			b.Put(0.1f);                  // linear damping
			b.Put(0.05f);                 // angular damping
			b.Put(1.0f);                  // time factor
			b.Put(1.0f);                  // gravity factor
			b.Put(0.5f);                  // friction
			b.Put(0.0f);                  // rolling friction multiplier (0 in every vanilla body; 1.0 was written before)
			b.Put(0.4f);                  // restitution
			b.Put(104.4f);                // max linear velocity
			b.Put(31.57f);                // max angular velocity
			b.Put(0.15f);                 // penetration depth
			// These eight bytes copied from Skyrim's own static bodies (vanilla_dump: 05 01 01 00 00 00 03 00). Motion system 5 is FIXED;
			// the 7 written here before is CHARACTER, which made Skyrim treat the static as a character-controller body.
			b.Put<std::uint8_t>(a_opt.motion);  // motion system: 5 fixed, 6 keyframed
			b.Put<std::uint8_t>(1);       // deactivator: never
			b.Put<std::uint8_t>(1);       // solver deactivation: off
			b.Put<std::uint8_t>(0);       // quality (0 in every vanilla static)
			b.Put<std::uint8_t>(0);       // auto remove level
			b.Put<std::uint8_t>(0);       // response modifier
			b.Put<std::uint8_t>(3);       // as vanilla
			b.Put<std::uint8_t>(0);       // force collided owner
			b.Zeros(static_cast<std::size_t>(12 + a_opt.tailExtra));
			b.Put<std::uint32_t>(0);      // constraints
			b.Put<std::uint16_t>(0);      // body flags
		}

		// Header, blocks and footer of a file whose blocks are given (the layout of the header is the same whatever is in the blocks).
		inline std::vector<std::uint8_t> Assemble(const std::vector<Out>& a_blocks, const std::vector<std::string>& a_typeNames, const std::vector<std::uint16_t>& a_typeOf, const std::vector<std::string>& a_strings)
		{
			Out file;
			file.Text("Gamebryo File Format, Version 20.2.0.7\n");
			file.Put<std::uint32_t>(0x14020007);
			file.Put<std::uint8_t>(1);   // little endian
			file.Put<std::uint32_t>(12); // user version
			file.Put<std::uint32_t>(static_cast<std::uint32_t>(a_blocks.size()));
			file.Put<std::uint32_t>(100);  // user version 2 (Skyrim SE)
			for (const char* text : { "SkyCraft", "", "" }) {
				const std::size_t n = std::strlen(text);
				file.Put<std::uint8_t>(static_cast<std::uint8_t>(n + 1));
				file.Text(text);
				file.Put<std::uint8_t>(0);
			}
			file.Put<std::uint16_t>(static_cast<std::uint16_t>(a_typeNames.size()));
			for (const auto& name : a_typeNames) {
				file.Put<std::uint32_t>(static_cast<std::uint32_t>(name.size()));
				file.Text(name);
			}
			for (const auto t : a_typeOf) {
				file.Put<std::uint16_t>(t);
			}
			for (const auto& b : a_blocks) {
				file.Put<std::uint32_t>(static_cast<std::uint32_t>(b.bytes.size()));
			}
			std::uint32_t longest = 0;
			for (const auto& str : a_strings) {
				longest = std::max<std::uint32_t>(longest, static_cast<std::uint32_t>(str.size()));
			}
			file.Put<std::uint32_t>(static_cast<std::uint32_t>(a_strings.size()));
			file.Put<std::uint32_t>(longest);
			for (const auto& str : a_strings) {
				file.Put<std::uint32_t>(static_cast<std::uint32_t>(str.size()));
				file.Text(str);
			}
			file.Put<std::uint32_t>(0);  // groups
			for (const auto& b : a_blocks) {
				file.bytes.insert(file.bytes.end(), b.bytes.begin(), b.bytes.end());
			}
			file.Put<std::uint32_t>(1);  // roots
			file.Put<std::int32_t>(0);
			return std::move(file.bytes);
		}

		// The root node: its extra data (a_extra, -1 for none) and collision object (a_collision, -1 for none).
		inline void RootNode(Out& b, std::int32_t a_extra, std::int32_t a_collision, std::int32_t a_name = 0, float a_z = 0.0f, const std::vector<std::int32_t>& a_children = {})
		{
			b.Put<std::int32_t>(a_name);  // name: a string index
			if (a_extra >= 0) {
				b.Put<std::uint32_t>(1);
				b.Put<std::int32_t>(a_extra);
			} else {
				b.Put<std::uint32_t>(0);
			}
			b.Put<std::int32_t>(-1);  // no controller
			b.Put<std::uint32_t>(14); // flags
			b.Put(0.0f), b.Put(0.0f), b.Put(a_z);
			const float identity[9] = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
			for (const float f : identity) {
				b.Put(f);
			}
			b.Put(1.0f);
			b.Put<std::int32_t>(a_collision);
			b.Put<std::uint32_t>(static_cast<std::uint32_t>(a_children.size()));  // children
			for (const auto c : a_children) {
				b.Put<std::int32_t>(c);
			}
			b.Put<std::uint32_t>(0);  // effects
		}

		// Smaller files for finding out which part Skyrim won't load: 0 a bare node, 1 plus BSXFlags, 2 plus a body of one box (a bhkBoxShape).
		inline std::vector<std::uint8_t> Simple(const Box& a_box, std::uint32_t a_material, float a_radius, const Options& a_opt, float a_scale)
		{
			const std::string nodeType = a_opt.plainNode ? "NiNode" : "BSFadeNode";
			std::vector<Out>           blocks;
			std::vector<std::string>   types{ nodeType };
			std::vector<std::uint16_t> typeOf{ 0 };
			std::vector<std::string>   strings{ "SkyCraftCollision" };
			blocks.resize(1);
			if (a_opt.level <= 0) {
				RootNode(blocks[0], -1, -1);
				return Assemble(blocks, types, typeOf, strings);
			}
			strings.push_back("BSX");
			types.push_back("BSXFlags");
			typeOf.push_back(1);
			blocks.resize(2);
			blocks[1].Put<std::int32_t>(1);
			blocks[1].Put<std::uint32_t>(a_opt.bsxFlags);
			if (a_opt.level == 1) {
				RootNode(blocks[0], 1, -1);
				return Assemble(blocks, types, typeOf, strings);
			}
			// Revision 35: blocks in Skyrim's own order, a shape before the body that holds it and the body before the collision object (the
			// loader builds each Havok object as it links its block, so a block must come after everything it refers to).
			RootNode(blocks[0], 1, 4);
			types.push_back("bhkBoxShape");
			types.push_back(a_opt.rigidBodyT ? "bhkRigidBodyT" : "bhkRigidBody");
			types.push_back("bhkCollisionObject");
			typeOf.push_back(2);
			typeOf.push_back(3);
			typeOf.push_back(4);
			blocks.resize(5);
			{
				Out& b = blocks[2];  // the box
				b.Put<std::uint32_t>(a_material);
				b.Put(a_radius);
				b.Zeros(8);
				b.Put((a_box.hi[0] - a_box.lo[0]) * 0.5f / a_scale);  // half extents
				b.Put((a_box.hi[1] - a_box.lo[1]) * 0.5f / a_scale);
				b.Put((a_box.hi[2] - a_box.lo[2]) * 0.5f / a_scale);
				b.Put(0.0f);
			}
			RigidBody(blocks[3], 2, a_opt);
			blocks[4].Put<std::int32_t>(0);
			blocks[4].Put<std::uint16_t>(a_opt.collisionFlags);
			blocks[4].Put<std::int32_t>(3);
			return Assemble(blocks, types, typeOf, strings);
		}

	}

	// The file's bytes. a_radius is Havok's convex radius in Havok units (0.01 is what Skyrim's own meshes use); boxes beyond kMaxBoxes are dropped.
	//
	// Block order (revision 35; this is Skyrim's own order, checked on every list shape in vanilla_dump): 0 root, 1 BSXFlags, 2.. the convex
	// shapes (one per box), then the list shape, the rigid body and the collision object. Skyrim builds each Havok object while linking its
	// block, in file order, so a list shape placed BEFORE its children read their (still null) Havok shapes: the crash at SkyrimSE.exe+0EEE90F,
	// `mov r8d, [rdx+0x18]` with rdx 0 (a shape's type), in the model loader thread, on every section model.
	inline std::vector<std::uint8_t> Build(const std::vector<Box>& a_boxes, std::uint32_t a_material = kMaterialStone, float a_radius = 0.01f, const Options& a_opt = {})
	{
		using namespace detail;

		if (a_opt.level < 3) {
			const Box first = a_boxes.empty() ? Box{ { 0, 0, 0 }, { 70, 70, 70 } } : a_boxes[0];
			return Simple(first, a_material, a_radius, a_opt, kHavokScale);
		}

		const std::uint32_t boxCount = static_cast<std::uint32_t>(a_boxes.size() > kMaxBoxes ? kMaxBoxes : a_boxes.size());
		const std::uint32_t extraNodes  = a_opt.actorNodes ? 2u : 0u;  // "NPC COM [COM ]" and "NPC Head [Head]", after the collision object
		const std::uint32_t blockCount  = 5 + boxCount + extraNodes;
		const std::int32_t  listAt = static_cast<std::int32_t>(2 + boxCount);
		const std::int32_t  bodyAt = listAt + 1;
		const std::int32_t  collisionAt = listAt + 2;

		std::vector<Out> blocks(blockCount);

		// 0: the root (a BSFadeNode with nothing in it but the collision)
		if (a_opt.actorNodes) {
			RootNode(blocks[0], 1, collisionAt, 0, 0.0f, { collisionAt + 1, collisionAt + 2 });
			float com = 0.0f, top = 0.0f;
			for (std::uint32_t i = 0; i < boxCount; ++i) {
				top = std::max(top, a_boxes[i].hi[2]);
			}
			com = top * 0.5f;
			const float head = a_opt.headHeight > 0.0f ? a_opt.headHeight : top * 0.9f;
			RootNode(blocks[static_cast<std::size_t>(collisionAt + 1)], -1, -1, 2, com);
			RootNode(blocks[static_cast<std::size_t>(collisionAt + 2)], -1, -1, 3, head);
		} else {
			RootNode(blocks[0], 1, collisionAt);
		}

		// 1: BSXFlags: havok (bit 1), so the loader makes the collision
		{
			Out& b = blocks[1];
			b.Put<std::int32_t>(1);  // name: string 1 ("BSX")
			b.Put<std::uint32_t>(a_opt.bsxFlags);
		}

		// 2..: one bhkConvexVerticesShape per box: its 8 corners and 6 planes (normal, then -distance: inside is where n.p + d <= 0)
		for (std::uint32_t i = 0; i < boxCount; ++i) {
			Out&  b = blocks[2 + i];
			float lo[3], hi[3];
			for (int k = 0; k < 3; ++k) {
				lo[k] = a_boxes[i].lo[k] / kHavokScale;
				hi[k] = a_boxes[i].hi[k] / kHavokScale;
			}
			b.Put<std::uint32_t>(a_material);
			b.Put(a_radius);
			b.Zeros(24);  // both array headers empty, as vanilla's convex shapes have them
			b.Put<std::uint32_t>(8);
			for (int corner = 0; corner < 8; ++corner) {
				Vec4(b, (corner & 1) ? hi[0] : lo[0], (corner & 2) ? hi[1] : lo[1], (corner & 4) ? hi[2] : lo[2], 0.0f);
			}
			b.Put<std::uint32_t>(6);
			Vec4(b, 1, 0, 0, -hi[0]);
			Vec4(b, -1, 0, 0, lo[0]);
			Vec4(b, 0, 1, 0, -hi[1]);
			Vec4(b, 0, -1, 0, lo[1]);
			Vec4(b, 0, 0, 1, -hi[2]);
			Vec4(b, 0, 0, -1, lo[2]);
		}

		// the list shape
		{
			Out& b = blocks[static_cast<std::size_t>(listAt)];
			b.Put<std::uint32_t>(boxCount);
			for (std::uint32_t i = 0; i < boxCount; ++i) {
				b.Put<std::int32_t>(static_cast<std::int32_t>(2 + i));
			}
			b.Put<std::uint32_t>(a_material);
			// The two array headers are written empty (all zeros, as in vanilla's BasicTankard01).
			b.Zeros(12);  // child shapes
			b.Zeros(12);  // child filters
			b.Put<std::uint32_t>(boxCount);
			for (std::uint32_t i = 0; i < boxCount; ++i) {
				Filter(b);
			}
		}

		// the rigid body
		RigidBody(blocks[static_cast<std::size_t>(bodyAt)], listAt, a_opt);

		// the collision object
		{
			Out& b = blocks[static_cast<std::size_t>(collisionAt)];
			b.Put<std::int32_t>(0);   // target: the root
			b.Put<std::uint16_t>(a_opt.collisionFlags);  // flags (1: active)
			b.Put<std::int32_t>(bodyAt);
		}

		// ---- the header, the blocks and the footer ----
		std::vector<std::string>   typeNames{ a_opt.plainNode ? "NiNode" : "BSFadeNode", "BSXFlags", "bhkConvexVerticesShape", "bhkListShape", a_opt.rigidBodyT ? "bhkRigidBodyT" : "bhkRigidBody", "bhkCollisionObject" };
		std::vector<std::uint16_t> typeOf(blockCount);
		typeOf[0] = 0;
		typeOf[1] = 1;
		for (std::uint32_t i = 0; i < boxCount; ++i) {
			typeOf[2 + i] = 2;
		}
		typeOf[static_cast<std::size_t>(listAt)] = 3;
		typeOf[static_cast<std::size_t>(bodyAt)] = 4;
		typeOf[static_cast<std::size_t>(collisionAt)] = 5;
		if (a_opt.actorNodes) {
			typeNames.push_back("NiNode");
			typeOf[static_cast<std::size_t>(collisionAt + 1)] = 6;
			typeOf[static_cast<std::size_t>(collisionAt + 2)] = 6;
			return Assemble(blocks, typeNames, typeOf, { "NPC Root [Root]", "BSX", "NPC COM [COM ]", "NPC Head [Head]" });
		}
		return Assemble(blocks, typeNames, typeOf, { "SkyCraftCollision", "BSX" });
	}
}
