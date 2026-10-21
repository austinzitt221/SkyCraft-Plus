// SkyCraft+ : Skyrim magic, arrows and shouts on Minecraft mobs
#include "Game.h"

// Skyrim's spells and shouts only find Skyrim's own actors; Minecraft's mobs are just drawn.
// Nearly every spell and shout is a projectile (bolts, flames, beams, cones) and so are arrows,
// so each frame the player's live projectiles go to Minecraft with their whole path so far, what
// they carry (damage, fire, frost, shock, a push) and, a moment after one is gone, the explosion
// where it ended. Minecraft hits each mob once per shot (proto::SkyrimShots).
namespace skycraft::SkyrimShots
{
	namespace
	{
		constexpr float kSpellToMc = 0.25f;        // a firebolt (25) ~6, like a Minecraft iron sword
		constexpr float kConcentrationShare = 0.5f; // flames and the like: a hit every half second
		constexpr float kArrowToMc = 0.35f;        // a hunting bow with iron arrows (~17) ~6
		constexpr float kKeepAfterGone = 0.4f;     // seconds a finished shot stays listed

		struct Tracked
		{
			proto::SkyrimShot shot{};
			bool              seen = false;
			float             gone = -1.0f;  // seconds since it disappeared (-1: still flying)
			float             explosionRadius = 0.0f;
		};
		std::unordered_map<std::uint32_t, Tracked> tracked;

		// SkyCraft+ : Minecraft enchantments on the Skyrim bow (kEvBowEnchants): Infinity gives each
		// arrow shot back, Multishot sends two more beside it (not themselves given back or multiplied).
		std::uint32_t                   bowEnchants = 0;
		std::unordered_set<std::uint32_t> extraArrows;
		struct Fired
		{
			RE::TESAmmo*                  ammo;
			RE::TESObjectWEAP*            weapon;
			RE::NiPoint3                  at;
			RE::Projectile::ProjectileRot angles;
		};
		std::vector<Fired> fired;
		// SkyCraft+ : arrows across the games. Arrows shot for Minecraft's (kEvBowEnchants flag 4: the bow's arrows are
		// stand-ins for Minecraft arrows) go once they've hit: picked up, they'd be Skyrim arrows the player never had.
		std::unordered_set<std::uint32_t> lentShots;
		std::vector<RE::ProjectileHandle> spent;

		McVec ToMc(const RE::NiPoint3& a_pos) { return SkyToMc(a_pos); }

		// What a spell does to whatever it hits, from its effects.
		void ReadSpell(RE::MagicItem* a_spell, float a_power, proto::SkyrimShot& a_shot)
		{
			float damage = 0.0f, push = 0.0f;
			for (auto* effect : a_spell->effects) {
				auto* base = effect ? effect->baseEffect : nullptr;
				if (!base) {
					continue;
				}
				using Arch = RE::EffectArchetypes::ArchetypeID;
				const auto arch = base->GetArchetype();
				if ((arch == Arch::kValueModifier || arch == Arch::kDualValueModifier || arch == Arch::kPeakValueModifier) &&
					base->data.primaryAV == RE::ActorValue::kHealth && base->IsDetrimental()) {
					damage += effect->GetMagnitude();
				}
				if (arch == Arch::kStagger) {
					push = std::max(push, 1.0f + effect->GetMagnitude() * 2.0f);
				}
				// SkyCraft+ : every shout in Minecraft mode. What it does besides harm, and for how long.
				std::uint32_t also = 0;
				switch (arch) {
				case Arch::kParalysis:
					also = proto::kShotParalyze;
					break;
				case Arch::kDisarm:
					also = proto::kShotDisarm;
					break;
				case Arch::kDemoralize:
				case Arch::kTurnUndead:
					also = proto::kShotFear;
					break;
				case Arch::kCalm:
				case Arch::kCommandSummoned:
					also = proto::kShotCalm;
					break;
				case Arch::kFrenzy:
					also = proto::kShotFrenzy;
					break;
				default:
					if ((arch == Arch::kValueModifier || arch == Arch::kPeakValueModifier) && base->IsDetrimental() &&
						base->data.primaryAV == RE::ActorValue::kDamageResist) {
						also = proto::kShotWither;  // Marked for Death
					}
					break;
				}
				if (also) {
					a_shot.flags |= also;
					a_shot.seconds = std::max<std::uint32_t>(a_shot.seconds, std::clamp<std::uint32_t>(effect->GetDuration(), 2u, 60u));
				}
				switch (base->data.resistVariable) {
				case RE::ActorValue::kResistFire:
					a_shot.flags |= proto::kShotFire;
					break;
				case RE::ActorValue::kResistFrost:
					a_shot.flags |= proto::kShotFrost;
					break;
				case RE::ActorValue::kResistShock:
					a_shot.flags |= proto::kShotShock;
					break;
				default:
					break;
				}
			}
			if (a_spell->GetCastingType() == RE::MagicSystem::CastingType::kConcentration) {
				damage *= kConcentrationShare;
			}
			a_shot.damage = damage * kSpellToMc * std::max(a_power, 0.25f);
			if (push > 0.0f) {
				a_shot.flags |= proto::kShotPush;
				a_shot.push = std::min(push * std::max(a_power, 0.5f), 4.0f);
			}
		}

		void Describe(RE::Projectile* a_projectile, std::uint32_t a_id, Tracked& a_tracked)
		{
			auto&      runtime = a_projectile->GetProjectileRuntimeData();
			auto*      base = a_projectile->GetProjectileBase();
			const auto here = ToMc(a_projectile->GetPosition());
			auto&      shot = a_tracked.shot;
			const bool fresh = shot.id == 0;
			shot.id = a_id;
			if (fresh) {
				shot.x0 = float(here.x), shot.y0 = float(here.y), shot.z0 = float(here.z);  // where it was first seen
				shot.flags = 0;
				const auto type = a_projectile->GetFormType();
				if (type == RE::FormType::ProjectileBeam) {
					shot.flags |= proto::kShotBeam | proto::kShotRepeats;
				} else if (type == RE::FormType::ProjectileFlame) {
					// One flame projectile sits at the hand for the whole cast (the spray is only its
					// look): what it burns is out along the aim.
					shot.flags |= proto::kShotSpray | proto::kShotRepeats;
				} else if (type == RE::FormType::ProjectileCone) {
					shot.flags |= proto::kShotCone;
				} else {
					shot.flags |= proto::kShotPath;
					if (type == RE::FormType::ProjectileArrow || type == RE::FormType::ProjectileMissile) {
						shot.flags |= proto::kShotStops;
					}
					if (type == RE::FormType::ProjectileArrow && !runtime.spell) {
						shot.flags |= proto::kShotArrow;  // SkyCraft+ : from a bow (Minecraft arrows lent to it: SkyArrows)
					}
				}
				if (runtime.spell) {
					ReadSpell(runtime.spell, runtime.power, shot);
				} else {
					shot.damage = runtime.weaponDamage * kArrowToMc;  // an arrow or bolt
				}
				auto* explosion = runtime.explosion ? runtime.explosion : (base ? base->data.explosionType : nullptr);
				a_tracked.explosionRadius = explosion ? explosion->data.radius / float(proto::kUnitsPerBlock) : 0.0f;
				static int logged = 0;
				if (DiagnosticsEnabled() && logged++ < 15) {
					logger::info("shots: new {} ({:X}) from {}: flags {:X}, damage {:.2f}, push {:.1f}, power {:.2f}, weapon damage {:.1f}, explosion {:.1f} blocks",
						static_cast<int>(type), a_id, runtime.spell ? runtime.spell->GetName() : "(no spell)", shot.flags, shot.damage, shot.push,
						runtime.power, runtime.weaponDamage, a_tracked.explosionRadius);
				}
			}
			shot.x1 = float(here.x), shot.y1 = float(here.y), shot.z1 = float(here.z);
			if (shot.flags & (proto::kShotBeam | proto::kShotSpray)) {
				// From the hand out along the player's aim (the projectile itself stays at the hand),
				// as far as the spell reaches: a beam its range, a spray 10-15 blocks.
				auto*       player = RE::PlayerCharacter::GetSingleton();
				const float heading = player ? player->data.angle.z : a_projectile->data.angle.z;
				const float pitch = player ? player->data.angle.x : a_projectile->data.angle.x;
				const float range = (shot.flags & proto::kShotSpray) ? std::clamp(base ? base->data.range : 840.0f, 700.0f, 1050.0f)
				                                                     : std::min(base ? base->data.range : 2000.0f, 3000.0f);
				const RE::NiPoint3 end = a_projectile->GetPosition() +
				                         RE::NiPoint3(std::sin(heading) * std::cos(pitch), std::cos(heading) * std::cos(pitch), -std::sin(pitch)) * range;
				const auto endMc = ToMc(end);
				shot.x0 = float(here.x), shot.y0 = float(here.y), shot.z0 = float(here.z);
				shot.x1 = float(endMc.x), shot.y1 = float(endMc.y), shot.z1 = float(endMc.z);
			}
			if (shot.flags & proto::kShotCone) {
				const float spread = base ? base->data.coneSpread : 0.6f;
				shot.radius = std::clamp(spread > 3.2f ? spread * 0.0174533f : spread, 0.2f, 1.2f);  // degrees or radians
			} else {
				shot.radius = std::max((shot.flags & proto::kShotSpray) ? 0.7f : 0.3f, (base ? base->data.collisionRadius : 10.0f) / float(proto::kUnitsPerBlock));
			}
			a_tracked.seen = true;
			a_tracked.gone = -1.0f;
		}
	}

	// SkyCraft+ : every shout in Minecraft mode. The shouts and spells on the player now that Minecraft acts out
	// (proto::SelfEffects): by archetype, and two of Dragonborn's by name.
	std::uint32_t SelfEffects(RE::PlayerCharacter* a_player)
	{
		auto* list = a_player ? a_player->AsMagicTarget()->GetActiveEffectList() : nullptr;
		if (!list) {
			return 0;
		}
		using Arch = RE::EffectArchetypes::ArchetypeID;
		std::uint32_t bits = 0;
		for (auto* active : *list) {
			auto* base = active ? active->GetBaseObject() : nullptr;
			if (!base || active->flags.any(RE::ActiveEffect::Flag::kInactive, RE::ActiveEffect::Flag::kDispelled)) {
				continue;
			}
			const std::string_view name = base->GetName() ? base->GetName() : "";
			switch (base->GetArchetype()) {
			case Arch::kEtherealize:
				bits |= proto::kSelfEthereal;
				break;
			case Arch::kSlowTime:
				bits |= proto::kSelfSlowTime;
				break;
			case Arch::kDetectLife:
				bits |= proto::kSelfAura;
				break;
			default:
				if (!base->IsDetrimental() && base->data.primaryAV == RE::ActorValue::kWeaponSpeedMult) {
					bits |= proto::kSelfFury;
				}
				break;
			}
			if (name.find("Dragon Aspect") != std::string_view::npos) {
				bits |= proto::kSelfAspect;
			}
			if (name.find("Storm Call") != std::string_view::npos) {
				bits |= proto::kSelfStorm;
			}
		}
		return bits;
	}

	// SkyCraft+ : every shout in Minecraft mode. A shout on the player that reaches all around them
	// (Animal Allegiance, Kyne's Peace...) has no projectile to follow: Minecraft is told of it as a
	// burst where the player stands, as wide as its area, doing what it does (calm, fear...).
	void AroundPlayer(RE::PlayerCharacter* a_player, RE::MagicItem* a_spell)
	{
		if (!a_player || !a_spell) {
			return;
		}
		std::uint32_t area = 0;
		for (auto* effect : a_spell->effects) {
			area = std::max(area, effect ? effect->effectItem.area : 0u);
		}
		if (area == 0) {
			return;
		}
		static std::uint32_t next = 0xF0000000u;
		Tracked t{};
		ReadSpell(a_spell, 1.0f, t.shot);
		if (t.shot.damage <= 0.0f && !(t.shot.flags & (proto::kShotPush | proto::kShotFire | proto::kShotFrost | proto::kShotParalyze | proto::kShotDisarm | proto::kShotFear |
		                                                 proto::kShotCalm | proto::kShotFrenzy | proto::kShotWither))) {
			return;
		}
		const auto at = ToMc(a_player->GetPosition());
		t.shot.id = ++next;
		t.shot.flags |= proto::kShotExplosion;
		t.shot.x0 = t.shot.x1 = float(at.x), t.shot.y0 = t.shot.y1 = float(at.y + 1.0), t.shot.z0 = t.shot.z1 = float(at.z);
		t.shot.radius = std::clamp(area * 21.33f / float(proto::kUnitsPerBlock), 2.0f, 48.0f);  // (its area is in feet)
		t.seen = true;
		t.gone = 0.0f;  // (listed a moment, then gone)
		tracked[t.shot.id] = t;
		logger::info("shots: {} reaches {:.0f} blocks around the player (flags {:X})", a_spell->GetName(), t.shot.radius, t.shot.flags);
	}

	void SetBowEnchants(std::uint32_t a_flags)
	{
		if (a_flags != bowEnchants) {
			logger::info("shots: the Skyrim bow's Minecraft enchantments: {}{}{}{}", a_flags & 1 ? "Infinity " : "", a_flags & 2 ? "Multishot " : "", a_flags & 3 ? "" : "none",
				a_flags & 4 ? " (shooting Minecraft's arrows)" : "");
		}
		bowEnchants = a_flags;
	}

	void PerFrame(RE::PlayerCharacter* a_player, bool a_active, float a_delta)
	{
		if (!a_active || !a_player) {
			if (!tracked.empty()) {
				tracked.clear();
				Link::Get().WriteSkyrimShots(nullptr, 0);
			}
			return;
		}
		for (auto& [id, t] : tracked) {
			t.seen = false;
		}
		if (auto* manager = RE::Projectile::Manager::GetSingleton()) {
			RE::BSSpinLockGuard guard(manager->projectileLock);
			for (auto* list : { &manager->unlimited, &manager->limited }) {
				for (auto& handle : *list) {
					auto projectile = handle.get();
					if (!projectile || projectile->GetProjectileRuntimeData().shooter.get().get() != a_player) {
						continue;
					}
					const auto id = projectile->GetFormID();
					auto&      t = tracked[id];
					if (t.gone >= 0.0f) {
						t = Tracked{};  // SkyCraft+ : a finished shot's form ID used again by a new one
					}
					const bool fresh = t.shot.id == 0;
					Describe(projectile.get(), id, t);
					if (fresh && (bowEnchants & 4) && (t.shot.flags & proto::kShotArrow)) {
						lentShots.insert(id);
					}
					if (lentShots.contains(id) && !projectile->GetProjectileRuntimeData().impacts.empty()) {
						lentShots.erase(id);
						spent.push_back(handle);
					}
					if (fresh && (bowEnchants & 3) && (t.shot.flags & proto::kShotArrow) && !extraArrows.erase(id)) {
						auto& runtime = projectile->GetProjectileRuntimeData();
						if (runtime.ammoSource) {
							fired.push_back({ runtime.ammoSource, runtime.weaponSource, projectile->GetPosition(), { projectile->data.angle.x, projectile->data.angle.z } });
						}
					}
				}
			}
		}
		// SkyCraft+ : the bow's Minecraft enchantments, once the projectile lists are let go.
		for (const auto& f : fired) {
			if (bowEnchants & 1) {
				a_player->AddObjectToContainer(f.ammo, nullptr, 1, nullptr);
				if (!a_player->GetCurrentAmmo()) {
					RE::ActorEquipManager::GetSingleton()->EquipObject(a_player, f.ammo);  // (the last one was shot: readied again)
				}
			}
			if ((bowEnchants & 2) && f.weapon) {
				for (const float turn : { -0.17f, 0.17f }) {
					RE::ProjectileHandle handle;
					RE::Projectile::LaunchArrow(&handle, a_player, f.ammo, f.weapon, f.at, { f.angles.x, f.angles.z + turn });
					if (auto extra = handle.get()) {
						extraArrows.insert(extra->GetFormID());
					}
				}
			}
		}
		fired.clear();
		if (extraArrows.size() > 64) {
			extraArrows.clear();
		}
		for (auto& handle : spent) {
			if (auto arrow = handle.get(); arrow && !arrow->IsDisabled()) {
				arrow->Disable();
				arrow->SetDelete(true);
			}
		}
		spent.clear();
		if (lentShots.size() > 64) {
			lentShots.clear();
		}
		std::vector<proto::SkyrimShot> shots;
		for (auto it = tracked.begin(); it != tracked.end();) {
			auto& t = it->second;
			if (!t.seen) {
				if (t.gone < 0.0f) {
					t.gone = 0.0f;
					static int logged = 0;
					if (DiagnosticsEnabled() && logged++ < 15) {
						const float dx = t.shot.x1 - t.shot.x0, dy = t.shot.y1 - t.shot.y0, dz = t.shot.z1 - t.shot.z0;
						logger::info("shots: {:X} gone after {:.1f} blocks (radius {:.2f})", t.shot.id, std::sqrt(dx * dx + dy * dy + dz * dz), t.shot.radius);
					}
					if (t.explosionRadius > 0.0f) {
						// It went off where it was last seen.
						t.shot.flags = (t.shot.flags & ~(proto::kShotPath | proto::kShotBeam | proto::kShotCone | proto::kShotStops | proto::kShotSpray | proto::kShotRepeats)) | proto::kShotExplosion;
						t.shot.radius = t.explosionRadius;
						t.shot.id ^= 0x80000000u;  // a new shot: everything in the blast is hit, even what the bolt hit
					}
				}
				t.gone += a_delta;
				if (t.gone > kKeepAfterGone) {
					it = tracked.erase(it);
					continue;
				}
			}
			constexpr std::uint32_t kDoes = proto::kShotPush | proto::kShotFire | proto::kShotFrost | proto::kShotParalyze | proto::kShotDisarm | proto::kShotFear |
			                                proto::kShotCalm | proto::kShotFrenzy | proto::kShotWither;
			if (shots.size() < proto::kMaxSkyrimShots && (t.shot.damage > 0.0f || (t.shot.flags & kDoes))) {
				shots.push_back(t.shot);
			}
			++it;
		}
		Link::Get().WriteSkyrimShots(shots.data(), static_cast<std::uint32_t>(shots.size()));
	}
}
