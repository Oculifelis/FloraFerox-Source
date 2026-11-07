package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Kind;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * Game-bus wiring for the Flora Ferox mobs (docs/DESIGN.md, sections 3 and 7): picking a grown herb wakes its
 * family's monster ("similar to fishing during a blood moon"), and (1.3) its burning and frozen strains wake one of the
 * family's new hunters (section 8); picking a flower that looks like one of the herbs now and then does too (1.0's
 * stand-in, kept as a rare fallback).
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureEvents {
    private VerdureEvents() { }

    /** The family whose herb the flower looks like, or null. */
    public static Kind family(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SUNFLOWER) return Kind.DUSK_SPRIG;                                   // the Duskpetal: a gold-ringed sun
        if (b == Blocks.ROSE_BUSH || b == Blocks.POPPY || b == Blocks.PEONY || b == Blocks.PINK_TULIP) return Kind.PULSE_GNAT;
        if (b == Blocks.BLUE_ORCHID || b == Blocks.CORNFLOWER) return Kind.MOON_TICK;          // the Moonwisp: a blue bud
        if (b == Blocks.LILY_PAD || b == Blocks.MOSS_CARPET || b == Blocks.MANGROVE_PROPAGULE) return Kind.MIRE_GHAST;
        return null;
    }

    public static EntityType<? extends Mob> type(Kind kind) {
        return switch (kind) {
            case PULSE_GNAT -> VerdureEntities.PULSE_GNAT.get();
            case MIRE_GHAST -> VerdureEntities.MIRE_GHAST.get();
            case DUSK_SPRIG -> VerdureEntities.DUSK_SPRIG.get();
            case MOON_TICK -> VerdureEntities.MOON_TICK.get();
        };
    }

    @SubscribeEvent
    public static void harvest(BreakBlockEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getPlayer().isSpectator()) return;
        if (e.getState().getBlock() instanceof HerbCropBlock crop) {
            if (crop.isMaxAge(e.getState()) && level.getRandom().nextInt(VerdureRules.CROP_WAKE_ONE_IN) == 0) wake(level, e.getPos(), crop.herb);
            return;
        }
        Kind kind = family(e.getState());
        if (kind == null || level.getRandom().nextInt(VerdureRules.HARVEST_ONE_IN) != 0) return;
        wake(level, e.getPos(), kind);
    }

    /** The family's monster rises where the flower was. */
    public static Mob wake(ServerLevel level, BlockPos pos, Kind kind) {
        return rise(level, pos, type(kind), kind == Kind.PULSE_GNAT ? 0.5 : 0.0);
    }

    /** A grown herb's monster: the first strain's is its family's first; the later strains' one of the family's six. */
    public static Mob wake(ServerLevel level, BlockPos pos, Herb herb) {
        if (herb.tier == 1) return wake(level, pos, herb.kind);
        List<Ferox> hunters = Arrays.stream(Ferox.values()).filter(f -> f.kind == herb.kind).toList();
        Ferox f = hunters.get(level.getRandom().nextInt(hunters.size()));
        return rise(level, pos, VerdureEntities.ferox(f), f == Ferox.LANTERN_LEECH ? 0.5 : 0.0);
    }

    private static Mob rise(ServerLevel level, BlockPos pos, EntityType<? extends Mob> type, double up) {
        Mob mob = type.create(level, EntitySpawnReason.EVENT);
        if (mob == null) return null;
        mob.snapTo(pos.getX() + 0.5, pos.getY() + up, pos.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
        level.addFreshEntity(mob);
        return mob;
    }
}
