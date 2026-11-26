package com.floraferox;

import com.floraferox.VerdureRules.Wild;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.AABB;

/**
 * The heart of the Dormant Behemoth (docs/DESIGN.md section 14): a glowing core under a dome of wart that a Rotting
 * Offering wakes. Woken, it is spent and the dome is gone; MOUND_REGROW later it regrows, unless its Behemoth still lives.
 */
public class BehemothCoreBlock extends Block {
    public static final MapCodec<BehemothCoreBlock> CODEC = simpleCodec(BehemothCoreBlock::new);
    public static final BooleanProperty SPENT = BooleanProperty.create("spent");
    /** The dome round the core: this wide and this high. */
    public static final double DOME_RADIUS = 4.5, DOME_HEIGHT = 4.0;

    public BehemothCoreBlock(Properties properties) {
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

    /** Wakes the Behemoth from the core at pos, in the given look (or as its biome rolls it); null if it couldn't. */
    public static CrimsonBehemoth wake(ServerLevel level, BlockPos pos, String look) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof BehemothCoreBlock) || s.getValue(SPENT)) return null;
        clear(level, pos);
        level.setBlock(pos, s.setValue(SPENT, true), Block.UPDATE_ALL);
        var b = VerdureEntities.wild(Wild.CRIMSON_BEHEMOTH).spawn(level, pos.above(), EntitySpawnReason.TRIGGERED);
        if (!(b instanceof CrimsonBehemoth boss)) return null;
        VerdureLooks.Look l = look == null ? null : Looks.ofKind("crimson_behemoth", look);
        if (l != null) Looks.set(boss, l.id());
        boss.wakeAt(level, pos);
        level.playSound(null, pos, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3F, 0.5F);
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5, 200, 3, 2, 3, 0.1);
        return boss;
    }

    /** Its Behemoth fell: the core is spent until MOUND_REGROW from now. */
    public static void spent(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        level.setBlock(pos, s.setValue(SPENT, true), Block.UPDATE_ALL);
        level.scheduleTick(pos, s.getBlock(), VerdureRules.MOUND_REGROW);
    }

    /** Ready again, the dome regrown over it. */
    public static void ready(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, level.getBlockState(pos).setValue(SPENT, false), Block.UPDATE_ALL);
        grow(level, pos);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(SPENT)) return;
        boolean alive = !level.getEntitiesOfClass(CrimsonBehemoth.class, new AABB(pos).inflate(VerdureRules.LEASH_RANGE),
                b -> b.isAlive() && pos.equals(b.core())).isEmpty();
        if (alive) level.scheduleTick(pos, this, VerdureRules.MOUND_REGROW);
        else ready(level, pos);
    }

    /** The dome of wart round the core, where there is room for it. */
    public static void grow(LevelAccessor level, BlockPos core) {
        BlockState mound = VerdureBlocks.BEHEMOTH_MOUND.get().defaultBlockState();
        int r = (int) Math.ceil(DOME_RADIUS);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -1; dy <= DOME_HEIGHT; dy++) {
                    double e = (dx * dx + dz * dz) / (DOME_RADIUS * DOME_RADIUS) + (dy + 1) * (dy + 1) / ((DOME_HEIGHT + 1) * (DOME_HEIGHT + 1));
                    BlockPos p = core.offset(dx, dy, dz);
                    if (e > 1 || p.equals(core)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.canBeReplaced()) level.setBlock(p, mound, Block.UPDATE_ALL);
                }
    }

    /** The dome splits: every mound block round the core goes. */
    public static void clear(LevelAccessor level, BlockPos core) {
        int r = (int) Math.ceil(DOME_RADIUS);
        for (BlockPos p : BlockPos.betweenClosed(core.offset(-r, -1, -r), core.offset(r, (int) DOME_HEIGHT, r)))
            if (level.getBlockState(p).is(VerdureBlocks.BEHEMOTH_MOUND.get())) level.removeBlock(p, false);
    }

    /** The nearest core within reach of pos that can be woken, or null. */
    public static BlockPos near(LevelAccessor level, BlockPos pos, int reach) {
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-reach, -reach, -reach), pos.offset(reach, reach, reach))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof BehemothCoreBlock && !s.getValue(SPENT) && (best == null || p.distSqr(pos) < best.distSqr(pos))) best = p.immutable();
        }
        return best;
    }

    /** Worldgen: the dormant mound, on crimson or warped nylium with room above it. */
    public static final class MoundFeature extends Feature<NoneFeatureConfiguration> {
        public MoundFeature() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
            var level = ctx.level();
            BlockPos at = ctx.origin();
            if (!level.getBlockState(at.below()).is(BlockTags.NYLIUM)) return false;
            for (int dy = 1; dy <= 5; dy++) if (!level.getBlockState(at.above(dy)).isAir() && !level.getBlockState(at.above(dy)).canBeReplaced()) return false;
            level.setBlock(at, VerdureBlocks.BEHEMOTH_CORE.get().defaultBlockState(), 2);
            grow(level, at);
            return true;
        }
    }
}
