package com.floraferox;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Flora Ferox herb in the ground: four ages, as beetroots grow (Terraria's herbs have three stages and a bloom), on
 * farmland. Picked grown, it wakes its family's monster (VerdureEvents.harvest); its drops are in its loot table.
 */
public class HerbCropBlock extends CropBlock {
    public static final IntegerProperty AGE = BlockStateProperties.AGE_3;
    private static final VoxelShape[] SHAPES = Block.boxes(3, age -> Block.column(16.0, 0.0, 3 + age * 3));

    public final Herb herb;

    public HerbCropBlock(Herb herb, BlockBehaviour.Properties properties) {
        super(properties);
        this.herb = herb;
    }

    @Override
    public MapCodec<? extends CropBlock> codec() {
        return simpleCodec(p -> new HerbCropBlock(herb, p));
    }

    @Override
    protected IntegerProperty getAgeProperty() {
        return AGE;
    }

    @Override
    public int getMaxAge() {
        return 3;
    }

    @Override
    protected ItemLike getBaseSeedId() {
        return VerdureItems.SEEDS.get(herb).get();
    }

    /** The later strains grow slower: a third of ticks skipped per tier past the first. */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(herb.tier + 1) == 0) super.randomTick(state, level, pos, random);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[getAge(state)];
    }
}
