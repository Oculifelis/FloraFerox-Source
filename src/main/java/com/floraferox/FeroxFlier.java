package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Ferox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Lantern Leech: a FeroxMob that floats a little over the ground, drifts at its prey's chest, and splits its husk
 * open to bite (and drinks: VerdureRules.LEECH_HEAL).
 */
public class FeroxFlier extends FeroxMob {
    public FeroxFlier(EntityType<? extends Monster> type, Level level, Ferox profile) {
        super(type, level, profile);
        moveControl = new FlyingMoveControl(this, 10, true);
        setNoGravity(true);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    protected void chase(LivingEntity t) {
        Vec3 off = position().subtract(t.position()).multiply(1, 0, 1);
        if (flee > 0) {                                                               // 1.5: having drunk, it backs off and up
            Vec3 away = off.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : off.normalize();
            getMoveControl().setWantedPosition(getX() + away.x * 5, getY() + 1.5, getZ() + away.z * 5, 1.1);
            return;
        }
        off = off.lengthSqr() < 1.0E-4 ? Vec3.ZERO : off.normalize().scale(0.9 + t.getBbWidth() * 0.5);
        getMoveControl().setWantedPosition(t.getX() + off.x, t.getY() + t.getBbHeight() * 0.5 - getBbHeight() * 0.4, t.getZ() + off.z, 1.0);
    }

    @Override
    protected void customServerAiStep(ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        if (getTarget() == null && getNavigation().isDone()) {                // it bobs a little over the ground where it is
            int ground = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, getBlockX(), getBlockZ());
            double dy = ground + LEECH_HOVER - getY();
            setDeltaMovement(getDeltaMovement().multiply(0.9, 0.8, 0.9).add(0, Math.max(-0.04, Math.min(0.04, dy * 0.02)), 0));
        }
    }

    @Override
    public boolean causeFallDamage(double dist, float mult, DamageSource src) { return false; }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) { }
}
