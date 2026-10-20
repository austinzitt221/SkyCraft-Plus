// SkyCraft+ : the SkyCraft+ settings screen
#include "Game.h"

#include <SimpleIni.h>

#include <fstream>
#include <sstream>

// Minecraft's pause menu has a SkyCraft+ settings screen. Skyrim gives it SkyCraft.ini's text (with
// its comments, which the screen shows as each setting's description) and writes whatever is
// changed there back into the file, comments kept, then takes the change up at once: the controls,
// the worlds, combat and the step-up settings are read again; a few (the launcher, the block collision
// models and crates, the ground's reach, diagnostics) only at Skyrim's next start, which the screen says.
namespace skycraft::Settings
{
	namespace
	{
		constexpr const char* kPath = "Data/SKSE/Plugins/SkyCraft.ini";
		bool published = false;

		std::string ReadFile()
		{
			std::ifstream in(kPath, std::ios::binary);
			std::ostringstream text;
			text << in.rdbuf();
			return text.str();
		}

		void Publish()
		{
			Link::Get().WriteSettingsText(ReadFile());
			published = true;
		}

		std::string Field(const char* a_text, std::size_t a_size)
		{
			return std::string(a_text, strnlen(a_text, a_size));
		}

		void Apply(const proto::SettingsEdit& a_edit)
		{
			const std::string section = Field(a_edit.section, sizeof(a_edit.section));
			const std::string key = Field(a_edit.key, sizeof(a_edit.key));
			const std::string value = Field(a_edit.value, sizeof(a_edit.value));
			if (section.empty() || key.empty()) {
				return;
			}
			CSimpleIniA ini;
			ini.SetUnicode();
			if (ini.LoadFile(kPath) < 0) {
				logger::warn("settings: couldn't read {} to change [{}] {}", kPath, section, key);
				return;
			}
			ini.SetValue(section.c_str(), key.c_str(), value.c_str());
			if (ini.SaveFile(kPath, false) < 0) {
				logger::warn("settings: couldn't write {} ([{}] {} = {})", kPath, section, key, value);
				RE::SendHUDMessage::ShowHUDMessage("SkyCraft: couldn't save SkyCraft.ini (is it read-only?)");
				return;
			}
			logger::info("settings: [{}] {} = {} (from Minecraft's settings screen)", section, key, value);
			State().iniGeneration++;
			Input::ReloadSettings();
			LockOn::Reload();
		}
	}

	void PerFrame()
	{
		if (!published && Link::Get().Valid()) {
			Publish();
		}
		proto::SettingsEdit edit;
		bool changed = false;
		for (int budget = 16; budget > 0 && Link::Get().PopSettingsEdit(edit); --budget) {
			Apply(edit);
			changed = true;
		}
		if (changed) {
			Publish();
		}
	}
}
