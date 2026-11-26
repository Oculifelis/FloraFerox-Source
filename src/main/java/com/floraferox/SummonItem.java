package com.floraferox;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;

/**
 * A boss's summon (docs/DESIGN.md section 15): Royal Nectar on a hopper den, a Thorned Rose on a Briar Throne, Carrion Bait
 * on or under a Carrion Bud. Used where it fits, it wakes that home's boss and is spent; anywhere else it does nothing.
 */
public class SummonItem extends Item {
    /** Where it was used to the home it calls on (null if none), and the call (the boss, or null if it would not come). */
    private final BiFunction<BlockGetter, BlockPos, BlockPos> home;
    private final BiFunction<ServerLevel, BlockPos, ?> wake;

    public SummonItem(Properties properties, BiFunction<BlockGetter, BlockPos, BlockPos> home, BiFunction<ServerLevel, BlockPos, ?> wake) {
        super(properties);
        this.home = home;
        this.wake = wake;
    }

    /** A home that is just the block used on, when it passes the test. */
    public static BiFunction<BlockGetter, BlockPos, BlockPos> on(BiPredicate<BlockGetter, BlockPos> is) {
        return (level, pos) -> is.test(level, pos) ? pos : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        BlockPos at = home.apply(ctx.getLevel(), ctx.getClickedPos());
        if (at == null) return InteractionResult.PASS;
        if (!(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        if (wake.apply(level, at) == null) return InteractionResult.FAIL;
        if (ctx.getPlayer() == null || !ctx.getPlayer().getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }
}
