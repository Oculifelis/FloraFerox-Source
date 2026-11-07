package com.floraferox;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The Grafting Bench: two inputs spliced into a seed (Splicing), laid out as tools/gen/verdure/art.py draws the screen. */
public class GraftingMenu extends ItemCombinerMenu {
    public GraftingMenu(int id, Inventory inventory) {
        this(id, inventory, ContainerLevelAccess.NULL);
    }

    public GraftingMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(VerdureBlocks.GRAFTING_MENU.get(), id, inventory, access, ItemCombinerMenuSlotDefinition.create()
                .withSlot(0, 27, 47, s -> true)
                .withSlot(1, 76, 47, s -> true)
                .withResultSlot(2, 134, 47)
                .build());
    }

    @Override
    protected boolean mayPickup(Player player, boolean hasItem) {
        return hasItem;
    }

    @Override
    protected void onTake(Player player, ItemStack carried) {
        carried.onCraftedBy(player, carried.getCount());
        inputSlots.removeItem(0, 1);
        inputSlots.removeItem(1, 1);
        access.execute((level, pos) -> level.playSound(null, pos, SoundEvents.COMPOSTER_FILL_SUCCESS, SoundSource.BLOCKS, 1F, 1.2F));
    }

    @Override
    protected boolean isValidBlock(BlockState state) {
        return state.is(VerdureBlocks.GRAFTING_BENCH.get());
    }

    @Override
    public void createResult() {
        resultSlots.setItem(0, Splicing.result(inputSlots.getItem(0), inputSlots.getItem(1)));
        broadcastChanges();
    }
}
