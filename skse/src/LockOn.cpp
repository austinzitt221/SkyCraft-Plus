#include "Game.h"
#include "TDMApi.h"

#include <SimpleIni.h>

#include <chrono>
#include <cmath>
#include <unordered_map>

// Lock-on for Minecraft's mobs. They are not Skyrim actors, so True Directional Movement has nothing to lock on to; instead
// every frame the mobs Minecraft sent (proto::kWeMob) are handed to it as virtual targets, with TDM built from
// TrueDirectionalMovement-VirtualTargets.patch. TDM's own lock-on key, target switching, camera, facing and strafing then work on them,
// and it draws their name and health bar. Targets are only good to TDM for a second, so they are sent every frame while the
// mobs are around, and withdrawn when Minecraft's world isn't (Minecraft's controls, a menu, a load).
namespace skycraft::LockOn
{
	namespace
	{
		struct Config
		{
			bool  on{ true };
			float aimHeight{ 0.6f };
			float range{ 40.0f };
		};

		Config                 cfg;
		TDM_API::IVTDM6*       api = nullptr;
		bool                   installed = false;
		bool                   sentAny = false;
		std::uint32_t          lastLocked = 0;
		std::uint32_t          missingLogged = 0;
		proto::WorldEntities   entities{};
		std::vector<TDM_API::VirtualTarget> targets;

		// The targets of the last few frames. A mob that is missing from one frame's list (Minecraft's list not read in time, a mob left out for a
		// moment) must not drop the lock: TDM lets go the instant its target isn't there, so a target stays in the list for a little while after it
		// was last seen. (Not for long: one that has really gone must stop being a target.)
		struct Seen
		{
			TDM_API::VirtualTarget target{};
			std::int64_t           atMs{ 0 };
		};
		std::unordered_map<std::uint32_t, Seen> seen;

		constexpr std::int64_t kGraceMs = 750;

		std::int64_t NowMs()
		{
			return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
		}

		std::uint32_t TargetId(std::uint32_t a_entityId)
		{
			return a_entityId + 1;  // not 0
		}

		void Withdraw()
		{
			seen.clear();
			if (api && sentAny) {
				(void)api->SetVirtualTargets(SKSE::GetPluginHandle(), nullptr, 0);
			}
			sentAny = false;
		}
	}

	void Reload()
	{
		CSimpleIniA ini;
		ini.SetUnicode();
		ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
		cfg.on = ini.GetBoolValue("LockOn", "bLockOnMobs", true);
		cfg.aimHeight = static_cast<float>(std::clamp(ini.GetDoubleValue("LockOn", "fAimHeight", 0.6), 0.1, 1.0));
		cfg.range = static_cast<float>(std::clamp(ini.GetDoubleValue("LockOn", "fRange", 40.0), 4.0, 200.0));
	}

	void Install()
	{
		if (installed) {
			return;
		}
		installed = true;
		Reload();
		if (!cfg.on) {
			logger::info("lock-on: Minecraft's mobs as lock-on targets are off (SkyCraft.ini [LockOn] bLockOnMobs)");
			return;
		}
		auto* found = TDM_API::RequestPluginAPI(TDM_API::InterfaceVersion::V6);
		api = static_cast<TDM_API::IVTDM6*>(found);
		if (api) {
			logger::info("lock-on: True Directional Movement's virtual-target interface found; Minecraft's mobs can be locked on to");
		} else if (GetModuleHandleA("TrueDirectionalMovement.dll")) {
			logger::warn("lock-on: True Directional Movement is installed but has no virtual-target interface. Install the TrueDirectionalMovement.dll built with "
			             "TrueDirectionalMovement-VirtualTargets.patch over it; until then mobs can't be locked on to");
		} else {
			logger::warn("lock-on: True Directional Movement isn't installed, so there is no lock-on for Minecraft's mobs");
		}
	}

	void Update(RE::PlayerCharacter* a_player, bool a_active)
	{
		if (!api) {
			return;
		}
		a_active = a_active && cfg.on;  // SkyCraft+ : (turned off from the settings screen: its targets taken back)
		if (!a_active || !a_player) {
			Withdraw();
			return;
		}

		const auto now = NowMs();
		const auto playerMc = SkyToMc(a_player->GetPosition());
		// A list that couldn't be read this frame (Minecraft was writing it) is not "no mobs": the last one stands for the grace period.
		const bool read = Link::Get().ReadWorldEntities(entities);
		if (read) {
			for (std::uint32_t i = 0; i < entities.count; ++i) {
				const auto& e = entities.entities[i];
				if (e.kind != proto::kWeMob) {
					continue;
				}
				const double dx = e.x - playerMc.x, dy = e.y - playerMc.y, dz = e.z - playerMc.z;
				if (dx * dx + dy * dy + dz * dz > double(cfg.range) * cfg.range) {
					continue;
				}
				TDM_API::VirtualTarget target{};
				target.id = TargetId(e.id);
				const float height = std::max(e.scale, 0.3f);
				const auto  aim = McToSky(e.x, e.y + height * cfg.aimHeight, e.z);
				target.x = aim.x;
				target.y = aim.y;
				target.z = aim.z;
				target.health = e.ext[0] < 0.0f ? -1.0f : std::min(e.ext[0], 1.0f);
				static_assert(sizeof(target.name) == proto::kMobNameBytes);
				std::memcpy(target.name, e.uv, sizeof(target.name));
				target.name[sizeof(target.name) - 1] = '\0';
				seen[target.id] = { target, now };
			}
		}

		// Why a lock is about to go: the locked mob is not in this frame's list. Said once, with what is known about it.
		const std::uint32_t lockedNow = api->GetCurrentVirtualTarget();
		if (read && lockedNow != 0) {
			const auto it = seen.find(lockedNow);
			if (it != seen.end() && it->second.atMs == now) {
				missingLogged = 0;
			} else if (missingLogged != lockedNow) {
				missingLogged = lockedNow;
				bool         stillThere = false;
				proto::WorldEntity gone{};
				for (std::uint32_t i = 0; i < entities.count; ++i) {
					if (entities.entities[i].id == lockedNow - 1 && entities.entities[i].kind != proto::kWeMob) {
						stillThere = true;  // Minecraft still has it (its shadow is sent) but sends it no longer as a mob to lock on to
						gone = entities.entities[i];
						break;
					}
				}
				if (it != seen.end()) {
					const auto&  t = it->second.target;
					const double d = std::sqrt(double(gone.x - playerMc.x) * (gone.x - playerMc.x) + double(gone.z - playerMc.z) * (gone.z - playerMc.z));
					logger::info("lock-on: locked mob {} ('{}', health {:.2f}) is not in Minecraft's list this frame; {} (last seen {} ms ago{}{})", lockedNow - 1, t.name, t.health,
						stillThere ? "Minecraft still has it in the world, but not as a lockable mob (dead or dying?)" : "it is gone from Minecraft's list altogether", now - it->second.atMs,
						stillThere ? ", now " : "", stillThere ? fmt::format("{:.1f} blocks away", d) : std::string());
				}
			}
		}

		targets.clear();
		for (auto it = seen.begin(); it != seen.end();) {
			if (now - it->second.atMs > kGraceMs) {
				it = seen.erase(it);
			} else {
				targets.push_back(it->second.target);
				++it;
			}
		}

		(void)api->SetVirtualTargets(SKSE::GetPluginHandle(), targets.data(), static_cast<std::uint32_t>(targets.size()));
		sentAny = true;

		static int logged = 0;
		if (!targets.empty() && logged < 3) {
			++logged;
			logger::info("lock-on: {} Minecraft mob(s) handed to True Directional Movement as targets (first: '{}')", targets.size(), targets.front().name);
		}
		const std::uint32_t locked = api->GetCurrentVirtualTarget();
		if (locked != lastLocked) {
			lastLocked = locked;
			if (locked != 0) {
				logger::info("lock-on: locked on to Minecraft mob {}", locked - 1);
			} else {
				logger::info("lock-on: released");
			}
		}
	}
}
