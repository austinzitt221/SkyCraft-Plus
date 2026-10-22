// SkyCraft+ : Skyrim's inventory in Minecraft's
#include "Game.h"

// Skyrim's menus can't be open alongside Minecraft's inventory, so Minecraft draws Skyrim's items,
// spells, shouts and powers in screens of its own (SkyItemsScreen on the Minecraft side) from the
// list kept here in shared memory (proto::SkyInventory), and sends each click back (kEvSkyItem):
// equip in a hand, put on, drink, eat, read, make a shout or power the voice's. Equipping goes
// through ActorEquipManager as Skyrim's own menu does, so hand mixing and the rest follow as usual.
namespace skycraft::SkyInventory
{
	namespace
	{
		std::vector<proto::SkyItem> items;
		// SkyCraft+ : Skyrim items in Minecraft's containers whose pictures Minecraft asked for, and when.
		std::unordered_map<RE::FormID, std::uint64_t> pictures;
		// SkyCraft+ : Skyrim blocks' items. Pictures of textures Minecraft asked for (kStoreTexturePicture): key -> when.
		std::unordered_map<std::uint32_t, std::uint64_t> texturePictures;
		float                       timer = 0.0f;
		bool                        dirty = true;
		std::uint64_t               lastHash = 0;
		std::uint32_t               generation = 0;

		void SetName(proto::SkyItem& a_item, const char* a_name)
		{
			std::snprintf(a_item.name, sizeof(a_item.name), "%s", a_name ? a_name : "");
		}

		// Where it comes from, for Minecraft's shortcuts: the plugin and the id within it (made in
		// game: no plugin, and the form id, which the save keeps).
		void SetSource(proto::SkyItem& a_item, const RE::TESForm* a_form)
		{
			auto* file = (a_form->GetFormID() >> 24) != 0xFF ? a_form->GetFile(0) : nullptr;
			if (!file) {
				a_item.localId = a_form->GetFormID();
				return;
			}
			a_item.localId = a_form->GetFormID() & (file->IsLight() ? 0xFFFu : 0xFFFFFFu);
			const auto name = file->GetFilename();
			std::snprintf(a_item.plugin, sizeof(a_item.plugin), "%.*s", static_cast<int>(name.size()), name.data());
		}

		bool Contains(std::string_view a_text, std::string_view a_part)
		{
			return std::search(a_text.begin(), a_text.end(), a_part.begin(), a_part.end(), [](char a, char b) {
				return std::tolower(static_cast<unsigned char>(a)) == std::tolower(static_cast<unsigned char>(b));
			}) != a_text.end();
		}

		// What a weapon or piece of apparel is made of, from its material keyword (WeapMaterialIron,
		// ArmorMaterialDaedric, DLC2ArmorMaterialStalhrimHeavy...).
		std::uint8_t MaterialOf(RE::BGSKeywordForm* a_keywords)
		{
			if (!a_keywords) {
				return proto::kSkyMatNone;
			}
			static constexpr std::pair<std::string_view, proto::SkyMaterial> kMaterials[] = {
				{ "Daedric", proto::kSkyMatDaedric }, { "Dragon", proto::kSkyMatDragon }, { "Ebony", proto::kSkyMatEbony },
				{ "Stalhrim", proto::kSkyMatStalhrim }, { "Glass", proto::kSkyMatGlass }, { "Elven", proto::kSkyMatElven },
				{ "Dwarven", proto::kSkyMatDwarven }, { "Orcish", proto::kSkyMatOrcish }, { "Silver", proto::kSkyMatSilver },
				{ "Gold", proto::kSkyMatGold }, { "Steel", proto::kSkyMatSteel }, { "Imperial", proto::kSkyMatSteel },
				{ "Scaled", proto::kSkyMatSteel }, { "Iron", proto::kSkyMatIron }, { "Draugr", proto::kSkyMatIron },
				{ "Chitin", proto::kSkyMatElven }, { "Falmer", proto::kSkyMatElven }, { "Leather", proto::kSkyMatLeather },
				{ "Hide", proto::kSkyMatLeather }, { "Fur", proto::kSkyMatLeather }, { "Stormcloak", proto::kSkyMatLeather },
				{ "Forsworn", proto::kSkyMatWood }, { "Wood", proto::kSkyMatWood }, { "Chain", proto::kSkyMatChain },
			};
			for (std::uint32_t i = 0; i < a_keywords->numKeywords; ++i) {
				auto* keyword = a_keywords->keywords[i];
				const std::string_view id = keyword ? keyword->GetFormEditorID() : "";
				if (!Contains(id, "Material")) {
					continue;
				}
				for (const auto& [part, material] : kMaterials) {
					if (Contains(id, part)) {
						return material;
					}
				}
			}
			return proto::kSkyMatNone;
		}

		std::uint8_t WeaponType(RE::TESObjectWEAP* a_weapon)
		{
			using T = RE::WEAPON_TYPE;
			switch (a_weapon->GetWeaponType()) {
			case T::kOneHandSword:
				return proto::kSkySword;
			case T::kOneHandDagger:
				return proto::kSkyDagger;
			case T::kOneHandAxe:
				{
					const std::string_view name = a_weapon->GetName();
					if (Contains(name, "pickaxe") || Contains(name, "pick axe")) {
						return proto::kSkyPickaxe;
					}
					if (Contains(name, "woodcutter")) {
						return proto::kSkyWoodAxe;
					}
					return proto::kSkyWarAxe;
				}
			case T::kOneHandMace:
				return proto::kSkyMace;
			case T::kTwoHandSword:
				return proto::kSkyGreatsword;
			case T::kTwoHandAxe:
				return a_weapon->HasKeywordString("WeapTypeWarhammer") ? proto::kSkyWarhammer : proto::kSkyBattleaxe;
			case T::kBow:
				return proto::kSkyBow;
			case T::kCrossbow:
				return proto::kSkyCrossbow;
			case T::kStaff:
				return proto::kSkyStaff;
			default:
				return proto::kSkyHand;
			}
		}

		std::uint8_t ApparelSlot(RE::TESObjectARMO* a_armor)
		{
			using S = RE::BGSBipedObjectForm::BipedObjectSlot;
			const auto slots = a_armor->GetSlotMask();
			if (slots.any(S::kShield)) {
				return proto::kSkyShield;
			}
			if (slots.any(S::kBody)) {
				return proto::kSkyBody;
			}
			if (slots.any(S::kHead, S::kHair)) {
				return proto::kSkyHead;
			}
			if (slots.any(S::kCirclet)) {
				return proto::kSkyCirclet;
			}
			if (slots.any(S::kHands)) {
				return proto::kSkyHands;
			}
			if (slots.any(S::kFeet)) {
				return proto::kSkyFeet;
			}
			if (slots.any(S::kRing)) {
				return proto::kSkyRing;
			}
			if (slots.any(S::kAmulet)) {
				return proto::kSkyAmulet;
			}
			return proto::kSkyBody;
		}

		// The effect a spell, potion or scroll is mostly about.
		RE::Effect* MainEffect(RE::MagicItem* a_magic)
		{
			auto* effect = a_magic ? a_magic->GetCostliestEffectItem() : nullptr;
			return effect && effect->baseEffect ? effect : nullptr;
		}

		std::uint8_t ElementOf(RE::Effect* a_effect)
		{
			switch (a_effect ? a_effect->baseEffect->data.resistVariable : RE::ActorValue::kNone) {
			case RE::ActorValue::kResistFire:
				return proto::kSkyFire;
			case RE::ActorValue::kResistFrost:
				return proto::kSkyFrost;
			case RE::ActorValue::kResistShock:
				return proto::kSkyShock;
			default:
				return 0;
			}
		}

		void AddItem(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_object, std::int32_t a_count, RE::InventoryEntryData* a_entry, std::int32_t& a_gold)
		{
			if (a_object->GetFormID() == 0xF) {  // Gold
				a_gold += a_count;
				return;
			}
			proto::SkyItem item{};
			item.formId = a_object->GetFormID();
			item.count = a_count;
			const char* name = a_entry ? a_entry->GetDisplayName() : nullptr;
			SetName(item, name && *name ? name : a_object->GetName());
			SetSource(item, a_object);
			if (a_entry) {
				item.weight = a_entry->GetWeight();
				item.value = a_entry->GetValue();
				item.flags |= a_entry->IsEnchanted() ? proto::kSkyItemEnchanted : 0;
				item.flags |= a_entry->IsFavorited() ? proto::kSkyItemFavorite : 0;
				item.flags |= a_entry->IsQuestObject() ? proto::kSkyItemQuest : 0;
				item.flags |= !a_entry->IsOwnedBy(a_player, true) ? proto::kSkyItemStolen : 0;
			} else {
				item.weight = a_object->GetWeight();
				item.value = a_object->GetGoldValue();
			}
			const bool right = a_entry && a_entry->IsWorn(false);
			const bool left = a_entry && a_entry->IsWorn(true);

			switch (a_object->GetFormType()) {
			case RE::FormType::Weapon:
				{
					auto* weapon = a_object->As<RE::TESObjectWEAP>();
					item.kind = proto::kSkyWeapon;
					item.sub = WeaponType(weapon);
					item.material = MaterialOf(weapon);
					item.stat = static_cast<float>(weapon->GetAttackDamage());
					item.flags |= weapon->IsTwoHandedSword() || weapon->IsTwoHandedAxe() || weapon->IsBow() || weapon->IsCrossbow() ? proto::kSkyItemTwoHanded : 0;
					item.flags |= (right ? proto::kSkyItemRightHand : 0) | (left ? proto::kSkyItemLeftHand : 0);
					break;
				}
			case RE::FormType::Armor:
				{
					auto* armor = a_object->As<RE::TESObjectARMO>();
					item.kind = proto::kSkyApparel;
					item.sub = ApparelSlot(armor);
					item.material = MaterialOf(armor);
					item.stat = armor->GetArmorRating();
					item.flags |= armor->IsHeavyArmor() ? proto::kSkyItemHeavy : armor->IsLightArmor() ? proto::kSkyItemLight : 0;
					item.flags |= right || left ? proto::kSkyItemWorn : 0;
					break;
				}
			case RE::FormType::AlchemyItem:
				{
					auto* potion = a_object->As<RE::AlchemyItem>();
					auto* effect = MainEffect(potion);
					item.kind = potion->IsFood() ? proto::kSkyFood : proto::kSkyPotion;
					if (potion->IsPoison()) {
						item.sub = proto::kSkyPoison;
					} else if (effect) {
						switch (effect->baseEffect->data.primaryAV) {
						case RE::ActorValue::kHealth:
							item.sub = proto::kSkyPotionHealth;
							break;
						case RE::ActorValue::kMagicka:
							item.sub = proto::kSkyPotionMagicka;
							break;
						case RE::ActorValue::kStamina:
							item.sub = proto::kSkyPotionStamina;
							break;
						default:
							break;
						}
					}
					if (effect) {
						item.stat = effect->GetMagnitude();
						item.stat2 = static_cast<float>(effect->GetDuration());
					}
					break;
				}
			case RE::FormType::Ingredient:
				item.kind = proto::kSkyIngredient;
				break;
			case RE::FormType::Book:
				{
					auto* book = a_object->As<RE::TESObjectBOOK>();
					item.kind = proto::kSkyBook;
					item.sub = book->TeachesSpell() ? 1 : book->IsNoteScroll() ? 2 : 0;
					item.flags |= book->IsRead() ? proto::kSkyItemRead : 0;
					break;
				}
			case RE::FormType::KeyMaster:
				item.kind = proto::kSkyKey;
				break;
			case RE::FormType::SoulGem:
				item.kind = proto::kSkyMisc;
				item.sub = 1;
				item.flags |= a_entry && a_entry->GetSoulLevel() != RE::SOUL_LEVEL::kNone ? proto::kSkyItemFilled : 0;
				item.stat = a_entry ? static_cast<float>(a_entry->GetSoulLevel()) : 0.0f;  // SkyCraft+ : its soul (recharging Minecraft weapons)
				break;
			case RE::FormType::Light:
				item.kind = proto::kSkyMisc;
				item.sub = 2;
				item.flags |= (right ? proto::kSkyItemRightHand : 0) | (left ? proto::kSkyItemLeftHand : 0);
				break;
			case RE::FormType::Ammo:
				item.kind = proto::kSkyAmmo;
				item.sub = a_object->As<RE::TESAmmo>()->IsBolt() ? 1 : 0;
				item.stat = a_object->As<RE::TESAmmo>()->GetRuntimeData().data.damage;
				item.flags |= right || left ? proto::kSkyItemWorn : 0;
				break;
			case RE::FormType::Scroll:
				item.kind = proto::kSkyScroll;
				item.flags |= (right ? proto::kSkyItemRightHand : 0) | (left ? proto::kSkyItemLeftHand : 0);
				break;
			case RE::FormType::Misc:
				item.kind = proto::kSkyMisc;
				item.sub = a_object->GetFormID() == 0xA ? 3 : 0;  // Lockpick
				break;
			default:
				return;
			}
			if (Crops::Plantable(a_object)) {
				item.flags |= proto::kSkyItemPlantable;  // SkyCraft+ : grows on Minecraft farmland
			}
			items.push_back(item);
		}

		void AddSpell(RE::PlayerCharacter* a_player, RE::SpellItem* a_spell, std::unordered_set<RE::FormID>& a_seen)
		{
			if (!a_spell || !a_seen.insert(a_spell->GetFormID()).second) {
				return;
			}
			using T = RE::MagicSystem::SpellType;
			const auto type = a_spell->GetSpellType();
			if (type != T::kSpell && type != T::kPower && type != T::kLesserPower) {
				return;
			}
			proto::SkyItem item{};
			item.formId = a_spell->GetFormID();
			item.count = 1;
			SetName(item, a_spell->GetName());
			SetSource(item, a_spell);
			auto* effect = MainEffect(a_spell);
			if (type == T::kSpell) {
				item.kind = proto::kSkySpell;
				item.sub = 0xFF;
				switch (effect ? effect->baseEffect->GetMagickSkill() : RE::ActorValue::kNone) {
				case RE::ActorValue::kAlteration:
					item.sub = proto::kSkyAlteration;
					break;
				case RE::ActorValue::kConjuration:
					item.sub = proto::kSkyConjuration;
					break;
				case RE::ActorValue::kDestruction:
					item.sub = proto::kSkyDestruction;
					break;
				case RE::ActorValue::kIllusion:
					item.sub = proto::kSkyIllusion;
					break;
				case RE::ActorValue::kRestoration:
					item.sub = proto::kSkyRestoration;
					break;
				default:
					break;
				}
				item.material = ElementOf(effect);
				item.stat = a_spell->CalculateMagickaCost(a_player);
			} else {
				item.kind = proto::kSkyPower;
				item.sub = type == T::kLesserPower ? 1 : 0;
			}
			if (effect) {
				item.stat2 = static_cast<float>(effect->GetDuration());
			}
			const auto& runtime = a_player->GetActorRuntimeData();
			item.flags |= runtime.selectedSpells[RE::Actor::SlotTypes::kRightHand] == a_spell ? proto::kSkyItemRightHand : 0;
			item.flags |= runtime.selectedSpells[RE::Actor::SlotTypes::kLeftHand] == a_spell ? proto::kSkyItemLeftHand : 0;
			item.flags |= runtime.selectedPower == a_spell ? proto::kSkyItemVoice : 0;
			items.push_back(item);
		}

		void AddShout(RE::PlayerCharacter* a_player, RE::TESShout* a_shout, std::unordered_set<RE::FormID>& a_seen)
		{
			if (!a_shout || !a_seen.insert(a_shout->GetFormID()).second) {
				return;
			}
			proto::SkyItem item{};
			item.formId = a_shout->GetFormID();
			item.kind = proto::kSkyShout;
			SetName(item, a_shout->GetName());
			SetSource(item, a_shout);
			for (const auto& variation : a_shout->variations) {
				if (variation.word && (variation.word->formFlags & RE::TESForm::RecordFlags::kUnlocked) != 0) {
					++item.count;
					item.stat2 = variation.recoveryTime;
				}
			}
			item.flags |= a_player->GetActorRuntimeData().selectedPower == a_shout ? proto::kSkyItemVoice : 0;
			items.push_back(item);
		}

		void AddSpellList(RE::PlayerCharacter* a_player, RE::TESSpellList::SpellData* a_list, std::unordered_set<RE::FormID>& a_seen)
		{
			if (!a_list) {
				return;
			}
			for (std::uint32_t i = 0; a_list->spells && i < a_list->numSpells; ++i) {
				AddSpell(a_player, a_list->spells[i], a_seen);
			}
			for (std::uint32_t i = 0; a_list->shouts && i < a_list->numShouts; ++i) {
				AddShout(a_player, a_list->shouts[i], a_seen);
			}
		}

		// SkyCraft+ : Skyrim's creatures as one of Minecraft's spawn eggs' looks (SkyItem::material of a
		// kSkyActor), from its race: 0 a person's (a villager's) or none matched.
		std::uint8_t EggLook(RE::TESRace* a_race)
		{
			if (!a_race) {
				return 0;
			}
			const std::string id = std::string(a_race->GetFormEditorID()) + " " + (a_race->GetName() ? a_race->GetName() : "");
			static constexpr std::pair<std::string_view, std::uint8_t> kLooks[] = {
				{ "Werewolf", 14 }, { "Wolf", 1 }, { "Dog", 1 }, { "Husky", 1 }, { "Bear", 2 }, { "Spider", 3 }, { "Draugr", 4 }, { "Skeleton", 5 },
				{ "Chicken", 6 }, { "Cow", 7 }, { "Goat", 8 }, { "Deer", 9 }, { "Elk", 9 }, { "Horse", 10 }, { "Fox", 11 }, { "Hare", 12 },
				{ "Rabbit", 12 }, { "Mudcrab", 13 }, { "Troll", 14 }, { "Giant", 15 }, { "Mammoth", 15 }, { "Atronach", 16 }, { "Dragon", 17 },
				{ "Skeever", 18 }, { "Slaughterfish", 19 }, { "Horker", 20 }, { "Sabre", 21 }, { "Cat", 21 }, { "Spriggan", 22 }, { "Wisp", 23 },
				{ "Falmer", 24 }, { "Chaurus", 25 }, { "Vampire", 26 }, { "Dwarven", 27 }, { "Sphere", 27 }, { "Centurion", 27 }, { "Hagraven", 28 },
				{ "Ghost", 23 }, { "Wraith", 23 }, { "Netch", 29 }, { "Riekling", 24 }, { "Ash", 4 }, { "Gargoyle", 15 }, { "Seeker", 23 },
			};
			for (const auto& [part, look] : kLooks) {
				if (Contains(id, part)) {
					return look;
				}
			}
			return 0;
		}

		// SkyCraft+ : every Skyrim thing, for Minecraft's creative menu (proto::SkyCatalog). Once per
		// session, a game first loaded: everything a player could carry (by name: one of each), every
		// spell a tome teaches, every shout, and every named person and creature.
		void WriteCatalog(RE::PlayerCharacter* a_player)
		{
			auto* handler = RE::TESDataHandler::GetSingleton();
			if (!handler) {
				return;
			}
			std::vector<proto::SkyItem> mine;
			mine.swap(items);  // (AddItem and the rest fill items)
			std::set<std::pair<std::uint8_t, std::string>> named;
			std::int32_t gold = 0;
			auto object = [&](RE::TESBoundObject* a_object) {
				const char* name = a_object ? a_object->GetName() : nullptr;
				if (!name || !*name || !a_object->GetPlayable() || items.size() >= proto::kMaxCatalog) {
					return;
				}
				const auto before = items.size();
				AddItem(a_player, a_object, 1, nullptr, gold);
				if (items.size() > before && !named.insert({ items.back().kind, items.back().name }).second) {
					items.pop_back();  // (a copy by another name in the plugins: the same thing)
				}
			};
			auto all = [&]<class T>(std::type_identity<T>) {
				for (auto* form : handler->GetFormArray<T>()) {
					object(form);
				}
			};
			all(std::type_identity<RE::TESObjectWEAP>{});
			all(std::type_identity<RE::TESObjectARMO>{});
			all(std::type_identity<RE::TESAmmo>{});
			all(std::type_identity<RE::AlchemyItem>{});
			all(std::type_identity<RE::IngredientItem>{});
			all(std::type_identity<RE::TESObjectBOOK>{});
			all(std::type_identity<RE::ScrollItem>{});
			all(std::type_identity<RE::TESKey>{});
			all(std::type_identity<RE::TESSoulGem>{});
			all(std::type_identity<RE::TESObjectMISC>{});
			for (auto* light : handler->GetFormArray<RE::TESObjectLIGH>()) {
				if (light && light->data.flags.any(RE::TES_LIGHT_FLAGS::kCanCarry)) {
					object(light);
				}
			}
			if (auto* coin = RE::TESForm::LookupByID<RE::TESObjectMISC>(0xF)) {  // Gold (AddItem counts it apart)
				proto::SkyItem item{};
				item.formId = coin->GetFormID();
				item.kind = proto::kSkyMisc;
				item.count = 1;
				item.value = 1;
				SetName(item, coin->GetName());
				SetSource(item, coin);
				items.push_back(item);
			}
			std::unordered_set<RE::FormID> seen;
			for (auto* book : handler->GetFormArray<RE::TESObjectBOOK>()) {
				if (book && book->TeachesSpell() && items.size() < proto::kMaxCatalog) {
					AddSpell(a_player, book->GetSpell(), seen);
				}
			}
			for (auto* shout : handler->GetFormArray<RE::TESShout>()) {
				const char* name = shout ? shout->GetName() : nullptr;
				if (name && *name && shout->variations[0].word && named.insert({ proto::kSkyShout, name }).second && items.size() < proto::kMaxCatalog) {
					AddShout(a_player, shout, seen);
					items.back().count = 3;
					items.back().flags = 0;
				}
			}
			for (auto& item : items) {
				item.flags &= ~(proto::kSkyItemRightHand | proto::kSkyItemLeftHand | proto::kSkyItemVoice | proto::kSkyItemWorn);
			}
			// SkyCraft+ : quest people. Anyone a quest alias names (a unique actor, or a forced reference's base), or
			// essential or protected: another of them can break that quest (the egg's tooltip warns).
			std::unordered_set<RE::TESNPC*> questPeople;
			for (auto* quest : handler->GetFormArray<RE::TESQuest>()) {
				if (!quest) {
					continue;
				}
				for (auto* alias : quest->aliases) {
					if (!alias || alias->GetVMTypeID() != RE::BGSRefAlias::VMTYPEID) {
						continue;
					}
					auto* ref = static_cast<RE::BGSRefAlias*>(alias);
					if (ref->fillType == RE::BGSBaseAlias::FILL_TYPE::kUniqueActor && ref->fillData.uniqueActor.uniqueActor) {
						questPeople.insert(ref->fillData.uniqueActor.uniqueActor);
					} else if (ref->fillType == RE::BGSBaseAlias::FILL_TYPE::kForced) {
						auto forced = ref->fillData.forced.forcedRef.get();
						if (auto* base = forced ? forced->GetBaseObject() : nullptr; base && base->Is(RE::FormType::NPC)) {
							questPeople.insert(static_cast<RE::TESNPC*>(base));
						}
					}
				}
			}
			int people = 0, creatures = 0, quests = 0;
			for (auto* npc : handler->GetFormArray<RE::TESNPC>()) {
				const char* name = npc ? npc->GetName() : nullptr;
				if (!name || !*name || npc->GetFormID() == 0x7 || items.size() >= proto::kMaxCatalog || !named.insert({ proto::kSkyActor, name }).second) {
					continue;
				}
				auto*           race = npc->GetRace();
				const bool      person = race && race->data.flags.any(RE::RACE_DATA::Flag::kPlayable);
				proto::SkyItem item{};
				item.formId = npc->GetFormID();
				item.kind = proto::kSkyActor;
				item.sub = person ? 0 : 1;
				item.material = person ? 0 : EggLook(race);
				item.count = 1;
				if (questPeople.contains(npc) || npc->IsEssential() || npc->IsProtected()) {
					item.flags |= proto::kSkyItemQuest;  // SkyCraft+
					++quests;
				}
				SetName(item, name);
				SetSource(item, npc);
				items.push_back(item);
				++(person ? people : creatures);
			}
			GeoTextures::Catalog(items);  // SkyCraft+ : Skyrim geo for the creative menu
			Link::Get().WriteSkyCatalog(items.data(), static_cast<std::uint32_t>(items.size()), 1);
			logger::info("Skyrim catalog: {} things for Minecraft's creative menu ({} people, {} creatures; {} in quests)", items.size(), people, creatures, quests);
			items.swap(mine);
		}

		void Write(RE::PlayerCharacter* a_player)
		{
			items.clear();
			std::int32_t gold = 0;
			for (auto& [object, data] : a_player->GetInventory()) {
				auto& [count, entry] = data;
				if (object && count > 0 && object->GetPlayable() && items.size() < proto::kMaxSkyItems) {
					AddItem(a_player, object, count, entry.get(), gold);
				}
			}
			std::unordered_set<RE::FormID> seen;
			if (auto* base = a_player->GetActorBase()) {
				AddSpellList(a_player, base->actorEffects, seen);
			}
			if (auto* race = a_player->GetRace()) {
				AddSpellList(a_player, race->actorEffects, seen);
			}
			for (auto* spell : a_player->GetActorRuntimeData().addedSpells) {
				AddSpell(a_player, spell, seen);
			}
			if (items.size() > proto::kMaxSkyItems) {
				items.resize(proto::kMaxSkyItems);
			}
			const float carried = a_player->GetTotalCarryWeight();
			const float carryMax = a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kCarryWeight);

			// The generation changes only when something did (Minecraft rebuilds its screens then).
			std::uint64_t hash = 1469598103934665603ull;
			auto mix = [&](const void* a_bytes, std::size_t a_size) {
				for (std::size_t i = 0; i < a_size; ++i) {
					hash = (hash ^ static_cast<const std::uint8_t*>(a_bytes)[i]) * 1099511628211ull;
				}
			};
			mix(items.data(), items.size() * sizeof(proto::SkyItem));
			mix(&gold, sizeof(gold));
			mix(&carried, sizeof(carried));
			mix(&carryMax, sizeof(carryMax));
			if (hash != lastHash) {
				lastHash = hash;
				++generation;
			}
			// SkyCraft+ : and the pictures of Skyrim items in Minecraft's containers on screen (kStorePicture).
			std::vector<proto::SkyItem> icons = items;
			std::vector<std::uint64_t>  askedAt(items.size(), 0);  // SkyCraft+ : 0, the inventory's: always kept; a picture's, when last asked for
			const auto                  now = GetTickCount64();
			for (auto it = pictures.begin(); it != pictures.end();) {
				auto* form = now - it->second < 60000 ? RE::TESForm::LookupByID(it->first) : nullptr;
				if (!form) {
					it = pictures.erase(it);
					continue;
				}
				proto::SkyItem icon{};
				icon.formId = it->first;
				SetName(icon, form->GetName());
				icons.push_back(icon);
				askedAt.push_back(it->second);
				++it;
			}
			for (auto it = texturePictures.begin(); it != texturePictures.end();) {
				const char* path = now - it->second < 60000 ? IconRender::TexturePath(it->first) : nullptr;
				if (!path) {
					it = texturePictures.erase(it);
					continue;
				}
				proto::SkyItem icon{};
				icon.formId = it->first;
				SetName(icon, path);
				icons.push_back(icon);
				askedAt.push_back(it->second);
				++it;
			}
			IconRender::Want(icons.data(), icons.size(), askedAt.data());
			Link::Get().WriteSkyInventory(items.data(), static_cast<std::uint32_t>(items.size()), generation, gold, carried, carryMax);
		}

		const RE::BGSEquipSlot* Slot(RE::DEFAULT_OBJECTS::DEFAULT_OBJECT a_which)
		{
			auto* defaults = RE::BGSDefaultObjectManager::GetSingleton();
#pragma push_macro("GetObject")
#undef GetObject  // (Windows' GetObjectA)
			return defaults ? defaults->GetObject<RE::BGSEquipSlot>(a_which) : nullptr;
#pragma pop_macro("GetObject")
		}

		// The copy to equip (the one worn, to take it off; else an enchanted or tempered one first),
		// as Skyrim's menu passes its extra data.
		RE::ExtraDataList* ExtraFor(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_object, bool a_worn, bool a_left)
		{
			auto inventory = a_player->GetInventory([a_object](RE::TESBoundObject& a_candidate) { return &a_candidate == a_object; });
			const auto it = inventory.find(a_object);
			if (it == inventory.end() || !it->second.second || !it->second.second->extraLists) {
				return nullptr;
			}
			RE::ExtraDataList* other = nullptr;
			for (auto* list : *it->second.second->extraLists) {
				if (!list) {
					continue;
				}
				const bool worn = list->HasType(a_left ? RE::ExtraDataType::kWornLeft : RE::ExtraDataType::kWorn);
				if (a_worn && worn) {
					return list;
				}
				if (!a_worn && !list->HasType(RE::ExtraDataType::kWorn) && !list->HasType(RE::ExtraDataType::kWornLeft) && !other) {
					other = list;
				}
			}
			return a_worn ? nullptr : other;
		}

		void Notify(const char* a_text)
		{
			RE::SendHUDMessage::ShowHUDMessage(a_text);
		}

		// SkyCraft+ : Skyrim items in Minecraft's containers. Results Minecraft hasn't taken yet (its
		// ring was full) wait here, in order.
		std::deque<proto::SkyStoreResult> unsent;

		proto::SkyStoreResult Refusal(std::uint32_t a_id, std::uint8_t a_status)
		{
			proto::SkyStoreResult result{};
			result.id = a_id;
			result.status = a_status;
			return result;
		}

		// One kind of copy taken out: what Minecraft's stored item will say about it (as the list does).
		proto::SkyStoreResult Taken(RE::PlayerCharacter* a_player, std::uint32_t a_id, RE::TESBoundObject* a_object, RE::InventoryEntryData* a_entry, std::int32_t a_count,
			float a_health, float a_charge, std::int32_t a_soul)
		{
			proto::SkyStoreResult result = Refusal(a_id, proto::kStoreOk);
			result.count = a_count;
			result.health = a_health;
			result.charge = a_charge;
			result.soul = a_soul;
			result.formId = a_object->GetFormID();
			const auto   before = items.size();
			std::int32_t gold = 0;
			AddItem(a_player, a_object, a_count, nullptr, gold);  // (the base name: the list's may be a tempered copy's)
			if (items.size() > before) {
				const auto& item = items.back();
				result.kind = item.kind;
				result.sub = item.sub;
				result.material = item.material;
				result.flags = item.flags & ~(proto::kSkyItemRightHand | proto::kSkyItemLeftHand | proto::kSkyItemWorn | proto::kSkyItemVoice | proto::kSkyItemFavorite);
				result.weight = item.weight;
				result.value = item.value;
				result.stat = item.stat;
				result.stat2 = item.stat2;
				std::memcpy(result.name, item.name, sizeof(result.name));
				result.localId = item.localId;
				std::memcpy(result.plugin, item.plugin, sizeof(result.plugin));
				items.pop_back();
			}
			if (auto* enchantable = a_object->As<RE::TESEnchantableForm>(); enchantable && enchantable->formEnchanting) {
				result.flags |= proto::kSkyItemEnchanted;
			}
			if (a_entry && a_entry->IsQuestObject()) {
				result.flags |= proto::kSkyItemQuest;
			}
			return result;
		}

		// Out of Skyrim's inventory into a Minecraft container: plain copies first, then tempered,
		// charged or filled ones (each kind its own stored item). Quest items, things made in this
		// game and things enchanted by the player stay (they only exist in this save).
		void Take(RE::PlayerCharacter* a_player, const proto::SkyStoreRequest& a_request)
		{
			auto* object = RE::TESForm::LookupByID<RE::TESBoundObject>(a_request.formId);
			if (!object || a_request.count <= 0) {
				unsent.push_back(Refusal(a_request.id, proto::kStoreNone));
				return;
			}
			if ((object->GetFormID() >> 24) == 0xFF) {
				Notify("Things made in this game (your own potions and the like) stay in Skyrim.");
				unsent.push_back(Refusal(a_request.id, proto::kStoreMadeHere));
				return;
			}
			auto       inventory = a_player->GetInventory([object](RE::TESBoundObject& a_candidate) { return &a_candidate == object; });
			const auto it = inventory.find(object);
			if (it == inventory.end() || it->second.first <= 0) {
				unsent.push_back(Refusal(a_request.id, proto::kStoreNone));
				return;
			}
			auto* entry = it->second.second.get();
			if (entry && entry->IsQuestObject()) {
				Notify("Quest items stay in Skyrim.");
				unsent.push_back(Refusal(a_request.id, proto::kStoreQuest));
				return;
			}
			auto* equip = RE::ActorEquipManager::GetSingleton();
			if (equip && object->Is(RE::FormType::Weapon, RE::FormType::Armor, RE::FormType::Ammo, RE::FormType::Light)) {
				for (bool left : { false, true }) {
					if (auto* worn = ExtraFor(a_player, object, true, left)) {
						equip->UnequipObject(a_player, object, worn, 1, nullptr);
					}
				}
			}
			struct Copy
			{
				RE::ExtraDataList* list;
				std::int32_t       count;
				float              health, charge;
				std::int32_t       soul;
			};
			std::vector<Copy> copies;
			std::int32_t      listed = 0;
			bool              enchantedByYou = false;
			if (entry && entry->extraLists) {
				for (auto* list : *entry->extraLists) {
					if (!list) {
						continue;
					}
					const std::int32_t count = std::max(1, list->GetCount());
					listed += count;
					if (list->GetByType<RE::ExtraEnchantment>()) {
						enchantedByYou = true;
						continue;
					}
					const auto* health = list->GetByType<RE::ExtraHealth>();
					const auto* charge = list->GetByType<RE::ExtraCharge>();
					const auto* soul = list->GetByType<RE::ExtraSoul>();
					copies.push_back({ list, count, health ? health->health : 1.0f, charge ? charge->charge : -1.0f, soul ? static_cast<std::int32_t>(soul->soul.get()) : 0 });
				}
			}
			// What goes, worked out before anything is removed (removing changes the lists).
			std::int32_t                                                     left = std::min(a_request.count, it->second.first);
			std::vector<std::pair<RE::ExtraDataList*, proto::SkyStoreResult>> plan;
			if (const std::int32_t plain = std::min(left, it->second.first - listed); plain > 0) {
				plan.emplace_back(nullptr, Taken(a_player, a_request.id, object, entry, plain, 1.0f, -1.0f, 0));
				left -= plain;
			}
			for (const auto& copy : copies) {
				if (left <= 0) {
					break;
				}
				const std::int32_t n = std::min(left, copy.count);
				plan.emplace_back(copy.list, Taken(a_player, a_request.id, object, entry, n, copy.health, copy.charge, copy.soul));
				left -= n;
			}
			if (plan.empty()) {
				if (enchantedByYou) {
					Notify("Things you enchanted yourself stay in Skyrim.");
				}
				unsent.push_back(Refusal(a_request.id, enchantedByYou ? proto::kStoreMadeHere : proto::kStoreNone));
				return;
			}
			for (auto& [list, result] : plan) {
				a_player->RemoveItem(object, result.count, RE::ITEM_REMOVE_REASON::kRemove, list, nullptr);
				logger::info("Skyrim items: {} x{} put in a Minecraft container (tempering {:.2f}, charge {:.0f}, soul {})", result.name, result.count, result.health, result.charge,
					result.soul);
				unsent.push_back(result);
			}
		}

		// Out of a Minecraft container back into Skyrim's inventory, as it went in.
		void Give(RE::PlayerCharacter* a_player, const proto::SkyStoreRequest& a_request)
		{
			const std::string_view plugin(a_request.plugin, strnlen(a_request.plugin, sizeof(a_request.plugin)));
			auto*                  handler = RE::TESDataHandler::GetSingleton();
			// SkyCraft+ : from Minecraft's creative menu, things that aren't items: a dragon soul, a spell
			// (learned), a shout (learned, every word of it unlocked).
			if (plugin == "SkyCraft" && a_request.localId == 1) {
				a_player->AsActorValueOwner()->ModActorValue(RE::ACTOR_VALUE_MODIFIER::kPermanent, RE::ActorValue::kDragonSouls, static_cast<float>(a_request.count));
				logger::info("Skyrim items: {} dragon soul(s) from Minecraft's creative menu (now {:.0f})", a_request.count, a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kDragonSouls));
				Notify(a_request.count > 1 ? "Dragon souls absorbed." : "Dragon soul absorbed.");
				proto::SkyStoreResult result = Refusal(a_request.id, proto::kStoreOk);
				result.count = a_request.count;
				unsent.push_back(result);
				return;
			}
			RE::TESForm*           form = plugin.empty() ? RE::TESForm::LookupByID(a_request.localId) : handler ? handler->LookupForm(a_request.localId, plugin) : nullptr;
			if (auto* spell = form ? form->As<RE::SpellItem>() : nullptr; spell || (form && form->Is(RE::FormType::Shout))) {
				auto* shout = spell ? nullptr : form->As<RE::TESShout>();
				if (spell) {
					a_player->AddSpell(spell);
				} else {
					a_player->AddShout(shout);
					for (auto& variation : shout->variations) {
						if (variation.word) {
							variation.word->formFlags |= RE::TESForm::RecordFlags::kKnown | RE::TESForm::RecordFlags::kUnlocked;
							variation.word->AddChange(RE::TESForm::ChangeFlags::kFlags);  // (kept in the save)
						}
					}
				}
				logger::info("Skyrim items: learned {} from Minecraft's creative menu", form->GetName());
				Notify(std::format("{} learned.", form->GetName()).c_str());
				proto::SkyStoreResult result = Refusal(a_request.id, proto::kStoreOk);
				result.count = 1;
				result.formId = form->GetFormID();
				unsent.push_back(result);
				return;
			}
			auto*                  object = form ? form->As<RE::TESBoundObject>() : nullptr;
			if (!object || a_request.count <= 0) {
				Notify("A Skyrim item from a Minecraft container couldn't be found (is its mod still loaded?).");
				logger::warn("Skyrim items: {:06X} in '{}' from a Minecraft container not found", a_request.localId, plugin);
				unsent.push_back(Refusal(a_request.id, proto::kStoreMissing));
				return;
			}
			const bool tempered = std::fabs(a_request.health - 1.0f) >= 0.0001f;
			if (!tempered && a_request.charge < 0.0f && a_request.soul <= 0) {
				a_player->AddObjectToContainer(object, nullptr, a_request.count, nullptr);
			} else {
				// Each copy as a thing in the world with that data, picked up (a new list can't be made
				// here; picking up keeps a reference's data, as with tempered loot).
				for (std::int32_t i = 0; i < a_request.count; ++i) {
					auto ref = a_player->PlaceObjectAtMe(object, false);
					if (!ref) {
						a_player->AddObjectToContainer(object, nullptr, a_request.count - i, nullptr);
						break;
					}
					auto* list = &ref->extraList;
					if (tempered) {
						list->Add(new RE::ExtraHealth(a_request.health));
					}
					if (a_request.charge >= 0.0f) {
						auto* charge = RE::BSExtraData::Create<RE::ExtraCharge>();
						charge->charge = a_request.charge;
						list->Add(charge);
					}
					if (a_request.soul > 0) {
						list->Add(new RE::ExtraSoul(static_cast<RE::SOUL_LEVEL>(a_request.soul)));
					}
					a_player->PickUpObject(ref.get(), 1, false, false);
				}
			}
			Workbench::Given();  // SkyCraft+ : (lent to a workbench whose menu is open: its lists again)
			logger::info("Skyrim items: {} x{} back from a Minecraft container", object->GetName(), a_request.count);
			proto::SkyStoreResult result = Refusal(a_request.id, proto::kStoreOk);
			result.count = a_request.count;
			result.formId = object->GetFormID();
			unsent.push_back(result);
		}

		// SkyCraft+ : Minecraft's things at Skyrim's workbenches. A Minecraft weapon or piece of armor at
		// Skyrim's enchanter: an iron one of its kind stands in for it, named after it, and comes back out
		// with whatever enchantment it got (the Minecraft one takes that on: SkyEnchanting).
		struct LentGear
		{
			RE::TESBoundObject* base = nullptr;
			std::string         name;
			std::int32_t        enchantedBefore = 0;  // its enchanted copies when lent (a new one is ours)
		};
		std::optional<LentGear> lentGear;

		RE::TESBoundObject* GearBase(std::int32_t a_kind)
		{
			static std::unordered_map<std::int32_t, RE::TESBoundObject*> cache;
			if (auto it = cache.find(a_kind); it != cache.end()) {
				return it->second;
			}
			static constexpr std::pair<std::int32_t, const char*> kNames[] = {
				{ proto::kGearSword, "Iron Sword" }, { proto::kGearAxe, "Iron War Axe" }, { proto::kGearMace, "Iron Mace" }, { proto::kGearPickaxe, "Pickaxe" },
				{ proto::kGearBow, "Long Bow" }, { proto::kGearCrossbow, "Crossbow" }, { proto::kGearHelmet, "Iron Helmet" }, { proto::kGearChest, "Iron Armor" },
				{ proto::kGearLegs, "Iron Gauntlets" }, { proto::kGearBoots, "Iron Boots" }, { proto::kGearShield, "Iron Shield" },
			};
			const char* wanted = nullptr;
			for (const auto& [kind, name] : kNames) {
				if (kind == a_kind) {
					wanted = name;
				}
			}
			RE::TESBoundObject* found = nullptr;
			auto*               handler = RE::TESDataHandler::GetSingleton();
			auto search = [&]<class T>(std::type_identity<T>) {
				if (!handler || !wanted) {
					return;
				}
				for (auto* form : handler->GetFormArray<T>()) {
					if (!found && form && form->GetName() && std::string_view(form->GetName()) == wanted && form->GetPlayable() && !form->formEnchanting) {
						found = form;
					}
				}
			};
			if (a_kind >= proto::kGearHelmet) {
				search(std::type_identity<RE::TESObjectARMO>{});
			} else {
				search(std::type_identity<RE::TESObjectWEAP>{});
			}
			if (!found && a_kind == proto::kGearCrossbow) {
				found = GearBase(proto::kGearBow);  // (no Dawnguard: a bow)
			}
			cache.emplace(a_kind, found);
			logger::info("workbench: Minecraft gear kind {} stands as {}", a_kind, found ? found->GetName() : "nothing");
			return found;
		}

		std::int32_t EnchantedCopies(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_base)
		{
			auto         inventory = a_player->GetInventory([a_base](RE::TESBoundObject& a_candidate) { return &a_candidate == a_base; });
			const auto   it = inventory.find(a_base);
			std::int32_t n = 0;
			if (it != inventory.end() && it->second.second && it->second.second->extraLists) {
				for (auto* list : *it->second.second->extraLists) {
					if (list && list->GetByType<RE::ExtraEnchantment>()) {
						n += std::max(1, list->GetCount());
					}
				}
			}
			return n;
		}

		void LendGear(RE::PlayerCharacter* a_player, const proto::SkyStoreRequest& a_request)
		{
			auto* base = GearBase(a_request.soul);
			if (!base) {
				unsent.push_back(Refusal(a_request.id, proto::kStoreMissing));
				return;
			}
			const std::string name = std::string(a_request.plugin, strnlen(a_request.plugin, sizeof(a_request.plugin))) + " (Minecraft)";
			auto              ref = a_player->PlaceObjectAtMe(base, false);
			if (!ref) {
				unsent.push_back(Refusal(a_request.id, proto::kStoreMissing));
				return;
			}
			ref->extraList.Add(new RE::ExtraTextDisplayData(name.c_str()));
			lentGear = LentGear{ base, name, EnchantedCopies(a_player, base) };
			a_player->PickUpObject(ref.get(), 1, false, false);
			Workbench::Given();
			logger::info("workbench: {} lent to the enchanter as a {}", name, base->GetName());
			proto::SkyStoreResult result = Refusal(a_request.id, proto::kStoreOk);
			result.count = 1;
			result.formId = base->GetFormID();
			unsent.push_back(result);
		}

		void ReturnGear(RE::PlayerCharacter* a_player, const proto::SkyStoreRequest& a_request)
		{
			if (!lentGear) {
				unsent.push_back(Refusal(a_request.id, proto::kStoreNone));
				return;
			}
			const auto gear = *lentGear;
			lentGear.reset();
			auto               inventory = a_player->GetInventory([&gear](RE::TESBoundObject& a_candidate) { return &a_candidate == gear.base; });
			const auto         it = inventory.find(gear.base);
			RE::ExtraDataList* enchanted = nullptr;
			RE::ExtraDataList* plain = nullptr;
			if (it != inventory.end() && it->second.second && it->second.second->extraLists) {
				const bool newOne = EnchantedCopies(a_player, gear.base) > gear.enchantedBefore;
				for (auto* list : *it->second.second->extraLists) {
					if (!list) {
						continue;
					}
					if (newOne && list->GetByType<RE::ExtraEnchantment>()) {
						enchanted = list;  // (the newest is last)
					} else if (auto* shown = list->GetByType<RE::ExtraTextDisplayData>(); shown && shown->displayName.c_str() && gear.name == shown->displayName.c_str()) {
						plain = list;
					}
				}
			}
			proto::SkyStoreResult result = Refusal(a_request.id, enchanted ? proto::kStoreOk : proto::kStoreNone);
			if (enchanted) {
				auto*       extra = enchanted->GetByType<RE::ExtraEnchantment>();
				auto*       enchantment = extra->enchantment;
				const auto* charge = enchanted->GetByType<RE::ExtraCharge>();
				std::string effects;
				if (enchantment) {
					for (auto* effect : enchantment->effects) {
						const char* kind = effect ? Workbench::EffectKind(effect->baseEffect) : nullptr;
						if (kind) {
							std::string one = std::format("{}{} {:.0f} {}", effects.empty() ? "" : ";", kind, effect->GetMagnitude(), effect->GetDuration());
							if (effects.size() + one.size() < sizeof(result.name)) {
								effects += one;
							}
						}
					}
				}
				result.count = 1;
				result.formId = enchantment ? enchantment->GetFormID() : 0;
				result.health = static_cast<float>(extra->charge);
				result.charge = charge ? charge->charge : static_cast<float>(extra->charge);
				result.stat = enchantment ? (enchantment->data.costOverride > 0 ? static_cast<float>(enchantment->data.costOverride) : enchantment->CalculateMagickaCost(a_player)) : 0.0f;
				std::memcpy(result.name, effects.c_str(), std::min(effects.size() + 1, sizeof(result.name)));
				logger::info("workbench: {} came back enchanted: {} (charge {:.0f} of {:.0f}, {:.0f} a hit)", gear.name, effects, result.charge, result.health, result.stat);
			} else {
				logger::info("workbench: {} came back as it went", gear.name);
			}
			if (auto* list = enchanted ? enchanted : plain) {
				a_player->RemoveItem(gear.base, 1, RE::ITEM_REMOVE_REASON::kRemove, list, nullptr);
			} else {
				logger::warn("workbench: {}'s stand-in wasn't found (sold? dropped?)", gear.name);
			}
			unsent.push_back(result);
		}

		// A picture for a stored item: drawn as if it were in the inventory, for a minute after each ask.
		void Picture(const proto::SkyStoreRequest& a_request)
		{
			const std::string_view plugin(a_request.plugin, strnlen(a_request.plugin, sizeof(a_request.plugin)));
			auto*                  handler = RE::TESDataHandler::GetSingleton();
			RE::TESForm*           form = plugin.empty() ? nullptr : handler ? handler->LookupForm(a_request.localId, plugin) : nullptr;
			if (!form || !form->As<RE::TESBoundObject>()) {
				unsent.push_back(Refusal(a_request.id, proto::kStoreMissing));
				return;
			}
			pictures[form->GetFormID()] = GetTickCount64();
			proto::SkyStoreResult result = Refusal(a_request.id, proto::kStoreOk);
			result.formId = form->GetFormID();
			unsent.push_back(result);
		}

		void ProcessStore(RE::PlayerCharacter* a_player)
		{
			auto& link = Link::Get();
			while (!unsent.empty() && link.PushStoreResult(unsent.front())) {
				unsent.pop_front();
			}
			proto::SkyStoreRequest request;
			for (int budget = 8; budget > 0 && unsent.size() < proto::kStoreResults && link.PopStoreRequest(request); --budget) {
				dirty = true;
				if (request.op == proto::kStoreTake) {
					Take(a_player, request);
				} else if (request.op == proto::kStoreGive) {
					Give(a_player, request);
				} else if (request.op == proto::kStorePicture) {
					Picture(request);
				} else if (request.op == proto::kStoreTexturePicture) {
					// SkyCraft+ : Skyrim blocks' items. A picture of a texture (its path in plugin); the result's formId is its key.
					const std::string_view path(request.plugin, strnlen(request.plugin, sizeof(request.plugin)));
					const auto             key = path.empty() ? 0u : IconRender::TextureKey(path, request.health);
					proto::SkyStoreResult  result = Refusal(request.id, key ? proto::kStoreOk : proto::kStoreMissing);
					result.formId = key;
					if (key) {
						texturePictures[key] = GetTickCount64();
					}
					unsent.push_back(result);
				} else if (request.op == proto::kStoreLendGear) {
					LendGear(a_player, request);  // SkyCraft+
				} else if (request.op == proto::kStoreReturnGear) {
					ReturnGear(a_player, request);  // SkyCraft+
				} else if (request.op == proto::kStoreTextureAt) {
					// SkyCraft+ : Skyrim geo. Which texture a dug surface has (the point in health, charge and soul's bits).
					int        kind = 0;
					const auto path = GeoTextures::TextureAt(request.health, request.charge, std::bit_cast<float>(request.soul), kind, request.count);
					proto::SkyStoreResult result = Refusal(request.id, path.empty() ? proto::kStoreNone : proto::kStoreOk);
					result.kind = static_cast<std::uint8_t>(kind);
					std::strncpy(result.name, path.c_str(), sizeof(result.name) - 1);
					unsent.push_back(result);
				}
			}
			while (!unsent.empty() && link.PushStoreResult(unsent.front())) {
				unsent.pop_front();
			}
		}
	}

	void ProcessStoreNow(RE::PlayerCharacter* a_player)
	{
		ProcessStore(a_player);
	}

	void Spawn(RE::PlayerCharacter* a_player, RE::FormID a_base, double a_x, double a_y, double a_z)
	{
		auto* npc = RE::TESForm::LookupByID<RE::TESNPC>(a_base);
		if (!npc) {
			logger::warn("Skyrim catalog: spawn egg for {:08X}: no such actor", a_base);
			return;
		}
		auto ref = a_player->PlaceObjectAtMe(npc, false);
		if (!ref) {
			return;
		}
		auto at = McToSky(a_x, a_y, a_z);
		ref->SetPosition(at);
		ref->data.angle.z = a_player->data.angle.z + RE::NI_PI;  // facing the player
		logger::info("Skyrim catalog: a spawn egg brought {} ({:08X})", npc->GetName(), ref->GetFormID());
	}

	void Apply(RE::PlayerCharacter* a_player, const proto::McEvent& a_event)
	{
		dirty = true;
		auto* ui = RE::UIMessageQueue::GetSingleton();
		if (a_event.flags == proto::kSkyItemMenuStats || a_event.flags == proto::kSkyItemMenuMap) {
			if (ui) {
				ui->AddMessage(a_event.flags == proto::kSkyItemMenuStats ? RE::StatsMenu::MENU_NAME : RE::MapMenu::MENU_NAME, RE::UI_MESSAGE_TYPE::kShow, nullptr);
			}
			return;
		}
		auto* form = RE::TESForm::LookupByID(a_event.formId);
		auto* equip = RE::ActorEquipManager::GetSingleton();
		if (!form || !equip) {
			return;
		}
		const bool left = a_event.flags == proto::kSkyItemLeft || a_event.flags == proto::kSkyItemEquipLeft;
		// A shortcut picked on Minecraft's hotbar: into the hand, never out of it, nothing used.
		const bool equipOnly = a_event.flags == proto::kSkyItemEquipRight || a_event.flags == proto::kSkyItemEquipLeft;
		const auto* handSlot = Slot(left ? RE::DEFAULT_OBJECTS::kLeftHandEquip : RE::DEFAULT_OBJECTS::kRightHandEquip);
		auto&      runtime = a_player->GetActorRuntimeData();

		// A shortcut taken out of a hand's slot in Minecraft: out of that hand, if it's still there.
		if (a_event.flags == proto::kSkyItemUnequipRight || a_event.flags == proto::kSkyItemUnequipLeft) {
			const bool fromLeft = a_event.flags == proto::kSkyItemUnequipLeft;
			if (auto* spell = form->As<RE::SpellItem>(); spell && !form->Is(RE::FormType::Scroll)) {
				if (runtime.selectedSpells[fromLeft ? RE::Actor::SlotTypes::kLeftHand : RE::Actor::SlotTypes::kRightHand] == spell) {
					a_player->DeselectSpell(spell);
					logger::info("Skyrim items: {} taken out of the {} hand (its shortcut left the slot)", spell->GetName(), fromLeft ? "left" : "right");
				}
				return;
			}
			auto* object = form->As<RE::TESBoundObject>();
			if (!object) {
				return;
			}
			if (object->Is(RE::FormType::Armor)) {
				if (auto* worn = ExtraFor(a_player, object, true, false)) {
					equip->UnequipObject(a_player, object, worn, 1, nullptr);
					logger::info("Skyrim items: {} taken off (its shortcut left the slot)", object->GetName());
				}
				return;
			}
			if (a_player->GetEquippedObject(fromLeft) == object) {
				const bool bothHands = a_player->GetEquippedObject(!fromLeft) == object;
				equip->UnequipObject(a_player, object, ExtraFor(a_player, object, true, fromLeft), 1,
					bothHands ? nullptr : Slot(fromLeft ? RE::DEFAULT_OBJECTS::kLeftHandEquip : RE::DEFAULT_OBJECTS::kRightHandEquip));
				logger::info("Skyrim items: {} unequipped (its shortcut left the slot)", object->GetName());
			}
			return;
		}

		if (auto* shout = form->As<RE::TESShout>()) {
			equip->EquipShout(a_player, shout);
			logger::info("Skyrim items: {} is the voice's", shout->GetName());
			return;
		}
		if (auto* spell = form->As<RE::SpellItem>()) {
			using T = RE::MagicSystem::SpellType;
			if (spell->GetSpellType() != T::kSpell) {
				equip->EquipSpell(a_player, spell, Slot(RE::DEFAULT_OBJECTS::kVoiceEquip));
				logger::info("Skyrim items: {} is the voice's", spell->GetName());
			} else if (runtime.selectedSpells[left ? RE::Actor::SlotTypes::kLeftHand : RE::Actor::SlotTypes::kRightHand] == spell) {
				if (equipOnly) {
					return;
				}
				a_player->DeselectSpell(spell);
				logger::info("Skyrim items: {} taken out of the {} hand", spell->GetName(), left ? "left" : "right");
			} else {
				equip->EquipSpell(a_player, spell, handSlot);
				logger::info("Skyrim items: {} in the {} hand", spell->GetName(), left ? "left" : "right");
			}
			return;
		}
		auto* object = form->As<RE::TESBoundObject>();
		if (!object || a_player->GetItemCount(object) <= 0) {
			return;
		}
		if (a_event.flags == proto::kSkyItemConsume) {  // SkyCraft+ : a poison onto a Minecraft weapon
			a_player->RemoveItem(object, 1, RE::ITEM_REMOVE_REASON::kRemove, nullptr, nullptr);
			logger::info("Skyrim items: {} used on Minecraft's weapon", object->GetName());
			return;
		}
		if (a_event.flags == proto::kSkyItemDrop || a_event.flags == proto::kSkyItemDestroy) {  // SkyCraft+ : or destroyed (creative)
			const bool destroy = a_event.flags == proto::kSkyItemDestroy;  // (quest items never sent: Minecraft checks)
			if (object->Is(RE::FormType::Weapon, RE::FormType::Armor, RE::FormType::Ammo, RE::FormType::Light)) {
				for (bool leftHand : { false, true }) {
					if (auto* worn = ExtraFor(a_player, object, true, leftHand)) {
						equip->UnequipObject(a_player, object, worn, 1, nullptr);
					}
				}
			}
			const auto count = a_player->GetItemCount(object);
			a_player->RemoveItem(object, count, destroy ? RE::ITEM_REMOVE_REASON::kRemove : RE::ITEM_REMOVE_REASON::kDropping, nullptr, nullptr);
			logger::info("Skyrim items: {} x{} {}", object->GetName(), count, destroy ? "destroyed (the creative menu's destroy slot)" : "dropped");
			return;
		}
		switch (object->GetFormType()) {
		case RE::FormType::Weapon:
		case RE::FormType::Light:
		case RE::FormType::Scroll:
			{
				const bool twoHanded = object->Is(RE::FormType::Weapon) && WeaponType(object->As<RE::TESObjectWEAP>()) >= proto::kSkyGreatsword &&
				                       WeaponType(object->As<RE::TESObjectWEAP>()) != proto::kSkyStaff && WeaponType(object->As<RE::TESObjectWEAP>()) <= proto::kSkyCrossbow;
				const bool torch = object->Is(RE::FormType::Light);
				const bool inLeft = a_player->GetEquippedObject(true) == object, inRight = a_player->GetEquippedObject(false) == object;
				const bool here = twoHanded ? inRight || inLeft : (torch ? inLeft : left ? inLeft : inRight);
				const auto* slot = twoHanded ? nullptr : torch ? Slot(RE::DEFAULT_OBJECTS::kLeftHandEquip) : handSlot;
				if (here && equipOnly) {
					return;
				}
				if (here) {
					equip->UnequipObject(a_player, object, ExtraFor(a_player, object, true, torch || (!twoHanded && left)), 1, slot);
					logger::info("Skyrim items: {} unequipped", object->GetName());
				} else {
					equip->EquipObject(a_player, object, ExtraFor(a_player, object, false, false), 1, slot);
					logger::info("Skyrim items: {} equipped ({})", object->GetName(), twoHanded ? "both hands" : torch || left ? "left hand" : "right hand");
				}
				return;
			}
		case RE::FormType::Armor:
		case RE::FormType::Ammo:
			{
				auto* worn = ExtraFor(a_player, object, true, false);
				if (worn && equipOnly) {
					return;
				}
				if (worn) {
					equip->UnequipObject(a_player, object, worn, 1, nullptr);
					logger::info("Skyrim items: {} taken off", object->GetName());
				} else {
					equip->EquipObject(a_player, object, ExtraFor(a_player, object, false, false), 1, nullptr);
					logger::info("Skyrim items: {} put on", object->GetName());
				}
				return;
			}
		case RE::FormType::AlchemyItem:
		case RE::FormType::Ingredient:
			if (equipOnly) {
				return;
			}
			equip->EquipObject(a_player, object, nullptr, 1, nullptr);
			logger::info("Skyrim items: {} used", object->GetName());
			return;
		case RE::FormType::Book:
			if (equipOnly) {
				return;
			}
			{
				auto* book = object->As<RE::TESObjectBOOK>();
				if (book->TeachesSpell() || book->TeachesSkill()) {
					auto* spell = book->TeachesSpell() ? book->GetSpell() : nullptr;
					if (spell && a_player->HasSpell(spell)) {
						Notify("You already know that spell.");
						return;
					}
					book->Read(a_player);
					if (spell) {
						a_player->RemoveItem(object, 1, RE::ITEM_REMOVE_REASON::kRemove, nullptr, nullptr);
					}
					logger::info("Skyrim items: {} read", object->GetName());
				} else {
					Notify("Open Skyrim's inventory to read this.");
				}
				return;
			}
		default:

			return;
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, bool a_wanted, float a_delta)
	{
		static bool catalogued = false;  // SkyCraft+ : the creative menu's Skyrim things, once
		if (!catalogued) {
			catalogued = true;
			WriteCatalog(a_player);
		}
		ProcessStore(a_player);  // SkyCraft+
		timer -= a_delta;
		if (!a_wanted) {
			dirty = true;  // fresh the moment Minecraft's inventory opens
			return;
		}
		IconRender::PerFrame();
		if (!dirty && timer > 0.0f) {
			return;
		}
		dirty = false;
		timer = 0.25f;
		Write(a_player);
	}
}
