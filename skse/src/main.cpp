#include "Dig.h"
#include "Game.h"

namespace
{
	void SetupLog()
	{
		auto dir = SKSE::log::log_directory();
		if (!dir) {
			return;
		}
		auto path = *dir / "SkyCraft.log";
		auto sink = std::make_shared<spdlog::sinks::basic_file_sink_mt>(path.string(), true);
		auto log = std::make_shared<spdlog::logger>("global", std::move(sink));
		log->set_level(spdlog::level::info);
		log->flush_on(spdlog::level::info);
		spdlog::set_default_logger(std::move(log));
		spdlog::set_pattern("[%H:%M:%S.%e] [%l] %v");
	}

	void OnMessage(SKSE::MessagingInterface::Message* a_msg)
	{
		switch (a_msg->type) {
		case SKSE::MessagingInterface::kDataLoaded:
			if (!skycraft::Link::Get().Create()) {
				logger::error("SkyCraft disabled: could not create shared memory");
				return;
			}
			skycraft::Game::Install();
			skycraft::Input::Install();
			skycraft::Overlay::Install();
			skycraft::WorldRender::Install();
			skycraft::PathAvoid::Install();
			skycraft::Dig::Install();
			skycraft::CrashLog::Install();
			// SkyCraft+ : Minecraft's structures in Skyrim's world. Where Skyrim's Data folder is, for
			// Minecraft (started through Prism, so given nothing) to read the land from Skyrim.esm.
			try {
				wchar_t local[MAX_PATH]{};
				if (::ExpandEnvironmentStringsW(L"%LOCALAPPDATA%\\SkyCraft", local, MAX_PATH) > 0) {
					std::filesystem::create_directories(local);
					std::ofstream out(std::filesystem::path(local) / "skyrim-data.txt", std::ios::trunc);
					out << (std::filesystem::current_path() / "Data").string() << "\n";
				}
			} catch (const std::exception& e) {
				logger::warn("couldn't note Skyrim's Data folder for Minecraft: {}", e.what());
			}
			break;
		case SKSE::MessagingInterface::kPostLoadGame:
		case SKSE::MessagingInterface::kNewGame:
			skycraft::Game::OnGameLoaded();
			break;
		default:
			break;
		}
	}
}

SKSEPluginLoad(const SKSE::LoadInterface* a_skse)
{
	SKSE::Init(a_skse, { .trampoline = true, .trampolineSize = 1024 });
	SetupLog();
	skycraft::CrashLog::Install();
	logger::info("SkyCraft {} loading (runtime {})", "0.1.2-plus.4", a_skse->RuntimeVersion().string());
	SKSE::GetMessagingInterface()->RegisterListener(OnMessage);
	if (auto* serialization = SKSE::GetSerializationInterface()) {
		serialization->SetUniqueID('SKYC');
		serialization->SetSaveCallback(skycraft::Game::OnSave);
		serialization->SetLoadCallback(skycraft::Game::OnLoad);
		serialization->SetRevertCallback(skycraft::Game::OnRevert);
	}
	// As early as possible: Minecraft takes about as long to start as Skyrim does to reach its menu.
	skycraft::Launcher::StartMinecraft();
	return true;
}
