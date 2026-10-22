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
		};
		std::unordered_map<RE::FormID, Lead> leads;
		RE::TESObjectCELL*                   leadCell = nullptr;

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
			} else if (has("dog") || has("husky") || (has("wolf") && !has("werewolf"))) {
				likes = proto::kFoodMeat | proto::kFoodBone;
			} else if (has("mudcrab")) {
				likes = proto::kFoodFish;
			} else if (has("boar") || has("bristleback")) {
				likes = proto::kFoodBeetroot;
			}
			likesCache.emplace(race->GetFormID(), likes);
			logger::info("animals: {} ({}) likes {:02X}", race->GetName() ? race->GetName() : "?", a_actor->GetName(), likes);
			return likes;
		}

		// Walked the Minecraft way: Skyrim's KeepOffsetFromActor on an animal keeps going the way the
		// player went when it was given (it matches the target's own moving, which a player moved by
		// Minecraft hasn't), and stepping it only while it stood still fought Skyrim's own walking (a
		// stutter). So one that should come and is more than a block past its distance is walked
		// there by us alone, as a Minecraft mob is pulled, with Skyrim's AI paused meanwhile (nothing
		// to fight): over Minecraft's blocks (a step up or down) and along Skyrim's land, turning as
		// it goes, till it's there; then it's Skyrim's again.
		// Skyrim's AI paused by us: walked the Minecraft way, a pet sitting, a follower held through its
		// swing (MobFights). With it off Skyrim leaves the body's animation where it was (a walk frozen
		// mid-step), and left on it ran them on the spot (a sitting wolf, or a follower's swing broken
		// off for a run, as its follow package had it go after the player): so it's off, and the body
		// is played on here each frame (PerFrame). Why it's paused, as bits: one can be for more than one.
		enum : std::uint8_t
		{
			kPauseWalk = 1,
			kPauseSit = 2,
			kPauseHold = 4
		};
		std::unordered_map<RE::FormID, std::uint8_t> paused;

		void Pause(RE::Actor* a_actor, std::uint8_t a_why, bool a_on)
		{
			const auto          id = a_actor->GetFormID();
			const auto          it = paused.find(id);
			const std::uint8_t  was = it != paused.end() ? it->second : 0;
			const std::uint8_t  now = a_on ? std::uint8_t(was | a_why) : std::uint8_t(was & ~a_why);
			if (now == was) {
				return;
			}
			if (now) {
				paused[id] = now;
			} else {
				paused.erase(id);
			}
			if (!was) {
				a_actor->EnableAI(false);
			} else if (!now) {
				a_actor->EnableAI(true);
				a_actor->EvaluatePackage(true, false);
			}
		}

		void AnimatePaused(float a_delta)
		{
			for (const auto& [id, why] : paused) {
				auto* actor = RE::TESForm::LookupByID<RE::Actor>(id);
				if (actor && !actor->IsDead() && actor->Get3D()) {
					actor->UpdateAnimation(a_delta);
				}
			}
		}

		// The height of what's under a point (Skyrim's land and objects, Minecraft's blocks' collision), looking from a
		// little over it (a small step up) to this far below; none if nothing is.
		std::optional<float> GroundUnder(RE::Actor* a_actor, const RE::NiPoint3& a_at, float a_below)
		{
			auto* cell = a_actor->GetParentCell();
			auto* world = cell ? cell->GetbhkWorld() : nullptr;
			if (!world) {
				return std::nullopt;
			}
			const float     s = RE::bhkWorld::GetWorldScale();
			const float     over = 0.6f * float(proto::kUnitsPerBlock);
			RE::bhkPickData pick;
			pick.rayInput.from = RE::hkVector4(a_at.x * s, a_at.y * s, (a_at.z + over) * s, 0.0f);
			pick.rayInput.to = RE::hkVector4(a_at.x * s, a_at.y * s, (a_at.z - a_below) * s, 0.0f);
			pick.rayInput.filterInfo.SetCollisionLayer(RE::COL_LAYER::kLOS);
			{
				RE::BSReadLockGuard guard(world->worldLock);
				world->PickObject(pick);
			}
			if (!pick.rayOutput.HasHit()) {
				return std::nullopt;
			}
			return a_at.z + over - (over + a_below) * pick.rayOutput.hitFraction;
		}

		struct Walk
		{
			RE::NiPoint3 at;               // where we last put it
			bool         driving = false;  // walked by us now (its AI paused)
			float        blocked = 0.0f;   // seconds at a wall
			float        rest = 0.0f;      // after a wall: Skyrim's own a while
		};
		std::unordered_map<RE::FormID, Walk>       walks;
		std::unordered_map<RE::FormID, RE::FormID> approaching;  // breeding: to its partner
		constexpr std::uint32_t                    kWalksRecord = 'WALK';

		void Release(RE::Actor* a_actor, Walk& a_walk)
		{
			if (!a_walk.driving) {
				return;
			}
			a_walk.driving = false;
			a_actor->SetGraphVariableFloat("Speed", 0.0f);
			a_actor->SetGraphVariableFloat("SpeedSampled", 0.0f);
			a_actor->NotifyAnimationGraph("moveStop");
			Pause(a_actor, kPauseWalk, false);
		}

		void Steer(RE::Actor* a_actor, const RE::NiPoint3& a_goal, double a_keepBlocks, float a_delta)
		{
			auto&        walk = walks[a_actor->GetFormID()];
			const auto   pos = a_actor->GetPosition();
			const auto   here = SkyToMc(pos), goal = SkyToMc(a_goal);
			const double dx = goal.x - here.x, dz = goal.z - here.z;
			const double distance = std::sqrt(dx * dx + dz * dz);
			if (a_actor->IsInCombat() || !a_actor->Get3D()) {
				Release(a_actor, walk);
				return;
			}
			if (!walk.driving) {
				if ((walk.rest -= a_delta) > 0.0f || distance <= a_keepBlocks + 1.0) {
					return;
				}
				walk.driving = true;
				walk.at = pos;
				walk.blocked = 0.0f;
				Pause(a_actor, kPauseWalk, true);
				a_actor->SetGraphVariableFloat("Direction", 0.0f);
				a_actor->NotifyAnimationGraph("moveStart");  // (its walk or run plays: AnimatePaused)
				static int logged = 0;
				if (logged++ < 20) {
					logger::info("animals: {} walked the Minecraft way ({:.0f} blocks to go)", a_actor->GetName(), distance);
				}
			}
			if (distance <= a_keepBlocks) {
				Release(a_actor, walk);
				return;
			}
			// From where we put it (unless something else moved it: a door, a push).
			const auto   from = pos.GetDistance(walk.at) < 1.5f * float(proto::kUnitsPerBlock) ? SkyToMc(walk.at) : here;
			const double speed = distance > 8.0 ? 5.0 : 3.0;  // blocks a second: hurrying when far behind
			const double step = std::min(speed * a_delta, distance - a_keepBlocks);
			const double nx = from.x + dx / distance * step, nz = from.z + dz / distance * step;
			const int    bx = int(std::floor(nx)), bz = int(std::floor(nz)), by = int(std::floor(from.y + 0.05));
			double       ny = from.y;
			if (NpcBlocks::BlocksBody(bx, by, bz) || NpcBlocks::BlocksBody(bx, by + 1, bz)) {
				if (NpcBlocks::BlocksBody(bx, by + 1, bz) || NpcBlocks::BlocksBody(bx, by + 2, bz)) {
					if ((walk.blocked += a_delta) > 2.0f) {
						Release(a_actor, walk);  // a wall: Skyrim's own way round, a while
						walk.rest = 3.0f;
					}
					return;
				}
				ny = by + 1.0;  // a step up
			} else if (!NpcBlocks::SolidAt(bx, by - 1, bz) && NpcBlocks::SolidAt(bx, by - 2, bz)) {
				ny = by - 1.0;  // a step down
			}
			walk.blocked = 0.0f;
			auto to = McToSky(nx, ny, nz);
			// SkyCraft+ : on whatever is under it (Skyrim's land, a rock, a bridge, Minecraft's blocks), falling to it when it's
			// far below: brought to a player flying in creative, it walked on the air (its AI, and with it its falling, paused).
			if (!NpcBlocks::SolidAt(bx, int(std::floor(ny + 0.05)) - 1, bz)) {
				const float block = float(proto::kUnitsPerBlock);
				auto  above = to;
				float land = 0.0f;
				above.z += 100000.0f;
				if (auto ground = GroundUnder(a_actor, to, 64.0f * block)) {
					to.z = *ground > to.z - 1.5f * block ? *ground : std::max(*ground, walk.at.z - 20.0f * block * a_delta);
				} else if (auto* tes = RE::TES::GetSingleton(); tes && tes->GetLandHeight(above, land) && land > to.z - 1.5f * block && land < to.z + block) {
					to.z = land;  // along Skyrim's land, up and down (not out of a tunnel: the land's far over that)
				}
			}
			a_actor->SetPosition(to, true);
			walk.at = to;
			// Turning as it goes, not at once.
			const float want = std::atan2(float(dx), float(-dz));
			float       turn = want - a_actor->data.angle.z;
			turn = std::remainder(turn, 2.0f * std::numbers::pi_v<float>);
			const float most = 8.0f * a_delta;
			a_actor->SetHeading(a_actor->data.angle.z + std::clamp(turn, -most, most));
			// Its model too: with its AI paused Skyrim leaves that where it stood (only the lead,
			// drawn from where it is, came along; the model caught up when it stopped).
			a_actor->Update3DPosition(true);
			if (auto* root = a_actor->Get3D()) {
				root->local.translate = to;
				RE::NiUpdateData update;
				root->Update(update);
			}
			a_actor->SetGraphVariableFloat("Speed", float(speed) * float(proto::kUnitsPerBlock));  // (walking, or running when far behind)
			a_actor->SetGraphVariableFloat("SpeedSampled", float(speed) * float(proto::kUnitsPerBlock));
		}

		// Done with it: Skyrim's again.
		void Unwalk(RE::Actor* a_actor)
		{
			if (auto it = walks.find(a_actor->GetFormID()); it != walks.end()) {
				Release(a_actor, it->second);
				walks.erase(it);
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

	// Coming to someone: walked there by Steer (Skyrim's KeepOffsetFromActor isn't used: see Walk), the
	// offset unused now. One left from an earlier game is cleared.
	void Follow(RE::Actor* a_actor, RE::Actor* a_target, float, float)
	{
		CallActor(a_actor, "ClearKeepOffsetFromActor");
		static int logged = 0;
		if (logged++ < 20) {
			logger::info("animals: {} comes to {}", a_actor->GetName(), a_target ? a_target->GetName() : "?");
		}
	}

	bool PathTo(RE::Actor* a_actor, RE::TESObjectREFR* a_to)
	{
		const bool sent = CallActor(a_actor, "PathToReference", std::move(a_to), 1.0f);
		a_actor->EvaluatePackage(true, false);
		return sent;
	}

	void HoldStill(RE::Actor* a_actor, bool a_hold)
	{
		CallActor(a_actor, "SetDontMove", std::move(a_hold));
	}

	void LetGo(RE::Actor* a_actor)
	{
		Unwalk(a_actor);
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
		                            : has("mudcrab") ? 10 : has("boar") || has("bristleback") ? 11 : has("wolf") && !has("werewolf") ? 12 : 0;
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

	// SkyCraft+ : fed (as Minecraft's babies are), a baby grows a share of what it has left.
	void OnGrowBaby(RE::FormID a_baby, float a_share)
	{
		auto it = babies.find(a_baby);
		if (it == babies.end()) {
			return;
		}
		it->second = std::min(1.0f, it->second + (1.0f - it->second) * std::clamp(a_share, 0.0f, 1.0f));
		if (auto* baby = RE::TESForm::LookupByID<RE::Actor>(a_baby)) {
			baby->SetScale(0.5f + 0.5f * it->second);
			logger::info("breeding: {} was fed: {:.0f}% grown", baby->GetName(), it->second * 100.0f);
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

	// Animals walked by us have their AI paused: it's back on for the save (the next frame takes them
	// on again), and which they were is kept, so a save made with it off anyway gets it back on load.
	void SaveWalks(SKSE::SerializationInterface* a_intfc)
	{
		std::vector<RE::FormID> driven;
		for (auto& [id, walk] : walks) {
			if (walk.driving) {
				driven.push_back(id);
				if (auto* actor = RE::TESForm::LookupByID<RE::Actor>(id)) {
					Release(actor, walk);
				}
			}
		}
		// (And those paused for anything else: a sitting pet is paused again the next frame.)
		for (const auto& [id, why] : paused) {
			driven.push_back(id);
			if (auto* actor = RE::TESForm::LookupByID<RE::Actor>(id)) {
				actor->EnableAI(true);
			}
		}
		paused.clear();
		if (driven.empty() || !a_intfc->OpenRecord(kWalksRecord, 1)) {
			return;
		}
		const auto count = static_cast<std::uint32_t>(driven.size());
		a_intfc->WriteRecordData(count);
		for (auto id : driven) {
			a_intfc->WriteRecordData(id);
		}
	}

	// Another game: none of these are ours now (its own are put right by LoadWalks).
	void ClearWalks()
	{
		walks.clear();
		approaching.clear();
		paused.clear();
	}

	void HoldForAttack(RE::Actor* a_actor, bool a_hold)
	{
		HoldStill(a_actor, a_hold);
		Pause(a_actor, kPauseHold, a_hold);
	}

	void LoadWalks(SKSE::SerializationInterface* a_intfc)
	{
		std::uint32_t count = 0;
		a_intfc->ReadRecordData(count);
		for (std::uint32_t i = 0; i < count && i < 4096; ++i) {
			RE::FormID id = 0, resolved = 0;
			if (a_intfc->ReadRecordData(id) && a_intfc->ResolveFormID(id, resolved)) {
				if (auto* actor = RE::TESForm::LookupByID<RE::Actor>(resolved)) {
					actor->EnableAI(true);
				}
			}
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
				if (actor) {
					Unwalk(actor);
				}
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
			} else if (lead.mode == 2 && lead.marker && actor->GetParentCell() == lead.marker->GetParentCell()) {
				Steer(actor, lead.post, 3.0, a_delta);  // (free within four blocks of its post)
			} else {
				Unwalk(actor);  // (somewhere else: not held there with its AI paused)
			}
			++it;
		}
	}

	// ---- SkyCraft+ : tamed with bones, the Minecraft way --------------------------------------------
	// A dog or wolf given bones (Minecraft decides, a chance a bone as with its own wolves: kEvTame)
	// is the player's: in the player's faction, an ally and a teammate (it fights at their side,
	// Minecraft's monsters too), no longer aggressive to anyone else. It follows as a Minecraft wolf
	// does: walked after the player when more than four blocks behind, brought to them when far
	// behind or through a door. Told to sit, it stays where it is till told to stand.
	namespace
	{
		struct Pet
		{
			bool sitting = false;
			bool friends = false;  // made friends this session (a loaded game's: again, once it's loaded)
			float poseTimer = 0.0f;  // seconds to the next look at its sitting pose
		};

		// Sits (a dog's sitting idle, DogBehavior's idleSitStart; a wolf has none and just stands
		// still), or gets up again. True if its body took it.
		bool SitPose(RE::Actor* a_actor, bool a_sit)
		{
			if (!a_sit) {
				return a_actor->NotifyAnimationGraph("Idle_Out") || a_actor->NotifyAnimationGraph("IdleStop");
			}
			bool sat = false;
			if (a_actor->GetGraphVariableBool("isIdleSitting", sat) && sat) {
				return true;
			}
			return a_actor->NotifyAnimationGraph("idleSitStart");
		}

		// A pet never fights the player or their side: the fight it was in when tamed (a spawned wolf
		// goes for the player at once) carried on, and as the factions were changed after it (Papyrus,
		// a frame or two later) it went back to it; and sitting, it ran on the spot at the player.
		void KeepPeace(RE::PlayerCharacter* a_player, RE::Actor* a_actor)
		{
			// (SkyCraft+ : angry with the player since it was spawned against them, it kept the player from waiting.)
			if (a_actor->IsAngryWithPlayer()) {
				a_actor->GetActorRuntimeData().boolFlags.reset(RE::Actor::BOOL_FLAGS::kAngryWithPlayer);
				logger::info("pets: {} no longer angry with the player", a_actor->GetName());
			}
			if (static std::uint64_t told = 0; GetTickCount64() - told > 15000 && a_actor->GetPosition().GetDistance(a_player->GetPosition()) < 1500.0f) {
				told = GetTickCount64();
				auto target = a_actor->GetActorRuntimeData().currentCombatTarget.get();
				logger::info("pets: {} hostile to the player {}, in combat {}{}{}, player in combat {}", a_actor->GetName(), a_actor->IsHostileToActor(a_player),
					a_actor->IsInCombat(), target ? " with " : "", target ? target->GetName() : "", a_player->IsInCombat());
			}
			if (!a_actor->IsInCombat()) {
				return;
			}
			auto target = a_actor->GetActorRuntimeData().currentCombatTarget.get();
			// (SkyCraft+ : nor anyone who isn't the player's enemy: a guard it went for would keep the player from waiting.)
			if (!target || target.get() == a_player || target->IsPlayerTeammate() || target->IsCommandedActor() || !target->IsHostileToActor(a_player)) {
				a_actor->StopCombat();
				a_actor->EvaluatePackage(true, false);
			}
		}
		std::unordered_map<RE::FormID, Pet> pets;
		RE::TESObjectCELL*                  petCell = nullptr;
		constexpr std::uint32_t             kPetsRecord = 'TAME';
		float                               peaceTimer = 0.0f;

		// SkyCraft+ : nobody who isn't the player's enemy fights a pet. Skyrim's people still went for a tamed wolf on
		// sight, and a fight near the player kept them from waiting ("enemies nearby").
		void LeavePetsBe(RE::PlayerCharacter* a_player, float a_delta)
		{
			if (pets.empty() || (peaceTimer -= a_delta) > 0.0f) {
				return;
			}
			peaceTimer = 0.5f;
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				return;
			}
			for (auto& handle : lists->highActorHandles) {
				auto actor = handle.get();
				if (!actor || actor->IsDead() || !actor->IsInCombat() || pets.contains(actor->GetFormID())) {
					continue;
				}
				auto target = actor->GetActorRuntimeData().currentCombatTarget.get();
				if (target && pets.contains(target->GetFormID()) && !actor->IsHostileToActor(a_player)) {
					actor->StopCombat();
					actor->EvaluatePackage(true, false);
					static int logged = 0;
					if (logged++ < 20) {
						logger::info("pets: {} let {} be (not the player's enemy)", actor->GetName(), target->GetName());
					}
				}
			}
		}

		void MakeFriends(RE::PlayerCharacter* a_player, RE::Actor* a_actor)
		{
			a_actor->StopCombat();
			CallActor(a_actor, "RemoveFromAllFactions");
			if (auto* faction = RE::TESForm::LookupByID<RE::TESFaction>(0xDB1)) {  // PlayerFaction
				CallActor(a_actor, "AddToFaction", std::move(faction));
			}
			if (auto* faction = RE::TESForm::LookupByID<RE::TESFaction>(0x5C84E)) {  // CurrentFollowerFaction (as Meeko: Skyrim's people leave it be)
				CallActor(a_actor, "AddToFaction", std::move(faction));
			}
			RE::Actor* player = a_player;
			CallActor(a_actor, "SetRelationshipRank", std::move(player), 3);
			CallActor(a_actor, "SetPlayerTeammate", true, false);
			CallActor(a_actor, "IgnoreFriendlyHits", true);
			CallActor(a_actor, "StopCombatAlarm");  // SkyCraft+ : (spawned against the player: its alarm too)
			// (SkyCraft+ : aggressive: it goes for the player's enemies on sight, as a follower; it has no quarrel with the player now.)
			a_actor->AsActorValueOwner()->SetActorValue(RE::ActorValue::kAggression, 1.0f);
			// SkyCraft+ : as tough as a tamed Minecraft wolf (40 health), in Skyrim's health for a Minecraft hit on it (Combat's scale).
			auto*       values = a_actor->AsActorValueOwner();
			const float tough = 40.0f * (State().mcDamageBase + State().mcDamagePerLevel * static_cast<float>(a_actor->GetLevel()));
			if (values->GetBaseActorValue(RE::ActorValue::kHealth) < tough) {
				values->SetBaseActorValue(RE::ActorValue::kHealth, tough);
				values->RestoreActorValue(RE::ActorValue::kHealth, tough);
				logger::info("pets: {} as tough as a Minecraft wolf ({:.0f} health)", a_actor->GetName(), tough);
			}
			a_actor->EvaluatePackage(true, false);
		}

		void TickPets(RE::PlayerCharacter* a_player, float a_delta)
		{
			auto* cell = a_player->GetParentCell();
			const bool moved = cell != petCell;
			petCell = cell;
			const float block = float(proto::kUnitsPerBlock);
			for (auto it = pets.begin(); it != pets.end();) {
				auto* actor = RE::TESForm::LookupByID<RE::Actor>(it->first);
				if (!actor) {
					// (One placed in the game, as a spawn egg's, is only there while its place is loaded: left
					// behind through a door it's gone until the player comes back. Still theirs then.)
					it->second.friends = false;
					++it;
					continue;
				}
				if (actor->IsDead()) {
					Unwalk(actor);
					Pause(actor, kPauseSit, false);
					logger::info("pets: {} died", actor->GetName());
					it = pets.erase(it);
					continue;
				}
				auto& pet = it->second;
				if (!pet.friends && actor->Get3D()) {
					pet.friends = true;
					MakeFriends(a_player, actor);
					HoldStill(actor, pet.sitting);
				}
				KeepPeace(a_player, actor);
				if (pet.sitting && actor->Get3D()) {
					Pause(actor, kPauseSit, true);  // (its sitting not fought by a run after the player)
					if ((pet.poseTimer -= a_delta) <= 0.0f) {
						pet.poseTimer = 1.5f;
						SitPose(actor, true);
					}
				}
				if (pet.sitting || leads.contains(it->first) || State().parked) {
					++it;
					continue;
				}
				auto* actorCell = actor->GetParentCell();
				const bool otherPlace = cell && actorCell != cell && (cell->IsInteriorCell() || !actorCell || actorCell->IsInteriorCell() ||
				                                                     actor->GetWorldspace() != a_player->GetWorldspace());
				const float distance = actor->GetPosition().GetDistance(a_player->GetPosition());
				if ((moved && otherPlace && distance > 6.0f * block) || (!otherPlace && distance > 20.0f * block && !actor->IsInCombat())) {
					Unwalk(actor);
					actor->MoveTo(a_player);
					static int logged = 0;
					if (logged++ < 20) {
						logger::info("pets: {} came to the player ({:.0f} blocks behind{})", actor->GetName(), distance / block, otherPlace ? ", another place" : "");
					}
				} else if (!otherPlace && !actor->IsInCombat()) {  // SkyCraft+ : (fighting: its own way, after the player's enemies)
					Steer(actor, a_player->GetPosition(), 3.0, a_delta);
				} else {
					Unwalk(actor);
				}
				++it;
			}
		}
	}

	namespace
	{
		// SkyCraft+ : a pet comes through a load door with the player: sent to the door's other side as
		// the player goes (once they're through, one placed in the game, as a spawn egg's, is gone from
		// memory with the place it was in, and couldn't be brought any more).
		class DoorSink final : public RE::BSTEventSink<RE::TESActivateEvent>
		{
		public:
			static DoorSink* Get()
			{
				static DoorSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESActivateEvent* a_event, RE::BSTEventSource<RE::TESActivateEvent>*) override
			{
				auto* door = a_event ? a_event->objectActivated.get() : nullptr;
				auto* by = a_event ? a_event->actionRef.get() : nullptr;
				auto* teleport = door && by && by->IsPlayerRef() ? door->extraList.GetByType<RE::ExtraTeleport>() : nullptr;
				auto  other = teleport && teleport->teleportData ? teleport->teleportData->linkedDoor.get() : nullptr;
				if (!other) {
					return RE::BSEventNotifyControl::kContinue;
				}
				for (const auto& [id, pet] : pets) {
					auto* actor = RE::TESForm::LookupByID<RE::Actor>(id);
					if (!actor || actor->IsDead() || pet.sitting || leads.contains(id) || !actor->Get3D() ||
						actor->GetPosition().GetDistance(by->GetPosition()) > 24.0f * float(proto::kUnitsPerBlock)) {
						continue;
					}
					Unwalk(actor);
					actor->MoveTo(other.get());
					logger::info("pets: {} comes through the door with the player", actor->GetName());
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};
	}

	void OnTame(RE::PlayerCharacter* a_player, RE::FormID a_actor, std::uint32_t a_mode)
	{
		auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_actor);
		if (!actor || actor->IsDead() || actor->IsPlayerRef()) {
			return;
		}
		if (a_mode == 1) {
			auto& pet = pets[a_actor];
			pet.friends = true;
			pet.sitting = false;
			following.erase(a_actor);
			LetGo(actor);
			MakeFriends(a_player, actor);
			HoldStill(actor, false);
			logger::info("pets: {} ({:08X}) tamed", actor->GetName(), a_actor);
			return;
		}
		auto it = pets.find(a_actor);
		if (it == pets.end()) {
			return;
		}
		it->second.sitting = a_mode == 2;
		it->second.poseTimer = 0.0f;
		Unwalk(actor);
		HoldStill(actor, it->second.sitting);
		if (!it->second.sitting) {
			Pause(actor, kPauseSit, false);
		}
		KeepPeace(a_player, actor);
		const bool posed = SitPose(actor, it->second.sitting);
		logger::info("pets: {} {}{}", actor->GetName(), it->second.sitting ? "sits" : "stands", posed ? "" : " (no sitting pose of its own)");
	}

	bool IsTamed(RE::FormID a_actor)
	{
		return pets.contains(a_actor);
	}

	bool IsSitting(RE::FormID a_actor)
	{
		auto it = pets.find(a_actor);
		return it != pets.end() && it->second.sitting;
	}

	void SavePets(SKSE::SerializationInterface* a_intfc)
	{
		if (pets.empty() || !a_intfc->OpenRecord(kPetsRecord, 1)) {
			return;
		}
		const auto count = static_cast<std::uint32_t>(pets.size());
		a_intfc->WriteRecordData(count);
		for (const auto& [id, pet] : pets) {
			const std::uint8_t sitting = pet.sitting ? 1 : 0;
			a_intfc->WriteRecordData(id);
			a_intfc->WriteRecordData(sitting);
		}
	}

	void LoadPets(SKSE::SerializationInterface* a_intfc)
	{
		std::uint32_t count = 0;
		a_intfc->ReadRecordData(count);
		for (std::uint32_t i = 0; i < count && i < 4096; ++i) {
			RE::FormID   id = 0, resolved = 0;
			std::uint8_t sitting = 0;
			if (a_intfc->ReadRecordData(id) && a_intfc->ReadRecordData(sitting) && a_intfc->ResolveFormID(id, resolved)) {
				pets[resolved] = Pet{ sitting != 0, false };
			}
		}
		logger::info("pets: {} tamed animal(s)", pets.size());
	}

	void ClearPets()
	{
		pets.clear();
	}

	void PerFrame(RE::PlayerCharacter* a_player, std::uint32_t a_heldFood, bool a_driving, float a_delta)
	{
		TickLeads(a_player, a_delta);
		static bool watchingDoors = false;  // SkyCraft+ : pets through load doors
		if (!watchingDoors && !pets.empty()) {
			if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
				events->AddEventSink<RE::TESActivateEvent>(DoorSink::Get());
				watchingDoors = true;
			}
		}
		TickPets(a_player, a_delta);
		LeavePetsBe(a_player, a_delta);
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
				if (actor) {
					Unwalk(actor);
				}
				it = approaching.erase(it);
				continue;
			}
			Steer(actor, to->GetPosition(), 1.0, a_delta);
			++it;
		}
		AnimatePaused(a_delta);
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
				if (!actor || actor->IsDead() || actor->IsPlayerRef() || actor->IsInCombat() || leads.contains(actor->GetFormID()) || pets.contains(actor->GetFormID()) ||
					!(Likes(actor) & food)) {
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
