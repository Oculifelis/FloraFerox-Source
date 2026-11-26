package com.floraferox;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The Rotting Offering (docs/DESIGN.md section 14): used on a Dormant Behemoth's mound, it wakes the Behemoth. One made
 * with an Ember or Rime Graft carries that look in its custom data ("look": "ember" | "rime") and wakes it in that look.
 */
public class RottingOfferingItem extends Item {
    /** How far from where it is used the offering finds the core. */
    public static final int REACH = 6;

    public RottingOfferingItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        var state = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (!state.is(VerdureBlocks.BEHEMOTH_MOUND.get()) && !state.is(VerdureBlocks.BEHEMOTH_CORE.get())) return InteractionResult.PASS;
        if (!(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos core = BehemothCoreBlock.near(level, ctx.getClickedPos(), REACH);
        if (core == null || BehemothCoreBlock.wake(level, core, look(ctx.getItemInHand().get(DataComponents.CUSTOM_DATA))) == null) return InteractionResult.FAIL;
        if (ctx.getPlayer() == null || !ctx.getPlayer().getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }

    static String look(CustomData data) {
        return data == null ? null : data.copyTag().getString("look").orElse(null);
    }
}
