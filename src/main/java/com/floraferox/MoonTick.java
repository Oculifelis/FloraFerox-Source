package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Kind;
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
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * The Moon Tick (docs/DESIGN.md, section 2.4): the Moonwisp come alive, a beetle under crystal wing cases. It
 * hops at its prey in long bounds (Terraria's slime behaviour as Hoppin' Jack), opening its cases in the air so it glides, and
 * hurts what it touches (MoonTickBrain). Weak to summons.
 */
public class MoonTick extends Monster {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(MoonTick.class, EntityDataSerializers.INT);
    public final MoonTickBrain brain = new MoonTickBrain();
    private int touchCooldown;
    /** For tests: hops made and touches that hurt. */
    public int hops, touches;

    public MoonTick(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, HEALTH).add(Attributes.ATTACK_DAMAGE, ATTACK).add(Attributes.ARMOR, ARMOR)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE).add(Attributes.MOVEMENT_SPEED, 0.2).add(Attributes.FOLLOW_RANGE, 32.0);
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
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        LivingEntity t = getTarget();
        boolean prey = t != null && t.isAlive();
        switch (brain.tick(prey, onGround(), random::nextInt)) {
            case CROUCH -> {
                if (prey) faceTowards(t.position());
            }
            case JUMP -> {
                Vec3 dir;
                if (prey) dir = new Vec3(t.getX() - getX(), 0, t.getZ() - getZ());
                else {
                    double a = random.nextDouble() * Math.PI * 2;
                    dir = new Vec3(Math.cos(a), 0, Math.sin(a));
                }
                dir = dir.lengthSqr() < 1.0E-4 ? getLookAngle() : dir.normalize();
                double speed = prey ? LOUSE_HOP_SPEED : LOUSE_HOP_SPEED * 0.5;
                setDeltaMovement(dir.x * speed, LOUSE_HOP_UP, dir.z * speed);
                faceTowards(position().add(dir));
                needsSync = true;
                hops++;
                playSound(SoundEvents.SILVERFISH_STEP, 1.0F, 0.6F);
            }
            case LANDED -> {
                setDeltaMovement(getDeltaMovement().multiply(0.2, 1, 0.2));
                playSound(SoundEvents.AMETHYST_BLOCK_HIT, 0.9F, 0.8F);
            }
            default -> { }
        }
        // the cases open in the air and the hind wings buzz: it falls slower than a stone
        if (brain.state() == MoonTickBrain.State.AIR && getDeltaMovement().y < 0) {
            setDeltaMovement(getDeltaMovement().add(0, 0.08 * (1 - LOUSE_GLIDE_GRAVITY), 0));
            if (tickCount % 4 == 0) playSound(SoundEvents.BEE_LOOP, 0.4F, 1.8F);
        }
        if (onGround() && brain.state() != MoonTickBrain.State.AIR) setDeltaMovement(getDeltaMovement().multiply(0.5, 1, 0.5));
        if (touchCooldown > 0) touchCooldown--;
        if (prey && touchCooldown == 0 && getBoundingBox().inflate(0.15).intersects(t.getBoundingBox()) && doHurtTarget((net.minecraft.server.level.ServerLevel) level(), t)) {
            touches++;
            touchCooldown = 20;
        }
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    private void faceTowards(Vec3 p) {
        float yaw = (float) (Mth.atan2(p.z - getZ(), p.x - getX()) * Mth.RAD_TO_DEG) - 90F;
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        return super.hurtServer(level, source, VerdureDamage.scale(Kind.MOON_TICK, source, amount));
    }

    @Override
    protected int calculateFallDamage(double dist, float mult) { return super.calculateFallDamage(dist - 4.0, mult); }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.SILVERFISH_AMBIENT; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.AMETHYST_BLOCK_HIT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.AMETHYST_BLOCK_BREAK; }

    /** Natural spawns: at night under open sky, as monsters may; twice as often under a full moon. */
    public static boolean checkSpawn(EntityType<MoonTick> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource r) {
        if (reason == EntitySpawnReason.SPAWNER) return level.getDifficulty() != Difficulty.PEACEFUL;
        if (!checkMonsterSpawnRules(type, level, reason, pos, r) || !level.canSeeSky(pos)) return false;
        return level.getLevel().getMoonBrightness(pos) >= 1.0F || r.nextBoolean();
    }
}
