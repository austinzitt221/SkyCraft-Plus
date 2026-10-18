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

		std::unordered_map<RE::FormID, std::uint32_t> speciesCache;  // by race
		// Babies bred the Minecraft way: how grown (0 half size .. 1 full), growing while near.
		std::unordered_map<RE::FormID, float> babies;
		float                                 babyTimer = 0.0f;
		constexpr float                       kGrowSeconds = 1200.0f;  // as Minecraft's: twenty minutes
		constexpr std::uint32_t               kBabiesRecord = 'BABY';

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

		// Walked the Minecraft way: Skyrim takes KeepOffsetFromActor on an animal and does nothing with
		// it (its own packages win), so one that stands still while it should come is stepped there
		// as a Minecraft mob is pulled, over Minecraft's blocks (a one-block step up) and up Skyrim's
		// slopes, until it's within reach or walks on its own again.
		struct Walk
		{
			RE::NiPoint3 last;
			float        still = 0.0f;
		};
		std::unordered_map<RE::FormID, Walk>       walks;
		std::unordered_map<RE::FormID, RE::FormID> approaching;  // breeding: to its partner

		void Steer(RE::Actor* a_actor, const RE::NiPoint3& a_goal, double a_keepBlocks, float a_delta)
		{
			auto&       walk = walks[a_actor->GetFormID()];
			const auto  pos = a_actor->GetPosition();
			const float moved = walk.last.GetDistance(pos);
			walk.last = pos;
			walk.still = moved < 0.5f * float(proto::kUnitsPerBlock) * a_delta ? walk.still + a_delta : 0.0f;  // under half a block a second
			const auto   here = SkyToMc(pos), goal = SkyToMc(a_goal);
			const double dx = goal.x - here.x, dz = goal.z - here.z;
			const double distance = std::sqrt(dx * dx + dz * dz);
			if (distance <= a_keepBlocks || walk.still < 0.6f || distance < 0.01 || a_actor->IsInCombat()) {
				return;
			}
			const double speed = distance > 8.0 ? 4.5 : 2.5;  // blocks a second: hurrying when far behind
			const double step = std::min(speed * a_delta, distance - a_keepBlocks);
			const double nx = here.x + dx / distance * step, nz = here.z + dz / distance * step;
			const int    bx = int(std::floor(nx)), bz = int(std::floor(nz)), by = int(std::floor(here.y + 0.05));
			double       ny = here.y;
			if (NpcBlocks::BlocksBody(bx, by, bz) || NpcBlocks::BlocksBody(bx, by + 1, bz)) {
				if (NpcBlocks::BlocksBody(bx, by + 1, bz) || NpcBlocks::BlocksBody(bx, by + 2, bz)) {
					return;  // a wall: it waits
				}
				ny = by + 1.0;
			}
			auto to = McToSky(nx, ny, nz);
			if (auto* tes = RE::TES::GetSingleton()) {
				auto  above = to;
				float land = 0.0f;
				above.z += 100000.0f;
				if (tes->GetLandHeight(above, land) && land > to.z && land - to.z < float(proto::kUnitsPerBlock)) {
					to.z = land;  // up Skyrim's slope (not out of a tunnel: the land's far above that)
				}
			}
			a_actor->SetPosition(to, true);
			walk.last = a_actor->GetPosition();  // our step isn't its own walking
			a_actor->data.angle.z = std::atan2(float(dx), float(-dz));
			static int logged = 0;
			if (walk.still < 0.6f + a_delta * 1.5f && logged++ < 20) {
				logger::info("animals: {} stood still: walked the Minecraft way ({:.0f} blocks to go)", a_actor->GetName(), distance);
			}
		}

		// Whether Skyrim ran a Papyrus call (CallActor), for the log.
		class Ran final : public RE::BSScript::IStackCallbackFunctor
		{
		public:
			explicit Ran(std::string a_what) :
				what(std::move(a_what)) {}
			void operator()(RE::BSScript::Variable) override { logger::info("animals: {} ran", what); }
			void SetObject(const RE::BSTSmartPointer<RE::BSScript::Object>&) override {}

		private:
			std::string what;
		};
	}

	// A Papyrus Actor method on this actor (Skyrim's own behaviour, as scripts use it). An actor with
	// no script of its own (most animals) gets a plain Actor object bound to it first: without one the
	// call went nowhere.
	template <class... Args>
	bool CallActor(RE::Actor* a_actor, const char* a_function, Args&&... a_args)
	{
		auto* vm = RE::BSScript::Internal::VirtualMachine::GetSingleton();
		auto* policy = vm ? vm->GetObjectHandlePolicy() : nullptr;
		if (!policy) {
			return false;
		}
		const auto handle = policy->GetHandleForObject(static_cast<RE::VMTypeID>(RE::Actor::FORMTYPE), a_actor);
		if (handle == policy->EmptyHandle()) {
			return false;
		}
		RE::BSTSmartPointer<RE::BSScript::Object> object;
		if (!vm->FindBoundObject(handle, "Actor", object) || !object) {
			auto* bind = vm->GetObjectBindPolicy();
			if (!bind || !vm->CreateObject("Actor", object) || !object) {
				return false;
			}
			bind->BindObject(object, handle);
		}
		RE::BSTSmartPointer<RE::BSScript::IStackCallbackFunctor> callback;
		static int logged = 0;
		if (logged++ < 10) {
			callback.reset(new Ran(std::string(a_function) + " on " + a_actor->GetName()));
		}
		auto* arguments = RE::MakeFunctionArguments(std::forward<Args>(a_args)...);
		return vm->DispatchMethodCall1(object, a_function, arguments, callback);
	}

	void Follow(RE::Actor* a_actor, RE::Actor* a_target, float a_offsetX, float a_offsetY)
	{
		const bool sent = CallActor(a_actor, "KeepOffsetFromActor", std::move(a_target), std::move(a_offsetX), std::move(a_offsetY), 0.0f, 0.0f, 0.0f, 0.0f, 300.0f, 60.0f);
		a_actor->EvaluatePackage(true, false);  // (at once, not at its next AI update)
		static int logged = 0;
		if (logged++ < 20) {
			logger::info("animals: {} keeps by {}: {}", a_actor->GetName(), a_target ? a_target->GetName() : "?", sent ? "sent" : "couldn't send");
		}
	}

	void LetGo(RE::Actor* a_actor)
	{
		walks.erase(a_actor->GetFormID());
		approaching.erase(a_actor->GetFormID());
		CallActor(a_actor, "ClearKeepOffsetFromActor");
		a_actor->EvaluatePackage(true, false);
	}

	std::uint32_t Species(RE::Actor* a_actor)
	{
		auto* race = a_actor ? a_actor->GetRace() : nullptr;
		if (!race) {
			return 0;
		}
		if (auto it = speciesCache.find(race->GetFormID()); it != speciesCache.end()) {
			return it->second;
		}
		const std::string what = Lower(race->GetName()) + " " + Lower(a_actor->GetName());
		auto has = [&](std::string_view a_part) { return what.find(a_part) != std::string::npos; };
		const std::uint32_t species = has("chicken") || has(" hen") ? 1 : has("cow") || has("bull") ? 2 : has("goat") ? 3 : has("deer") || has("stag") ? 4
		                            : has("elk") ? 5 : has("hare") || has("rabbit") ? 6 : has("horse") ? 7 : has("fox") ? 8 : has("dog") || has("husky") ? 9
		                            : has("mudcrab") ? 10 : has("boar") || has("bristleback") ? 11 : 0;
		speciesCache.emplace(race->GetFormID(), species);
		return species;
	}

	bool IsBaby(RE::FormID a_actor)
	{
		return babies.contains(a_actor);
	}

	void OnApproach(RE::FormID a_actor, RE::FormID a_to)
	{
		auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_actor);
		auto* to = a_to ? RE::TESForm::LookupByID<RE::Actor>(a_to) : nullptr;
		if (!actor || leads.contains(a_actor)) {
			return;
		}
		if (to) {
			Follow(actor, to, 0.0f, 40.0f);
			approaching[a_actor] = a_to;
		} else {
			LetGo(actor);
		}
	}

	void OnBreed(RE::FormID a_parent)
	{
		auto* parent = RE::TESForm::LookupByID<RE::Actor>(a_parent);
		auto* base = parent ? parent->GetActorBase() : nullptr;
		if (!base) {
			return;
		}
		auto baby = parent->PlaceObjectAtMe(base, true);  // (persists: saved with the game)
		if (!baby) {
			return;
		}
		baby->SetScale(0.5f);  // (the whole model, in proportion)
		babies[baby->GetFormID()] = 0.0f;
		logger::info("breeding: a baby {} ({:08X})", parent->GetName(), baby->GetFormID());
	}

	// Babies grow while loaded near the player, from half size to full in twenty minutes.
	void TickBabies(float a_delta)
	{
		if (babies.empty() || (babyTimer -= a_delta) > 0.0f) {
			return;
		}
		const float step = 2.0f - babyTimer;
		babyTimer = 2.0f;
		for (auto it = babies.begin(); it != babies.end();) {
			auto* baby = RE::TESForm::LookupByID<RE::Actor>(it->first);
			if (!baby || baby->IsDead()) {
				it = babies.erase(it);
				continue;
			}
			if (baby->Get3D()) {
				it->second = std::min(1.0f, it->second + step / kGrowSeconds);
				baby->SetScale(0.5f + 0.5f * it->second);
			}
			if (it->second >= 1.0f) {
				logger::info("breeding: {} grew up", baby->GetName());
				it = babies.erase(it);
			} else {
				++it;
			}
		}
	}

	void SaveBabies(SKSE::SerializationInterface* a_intfc)
	{
		if (babies.empty() || !a_intfc->OpenRecord(kBabiesRecord, 1)) {
			return;
		}
		const auto count = static_cast<std::uint32_t>(babies.size());
		a_intfc->WriteRecordData(count);
		for (const auto& [id, grown] : babies) {
			a_intfc->WriteRecordData(id);
			a_intfc->WriteRecordData(grown);
		}
	}

	void LoadBabies(SKSE::SerializationInterface* a_intfc)
	{
		std::uint32_t count = 0;
		a_intfc->ReadRecordData(count);
		for (std::uint32_t i = 0; i < count && i < 4096; ++i) {
			RE::FormID id = 0, resolved = 0;
			float grown = 0.0f;
			if (a_intfc->ReadRecordData(id) && a_intfc->ReadRecordData(grown) && a_intfc->ResolveFormID(id, resolved)) {
				babies[resolved] = grown;
			}
		}
		logger::info("breeding: {} baby animal(s) still growing", babies.size());
	}

	void ClearBabies()
	{
		babies.clear();
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
			auto* actorCell = actor->GetParentCell();
			const bool otherPlace = cell && actorCell != cell && (cell->IsInteriorCell() || !actorCell || actorCell->IsInteriorCell() ||
			                                                     actor->GetWorldspace() != a_player->GetWorldspace());
			if (lead.mode == 1 && moved && otherPlace && actor->GetPosition().GetDistance(a_player->GetPosition()) > 30.0f * float(proto::kUnitsPerBlock)) {
				actor->MoveTo(a_player);
				Follow(actor, a_player, 0.0f, -120.0f);
				logger::info("leads: {} came through the door on the lead", actor->GetName());
			} else if (lead.mode == 1 && !otherPlace) {
				Steer(actor, a_player->GetPosition(), 3.0, a_delta);
			} else if (lead.mode == 2 && lead.marker && actor->GetParentCell() == lead.marker->GetParentCell() && actor->GetPosition().GetDistance(lead.post) > kPostSlack) {
				Steer(actor, lead.post, 2.0, a_delta);
			}
			if (lead.mode == 2 && lead.marker && (lead.pullTimer -= a_delta) <= 0.0f) {
				lead.pullTimer = 3.0f;
				if (actor->GetParentCell() == lead.marker->GetParentCell() && actor->GetPosition().GetDistance(lead.post) > kPostSlack) {
					RE::TESObjectREFR* post = lead.marker.get();
					CallActor(actor, "PathToReference", std::move(post), 0.5f);
					actor->EvaluatePackage(true, false);
				}
			}
			++it;
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, std::uint32_t a_heldFood, bool a_driving, float a_delta)
	{
		TickLeads(a_player, a_delta);
		TickBabies(a_delta);
		for (const auto& [id, _] : following) {
			if (auto* actor = RE::TESForm::LookupByID<RE::Actor>(id)) {
				Steer(actor, a_player->GetPosition(), 2.0, a_delta);
			}
		}
		for (auto it = approaching.begin(); it != approaching.end();) {
			auto* actor = RE::TESForm::LookupByID<RE::Actor>(it->first);
			auto* to = RE::TESForm::LookupByID<RE::Actor>(it->second);
			if (!actor || !to || actor->IsDead() || to->IsDead() || actor->GetPosition().GetDistance(to->GetPosition()) > 32.0f * float(proto::kUnitsPerBlock)) {
				it = approaching.erase(it);
				continue;
			}
			Steer(actor, to->GetPosition(), 1.0, a_delta);
			++it;
		}
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
