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

		// SkyCraft+ : a texture that isn't a whole surface: see-through in places (leaves, grass, fences,
		// cobwebs: alpha tested or blended), or a decal laid over another (moss, cracks, blood). Spread
		// over a block those showed their holes; the surface under them is the one wanted.
		bool Partial(RE::BSGeometry* a_geom)
		{
			auto& data = a_geom->GetGeometryRuntimeData();
			if (auto* alpha = data.alphaProperty.get(); alpha && (alpha->GetAlphaTesting() || alpha->GetAlphaBlending())) {
				return true;
			}
			auto* property = data.shaderProperty.get();
			using F = RE::BSShaderProperty::EShaderPropertyFlag;
			return property && property->flags.any(F::kDecal, F::kDynamicDecal, F::kTreeAnim);
		}

		// The smallest whole-surface shape of this object whose bound holds the point.
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
			if (geom && bound.radius > 0.0f && bound.center.GetDistance(a_at) <= bound.radius && bound.radius < a_bestRadius && !Partial(geom) && !DiffuseOf(geom).empty()) {
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

	// SkyCraft+ : the land's texture that shows most around a point (Skyrim units): each layer painted
	// over the quad's base, as Skyrim blends them (a layer covers what's under it by its opacity),
	// read between the four nearest land vertices and over a block's breadth around the point. Null
	// where it's Skyrim's default dirt; a_land false: no land loaded there.
	RE::TESLandTexture* LandTextureCovering(float a_x, float a_y, bool& a_land)
	{
		a_land = false;
		auto* tes = RE::TES::GetSingleton();
		auto* grid = tes ? tes->gridCells : nullptr;
		if (!grid || tes->interiorCell) {
			return nullptr;
		}
		constexpr float kCell = 4096.0f, kQuad = 2048.0f, kSpacing = 128.0f;
		std::unordered_map<RE::TESLandTexture*, float> shown;
		const float                                    half = 0.5f * float(proto::kUnitsPerBlock);
		for (int sy = -1; sy <= 1; ++sy) {
			for (int sx = -1; sx <= 1; ++sx) {
				const float px = a_x + sx * half, py = a_y + sy * half;
				const int   cx = int(std::floor(px / kCell)), cy = int(std::floor(py / kCell));
				RE::TESObjectCELL* cell = nullptr;
				for (std::uint32_t i = 0; i < grid->length * grid->length && !cell; ++i) {
					auto* c = grid->cells[i];
					auto* ext = c ? c->GetCoordinates() : nullptr;
					if (ext && ext->cellX == cx && ext->cellY == cy) {
						cell = c;
					}
				}
				auto* landForm = cell ? cell->GetRuntimeData().cellLand : nullptr;
				auto* loaded = landForm ? landForm->loadedData : nullptr;
				if (!loaded) {
					continue;
				}
				a_land = true;
				const float lx = px - cx * kCell, ly = py - cy * kCell;
				const int   quad = (lx >= kQuad ? 1 : 0) + (ly >= kQuad ? 2 : 0);
				const float qx = std::clamp((lx - (quad & 1) * kQuad) / kSpacing, 0.0f, 16.0f), qy = std::clamp((ly - (quad >> 1) * kQuad) / kSpacing, 0.0f, 16.0f);
				const int   c0 = std::min(int(qx), 15), r0 = std::min(int(qy), 15);
				const float fx = qx - c0, fy = qy - r0;
				// (Opacities are 0-100, or 0-255 in some data: the scale from what's there.)
				float scale = 100.0f;
				for (int v : { r0 * 17 + c0, r0 * 17 + c0 + 1, (r0 + 1) * 17 + c0, (r0 + 1) * 17 + c0 + 1 }) {
					for (int layer = 0; layer < 6; ++layer) {
						scale = std::max(scale, float(std::uint8_t(loaded->percents[quad][v][layer])));
					}
				}
				float opacity[6]{};
				for (int layer = 0; layer < 6; ++layer) {
					auto at = [&](int a_r, int a_c) { return float(std::uint8_t(loaded->percents[quad][a_r * 17 + a_c][layer])) / scale; };
					opacity[layer] = std::clamp((at(r0, c0) * (1 - fx) + at(r0, c0 + 1) * fx) * (1 - fy) + (at(r0 + 1, c0) * (1 - fx) + at(r0 + 1, c0 + 1) * fx) * fy, 0.0f, 1.0f);
				}
				// From the top layer down: each shows by its opacity, of what the ones above leave.
				float left = 1.0f;
				for (int layer = 5; layer >= 0; --layer) {
					if (loaded->quadTextures[quad][layer] && opacity[layer] > 0.0f) {
						shown[loaded->quadTextures[quad][layer]] += left * opacity[layer];
						left *= 1.0f - opacity[layer];
					}
				}
				shown[loaded->defQuadTextures[quad]] += left;
			}
		}
		RE::TESLandTexture* best = nullptr;
		float               most = -1.0f;
		for (auto& [texture, weight] : shown) {
			if (weight > most) {
				most = weight;
				best = texture;
			}
		}
		return best;
	}

	std::string TextureAt(double a_x, double a_y, double a_z, int& a_kind, int a_hit)
	{
		a_kind = 0;
		const RE::NiPoint3 at = McToSky(a_x, a_y, a_z);
		const float        block = float(proto::kUnitsPerBlock);
		// The land, where the point is on it (the ground a block was dug from); not where Minecraft saw
		// the point on something standing on it (a_hit 2: a city's stone path, a rock), which wins.
		bool        land = false;
		auto*       texture = LandTextureCovering(at.x, at.y, land);
		float       landZ = 0.0f;
		auto*       tes = RE::TES::GetSingleton();
		auto        above = at;
		above.z += 100000.0f;
		const bool  onLand = a_hit != 2 && land && tes && tes->GetLandHeight(above, landZ) && std::fabs(landZ - at.z) < 0.75f * block;
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
