// SkyCraft+ : Skyrim's item pictures
#include "Game.h"

#include <d3dcompiler.h>

// Skyrim has no 2D pictures of its items (its menus spin the 3D model), so this draws each one in
// the player's list once, to a small picture Minecraft's Skyrim panels and shortcuts show
// (proto::SkyIcons). The model is loaded on the main thread (PerFrame); at Present (Draw) it's drawn
// straight from Skyrim's own vertex and index buffers and textures, first from the front, the side
// and above at a low size to see which way shows the most of it and how it lies (a sword is turned
// to point up and right, its grip at the bottom), then once more at that view, filling the picture.
namespace skycraft::IconRender
{
	namespace
	{
		constexpr UINT kTile = 64;    // the three trial views, side by side
		constexpr UINT kFinal = 128;  // the final view, halved to kSkyIconSize
		constexpr int  kJobs = 4;
		constexpr int  kLoadsPerFrame = 2;

		struct Part
		{
			RE::NiPointer<RE::BSTriShape> keep;
			ID3D11Buffer*                 vb = nullptr;
			ID3D11Buffer*                 ib = nullptr;
			std::uint64_t                 desc = 0;
			UINT                          stride = 0;
			UINT                          indexCount = 0;
			float                         world[12]{};  // 3 rows of a 3x4 (rotation * scale | translation)
			ID3D11ShaderResourceView*     srv = nullptr;  // our reference
			RE::NiPointer<RE::NiTexture>  texture;        // keeps a texture we loaded ourselves
			bool                          alphaTest = false;
			bool                          full = true;  // float positions (else halves)
		};

		struct Model
		{
			std::uint32_t                key = 0;
			std::uint32_t                slot = 0;
			std::string                  name;
			RE::NiPointer<RE::NiNode>    root;
			std::vector<Part>            parts;
			RE::NiPoint3                 center;
			float                        radius = 1.0f;
		};

		// ---- main thread ----
		struct Wanted
		{
			std::uint32_t key;
			RE::FormID    formId;
			std::uint64_t nameHash;
		};
		std::vector<Wanted>                            wanted;
		std::unordered_map<std::uint32_t, std::uint32_t> slotOf;       // key -> slot
		std::unordered_map<std::uint32_t, std::uint64_t> drawnName;    // key -> name hash it was drawn for
		std::unordered_set<std::uint32_t>              noModel;       // keys with nothing to draw
		std::array<std::uint32_t, proto::kMaxSkyIcons> keyIn{};
		std::array<std::uint64_t, proto::kMaxSkyIcons> lastUse{};
		std::uint64_t                                  tick = 0;
		std::unordered_map<RE::SpellItem*, RE::TESObjectBOOK*> tomes;
		bool                                           tomesBuilt = false;

		// ---- main thread -> render thread ----
		std::mutex        lock;
		std::deque<Model> queue;

		// ---- render thread ----
		ID3D11VertexShader*       vs = nullptr;
		ID3D11PixelShader*        ps = nullptr;
		ID3DBlob*                 vsBlob = nullptr;
		ID3D11Buffer*             constants = nullptr;
		ID3D11SamplerState*       sampler = nullptr;
		ID3D11RasterizerState*    raster = nullptr;
		ID3D11DepthStencilState*  depthState = nullptr;
		ID3D11BlendState*         blendState = nullptr;
		ID3D11ShaderResourceView* white = nullptr;
		ID3D11Texture2D*          rtA = nullptr;
		ID3D11RenderTargetView*   rtvA = nullptr;
		ID3D11Texture2D*          dsA = nullptr;
		ID3D11DepthStencilView*   dsvA = nullptr;
		ID3D11Texture2D*          rtB = nullptr;
		ID3D11RenderTargetView*   rtvB = nullptr;
		ID3D11Texture2D*          dsB = nullptr;
		ID3D11DepthStencilView*   dsvB = nullptr;
		std::unordered_map<std::uint64_t, ID3D11InputLayout*> layouts;
		bool                      initFailed = false;
		int                       logged = 0;

		struct View
		{
			RE::NiPoint3 right, up, forward, center;
			float        half = 1.0f, depth = 1.0f;
		};

		struct Job
		{
			bool             busy = false;
			int              stage = 0;  // 1: trial views drawn, 2: final drawn
			int              waited = 0;
			int              refits = 0;
			Model            model;
			View             final;
			ID3D11Texture2D* stagingA = nullptr;
			ID3D11Texture2D* stagingB = nullptr;
		};
		std::array<Job, kJobs> jobs;

		struct alignas(16) Constants
		{
			float viewProj[16];  // row-major
			float world[12];
			float viewRot[12];   // rows: right, up, forward (and a pad each)
			float opts[4];       // has uv, has normal, has colour, alpha test
		};

		constexpr char kShader[] = R"(
cbuffer C : register(b0) { row_major float4x4 viewProj; float4 w0; float4 w1; float4 w2; float4 vr; float4 vu; float4 vf; float4 opts; };
Texture2D tex : register(t0);
SamplerState samp : register(s0);
struct VI { float3 pos : POSITION; float2 uv : TEXCOORD0; float4 nrm : NORMAL; float4 col : COLOR0; };
struct VO { float4 pos : SV_Position; float2 uv : TEXCOORD0; float3 n : NORMAL; float4 col : COLOR0; };
VO VSMain(VI i) {
	VO o;
	float4 p = float4(i.pos, 1);
	float3 wp = float3(dot(w0, p), dot(w1, p), dot(w2, p));
	o.pos = mul(viewProj, float4(wp, 1));
	o.uv = opts.x > 0.5 ? i.uv : float2(0, 0);
	float3 n = opts.y > 0.5 ? i.nrm.xyz * 2 - 1 : float3(0, 0, 1);
	float3 wn = float3(dot(w0.xyz, n), dot(w1.xyz, n), dot(w2.xyz, n));
	o.n = float3(dot(vr.xyz, wn), dot(vu.xyz, wn), -dot(vf.xyz, wn));
	o.col = opts.z > 0.5 ? float4(i.col.rgb, 1) : float4(1, 1, 1, 1);
	return o;
}
float4 PSMain(VO i, bool front : SV_IsFrontFace) : SV_Target {
	float4 c = tex.Sample(samp, i.uv) * i.col;
	if (opts.w > 0.5 && c.a < 0.5) discard;
	float3 n = normalize(i.n);
	if (n.z < 0) n = -n;
	float3 l = normalize(float3(-0.45, 0.55, 0.7));
	float shade = 0.5 + 0.6 * saturate(dot(n, l));
	return float4(saturate(c.rgb * shade), 1);
}
)";

		template <class T>
		void SafeRelease(T*& a_ptr)
		{
			if (a_ptr) {
				a_ptr->Release();
				a_ptr = nullptr;
			}
		}

		std::uint64_t Hash(std::string_view a_text)
		{
			std::uint64_t h = 1469598103934665603ull;
			for (const char c : a_text) {
				h = (h ^ static_cast<std::uint8_t>(c)) * 1099511628211ull;
			}
			return h;
		}

		// ---- what to draw for each thing (main thread) ----

		void BuildTomes()
		{
			if (tomesBuilt) {
				return;
			}
			tomesBuilt = true;
			if (auto* data = RE::TESDataHandler::GetSingleton()) {
				for (auto* book : data->GetFormArray<RE::TESObjectBOOK>()) {
					if (book && book->TeachesSpell() && book->GetSpell()) {
						tomes.emplace(book->GetSpell(), book);
					}
				}
			}
		}

		template <class T>
		T* ByName(std::string_view a_name, bool a_anyIfNone = true)
		{
			auto* data = RE::TESDataHandler::GetSingleton();
			if (!data) {
				return nullptr;
			}
			T* first = nullptr;
			for (auto* form : data->GetFormArray<T>()) {
				if (!form || !form->GetPlayable()) {
					continue;
				}
				if (a_name.empty() || _stricmp(form->GetName(), std::string(a_name).c_str()) == 0) {
					return form;
				}
				if (!first && form->GetName() && *form->GetName()) {
					first = form;
				}
			}
			return a_anyIfNone ? first : nullptr;
		}

		// The spell tome of a school's cheapest spell.
		RE::TESObjectBOOK* SchoolTome(RE::ActorValue a_school)
		{
			BuildTomes();
			RE::TESObjectBOOK* best = nullptr;
			float              cost = 1e9f;
			for (auto& [spell, book] : tomes) {
				auto* effect = spell->GetCostliestEffectItem();
				if (effect && effect->baseEffect && effect->baseEffect->GetMagickSkill() == a_school && effect->cost < cost) {
					cost = effect->cost;
					best = book;
				}
			}
			return best;
		}

		// What stands for one of Minecraft's Skyrim categories (see kSkyIconCategory).
		RE::TESForm* CategoryForm(std::uint32_t a_index)
		{
			switch (a_index) {
			case 0:
				return RE::TESForm::LookupByID(0xF);  // gold
			case 1:
				return RE::TESForm::LookupByID(0x12EB7);  // Iron Sword
			case 2:
				return RE::TESForm::LookupByID(0x12E49);  // Iron Armor
			case 3:
				return ByName<RE::AlchemyItem>("Potion of Minor Healing");
			case 4:
				return ByName<RE::ScrollItem>("Scroll of Fireball");
			case 5:
				return RE::TESForm::LookupByID(0x64B3D);  // Sweet Roll
			case 6:
				return ByName<RE::IngredientItem>("Nirnroot");
			case 7:
				return ByName<RE::TESObjectBOOK>("The Book of the Dragonborn");
			case 8:
				return ByName<RE::TESKey>("");
			case 9:
				return RE::TESForm::LookupByID(0xA);  // lockpick
			case 10:
				{
					BuildTomes();
					auto* flames = RE::TESForm::LookupByID<RE::SpellItem>(0x12FCD);
					const auto it = flames ? tomes.find(flames) : tomes.end();
					return it != tomes.end() ? it->second : nullptr;
				}
			case 11:
				return SchoolTome(RE::ActorValue::kAlteration);
			case 12:
				return SchoolTome(RE::ActorValue::kConjuration);
			case 13:
				return SchoolTome(RE::ActorValue::kDestruction);
			case 14:
				return SchoolTome(RE::ActorValue::kIllusion);
			case 15:
				return SchoolTome(RE::ActorValue::kRestoration);
			case 16:
				return ByName<RE::TESObjectMISC>("Dragon Bone");
			case 17:
				return ByName<RE::TESSoulGem>("Black Soul Gem");
			default:
				return nullptr;
			}
		}

		// The model that pictures a form: its world model (armour: the one lying on the ground), a
		// spell's tome, a shout's dragon bone, a power's black soul gem.
		const char* ModelOf(RE::TESForm* a_form)
		{
			if (!a_form) {
				return nullptr;
			}
			if (auto* armor = a_form->As<RE::TESObjectARMO>()) {
				for (auto& model : armor->worldModels) {
					if (model.GetModel() && *model.GetModel()) {
						return model.GetModel();
					}
				}
				return nullptr;
			}
			if (auto* spell = a_form->As<RE::SpellItem>(); spell && !a_form->Is(RE::FormType::Scroll)) {
				if (spell->GetSpellType() != RE::MagicSystem::SpellType::kSpell) {
					return ModelOf(CategoryForm(17));
				}
				BuildTomes();
				const auto it = tomes.find(spell);
				if (it != tomes.end()) {
					return ModelOf(it->second);
				}
				auto* effect = spell->GetCostliestEffectItem();
				return effect && effect->baseEffect ? ModelOf(SchoolTome(effect->baseEffect->GetMagickSkill())) : nullptr;
			}
			if (a_form->Is(RE::FormType::Shout)) {
				return ModelOf(CategoryForm(16));
			}
			auto* model = a_form->As<RE::TESModel>();
			return model && model->GetModel() && *model->GetModel() ? model->GetModel() : nullptr;
		}

		// A shader resource view of a Skyrim texture (BSGraphics::Texture: the texture at 0, its view at
		// 0x10). Guarded: a wrong guess at the layout gives no texture, not a crash.
		ID3D11ShaderResourceView* TryView(void* a_object)
		{
			ID3D11ShaderResourceView* view = nullptr;
			__try {
				if (a_object && SUCCEEDED(static_cast<IUnknown*>(a_object)->QueryInterface(__uuidof(ID3D11ShaderResourceView), reinterpret_cast<void**>(&view)))) {
					return view;
				}
			} __except (EXCEPTION_EXECUTE_HANDLER) {
			}
			return nullptr;
		}

		void* ReadPointer(const void* a_at)
		{
			__try {
				return *static_cast<void* const*>(a_at);
			} __except (EXCEPTION_EXECUTE_HANDLER) {
				return nullptr;
			}
		}

		ID3D11ShaderResourceView* ViewOf(RE::NiTexture* a_texture)
		{
			auto* source = a_texture ? netimmerse_cast<RE::NiSourceTexture*>(a_texture) : nullptr;
			auto* renderer = source ? reinterpret_cast<std::uint8_t*>(source->rendererTexture) : nullptr;
			if (!renderer) {
				return nullptr;
			}
			return TryView(ReadPointer(renderer + 0x10));
		}

		void Collect(RE::NiAVObject* a_obj, const RE::NiTransform& a_parent, Model& a_model, std::vector<RE::NiBound>& a_bounds, int a_depth = 0)
		{
			if (!a_obj || a_depth > 32 || a_obj->GetFlags().any(RE::NiAVObject::Flag::kHidden)) {
				return;
			}
			const RE::NiTransform world = a_depth == 0 ? RE::NiTransform{} : a_parent * a_obj->local;
			if (auto* node = a_obj->AsNode()) {
				for (auto& child : node->GetChildren()) {
					Collect(child.get(), world, a_model, a_bounds, a_depth + 1);
				}
				return;
			}
			auto* geom = a_obj->AsGeometry();
			if (!geom || std::strcmp(geom->GetRTTI()->GetName(), "BSTriShape") != 0) {
				return;
			}
			auto& data = geom->GetGeometryRuntimeData();
			auto* property = data.shaderProperty.get();
			if (!property || std::strcmp(property->GetRTTI()->GetName(), "BSLightingShaderProperty") != 0) {
				return;
			}
			// What to draw: the shape's own buffers, or a skinned shape's (a bow's string, a book's pages)
			// partitions, drawn as they rest.
			struct Source
			{
				RE::BSGraphics::TriShape* rd;
				std::uint64_t             desc;
				UINT                      triangles;
			};
			std::vector<Source> sources;
			if (auto* skin = data.skinInstance.get()) {
				if (auto* partition = skin->skinPartition.get()) {
					for (std::uint32_t i = 0; i < partition->numPartitions; ++i) {
						auto& part = partition->partitions[i];
						sources.push_back({ part.buffData, reinterpret_cast<const std::uint64_t&>(part.vertexDesc), part.triangles });
					}
				}
			} else if (data.rendererData) {
				sources.push_back({ data.rendererData, reinterpret_cast<const std::uint64_t&>(data.rendererData->vertexDesc),
					UINT(static_cast<RE::BSTriShape*>(geom)->GetTrishapeRuntimeData().triangleCount) });
			}
			RE::NiPointer<RE::NiTexture> loaded;
			ID3D11ShaderResourceView*    srv = nullptr;
			if (auto* material = static_cast<RE::BSLightingShaderMaterialBase*>(property->material)) {
				srv = ViewOf(material->diffuseTexture.get());
				if (!srv && material->textureSet) {
					const char* path = material->textureSet->GetTexturePath(RE::BSTextureSet::Texture::kDiffuse);
					if (path && *path) {
						RE::BSShaderManager::GetTexture(path, true, loaded, false);
						srv = ViewOf(loaded.get());
					}
				}
			}
			bool added = false;
			for (const auto& source : sources) {
				const auto&   desc = reinterpret_cast<const RE::BSGraphics::VertexDesc&>(source.desc);
				const UINT    stride = UINT(source.desc & 0xF) * 4;
				const auto    flags = desc.GetFlags();
				using V = RE::BSGraphics::Vertex;
				if (!source.rd || !source.rd->vertexBuffer || !source.rd->indexBuffer || stride == 0 || source.triangles == 0 || !(flags & V::VF_VERTEX) ||
					(flags & (V::VF_INSTANCEDATA | V::VF_EYEDATA))) {
					continue;
				}
				// Positions are floats (16 bytes) or halves (8): told by the room before the next part of
				// the vertex (the full-precision flag isn't reliable; see DigMesh's SpansOf).
				UINT positionBytes = stride;
				for (int attr = 1; attr < V::VA_COUNT; ++attr) {
					if (flags & (1u << attr)) {
						const UINT offset = desc.GetAttributeOffset(V::Attribute(attr));
						if (offset > 0) {
							positionBytes = std::min(positionBytes, offset);
						}
					}
				}
				Part part;
				part.keep = RE::NiPointer<RE::BSTriShape>(static_cast<RE::BSTriShape*>(geom));
				part.vb = reinterpret_cast<ID3D11Buffer*>(source.rd->vertexBuffer);
				part.ib = reinterpret_cast<ID3D11Buffer*>(source.rd->indexBuffer);
				part.desc = source.desc;
				part.stride = stride;
				part.full = positionBytes >= 12;
				part.indexCount = source.triangles * 3;
				const auto& r = world.rotate;
				for (int row = 0; row < 3; ++row) {
					for (int col = 0; col < 3; ++col) {
						part.world[row * 4 + col] = r.entry[row][col] * world.scale;
					}
				}
				part.world[3] = world.translate.x;
				part.world[7] = world.translate.y;
				part.world[11] = world.translate.z;
				if (srv) {
					srv->AddRef();
					part.srv = srv;
				}
				part.texture = loaded;
				if (auto* alpha = data.alphaProperty.get()) {
					part.alphaTest = alpha->GetAlphaTesting() || alpha->GetAlphaBlending();
				}
				a_model.parts.push_back(std::move(part));
				added = true;
			}
			if (srv) {
				srv->Release();
			}
			if (added) {
				const auto& bound = geom->GetModelData().modelBound;
				a_bounds.push_back({ world * bound.center, bound.radius * world.scale });
			} else if (logged++ < 40) {
				logger::info("icons: {}: shape {} left out ({} sources, skinned {})", a_model.name, geom->name.c_str(), sources.size(), data.skinInstance != nullptr);
			}
		}

		bool Load(Model& a_model, const char* a_path)
		{
			RE::BSModelDB::DBTraits::ArgsType args{};
			RE::NiPointer<RE::NiNode>         root;
			auto                              error = RE::BSModelDB::Demand(a_path, root, args);
			if (!root) {
				const std::string withFolder = std::string("Meshes\\") + a_path;
				error = RE::BSModelDB::Demand(withFolder.c_str(), root, args);
			}
			if (!root) {
				if (logged++ < 40) {
					logger::info("icons: {} not drawn: model {} didn't load ({})", a_model.name, a_path, static_cast<int>(error));
				}
				return false;
			}
			a_model.root = root;
			std::vector<RE::NiBound> bounds;
			Collect(root.get(), RE::NiTransform{}, a_model, bounds);
			if (a_model.parts.empty()) {
				if (logged++ < 40) {
					logger::info("icons: {} not drawn: nothing in {} to draw", a_model.name, a_path);
				}
				return false;
			}
			RE::NiPoint3 lo{ 1e9f, 1e9f, 1e9f }, hi{ -1e9f, -1e9f, -1e9f };
			for (const auto& b : bounds) {
				lo = { std::min(lo.x, b.center.x - b.radius), std::min(lo.y, b.center.y - b.radius), std::min(lo.z, b.center.z - b.radius) };
				hi = { std::max(hi.x, b.center.x + b.radius), std::max(hi.y, b.center.y + b.radius), std::max(hi.z, b.center.z + b.radius) };
			}
			a_model.center = (lo + hi) * 0.5f;
			a_model.radius = std::max(0.5f, (hi - lo).Length() * 0.5f);
			return true;
		}

		std::uint32_t PickSlot(std::uint32_t a_key, const std::unordered_set<std::uint32_t>& a_wantedKeys)
		{
			if (const auto it = slotOf.find(a_key); it != slotOf.end()) {
				return it->second;
			}
			std::uint32_t best = 0;
			std::uint64_t oldest = ~0ull;
			for (std::uint32_t i = 0; i < proto::kMaxSkyIcons; ++i) {
				if (keyIn[i] == 0) {
					best = i;
					break;
				}
				if (!a_wantedKeys.contains(keyIn[i]) && lastUse[i] < oldest) {
					oldest = lastUse[i];
					best = i;
				}
			}
			if (keyIn[best] != 0) {
				slotOf.erase(keyIn[best]);
				drawnName.erase(keyIn[best]);
			}
			keyIn[best] = a_key;
			slotOf[a_key] = best;
			return best;
		}

		// ---- drawing (render thread) ----

		bool InitResources(ID3D11Device* a_device)
		{
			if (vs) {
				return true;
			}
			if (initFailed) {
				return false;
			}
			initFailed = true;
			ID3DBlob* errors = nullptr;
			ID3DBlob* psBlob = nullptr;
			if (FAILED(D3DCompile(kShader, sizeof(kShader) - 1, "skycraft_icons", nullptr, nullptr, "VSMain", "vs_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &vsBlob, &errors)) ||
				FAILED(D3DCompile(kShader, sizeof(kShader) - 1, "skycraft_icons", nullptr, nullptr, "PSMain", "ps_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &psBlob, &errors))) {
				logger::error("icons: shader didn't compile: {}", errors ? static_cast<const char*>(errors->GetBufferPointer()) : "?");
				SafeRelease(errors);
				return false;
			}
			a_device->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), nullptr, &vs);
			a_device->CreatePixelShader(psBlob->GetBufferPointer(), psBlob->GetBufferSize(), nullptr, &ps);
			SafeRelease(psBlob);

			D3D11_BUFFER_DESC cb{ sizeof(Constants), D3D11_USAGE_DYNAMIC, D3D11_BIND_CONSTANT_BUFFER, D3D11_CPU_ACCESS_WRITE, 0, 0 };
			a_device->CreateBuffer(&cb, nullptr, &constants);
			D3D11_SAMPLER_DESC sd{};
			sd.Filter = D3D11_FILTER_ANISOTROPIC;
			sd.MaxAnisotropy = 4;
			sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_WRAP;
			sd.MaxLOD = D3D11_FLOAT32_MAX;
			a_device->CreateSamplerState(&sd, &sampler);
			D3D11_RASTERIZER_DESC rd{};
			rd.FillMode = D3D11_FILL_SOLID;
			rd.CullMode = D3D11_CULL_NONE;
			rd.DepthClipEnable = TRUE;
			a_device->CreateRasterizerState(&rd, &raster);
			D3D11_DEPTH_STENCIL_DESC dd{};
			dd.DepthEnable = TRUE;
			dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
			dd.DepthFunc = D3D11_COMPARISON_LESS_EQUAL;
			a_device->CreateDepthStencilState(&dd, &depthState);
			D3D11_BLEND_DESC bd{};
			bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
			a_device->CreateBlendState(&bd, &blendState);

			const std::uint32_t        pixel = 0xFFFFFFFF;
			D3D11_TEXTURE2D_DESC       wd{ 1, 1, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, { 1, 0 }, D3D11_USAGE_IMMUTABLE, D3D11_BIND_SHADER_RESOURCE, 0, 0 };
			D3D11_SUBRESOURCE_DATA     init{ &pixel, 4, 4 };
			ID3D11Texture2D*           wt = nullptr;
			if (SUCCEEDED(a_device->CreateTexture2D(&wd, &init, &wt))) {
				a_device->CreateShaderResourceView(wt, nullptr, &white);
				SafeRelease(wt);
			}

			auto target = [&](UINT a_w, UINT a_h, ID3D11Texture2D*& a_rt, ID3D11RenderTargetView*& a_rtv, ID3D11Texture2D*& a_ds, ID3D11DepthStencilView*& a_dsv) {
				D3D11_TEXTURE2D_DESC td{ a_w, a_h, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, { 1, 0 }, D3D11_USAGE_DEFAULT, D3D11_BIND_RENDER_TARGET, 0, 0 };
				a_device->CreateTexture2D(&td, nullptr, &a_rt);
				if (a_rt) {
					a_device->CreateRenderTargetView(a_rt, nullptr, &a_rtv);
				}
				td.Format = DXGI_FORMAT_D32_FLOAT;
				td.BindFlags = D3D11_BIND_DEPTH_STENCIL;
				a_device->CreateTexture2D(&td, nullptr, &a_ds);
				if (a_ds) {
					a_device->CreateDepthStencilView(a_ds, nullptr, &a_dsv);
				}
			};
			target(kTile * 3, kTile, rtA, rtvA, dsA, dsvA);
			target(kFinal, kFinal, rtB, rtvB, dsB, dsvB);
			for (auto& job : jobs) {
				D3D11_TEXTURE2D_DESC sd2{ kTile * 3, kTile, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, { 1, 0 }, D3D11_USAGE_STAGING, 0, D3D11_CPU_ACCESS_READ, 0 };
				a_device->CreateTexture2D(&sd2, nullptr, &job.stagingA);
				sd2.Width = sd2.Height = kFinal;
				a_device->CreateTexture2D(&sd2, nullptr, &job.stagingB);
			}
			if (!vs || !ps || !constants || !sampler || !raster || !depthState || !blendState || !white || !rtvA || !dsvA || !rtvB || !dsvB) {
				logger::error("icons: couldn't make what drawing needs");
				return false;
			}
			initFailed = false;
			logger::info("icons: ready to draw Skyrim's item pictures");
			return true;
		}

		ID3D11InputLayout* LayoutFor(ID3D11Device* a_device, std::uint64_t a_desc, bool a_full)
		{
			const auto&   desc = reinterpret_cast<const RE::BSGraphics::VertexDesc&>(a_desc);
			const auto    flags = desc.GetFlags();
			using V = RE::BSGraphics::Vertex;
			// Only what the layout depends on: flags and offsets.
			const std::uint64_t key = (std::uint64_t(a_full) << 63) | (std::uint64_t(flags) << 32) | (desc.GetAttributeOffset(V::VA_TEXCOORD0) << 16) | (desc.GetAttributeOffset(V::VA_NORMAL) << 8) |
			                          desc.GetAttributeOffset(V::VA_COLOR);
			if (const auto it = layouts.find(key); it != layouts.end()) {
				return it->second;
			}
			const bool               full = a_full;
			D3D11_INPUT_ELEMENT_DESC elements[4] = {
				{ "POSITION", 0, full ? DXGI_FORMAT_R32G32B32_FLOAT : DXGI_FORMAT_R16G16B16A16_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "TEXCOORD", 0, DXGI_FORMAT_R16G16_FLOAT, 0, (flags & V::VF_UV) ? desc.GetAttributeOffset(V::VA_TEXCOORD0) : 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "NORMAL", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, (flags & V::VF_NORMAL) ? desc.GetAttributeOffset(V::VA_NORMAL) : 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, (flags & V::VF_COLORS) ? desc.GetAttributeOffset(V::VA_COLOR) : 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
			};
			ID3D11InputLayout* layout = nullptr;
			a_device->CreateInputLayout(elements, 4, vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), &layout);
			layouts.emplace(key, layout);
			return layout;
		}

		void DrawModel(ID3D11Device* a_device, ID3D11DeviceContext* a_context, const Model& a_model, const View& a_view)
		{
			Constants c{};
			const float h = a_view.half, d = a_view.depth;
			const auto& r = a_view.right;
			const auto& u = a_view.up;
			const auto& f = a_view.forward;
			const auto& o = a_view.center;
			const float rows[16] = {
				r.x / h, r.y / h, r.z / h, -r.Dot(o) / h,
				u.x / h, u.y / h, u.z / h, -u.Dot(o) / h,
				f.x / (2 * d), f.y / (2 * d), f.z / (2 * d), (-f.Dot(o) + d) / (2 * d),
				0, 0, 0, 1
			};
			std::memcpy(c.viewProj, rows, sizeof(rows));
			const float rot[12] = { r.x, r.y, r.z, 0, u.x, u.y, u.z, 0, f.x, f.y, f.z, 0 };
			std::memcpy(c.viewRot, rot, sizeof(rot));
			for (const auto& part : a_model.parts) {
				using V = RE::BSGraphics::Vertex;
				const auto& desc = reinterpret_cast<const RE::BSGraphics::VertexDesc&>(part.desc);
				const auto  flags = desc.GetFlags();
				auto*       layout = LayoutFor(a_device, part.desc, part.full);
				if (!layout) {
					continue;
				}
				std::memcpy(c.world, part.world, sizeof(c.world));
				c.opts[0] = (flags & V::VF_UV) ? 1.0f : 0.0f;
				c.opts[1] = (flags & V::VF_NORMAL) ? 1.0f : 0.0f;
				c.opts[2] = (flags & V::VF_COLORS) ? 1.0f : 0.0f;
				c.opts[3] = part.alphaTest ? 1.0f : 0.0f;
				D3D11_MAPPED_SUBRESOURCE mapped{};
				if (FAILED(a_context->Map(constants, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
					continue;
				}
				std::memcpy(mapped.pData, &c, sizeof(c));
				a_context->Unmap(constants, 0);
				const UINT offset = 0;
				a_context->IASetInputLayout(layout);
				a_context->IASetVertexBuffers(0, 1, &part.vb, &part.stride, &offset);
				a_context->IASetIndexBuffer(part.ib, DXGI_FORMAT_R16_UINT, 0);
				auto* srv = part.srv ? part.srv : white;
				a_context->PSSetShaderResources(0, 1, &srv);
				a_context->DrawIndexed(part.indexCount, 0, 0);
			}
		}

		View AxisView(const Model& a_model, int a_axis)
		{
			View v;
			v.center = a_model.center;
			v.half = a_model.radius;
			v.depth = a_model.radius;
			switch (a_axis) {
			case 0:  // from the front
				v.right = { 1, 0, 0 };
				v.up = { 0, 0, 1 };
				v.forward = { 0, 1, 0 };
				break;
			case 1:  // from the side
				v.right = { 0, 1, 0 };
				v.up = { 0, 0, 1 };
				v.forward = { -1, 0, 0 };
				break;
			default:  // from above
				v.right = { 1, 0, 0 };
				v.up = { 0, 1, 0 };
				v.forward = { 0, 0, -1 };
				break;
			}
			return v;
		}

		// From the trial views, where the model really is: re-centred and resized if it ran off their
		// edges or filled only a little of them (its stored bounds can be off). True: draw them again.
		bool Refit(Model& a_model, const std::uint8_t* a_pixels, UINT a_pitch)
		{
			const float R = a_model.radius;
			float       lo[3]{ 1e9f, 1e9f, 1e9f }, hi[3]{ -1e9f, -1e9f, -1e9f };
			bool        edge = false, any = false;
			int         widest = 0;
			// Which model axes each view's right and up run along (front: x, z; side: y, z; above: x, y).
			constexpr int kAxes[3][2] = { { 0, 2 }, { 1, 2 }, { 0, 1 } };
			for (int view = 0; view < 3; ++view) {
				int x0 = kTile, y0 = kTile, x1 = -1, y1 = -1;
				for (UINT y = 0; y < kTile; ++y) {
					const auto* row = a_pixels + y * a_pitch + view * kTile * 4;
					for (UINT x = 0; x < kTile; ++x) {
						if (row[x * 4 + 3] > 0) {
							x0 = std::min(x0, int(x));
							x1 = std::max(x1, int(x));
							y0 = std::min(y0, int(y));
							y1 = std::max(y1, int(y));
						}
					}
				}
				if (x1 < 0) {
					continue;
				}
				any = true;
				edge = edge || x0 == 0 || y0 == 0 || x1 == int(kTile) - 1 || y1 == int(kTile) - 1;
				widest = std::max({ widest, x1 - x0 + 1, y1 - y0 + 1 });
				const float pixel = 2.0f * R / kTile;
				const float rx0 = -R + x0 * pixel, rx1 = -R + (x1 + 1) * pixel;
				const float uy0 = R - (y1 + 1) * pixel, uy1 = R - y0 * pixel;
				const int   ax = kAxes[view][0], ay = kAxes[view][1];
				lo[ax] = std::min(lo[ax], rx0);
				hi[ax] = std::max(hi[ax], rx1);
				lo[ay] = std::min(lo[ay], uy0);
				hi[ay] = std::max(hi[ay], uy1);
			}
			if (!any) {
				a_model.radius = R * 4.0f;  // nowhere to be seen: look wider
				return true;
			}
			for (int k = 0; k < 3; ++k) {
				if (lo[k] > hi[k]) {
					lo[k] = hi[k] = 0.0f;
				}
			}
			// (The side view looks along -x, so its right runs along +y as used above.)
			const RE::NiPoint3 mid{ (lo[0] + hi[0]) * 0.5f, (lo[1] + hi[1]) * 0.5f, (lo[2] + hi[2]) * 0.5f };
			const RE::NiPoint3 half{ (hi[0] - lo[0]) * 0.5f, (hi[1] - lo[1]) * 0.5f, (hi[2] - lo[2]) * 0.5f };
			a_model.center = a_model.center + mid;
			if (edge) {
				a_model.radius = std::max(R * 2.5f, half.Length() * 1.3f);
				return true;
			}
			const float fitted = std::max(0.5f, half.Length() * 1.1f);
			if (widest < int(kTile) / 2 || fitted < R * 0.7f) {
				a_model.radius = fitted;
				return true;
			}
			return false;
		}

		// From the trial views: the one showing the most, turned and fitted.
		View FinalView(const Model& a_model, const std::uint8_t* a_pixels, UINT a_pitch)
		{
			int    best = 0;
			int    bestCount = -1;
			for (int axis = 0; axis < 3; ++axis) {
				int count = 0;
				for (UINT y = 0; y < kTile; ++y) {
					const auto* row = a_pixels + y * a_pitch + axis * kTile * 4;
					for (UINT x = 0; x < kTile; ++x) {
						count += row[x * 4 + 3] > 0;
					}
				}
				if (count > bestCount) {
					bestCount = count;
					best = axis;
				}
			}
			View v = AxisView(a_model, best);
			const float R = a_model.radius;
			std::vector<std::pair<float, float>> points;
			double sx = 0, sy = 0;
			for (UINT y = 0; y < kTile; ++y) {
				const auto* row = a_pixels + y * a_pitch + best * kTile * 4;
				for (UINT x = 0; x < kTile; ++x) {
					if (row[x * 4 + 3] > 0) {
						const float px = ((float(x) + 0.5f) / kTile * 2.0f - 1.0f) * R;
						const float py = (1.0f - (float(y) + 0.5f) / kTile * 2.0f) * R;
						points.emplace_back(px, py);
						sx += px;
						sy += py;
					}
				}
			}
			if (points.empty()) {
				return v;
			}
			const double mx = sx / points.size(), my = sy / points.size();
			double       cxx = 0, cyy = 0, cxy = 0;
			for (const auto& [px, py] : points) {
				cxx += (px - mx) * (px - mx);
				cyy += (py - my) * (py - my);
				cxy += (px - mx) * (py - my);
			}
			const double theta = 0.5 * std::atan2(2.0 * cxy, cxx - cyy);
			const double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
			const double l1 = tr / 2 + std::sqrt(std::max(0.0, tr * tr / 4 - det)), l2 = std::max(1e-9, tr / 2 - std::sqrt(std::max(0.0, tr * tr / 4 - det)));
			double       phi = 0.0;
			if (std::sqrt(l1 / l2) > 1.8) {
				// Long things point up and right, the end nearest the model's origin (a weapon's grip)
				// at the bottom left.
				double       dx = std::cos(theta), dy = std::sin(theta);
				const double ox = -(v.right.Dot(v.center)), oy = -(v.up.Dot(v.center));
				if ((mx - ox) * dx + (my - oy) * dy < 0) {
					dx = -dx;
					dy = -dy;
				}
				phi = 0.78539816339744831 - std::atan2(dy, dx);
			}
			const float  cp = float(std::cos(phi)), sp = float(std::sin(phi));
			const auto   r = v.right, u = v.up;
			v.right = r * cp - u * sp;
			v.up = r * sp + u * cp;
			float lo[2]{ 1e9f, 1e9f }, hi[2]{ -1e9f, -1e9f };
			const float pixel = R / kTile * 2.0f;
			for (const auto& [px, py] : points) {
				const float qx = px * cp - py * sp, qy = px * sp + py * cp;
				lo[0] = std::min(lo[0], qx - pixel);
				lo[1] = std::min(lo[1], qy - pixel);
				hi[0] = std::max(hi[0], qx + pixel);
				hi[1] = std::max(hi[1], qy + pixel);
			}
			const float midX = (lo[0] + hi[0]) * 0.5f, midY = (lo[1] + hi[1]) * 0.5f;
			v.center = v.center + v.right * midX + v.up * midY;
			v.half = std::max(hi[0] - lo[0], hi[1] - lo[1]) * 0.5f * 1.06f;
			v.depth = R * 2.0f;
			return v;
		}

		void Release(Model& a_model)
		{
			for (auto& part : a_model.parts) {
				SafeRelease(part.srv);
			}
			a_model = Model{};
		}

		struct SavedState
		{
			ID3D11RenderTargetView*   rtv[D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT]{};
			ID3D11DepthStencilView*   dsv = nullptr;
			ID3D11BlendState*         blend = nullptr;
			float                     factor[4]{};
			UINT                      mask = 0;
			ID3D11RasterizerState*    raster = nullptr;
			ID3D11DepthStencilState*  depth = nullptr;
			UINT                      stencil = 0;
			D3D11_VIEWPORT            vps[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE]{};
			UINT                      vpCount = D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE;
			D3D11_PRIMITIVE_TOPOLOGY  topo{};
			ID3D11InputLayout*        layout = nullptr;
			ID3D11Buffer*             vb = nullptr;
			UINT                      vbStride = 0, vbOffset = 0;
			ID3D11Buffer*             ib = nullptr;
			DXGI_FORMAT               ibFormat{};
			UINT                      ibOffset = 0;
			ID3D11VertexShader*       vs = nullptr;
			ID3D11PixelShader*        ps = nullptr;
			ID3D11GeometryShader*     gs = nullptr;
			ID3D11HullShader*         hs = nullptr;
			ID3D11DomainShader*       ds = nullptr;
			ID3D11ShaderResourceView* srv = nullptr;
			ID3D11SamplerState*       sampler = nullptr;
			ID3D11Buffer*             vsCb = nullptr;
			ID3D11Buffer*             psCb = nullptr;

			void Save(ID3D11DeviceContext* c)
			{
				c->OMGetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, rtv, &dsv);
				c->OMGetBlendState(&blend, factor, &mask);
				c->RSGetState(&raster);
				c->OMGetDepthStencilState(&depth, &stencil);
				c->RSGetViewports(&vpCount, vps);
				c->IAGetPrimitiveTopology(&topo);
				c->IAGetInputLayout(&layout);
				c->IAGetVertexBuffers(0, 1, &vb, &vbStride, &vbOffset);
				c->IAGetIndexBuffer(&ib, &ibFormat, &ibOffset);
				c->VSGetShader(&vs, nullptr, nullptr);
				c->PSGetShader(&ps, nullptr, nullptr);
				c->GSGetShader(&gs, nullptr, nullptr);
				c->HSGetShader(&hs, nullptr, nullptr);
				c->DSGetShader(&ds, nullptr, nullptr);
				c->PSGetShaderResources(0, 1, &srv);
				c->PSGetSamplers(0, 1, &sampler);
				c->VSGetConstantBuffers(0, 1, &vsCb);
				c->PSGetConstantBuffers(0, 1, &psCb);
			}

			void Restore(ID3D11DeviceContext* c)
			{
				c->OMSetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, rtv, dsv);
				c->OMSetBlendState(blend, factor, mask);
				c->RSSetState(raster);
				c->OMSetDepthStencilState(depth, stencil);
				c->RSSetViewports(vpCount, vps);
				c->IASetPrimitiveTopology(topo);
				c->IASetInputLayout(layout);
				c->IASetVertexBuffers(0, 1, &vb, &vbStride, &vbOffset);
				c->IASetIndexBuffer(ib, ibFormat, ibOffset);
				c->VSSetShader(vs, nullptr, 0);
				c->PSSetShader(ps, nullptr, 0);
				c->GSSetShader(gs, nullptr, 0);
				c->HSSetShader(hs, nullptr, 0);
				c->DSSetShader(ds, nullptr, 0);
				c->PSSetShaderResources(0, 1, &srv);
				c->PSSetSamplers(0, 1, &sampler);
				c->VSSetConstantBuffers(0, 1, &vsCb);
				c->PSSetConstantBuffers(0, 1, &psCb);
				for (auto*& r : rtv) {
					SafeRelease(r);
				}
				SafeRelease(dsv);
				SafeRelease(blend);
				SafeRelease(raster);
				SafeRelease(depth);
				SafeRelease(layout);
				SafeRelease(vb);
				SafeRelease(ib);
				SafeRelease(vs);
				SafeRelease(ps);
				SafeRelease(gs);
				SafeRelease(hs);
				SafeRelease(ds);
				SafeRelease(srv);
				SafeRelease(sampler);
				SafeRelease(vsCb);
				SafeRelease(psCb);
			}
		};

		void BeginPass(ID3D11DeviceContext* a_context, ID3D11RenderTargetView* a_rtv, ID3D11DepthStencilView* a_dsv)
		{
			const float clear[4]{ 0, 0, 0, 0 };
			const float factor[4]{};
			a_context->ClearRenderTargetView(a_rtv, clear);
			a_context->ClearDepthStencilView(a_dsv, D3D11_CLEAR_DEPTH, 1.0f, 0);
			a_context->OMSetRenderTargets(1, &a_rtv, a_dsv);
			a_context->OMSetBlendState(blendState, factor, 0xFFFFFFFF);
			a_context->OMSetDepthStencilState(depthState, 0);
			a_context->RSSetState(raster);
			a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
			a_context->VSSetShader(vs, nullptr, 0);
			a_context->PSSetShader(ps, nullptr, 0);
			a_context->GSSetShader(nullptr, nullptr, 0);
			a_context->HSSetShader(nullptr, nullptr, 0);
			a_context->DSSetShader(nullptr, nullptr, 0);
			a_context->VSSetConstantBuffers(0, 1, &constants);
			a_context->PSSetConstantBuffers(0, 1, &constants);
			a_context->PSSetSamplers(0, 1, &sampler);
		}

		// The final view's picture, halved (2x2 averaged, by coverage) into the slot.
		void Publish(const Model& a_model, const std::uint8_t* a_pixels, UINT a_pitch)
		{
			constexpr UINT              n = proto::kSkyIconSize;
			std::vector<std::uint8_t>   out(n * n * 4);
			bool                        any = false;
			for (UINT y = 0; y < n; ++y) {
				for (UINT x = 0; x < n; ++x) {
					float rgb[3]{}, alpha = 0;
					for (UINT k = 0; k < 4; ++k) {
						const auto* p = a_pixels + (y * 2 + k / 2) * a_pitch + (x * 2 + k % 2) * 4;
						const float a = p[3] / 255.0f;
						rgb[0] += p[0] * a;
						rgb[1] += p[1] * a;
						rgb[2] += p[2] * a;
						alpha += a;
					}
					auto* o = &out[(y * n + x) * 4];
					if (alpha > 0) {
						o[0] = std::uint8_t(std::clamp(rgb[0] / alpha, 0.0f, 255.0f));
						o[1] = std::uint8_t(std::clamp(rgb[1] / alpha, 0.0f, 255.0f));
						o[2] = std::uint8_t(std::clamp(rgb[2] / alpha, 0.0f, 255.0f));
						o[3] = std::uint8_t(std::clamp(alpha / 4.0f * 255.0f, 0.0f, 255.0f));
						any = true;
					}
				}
			}
			if (any) {
				Link::Get().WriteSkyIcon(a_model.slot, a_model.key, out.data());
			}
			if (logged++ < 40) {
				logger::info("icons: {} drawn ({} parts{})", a_model.name, a_model.parts.size(), any ? "" : ", but nothing showed");
			}
		}
	}

	void Want(const proto::SkyItem* a_items, std::size_t a_count)
	{
		wanted.clear();
		for (std::uint32_t i = 0; i < 18; ++i) {
			wanted.push_back({ proto::kSkyIconCategory + i, 0, 0 });
		}
		for (std::size_t i = 0; i < a_count; ++i) {
			wanted.push_back({ a_items[i].formId, a_items[i].formId, Hash(a_items[i].name) });
		}
	}

	void PerFrame()
	{
		if (!Link::Get().Valid()) {
			return;
		}
		++tick;
		std::unordered_set<std::uint32_t> keys;
		for (const auto& w : wanted) {
			keys.insert(w.key);
			if (const auto it = slotOf.find(w.key); it != slotOf.end()) {
				lastUse[it->second] = tick;
			}
		}
		{
			std::scoped_lock guard(lock);
			if (queue.size() >= 8) {
				return;
			}
		}
		int loads = 0;
		for (const auto& w : wanted) {
			if (loads >= kLoadsPerFrame) {
				break;
			}
			const auto drawn = drawnName.find(w.key);
			if ((drawn != drawnName.end() && drawn->second == w.nameHash) || noModel.contains(w.key)) {
				continue;
			}
			drawnName[w.key] = w.nameHash;
			++loads;
			const bool   category = w.key >= proto::kSkyIconCategory;
			RE::TESForm* form = category ? CategoryForm(w.key - proto::kSkyIconCategory) : RE::TESForm::LookupByID(w.formId);
			const char*  path = ModelOf(form);
			Model        model;
			model.key = w.key;
			model.name = form && form->GetName() ? form->GetName() : "?";
			if (category) {
				model.name = std::format("category {} ({})", w.key - proto::kSkyIconCategory, model.name);
			}
			if (!path || !Load(model, path)) {
				noModel.insert(w.key);
				if (!path && logged++ < 40) {
					logger::info("icons: {} has no model to draw", model.name);
				}
				continue;
			}
			model.slot = PickSlot(w.key, keys);
			lastUse[model.slot] = tick;
			std::scoped_lock guard(lock);
			queue.push_back(std::move(model));
		}
	}

	void Draw(ID3D11Device* a_device, ID3D11DeviceContext* a_context)
	{
		{
			std::scoped_lock guard(lock);
			if (queue.empty() && std::ranges::none_of(jobs, [](const Job& j) { return j.busy; })) {
				return;
			}
		}
		if (!InitResources(a_device)) {
			std::scoped_lock guard(lock);
			for (auto& model : queue) {
				Release(model);
			}
			queue.clear();
			return;
		}
		SavedState saved;
		saved.Save(a_context);
		for (auto& job : jobs) {
			if (!job.busy) {
				std::scoped_lock guard(lock);
				if (queue.empty()) {
					continue;
				}
				job.model = std::move(queue.front());
				queue.pop_front();
				job.busy = true;
				job.stage = 0;
				job.waited = 0;
				job.refits = 0;
			}
			if (job.stage == 0) {
				BeginPass(a_context, rtvA, dsvA);
				for (int axis = 0; axis < 3; ++axis) {
					D3D11_VIEWPORT vp{ float(axis * kTile), 0, float(kTile), float(kTile), 0, 1 };
					a_context->RSSetViewports(1, &vp);
					DrawModel(a_device, a_context, job.model, AxisView(job.model, axis));
				}
				a_context->CopyResource(job.stagingA, rtA);
				job.stage = 1;
				continue;
			}
			const bool trial = job.stage == 1;
			D3D11_MAPPED_SUBRESOURCE mapped{};
			const HRESULT hr = a_context->Map(trial ? job.stagingA : job.stagingB, 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped);
			if (hr == DXGI_ERROR_WAS_STILL_DRAWING && ++job.waited < 120) {
				continue;
			}
			if (FAILED(hr)) {
				Release(job.model);
				job.busy = false;
				continue;
			}
			if (trial && job.refits < 4 && Refit(job.model, static_cast<const std::uint8_t*>(mapped.pData), mapped.RowPitch)) {
				a_context->Unmap(job.stagingA, 0);
				++job.refits;
				job.stage = 0;  // the trial views again, next frame
				job.waited = 0;
				continue;
			}
			if (trial) {
				job.final = FinalView(job.model, static_cast<const std::uint8_t*>(mapped.pData), mapped.RowPitch);
				a_context->Unmap(job.stagingA, 0);
				BeginPass(a_context, rtvB, dsvB);
				D3D11_VIEWPORT vp{ 0, 0, float(kFinal), float(kFinal), 0, 1 };
				a_context->RSSetViewports(1, &vp);
				DrawModel(a_device, a_context, job.model, job.final);
				a_context->CopyResource(job.stagingB, rtB);
				job.stage = 2;
				job.waited = 0;
			} else {
				Publish(job.model, static_cast<const std::uint8_t*>(mapped.pData), mapped.RowPitch);
				a_context->Unmap(job.stagingB, 0);
				Release(job.model);
				job.busy = false;
			}
		}
		saved.Restore(a_context);
	}
}
