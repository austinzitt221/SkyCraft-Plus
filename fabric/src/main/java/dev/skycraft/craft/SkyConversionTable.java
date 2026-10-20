// SkyCraft+ : the conversion table
package dev.skycraft.craft;

import dev.skycraft.SkyCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The conversion table: a crafting table of another colour that turns Minecraft materials into the
 * same Skyrim ones and back (SkyMaterials), for keeping them on the other side: Minecraft iron put
 * in the top slot comes out as Skyrim iron (taken into the inventory, it goes into Skyrim's, out of
 * Minecraft's way), Skyrim iron put in the bottom slot (from the Skyrim panel) comes out as
 * Minecraft's. A whole stack at a time. Made from a crafting table and an iron ingot.
 */
public final class SkyConversionTable extends Block {
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "conversion_table"));
	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY,
		new SkyConversionTable(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(2.5F).sound(SoundType.WOOD).ignitedByLava().setId(KEY)));
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, KEY.identifier());
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix()));
	public static final MenuType<Menu> MENU = Registry.register(BuiltInRegistries.MENU, KEY.identifier(), new MenuType<>(Menu::new, FeatureFlags.VANILLA_SET));
	private static final Component TITLE = Component.translatable("container.skycraft.conversion_table");
	/** The tint of its crafting-table faces (BlockColorRegistry, client). */
	public static final int TINT = 0x8FC8FF;

	private SkyConversionTable(BlockBehaviour.Properties properties) {
		super(properties);
	}

	public static void init() {
		SkyCraft.LOG.debug("SkyCraft: {} registered", KEY.identifier());
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS)
			.register(output -> output.accept(ITEM));
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide()) {
			player.openMenu(state.getMenuProvider(level, pos));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
		return new SimpleMenuProvider((id, inventory, player) -> new Menu(id, inventory, ContainerLevelAccess.create(level, pos)), TITLE);
	}

	/** Top: a Minecraft material; bottom: a Skyrim one; right: the same thing from the other game. Laid out as a grindstone's. */
	public static final class Menu extends AbstractContainerMenu {
		public static final int MINECRAFT_SLOT = 0, SKYRIM_SLOT = 1, RESULT_SLOT = 2;
		private final ContainerLevelAccess access;
		private final Player player;
		private final Container inputs = new SimpleContainer(2) {
			@Override
			public void setChanged() {
				super.setChanged();
				Menu.this.slotsChanged(this);
			}
		};
		private final Container result = new ResultContainer();

		public Menu(int id, Inventory inventory) {
			this(id, inventory, ContainerLevelAccess.NULL);
		}

		Menu(int id, Inventory inventory, ContainerLevelAccess access) {
			super(MENU, id);
			this.access = access;
			this.player = inventory.player;
			this.addSlot(new Slot(this.inputs, MINECRAFT_SLOT, 49, 19) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return SkyMaterials.hasSkyrimMatch(stack);
				}
			});
			this.addSlot(new Slot(this.inputs, SKYRIM_SLOT, 49, 40) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return SkyMaterials.convertible(stack) != null;
				}
			});
			this.addSlot(new Slot(this.result, RESULT_SLOT, 129, 34) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return false;
				}

				@Override
				public void onTake(Player taker, ItemStack carried) {
					Menu.this.inputs.setItem(Menu.this.inputs.getItem(MINECRAFT_SLOT).isEmpty() ? SKYRIM_SLOT : MINECRAFT_SLOT, ItemStack.EMPTY);
					Menu.this.access.execute((level, pos) -> level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.2F));
					super.onTake(taker, carried);
				}
			});
			this.addStandardInventorySlots(inventory, 8, 84);
		}

		@Override
		public void slotsChanged(Container container) {
			super.slotsChanged(container);
			if (container == this.inputs && !this.player.level().isClientSide()) {
				this.result.setItem(0, this.convert());
				this.broadcastChanges();
			}
		}

		/** The top slot's Minecraft stack as Skyrim's, or else the bottom slot's Skyrim stack as Minecraft's. */
		private ItemStack convert() {
			ItemStack minecraft = this.inputs.getItem(MINECRAFT_SLOT);
			if (!minecraft.isEmpty()) {
				return SkyMaterials.hasSkyrimMatch(minecraft) ? SkyMaterials.storedOf(minecraft.getItem(), minecraft.getCount()) : ItemStack.EMPTY;
			}
			ItemStack skyrim = this.inputs.getItem(SKYRIM_SLOT);
			Item match = SkyMaterials.convertible(skyrim);
			return match == null ? ItemStack.EMPTY : new ItemStack(match, Math.min(skyrim.getCount(), match.getDefaultMaxStackSize()));
		}

		@Override
		public ItemStack quickMoveStack(Player who, int index) {
			Slot slot = this.slots.get(index);
			if (!slot.hasItem()) {
				return ItemStack.EMPTY;
			}
			ItemStack stack = slot.getItem();
			ItemStack before = stack.copy();
			int inventoryStart = RESULT_SLOT + 1, inventoryEnd = this.slots.size();
			if (index == RESULT_SLOT) {
				if (!this.moveItemStackTo(stack, inventoryStart, inventoryEnd, true)) {
					return ItemStack.EMPTY;
				}
				slot.onQuickCraft(stack, before);
			} else if (index < RESULT_SLOT) {
				if (!this.moveItemStackTo(stack, inventoryStart, inventoryEnd, false)) {
					return ItemStack.EMPTY;
				}
			} else if (SkyMaterials.hasSkyrimMatch(stack)) {
				if (!this.moveItemStackTo(stack, MINECRAFT_SLOT, MINECRAFT_SLOT + 1, false)) {
					return ItemStack.EMPTY;
				}
			} else {
				return ItemStack.EMPTY;
			}
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
			if (stack.getCount() == before.getCount()) {
				return ItemStack.EMPTY;
			}
			slot.onTake(who, stack);
			return before;
		}

		@Override
		public void removed(Player who) {
			super.removed(who);
			this.access.execute((level, pos) -> this.clearContainer(who, this.inputs));
		}

		@Override
		public boolean stillValid(Player who) {
			return stillValid(this.access, who, BLOCK);
		}
	}
}
