package com.floraferox;

import com.floraferox.VerdureRules.Wild;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.AABB;

/**
 * The Carrion Bud (docs/DESIGN.md section 15.3): a great closed bud hanging from a lush cave's ceiling over moss. Carrion
 * Bait laid on it, or on the ground under it, makes it drop: the Carrion Bloom roots where it lands. Spent, it regrows
 * HOME_REST later.
 */
public class CarrionBudBlock extends Block {
    public static final MapCodec<CarrionBudBlock> CODEC = simpleCodec(CarrionBudBlock::new);
    public static final BooleanProperty SPENT = BooleanProperty.create("spent");
    /** How far under the bud its bait may be laid, and the floor it drops to. */
    public static final int DROP = 16;

    public CarrionBudBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SPENT, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(SPENT);
    }

    /** The bait is taken: the bud drops and the Bloom roots on the floor below it (null if it couldn't). */
    public static CarrionBloom wake(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof CarrionBudBlock) || s.getValue(SPENT)) return null;
        BlockPos floor = floor(level, pos);
        if (floor == null) return null;
        var b = VerdureEntities.wild(Wild.CARRION_BLOOM).spawn(level, floor, EntitySpawnReason.TRIGGERED);
        if (!(b instanceof CarrionBloom bloom)) return null;
        rest(level, pos, true);
        bloom.wakeAt(level, pos);
        level.playSound(null, floor, SoundEvents.SPORE_BLOSSOM_BREAK, SoundSource.HOSTILE, 3F, 0.4F);
        level.sendParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 60, 0.6, 0.2, 0.6, 0);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, floor.getX() + 0.5, floor.getY() + 0.5, floor.getZ() + 0.5, 80, 1.5, 0.5, 1.5, 0);
        return bloom;
    }

    /** Spent (till HOME_REST from now) or ready again. */
    public static void rest(ServerLevel level, BlockPos pos, boolean spent) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof CarrionBudBlock)) return;
        level.setBlock(pos, s.setValue(SPENT, spent), Block.UPDATE_ALL);
        if (spent) level.scheduleTick(pos, s.getBlock(), VerdureRules.HOME_REST);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(SPENT)) return;
        boolean alive = !level.getEntitiesOfClass(CarrionBloom.class, new AABB(pos).inflate(VerdureRules.BLOOM_LEASH),
                b -> b.isAlive() && pos.equals(b.home())).isEmpty();
        if (alive) level.scheduleTick(pos, this, VerdureRules.HOME_REST);
        else rest(level, pos, false);
    }

    /** The open spot on the first solid floor under the bud, or null. */
    public static BlockPos floor(BlockGetter level, BlockPos bud) {
        for (int dy = 1; dy <= DROP; dy++) {
            BlockPos p = bud.below(dy);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                if (level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return p;
            } else return dy > 1 && level.getBlockState(p).getCollisionShape(level, p).max(Direction.Axis.Y) <= 0.5 ? p : null;   // a moss carpet
        }
        return null;
    }

    /** A bud not spent at pos or over it (bait laid on the floor under it), or null. */
    public static BlockPos near(BlockGetter level, BlockPos pos) {
        for (int dy = 0; dy <= DROP; dy++)
            for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, dy, -1), pos.offset(1, dy, 1))) {
                BlockState s = level.getBlockState(p);
                if (s.getBlock() instanceof CarrionBudBlock && !s.getValue(SPENT)) return p.immutable();
            }
        return null;
    }

    /** Worldgen: a bud under a lush cave's ceiling, over a moss floor with room for the Bloom between. */
    public static final class BudFeature extends Feature<NoneFeatureConfiguration> {
        public BudFeature() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
            var level = ctx.level();
            BlockPos at = ctx.origin();
            if (!level.getBlockState(at).isAir()) return false;
            BlockPos floor = null, ceiling = null;
            for (int dy = 0; dy <= 12 && floor == null; dy++) if (!level.getBlockState(at.below(dy + 1)).isAir()) floor = at.below(dy);
            for (int dy = 0; dy <= 12 && ceiling == null; dy++) if (!level.getBlockState(at.above(dy + 1)).isAir()) ceiling = at.above(dy);
            if (floor == null || ceiling == null || ceiling.getY() - floor.getY() < 5) return false;
            if (!(level.getBlockState(floor.below()).is(Blocks.MOSS_BLOCK) || level.getBlockState(floor.below()).is(Blocks.MOSS_CARPET)) || !level.getBlockState(ceiling.above()).isFaceSturdy(level, ceiling.above(), Direction.DOWN)) return false;
            level.setBlock(ceiling, VerdureBlocks.CARRION_BUD.get().defaultBlockState(), 2);
            return true;
        }
    }
}
