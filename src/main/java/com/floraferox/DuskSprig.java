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
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * The Dusk Sprig (docs/DESIGN.md, section 2.3): the Duskpetal come alive, a maw on a stalk. It hops at its prey by
 * night, rears back and gapes (the tell), lunges and snaps; by day, with no prey, it roots in place (DuskSprigBrain).
 * Weak to magic.
 */
public class DuskSprig extends Monster {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(DuskSprig.class, EntityDataSerializers.INT);
    public final DuskSprigBrain brain = new DuskSprigBrain();
    /** For tests: how many bites landed, and whether daylight is forced (null: the sky decides). */
    public int bites;
    public Boolean debugDaylight;

    public DuskSprig(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, HEALTH).add(Attributes.ATTACK_DAMAGE, ATTACK).add(Attributes.ARMOR, ARMOR)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE).add(Attributes.MOVEMENT_SPEED, 0.26).add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(ANIM, 0);
    }

    /** The brain's state and its ticks, synced for the renderer: state * 1000 + ticks. */
    public int anim() { return entityData.get(ANIM); }

    public boolean daylight() {
        if (debugDaylight != null) return debugDaylight;
        return level().isBrightOutside() && level().canSeeSky(blockPosition());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new HopGoal());
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8) {
            @Override
            public boolean canUse() { return !brain.still() && super.canUse(); }
        });
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // a night bloom: it only goes hunting in the dark (hurt by day, it still answers)
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, (p, l) -> !daylight()));
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        LivingEntity t = getTarget();
        boolean prey = t != null && t.isAlive();
        double d = prey ? Math.max(0.0, distanceTo(t) - t.getBbWidth() * 0.5) : Double.MAX_VALUE;
        switch (brain.tick(prey, d, prey && hasLineOfSight(t), onGround(), daylight(), random::nextInt)) {
            case REAR -> playSound(SoundEvents.FOX_AGGRO, 1.0F, 0.6F);
            case LEAP -> {
                Vec3 to = t.position().subtract(position());
                Vec3 flat = new Vec3(to.x, 0, to.z);
                flat = flat.lengthSqr() < 1.0E-4 ? getLookAngle() : flat.normalize();
                setDeltaMovement(flat.x * SPROUT_LEAP_SPEED, SPROUT_LEAP_UP, flat.z * SPROUT_LEAP_SPEED);
                needsSync = true;
                playSound(SoundEvents.GRASS_BREAK, 1.0F, 0.8F);
            }
            case BITE -> {
                playSound(SoundEvents.FOX_BITE, 1.2F, 0.7F);
                if (prey && d <= SPROUT_BITE_REACH + 0.6 && doHurtTarget((net.minecraft.server.level.ServerLevel) level(), t)) bites++;
            }
            default -> { }
        }
        if (brain.still()) {
            getNavigation().stop();
            if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            if (prey) getLookControl().setLookAt(t, 40F, 40F);
        }
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        return super.hurtServer(level, source, VerdureDamage.scale(Kind.DUSK_SPRIG, source, amount));
    }

    @Override
    protected SoundEvent getAmbientSound() { return brain.state() == DuskSprigBrain.State.ROOTED ? null : SoundEvents.FOX_SNIFF; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.FOX_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.FOX_DEATH; }

    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        playSound(SoundEvents.GRASS_STEP, 0.4F, 1.1F);
    }

    /** Natural spawns: at night, on grass or dirt under open sky, where monsters may. */
    public static boolean checkSpawn(EntityType<DuskSprig> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource r) {
        if (reason == EntitySpawnReason.SPAWNER) return level.getDifficulty() != Difficulty.PEACEFUL;
        return checkMonsterSpawnRules(type, level, reason, pos, r) && level.canSeeSky(pos) && level.getBlockState(pos.below()).is(BlockTags.DIRT);
    }

    /** It walks at its prey in little hops (Terraria's snowman behaviour), stopping when it strikes or roots. */
    private final class HopGoal extends Goal {
        private int repath;

        HopGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() { return getTarget() != null && getTarget().isAlive(); }

        @Override
        public void stop() { getNavigation().stop(); }

        @Override
        public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            getLookControl().setLookAt(t, 30F, 30F);
            if (brain.still() || brain.state() == DuskSprigBrain.State.LUNGE) return;
            if (--repath <= 0) {
                repath = 5 + random.nextInt(5);
                getNavigation().moveTo(t, 1.0);
            }
            if (onGround() && getNavigation().isInProgress() && random.nextInt(10) == 0) getJumpControl().jump();
        }
    }
}
