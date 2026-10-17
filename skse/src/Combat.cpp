#include "Game.h"
#include "Dig.h"

namespace skycraft
{
	namespace
	{
		constexpr float kActorRange = 80.0f * static_cast<float>(proto::kUnitsPerBlock);  // stand-ins exist this far out
		constexpr float kPi = 3.14159265f;
		constexpr float kHitMemorySeconds = 0.35f;   // how long a hit event can explain a health drop
		constexpr float kDotFlushSeconds = 0.5f;     // magic/other damage is batched (Minecraft i-frames)

		// ---- what hit the player (main thread) -------------------------------------------------
		struct RecentHit
		{
			RE::FormID   attacker{ 0 };
			proto::HurtKind kind{ proto::kHurtMelee };
			std::uint32_t flags{ 0 };
			float        age{ 99.0f };
		};
		RecentHit lastHit;               // from TESHitEvent (kind, power attack, ...)
		RE::FormID lastDamager{ 0 };     // from HandleHealthDamage
		float      lastDamagerAge{ 99.0f };

		struct PendingHurt
		{
			RE::FormID      attacker{ 0 };
			proto::HurtKind kind{ proto::kHurtOther };
			std::uint32_t   flags{ 0 };
			float           damage{ 0.0f };
			float           age{ 0.0f };
		};
		PendingHurt dot;  // accumulated magic / unattributed damage

		bool  essentialSet = false;
		// SkyCraft+ : healing potions. Seconds left in which a health gain counts as a heal.
		float healWindow = 0.0f;
		bool  healthPrimed = false;
		float diagTimer = 5.0f;
		bool  engaged = false;
		float engagedTimer = 0.0f;

		bool IsHeal(const RE::EffectSetting* a_base)
		{
			using Arch = RE::EffectArchetypes::ArchetypeID;
			return a_base && a_base->data.primaryAV == RE::ActorValue::kHealth && !a_base->IsDetrimental() &&
			       (a_base->HasArchetype(Arch::kValueModifier) || a_base->HasArchetype(Arch::kPeakValueModifier) || a_base->HasArchetype(Arch::kDualValueModifier));
		}

		// SkyCraft+ : healing potions. A potion's heal is instant: its effect was gone before the
		// health it gave was seen, so it was taken for Skyrim's own regeneration and Minecraft's hearts
		// stayed where they were. A healing effect landing on the player opens a second for it.
		class HealSink final : public RE::BSTEventSink<RE::TESMagicEffectApplyEvent>
		{
		public:
			static HealSink* Get()
			{
				static HealSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESMagicEffectApplyEvent* a_event, RE::BSTEventSource<RE::TESMagicEffectApplyEvent>*) override
			{
				auto* player = RE::PlayerCharacter::GetSingleton();
				if (a_event && player && a_event->target.get() == player && IsHeal(RE::TESForm::LookupByID<RE::EffectSetting>(a_event->magicEffect))) {
					healWindow = 1.0f;
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// SkyCraft+ : Skyrim food and stamina potions feed Minecraft's hunger. Eaten or drunk anywhere
		// (Skyrim's menus, the panels, a hotbar shortcut): food by how much it restores, a stamina
		// potion by its stamina (a Minor one 2 points, Ultimate the whole bar).
		class FedSink final : public RE::BSTEventSink<RE::TESEquipEvent>
		{
		public:
			static FedSink* Get()
			{
				static FedSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESEquipEvent* a_event, RE::BSTEventSource<RE::TESEquipEvent>*) override
			{
				if (!a_event || !a_event->equipped || !a_event->actor || !a_event->actor->IsPlayerRef() || !State().skyrimFoodFeeds) {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto* item = RE::TESForm::LookupByID<RE::AlchemyItem>(a_event->baseObject);
				if (!item || item->IsPoison()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				float stamina = 0.0f, restores = 0.0f;
				for (auto* effect : item->effects) {
					auto* base = effect ? effect->baseEffect : nullptr;
					if (!base || base->IsDetrimental()) {
						continue;
					}
					const float amount = effect->GetMagnitude() * static_cast<float>(std::max<std::uint32_t>(1, effect->GetDuration()));
					if (base->data.primaryAV == RE::ActorValue::kStamina && base->HasArchetype(RE::EffectArchetypes::ArchetypeID::kValueModifier)) {  // (restore, not fortify)
						stamina += amount;
					}
					if (base->data.primaryAV == RE::ActorValue::kStamina || base->data.primaryAV == RE::ActorValue::kHealth) {
						restores += amount;
					}
				}
				int points = 0;
				if (item->IsFood()) {
					points = std::clamp(2 + static_cast<int>(std::lround(restores / 10.0f)), 2, 10);
				} else if (stamina > 0.0f) {
					points = std::clamp(static_cast<int>(std::lround(stamina / 12.5f)), 1, 20);
				}
				if (points > 0) {
					State().fedPoints += static_cast<std::uint32_t>(points);
					logger::info("hunger: {} gives Minecraft {} hunger points", item->GetName(), points);
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		class HitSink final : public RE::BSTEventSink<RE::TESHitEvent>
		{
		public:
			static HitSink* Get()
			{
				static HitSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESHitEvent* a_event, RE::BSTEventSource<RE::TESHitEvent>*) override
			{
				auto* player = RE::PlayerCharacter::GetSingleton();
				if (!a_event || !player || a_event->target.get() != player || !State().puppeting) {
					return RE::BSEventNotifyControl::kContinue;
				}
				RecentHit hit;
				hit.attacker = a_event->cause ? a_event->cause->GetFormID() : 0;
				hit.age = 0.0f;
				if (a_event->projectile != 0) {
					hit.kind = proto::kHurtProjectile;
				} else if (auto* source = RE::TESForm::LookupByID(a_event->source)) {
					switch (source->GetFormType()) {
					case RE::FormType::Spell:
					case RE::FormType::Enchantment:
					case RE::FormType::Scroll:
					case RE::FormType::Ingredient:
					case RE::FormType::AlchemyItem:
						hit.kind = proto::kHurtMagic;
						break;
					default:
						hit.kind = proto::kHurtMelee;
						break;
					}
				}
				if (a_event->flags.any(RE::TESHitEvent::Flag::kPowerAttack)) {
					hit.flags |= proto::kHurtPowerAttack;
				}
				if (a_event->flags.any(RE::TESHitEvent::Flag::kHitBlocked)) {
					hit.flags |= proto::kHurtBlockedInSkyrim;
				}
				lastHit = hit;
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// Skyrim's damage path calls this on the victim with the attacker; we only need "who".
		struct HandleHealthDamageHook
		{
			static void thunk(RE::Actor* a_this, RE::Actor* a_attacker, float a_damage)
			{
				func(a_this, a_attacker, a_damage);
				if (a_attacker && a_attacker != a_this) {
					lastDamager = a_attacker->GetFormID();
					lastDamagerAge = 0.0f;
				}
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// SkyCraft+ : Skyrim can't kill the player while Minecraft decides. Essential didn't stop one
		// big hit (a Dremora's fireball, 120 at level 1: dead, and the last save loaded): health
		// damage that would take it under 1 is cut to 1, and what was cut goes to Minecraft with the
		// rest (BridgePlayerDamage). (ActorValueOwner::ModActorValue, the player's ActorValueOwner vtable.)
		float cutDamage = 0.0f;

		struct ModActorValueHook
		{
			static void thunk(RE::ActorValueOwner* a_this, RE::ACTOR_VALUE_MODIFIER a_modifier, RE::ActorValue a_value, float a_amount)
			{
				if (essentialSet && a_value == RE::ActorValue::kHealth && a_modifier == RE::ACTOR_VALUE_MODIFIER::kDamage && a_amount < 0.0f) {
					const float cur = a_this->GetActorValue(RE::ActorValue::kHealth);
					if (cur + a_amount < 1.0f) {
						const float allowed = std::min(0.0f, 1.0f - cur);
						cutDamage += allowed - a_amount;
						a_amount = allowed;
					}
				}
				func(a_this, a_modifier, a_value, a_amount);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		void SendHurt(proto::HurtKind a_kind, float a_damage, RE::FormID a_attacker, std::uint32_t a_flags)
		{
			if (a_damage <= 0.01f) {
				return;
			}
			// SkyCraft+ : one Skyrim hit takes at most this much of Minecraft's health (5 Skyrim damage is
			// 1 Minecraft): a Dremora's fireball (120) was 24, dead from full in one hit at level 1.
			if (const float cap = State().maxHitHealth; cap > 0.0f && a_damage > cap * 5.0f) {
				logger::info("player hit for {:.1f} Skyrim damage: capped at {:.1f} ({} Minecraft health)", a_damage, cap * 5.0f, cap);
				a_damage = cap * 5.0f;
			}
			Link::Get().PushInput(proto::kInHurt, static_cast<std::uint16_t>(a_kind), static_cast<std::int32_t>(a_damage * 100.0f),
				static_cast<std::int32_t>(a_attacker), static_cast<std::int32_t>(a_flags));
			logger::info("player hit by {:08X}: {:.1f} Skyrim damage ({}) -> {:.1f} Minecraft", a_attacker, a_damage,
				a_kind == proto::kHurtMelee ? "melee" : a_kind == proto::kHurtProjectile ? "projectile" : a_kind == proto::kHurtMagic ? "magic" : "other",
				a_damage / 5.0f);
		}

		void SetEssential(RE::PlayerCharacter* a_player, bool a_on)
		{
			if (a_on == essentialSet) {
				return;
			}
			auto& flags = a_player->GetActorRuntimeData().boolFlags;
			if (a_on) {
				flags.set(RE::Actor::BOOL_FLAGS::kEssential);
			} else {
				flags.reset(RE::Actor::BOOL_FLAGS::kEssential);
			}
			essentialSet = a_on;
		}

		// SkyCraft+ : a healing effect (spell, potion, ...) is restoring the player's health.
		bool HealingNow(RE::PlayerCharacter* a_player)
		{
			// Through MagicTarget, so it's a real call into the game's vtable: on the player itself the
			// compiler bound it to an address that's empty in this build (a crash on loading a save).
			auto* target = a_player->AsMagicTarget();
			auto* effects = target ? target->GetActiveEffectList() : nullptr;
			if (!effects) {
				return false;
			}
			for (auto* active : *effects) {
				auto* base = active && !active->flags.any(RE::ActiveEffect::Flag::kInactive) ? active->GetBaseObject() : nullptr;
				if (IsHeal(base)) {
					return true;
				}
			}
			return false;
		}

		// Minecraft owns the player's health: Skyrim damage is refunded here and sent to Minecraft.
		// SkyCraft+ : Skyrim's health is held at Minecraft's share (never under half, so one big
		// hit can't send the player into bleedout) instead of full, so a heal has room to work:
		// what a healing spell or potion restores goes to Minecraft's hearts. Skyrim's own slow
		// regeneration doesn't (Minecraft heals by eating).
		void BridgePlayerDamage(RE::PlayerCharacter* a_player, float a_delta)
		{
			auto*       av = a_player->AsActorValueOwner();
			const float max = a_player->GetActorValueMax(RE::ActorValue::kHealth);
			const float held = std::clamp(State().mcHealthFrac.load(), 0.5f, 1.0f) * max;
			// Minecraft's hearts moved (healed, hurt, eaten): Skyrim's health follows quietly. Only
			// what's left after that is Skyrim's doing; taken as a hit, Minecraft healing above half
			// sent damage back to Minecraft over and over.
			static float lastHeld = -1.0f;
			if (healthPrimed && lastHeld >= 0.0f && std::fabs(held - lastHeld) > 0.001f) {
				if (held > lastHeld) {
					av->RestoreActorValue(RE::ActorValue::kHealth, held - lastHeld);
				} else {
					av->DamageActorValue(RE::ActorValue::kHealth, lastHeld - held);
				}
			}
			lastHeld = held;
			const float cur = av->GetActorValue(RE::ActorValue::kHealth);
			// What Skyrim's health is given back (to where it's held), and the damage Minecraft gets: that
			// and what was cut off a hit that would have killed (ModActorValueHook).
			const float deficit = held - cur;
			const float hurt = deficit + std::exchange(cutDamage, 0.0f);
			lastHit.age += a_delta;
			lastDamagerAge += a_delta;
			const bool healing = healWindow > 0.0f;
			healWindow = std::max(0.0f, healWindow - a_delta);
			if (!healthPrimed) {
				// Whatever damage the save had before Minecraft took over isn't a new hit.
				healthPrimed = true;
				if (deficit > 0.0f) {
					av->RestoreActorValue(RE::ActorValue::kHealth, deficit);
				} else if (deficit < 0.0f) {
					av->DamageActorValue(RE::ActorValue::kHealth, -deficit);
				}
				return;
			}
			if (deficit < -0.01f) {
				const float gained = -deficit;
				av->DamageActorValue(RE::ActorValue::kHealth, gained);
				if ((healing || HealingNow(a_player)) && max > 0.0f) {
					Link::Get().PushInput(proto::kInHeal, 0, static_cast<std::int32_t>(gained / max * 10000.0f));
					static int logged = 0;
					if (logged++ < 20) {
						logger::info("health: a Skyrim heal of {:.1f} goes to Minecraft's hearts ({:.0f}%)", gained, gained / max * 100.0f);
					}
				}
			}
			if (deficit > 0.01f) {
				av->RestoreActorValue(RE::ActorValue::kHealth, deficit);
			}
			if (hurt > 0.01f) {
				RE::FormID      attacker = 0;
				proto::HurtKind kind = proto::kHurtOther;
				std::uint32_t   flags = 0;
				if (lastHit.age < kHitMemorySeconds) {
					attacker = lastHit.attacker;
					kind = lastHit.kind;
					flags = lastHit.flags;
					lastHit.age = 99.0f;  // one hit event explains one health drop
				} else if (lastDamagerAge < kHitMemorySeconds) {
					attacker = lastDamager;
					kind = proto::kHurtMagic;  // damage over time from someone (spells, poison)
				}
				if (kind == proto::kHurtMelee || kind == proto::kHurtProjectile) {
					SendHurt(kind, hurt, attacker, flags);
				} else {
					dot.attacker = attacker ? attacker : dot.attacker;
					dot.kind = kind;
					dot.damage += hurt;
				}
			}
			dot.age += a_delta;
			if (dot.age >= kDotFlushSeconds) {
				SendHurt(dot.kind, dot.damage, dot.attacker, 0);
				dot = PendingHurt{};
			}
		}

		void WriteActorTable(RE::PlayerCharacter* a_player)
		{
			static std::vector<proto::ActorRecord> records;
			records.clear();
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				Link::Get().WriteActors(nullptr, 0);
				return;
			}
			const auto playerPos = a_player->GetPosition();
			for (auto& handle : lists->highActorHandles) {
				auto actorPtr = handle.get();
				auto* actor = actorPtr.get();
				if (!actor || actor == a_player || actor->IsDisabled() || !actor->Is3DLoaded() || actor->IsGhost()) {
					continue;
				}
				const auto pos = actor->GetPosition();
				if (pos.GetDistance(playerPos) > kActorRange) {
					continue;
				}
				proto::ActorRecord r{};
				r.formId = actor->GetFormID();
				r.flags = (actor->IsHostileToActor(a_player) ? proto::kActorHostile : 0u) | (actor->IsDead() ? proto::kActorDead : 0u) |
				          (actor->IsEssential() ? proto::kActorEssential : 0u) | (actor->IsInCombat() ? proto::kActorInCombat : 0u) |
				          (Animals::InQuest(actor) ? proto::kActorQuest : 0u) | (Animals::OnPlayersLead(actor->GetFormID()) ? proto::kActorLeashed : 0u) |  // SkyCraft+
				          (Animals::IsBaby(actor->GetFormID()) ? proto::kActorBaby : 0u) | (Animals::Species(actor) << proto::kActorSpeciesShift);
				const auto mc = SkyToMc(pos);
				r.x = static_cast<float>(mc.x);
				r.y = static_cast<float>(mc.y);
				r.z = static_cast<float>(mc.z);
				r.yaw = HeadingToMcYaw(actor->GetAngleZ());
				const float k = 1.0f / static_cast<float>(proto::kUnitsPerBlock);
				r.height = std::clamp(actor->GetHeight() * k, 0.3f, 12.0f);
				r.width = std::clamp(actor->GetBoundRadius() * 2.0f * k, 0.3f, 6.0f);
				const float maxHealth = actor->GetActorValueMax(RE::ActorValue::kHealth);
				r.healthFrac = maxHealth > 0.0f ? std::clamp(actor->AsActorValueOwner()->GetActorValue(RE::ActorValue::kHealth) / maxHealth, 0.0f, 1.0f) : 0.0f;
				r.level = actor->GetLevel();
				if (const char* name = actor->GetDisplayFullName()) {
					strncpy_s(r.name, name, _TRUNCATE);
				}
				records.push_back(r);
				if (records.size() >= proto::kMaxActors) {
					break;
				}
			}
			Link::Get().WriteActors(records.data(), static_cast<std::uint32_t>(records.size()));
		}

		// ---- Skyrim's own hit pipeline ---------------------------------------------------------
		// ID 38627 is Skyrim's melee "HitFrame" handler: it builds a HitData (Ctor 43995,
		// Populate 44001) and hands it to ID 38586, which applies the hit to the victim: damage,
		// blocking, hit reactions and stagger, pain voice, the hit event (crime, quests, the enemy
		// health bar), kill credit. We feed Minecraft's hits through the same function.
		using ProcessHitFn = void(RE::Actor*, RE::HitData&);
		using HitDataCtorFn = RE::HitData*(RE::HitData*);
		ProcessHitFn*  processHit = nullptr;
		HitDataCtorFn* hitDataCtor = nullptr;

		void ResolveHitPipeline()
		{
			if (!REL::Module::IsAE()) {
				logger::info("hit pipeline: not on AE; using plain damage");
				return;
			}
			// Only trust the address if the engine's own melee code really calls it.
			const auto  site = REL::ID(38627).address() + 0x4A8;
			const auto* code = reinterpret_cast<const std::uint8_t*>(site);
			const auto  target = REL::ID(38586).address();
			std::int32_t rel = 0;
			std::memcpy(&rel, code + 1, 4);
			if (code[0] != 0xE8 || site + 5 + rel != target) {
				logger::warn("hit pipeline: melee call site doesn't match this game build; using plain damage");
				return;
			}
			processHit = reinterpret_cast<ProcessHitFn*>(target);
			hitDataCtor = reinterpret_cast<HitDataCtorFn*>(REL::ID(43995).address());
			logger::info("hit pipeline: Minecraft hits go through Skyrim's hit processing");
		}

		// A vanilla weapon standing in for the Minecraft one: its impact set gives the blood
		// spray and impact sound, its type the skill and sounds.
		RE::TESObjectWEAP* StandInWeapon(std::uint32_t a_weapon)
		{
			RE::FormID id = 0;
			switch (a_weapon) {
			case proto::kWeaponBlade:
				id = 0x00012EB7;  // Iron Sword
				break;
			case proto::kWeaponAxe:
				id = 0x00013790;  // Iron War Axe
				break;
			case proto::kWeaponPierce:
				id = 0x0001397E;  // Iron Dagger
				break;
			case proto::kWeaponArrow:
				id = 0x00013985;  // Hunting Bow
				break;
			default:
				id = 0x00013982;  // Iron Mace (fists, tools, blunt things)
				break;
			}
			return RE::TESForm::LookupByID<RE::TESObjectWEAP>(id);
		}

		// The Skyrim skill a Minecraft hit trains: swords, maces and tools One-Handed; axes and
		// tridents/spears in hand Two-Handed; arrows and anything thrown Archery; fists nothing.
		RE::ActorValue HitSkill(const proto::McEvent& a_ev)
		{
			if (a_ev.flags & proto::kHitProjectile) {
				return RE::ActorValue::kArchery;
			}
			switch (a_ev.weapon) {
			case proto::kWeaponUnarmed:
				return RE::ActorValue::kNone;
			case proto::kWeaponAxe:
			case proto::kWeaponPierce:
				return RE::ActorValue::kTwoHanded;
			default:
				return RE::ActorValue::kOneHanded;
			}
		}

		// Skyrim's own skill experience (what its weapons, blocking, armour and crafting call), so
		// skills level up with Skyrim's notification and count towards the character's level.
		// Uses are counted like Skyrim counts them: a weapon hit is worth the weapon's damage.
		void TrainSkill(RE::PlayerCharacter* a_player, RE::ActorValue a_skill, float a_uses)
		{
			if (a_skill == RE::ActorValue::kNone || !(a_uses >= 0.05f)) {
				return;  // nothing, or a scratch (damage over time ticks)
			}
			const float before = a_player->AsActorValueOwner()->GetBaseActorValue(a_skill);
			a_player->AddSkillExperience(a_skill, std::min(a_uses, 500.0f));
			const float after = a_player->AsActorValueOwner()->GetBaseActorValue(a_skill);
			static int logged = 0;
			if (after > before || logged < 8) {
				++logged;
				logger::info("skill {} +{:.1f} uses ({:.0f}{})", static_cast<int>(a_skill), a_uses, after, after > before ? ", levelled up" : "");
			}
		}

		RE::NiAVObject* HitNode(RE::Actor* a_actor)
		{
			auto* root = a_actor->Get3D();
			if (!root) {
				return nullptr;
			}
			for (const char* name : { "NPC Spine2 [Spn2]", "NPC Spine1 [Spn1]", "NPC Spine [Spn0]", "NPC Pelvis [Pelv]" }) {
				if (auto* node = root->GetObjectByName(name)) {
					return node;
				}
			}
			return root;
		}

		// A Minecraft hit on an actor's stand-in: real damage, scaled so Minecraft gear stays
		// meaningful against higher-level enemies, delivered the way a Skyrim weapon would.
		void ApplyHit(RE::PlayerCharacter* a_player, const proto::McEvent& a_ev)
		{
			auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_ev.formId);
			if (!actor || actor->IsDead()) {
				return;
			}
			// SkyCraft+ : balancing. Was 5 + 0.25 per level: Minecraft swords hit far harder than Skyrim's.
			const float scale = State().mcDamageBase + State().mcDamagePerLevel * static_cast<float>(actor->GetLevel());
			const float damage = a_ev.a * scale;
			const bool  crit = (a_ev.flags & proto::kHitCritical) != 0;
			const bool  projectile = (a_ev.flags & proto::kHitProjectile) != 0;
			// Sprint hits, knockback enchantments and crits stagger; ordinary swings don't
			// (Minecraft's attack rate would otherwise stun-lock everything).
			const float push = a_ev.d;
			const float stagger = (push > 0.45f || crit) ? std::clamp(crit ? 0.35f : (push - 0.4f) * 1.2f, 0.25f, 1.0f) : 0.0f;
			auto*       weapon = StandInWeapon(a_ev.weapon);
			auto*       node = HitNode(actor);
			RE::NiPoint3 hitPos = node ? node->world.translate : actor->GetPosition() + RE::NiPoint3{ 0.0f, 0.0f, actor->GetHeight() * 0.6f };
			RE::NiPoint3 dir = hitPos - a_player->GetPosition();
			dir.z = 0.0f;
			dir = dir.Length() > 1e-3f ? dir / dir.Length() : RE::NiPoint3{ 0.0f, 1.0f, 0.0f };

			if (processHit && damage > 0.0f) {
				alignas(16) std::array<std::byte, sizeof(RE::HitData)> storage{};
				auto* hit = reinterpret_cast<RE::HitData*>(storage.data());
				hitDataCtor(hit);
				hit->Populate(a_player, actor, nullptr);
				hit->weapon = weapon;
				hit->hitPosition = hitPos;
				hit->hitDirection = dir;
				hit->totalDamage = damage;
				hit->physicalDamage = damage;
				hit->resistedPhysicalDamage = 0.0f;
				hit->resistedTypedDamage = 0.0f;
				hit->sneakAttackBonus = 1.0f;
				hit->bonusHealthDamageMult = 1.0f;
				hit->stagger = stagger;
				hit->pushBack = stagger > 0.0f ? push : 0.0f;
				hit->skill = HitSkill(a_ev);
				hit->flags.reset(RE::HitData::Flag::kPowerAttack, RE::HitData::Flag::kCritical, RE::HitData::Flag::kSneakAttack, RE::HitData::Flag::kMeleeAttack);
				if (!projectile) {
					hit->flags.set(RE::HitData::Flag::kMeleeAttack);
				}
				if (crit) {
					hit->flags.set(RE::HitData::Flag::kCritical);
				}
				if (push > 0.45f) {
					hit->flags.set(RE::HitData::Flag::kPowerAttack);
				}
				processHit(actor, *hit);
			} else if (damage > 0.0f) {
				actor->DoDamage(damage, a_player, true);
				if (stagger > 0.0f && !actor->IsDead()) {
					const float pushHeading = std::atan2(a_ev.b, -a_ev.c);  // MC (x, z) -> Skyrim heading
					float       sdir = (pushHeading - actor->GetAngleZ()) / (2.0f * kPi) + 0.5f;
					sdir -= std::floor(sdir);
					actor->SetGraphVariableFloat("staggerDirection", sdir);
					actor->SetGraphVariableFloat("staggerMagnitude", stagger);
					actor->NotifyAnimationGraph("staggerStart");
				}
			}
			// Blood spray and the weapon-on-flesh sound, like a Skyrim blade connecting.
			if (auto* impacts = RE::BGSImpactManager::GetSingleton(); impacts && weapon && weapon->impactDataSet && node && damage > 0.0f) {
				RE::NiPoint3 pick = dir;
				impacts->PlayImpactEffect(actor, weapon->impactDataSet, node->name.c_str(), pick, 128.0f, false, false);
			}
			if (damage > 0.0f) {
				TrainSkill(a_player, HitSkill(a_ev), a_ev.a);
			}
			if (!actor->IsDead() && !actor->IsPlayerTeammate() && !actor->IsInCombat()) {
				actor->StartCombat(a_player);
			}
			logger::info("hit {} ({:08X}, level {}) for {:.1f} Minecraft -> {:.0f} Skyrim damage{}{}{}", actor->GetDisplayFullName(), a_ev.formId,
				actor->GetLevel(), a_ev.a, damage, crit ? ", critical" : "", projectile ? ", projectile" : "", stagger > 0.0f ? ", stagger" : "");
		}

		void KillPlayer(RE::PlayerCharacter* a_player, const proto::McEvent& a_ev)
		{
			if (a_player->IsDead()) {
				return;
			}
			logger::info("Minecraft player died (killer {:08X}); killing the Skyrim player", a_ev.formId);
			SetEssential(a_player, false);
			auto* killer = a_ev.formId ? RE::TESForm::LookupByID<RE::Actor>(a_ev.formId) : nullptr;
			const float health = a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kHealth);
			a_player->KillImpl(killer, health + 1.0f, true, false);
			if (!a_player->IsDead()) {
				a_player->KillImmediate();
			}
		}

		bool AnyoneFighting(RE::PlayerCharacter* a_player)
		{
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				return false;
			}
			const auto playerPos = a_player->GetPosition();
			for (auto& handle : lists->highActorHandles) {
				auto actor = handle.get();
				if (actor && !actor->IsDead() && actor->IsInCombat() && actor->GetActorRuntimeData().currentCombatTarget.get().get() == a_player &&
					actor->GetPosition().GetDistance(playerPos) < 6000.0f) {
					return true;
				}
			}
			return false;
		}

		// How an enemy's combat AI sees the player (read under SEH: these arrays belong to Skyrim's
		// AI, which may be changing them).
		struct CombatView
		{
			int   unreachableSpots = -1;
			float nearestSpot = -1.0f;  // units from the player
			int   detectLevel = -99;
			int   targetFlags = -1;
			float lastKnownGap = -1.0f;  // how far the player is from where the group last placed them
			int   attackers = -1;
		};

		bool ReadCombatView(const RE::CombatController* a_cc, std::uint32_t a_playerHandle, const RE::NiPoint3& a_playerPos, CombatView& a_out)
		{
			__try {
				if (a_cc->state) {
					const auto& spots = a_cc->state->unreachableLocations;
					a_out.unreachableSpots = static_cast<int>(spots.size());
					for (std::uint32_t i = 0; i < spots.size() && i < 64; ++i) {
						const float d = spots[i].loc.pos.GetDistance(a_playerPos);
						if (a_out.nearestSpot < 0.0f || d < a_out.nearestSpot) {
							a_out.nearestSpot = d;
						}
					}
				}
				if (a_cc->combatGroup) {
					const auto& targets = a_cc->combatGroup->targets;
					for (std::uint32_t i = 0; i < targets.size() && i < 32; ++i) {
						const auto& t = targets[i];
						if (t.targetHandle.native_handle() == a_playerHandle) {
							a_out.detectLevel = t.detectLevel;
							a_out.targetFlags = static_cast<int>(t.flags.underlying());
							a_out.lastKnownGap = t.lastKnownLoc.pos.GetDistance(a_playerPos);
							a_out.attackers = t.attackerCount;
						}
					}
				}
				return true;
			} __except (EXCEPTION_EXECUTE_HANDLER) {
				return false;
			}
		}

		// ---- Minecraft lava and fire on Skyrim's NPCs -------------------------------------------
		// Standing in lava, fire or a campfire (or on magma) hurts like it does in Minecraft,
		// scaled like Minecraft's hits on them, sets them burning (Skyrim's own campfire burn,
		// HazardFireSpell, for the flames on their body), and they scramble out to the nearest
		// spot that isn't burning.
		constexpr RE::FormID kHazardFireSpell = 0x000153BD;
		constexpr float      kHazardTick = 0.2f;

		struct Burning
		{
			float        afterburn = 0.0f;  // seconds left burning after leaving the fire
			float        castCooldown = 0.0f;
			bool         escaping = false;
			RE::NiPoint3 escapeTo;
		};
		std::unordered_map<RE::FormID, Burning> burning;
		float                                   hazardTimer = 0.0f;

		std::uint8_t HazardAtActor(RE::Actor* a_actor)
		{
			const auto   mc = SkyToMc(a_actor->GetPosition());
			const int    x = int(std::floor(mc.x)), z = int(std::floor(mc.z)), feet = int(std::floor(mc.y + 0.1));
			std::uint8_t worst = proto::kHazardNone;
			for (int y = feet; y <= feet + 1; ++y) {
				const auto h = BlockLights::HazardAt(x, y, z);
				if (h == proto::kHazardLava || (h == proto::kHazardFire && worst != proto::kHazardLava)) {
					worst = h;
				}
			}
			if (worst == proto::kHazardNone && BlockLights::HazardAt(x, int(std::floor(mc.y - 0.1)), z) == proto::kHazardMagma) {
				worst = proto::kHazardMagma;
			}
			return worst;
		}

		// The nearest block column around the actor whose feet and head blocks don't burn.
		bool FindEscape(RE::Actor* a_actor, RE::NiPoint3& a_out)
		{
			const auto mc = SkyToMc(a_actor->GetPosition());
			const int  x0 = int(std::floor(mc.x)), z0 = int(std::floor(mc.z)), feet = int(std::floor(mc.y + 0.1));
			for (int r = 1; r <= 5; ++r) {
				float best = 1e9f;
				int   bx = 0, bz = 0;
				for (int dx = -r; dx <= r; ++dx) {
					for (int dz = -r; dz <= r; ++dz) {
						if (std::max(std::abs(dx), std::abs(dz)) != r) {
							continue;
						}
						const int x = x0 + dx, z = z0 + dz;
						bool      safe = true;
						for (int y = feet - 1; y <= feet + 1 && safe; ++y) {
							const auto h = BlockLights::HazardAt(x, y, z);
							safe = h == proto::kHazardNone || (y == feet - 1 && h == proto::kHazardFire);
						}
						const float d = float((x + 0.5 - mc.x) * (x + 0.5 - mc.x) + (z + 0.5 - mc.z) * (z + 0.5 - mc.z));
						if (safe && d < best) {
							best = d, bx = x, bz = z;
						}
					}
				}
				if (best < 1e8f) {
					a_out = McToSky(bx + 0.5, mc.y, bz + 0.5);
					return true;
				}
			}
			return false;
		}

		void UpdateHazards(RE::PlayerCharacter* a_player, float a_delta)
		{
			// Scramble out: a quick run toward safety, every frame, facing the way they go.
			for (auto& [id, b] : burning) {
				if (!b.escaping) {
					continue;
				}
				auto* actor = RE::TESForm::LookupByID<RE::Actor>(id);
				if (!actor || actor->IsDead() || !actor->Is3DLoaded()) {
					b.escaping = false;
					continue;
				}
				auto         pos = actor->GetPosition();
				RE::NiPoint3 to = b.escapeTo - pos;
				to.z = 0.0f;
				const float dist = to.Length();
				if (dist < 10.0f) {
					b.escaping = false;
					continue;
				}
				const float step = std::min(dist, 380.0f * a_delta);
				pos += to * (step / dist);
				actor->SetPosition(pos, true);
				actor->SetHeading(std::atan2(to.x, to.y));
			}

			hazardTimer -= a_delta;
			if (hazardTimer > 0.0f) {
				return;
			}
			hazardTimer = kHazardTick;
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				return;
			}
			static auto* fireSpell = RE::TESForm::LookupByID<RE::SpellItem>(kHazardFireSpell);
			const auto   playerPos = a_player->GetPosition();
			for (auto& handle : lists->highActorHandles) {
				auto actorPtr = handle.get();
				auto* actor = actorPtr.get();
				if (!actor || actor == a_player || actor->IsDead() || !actor->Is3DLoaded() || actor->GetPosition().GetDistance(playerPos) > kActorRange) {
					continue;
				}
				const auto hazard = HazardAtActor(actor);
				auto       it = burning.find(actor->GetFormID());
				if (hazard == proto::kHazardNone && (it == burning.end() || it->second.afterburn <= 0.0f)) {
					continue;
				}
				auto& b = burning[actor->GetFormID()];
				// Minecraft damage per second: lava 8 (4 every half second), fire 2, magma 1,
				// then burning 1 a second once out; scaled like a Minecraft hit on them.
				float mcPerSecond = 1.0f;
				if (hazard == proto::kHazardLava) {
					mcPerSecond = 8.0f;
					b.afterburn = 8.0f;
				} else if (hazard == proto::kHazardFire) {
					mcPerSecond = 2.0f;
					b.afterburn = std::max(b.afterburn, 4.0f);
				} else if (hazard == proto::kHazardMagma) {
					mcPerSecond = 1.0f;
				} else {
					b.afterburn -= kHazardTick;
				}
				const float scale = 5.0f + 0.25f * static_cast<float>(actor->GetLevel());
				actor->DoDamage(mcPerSecond * kHazardTick * scale, nullptr, true);
				// Flames on the body while they burn (not on magma: that only scorches feet).
				b.castCooldown -= kHazardTick;
				if (fireSpell && hazard != proto::kHazardMagma && b.castCooldown <= 0.0f && !actor->IsDead()) {
					if (auto* caster = actor->GetMagicCaster(RE::MagicSystem::CastingSource::kInstant)) {
						caster->CastSpellImmediate(fireSpell, false, actor, 1.0f, false, 0.0f, nullptr);
					}
					b.castCooldown = 1.5f;
				}
				if (hazard != proto::kHazardNone && !b.escaping && !actor->IsDead()) {
					b.escaping = FindEscape(actor, b.escapeTo);
				}
			}
			std::erase_if(burning, [](const auto& a_entry) { return a_entry.second.afterburn <= 0.0f && !a_entry.second.escaping; });
		}

		void LogNearbyCombat(RE::PlayerCharacter* a_player)
		{
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				return;
			}
			const auto playerPos = a_player->GetPosition();
			int        hostiles = 0, fighting = 0, targeting = 0, attacking = 0;
			float      nearest = 1e9f;
			RE::Actor* closest = nullptr;
			for (auto& handle : lists->highActorHandles) {
				auto actor = handle.get();
				if (!actor || actor->IsDead() || !actor->IsHostileToActor(a_player)) {
					continue;
				}
				const float d = actor->GetPosition().GetDistance(playerPos);
				if (d > 4000.0f) {
					continue;
				}
				++hostiles;
				if (d < nearest) {
					nearest = d;
					closest = actor.get();
				}
				fighting += actor->IsInCombat() ? 1 : 0;
				targeting += actor->GetActorRuntimeData().currentCombatTarget.get().get() == a_player ? 1 : 0;
				attacking += actor->IsAttacking() ? 1 : 0;
			}
			if (!hostiles) {
				return;
			}
			auto* av = closest->AsActorValueOwner();
			logger::info("combat: {} hostile within 4000 units, {} in combat, {} targeting the player, {} attacking | nearest {} at {:.0f} (aggression {:.0f}, weapon {}) | "
						 "player weapon state {}",
				hostiles, fighting, targeting, attacking, closest->GetDisplayFullName(), nearest, av ? av->GetActorValue(RE::ActorValue::kAggression) : -1.0f,
				closest->AsActorState()->IsWeaponDrawn() ? "out" : "away", static_cast<int>(a_player->AsActorState()->GetWeaponState()));
			// Does its AI think it can reach the player, and where does it think the player is?
			if (auto* cc = closest->GetActorRuntimeData().combatController) {
				CombatView  view;
				const bool  ok = ReadCombatView(cc, a_player->GetHandle().native_handle(), playerPos, view);
				const auto* package = closest->GetCurrentPackage();
				logger::info("  {}: {} unreachable spots (nearest {:.0f} from the player) | player to its group: detect level {}, flags {}, {:.0f} from last known spot, "
							 "{} attackers | package {:08X} type {} | it moving {}, player moving {}, player midair {}{}",
					closest->GetDisplayFullName(), view.unreachableSpots, view.nearestSpot, view.detectLevel, view.targetFlags, view.lastKnownGap, view.attackers,
					package ? package->GetFormID() : 0u, package ? static_cast<int>(package->packData.packType.underlying()) : -1, closest->IsMoving(), a_player->IsMoving(),
					a_player->IsInMidair(), ok ? "" : " (read faulted)");
				// Skyrim's own gate on updating an NPC's combat controller (AE ID 33216) and each
				// of its checks on the NPC: whichever is "true" (or "off") is what stops it.
				if (REL::Module::IsAE()) {
					using GateFn = bool (*)(RE::CombatController*);
					using ActorFn = bool (*)(RE::Actor*);
					using ProcessFn = bool (*)(RE::AIProcess*);
					static const auto  gate = reinterpret_cast<GateFn>(REL::ID(33216).address());
					static const auto  knocked = reinterpret_cast<ActorFn>(REL::ID(37484).address());
					static const auto  fading = reinterpret_cast<ProcessFn>(REL::ID(39452).address());
					static const auto  talking = reinterpret_cast<ActorFn>(REL::ID(37199).address());
					static const auto* combatAi = reinterpret_cast<const std::uint8_t*>(REL::ID(380233).address());
					auto*              process = closest->GetActorRuntimeData().currentProcess;
					const auto         bits = closest->GetActorRuntimeData().boolBits;
					logger::info("  {} combat update allowed {} | combat AI on {}, controller flag41 {}, inactive {}, processMe {}, paralyzed {}, 3D {}, "
								 "knocked {}, fading {}, talking to player {}, started {} | player controls {:#x}",
						closest->GetDisplayFullName(), gate(cc), *combatAi, cc->unk41, cc->inactive, bits.all(RE::Actor::BOOL_BITS::kProcessMe),
						bits.all(RE::Actor::BOOL_BITS::kParalyzed), closest->Is3DLoaded(), knocked(closest), process ? fading(process) : false,
						talking(closest), cc->startedCombat,
						RE::ControlMap::GetSingleton() ? RE::ControlMap::GetSingleton()->GetRuntimeData().enabledControls.underlying() : 0u);
				}
			}
		}
	}

	namespace
	{
		// ---- Minecraft explosions in Skyrim's physics ------------------------------------------
		struct PendingExplosion
		{
			RE::NiPoint3 center;
			float        radius;  // Minecraft blocks
			float        delay;   // seconds: after the blast's damage has landed
		};
		std::vector<PendingExplosion> pendingExplosions;

		// People a blast caught. When one of them dies (Minecraft's damage lands a moment after the
		// blast) and Skyrim turns them into a ragdoll, the whole body is thrown away from it.
		struct PendingFling
		{
			RE::ActorHandle actor;
			RE::NiPoint3    center;
			float           speed;     // Havok metres per second
			float           timeLeft;  // seconds to wait for the ragdoll
		};
		std::vector<PendingFling> pendingFlings;

		// Every dynamic body of an actor's ragdoll gets the same velocity: out and up from the blast.
		bool FlingRagdoll(RE::Actor* a_actor, const RE::NiPoint3& a_center, float a_speed)
		{
			auto* root = a_actor->Get3D();
			auto* cell = a_actor->GetParentCell();
			auto* world = cell ? cell->GetbhkWorld() : nullptr;
			if (!root || !world) {
				return false;
			}
			RE::NiPoint3 dir = a_actor->GetPosition() - a_center;
			dir.z = 0.0f;
			const float flat = dir.Length();
			dir = flat > 1.0f ? dir / flat : RE::NiPoint3{};
			dir.z = 0.9f;
			dir /= dir.Length();
			int bodies = 0;
			RE::BSWriteLockGuard lock(world->worldLock);
			RE::BSVisit::TraverseScenegraphCollision(root, [&](RE::bhkNiCollisionObject* a_collision) {
				auto* rigid = a_collision->body ? netimmerse_cast<RE::bhkRigidBody*>(a_collision->body.get()) : nullptr;
				auto* body = rigid ? rigid->GetRigidBody() : nullptr;
				if (body) {
					using Motion = RE::hkpMotion::MotionType;
					const auto type = body->motion.type.get();
					if (type != Motion::kFixed && type != Motion::kKeyframed && type != Motion::kCharacter && type != Motion::kInvalid) {
						const float mass = body->motion.GetMass();
						body->ApplyLinearImpulse(RE::hkVector4(dir.x * a_speed * mass, dir.y * a_speed * mass, dir.z * a_speed * mass, 0.0f));
						++bodies;
					}
				}
				return RE::BSVisit::BSVisitControl::kContinue;
			});
			return bodies > 0;
		}

		void UpdateFlings(float a_delta)
		{
			for (auto it = pendingFlings.begin(); it != pendingFlings.end();) {
				auto actor = it->actor.get();
				it->timeLeft -= a_delta;
				bool done = !actor || it->timeLeft <= 0.0f;
				if (actor && actor->IsDead() && FlingRagdoll(actor.get(), it->center, it->speed)) {
					logger::info("flung the body of {} ({:.1f} m/s)", actor->GetName(), it->speed);
					done = true;
				}
				it = done ? pendingFlings.erase(it) : it + 1;
			}
		}

		// People within reach are knocked away the way Skyrim's own explosions do it (the dead
		// ragdoll and fly); loose physics objects get a kick away from the blast.
		void ApplyExplosion(RE::PlayerCharacter* a_player, const PendingExplosion& a_blast)
		{
			const float reach = a_blast.radius * 2.0f * static_cast<float>(proto::kUnitsPerBlock);
			const float power = a_blast.radius / 4.0f;  // TNT = 1
			std::vector<RE::TESObjectREFR*> things;
			if (auto* tes = RE::TES::GetSingleton()) {
				tes->ForEachCell([&](RE::TESObjectCELL* a_cell) {
					a_cell->ForEachReferenceInRange(a_blast.center, reach, [&](RE::TESObjectREFR* a_ref) {
						if (a_ref && a_ref != a_player && !a_ref->IsDisabled() && !a_ref->IsDeleted()) {
							things.push_back(a_ref);
						}
						return RE::BSContainer::ForEachResult::kContinue;
					});
				});
			}
			int people = 0, objects = 0;
			for (auto* ref : things) {
				const auto  pos = ref->GetPosition();
				const float dist = pos.GetDistance(a_blast.center);
				const float falloff = std::clamp(1.0f - dist / reach, 0.0f, 1.0f);
				if (falloff <= 0.0f) {
					continue;
				}
				if (auto* actor = ref->As<RE::Actor>()) {
					if (!actor->IsDead()) {
						if (auto* process = actor->GetActorRuntimeData().currentProcess) {
							process->KnockExplosion(actor, a_blast.center, 25.0f * falloff * power);
						}
					}
					// If the blast kills them (or already has), the body flies.
					pendingFlings.push_back({ actor->GetHandle(), a_blast.center, (6.0f + 12.0f * falloff) * power, 2.0f });
					++people;
					continue;
				}
				auto* root = ref->Get3D();
				auto* cell = ref->GetParentCell();
				auto* world = cell ? cell->GetbhkWorld() : nullptr;
				if (!root || !world) {
					continue;
				}
				RE::NiPoint3 dir = pos - a_blast.center;
				dir.z += 0.5f * dist + 30.0f;  // out and up
				const float len = dir.Length();
				if (len < 1e-3f) {
					continue;
				}
				dir /= len;
				const float speed = 14.0f * falloff * power;  // Havok metres per second
				bool        moved = false;
				RE::BSWriteLockGuard lock(world->worldLock);
				RE::BSVisit::TraverseScenegraphCollision(root, [&](RE::bhkNiCollisionObject* a_collision) {
					auto* rigid = a_collision->body ? netimmerse_cast<RE::bhkRigidBody*>(a_collision->body.get()) : nullptr;
					auto* body = rigid ? rigid->GetRigidBody() : nullptr;
					if (body) {
						using Motion = RE::hkpMotion::MotionType;
						const auto type = body->motion.type.get();
						if (type != Motion::kFixed && type != Motion::kKeyframed && type != Motion::kCharacter && type != Motion::kInvalid) {
							const float mass = body->motion.GetMass();
							body->ApplyLinearImpulse(RE::hkVector4(dir.x * speed * mass, dir.y * speed * mass, dir.z * speed * mass, 0.0f));  // wakes it too
							moved = true;
						}
					}
					return RE::BSVisit::BSVisitControl::kContinue;
				});
				objects += moved ? 1 : 0;
			}
			logger::info("Minecraft explosion (radius {:.1f}): knocked {} people away, threw {} objects", a_blast.radius, people, objects);
		}
	}

	namespace Combat
	{
		void Install()
		{
			REL::Relocation<std::uintptr_t> vtbl{ RE::VTABLE_PlayerCharacter[0] };
			HandleHealthDamageHook::func = vtbl.write_vfunc(0x104, HandleHealthDamageHook::thunk);
			REL::Relocation<std::uintptr_t> values{ RE::VTABLE_PlayerCharacter[5] };  // its ActorValueOwner (at +0xB8 on 1.7.104)
			ModActorValueHook::func = values.write_vfunc(0x6, ModActorValueHook::thunk);
			if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
				events->AddEventSink<RE::TESHitEvent>(HitSink::Get());
				events->AddEventSink<RE::TESMagicEffectApplyEvent>(HealSink::Get());  // SkyCraft+
				events->AddEventSink<RE::TESEquipEvent>(FedSink::Get());  // SkyCraft+
			}
			ResolveHitPipeline();
			logger::info("combat hooks installed");
		}

		// SkyCraft+ : Minecraft hits on Skyrim objects. Minecraft never sees Skyrim's breakable things
		// (spider webs and the like), so they couldn't be broken outside Skyrim mode: a Minecraft
		// attack also damages the nearest one in front of the camera within reach.
		std::atomic<bool> swingPending{ false };

		void HitSkyrimObject(RE::PlayerCharacter* a_player)
		{
			proto::McState mc{};
			auto*          tes = RE::TES::GetSingleton();
			if (!tes || !Link::Get().ReadMcState(mc)) {
				return;
			}
			// Minecraft's eye and look, as Skyrim positions (two points along the look).
			const double yaw = mc.yaw * (kPi / 180.0), pitch = mc.pitch * (kPi / 180.0);
			const double lx = -std::sin(yaw) * std::cos(pitch), ly = -std::sin(pitch), lz = std::cos(yaw) * std::cos(pitch);
			const RE::NiPoint3 eye = McToSky(mc.eyeX, mc.eyeY, mc.eyeZ);
			RE::NiPoint3       dir = McToSky(mc.eyeX + lx, mc.eyeY + ly, mc.eyeZ + lz) - eye;
			if (dir.Length() < 1e-3f) {
				return;
			}
			dir /= dir.Length();
			constexpr float kReach = 4.5f * float(proto::kUnitsPerBlock);
			RE::TESObjectREFR* best = nullptr;
			float              bestAlong = std::numeric_limits<float>::max();
			tes->ForEachReferenceInRange(a_player, kReach + 400.0f, [&](RE::TESObjectREFR* a_ref) {
				if (!a_ref || a_ref == a_player || a_ref->IsDisabled() || a_ref->IsDeleted() || !a_ref->Is3DLoaded() || a_ref->As<RE::Actor>()) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				auto* base = a_ref->GetBaseObject();
				auto* destructible = base ? skyrim_cast<RE::BGSDestructibleObjectForm*>(base) : nullptr;
				auto* root3D = a_ref->Get3D();
				if (!destructible || !destructible->data || destructible->data->numStages <= 0 || !root3D) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				const auto& bound = root3D->worldBound;
				const RE::NiPoint3 to = bound.center - eye;
				const float        along = to.Dot(dir);
				const RE::NiPoint3 side = to - dir * along;
				const float        radius = std::max(bound.radius, 20.0f);
				if (along < -radius || along - radius > kReach || side.Length() > radius * 0.8f + 15.0f || along >= bestAlong) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				best = a_ref;
				bestAlong = along;
				return RE::BSContainer::ForEachResult::kContinue;
			});
			if (!best) {
				return;
			}
			best->DamageObject(25.0f, false);
			static int logged = 0;
			if (logged++ < 20) {
				logger::info("Minecraft hit Skyrim's {:08X} ({}): object damaged", best->GetFormID(), best->GetDisplayFullName());
			}
		}

		void MinecraftSwing()
		{
			swingPending = true;
		}

		// SkyCraft+ : the Ender Dragon gives a dragon soul. The soul is added at once (so a save made
		// in the End keeps it); the message waits until the player is back in Skyrim's world, as
		// Skyrim's HUD isn't shown while it waits in the empty cell.
		int soulMessages = 0;

		void GiveDragonSoul(RE::PlayerCharacter* a_player)
		{
			if (!State().enderDragonSoul) {
				return;
			}
			a_player->AsActorValueOwner()->ModActorValue(RE::ACTOR_VALUE_MODIFIER::kPermanent, RE::ActorValue::kDragonSouls, 1.0f);
			++soulMessages;
			logger::info("the Ender Dragon died: one dragon soul (now {:.0f})", a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kDragonSouls));
		}

		void ShowSoulMessages()
		{
			if (soulMessages > 0 && !State().parked && !RE::UI::GetSingleton()->GameIsPaused()) {
				soulMessages = 0;
				RE::SendHUDMessage::ShowHUDMessage("Dragon soul absorbed");
			}
		}

		void PerFrame(RE::PlayerCharacter* a_player, bool a_puppeting, float a_delta)
		{
			auto& link = Link::Get();
			ShowSoulMessages();  // SkyCraft+
			// SkyCraft+ : Minecraft hits on Skyrim objects.
			if (swingPending.exchange(false) && a_puppeting && !State().parked && State().minecraftHitsObjects) {
				HitSkyrimObject(a_player);
			}
			if (!a_puppeting) {
				if (essentialSet) {
					SetEssential(a_player, false);
				}
				cutDamage = 0.0f;  // not a hit when control comes back
				healthPrimed = false;
				engaged = false;
				// Still drain Minecraft's events so stale hits don't land when control resumes.
				proto::McEvent ev;
				while (link.PopEvent(ev)) {
					if (ev.type == proto::kEvPlayerDied && link.McAlive()) {
						KillPlayer(a_player, ev);
					} else if (ev.type == proto::kEvPlayerHurt && link.McAlive() && !a_player->IsDead()) {
						// SkyCraft+ : Skyrim mode: a Minecraft mob hit the player. One health pool:
						// the share of Minecraft's health it took, of Skyrim's.
						const float max = a_player->GetActorValueMax(RE::ActorValue::kHealth);
						a_player->AsActorValueOwner()->DamageActorValue(RE::ActorValue::kHealth, std::clamp(ev.a, 0.0f, 1.0f) * max);
						logger::info("Skyrim mode: a Minecraft mob took {:.0f}% of the player's health", ev.a * 100.0f);
					} else if (ev.type == proto::kEvSkyItem) {
						SkyInventory::Apply(a_player, ev);  // SkyCraft+
					} else if (ev.type == proto::kEvDragonSoul) {
						GiveDragonSoul(a_player);  // SkyCraft+
					}
				}
				pendingExplosions.clear();
				pendingFlings.clear();
				link.WriteActors(nullptr, 0);
				return;
			}
			// Skyrim can't kill the player while Minecraft decides: one huge hit knocks them down
			// (essential) for a moment instead, and Minecraft's health does the rest.
			SetEssential(a_player, true);
			WriteActorTable(a_player);
			engagedTimer -= a_delta;
			if (engagedTimer <= 0.0f) {
				engagedTimer = 0.25f;
				engaged = AnyoneFighting(a_player);
			}

			proto::McEvent ev;
			while (link.PopEvent(ev)) {
				switch (ev.type) {
				case proto::kEvHitActor:
					ApplyHit(a_player, ev);
					break;
				case proto::kEvPlayerDied:
					KillPlayer(a_player, ev);
					break;
				case proto::kEvExplosion:
					pendingExplosions.push_back({ McToSky(ev.a, ev.b, ev.c), ev.d, 0.25f });
					break;
				case proto::kEvArrowStuck:
					WorldRender::StickArrow(ev.formId, ev.a, ev.b, ev.c, ev.d, std::bit_cast<float>(ev.flags));
					break;
				case proto::kEvSkyItem:
					SkyInventory::Apply(a_player, ev);  // SkyCraft+
					break;
				case proto::kEvDragonSoul:
					GiveDragonSoul(a_player);  // SkyCraft+
					break;
				case proto::kEvLeash:
					Animals::OnLeash(a_player, ev.formId, ev.flags, ev.a, ev.b, ev.c);  // SkyCraft+
					break;
				case proto::kEvBreed:
					Animals::OnBreed(ev.formId);  // SkyCraft+
					break;
				case proto::kEvApproach:
					Animals::OnApproach(ev.formId, ev.flags);  // SkyCraft+
					break;
				case proto::kEvCropGrown:
					Crops::OnGrown(a_player, ev.formId, ev.a, ev.b, ev.c);  // SkyCraft+
					break;
				case proto::kEvMinedOre:
					Dig::GiveMinedOre(a_player, ev.a, ev.b, ev.c);  // SkyCraft+
					break;
				case proto::kEvSkillUse:
					if (ev.formId >= static_cast<std::uint32_t>(RE::ActorValue::kOneHanded) && ev.formId <= static_cast<std::uint32_t>(RE::ActorValue::kEnchanting)) {
						TrainSkill(a_player, static_cast<RE::ActorValue>(ev.formId), ev.a);
					}
					break;
				default:
					break;
				}
			}
			UpdateFlings(a_delta);
			for (auto it = pendingExplosions.begin(); it != pendingExplosions.end();) {
				it->delay -= a_delta;
				if (it->delay <= 0.0f) {
					ApplyExplosion(a_player, *it);
					it = pendingExplosions.erase(it);
				} else {
					++it;
				}
			}
			if (a_player->IsDead()) {
				return;
			}
			BridgePlayerDamage(a_player, a_delta);
			UpdateHazards(a_player, a_delta);

			diagTimer -= a_delta;
			if (diagTimer <= 0.0f && DiagnosticsEnabled()) {
				diagTimer = 5.0f;
				LogNearbyCombat(a_player);
			}
		}

		bool PlayerEngaged() { return engaged; }

		// SkyCraft+ : Skyrim combat on Minecraft mobs. Skyrim's melee hits only find Skyrim's own
		// actors; Minecraft's mobs are just drawn. At each of the player's melee HitFrames (while
		// Minecraft is in its world: a Skyrim hand in Minecraft mode, or Skyrim mode), the hit goes
		// to Minecraft too, which strikes the mob(s) in front of the player.
		class HitFrameSink final : public RE::BSTEventSink<RE::BSAnimationGraphEvent>
		{
		public:
			static HitFrameSink* Get()
			{
				static HitFrameSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::BSAnimationGraphEvent* a_event, RE::BSTEventSource<RE::BSAnimationGraphEvent>*) override
			{
				auto* player = RE::PlayerCharacter::GetSingleton();
				if (!a_event || !player || a_event->holder != player || a_event->tag != "HitFrame") {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto& st = State();
				if (!(st.skyrimDrives || (st.puppeting && (st.rightHandSkyrim || st.leftHandSkyrim)))) {
					return RE::BSEventNotifyControl::kContinue;
				}
				// The first- and third-person graphs both send it.
				const auto now = GetTickCount64();
				if (now - lastHit < 120) {
					return RE::BSEventNotifyControl::kContinue;
				}
				lastHit = now;
				auto*      process = player->GetActorRuntimeData().currentProcess;
				auto*      attack = process && process->high ? process->high->attackData.get() : nullptr;
				const bool left = attack && attack->IsLeftAttack();
				const bool power = attack && attack->data.flags.any(RE::AttackData::AttackFlag::kPowerAttack);
				const bool bash = attack && attack->data.flags.any(RE::AttackData::AttackFlag::kBashAttack);
				auto*      form = player->GetEquippedObject(left);
				auto*      weapon = form && form->GetFormID() != 0x1F4 ? form->As<RE::TESObjectWEAP>() : nullptr;
				// Minecraft damage about a Minecraft weapon's: an iron sword (7) at low skill ~5.5, a
				// daedric one (14) at skill 100 ~16. A bash mostly pushes; fists are fists.
				float damage = 1.0f;
				if (bash) {
					damage = 2.0f;
				} else if (weapon) {
					const float skill = player->AsActorValueOwner()->GetActorValue(weapon->weaponData.skill.get());
					damage = float(weapon->GetAttackDamage()) * (1.0f + skill / 200.0f) * 0.75f;
				}
				if (power) {
					damage *= 2.0f;
				}
				// SkyCraft+ : balancing. Skyrim's weapons do extra to Minecraft's mobs (bashes stay pushes).
				if (!bash) {
					damage *= st.skyrimVsMinecraft;
				}
				const float          reach = 3.0f * (weapon ? std::clamp(weapon->weaponData.reach, 0.5f, 2.0f) : 0.8f);
				const std::uint16_t flags = (power ? proto::kSkyrimHitPower : 0) | (bash ? proto::kSkyrimHitBash : 0) | (left ? proto::kSkyrimHitLeft : 0);
				Link::Get().PushInput(proto::kInSkyrimHit, flags, static_cast<std::int32_t>(damage * 100.0f), static_cast<std::int32_t>(reach * 100.0f));
				return RE::BSEventNotifyControl::kContinue;
			}

		private:
			std::uint64_t lastHit = 0;
		};

		void WatchPlayerHits(RE::PlayerCharacter* a_player, float a_delta)
		{
			// Its animation graphs are rebuilt now and then (loads, race and 3D changes); adding the
			// same sink again is ignored.
			static float timer = 0.0f;
			if (a_player && (timer -= a_delta) <= 0.0f) {
				timer = 2.0f;
				// Every graph (first and third person): whichever is animating sends the HitFrame.
				RE::BSAnimationGraphManagerPtr graphs;
				a_player->GetAnimationGraphManager(graphs);
				if (graphs) {
					for (const auto& graph : graphs->graphs) {
						if (graph) {
							graph->GetEventSource<RE::BSAnimationGraphEvent>()->AddEventSink(HitFrameSink::Get());
						}
					}
				}
			}
		}
	}
}
