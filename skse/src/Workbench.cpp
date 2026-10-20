// SkyCraft+ : Minecraft's things at Skyrim's workbenches
#include "Game.h"

// Skyrim's forge, tanning rack, smelter, cooking pot, grindstone, workbench, enchanter and alchemy lab
// see Minecraft's matching things: from the moment one is activated (before its menu opens) till its
// menu closes, Minecraft knows which (SkyState::workbench) and lends its materials into Skyrim's
// inventory as the same Skyrim ones (and a weapon or armor held, to the enchanter, as a stand-in);
// whatever is left when the menu closes goes back. Items lent while a menu is already open put its
// list right again (the forge's recipes, the grindstone's and workbench's items).
namespace skycraft::Workbench
{
	namespace
	{
		std::atomic<std::uint8_t>  bench{ 0 };
		std::atomic<std::uint32_t> seq{ 0 };
		float                      sinceActivated = 0.0f;
		std::atomic<bool>          menuSeen{ false };
		std::atomic<bool>          refresh{ false };

		class ActivateSink final : public RE::BSTEventSink<RE::TESActivateEvent>
		{
		public:
			static ActivateSink* Get()
			{
				static ActivateSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESActivateEvent* a_event, RE::BSTEventSource<RE::TESActivateEvent>*) override
			{
				auto* what = a_event ? a_event->objectActivated.get() : nullptr;
				auto* by = a_event ? a_event->actionRef.get() : nullptr;
				auto* base = what ? what->GetBaseObject() : nullptr;
				auto* furniture = base ? base->As<RE::TESFurniture>() : nullptr;
				if (!by || !by->IsPlayerRef() || !furniture) {
					return RE::BSEventNotifyControl::kContinue;
				}
				const auto type = static_cast<std::uint8_t>(furniture->workBenchData.benchType.get());
				if (type == 0) {
					return RE::BSEventNotifyControl::kContinue;
				}
				bench = type;
				++seq;
				sinceActivated = 0.0f;
				menuSeen = false;
				logger::info("workbench: {} (kind {}): Minecraft's things lent", furniture->GetName(), type);
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// The workbench's menu opening and closing (the player update stops while it's open: it pauses the game).
		class MenuSink final : public RE::BSTEventSink<RE::MenuOpenCloseEvent>
		{
		public:
			static MenuSink* Get()
			{
				static MenuSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::MenuOpenCloseEvent* a_event, RE::BSTEventSource<RE::MenuOpenCloseEvent>*) override
			{
				if (!a_event || a_event->menuName != RE::CraftingMenu::MENU_NAME || bench == 0) {
					return RE::BSEventNotifyControl::kContinue;
				}
				if (a_event->opening) {
					menuSeen = true;
				} else {
					logger::info("workbench: its menu closed");
					bench = 0;
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		std::atomic<bool> taskQueued{ false };

		// The lists the menu made when it opened, made again with what was lent since (on the UI thread).
		void RefreshLists()
		{
			SKSE::GetTaskInterface()->AddUITask([] {
				auto* ui = RE::UI::GetSingleton();
				auto  menu = ui ? ui->GetMenu<RE::CraftingMenu>() : nullptr;
				if (auto* sub = menu ? menu->GetCraftingSubMenu() : nullptr) {
					sub->UpdateConstructibleList();
					sub->UpdateSmithingList();
					logger::info("workbench: its lists made again with what Minecraft lent");
				}
			});
		}
	}

	void Install()
	{
		static bool installed = false;
		if (!installed) {
			auto* events = RE::ScriptEventSourceHolder::GetSingleton();
			auto* ui = RE::UI::GetSingleton();
			if (events && ui) {
				events->AddEventSink<RE::TESActivateEvent>(ActivateSink::Get());
				ui->AddEventSink<RE::MenuOpenCloseEvent>(MenuSink::Get());
				installed = true;
			}
		}
	}

	// Present, every frame: while the workbench's menu has the game paused, what Minecraft lends late (the
	// player update that handles it has stopped) is handled on the main thread all the same.
	void WhilePaused()
	{
		auto* ui = RE::UI::GetSingleton();
		if (bench == 0 || !ui || !ui->GameIsPaused() || taskQueued.exchange(true)) {
			return;
		}
		SKSE::GetTaskInterface()->AddTask([] {
			taskQueued = false;
			if (auto* player = RE::PlayerCharacter::GetSingleton(); player && bench != 0) {
				SkyInventory::ProcessStoreNow(player);
				if (refresh.exchange(false)) {
					RefreshLists();
				}
			}
		});
	}

	std::uint8_t Kind()
	{
		return bench;
	}

	std::uint32_t Seq()
	{
		return seq;
	}

	void Given()
	{
		refresh = true;
	}

	void PerFrame(float a_delta)
	{
		Install();
		if (bench == 0) {
			return;
		}
		auto*      ui = RE::UI::GetSingleton();
		const bool open = ui && ui->IsMenuOpen(RE::CraftingMenu::MENU_NAME);
		sinceActivated += a_delta;
		if (open) {
			menuSeen = true;
		}
		// Done: its menu came and went (MenuSink, mostly), or never came (walked away, a menu-less bench).
		if ((menuSeen && !open) || (!menuSeen && sinceActivated > 8.0f)) {
			logger::info("workbench: left (its menu {})", menuSeen ? "closed" : "never opened");
			bench = 0;
			return;
		}
		if (open && refresh.exchange(false)) {
			RefreshLists();
		}
	}

	// Classifies a magic effect the way Minecraft acts it out (SkyEnchanting on the Minecraft side).
	const char* EffectKind(RE::EffectSetting* a_effect)
	{
		if (!a_effect) {
			return nullptr;
		}
		using A = RE::EffectArchetypes::ArchetypeID;
		const auto av = a_effect->data.primaryAV;
		const bool hurts = a_effect->IsDetrimental();
		switch (a_effect->GetArchetype()) {
		case A::kParalysis:
			return "paralyze";
		case A::kDemoralize:
			return "fear";
		case A::kFrenzy:
			return "frenzy";
		case A::kCalm:
			return "calm";
		case A::kSoulTrap:
			return "soultrap";
		case A::kBanish:
			return "banish";
		case A::kTurnUndead:
			return "turn";
		case A::kAbsorb:
			return av == RE::ActorValue::kHealth ? "absorb_health" : av == RE::ActorValue::kStamina ? "absorb_stamina" : "absorb_magicka";
		case A::kValueModifier:
		case A::kPeakValueModifier:
		case A::kDualValueModifier:
			if (hurts) {
				if (av == RE::ActorValue::kHealth) {
					const auto resist = a_effect->data.resistVariable;
					return resist == RE::ActorValue::kResistFire ? "fire" : resist == RE::ActorValue::kResistFrost ? "frost" : resist == RE::ActorValue::kResistShock ? "shock" : "damage";
				}
				return av == RE::ActorValue::kStamina ? "stamina" : av == RE::ActorValue::kMagicka ? "magicka" : "damage";
			}
			switch (av) {
			case RE::ActorValue::kHealth:
				return "fortify_health";
			case RE::ActorValue::kStamina:
				return "fortify_stamina";
			case RE::ActorValue::kMagicka:
				return "fortify_magicka";
			case RE::ActorValue::kHealRate:
				return "regen";
			case RE::ActorValue::kResistFire:
				return "resist_fire";
			case RE::ActorValue::kResistFrost:
				return "resist_frost";
			case RE::ActorValue::kResistShock:
				return "resist_shock";
			case RE::ActorValue::kResistMagic:
				return "resist_magic";
			case RE::ActorValue::kWaterBreathing:
				return "water_breathing";
			case RE::ActorValue::kSpeedMult:
				return "speed";
			case RE::ActorValue::kOneHanded:
			case RE::ActorValue::kTwoHanded:
			case RE::ActorValue::kArchery:
				return "fortify_attack";
			case RE::ActorValue::kBlock:
			case RE::ActorValue::kHeavyArmor:
			case RE::ActorValue::kLightArmor:
				return "fortify_armor";
			case RE::ActorValue::kSneak:
				return "sneak";
			case RE::ActorValue::kCarryWeight:
				return "carry";
			default:
				return "fortify";
			}
		default:
			return nullptr;
		}
	}

	void EnchantHit(RE::PlayerCharacter* a_player, RE::FormID a_target, RE::FormID a_enchantment)
	{
		auto* target = RE::TESForm::LookupByID<RE::Actor>(a_target);
		auto* enchantment = RE::TESForm::LookupByID<RE::EnchantmentItem>(a_enchantment);
		auto* caster = a_player->GetMagicCaster(RE::MagicSystem::CastingSource::kInstant);
		if (!target || !enchantment || !caster || target->IsDead()) {
			return;
		}
		caster->CastSpellImmediate(enchantment, false, target, 1.0f, false, 0.0f, a_player);
		static int logged = 0;
		if (logged++ < 10) {
			logger::info("workbench: a Minecraft weapon's {} on {}", enchantment->GetName(), target->GetName());
		}
	}
}
