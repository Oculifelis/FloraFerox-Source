package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Kind;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Mire Ghast (docs/DESIGN.md, section 2.2): the Mirecap come alive, a lump of bog soil lifted on its roots like a
 * puppet. It walks at its prey like a zombie, then raises both arms (the tell) and slams them down (MireGhastBrain).
 * Weak to melee.
 */
public class MireGhast extends Monster {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(MireGhast.class, EntityDataSerializers.INT);
    public final MireGhastBrain brain = new MireGhastBrain();
    /** For tests: how many slams landed. */
    public int slams;

    public MireGhast(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, HEALTH).add(Attributes.ATTACK_DAMAGE, ATTACK).add(Attributes.ARMOR, ARMOR)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE).add(Attributes.ATTACK_KNOCKBACK, 1.0).add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.FOLLOW_RANGE, 32.0);
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
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new ChaseGoal());
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        LivingEntity t = getTarget();
        boolean prey = t != null && t.isAlive();
        double d = prey ? reach(t) : Double.MAX_VALUE;
        switch (brain.tick(prey, d)) {
            case TELL -> {
                getNavigation().stop();
                playSound(SoundEvents.ZOMBIE_AMBIENT, 1.0F, 0.6F);
            }
            case SLAM -> {
                playSound(SoundEvents.MUD_BREAK, 1.2F, 0.7F);
                if (level() instanceof ServerLevel sl) {
                    Vec3 f = getLookAngle().scale(1.2);
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MUD.defaultBlockState()), getX() + f.x, getY() + 0.2, getZ() + f.z,
                            18, 0.5, 0.1, 0.5, 0.15);
                }
                if (prey && d <= GHOUL_HIT_REACH && hasLineOfSight(t) && doHurtTarget((net.minecraft.server.level.ServerLevel) level(), t)) slams++;
            }
            default -> { }
        }
        if (brain.rooted()) {
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            if (prey) getLookControl().setLookAt(t, 30F, 30F);
        }
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    /** The gap from its middle to the target's hitbox edge, so a tall or wide target is reached alike. */
    private double reach(LivingEntity t) {
        return Math.max(0.0, distanceTo(t) - t.getBbWidth() * 0.5);
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        return super.hurtServer(level, source, VerdureDamage.scale(Kind.MIRE_GHAST, source, amount));
    }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.DROWNED_AMBIENT; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.ZOMBIE_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.ZOMBIE_DEATH; }

    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        playSound(SoundEvents.SLIME_SQUISH_SMALL, 0.35F, 0.8F);
    }

    /** Natural spawns: by day, in the bog (the biome is the spawn list's), on solid ground under open sky. */
    public static boolean checkSpawn(EntityType<MireGhast> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource r) {
        if (reason == EntitySpawnReason.SPAWNER) return level.getDifficulty() != Difficulty.PEACEFUL;
        return level.getDifficulty() != Difficulty.PEACEFUL && level.getLevel().isBrightOutside() && level.canSeeSky(pos)
                && checkMobSpawnRules(type, level, reason, pos, r);
    }

    /** It walks straight at its prey (a zombie's pace) and stops to strike. */
    private final class ChaseGoal extends Goal {
        private int repath;

        ChaseGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

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
            if (brain.rooted()) return;
            if (--repath <= 0) {
                repath = 6 + random.nextInt(6);
                if (reach(t) > 0.8) getNavigation().moveTo(t, 1.0);
                else getNavigation().stop();
            }
        }
    }
}
