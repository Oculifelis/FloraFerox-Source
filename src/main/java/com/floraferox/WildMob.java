package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Mark;
import com.floraferox.VerdureRules.Move;
import com.floraferox.VerdureRules.Style;
import com.floraferox.VerdureRules.Wild;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 1.4's wild mobs (docs/DESIGN.md, section 10): the 1.2 strike brain (StrikeBrain) with each one's move and style
 * (VerdureRules.Wild). It closes on its prey (a shooter only to its range), shows its tell, and strikes: a blow in
 * reach, a leap or lunge that bites on contact, a shot, or a burst round itself. The fliers are WildFlier; the
 * minibosses are only tougher, with no boss bar (1.5.1: "they are mini bosses not bosses"). 1.5 (section 11): each has
 * its own ways besides (tactics): the hopper springs off after a blow, the grub burrows, the stalker creeps while
 * unseen, the minibosses charge, slam, drag and volley, the neutral ones call their kind. 1.15: those ways are one
 * explicit machine (Act), saved and synced.
 */
public class WildMob extends Monster {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(WildMob.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACT = SynchedEntityData.defineId(WildMob.class, EntityDataSerializers.INT);

    /**
     * 1.15 (Oculifelis: "fix all the implicit state machines"): what it is busy with besides its tell and blow, one
     * thing at a time (before, separate flags that a mixed profile could set together, lost on a reload).
     *
     * <pre>
     * NONE -(hopper's blow lands / narwhal recovers / puff bursts)-> FLEE -(time up)-> NONE
     * NONE -(far prey, Root Grub)-> BURROW -(under its prey, prey gone, or too long: it erupts)-> NONE
     * NONE -(far prey in sight, Briar King, Crimson Behemoth)-> CHARGE -(time up, or a wall)-> NONE
     * NONE -(its leap, Thorn Matriarch)-> LEAP -(off the ground)-> AIRBORNE -(lands: the slam)-> NONE
     * </pre>
     *
     * Saved by its key with its ticks (a charge with its heading too); synced to the client by its id.
     */
    public enum Act {
        NONE(0, "none", false), FLEE(1, "flee", true), BURROW(2, "burrow", true), CHARGE(3, "charge", true),
        LEAP(4, "leap", false), AIRBORNE(5, "airborne", false);

        public final int id;
        public final String key;
        /** While at it, it shows no tell (a leap's flight is its strike itself). */
        public final boolean busy;

        Act(int id, String key, boolean busy) {
            this.id = id;
            this.key = key;
            this.busy = busy;
        }

        static Act of(String key) {
            for (Act a : values()) if (a.key.equals(key)) return a;
            return NONE;
        }

        static Act byId(int id) {
            for (Act a : values()) if (a.id == id) return a;
            return NONE;
        }
    }

    public final Wild profile;
    public final StrikeBrain brain;
    /** For tests: how many blows (or bursts, or shots) landed, and how many shots it fired. */
    public int strikes, shots;
    /** A leap or lunge bites once, on contact. */
    private boolean bit;
    /** The Graft Twin fires from its flame head, then its frost one. */
    private boolean frost;
    // ---- 1.5: each one's own ways (tactics) ---------------------------------------------------------------------------
    /** What it is busy with (server side; the client reads ACT). */
    private Act act = Act.NONE;
    /** FLEE and CHARGE: ticks left; BURROW: ticks under the ground; LEAP and AIRBORNE: unused. */
    private int actTicks;
    /** A charge's heading, and what it has already run down (not saved: a reloaded charge may run them down again). */
    private Vec3 chargeDir = Vec3.ZERO;
    private final Set<Integer> trampled = new HashSet<>();
    /** Volleys or stings so far (the Graft Twin's twin volley, the Void Jelly's lift). */
    private int volley;
    /** Ticks till each move may come again: the grub's burrow, the charge, the Behemoth's volley, the bloom's drag and spores. */
    private int burrowCooldown, chargeCooldown, volleyCooldown, pullCooldown, sporeCooldown;
    /** The Thorn Matriarch: how much of its brood it has called (-1 until it has looked). */
    private int brood = -1;
    /** The Soulwick has flared (once, so saved). */
    private boolean flared;
    /** The Nylium Lurker's lair. */
    private BlockPos lair;
    /** Client only: a hopper's hop on its own clock (VerdureAnimations.wild plays it). */
    public final VerdureAnimations.HopClock hop = new VerdureAnimations.HopClock();

    public WildMob(EntityType<? extends Monster> type, Level level, Wild profile) {
        super(type, level);
        this.profile = profile;
        this.brain = new StrikeBrain(profile);
        xpReward = profile.is(BOSS) ? 50 : 6;
        if (profile.is(HOP)) moveControl = new HopControl();
        goals();
    }

    public static AttributeSupplier.Builder createAttributes(Wild w) {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, w.health).add(Attributes.ATTACK_DAMAGE, w.attack)
                .add(Attributes.ARMOR, w.armor).add(Attributes.MOVEMENT_SPEED, w.speed).add(Attributes.FLYING_SPEED, Math.max(0.3, w.speed * 2))
                .add(Attributes.KNOCKBACK_RESISTANCE, w.move == Move.STILL ? 1.0 : w.is(BOSS) ? BOSS_KNOCKBACK_RESISTANCE : KNOCKBACK_RESISTANCE)
                .add(Attributes.FOLLOW_RANGE, w.style == Style.SHOT ? 24.0 : 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(ANIM, 0);
        b.define(ACT, Act.NONE.id);
    }

    /** What it is busy with; on the client, as synced. */
    public Act act() { return level().isClientSide() ? Act.byId(entityData.get(ACT)) : act; }

    /** Its one transition: into an act with its ticks. Under the ground it is not seen. */
    private void go(Act next, int ticks) {
        Act was = act;
        act = next;
        actTicks = ticks;
        entityData.set(ACT, next.id);
        if (was == Act.BURROW || next == Act.BURROW) setInvisible(next == Act.BURROW);
    }

    /** It keeps away from its prey for a while (only when it is at nothing else). */
    private void flee(int ticks) {
        if (act == Act.NONE || act == Act.FLEE) go(Act.FLEE, ticks);
    }

    /** Whether it is keeping away from its prey just now. */
    protected boolean fleeing() { return act() == Act.FLEE; }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString("Act", act.key);
        out.putInt("ActTicks", actTicks);
        if (act == Act.CHARGE) {
            out.putDouble("ChargeX", chargeDir.x);
            out.putDouble("ChargeZ", chargeDir.z);
        }
        out.putInt("BurrowCooldown", burrowCooldown);
        out.putInt("ChargeCooldown", chargeCooldown);
        out.putInt("VolleyCooldown", volleyCooldown);
        out.putInt("PullCooldown", pullCooldown);
        out.putInt("SporeCooldown", sporeCooldown);
        out.putInt("Volley", volley);
        out.putInt("Brood", brood);
        out.putBoolean("Flared", flared);
        out.putBoolean("Frost", frost);
        if (lair != null) out.putLong("Lair", lair.asLong());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        Act a = Act.of(in.getStringOr("Act", Act.NONE.key));
        chargeDir = new Vec3(in.getDoubleOr("ChargeX", 0.0), 0.0, in.getDoubleOr("ChargeZ", 0.0));
        if (a == Act.CHARGE && chargeDir.lengthSqr() < 1.0E-4) a = Act.NONE;            // a charge with no heading: none
        go(a, a == Act.NONE ? 0 : in.getIntOr("ActTicks", 0));
        burrowCooldown = in.getIntOr("BurrowCooldown", 0);
        chargeCooldown = in.getIntOr("ChargeCooldown", 0);
        volleyCooldown = in.getIntOr("VolleyCooldown", 0);
        pullCooldown = in.getIntOr("PullCooldown", 0);
        sporeCooldown = in.getIntOr("SporeCooldown", 0);
        volley = in.getIntOr("Volley", 0);
        brood = in.getIntOr("Brood", -1);
        flared = in.getBooleanOr("Flared", false);
        frost = in.getBooleanOr("Frost", false);
        lair = in.getLong("Lair").map(BlockPos::of).orElse(null);
    }

    /** The brain's state and its ticks, synced for the renderer: state * 1000 + ticks. */
    public int anim() { return entityData.get(ANIM); }

    /** Its goals, by its profile: set after Mob's constructor (whose registerGoals runs before the profile is). */
    private void goals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new ChaseGoal());
        goalSelector.addGoal(6, new MoveTowardsRestrictionGoal(this, 1.0));   // back to its den, if it has one
        if (profile.move != Move.STILL && !profile.is(AMBUSH)) goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        if (profile.move != Move.STILL) goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        if (profile.is(AMBUSH)) {
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true, (t, l) -> t.distanceTo(this) < AMBUSH_RANGE));
        } else if (!profile.is(NEUTRAL)) {
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        }
    }

    /** Whether it fights at all just now (a tamed narwhal never turns on anyone itself). */
    protected boolean hunts() { return true; }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        LivingEntity t = getTarget();
        boolean hunting = hunts() && t != null && t.isAlive();
        boolean prey = hunting && (profile.style != Style.SHOT || hasLineOfSight(t));
        double d = hunting ? reach(t) : Double.MAX_VALUE;
        tactics(level, hunting ? t : null, d);
        if (act.busy) prey = false;                                           // busy with its own ways: no tell just now
        switch (brain.tick(prey, d)) {
            case TELL -> {
                getNavigation().stop();
                playSound(tellSound(), 1.0F, 1.0F + (random.nextFloat() - 0.5F) * 0.2F);
            }
            case STRIKE -> strike(level, prey ? t : null, d);
            default -> { }
        }
        if (brain.state() == StrikeBrain.State.STRIKE && !bit && prey && (profile.style == Style.LEAP || profile.style == Style.LUNGE)
                && d <= profile.hitReach && doHurtTarget(level, t)) {
            bit = true;                                                   // the leap or lunge bites on contact
            strikes++;
            mark(t);
        }
        if (brain.rooted() && profile.style != Style.LEAP && profile.style != Style.LUNGE) {
            getNavigation().stop();
            if (profile.move != Move.FLY) setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        }
        if (brain.rooted() && prey) getLookControl().setLookAt(t, 30F, 30F);
        if (profile.move == Move.STILL) setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    private void strike(ServerLevel level, LivingEntity t, double d) {
        playSound(strikeSound(), 1.0F, 1.0F);
        bit = false;
        if (profile == Wild.PITCHER_SPITTER && t != null && d <= GULP_REACH) {           // too close to spit at: it gulps
            if (doHurtTarget(level, t)) {
                strikes++;
                t.addEffect(new MobEffectInstance(MobEffects.NAUSEA, MARK_TICKS + 40, 0), this);
                mark(t);
            }
            playSound(SoundEvents.GENERIC_EAT.value(), 1.0F, 0.7F);
            return;
        }
        if (profile == Wild.BELL_STALKER) {                                           // its toll rings all round it
            level.sendParticles(ParticleTypes.NOTE, getX(), getY() + getBbHeight(), getZ(), 6, 0.4, 0.2, 0.4, 0.5);
            if (area(level, profile.hitReach, e -> e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, MARK_TICKS + 20, 0), this))) strikes++;
            return;
        }
        switch (profile.style) {
            case MELEE -> {
                if (t != null && d <= profile.hitReach && hasLineOfSight(t) && doHurtTarget(level, t)) {
                    strikes++;
                    mark(t);
                    if (profile == Wild.VOID_JELLY && ++volley % 2 == 0)              // every other sting lifts its prey
                        t.addEffect(new MobEffectInstance(MobEffects.LEVITATION, JELLY_LIFT_TICKS, 0), this);
                }
            }
            case LEAP, LUNGE -> {
                if (t == null) return;
                Vec3 to = t.position().subtract(position()).multiply(1, 0, 1);
                boolean leap = profile.style == Style.LEAP;
                double up = leap ? LEAP_UP : LUNGE_UP;
                if (profile.move == Move.FLY) up = (t.getY() + t.getBbHeight() * 0.5 - getY() - getBbHeight() * 0.5) * 0.15;
                if (to.lengthSqr() > 1.0E-4) addDeltaMovement(to.normalize().scale(leap ? LEAP_SPEED : LUNGE_SPEED).add(0, up, 0));
                if (profile == Wild.THORN_MATRIARCH) go(Act.LEAP, 0);
            }
            case SHOT -> {
                if (t == null) return;
                if (profile == Wild.CINDER_THISTLE) {                                   // a fan of three spikes
                    for (int k = -1; k <= 1; k++) shoot(level, t, k * FAN_SPREAD);
                } else {
                    shoot(level, t, 0F);
                    if (profile == Wild.GRAFT_TWIN && ++volley % TWIN_VOLLEY_EVERY == 0) shoot(level, t, 0F);   // both heads at once
                }
            }
            case BURST -> {
                level.sendParticles(burstParticle(), getX(), getY() + getBbHeight() * 0.5, getZ(), 24, profile.hitReach * 0.4, 0.4,
                        profile.hitReach * 0.4, 0.05);
                if (area(level, profile.hitReach, e -> {
                    if (profile == Wild.WARPED_STILT) e.addEffect(new MobEffectInstance(MobEffects.NAUSEA, MARK_TICKS + 40, 0), this);
                })) strikes++;
                if (profile.is(BLINK)) blink(t);
                if (profile == Wild.POLLEN_PUFF) flee(PUFF_FLEE_TICKS);                // and drifts off on the wind
            }
        }
    }

    /** A blow on everything but monsters within this far round it, each marked (and given more); whether any landed. */
    private boolean area(ServerLevel level, double radius, Consumer<LivingEntity> more) {
        boolean any = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius),
                e -> e != this && e.isAlive() && !(e instanceof Enemy) && e.distanceTo(this) <= radius + e.getBbWidth() * 0.5)) {
            if (doHurtTarget(level, e)) {
                any = true;
                mark(e);
                more.accept(e);
            }
        }
        return any;
    }

    // ---- 1.5: each one's own ways -------------------------------------------------------------------------------------

    /** What it does besides its tell and blow, every tick (t is null when it has no prey). */
    private void tactics(ServerLevel level, LivingEntity t, double d) {
        if (act == Act.FLEE && --actTicks <= 0) go(Act.NONE, 0);
        if (burrowCooldown > 0) burrowCooldown--;
        if (chargeCooldown > 0) chargeCooldown--;
        if (volleyCooldown > 0) volleyCooldown--;
        if (pullCooldown > 0) pullCooldown--;
        if (sporeCooldown > 0) sporeCooldown--;
        StrikeBrain.State st = brain.state();
        boolean recovering = st == StrikeBrain.State.RECOVER && brain.ticksInState() == 1;
        switch (profile) {
            case THORN_HOPPER -> {
                if (recovering && bit) flee(HOP_AWAY_TICKS);                            // hit, then hop away
            }
            case RIFT_NARWHAL -> {
                if (recovering) flee(NARWHAL_WHEEL_TICKS);                              // wheel round and charge again
            }
            case ROOT_GRUB -> burrow(level, t, d);
            case THORN_MATRIARCH -> matriarch(level, t);
            case BRIAR_KING -> charge(level, t, d);
            case CRIMSON_BEHEMOTH -> {
                charge(level, t, d);
                if (t != null && act != Act.CHARGE && st == StrikeBrain.State.WALK && d > VOLLEY_FROM && volleyCooldown == 0 && hasLineOfSight(t)) {
                    volleyCooldown = VOLLEY_COOLDOWN;                                   // three fireballs at far prey
                    Vec3 aim = t.getEyePosition().subtract(getEyePosition()).normalize();
                    for (int k = -1; k <= 1; k++) {
                        Vec3 dir = aim.yRot(k * 0.15F);
                        SmallFireball f = new SmallFireball(level, this, dir);
                        f.setPos(getEyePosition().add(dir.scale(getBbWidth() * 0.6)));
                        level.addFreshEntity(f);
                    }
                    playSound(SoundEvents.BLAZE_SHOOT, 1.0F, 0.7F);
                }
            }
            case CARRION_BLOOM -> bloom(level);
            case SOULWICK -> {
                if (!flared && getHealth() < getMaxHealth() * FLARE_AT) {                 // it flares up once, near its end
                    flared = true;
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + getBbHeight() * 0.5, getZ(), 40, 1.2, 0.6, 1.2, 0.05);
                    playSound(SoundEvents.FIRECHARGE_USE, 1.2F, 0.6F);
                    area(level, FLARE_RADIUS, e -> e.igniteForSeconds(BURN_SECONDS * 2));
                }
            }
            case NYLIUM_LURKER -> {
                if (lair == null) lair = blockPosition();
                if (t == null && getNavigation().isDone() && lair.distToCenterSqr(position()) > LAIR_RANGE * LAIR_RANGE)
                    getNavigation().moveTo(lair.getX() + 0.5, lair.getY(), lair.getZ() + 0.5, 0.8);   // back to its lair to wait
            }
            default -> { }
        }
    }

    /** The Root Grub goes under at far prey, tunnels to it, and bursts up beneath it. */
    private void burrow(ServerLevel level, LivingEntity t, double d) {
        if (act != Act.BURROW) {
            if (act == Act.NONE && t != null && brain.state() == StrikeBrain.State.WALK && d > BURROW_FROM && burrowCooldown == 0 && onGround()) {
                go(Act.BURROW, 0);
                playSound(SoundEvents.ROOTED_DIRT_BREAK, 1.0F, 0.8F);
            }
            return;
        }
        int burrowTicks = ++actTicks;
        BlockState under = level.getBlockState(blockPosition().below());
        if (!under.isAir() && burrowTicks % 2 == 0)
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, under), getX(), getY() + 0.1, getZ(), 4, 0.3, 0.05, 0.3, 0.05);
        if (t == null || d <= ERUPT_REACH || burrowTicks > BURROW_MAX_TICKS) {
            go(Act.NONE, 0);
            burrowCooldown = BURROW_COOLDOWN;
            playSound(SoundEvents.ROOTED_DIRT_BREAK, 1.2F, 0.6F);
            if (!under.isAir())
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, under), getX(), getY() + 0.3, getZ(), 30, 0.6, 0.3, 0.6, 0.1);
            if (t != null && d <= ERUPT_REACH + 0.8 && doHurtTarget(level, t)) {        // it bursts up under its prey
                strikes++;
                t.push(0, ERUPT_UP, 0);
                t.hurtMarked = true;
            }
        }
    }

    /** The Thorn Matriarch lands its leap in a shockwave, and calls its brood as it weakens. */
    private void matriarch(ServerLevel level, LivingEntity t) {
        if (act == Act.LEAP && !onGround()) go(Act.AIRBORNE, 0);
        if (act == Act.AIRBORNE && onGround()) {
            go(Act.NONE, 0);
            level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.2, getZ(), 3, 1.0, 0.1, 1.0, 0.0);
            playSound(SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 1.4F);
            area(level, SLAM_RADIUS, e -> {
                e.knockback(SLAM_KNOCKBACK, getX() - e.getX(), getZ() - e.getZ());
                e.hurtMarked = true;
            });
        }
        int due = getHealth() < getMaxHealth() / 3 ? 2 : getHealth() < getMaxHealth() * 2 / 3 ? 1 : 0;
        if (brood < 0) brood = due;                                                     // as it was when it was saved
        while (brood < due) {
            brood++;
            for (int i = 0; i < BROOD; i++) {
                WildMob h = VerdureEntities.wild(Wild.THORN_HOPPER).create(level, EntitySpawnReason.MOB_SUMMONED);
                if (h == null) continue;
                h.snapTo(getX() + (random.nextDouble() - 0.5) * 2, getY(), getZ() + (random.nextDouble() - 0.5) * 2, random.nextFloat() * 360F, 0F);
                if (t != null) h.setTarget(t);
                level.addFreshEntity(h);
            }
            playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 1.5F, 0.6F);
        }
    }

    /** The Briar King and Crimson Behemoth charge at far prey, running down whatever stands in their way. */
    private void charge(ServerLevel level, LivingEntity t, double d) {
        if (act != Act.CHARGE) {
            if (act == Act.NONE && t != null && brain.state() == StrikeBrain.State.WALK && d > CHARGE_FROM && d < CHARGE_TO && chargeCooldown == 0
                    && onGround() && hasLineOfSight(t)) {
                Vec3 to = t.position().subtract(position()).multiply(1, 0, 1);
                if (to.lengthSqr() < 1.0E-4) return;
                chargeDir = to.normalize();
                go(Act.CHARGE, CHARGE_TICKS);
                chargeCooldown = CHARGE_COOLDOWN;
                trampled.clear();
                playSound(SoundEvents.RAVAGER_ROAR, 1.2F, 1.0F);
            }
            return;
        }
        actTicks--;
        getNavigation().stop();
        setYRot((float) (Mth.atan2(chargeDir.z, chargeDir.x) * Mth.RAD_TO_DEG) - 90F);
        yBodyRot = getYRot();
        setDeltaMovement(chargeDir.x * CHARGE_SPEED, getDeltaMovement().y, chargeDir.z * CHARGE_SPEED);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.4),
                e -> e != this && e.isAlive() && !(e instanceof Enemy) && !trampled.contains(e.getId()))) {
            trampled.add(e.getId());
            if (doHurtTarget(level, e)) {
                strikes++;
                mark(e);
                e.knockback(CHARGE_KNOCKBACK, -chargeDir.x, -chargeDir.z);
                e.hurtMarked = true;
            }
        }
        if (horizontalCollision || actTicks <= 0) go(Act.NONE, 0);                       // time up, or it runs into a wall
    }

    /** The Carrion Bloom drags prey in on its vines and breathes poison spores round itself. */
    private void bloom(ServerLevel level) {
        if (pullCooldown == 0) {
            pullCooldown = PULL_EVERY;
            boolean any = false;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(PULL_RANGE),
                    e -> e != this && e.isAlive() && e.distanceTo(this) <= PULL_RANGE && prey(e) && hasLineOfSight(e))) {
                Vec3 in = position().subtract(e.position());
                if (in.lengthSqr() < 1.0E-4) continue;
                e.push(in.normalize().scale(PULL_SPEED).add(0, 0.2, 0));
                e.hurtMarked = true;
                any = true;
            }
            if (any) playSound(SoundEvents.CAVE_VINES_BREAK, 1.2F, 0.6F);
        }
        if (sporeCooldown == 0) {
            sporeCooldown = SPORE_EVERY;
            if (level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(SPORE_RANGE), e -> e != this && e.isAlive() && prey(e)).isEmpty()) return;
            AreaEffectCloud c = new AreaEffectCloud(level, getX(), getY(), getZ());
            c.setOwner(this);
            c.setRadius(3.0F);
            c.setRadiusPerTick(-0.01F);
            c.setDuration(SPORE_TICKS);
            c.setWaitTime(10);
            c.addEffect(new MobEffectInstance(MobEffects.POISON, MARK_TICKS, 0));
            level.addFreshEntity(c);
            playSound(SoundEvents.SPORE_BLOSSOM_BREAK, 1.2F, 0.6F);
        }
    }

    /** What the bloom, which never moves, would fight: its prey, or a player in reach it is not at truce with. */
    private boolean prey(LivingEntity e) {
        if (e == getTarget()) return true;
        return e instanceof Player p && !p.isCreative() && !p.isSpectator() && !Truce.spares(this, p);
    }

    /** Whether its prey is looking at it just now (the Bell Stalker creeps only while it is not). */
    public boolean watched(LivingEntity t) {
        if (!(t instanceof Player p)) return false;
        Vec3 to = getEyePosition().subtract(p.getEyePosition());
        return to.lengthSqr() > 1.0E-4 && p.getViewVector(1.0F).dot(to.normalize()) > WATCHED_DOT && p.hasLineOfSight(this);
    }

    private net.minecraft.core.particles.ParticleOptions burstParticle() {
        return switch (profile.mark) {
            case BURN -> ParticleTypes.FLAME;
            case POISON -> ParticleTypes.WARPED_SPORE;
            case WEAKNESS -> ParticleTypes.SPORE_BLOSSOM_AIR;
            default -> profile.is(BLINK) ? ParticleTypes.PORTAL : ParticleTypes.COMPOSTER;
        };
    }

    /** A shot from its eyes at its prey: the Graft Twin's flame then frost, the spitter's nectar, the thistle's spike. */
    private void shoot(ServerLevel level, LivingEntity t, float yaw) {
        String sprite;
        int tier;
        switch (profile.mark) {
            case TWIN -> {
                sprite = frost ? "rime_mote" : "ember_mote";
                tier = frost ? 3 : 2;
                frost = !frost;
            }
            case BURN -> {
                sprite = profile == Wild.LANTERNPUFF ? "ember_mote" : "flare_dart";
                tier = 2;
            }
            default -> {
                sprite = "dusk_mote";
                tier = 1;
            }
        }
        Seedshot s = new Seedshot(level, this, VerdureItems.SHOTS.get(sprite).get(), DamageClass.RANGED, (float) profile.attack, tier, 40, 0.0);
        if (profile.mark == Mark.SLOW) s.clinging();
        Vec3 from = getEyePosition();
        Vec3 aim = t.getBoundingBox().getCenter().subtract(from).yRot(yaw * Mth.DEG_TO_RAD);
        s.setPos(from.add(aim.normalize().scale(getBbWidth() * 0.5 + 0.3)));
        s.shoot(aim.x, aim.y, aim.z, (float) SHOT_SPEED, 1.0F);
        level.addFreshEntity(s);
        shots++;
    }

    /** What a landed blow does beyond its damage. */
    protected void mark(LivingEntity t) {
        switch (profile.mark) {
            case SLOW -> t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, MARK_TICKS, 1), this);
            case POISON -> t.addEffect(new MobEffectInstance(MobEffects.POISON, MARK_TICKS, 0), this);
            case WEAKNESS -> t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, MARK_TICKS + 20, 0), this);
            case BURN -> t.igniteForSeconds(BURN_SECONDS);
            default -> { }
        }
        switch (profile) {
            case NYLIUM_LURKER -> t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, GRIP_TICKS, 3), this);   // held in its jaws
            case WARTBACK -> {                                                           // tossed on its tusks
                t.push(0, TOSS_UP, 0);
                t.hurtMarked = true;
            }
            case RIFT_NARWHAL -> {                                                       // gored and flung aside
                t.knockback(GORE_KNOCKBACK, getX() - t.getX(), getZ() - t.getZ());
                t.hurtMarked = true;
            }
            default -> { }
        }
    }

    /** The Pearl Pod jumps like a thrown pearl after its burst. */
    private void blink(LivingEntity t) {
        for (int i = 0; i < 8; i++) {
            Vec3 c = t != null ? t.position() : position();
            double x = c.x + (random.nextDouble() - 0.5) * 2 * BLINK_RANGE, z = c.z + (random.nextDouble() - 0.5) * 2 * BLINK_RANGE;
            if (randomTeleport(x, c.y + random.nextInt(3), z, true)) {
                playSound(SoundEvents.ENDERMAN_TELEPORT, 0.8F, 1.3F);
                return;
            }
        }
    }

    /** The gap from its middle to the target's hitbox edge, so a tall or wide target is reached alike. */
    public double reach(LivingEntity t) {
        return Math.max(0.0, distanceTo(t) - t.getBbWidth() * 0.5 - getBbWidth() * 0.5 + 0.5);
    }

    /** Walks at its prey; a shooter only to its range, while it can see it. */
    protected void chase(LivingEntity t) {
        if (profile.move == Move.STILL || act == Act.CHARGE) return;
        if (act == Act.FLEE || kites(t)) {
            away(t);
            return;
        }
        if (profile == Wild.BELL_STALKER && watched(t)) {                              // it holds still while you look
            getNavigation().stop();
            return;
        }
        double want = profile.style == Style.SHOT ? profile.reach * 0.7 : 0.8;
        if (reach(t) > want || !hasLineOfSight(t)) getNavigation().moveTo(t, act == Act.BURROW ? 1.4 : 1.0);
        else getNavigation().stop();
    }

    /** A shooter that would rather not be close: the Graft Twin, the Cinder Thistle and the Lanternpuff back off. */
    protected boolean kites(LivingEntity t) {
        return (profile == Wild.GRAFT_TWIN || profile == Wild.CINDER_THISTLE || profile == Wild.LANTERNPUFF) && reach(t) < profile.reach * KITE_SHARE;
    }

    /** It gets a few blocks away from its prey. */
    protected void away(LivingEntity t) {
        Vec3 to = position().add(awayFrom(t).scale(5));
        getNavigation().moveTo(to.x, to.y, to.z, 1.1);
    }

    protected Vec3 awayFrom(LivingEntity t) {
        Vec3 off = position().subtract(t.position()).multiply(1, 0, 1);
        return off.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : off.normalize();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (act == Act.BURROW && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;   // safe under the ground
        boolean hurt = super.hurtServer(level, source, VerdureDamage.scale(profile.kind, source, amount));
        if (!hurt || !(source.getEntity() instanceof LivingEntity a) || a == this) return hurt;
        if (profile == Wild.BRIAR_KING && getHealth() < getMaxHealth() / 2 && source.getDirectEntity() == a && distanceTo(a) < 4.0)
            a.hurtServer(level, damageSources().thorns(this), THORNS);                  // its thorns, once it is hurt enough
        if (profile == Wild.PEARL_POD && isAlive() && random.nextInt(POD_DODGE_ONE_IN) == 0) blink(null);   // it blinks away
        if (profile == Wild.LANTERNPUFF && distanceTo(a) <= FLASH_RANGE) {             // it flashes in its striker's eyes
            a.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, FLASH_TICKS, 0), this);
            level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + getBbHeight() * 0.5, getZ(), 12, 0.3, 0.3, 0.3, 0.15);
        }
        if (profile.is(NEUTRAL) && hunts()) alert(a);
        return hurt;
    }

    /** A neutral one, struck, calls its kind round it to help. */
    private void alert(LivingEntity a) {
        for (WildMob m : level().getEntitiesOfClass(WildMob.class, getBoundingBox().inflate(ALERT_RANGE),
                m -> m != this && m.profile == profile && m.getTarget() == null && m.hunts())) {
            m.setTarget(a);
        }
    }

    @Override
    public boolean fireImmune() { return profile.fireproof() || super.fireImmune(); }

    @Override
    public boolean removeWhenFarAway(double dist) { return !profile.is(BOSS) && super.removeWhenFarAway(dist); }

    private SoundEvent tellSound() {
        return switch (profile) {
            case THORN_HOPPER, THORN_MATRIARCH -> SoundEvents.SWEET_BERRY_BUSH_BREAK;
            case POLLEN_PUFF, PEARL_POD -> SoundEvents.SPORE_BLOSSOM_BREAK;
            case GRAFT_TWIN -> SoundEvents.BLAZE_AMBIENT;
            case ROOT_GRUB -> SoundEvents.ROOTED_DIRT_BREAK;
            case PITCHER_SPITTER -> SoundEvents.BIG_DRIPLEAF_TILT_DOWN;
            case BELL_STALKER -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case BRIAR_KING, CRIMSON_BEHEMOTH -> SoundEvents.RAVAGER_ROAR;
            case CARRION_BLOOM -> SoundEvents.CAVE_VINES_BREAK;
            case VOID_JELLY -> SoundEvents.CHORUS_FLOWER_GROW;
            case RIFT_NARWHAL -> SoundEvents.ELDER_GUARDIAN_AMBIENT;
            case WARTBACK -> SoundEvents.HOGLIN_ANGRY;
            case WARPED_STILT -> SoundEvents.FUNGUS_BREAK;
            case SOULWICK -> SoundEvents.SOUL_ESCAPE.value();
            case CINDER_THISTLE, LANTERNPUFF -> SoundEvents.FIRECHARGE_USE;
            case NYLIUM_LURKER -> SoundEvents.NYLIUM_BREAK;
        };
    }

    private SoundEvent strikeSound() {
        return switch (profile.style) {
            case MELEE, BURST -> profile.is(BOSS) ? SoundEvents.RAVAGER_ATTACK : SoundEvents.PLAYER_ATTACK_SWEEP;
            case LEAP, LUNGE -> SoundEvents.FOX_BITE;
            case SHOT -> SoundEvents.SNOWBALL_THROW;
        };
    }

    @Override
    protected SoundEvent getAmbientSound() { return act() == Act.BURROW ? null : profile.fireproof() ? SoundEvents.NETHER_SPROUTS_STEP : SoundEvents.CAVE_VINES_STEP; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.BIG_DRIPLEAF_HIT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.BIG_DRIPLEAF_BREAK; }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (act() == Act.BURROW) return;
        playSound(profile.is(BOSS) ? SoundEvents.RAVAGER_STEP : SoundEvents.AZALEA_STEP, profile.is(BOSS) ? 0.6F : 0.3F, 1.0F);
    }

    /**
     * Natural spawns (the biome list is data.py's): by day under open sky, in the dark as a monster, or anywhere its
     * biome lists it (the Nether's, the End's, the Carrion Bloom in the lush caves).
     */
    public static boolean checkSpawn(Wild w, EntityType<? extends WildMob> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos,
                                     RandomSource r) {
        if (level.getDifficulty() == Difficulty.PEACEFUL && !w.is(TAME)) return false;
        if (reason == EntitySpawnReason.SPAWNER) return true;
        return switch (w.when) {
            case DAY -> level.getLevel().isBrightOutside() && level.canSeeSky(pos) && checkMobSpawnRules(type, level, reason, pos, r);
            case NIGHT -> checkMonsterSpawnRules(type, level, reason, pos, r);
            case ANY -> checkMobSpawnRules(type, level, reason, pos, r);
        };
    }

    /** Its own check (checkSpawn) decides the light: a monster's darkness test would keep the day ones out. */
    @Override
    public boolean checkSpawnRules(net.minecraft.world.level.LevelAccessor level, EntitySpawnReason reason) {
        return profile.when != VerdureRules.When.NIGHT || super.checkSpawnRules(level, reason);
    }

    /**
     * A hopper's legs: it never walks, it crouches and kicks off at where it is going, flies, lands, and rests a moment
     * before the next hop.
     */
    private final class HopControl extends net.minecraft.world.entity.ai.control.MoveControl {
        private int rest;

        HopControl() { super(WildMob.this); }

        @Override
        public void tick() {
            if (rest > 0) rest--;
            if (operation != Operation.MOVE_TO) {
                mob.setZza(0F);
                return;
            }
            double dx = wantedX - mob.getX(), dz = wantedZ - mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.5) {
                operation = Operation.WAIT;
                return;
            }
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90F;
            mob.setYRot(rotlerp(mob.getYRot(), yaw, 30F));
            mob.yBodyRot = mob.getYRot();
            mob.setZza(0F);                                           // no walking between hops
            mob.setSpeed(0F);
            if (mob.onGround() && rest == 0) {
                double f = Math.min(1.0, d / 3.0) * HOP_SPEED * speedModifier;
                mob.setDeltaMovement(dx / d * f, HOP_UP, dz / d * f);
                mob.needsSync = true;
                rest = HOP_REST + mob.getRandom().nextInt(6);
                playSound(SoundEvents.SWEET_BERRY_BUSH_PLACE, 0.4F, 1.4F);
            }
        }
    }

    /** It goes at its prey and stops to strike. */
    private final class ChaseGoal extends Goal {
        private int repath;

        ChaseGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() { return hunts() && getTarget() != null && getTarget().isAlive(); }

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
