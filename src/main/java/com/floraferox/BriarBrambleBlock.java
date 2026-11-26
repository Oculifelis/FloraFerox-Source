package com.floraferox;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Briar King's brambles (docs/DESIGN.md section 15.2): the wall round his court and the thorn walls he raises in it.
 * Like a berry bush, but thicker: what pushes through is slowed hard and pricked; the King and his court walk it freely.
 */
public class BriarBrambleBlock extends Block {
    public static final MapCodec<BriarBrambleBlock> CODEC = simpleCodec(BriarBrambleBlock::new);

    public BriarBrambleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean precise) {
        if (!(entity instanceof LivingEntity) || entity instanceof BriarKing || entity instanceof WildMob w && w.profile == VerdureRules.Wild.ROOT_GRUB) return;
        entity.makeStuckInBlock(state, new Vec3(0.5, 0.6, 0.5));
        if (level instanceof ServerLevel server) {
            Vec3 moved = entity.isClientAuthoritative() ? entity.getKnownMovement() : entity.oldPosition().subtract(entity.position());
            if (moved.horizontalDistanceSqr() > 0 && server.getGameTime() % 10 == 0) entity.hurtServer(server, level.damageSources().sweetBerryBush(), 2.0F);
        }
    }
}
