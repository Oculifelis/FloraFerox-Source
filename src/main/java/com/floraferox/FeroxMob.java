package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Ferox;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.phys.Vec3;

/**
 * 1.2's six (docs/DESIGN.md, section 8): the Snap Drake, Reed Wretch, Burr Crawler, Orchid Mantis and Dew Crab walk;
 * the Lantern Leech floats (FeroxFlier). Each closes on its prey, shows its tell and strikes (StrikeBrain, with its
 * timings in VerdureRules.Ferox); each is one of the four families and shares that family's weakness.
 */
public class FeroxMob extends Monster {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(FeroxMob.class, EntityDataSerializers.INT);
    public final Ferox profile;
    public final StrikeBrain brain;
    /** For tests: how many blows landed, and how many burrs it threw. */
    public int strikes, shots;
    // ---- 1.5: each one's own ways (docs/DESIGN.md, section 11) ---------------------------------------------------------
    /** Ticks to the second snap or slash (the Snap Drake, the Orchid Mantis); 0 when none is due. */
    private int followUp;
    /** Ticks it keeps away (the Lantern Leech, having drunk), and till the Burr Crawler's next throw. */
    protected int flee;
    private int burrCooldown;

    public FeroxMob(EntityType<? extends Monster> type, Level level, Ferox profile) {
        super(type, level);
        this.profile = profile;
        this.brain = new StrikeBrain(profile);
        xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes(Ferox f) {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, HEALTH).add(Attributes.ATTACK_DAMAGE, ATTACK).add(Attributes.ARMOR, ARMOR)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE).add(Attributes.MOVEMENT_SPEED, f.speed).add(Attributes.FLYING_SPEED, 0.5)
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
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        if (profile() == Ferox.ORCHID_MANTIS) {
            // 1.5: the mantis stands still as a flower and takes only prey that comes close
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true, (t, l) -> t.distanceTo(this) < AMBUSH_RANGE));
            return;
        }
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        if (profile() != Ferox.DEW_CRAB) targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));   // the crab is neutral
    }

    /**
     * Its profile while Mob's constructor runs registerGoals (before the field is set): the entity type's id says which
     * of the six it is.
     */
    private Ferox profile() {
        if (profile != null) return profile;
        String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(getType()).getPath();
        for (Ferox f : Ferox.values()) if (f.id.equals(id)) return f;
        return Ferox.SNAP_DRAKE;
    }

    @Override
    protected void customServerAiStep(ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        LivingEntity t = getTarget();
        boolean prey = t != null && t.isAlive();
        double d = prey ? reach(t) : Double.MAX_VALUE;
        tactics(serverLevel, prey ? t : null, d);
        switch (brain.tick(prey && flee == 0, d)) {
            case TELL -> {
                getNavigation().stop();
                playSound(tellSound(), 1.0F, 1.0F + (random.nextFloat() - 0.5F) * 0.2F);
            }
            case STRIKE -> {
                if (profile == Ferox.SNAP_DRAKE && prey) {               // the snap carries it forward
                    Vec3 to = t.position().subtract(position()).multiply(1, 0, 1);
                    if (to.lengthSqr() > 1.0E-4) addDeltaMovement(to.normalize().scale(SNAP_LUNGE).add(0, 0.1, 0));
                }
                playSound(strikeSound(), 1.0F, 1.0F);
                if (profile == Ferox.REED_WRETCH) sweep(serverLevel);                   // its scythe takes all in front of it
                else if (prey && d <= profile.hitReach && hasLineOfSight(t) && doHurtTarget(serverLevel, t)) {
                    strikes++;
                    struck(t);
                }
                if (profile == Ferox.SNAP_DRAKE || profile == Ferox.ORCHID_MANTIS) followUp = FOLLOW_UP_TICKS;
            }
            default -> { }
        }
        if (brain.rooted()) {
            getNavigation().stop();
            if (profile != Ferox.SNAP_DRAKE || brain.state() == StrikeBrain.State.TELL) setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            if (prey) getLookControl().setLookAt(t, 30F, 30F);
        }
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    /** What a landed blow does beyond its damage: the leech drinks, the burr's hooks and the sundew's glue hold. */
    private void struck(LivingEntity t) {
        switch (profile) {
            case LANTERN_LEECH -> {
                heal(LEECH_HEAL);
                flee = LEECH_FLEE_TICKS;                                                // and backs off, full
            }
            case BURR_CRAWLER -> t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CLING_TICKS, 0), this);
            case DEW_CRAB -> t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CRAB_GRIP_TICKS, 2), this);   // held fast
            default -> { }
        }
    }

    // ---- 1.5: each one's own ways --------------------------------------------------------------------------------------

    private void tactics(ServerLevel level, LivingEntity t, double d) {
        if (flee > 0) flee--;
        if (burrCooldown > 0) burrCooldown--;
        if (followUp > 0 && --followUp == 0 && t != null && d <= profile.hitReach && hasLineOfSight(t) && doHurtTarget(level, t)) {
            strikes++;                                                                  // the second snap or slash
            playSound(strikeSound(), 1.0F, 1.2F);
        }
        if (profile == Ferox.LANTERN_LEECH && getHealth() < getMaxHealth() * 0.3F && t != null) flee = Math.max(flee, 2);   // weak: it hangs back
        if (profile == Ferox.BURR_CRAWLER && t != null && brain.state() == StrikeBrain.State.WALK && d > BURR_FROM && d < BURR_TO
                && burrCooldown == 0 && hasLineOfSight(t)) {                              // it flings a clinging burr
            burrCooldown = BURR_COOLDOWN;
            Seedshot s = new Seedshot(level, this, VerdureItems.SHOTS.get("pulse_dart").get(), DamageClass.RANGED, (float) ATTACK * 0.75F, 1, 40, 0.0);
            s.clinging();
            Vec3 from = getEyePosition(), aim = t.getBoundingBox().getCenter().subtract(from);
            s.setPos(from.add(aim.normalize().scale(getBbWidth() * 0.5 + 0.3)));
            s.shoot(aim.x, aim.y + aim.horizontalDistance() * 0.1, aim.z, (float) SHOT_SPEED, 2.0F);
            level.addFreshEntity(s);
            shots++;
            playSound(SoundEvents.SNOWBALL_THROW, 0.8F, 0.6F);
        }
        if (profile == Ferox.DEW_CRAB && t != null && brain.state() == StrikeBrain.State.WALK && d < profile.reach * 1.5) {
            getMoveControl().strafe(0.2F, (tickCount / 40) % 2 == 0 ? 0.6F : -0.6F);      // it sidles round its prey
        }
    }

    /** The Reed Wretch's scythe: everything but monsters in a wide front within its reach. */
    private void sweep(ServerLevel level) {
        Vec3 look = Vec3.directionFromRotation(0, getYRot());
        boolean any = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(profile.hitReach),
                e -> e != this && e.isAlive() && !(e instanceof net.minecraft.world.entity.monster.Enemy) && reach(e) <= profile.hitReach)) {
            Vec3 to = e.position().subtract(position()).multiply(1, 0, 1);
            if (to.lengthSqr() > 1.0E-4 && look.dot(to.normalize()) < Math.cos(Math.toRadians(SWEEP_HALF_ANGLE))) continue;
            if (doHurtTarget(level, e)) any = true;
        }
        if (any) strikes++;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, getX() + look.x, getY() + getBbHeight() * 0.5, getZ() + look.z,
                1, 0, 0, 0, 0);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, VerdureDamage.scale(profile.kind, source, amount));
        if (hurt && profile == Ferox.DEW_CRAB && source.getEntity() instanceof LivingEntity a && a != this) {   // the crab calls its kind
            for (FeroxMob m : level.getEntitiesOfClass(FeroxMob.class, getBoundingBox().inflate(ALERT_RANGE),
                    m -> m != this && m.profile == profile && m.getTarget() == null)) m.setTarget(a);
        }
        return hurt;
    }

    /** The gap from its middle to the target's hitbox edge, so a tall or wide target is reached alike. */
    protected double reach(LivingEntity t) {
        return Math.max(0.0, distanceTo(t) - t.getBbWidth() * 0.5);
    }

    /** Walks at its prey (the flier floats at it instead). */
    protected void chase(LivingEntity t) {
        if (reach(t) > 0.8) getNavigation().moveTo(t, 1.0);
        else getNavigation().stop();
    }

    private SoundEvent tellSound() {
        return switch (profile) {
            case SNAP_DRAKE -> SoundEvents.FOX_AGGRO;
            case REED_WRETCH -> SoundEvents.AZALEA_LEAVES_BREAK;
            case LANTERN_LEECH -> SoundEvents.SPORE_BLOSSOM_BREAK;
            case BURR_CRAWLER -> SoundEvents.SPIDER_AMBIENT;
            case ORCHID_MANTIS -> SoundEvents.BIG_DRIPLEAF_TILT_DOWN;
            case DEW_CRAB -> SoundEvents.SLIME_SQUISH;
        };
    }

    private SoundEvent strikeSound() {
        return switch (profile) {
            case SNAP_DRAKE -> SoundEvents.FOX_BITE;
            case REED_WRETCH, ORCHID_MANTIS -> SoundEvents.PLAYER_ATTACK_SWEEP;
            case LANTERN_LEECH -> SoundEvents.HONEY_DRINK.value();
            case BURR_CRAWLER -> SoundEvents.SWEET_BERRY_BUSH_BREAK;
            case DEW_CRAB -> SoundEvents.HONEY_BLOCK_SLIDE;
        };
    }

    @Override
    protected SoundEvent getAmbientSound() { return profile == Ferox.LANTERN_LEECH ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.CAVE_VINES_STEP; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.BIG_DRIPLEAF_HIT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.BIG_DRIPLEAF_BREAK; }

    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        playSound(SoundEvents.AZALEA_STEP, 0.3F, 1.1F);
    }

    /**
     * Natural spawns (the biome is the spawn list's): by day under open sky; the Orchid Mantis by day under the jungle's
     * leaves too; the Lantern Leech, its glow, in the dark like any monster.
     */
    public static boolean checkSpawn(Ferox f, EntityType<? extends FeroxMob> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos,
                                     RandomSource r) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (reason == EntitySpawnReason.SPAWNER) return true;
        return switch (f) {
            case LANTERN_LEECH -> checkMonsterSpawnRules(type, level, reason, pos, r);
            case ORCHID_MANTIS -> level.getLevel().isBrightOutside() && checkMobSpawnRules(type, level, reason, pos, r);
            default -> level.getLevel().isBrightOutside() && level.canSeeSky(pos) && checkMobSpawnRules(type, level, reason, pos, r);
        };
    }

    /** It goes straight at its prey and stops to strike. */
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
                chase(t);
            }
        }
    }
}
