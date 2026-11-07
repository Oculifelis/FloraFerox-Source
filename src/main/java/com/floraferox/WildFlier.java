package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Wild;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 1.4's fliers (the Pollen Puff, Pearl Pod, Void Jelly, Lanternpuff, the Rift Narwhal as RiftNarwhal): a WildMob that
 * floats over the ground and drifts at its prey's chest, a shooter only to its range.
 */
public class WildFlier extends WildMob {
    public WildFlier(EntityType<? extends Monster> type, Level level, Wild profile) {
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
        if (fleeing() || kites(t)) {                                                 // off and up, out of its prey's reach
            Vec3 to = position().add(awayFrom(t).scale(5)).add(0, 1.5, 0);
            getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.1);
            return;
        }
        Vec3 off = position().subtract(t.position()).multiply(1, 0, 1);
        double want = profile.style == VerdureRules.Style.SHOT ? profile.reach * 0.7 : 0.6;
        off = off.lengthSqr() < 1.0E-4 ? Vec3.ZERO : off.normalize().scale(want + t.getBbWidth() * 0.5 + getBbWidth() * 0.5);
        getMoveControl().setWantedPosition(t.getX() + off.x, t.getY() + t.getBbHeight() * 0.5 - getBbHeight() * 0.4, t.getZ() + off.z, 1.0);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (getTarget() == null && getNavigation().isDone() && !isVehicle()) {     // it bobs a little over the ground where it is
            int ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING, getBlockX(), getBlockZ());
            double dy = ground + WILD_HOVER - getY();
            setDeltaMovement(getDeltaMovement().multiply(0.9, 0.8, 0.9).add(0, Math.max(-0.04, Math.min(0.04, dy * 0.02)), 0));
        }
    }

    @Override
    public boolean causeFallDamage(double dist, float mult, DamageSource src) { return false; }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) { }
}
