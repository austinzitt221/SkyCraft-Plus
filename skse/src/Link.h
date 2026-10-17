#pragma once

#include "skycraft_protocol.h"

namespace skycraft
{

	// Owner of the shared-memory mapping (Skyrim creates it; Minecraft opens it).
	class Link
	{
	public:
		static Link& Get();

		bool Create();
		[[nodiscard]] bool Valid() const { return base_ != nullptr; }

		// True if Minecraft has touched its heartbeat recently.
		[[nodiscard]] bool McAlive() const;
		void               Heartbeat();
		// Process id Minecraft wrote when it opened the mapping (changes when Minecraft restarts).
		[[nodiscard]] std::uint32_t McPid() const;

		// Seqlock write of Skyrim -> MC state. Called once per Skyrim frame (MC paces on seq).
		void WriteSkyState(const proto::SkyState& a_state);
		void WriteWaterGrid(const proto::WaterGrid& a_grid);
		// Seqlock read of MC -> Skyrim state. Returns false if no consistent snapshot was obtained.
		bool ReadMcState(proto::McState& a_out) const;

		// Input ring (producer side). Drops the event if MC has fallen a full ring behind.
		void PushInput(proto::InputType a_type, std::uint16_t a_code, std::int32_t a_a = 0, std::int32_t a_b = 0, std::int32_t a_c = 0);

		// Collision ring (producer side, one thread only). Returns false if the ring is full.
		bool WriteCollision(proto::ColType a_type, const void* a_payload, std::uint32_t a_bytes);

		// Actor table (producer, main thread): nearby actors Minecraft mirrors as hittable stand-ins.
		void WriteActors(const proto::ActorRecord* a_records, std::uint32_t a_count);
		// SkyCraft+ : the Skyrim player's projectiles this frame (main thread).
		void WriteSkyrimShots(const proto::SkyrimShot* a_shots, std::uint32_t a_count);
		// SkyCraft+ : Skyrim's inventory in Minecraft's.
		void WriteSkyInventory(const proto::SkyItem* a_items, std::uint32_t a_count, std::uint32_t a_generation, std::int32_t a_gold, float a_carried, float a_carryMax);
		// SkyCraft+ : Skyrim's item pictures. One picture (kSkyIconSize square, RGBA) into a slot.
		void WriteSkyIcon(std::uint32_t a_slot, std::uint32_t a_key, const std::uint8_t* a_rgba);
		// SkyCraft+ : Skyrim items in Minecraft's containers (main thread). A request from Minecraft
		// (false when none), and a result back (false when the ring is full: try again later).
		bool PopStoreRequest(proto::SkyStoreRequest& a_out);
		bool PushStoreResult(const proto::SkyStoreResult& a_result);
		// Event ring (consumer, main thread). Returns false when empty.
		bool PopEvent(proto::McEvent& a_out);
		// World entities + block outline (seqlock read, render thread).
		bool ReadWorldEntities(proto::WorldEntities& a_out) const;
		// Render ring (consumer, render thread): calls a_fn(type, payload, bytes) for each pending
		// message, up to about a_maxBytes of payload. The payload points into shared memory.
		void DrainRender(const std::function<void(std::uint32_t, const std::uint8_t*, std::uint32_t)>& a_fn, std::uint64_t a_maxBytes);

		// Overlay triple buffer (consumer side). If a newer frame is available, swaps it into the
		// front slot and returns true. FrontPixels/FrontHeader describe the current front slot.
		bool                                        AcquireOverlayFrame();
		// Minecraft (re)connected: its writer starts at slot 1, so restart the swap from scratch.
		void                                        ResetOverlay();
		[[nodiscard]] const std::uint8_t*           FrontPixels() const;
		[[nodiscard]] const proto::OverlaySlotHdr*  FrontHeader() const;

	private:
		template <class T>
		T* At(std::uint64_t a_off) const { return reinterpret_cast<T*>(base_ + a_off); }

		HANDLE        mapping_{ nullptr };
		std::uint8_t* base_{ nullptr };
		std::uint32_t overlayFront_{ 2 };
	};

	// ---- coordinate conversion (Skyrim units <-> Minecraft blocks) ----------------------------
	struct McVec
	{
		double x, y, z;
	};

	inline McVec SkyToMc(const RE::NiPoint3& a_p)
	{
		return { a_p.x / proto::kUnitsPerBlock, a_p.z / proto::kUnitsPerBlock, -a_p.y / proto::kUnitsPerBlock };
	}

	inline RE::NiPoint3 McToSky(double a_x, double a_y, double a_z)
	{
		return { float(a_x * proto::kUnitsPerBlock), float(-a_z * proto::kUnitsPerBlock), float(a_y * proto::kUnitsPerBlock) };
	}

	// Skyrim heading h (radians, 0 = north, clockwise) <-> MC yaw (degrees, 0 = south).
	inline float HeadingToMcYaw(float a_heading) { return a_heading * 57.2957795f + 180.0f; }
	inline float McYawToHeading(float a_yaw) { return (a_yaw - 180.0f) * 0.0174532925f; }
}
