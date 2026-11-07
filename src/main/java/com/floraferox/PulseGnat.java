package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Kind;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.HoverRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Pulse Gnat (docs/DESIGN.md, section 2.1): the Pulseroot come alive. A flier that never lands, it orbits its
 * prey and darts through it in a "heart shot" (PulseGnatBrain). Weak to ranged attacks.
 */
public class PulseGnat extends Monster {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(PulseGnat.class, EntityDataSerializers.INT);
    public final PulseGnatBrain brain = new PulseGnatBrain();
    private Vec3 dart = Vec3.ZERO;
    private double orbit;
    /** For tests: how many heart shots struck home. */
    public int hits;

    public PulseGnat(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        moveControl = new FlyingMoveControl(this, 20, true);
        setNoGravity(true);
        xpReward = 5;
        orbit = random.nextDouble() * Math.PI * 2;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, HEALTH).add(Attributes.ATTACK_DAMAGE, ATTACK).add(Attributes.ARMOR, ARMOR)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE).add(Attributes.FLYING_SPEED, 0.6).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(ANIM, 0);
    }

    /** The brain's state and its ticks, synced for the renderer: state * 1000 + ticks. */
    public int anim() { return entityData.get(ANIM); }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(8, new FlutterGoal());
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        LivingEntity t = getTarget();
        boolean prey = t != null && t.isAlive();
        double d = prey ? distanceTo(t) : Double.MAX_VALUE;
        switch (brain.tick(prey, d, prey && hasLineOfSight(t), random::nextInt)) {
            case TELL -> playSound(SoundEvents.BEE_LOOP_AGGRESSIVE, 0.8F, 1.4F);
            case DART -> {
                Vec3 aim = t.position().add(0, t.getBbHeight() * 0.5, 0);
                Vec3 to = aim.subtract(position().add(0, getBbHeight() * 0.5, 0));
                dart = to.lengthSqr() < 1.0E-4 ? getLookAngle() : to.normalize();
                playSound(SoundEvents.PHANTOM_SWOOP, 0.7F, 1.6F);
            }
            default -> { }
        }
        switch (brain.state()) {
            case ORBIT -> {
                orbit += 0.07 + random.nextGaussian() * 0.02;
                double r = (FLY_ORBIT_MIN + FLY_ORBIT_MAX) / 2 + Math.sin(tickCount * 0.05) * (FLY_ORBIT_MAX - FLY_ORBIT_MIN) / 2;
                double y = t.getY() + 1.5 + Math.sin(tickCount * 0.11) * 0.8;
                getMoveControl().setWantedPosition(t.getX() + Math.cos(orbit) * r, y, t.getZ() + Math.sin(orbit) * r, 1.0);
                if (random.nextInt(12) == 0) addDeltaMovement(new Vec3(random.nextGaussian() * 0.08, random.nextGaussian() * 0.05, random.nextGaussian() * 0.08));
                getLookControl().setLookAt(t, 30F, 30F);
            }
            case TELL -> {
                setDeltaMovement(getDeltaMovement().scale(0.6));
                getLookControl().setLookAt(t, 60F, 60F);
            }
            case DART -> {
                setDeltaMovement(dart.scale(FLY_DART_SPEED));          // no wanted position: the move control idles
                setYRot((float) (Mth.atan2(dart.z, dart.x) * Mth.RAD_TO_DEG) - 90F);
                yBodyRot = getYRot();
                if (prey && getBoundingBox().inflate(0.25).intersects(t.getBoundingBox())) {
                    if (doHurtTarget((net.minecraft.server.level.ServerLevel) level(), t)) hits++;
                    brain.struck();
                }
            }
            case RECOVER -> {
                setDeltaMovement(getDeltaMovement().scale(0.85).add(0, 0.03, 0));
                getMoveControl().setWantedPosition(getX(), getY() + 1.0, getZ(), 0.6);
            }
            default -> { }
        }
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        return super.hurtServer(level, source, VerdureDamage.scale(Kind.PULSE_GNAT, source, amount));
    }

    @Override
    public boolean causeFallDamage(double dist, float mult, DamageSource src) { return false; }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) { }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.BEE_LOOP; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.BEE_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.BEE_DEATH; }

    @Override
    public int getAmbientSoundInterval() { return 120; }

    /** Natural spawns: by day, under open sky, on the ground it lifts off from (the source's day-slime spawns). */
    public static boolean checkSpawn(EntityType<PulseGnat> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource r) {
        if (reason == EntitySpawnReason.SPAWNER) return level.getDifficulty() != Difficulty.PEACEFUL;
        return level.getDifficulty() != Difficulty.PEACEFUL && level.getLevel().isBrightOutside() && level.canSeeSky(pos)
                && checkMobSpawnRules(type, level, reason, pos, r);
    }

    /** With no prey it flutters about where it is, jittering like Terraria's bats. */
    private final class FlutterGoal extends Goal {
        FlutterGoal() { setFlags(EnumSet.of(Flag.MOVE)); }

        @Override
        public boolean canUse() { return brain.state() == PulseGnatBrain.State.FLUTTER && getNavigation().isDone() && random.nextInt(8) == 0; }

        @Override
        public boolean canContinueToUse() { return brain.state() == PulseGnatBrain.State.FLUTTER && getNavigation().isInProgress(); }

        @Override
        public void start() {
            Vec3 view = getViewVector(0F);
            Vec3 p = HoverRandomPos.getPos(PulseGnat.this, 8, 4, view.x, view.z, (float) (Math.PI / 2), 3, 1);
            if (p != null) getNavigation().moveTo(getNavigation().createPath(BlockPos.containing(p), 1), 1.0);
        }
    }
}
