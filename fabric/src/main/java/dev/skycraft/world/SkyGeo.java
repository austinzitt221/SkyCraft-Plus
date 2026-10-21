// SkyCraft+ : Skyrim geo placed as Minecraft blocks
package dev.skycraft.world;

import com.mojang.serialization.MapCodec;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's ground and rock as Minecraft blocks. Mined with silk touch, a cell of Skyrim's geometry
 * doesn't break into its Minecraft block (dirt, stone): it comes out as a block with the very texture
 * Skyrim used there (skycraft:skyrim_block), a plain cube that builds like any other. Skyrim draws it:
 * Minecraft sends its faces with the rest of the blocks (WorldExporter), its texture drawn into the
 * atlas by Skyrim itself (SkyGeoTextures). The creative menu has one for every texture Skyrim's land
 * is painted with. (The first version kept the mined piece's own shape, skycraft:skyrim_geo; the
 * restoration brush puts Skyrim's ground back as it was instead, so those are plain blocks now: still
 * there in worlds that have them, drawn as cubes, and turned into blocks when picked up.)
 */
public final class SkyGeo {
	private SkyGeo() {
	}

	public static final String TAG = "skycraft_geo";
	/** The client's (WorldExporter): a placed piece's data arrived, its section is drawn again. */
	public static volatile java.util.function.Consumer<BlockPos> clientChanged = pos -> {
	};
	/** At most this many triangles of a piece's surface are kept. */
	public static final int MAX_TRIS = 96;

	public static final ResourceKey<Block> GEO_KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_geo"));
	public static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_block"));
	public static final Block GEO = Registry.register(BuiltInRegistries.BLOCK, GEO_KEY, new GeoBlock(props(GEO_KEY)));
	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY, new GeoBlock(props(BLOCK_KEY)));
	public static final Item GEO_ITEM = Registry.register(BuiltInRegistries.ITEM, ResourceKey.create(Registries.ITEM, GEO_KEY.identifier()),
		new BlockItem(GEO, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, GEO_KEY.identifier())).useBlockDescriptionPrefix()));
	public static final Item BLOCK_ITEM = Registry.register(BuiltInRegistries.ITEM, ResourceKey.create(Registries.ITEM, BLOCK_KEY.identifier()),
		new BlockItem(BLOCK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BLOCK_KEY.identifier())).useBlockDescriptionPrefix()));
	public static final BlockEntityType<GeoEntity> ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, GEO_KEY.identifier(),
		new BlockEntityType<>(GeoEntity::new, Set.of(GEO, BLOCK)));

	private static BlockBehaviour.Properties props(ResourceKey<Block> key) {
		return BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.2F, 4.0F).sound(SoundType.STONE).setId(key);
	}

	// ---- a piece's data ----------------------------------------------------------------------

	/** A piece of Skyrim: its surface (triangles in the cell, 0..1, three packed vertices each), what it's made of, its texture. */
	public record Piece(int[] tris, int material, String texture) {
		public static Piece of(CompoundTag tag) {
			return new Piece(tag.getIntArray("tris").orElse(new int[0]), tag.getIntOr("material", Proto.DIG_STONE), tag.getStringOr("texture", ""));
		}

		CompoundTag tag() {
			CompoundTag tag = new CompoundTag();
			tag.putIntArray("tris", this.tris);
			tag.putInt("material", this.material);
			tag.putString("texture", this.texture);
			return tag;
		}

		/** A packed vertex (x, y, z each 0..1 in tenths of a millimetre... 1/1023ths) and back. */
		public static int pack(double x, double y, double z) {
			return q(x) | q(y) << 10 | q(z) << 20;
		}

		private static int q(double v) {
			return (int) Math.round(Math.clamp(v, 0.0, 1.0) * 1023.0);
		}

		public static float unpack(int packed, int axis) {
			return ((packed >> (axis * 10)) & 1023) / 1023.0F;
		}
	}

	public static @Nullable Piece piece(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? null : data.copyTag().getCompoundOrEmpty(TAG);
		return tag == null || tag.isEmpty() ? null : Piece.of(tag);
	}

	/** "landscape\\roads\\road01.dds" -> "Road"; "Landscape\\Grass\\GrassLand01.dds" -> "Grass Land". */
	public static String textureName(String path) {
		String file = path.replace('/', '\\');
		file = file.substring(file.lastIndexOf('\\') + 1);
		int dot = file.lastIndexOf('.');
		if (dot > 0) {
			file = file.substring(0, dot);
		}
		file = file.replaceAll("[0-9_]+", " ").trim();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < file.length(); i++) {
			char c = file.charAt(i);
			if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(file.charAt(i - 1))) {
				out.append(' ');
			}
			out.append(i == 0 || out.length() > 0 && out.charAt(out.length() - 1) == ' ' ? Character.toUpperCase(c) : c);
		}
		String name = out.toString().replaceAll(" +", " ").trim();
		return name.isEmpty() ? "Ground" : name;
	}

	/** What its icon looks like (the items' model picks it): the Minecraft block it's filled with. */
	private static String look(int material) {
		return switch (material) {
			case Proto.DIG_GRASS -> "grass";
			case Proto.DIG_DIRT, Proto.DIG_MUD -> "dirt";
			case Proto.DIG_SNOW, Proto.DIG_ICE -> "snow";
			case Proto.DIG_SAND -> "sand";
			case Proto.DIG_GRAVEL -> "gravel";
			case Proto.DIG_COBBLE -> "cobblestone";
			case Proto.DIG_OAK_LOG, Proto.DIG_SPRUCE_LOG, Proto.DIG_BIRCH_LOG, Proto.DIG_PLANKS -> "wood";
			default -> "stone";
		};
	}

	/** A Skyrim geo piece (geo true) or a Skyrim-textured block of a piece. */
	public static ItemStack stack(Piece piece, boolean geo, int count) {
		ItemStack stack = new ItemStack(geo ? GEO_ITEM : BLOCK_ITEM, count);
		CompoundTag root = new CompoundTag();
		root.put(TAG, (geo ? piece : new Piece(new int[0], piece.material(), piece.texture())).tag());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		String name = "Skyrim " + textureName(piece.texture()) + (geo ? "" : " Block");
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withItalic(false)));
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(look(piece.material())), List.of()));
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(geo ? "A piece of Skyrim's ground, as it was where it was mined" : "A block with Skyrim's texture")
			.withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GRAY)), Component.literal(geo ? "Craft it on its own for a Skyrim-textured block" : piece.texture())
			.withStyle(st -> st.withItalic(false).withColor(ChatFormatting.DARK_GRAY)))));
		return stack;
	}

	/** A flat piece (the whole top of the cell), for the creative menu's textures. */
	public static Piece flat(int material, String texture) {
		int a = Piece.pack(0, 1, 0), b = Piece.pack(1, 1, 0), c = Piece.pack(1, 1, 1), d = Piece.pack(0, 1, 1);
		return new Piece(new int[] { a, d, c, a, c, b }, material, texture);
	}

	// ---- mining with silk touch --------------------------------------------------------------

	/** Client -> server: a Skyrim cell mined with silk touch: dug out (as SkyNet.DigOpen), and this piece of it dropped. */
	public record Mine(int world, BlockPos pos, int material, int[] tris, String texture) implements CustomPacketPayload {
		public static final Type<Mine> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "geo_mine"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Mine> CODEC = StreamCodec.of((buf, m) -> {
			buf.writeInt(m.world);
			buf.writeBlockPos(m.pos);
			buf.writeVarInt(m.material);
			buf.writeVarInt(Math.min(m.tris.length, MAX_TRIS * 3));
			for (int i = 0; i < Math.min(m.tris.length, MAX_TRIS * 3); i++) {
				buf.writeInt(m.tris[i]);
			}
			buf.writeUtf(m.texture, 240);
		}, buf -> {
			int world = buf.readInt();
			BlockPos pos = buf.readBlockPos();
			int material = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), MAX_TRIS * 3);
			int[] tris = new int[n];
			for (int i = 0; i < n; i++) {
				tris[i] = buf.readInt();
			}
			return new Mine(world, pos, material, tris, buf.readUtf(240));
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Whether this tool has silk touch (Minecraft's own enchantment, on a Minecraft tool or a Skyrim one's shortcut). */
	public static boolean silkTouch(Level level, ItemStack tool) {
		if (tool.isEmpty()) {
			return false;
		}
		var lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		return lookup.get(Enchantments.SILK_TOUCH).map(silk -> EnchantmentHelper.getItemEnchantmentLevel(silk, tool) > 0).orElse(false);
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Mine.TYPE, Mine.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Mine.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				Piece piece = new Piece(new int[0], payload.material(), payload.texture());
				SkyDig.open(player, payload.world(), payload.pos(), payload.material(), stack(piece, false, 1));
			});
		});
		// An old geo piece picked up (or already held): the Skyrim-textured block it would have crafted into.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 7) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				var inventory = player.getInventory();
				for (int i = 0; i < inventory.getContainerSize(); i++) {
					ItemStack stack = inventory.getItem(i);
					Piece piece = stack.is(GEO_ITEM) ? piece(stack) : null;
					if (stack.is(GEO_ITEM)) {
						inventory.setItem(i, piece != null ? stack(piece, false, stack.getCount()) : ItemStack.EMPTY);
					}
				}
			}
		});
	}

	// ---- the blocks --------------------------------------------------------------------------

	static final class GeoBlock extends BaseEntityBlock {
		GeoBlock(BlockBehaviour.Properties properties) {
			super(properties);
		}

		@Override
		public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new GeoEntity(pos, state);
		}

		/** Skyrim draws it (WorldExporter sends its faces); Minecraft's own renderer has nothing to do. */
		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.INVISIBLE;
		}
	}

	/** A placed piece: its data, from the item it was placed from (and back into what it drops). */
	public static final class GeoEntity extends BlockEntity {
		private CompoundTag data = new CompoundTag();
		private @Nullable Component name;

		GeoEntity(BlockPos pos, BlockState state) {
			super(ENTITY, pos, state);
		}

		public Piece piece() {
			return Piece.of(this.data);
		}

		/** (Always a cube now: see the class comment.) */
		public boolean geo() {
			return false;
		}

		@Override
		protected void saveAdditional(ValueOutput output) {
			super.saveAdditional(output);
			output.store("geo", CompoundTag.CODEC, this.data);
			output.storeNullable("CustomName", net.minecraft.network.chat.ComponentSerialization.CODEC, this.name);
		}

		@Override
		protected void loadAdditional(ValueInput input) {
			super.loadAdditional(input);
			this.data = input.read("geo", CompoundTag.CODEC).orElse(new CompoundTag());
			this.name = parseCustomNameSafe(input, "CustomName");
			if (this.level != null && this.level.isClientSide()) {
				clientChanged.accept(this.getBlockPos());  // (its data came after the block: drawn again with it)
			}
		}

		@Override
		public ClientboundBlockEntityDataPacket getUpdatePacket() {
			return ClientboundBlockEntityDataPacket.create(this);
		}

		@Override
		public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
			return this.saveWithoutMetadata(registries);
		}

		@Override
		protected void applyImplicitComponents(DataComponentGetter components) {
			super.applyImplicitComponents(components);
			CustomData custom = components.get(DataComponents.CUSTOM_DATA);
			this.data = custom == null ? new CompoundTag() : custom.copyTag().getCompoundOrEmpty(TAG);
			this.name = components.get(DataComponents.CUSTOM_NAME);
		}

		@Override
		protected void collectImplicitComponents(DataComponentMap.Builder components) {
			super.collectImplicitComponents(components);
			CompoundTag root = new CompoundTag();
			root.put(TAG, this.data.copy());
			components.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			components.set(DataComponents.CUSTOM_NAME, this.name);
			components.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(look(this.piece().material())), List.of()));
		}

		@Override
		public void removeComponentsFromTag(ValueOutput output) {
			output.discard("geo");
			output.discard("CustomName");
		}
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
