// SkyCraft+ : Skyrim animals and Minecraft's ways with animals
#include "Game.h"
#include "Link.h"

// Skyrim's animals follow the food they like while the player holds it, as Minecraft's are
// tempted: chickens seeds, cows, goats, deer and elk wheat, hares carrots, horses apples, foxes
// berries, dogs bones and meat, mudcrabs fish, boars beetroot (McState::heldFood). Within ten
// blocks they come and keep close in front of the player (Skyrim's own KeepOffsetFromActor, as its
// followers use), and let go when the food is put away, the player is far, or they fight.
namespace skycraft::Animals
{
	namespace
	{
		constexpr float kTemptRange = 10.0f * float(proto::kUnitsPerBlock);
		constexpr float kLetGoRange = 16.0f * float(proto::kUnitsPerBlock);

		float                                    timer = 0.0f;
		std::unordered_map<RE::FormID, bool>     following;  // the animals keeping close now
		std::unordered_map<RE::FormID, std::uint32_t> likesCache;  // by race

		// Minecraft leads on Skyrim actors: the player's (they follow, through doors too) or a fence
		// post's (they're walked back to it when they stray, as a pen keeps them).
		struct Lead
		{
			std::uint32_t                    mode = 0;
			RE::NiPoint3                     post;
			RE::NiPointer<RE::TESObjectREFR> marker;
			float                            pullTimer = 0.0f;
		};
		std::unordered_map<RE::FormID, Lead> leads;
		RE::TESObjectCELL*                   leadCell = nullptr;
		constexpr float                      kPostSlack = 4.0f * float(proto::kUnitsPerBlock);

		std::string Lower(const char* a_text)
		{
			std::string out = a_text ? a_text : "";
			std::ranges::transform(out, out.begin(), [](unsigned char a_c) { return char(std::tolower(a_c)); });
			return out;
		}

		// The food this animal likes (HeldFood bits), by its race and name; 0 for anything else.
		std::uint32_t Likes(RE::Actor* a_actor)
		{
			auto* race = a_actor->GetRace();
			if (!race) {
				return 0;
			}
			if (auto it = likesCache.find(race->GetFormID()); it != likesCache.end()) {
				return it->second;
			}
			const std::string what = Lower(race->GetName()) + " " + Lower(a_actor->GetName());
			auto has = [&](std::string_view a_part) { return what.find(a_part) != std::string::npos; };
			std::uint32_t likes = 0;
			if (has("chicken") || has(" hen")) {
				likes = proto::kFoodSeeds;
			} else if (has("cow") || has("bull") || has("goat") || has("deer") || has("elk") || has("stag")) {
				likes = proto::kFoodWheat;
			} else if (has("hare") || has("rabbit")) {
				likes = proto::kFoodCarrot;
			} else if (has("horse")) {
				likes = proto::kFoodApple;
			} else if (has("fox")) {
				likes = proto::kFoodBerries;
			} else if (has("dog") || has("husky")) {
				likes = proto::kFoodMeat;
			} else if (has("mudcrab")) {
				likes = proto::kFoodFish;
			} else if (has("boar") || has("bristleback")) {
				likes = proto::kFoodBeetroot;
			}
			likesCache.emplace(race->GetFormID(), likes);
			logger::info("animals: {} ({}) likes {:02X}", race->GetName() ? race->GetName() : "?", a_actor->GetName(), likes);
			return likes;
		}
	}

	// A Papyrus Actor method on this actor (Skyrim's own behaviour, as scripts use it).
	template <class... Args>
	void CallActor(RE::Actor* a_actor, const char* a_function, Args&&... a_args)
	{
		auto* vm = RE::BSScript::Internal::VirtualMachine::GetSingleton();
		auto* policy = vm ? vm->GetObjectHandlePolicy() : nullptr;
		if (!policy) {
			return;
		}
		const auto handle = policy->GetHandleForObject(static_cast<RE::VMTypeID>(RE::Actor::FORMTYPE), a_actor);
		if (handle == policy->EmptyHandle()) {
			return;
		}
		RE::BSTSmartPointer<RE::BSScript::IStackCallbackFunctor> callback;
		auto* arguments = RE::MakeFunctionArguments(std::forward<Args>(a_args)...);
		vm->DispatchMethodCall2(handle, "Actor", a_function, arguments, callback);
	}

	void Follow(RE::Actor* a_actor, RE::Actor* a_target, float a_offsetX, float a_offsetY)
	{
		CallActor(a_actor, "KeepOffsetFromActor", std::move(a_target), std::move(a_offsetX), std::move(a_offsetY), 0.0f, 0.0f, 0.0f, 0.0f, 300.0f, 60.0f);
	}

	void LetGo(RE::Actor* a_actor)
	{
		CallActor(a_actor, "ClearKeepOffsetFromActor");
	}

	bool InQuest(RE::Actor* a_actor)
	{
		return a_actor && (a_actor->IsEssential() || a_actor->IsProtected() || a_actor->extraList.HasType(RE::ExtraDataType::kAliasInstanceArray));
	}

	bool OnPlayersLead(RE::FormID a_actor)
	{
		auto it = leads.find(a_actor);
		return it != leads.end() && it->second.mode == 1;
	}

	void OnLeash(RE::PlayerCharacter* a_player, RE::FormID a_actor, std::uint32_t a_mode, double a_x, double a_y, double a_z)
	{
		auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_actor);
		if (!actor || actor->IsPlayerRef()) {
			return;
		}
		if (a_mode == 0) {
			if (leads.erase(a_actor)) {
				LetGo(actor);
				logger::info("leads: {} let go", actor->GetName());
			}
			return;
		}
		auto& lead = leads[a_actor];
		lead.mode = a_mode;
		if (a_mode == 1) {
			Follow(actor, a_player, 0.0f, -120.0f);  // (behind the player, on the lead)
			logger::info("leads: {} on the player's lead{}", actor->GetName(), InQuest(actor) ? " (in a quest)" : "");
			return;
		}
		LetGo(actor);
		lead.post = McToSky(a_x, a_y, a_z);
		if (!lead.marker) {
			if (auto* xmarker = RE::TESForm::LookupByID<RE::TESBoundObject>(0x3B)) {
				lead.marker = a_player->PlaceObjectAtMe(xmarker, false);
			}
		}
		if (lead.marker) {
			lead.marker->SetPosition(lead.post);
		}
		lead.pullTimer = 0.0f;
		logger::info("leads: {} tied to a fence post", actor->GetName());
	}

	// The player's led actors come through doors with them; tied ones are walked back to their post.
	void TickLeads(RE::PlayerCharacter* a_player, float a_delta)
	{
		auto* cell = a_player->GetParentCell();
		const bool moved = cell != leadCell;
		leadCell = cell;
		for (auto it = leads.begin(); it != leads.end();) {
			auto* actor = RE::TESForm::LookupByID<RE::Actor>(it->first);
			if (!actor || actor->IsDead()) {
				it = leads.erase(it);
				continue;
			}
			auto& lead = it->second;
			if (lead.mode == 1 && moved && cell && actor->GetParentCell() != cell) {
				actor->MoveTo(a_player);
				Follow(actor, a_player, 0.0f, -120.0f);
				logger::info("leads: {} came through the door on the lead", actor->GetName());
			} else if (lead.mode == 2 && lead.marker && (lead.pullTimer -= a_delta) <= 0.0f) {
				lead.pullTimer = 3.0f;
				if (actor->GetParentCell() == lead.marker->GetParentCell() && actor->GetPosition().GetDistance(lead.post) > kPostSlack) {
					RE::TESObjectREFR* post = lead.marker.get();
					CallActor(actor, "PathToReference", std::move(post), 0.5f);
				}
			}
			++it;
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, std::uint32_t a_heldFood, bool a_driving, float a_delta)
	{
		TickLeads(a_player, a_delta);
		timer -= a_delta;
		if (timer > 0.0f) {
			return;
		}
		timer = 0.5f;
		const std::uint32_t food = a_driving && State().animalsFollowFood ? a_heldFood : 0u;
		if (!food && following.empty()) {
			return;
		}
		const RE::NiPoint3 at = a_player->GetPosition();
		std::unordered_map<RE::FormID, bool> now;
		if (auto* lists = RE::ProcessLists::GetSingleton(); lists && food) {
			for (auto& handle : lists->highActorHandles) {
				auto  actorPtr = handle.get();
				auto* actor = actorPtr.get();
				if (!actor || actor->IsDead() || actor->IsPlayerRef() || actor->IsInCombat() || leads.contains(actor->GetFormID()) || !(Likes(actor) & food)) {
					continue;
				}
				const float distance = actor->GetPosition().GetDistance(at);
				const bool  was = following.contains(actor->GetFormID());
				if (distance > (was ? kLetGoRange : kTemptRange)) {
					continue;
				}
				if (!was) {
					// A spot of its own in front of the player, so a flock doesn't crowd one point.
					const float spread = float(actor->GetFormID() % 7) - 3.0f;
					Follow(actor, a_player, spread * 30.0f, 110.0f + std::fabs(spread) * 10.0f);
					logger::info("animals: {} follows the food", actor->GetName());
				}
				now.emplace(actor->GetFormID(), true);
			}
		}
		for (const auto& [id, _] : following) {
			if (!now.contains(id)) {
				if (auto* actor = RE::TESForm::LookupByID<RE::Actor>(id)) {
					LetGo(actor);
				}
			}
		}
		following = std::move(now);
	}
}
