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
		if (a_flags == 1) {
			actor->SetHeading(std::atan2(at.x - pos.x, at.y - pos.y));  // (Skyrim's heading: 0 north, clockwise)
			actor->NotifyAnimationGraph("attackStart");
			if (logged++ < 30) {
				logger::info("mob fights: {} strikes at a Minecraft mob", actor->GetName());
			}
			return;
		}
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

	void Clear()
	{
		markers.clear();
	}
}
