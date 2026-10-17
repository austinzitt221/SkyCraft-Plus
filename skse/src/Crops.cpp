// SkyCraft+ : Skyrim crops on Minecraft farmland
#include "Game.h"
#include "Link.h"

// Skyrim's food grown on Minecraft's farmland. A Skyrim thing a Skyrim plant gives (a cabbage, a
// leek, a potato, wheat...) planted on farmland grows through Minecraft's wheat stages there; grown,
// it becomes that Skyrim plant (kEvCropGrown), which is picked the Skyrim way. One grown here gives
// two or three, and is gone once picked (the farmland is free for the next).
namespace skycraft::Crops
{
	namespace
	{
		constexpr std::uint32_t kCropsRecord = 'CROP';

		std::unordered_map<RE::FormID, RE::TESBoundObject*> plants;  // produce -> the plant giving it
		bool                                                 plantsRead = false;
		std::unordered_set<RE::FormID>                       grown;    // plants grown on farmland (refs)
		std::vector<std::pair<RE::FormID, float>>            picked;   // picked: gone after a moment

		void ReadPlants()
		{
			if (plantsRead) {
				return;
			}
			plantsRead = true;
			auto* data = RE::TESDataHandler::GetSingleton();
			if (!data) {
				return;
			}
			for (auto* flora : data->GetFormArray<RE::TESFlora>()) {
				if (flora && flora->produceItem) {
					plants.try_emplace(flora->produceItem->GetFormID(), flora);
				}
			}
			for (auto* tree : data->GetFormArray<RE::TESObjectTREE>()) {
				if (tree && tree->produceItem) {
					plants.try_emplace(tree->produceItem->GetFormID(), tree);
				}
			}
			logger::info("crops: {} Skyrim things grow on plants", plants.size());
		}

		class PickSink final : public RE::BSTEventSink<RE::TESActivateEvent>
		{
		public:
			static PickSink* Get()
			{
				static PickSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESActivateEvent* a_event, RE::BSTEventSource<RE::TESActivateEvent>*) override
			{
				auto* plant = a_event ? a_event->objectActivated.get() : nullptr;
				auto* by = a_event ? a_event->actionRef.get() : nullptr;
				if (!plant || !by || !by->IsPlayerRef() || !grown.contains(plant->GetFormID())) {
					return RE::BSEventNotifyControl::kContinue;
				}
				grown.erase(plant->GetFormID());
				auto* produce = plant->GetBaseObject() ? plant->GetBaseObject()->As<RE::TESProduceForm>() : nullptr;
				if (produce && produce->produceItem) {
					// Skyrim gives its one as ever; grown here, one or two more.
					const int more = 1 + int(RE::PlayerCharacter::GetSingleton()->GetFormID() ^ plant->GetFormID()) % 2;
					RE::PlayerCharacter::GetSingleton()->AddObjectToContainer(produce->produceItem, nullptr, more, nullptr);
					logger::info("crops: {} picked: {} more", produce->produceItem->GetName(), more);
				}
				picked.emplace_back(plant->GetFormID(), 1.5f);
				return RE::BSEventNotifyControl::kContinue;
			}
		};
	}

	bool Plantable(RE::TESBoundObject* a_produce)
	{
		ReadPlants();
		return a_produce && plants.contains(a_produce->GetFormID());
	}

	void OnGrown(RE::PlayerCharacter* a_player, RE::FormID a_produce, double a_x, double a_y, double a_z)
	{
		ReadPlants();
		static bool watching = false;
		if (!watching) {
			if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
				events->AddEventSink<RE::TESActivateEvent>(PickSink::Get());
				watching = true;
			}
		}
		auto it = plants.find(a_produce);
		if (it == plants.end()) {
			logger::info("crops: nothing of Skyrim's grows {:08X}", a_produce);
			return;
		}
		auto plant = a_player->PlaceObjectAtMe(it->second, true);  // (persists: saved with the game)
		if (!plant) {
			return;
		}
		plant->SetPosition(McToSky(a_x, a_y, a_z));
		plant->data.angle.z = float(std::rand() % 628) / 100.0f;
		grown.insert(plant->GetFormID());
		logger::info("crops: a {} grew on farmland", it->second->GetName());
	}

	void PerFrame(float a_delta)
	{
		for (auto it = picked.begin(); it != picked.end();) {
			if ((it->second -= a_delta) > 0.0f) {
				++it;
				continue;
			}
			if (auto* plant = RE::TESForm::LookupByID<RE::TESObjectREFR>(it->first)) {
				plant->Disable();
				plant->SetDelete(true);
			}
			it = picked.erase(it);
		}
	}

	void Save(SKSE::SerializationInterface* a_intfc)
	{
		if (grown.empty() || !a_intfc->OpenRecord(kCropsRecord, 1)) {
			return;
		}
		const auto count = static_cast<std::uint32_t>(grown.size());
		a_intfc->WriteRecordData(count);
		for (auto id : grown) {
			a_intfc->WriteRecordData(id);
		}
	}

	void Load(SKSE::SerializationInterface* a_intfc)
	{
		static bool watching = false;
		if (!watching) {
			if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
				events->AddEventSink<RE::TESActivateEvent>(PickSink::Get());
				watching = true;
			}
		}
		std::uint32_t count = 0;
		a_intfc->ReadRecordData(count);
		for (std::uint32_t i = 0; i < count && i < 65536; ++i) {
			RE::FormID id = 0, resolved = 0;
			if (a_intfc->ReadRecordData(id) && a_intfc->ResolveFormID(id, resolved)) {
				grown.insert(resolved);
			}
		}
	}

	void Clear()
	{
		grown.clear();
		picked.clear();
	}
}
