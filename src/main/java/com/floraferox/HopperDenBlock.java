package com.floraferox;

import com.floraferox.VerdureRules.Wild;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.AABB;

/**
 * The Thorn Hopper's den (Oculifelis: "give hoppers a den they spawn from bc otherwise they just everywhere"): a bramble
 * burrow set into the grass, the only place hoppers come from. By day, on its random tick, it tops its brood up to
 * DEN_BROOD hoppers, which keep within DEN_HOME blocks of it.
 */
public class HopperDenBlock extends Block {
    public static final MapCodec<HopperDenBlock> CODEC = simpleCodec(HopperDenBlock::new);

    public HopperDenBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        brood(level, pos, random, level.isBrightOutside());
    }

    /** One to two hoppers out of the den, by day, while its brood is short; how many came out. */
    public static int brood(ServerLevel level, BlockPos pos, RandomSource random, boolean day) {
        if (level.getDifficulty() == Difficulty.PEACEFUL || !day) return 0;
        var type = VerdureEntities.wild(Wild.THORN_HOPPER);
        int have = level.getEntities(type, new AABB(pos).inflate(VerdureRules.DEN_HOME * 2), e -> e.isAlive()).size();
        int out = 0;
        for (int i = 0, want = 1 + random.nextInt(2); i < 6 && out < want && have + out < VerdureRules.DEN_BROOD; i++) {
            BlockPos at = ground(level, pos.offset(random.nextInt(5) - 2, 1, random.nextInt(5) - 2));
            if (at == null) continue;
            WildMob hopper = type.spawn(level, at, EntitySpawnReason.SPAWNER);
            if (hopper == null) continue;
            hopper.setHomeTo(pos, VerdureRules.DEN_HOME);
            out++;
        }
        return out;
    }

    /**
     * The highest spot within 3 blocks up or down of near where a hopper can stand: open (short grass and flowers are), not
     * in a berry bush or water, on a solid top. Searched here, not by heightmap, so a roof or overhang is no matter.
     */
    private static BlockPos ground(ServerLevel level, BlockPos near) {
        for (int dy = 3; dy >= -3; dy--) {
            BlockPos at = near.above(dy);
            if (open(level, at) && open(level, at.above()) && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return at;
        }
        return null;
    }

    private static boolean open(ServerLevel level, BlockPos at) {
        BlockState s = level.getBlockState(at);
        return s.getCollisionShape(level, at).isEmpty() && s.getFluidState().isEmpty() && !s.is(Blocks.SWEET_BERRY_BUSH);
    }

    /** Worldgen: the den in place of the grass it is placed on, a ring of sweet berry bushes and trodden dirt round it. */
    public static final class DenFeature extends Feature<NoneFeatureConfiguration> {
        public DenFeature() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
            var level = ctx.level();
            RandomSource r = ctx.random();
            BlockPos ground = ctx.origin().below();
            if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK) || !clear(level, ctx.origin())) return false;
            level.setBlock(ctx.origin(), Blocks.AIR.defaultBlockState(), 2);           // no grass left growing out of it
            level.setBlock(ground, VerdureBlocks.HOPPER_DEN.get().defaultBlockState(), 2);
            for (int i = 0; i < 14; i++) {
                int dx = r.nextInt(7) - 3, dz = r.nextInt(7) - 3;
                if (Math.abs(dx) + Math.abs(dz) < 2) continue;
                BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, ground.offset(dx, 0, dz));
                if (!level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK) || !clear(level, at)) continue;
                if (r.nextInt(3) == 0) level.setBlock(at.below(), Blocks.COARSE_DIRT.defaultBlockState(), 2);
                else level.setBlock(at, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1 + r.nextInt(3)), 2);
            }
            return true;
        }

        /** Air, or a one-block plant (short grass, a flower) it may grow over; not half of a tall one. */
        private static boolean clear(WorldGenLevel level, BlockPos at) {
            BlockState s = level.getBlockState(at);
            return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof DoublePlantBlock);
        }
    }
}
