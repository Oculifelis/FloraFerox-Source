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
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.AABB;

/**
 * The Briar Throne (docs/DESIGN.md section 15.2): a mossy stump in a ring of rose bushes, deep in the dark forest. A
 * Thorned Rose laid on it by night raises the Briar King; one King to a throne, and a spent throne regrows HOME_REST later.
 */
public class BriarThroneBlock extends Block {
    public static final MapCodec<BriarThroneBlock> CODEC = simpleCodec(BriarThroneBlock::new);
    public static final BooleanProperty SPENT = BooleanProperty.create("spent");

    public BriarThroneBlock(Properties properties) {
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

    /** The Thorned Rose's call: by night, on a throne not spent, the King rises (null if he couldn't). */
    public static BriarKing wake(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof BriarThroneBlock) || s.getValue(SPENT) || level.isBrightOutside()) return null;
        var k = VerdureEntities.wild(Wild.BRIAR_KING).spawn(level, pos.above(), EntitySpawnReason.TRIGGERED);
        if (!(k instanceof BriarKing king)) return null;
        rest(level, pos, true);
        king.wakeAt(level, pos);
        level.playSound(null, pos, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 3F, 0.4F);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, 60, 1.5, 1, 1.5, 0);
        return king;
    }

    /** Spent (till HOME_REST from now) or ready again. */
    public static void rest(ServerLevel level, BlockPos pos, boolean spent) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof BriarThroneBlock)) return;
        level.setBlock(pos, s.setValue(SPENT, spent), Block.UPDATE_ALL);
        if (spent) level.scheduleTick(pos, s.getBlock(), VerdureRules.HOME_REST);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(SPENT)) return;
        boolean alive = !level.getEntitiesOfClass(BriarKing.class, new AABB(pos).inflate(VerdureRules.KING_LEASH),
                b -> b.isAlive() && pos.equals(b.home())).isEmpty();
        if (alive) level.scheduleTick(pos, this, VerdureRules.HOME_REST);
        else rest(level, pos, false);
    }

    /** Worldgen: the throne in place of the forest floor it is placed on, rose bushes in a ring round it. */
    public static final class ThroneFeature extends Feature<NoneFeatureConfiguration> {
        public ThroneFeature() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
            var level = ctx.level();
            RandomSource r = ctx.random();
            BlockPos at = ctx.origin();
            if (!level.getBlockState(at.below()).is(BlockTags.DIRT) || !clear(level, at) || !clear(level, at.above())) return false;
            level.setBlock(at, VerdureBlocks.BRIAR_THRONE.get().defaultBlockState(), 2);
            level.setBlock(at.below(), Blocks.MOSS_BLOCK.defaultBlockState(), 2);
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 * i / 10 + r.nextDouble() * 0.3;
                int dx = (int) Math.round(Math.cos(a) * 3), dz = (int) Math.round(Math.sin(a) * 3);
                BlockPos p = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.offset(dx, 0, dz));
                if (Math.abs(p.getY() - at.getY()) > 2 || !level.getBlockState(p.below()).is(BlockTags.DIRT) || !clear(level, p) || !clear(level, p.above())) continue;
                if (r.nextInt(3) == 0) level.setBlock(p.below(), Blocks.MOSS_BLOCK.defaultBlockState(), 2);
                DoublePlantBlock.placeAt(level, Blocks.ROSE_BUSH.defaultBlockState(), p, 2);
            }
            return true;
        }

        private static boolean clear(WorldGenLevel level, BlockPos at) {
            BlockState s = level.getBlockState(at);
            return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof DoublePlantBlock);
        }
    }
}
