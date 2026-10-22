// SkyCraft+ : Skyrim's people fight Minecraft's mobs
#include "Game.h"
#include "Link.h"

// Minecraft's mobs are only drawn in Skyrim, so Skyrim's people never saw them to fight: followers
// stood by while the player fought, and an enemy a wolf bit never bit back. Minecraft decides who
// fights what and deals the damage (SkyMobFights); here the fight is played: the actor turns to
// the mob, draws, and swings (kEvMobFight 1), and a follower out of reach is sent to it (0).
namespace skycraft::MobFights
{
	namespace
	{
		std::unordered_map<RE::FormID, RE::NiPointer<RE::TESObjectREFR>> markers;  // a follower's: where its mob is
		int                                                             logged = 0;
		// Held still through an attack (its follow and travel packages ran it off mid-swing, the draw
		// broken off for a run): until when (GetTickCount64).
		std::unordered_map<RE::FormID, std::uint64_t> held;
		std::uint64_t                                 foughtAt = 0;  // SkyCraft+ : the last swing at a Minecraft mob

		void Hold(RE::Actor* a_actor, std::uint64_t a_ms)
		{
			auto [it, added] = held.try_emplace(a_actor->GetFormID(), 0);
			it->second = GetTickCount64() + a_ms;
			if (added) {
				Animals::HoldForAttack(a_actor, true);
			}
		}

		void Free(RE::FormID a_actor)
		{
			if (held.erase(a_actor)) {
				if (auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_actor)) {
					Animals::HoldForAttack(actor, false);
				}
			}
		}
	}

	void OnFight(RE::PlayerCharacter* a_player, RE::FormID a_actor, std::uint32_t a_flags, double a_x, double a_y, double a_z)
	{
		auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_actor);
		if (!actor || actor->IsDead() || !actor->Get3D()) {
			return;
		}
		const auto at = McToSky(a_x, a_y, a_z);
		const auto pos = actor->GetPosition();
		if (!actor->AsActorState()->IsWeaponDrawn()) {
			actor->DrawWeaponMagicHands(true);
		}
		if (a_flags == 1 || a_flags == 2) {
			if (actor->IsPlayerTeammate()) {
				foughtAt = GetTickCount64();
			}
			actor->SetHeading(std::atan2(at.x - pos.x, at.y - pos.y));  // (Skyrim's heading: 0 north, clockwise)
			// A bow is drawn by its attackStart and only shot by attackRelease (without it the draw
			// was all that played); aimed at the mob's middle (Skyrim's pitch: positive looks down).
			const auto eye = actor->GetLookingAtLocation();
			const auto flat = std::sqrt((at.x - eye.x) * (at.x - eye.x) + (at.y - eye.y) * (at.y - eye.y));
			actor->data.angle.x = -std::atan2(at.z - eye.z, std::max(flat, 1.0f));
			actor->NotifyAnimationGraph(a_flags == 1 ? "attackStart" : "attackRelease");
			Hold(actor, a_flags == 2 ? 600 : Ranged(actor) ? 2500 : 1100);
			if (logged++ < 30) {
				logger::info("mob fights: {} {} a Minecraft mob", actor->GetName(), a_flags == 2 ? "shoots at" : Ranged(actor) ? "draws on" : "strikes at");
			}
			return;
		}
		Free(a_actor);
		auto& marker = markers[a_actor];
		if (!marker) {
			if (auto* xmarker = RE::TESForm::LookupByID<RE::TESBoundObject>(0x3B)) {
				marker = a_player->PlaceObjectAtMe(xmarker, false);
			}
		}
		if (!marker) {
			return;
		}
		marker->SetPosition(at);
		Animals::PathTo(actor, marker.get());
		if (logged++ < 30) {
			logger::info("mob fights: {} goes for a Minecraft mob ({:.0f} blocks off)", actor->GetName(), pos.GetDistance(at) / float(proto::kUnitsPerBlock));
		}
	}

	void PerFrame()
	{
		const auto now = GetTickCount64();
		std::vector<RE::FormID> done;
		for (const auto& [id, until] : held) {
			if (now >= until) {
				done.push_back(id);
			}
		}
		for (const auto id : done) {
			Free(id);
		}
	}

	void OnGameLoaded()
	{
		held.clear();
		auto* lists = RE::ProcessLists::GetSingleton();
		if (!lists) {
			return;
		}
		for (auto& handle : lists->highActorHandles) {
			auto actor = handle.get();
			if (actor && actor->IsPlayerTeammate()) {
				Animals::HoldStill(actor.get(), false);
			}
		}
	}

	void Clear()
	{
		markers.clear();
		while (!held.empty()) {
			Free(held.begin()->first);
		}
	}

	bool Fighting() { return foughtAt != 0 && GetTickCount64() - foughtAt < 5000; }

	bool Ranged(RE::Actor* a_actor)
	{
		auto* form = a_actor ? a_actor->GetEquippedObject(false) : nullptr;
		auto* weapon = form ? form->As<RE::TESObjectWEAP>() : nullptr;
		return weapon && (weapon->IsBow() || weapon->IsCrossbow());
	}
}
