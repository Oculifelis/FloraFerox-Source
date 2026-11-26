package com.floraferox;

import static com.floraferox.VerdureRules.*;
import com.floraferox.VerdureElements.Attack;
import com.floraferox.VerdureRules.Wild;
import com.fundamentum.state.BossStateMachine;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Thorn Matriarch's fight (docs/DESIGN.md section 15.1): called from a Thorn Hopper den by a Royal Nectar, she
 * bursts out and fights in a ring of berry bushes. Leaps: great leaps that land in a shockwave (a bush or den under her
 * snags her), a thorn fan, a bite. At 65% she calls her brood; then her leaps come in pairs, her pollen crest puffs a
 * Weakness cloud and stands open, and the den sends a hopper now and then. At 30% she calls again: the Swarm Queen dives
 * three times in a line and is winded after, and rains thorns on marked circles.
 */
public class ThornMatriarch extends WildBoss {
    public enum Phase { EMERGE, LEAPS, CALL, BROOD, SWARM, DYING }

    /** The move she is making; a tell then its blow. Not saved: after a load she starts afresh. */
    public enum Moves { IDLE, SHADOW, LEAPING, SNAGGED, FAN, BITE, POLLEN, OPEN, DIVE_TELL, DIVING, WINDED, RAIN }

    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(ThornMatriarch.class, EntityDataSerializers.INT);
    /** Her move, for the client's looks: the crest bloom opens for the pollen puff. */
    private static final EntityDataAccessor<Integer> MOVE = SynchedEntityData.defineId(ThornMatriarch.class, EntityDataSerializers.INT);
    /** Ticks the crest bloom and the wings take to open or close. */
    private static final float CREST_EASE = 8F, WINGS_EASE = 20F;
    /** Ticks the brood flaps on her abdomen take to tear open at her second phase. */
    private static final float BROOD_EASE = 14F;
    private static final DustParticleOptions PINK = new DustParticleOptions(0xF07AB4, 1.4F), RED = new DustParticleOptions(0xC0203A, 1.2F);

    public final BossStateMachine<Phase> phase = new BossStateMachine<>(Phase.class, Phase.EMERGE)
            .allow((from, to) -> from != Phase.DYING).onChange((from, to) -> entered(from, to));

    private Moves move = Moves.IDLE;
    private int moveTicks, flight, pairs, dives, leapCooldown, fanCooldown, pollenCooldown, rainCooldown, diveCooldown, denCall;
    private Vec3 from = Vec3.ZERO, mark = Vec3.ZERO;
    private double arc;
    private float crest, crestO, wings, wingsO;                  // client: how open the bloom and the wings are (0..1)
    private float brood, broodO;                                 // client: how far the brood flaps have torn open (0..1)
    private int stage = 1;                                       // her look's phase: 1, 2 from her first call, 3 from her second
    private final List<Vec3> marks = new ArrayList<>();

    public ThornMatriarch(EntityType<? extends Monster> type, Level level) {
        super(type, level, Wild.THORN_MATRIARCH, BossEvent.BossBarColor.PINK, MATRIARCH_RING, MATRIARCH_LEASH, 60);
        phase.syncTo(entityData, PHASE);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(PHASE, 0);
        b.define(MOVE, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PHASE.equals(key) && level().isClientSide()) phase.onSynced(entityData.get(PHASE));
    }

    /** The move, for tests. */
    public Moves move() { return move; }

    /** Client: how open the crest bloom is (0 a shut bud, 1 open): it opens while she swells and stands open for the pollen puff. */
    public float crest(float partial) { return crestO + (crest - crestO) * partial; }

    /** Client: how open her wings are (0 folded flat on her back, 1 open and beating): open through phase 3. */
    public float wings(float partial) { return wingsO + (wings - wingsO) * partial; }

    /** Client: how far the brood flaps on her abdomen have torn open (0 sealed, 1 peeled back): from her second phase. */
    public float brood(float partial) { return broodO + (brood - broodO) * partial; }

    /**
     * Her look's phase (its texture, the brood flaps, the bloom): 1 until her first call, 2 from it, 3 from her second
     * (the swarm); she keeps it as she dies.
     */
    public int stage() { return stage; }

    @Override
    public void wakeAt(ServerLevel level, BlockPos at) {
        super.wakeAt(level, at);
        setInvisible(true);                                                            // under the ground while the den shakes
    }

    private void entered(Phase from, Phase to) {
        move(Moves.IDLE);
        if (!(level() instanceof ServerLevel level)) return;
        if (from == Phase.EMERGE) {
            setInvisible(false);
            party(level);
            roar(level, SoundEvents.SWEET_BERRY_BUSH_BREAK, 1.6);                      // she bursts out, throwing back all round
        }
        if (to == Phase.CALL) {
            roar(level, SoundEvents.FOX_SCREECH, 1.0);
            if (home != null && level.getBlockState(home).getBlock() instanceof HopperDenBlock) {
                for (int i = 0; i < CALL_HOPPERS; i++) hopper(level, Vec3.atBottomCenterOf(home.above()));
            } else {
                call(level, Wild.THORN_HOPPER, CALL_HOPPERS, 4);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        phase.tick();
        stage = phase.is(Phase.SWARM) ? 3 : phase.isAny(Phase.CALL, Phase.BROOD) ? Math.max(stage, 2)
                : phase.is(Phase.DYING) ? stage : 1;
        if (level().isClientSide()) {
            int m = entityData.get(MOVE);
            boolean open = m == Moves.POLLEN.ordinal() || m == Moves.OPEN.ordinal() || stage >= 2;
            crestO = crest;
            wingsO = wings;
            broodO = brood;
            crest = ease(crest, open ? 1F : 0F, CREST_EASE);
            wings = ease(wings, phase.is(Phase.SWARM) ? 1F : 0F, WINGS_EASE);
            brood = ease(brood, stage >= 2 ? 1F : 0F, BROOD_EASE);
        }
    }

    private static float ease(float v, float to, float ticks) {
        return v < to ? Math.min(to, v + 1F / ticks) : Math.max(to, v - 1F / ticks);
    }

    @Override
    protected void fight(ServerLevel level) {
        if (leapCooldown > 0) leapCooldown--;
        if (fanCooldown > 0) fanCooldown--;
        if (pollenCooldown > 0) pollenCooldown--;
        if (rainCooldown > 0) rainCooldown--;
        if (diveCooldown > 0) diveCooldown--;
        switch (phase.state()) {
            case EMERGE -> {
                getNavigation().stop();
                BlockPos at = home != null ? home : blockPosition().below();
                if (phase.ticks() % 4 == 0) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(at)), at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5,
                            12, 0.6, 0.2, 0.6, 0.1);
                    playSound(SoundEvents.ROOTED_DIRT_BREAK, 1F, 0.7F);
                }
                if (phase.ticks() >= SHAKE_TICKS) phase.to(Phase.LEAPS);
            }
            case CALL -> {
                getNavigation().stop();
                if (phase.ticks() >= CALL_TICKS) phase.to(roared >= 2 ? Phase.SWARM : Phase.BROOD);
            }
            case DYING -> getNavigation().stop();
            default -> {
                float share = getHealth() / getMaxHealth();
                if (phase.is(Phase.LEAPS) && share <= BROOD_AT || phase.is(Phase.BROOD) && share <= SWARM_AT) {
                    roared++;
                    phase.to(Phase.CALL);
                } else {
                    den(level);
                    LivingEntity t = getTarget();
                    moves(level, t != null && t.isAlive() ? t : null);
                }
            }
        }
        anim(switch (move) {
            case SHADOW, FAN, POLLEN, DIVE_TELL, RAIN -> StrikeBrain.State.TELL;
            case LEAPING, DIVING, BITE -> StrikeBrain.State.STRIKE;
            case SNAGGED, OPEN, WINDED -> StrikeBrain.State.RECOVER;
            default -> phase.isAny(Phase.EMERGE, Phase.CALL) ? StrikeBrain.State.TELL : StrikeBrain.State.WALK;
        }, phase.isAny(Phase.EMERGE, Phase.CALL) ? phase.ticks() : moveTicks);
    }

    /** From her second phase the den sends a hopper now and then, while it stands. */
    private void den(ServerLevel level) {
        if (home == null || !phase.isAny(Phase.BROOD, Phase.SWARM) || ++denCall < DEN_CALL_EVERY) return;
        denCall = 0;
        if (level.getBlockState(home).getBlock() instanceof HopperDenBlock) hopper(level, Vec3.atBottomCenterOf(home.above()));
    }

    private void hopper(ServerLevel level, Vec3 at) {
        WildMob h = VerdureEntities.wild(Wild.THORN_HOPPER).create(level, EntitySpawnReason.MOB_SUMMONED);
        if (h == null) return;
        h.snapTo(at.x + (random.nextDouble() - 0.5) * 2, at.y, at.z + (random.nextDouble() - 0.5) * 2, random.nextFloat() * 360F, 0F);
        if (getTarget() != null) h.setTarget(getTarget());
        level.addFreshEntity(h);
        playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 1.5F, 0.6F);
    }

    // ---- the moves ----

    private void move(Moves next) {
        move = next;
        moveTicks = 0;
        entityData.set(MOVE, next.ordinal());
        struck.clear();
    }

    private void moves(ServerLevel level, LivingEntity t) {
        moveTicks++;
        switch (move) {
            case IDLE -> choose(level, t);
            case SHADOW, DIVE_TELL -> {
                getNavigation().stop();
                boolean dive = move == Moves.DIVE_TELL;
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                int tell = dive ? DIVE_TELL : GREAT_LEAP_TELL;
                if (dive) line(level, position(), mark, RED);                          // the dive's line scored ahead of it
                else circle(level, mark, GREAT_LEAP_RADIUS * Math.min(1.0, (moveTicks + 4) / (double) tell), 20, PINK);   // her shadow grows
                if (moveTicks >= tell) leap(dive ? 1.5 : 3.0 + from.distanceTo(mark) * 0.15);
            }
            case LEAPING, DIVING -> flight(level);
            case SNAGGED, WINDED -> {
                getNavigation().stop();
                if (moveTicks % 5 == 0) level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight() + 0.3, getZ(), 4, 0.5, 0.1, 0.5, 0);
                if (moveTicks >= (move == Moves.SNAGGED ? SNAG_TICKS : WINDED_TICKS)) move(Moves.IDLE);
            }
            case FAN -> {
                getNavigation().stop();
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks % 3 == 0) level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight(), getZ(), 6, 0.6, 0.2, 0.6, 0.05);   // her thorns rise
                if (moveTicks >= FAN_TELL) {
                    if (t != null) {
                        Vec3 eye = getEyePosition(), aim = t.getBoundingBox().getCenter().subtract(eye);
                        for (int k = 0; k < FAN_THORNS; k++) {
                            float yaw = (float) ((k - FAN_THORNS / 2) * FAN_SPREAD_DEG);
                            Vec3 dir = aim.yRot(yaw * net.minecraft.util.Mth.DEG_TO_RAD);
                            fire(level, eye.add(dir.normalize().scale(getBbWidth() * 0.5 + 0.3)), dir, "pulse_dart", FAN_SHARE, SHOT_SPEED, 0.0);
                        }
                    }
                    playSound(SoundEvents.SNOWBALL_THROW, 1.4F, 0.6F);
                    move(Moves.IDLE);
                }
            }
            case BITE -> {
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks == 8) {
                    playSound(SoundEvents.FOX_BITE, 1.4F, 0.6F);
                    for (LivingEntity e : foes(level, getBoundingBox().inflate(BITE_REACH, 1, BITE_REACH)))
                        if (reach(e) <= BITE_REACH && struck.add(e.getId())) hit(level, e, Attack.MAIN, BITE_SHARE);
                }
                if (moveTicks >= 14) move(Moves.IDLE);
            }
            case POLLEN -> {
                getNavigation().stop();
                if (moveTicks % 3 == 0) level.sendParticles(PINK, getX(), getY() + getBbHeight() + 0.2, getZ(), 6, 0.3, 0.2, 0.3, 0);   // the crest swells
                if (moveTicks >= POLLEN_TELL) {
                    pollen(level);
                    move(Moves.OPEN);
                }
            }
            case OPEN -> {
                getNavigation().stop();
                if (moveTicks % 6 == 0) level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight(), getZ(), 4, 0.3, 0.2, 0.3, 0);
                if (moveTicks >= BLOOM_OPEN_TICKS) move(Moves.IDLE);
            }
            case RAIN -> {
                getNavigation().stop();
                if (moveTicks % 3 == 0) for (Vec3 m : marks) circle(level, m.add(0, 0.6, 0), RAIN_RADIUS, 8, RED);   // red petals swirl over each
                if (moveTicks >= RAIN_TELL) {
                    playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 2F, 0.5F);
                    for (Vec3 m : marks) {
                        level.sendParticles(ParticleTypes.CRIT, m.x, m.y + 3, m.z, 20, RAIN_RADIUS * 0.5, 1.5, RAIN_RADIUS * 0.5, 0.1);
                        for (LivingEntity e : foes(level, new AABB(m, m).inflate(RAIN_RADIUS, 2, RAIN_RADIUS)))
                            if (e.distanceToSqr(m.x, e.getY(), m.z) <= RAIN_RADIUS * RAIN_RADIUS && struck.add(e.getId())) hit(level, e, Attack.MAIN, RAIN_SHARE);
                    }
                    marks.clear();
                    move(Moves.IDLE);
                }
            }
        }
    }

    /** Picks her next move by the gap to her target and what her phase allows; or walks at it. */
    private void choose(ServerLevel level, LivingEntity t) {
        if (t == null) return;
        double d = reach(t);
        boolean brood = phase.is(Phase.BROOD), swarm = phase.is(Phase.SWARM);
        if (moveTicks < 10) {
            chase(t);
            return;
        }
        if (swarm && diveCooldown == 0 && d >= 3) {
            diveCooldown = 200;
            dives = 0;
            aim(level, t, true);
            move(Moves.DIVE_TELL);
        } else if (swarm && rainCooldown == 0) {
            rainCooldown = 160;
            marks.clear();
            for (int i = 0; i < RAIN_CIRCLES; i++) {
                Vec3 at = i == 0 ? t.position() : t.position().add((random.nextDouble() - 0.5) * 2 * RAIN_SPREAD, 0, (random.nextDouble() - 0.5) * 2 * RAIN_SPREAD);
                marks.add(ground(level, at));
            }
            move(Moves.RAIN);
        } else if (d <= BITE_REACH && (random.nextInt(3) > 0 || !brood || pollenCooldown > 0)) {
            move(Moves.BITE);
        } else if (brood && pollenCooldown == 0 && d <= POLLEN_RADIUS + 1) {
            pollenCooldown = 200;
            move(Moves.POLLEN);
        } else if (!swarm && leapCooldown == 0 && d >= GREAT_LEAP_FROM && d < MATRIARCH_RING) {
            leapCooldown = 100;
            pairs = brood ? 1 : 0;                                                     // in her brood phase the leaps come in pairs
            aim(level, t, false);
            move(Moves.SHADOW);
        } else if (fanCooldown == 0 && d >= 4 && d < 16) {
            fanCooldown = 120;
            move(Moves.FAN);
        } else {
            chase(t);
        }
    }

    /** Where her next leap or dive lands: on her target (a dive goes at most DIVE_LENGTH past where it is). */
    private void aim(ServerLevel level, LivingEntity t, boolean dive) {
        from = position();
        Vec3 to = t.position();
        if (dive) {
            Vec3 dir = flatTo(to);
            to = position().add(dir.scale(Math.min(position().distanceTo(to) + 1.5, DIVE_LENGTH * 2)));
        }
        mark = ground(level, to);
    }

    private void leap(double height) {
        from = position();
        flight = Math.max(10, Math.min(LEAP_FLIGHT, (int) (from.distanceTo(mark) * 1.6)));
        arc = height;
        playSound(SoundEvents.RAVAGER_ROAR, 1F, 1.6F);
        move(move == Moves.DIVE_TELL ? Moves.DIVING : Moves.LEAPING);
    }

    /** Her flight to the mark, by hand along an arc; the landing's shockwave, and a bush or den under her snags her. */
    private void flight(ServerLevel level) {
        getNavigation().stop();
        double f = Math.min(1.0, moveTicks / (double) flight);
        Vec3 next = from.lerp(mark, f).add(0, 4 * arc * f * (1 - f), 0);
        setDeltaMovement(next.subtract(position()));
        face(flatTo(mark));
        boolean landed = f >= 1.0 && (onGround() || moveTicks > flight + 10) || moveTicks > 2 && f > 0.5 && onGround();
        if (!landed) return;
        setDeltaMovement(Vec3.ZERO);
        boolean dive = move == Moves.DIVING;
        playSound(SoundEvents.GENERIC_EXPLODE.value(), dive ? 0.8F : 1.4F, 1.2F);
        shockwave(level, position(), dive ? DIVE_RADIUS : GREAT_LEAP_RADIUS, Attack.SLAM, dive ? DIVE_SHARE : LEAP_SHARE);
        if (snagged(level)) {
            playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 2F, 0.5F);
            move(Moves.SNAGGED);
        } else if (dive) {
            if (++dives < DIVES && getTarget() != null) {
                aim(level, getTarget(), true);
                move(Moves.DIVE_TELL);
            } else {
                move(Moves.WINDED);                                                    // after the third dive she lands winded
            }
        } else if (pairs-- > 0 && getTarget() != null) {
            aim(level, getTarget(), false);
            move(Moves.SHADOW);
        } else {
            move(Moves.IDLE);
        }
    }

    /** Whether she landed on a berry bush or a den. */
    public boolean snagged(ServerLevel level) {
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(getBoundingBox().minX, getY() - 1, getBoundingBox().minZ),
                BlockPos.containing(getBoundingBox().maxX, getY() + 0.5, getBoundingBox().maxZ))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof SweetBerryBushBlock || s.getBlock() instanceof HopperDenBlock) return true;
        }
        return false;
    }

    /** Her pollen crest: a Weakness cloud POLLEN_RADIUS round her. */
    private void pollen(ServerLevel level) {
        playSound(SoundEvents.SPORE_BLOSSOM_BREAK, 2F, 1.2F);
        level.sendParticles(PINK, getX(), getY() + 1, getZ(), 60, POLLEN_RADIUS * 0.5, 0.6, POLLEN_RADIUS * 0.5, 0);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(POLLEN_RADIUS)))
            if (distanceTo(e) <= POLLEN_RADIUS + getBbWidth() * 0.5) e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, MARK_TICKS * 2, 0), this);
        AreaEffectCloud c = new AreaEffectCloud(level, getX(), getY(), getZ());
        c.setOwner(this);
        c.setRadius((float) POLLEN_RADIUS);
        c.setRadiusPerTick(-0.02F);
        c.setDuration(BLOOM_OPEN_TICKS * 2);
        c.setWaitTime(0);
        c.setCustomParticle(PINK);
        c.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, MARK_TICKS * 2, 0));
        level.addFreshEntity(c);
    }

    // ---- what WildBoss asks ----

    @Override
    protected boolean immune() { return phase.isAny(Phase.EMERGE, Phase.CALL, Phase.DYING); }

    @Override
    protected void dying() {
        phase.to(Phase.DYING);
        move(Moves.IDLE);
    }

    @Override
    protected BlockState ringBlock() { return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1); }

    @Override
    protected boolean ours(BlockState s) { return s.is(Blocks.SWEET_BERRY_BUSH); }

    @Override
    protected ItemStack trophy() { return new ItemStack(VerdureItems.MATRIARCH_CREST.get()); }

    @Override
    protected void homeReady(ServerLevel level) { HopperDenBlock.rest(level, home, false); }

    @Override
    protected void homeSpent(ServerLevel level) { }

    @Override
    protected ParticleOptions dust() { return PINK; }

    @Override
    protected boolean helper(WildMob w) { return w.profile == Wild.THORN_HOPPER; }

    @Override
    protected void chase(LivingEntity t) {
        if (move == Moves.IDLE && !immune()) super.chase(t);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (move == Moves.OPEN) amount *= 2;                                          // the open bloom takes double
        return super.hurtServer(level, source, amount);
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) { return false; }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        phase.save(out, "phase");
        out.putInt("DenCall", denCall);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        phase.load(in, "phase");
        denCall = in.getIntOr("DenCall", 0);
    }
}
