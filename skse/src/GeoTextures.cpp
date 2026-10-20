// SkyCraft+ : Skyrim geo placed as Minecraft blocks
#include "Dig.h"
#include "Game.h"

// Skyrim's ground and rock, mined with silk touch, is a Minecraft block that looks like what was
// mined: its surface drawn with the very texture Skyrim used there. Minecraft asks which texture that
// is (kStoreTextureAt: the land's painted texture, or the rock's or road's own), and later asks Skyrim
// to draw that texture into a slot of the block atlas (kRenSkyrimTexture), which its geo blocks'
// faces then sample. Loading a texture is the main thread's (Skyrim's texture manager), drawing it
// into the atlas the render thread's (WorldRender).
namespace skycraft::GeoTextures
{
	namespace
	{
		struct Want
		{
			std::uint32_t x, y, w, h;
			std::string   path;
		};
		std::mutex                                                lock;
		std::vector<Want>                                         wanted;  // render thread -> main
		std::vector<std::pair<Want, RE::NiPointer<RE::NiTexture>>> ready;  // main -> render thread

		std::string Lower(std::string a_text)
		{
			std::ranges::transform(a_text, a_text.begin(), [](unsigned char c) { return char(std::tolower(c)); });
			return a_text;
		}

		// A shape's diffuse texture (its lighting material's texture set).
		std::string DiffuseOf(RE::BSGeometry* a_geom)
		{
			auto* property = a_geom ? a_geom->GetGeometryRuntimeData().shaderProperty.get() : nullptr;
			if (!property || std::strcmp(property->GetRTTI()->GetName(), "BSLightingShaderProperty") != 0) {
				return {};
			}
			auto* material = static_cast<RE::BSLightingShaderMaterialBase*>(property->material);
			const char* path = material && material->textureSet ? material->textureSet->GetTexturePath(RE::BSTextureSet::Texture::kDiffuse) : nullptr;
			return path ? std::string(path) : std::string();
		}

		// The smallest shape of this object whose bound holds the point.
		void ShapeAt(RE::NiAVObject* a_obj, const RE::NiPoint3& a_at, RE::BSGeometry*& a_best, float& a_bestRadius, int a_depth = 0)
		{
			if (!a_obj || a_depth > 24 || a_obj->GetFlags().any(RE::NiAVObject::Flag::kHidden)) {
				return;
			}
			if (auto* node = a_obj->AsNode()) {
				for (auto& child : node->GetChildren()) {
					ShapeAt(child.get(), a_at, a_best, a_bestRadius, a_depth + 1);
				}
				return;
			}
			auto* geom = a_obj->AsGeometry();
			const auto& bound = a_obj->worldBound;
			if (geom && bound.radius > 0.0f && bound.center.GetDistance(a_at) <= bound.radius && bound.radius < a_bestRadius && !DiffuseOf(geom).empty()) {
				a_best = geom;
				a_bestRadius = bound.radius;
			}
		}
	}

	void Request(const std::uint8_t* a_data, std::uint32_t a_bytes)
	{
		if (a_bytes < sizeof(proto::RenSkyrimTexture)) {
			return;
		}
		const auto* hdr = reinterpret_cast<const proto::RenSkyrimTexture*>(a_data);
		std::string path(hdr->path, strnlen(hdr->path, sizeof(hdr->path)));
		if (path.empty() || !hdr->w || !hdr->h) {
			return;
		}
		std::scoped_lock guard(lock);
		wanted.push_back({ hdr->x, hdr->y, hdr->w, hdr->h, std::move(path) });
	}

	void PerFrameMain()
	{
		std::vector<Want> now;
		{
			std::scoped_lock guard(lock);
			now.swap(wanted);
		}
		for (auto& want : now) {
			RE::NiPointer<RE::NiTexture> texture;
			RE::BSShaderManager::GetTexture(want.path.c_str(), true, texture, false);
			if (!texture && !Lower(want.path).starts_with("textures\\")) {
				RE::BSShaderManager::GetTexture(("textures\\" + want.path).c_str(), true, texture, false);
			}
			if (!texture) {
				logger::warn("geo: Skyrim's texture {} wasn't found", want.path);
				continue;
			}
			std::scoped_lock guard(lock);
			ready.emplace_back(std::move(want), std::move(texture));
		}
	}

	bool TakeReady(std::uint32_t& a_x, std::uint32_t& a_y, std::uint32_t& a_w, std::uint32_t& a_h, RE::NiPointer<RE::NiTexture>& a_texture)
	{
		std::scoped_lock guard(lock);
		if (ready.empty()) {
			return false;
		}
		auto& [want, texture] = ready.back();
		a_x = want.x, a_y = want.y, a_w = want.w, a_h = want.h;
		a_texture = std::move(texture);
		ready.pop_back();
		return true;
	}

	std::string TextureAt(double a_x, double a_y, double a_z, int& a_kind)
	{
		a_kind = 0;
		const RE::NiPoint3 at = McToSky(a_x, a_y, a_z);
		const float        block = float(proto::kUnitsPerBlock);
		// The land, where the point is on it (the ground a block was dug from).
		bool        land = false;
		auto*       texture = Dig::LandTextureAt(at.x, at.y, land);
		float       landZ = 0.0f;
		auto*       tes = RE::TES::GetSingleton();
		auto        above = at;
		above.z += 100000.0f;
		const bool  onLand = land && tes && tes->GetLandHeight(above, landZ) && std::fabs(landZ - at.z) < 0.75f * block;
		if (onLand) {
			a_kind = 1;
			auto* set = texture ? texture->textureSet : nullptr;
			const char* path = set ? set->GetTexturePath(RE::BSTextureSet::Texture::kDiffuse) : nullptr;
			return path && *path ? std::string(path) : std::string("Landscape\\Dirt02.dds");
		}
		// Else the rock, cliff or road whose shape holds the point (the smallest).
		RE::BSGeometry* best = nullptr;
		float           bestRadius = 1.0e9f;
		if (tes) {
			tes->ForEachCell([&](RE::TESObjectCELL* a_cell) {
				a_cell->ForEachReferenceInRange(at, 30.0f * block, [&](RE::TESObjectREFR* a_ref) {
					if (a_ref && !a_ref->IsDisabled() && a_ref->Is3DLoaded() && Dig::IsDiggableRef(a_ref)) {
						ShapeAt(a_ref->Get3D(), at, best, bestRadius);
					}
					return RE::BSContainer::ForEachResult::kContinue;
				});
			});
		}
		if (best) {
			a_kind = 2;
			return DiffuseOf(best);
		}
		if (land && texture && texture->textureSet) {
			a_kind = 1;
			const char* path = texture->textureSet->GetTexturePath(RE::BSTextureSet::Texture::kDiffuse);
			return path ? std::string(path) : std::string();
		}
		return {};
	}

	void Catalog(std::vector<proto::SkyItem>& a_items)
	{
		auto* handler = RE::TESDataHandler::GetSingleton();
		if (!handler) {
			return;
		}
		std::unordered_set<std::string> seen;
		for (auto* texture : handler->GetFormArray<RE::TESLandTexture>()) {
			auto* set = texture ? texture->textureSet : nullptr;
			const char* path = set ? set->GetTexturePath(RE::BSTextureSet::Texture::kDiffuse) : nullptr;
			if (!path || !*path || !seen.insert(Lower(path)).second || a_items.size() >= proto::kMaxCatalog) {
				continue;
			}
			proto::SkyItem item{};
			item.formId = texture->GetFormID();
			item.kind = proto::kSkyGeo;
			item.count = 1;
			item.material = Dig::MaterialFor(texture->materialType ? texture->materialType->materialID : RE::MATERIAL_ID::kDirt, nullptr, false);
			std::strncpy(item.name, path, sizeof(item.name) - 1);
			a_items.push_back(item);
		}
	}
}
