package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Wild;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.AirAndWaterRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A WildMob with flying navigation. The End fliers roam through open air, while the other fliers hover near the ground.
 */
public class WildFlier extends WildMob {
    public WildFlier(EntityType<? extends Monster> type, Level level, Wild profile) {
        super(type, level, profile);
        moveControl = new FlyingMoveControl(this, 10, true);
        setNoGravity(true);
        if (profile.fliesFreely()) goalSelector.addGoal(7, new AirRoamGoal());
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level) {
            @Override
            public boolean isStableDestination(BlockPos pos) {
                if (!profile.fliesFreely()) return super.isStableDestination(pos);
                return level.isInWorldBounds(pos) && level.getBlockState(pos).isAir();
            }
        };
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    protected void chase(LivingEntity t) {
        if (fleeing() || kites(t)) {                                                 // off and up, out of its prey's reach
            Vec3 to = position().add(awayFrom(t).scale(5)).add(0, 1.5, 0);
            flyToward(to, 1.1);
            return;
        }
        Vec3 off = position().subtract(t.position()).multiply(1, 0, 1);
        double want = profile.style == VerdureRules.Style.SHOT ? profile.reach * 0.7 : 0.6;
        off = off.lengthSqr() < 1.0E-4 ? Vec3.ZERO : off.normalize().scale(want + t.getBbWidth() * 0.5 + getBbWidth() * 0.5);
        double y = t.getY() + t.getBbHeight() * 0.5 - getBbHeight() * 0.4;
        if (profile.fliesFreely()) y = Math.max(t.getY(), y);
        flyToward(new Vec3(t.getX() + off.x, y, t.getZ() + off.z), 1.0);
    }

    private void flyToward(Vec3 to, double speed) {
        if (profile.fliesFreely()) getNavigation().moveTo(to.x, to.y, to.z, speed);
        else getMoveControl().setWantedPosition(to.x, to.y, to.z, speed);
    }

    @Override
    public void travel(Vec3 input) {
        if (profile.fliesFreely()) travelFlying(input, 0.04F);
        else super.travel(input);
    }

    /** Picks air within reach of its current altitude, with no search for a floor below. */
    private final class AirRoamGoal extends RandomStrollGoal {
        private AirRoamGoal() { super(WildFlier.this, 0.8, 40); }

        @Override
        public boolean canUse() {
            return hunts() && getTarget() == null && getNavigation().isDone() && super.canUse();
        }

        @Override
        protected Vec3 getPosition() {
            Vec3 facing = getViewVector(0F);
            Vec3 to = AirAndWaterRandomPos.getPos(WildFlier.this, 8, 4, 0, facing.x, facing.z, Math.PI);
            return to != null && getNavigation().isStableDestination(BlockPos.containing(to)) ? to : null;
        }
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (!profile.fliesFreely() && getTarget() == null && getNavigation().isDone() && !isVehicle()) {
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
