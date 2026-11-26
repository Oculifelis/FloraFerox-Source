package com.floraferox;

import static com.floraferox.VerdureRules.*;
import com.floraferox.VerdureElements.Attack;
import com.floraferox.VerdureRules.Wild;
import com.fundamentum.state.BossStateMachine;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Carrion Bloom's fight (docs/DESIGN.md section 15.3): Carrion Bait under a Carrion Bud wakes it, and glow-berry
 * vines grow from the cave ceiling round it. Lure: it drags prey in on its vines, puffs poison spores, spits sticky
 * petals and bites what comes close. At 60% it wilts and turns to Feeding: it heals on its vines while any stand (with
 * all gone it starves and wilts, heart open), calls two Blightcaps, drops spore clouds from the ceiling. At 30% it turns
 * Carrion, still rooted where it fell (Oculifelis, round 14: it never moves): its heart sweeps a spore beam a quarter turn
 * and stays open after, it spits wide petal volleys and lobs spore pods, roots spike up under prey at range, its drags
 * come in pairs, and two more Blightcaps come. Nothing pushes it.
 */
public class CarrionBloom extends WildBoss {
    public enum Phase { BLOOM, LURE, WILT, FEEDING, CARRION, DYING }

    public enum Moves { IDLE, DRAG, PUFF, SPIT, BITE, STARVED, BEAM_TELL, BEAM, OPEN, SPIKES, PODS }

    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(CarrionBloom.class, EntityDataSerializers.INT);
    private static final DustParticleOptions MAGENTA = new DustParticleOptions(0xC2358F, 1.4F), VINE = new DustParticleOptions(0x7FCF4A, 1.0F),
            WHITE = new DustParticleOptions(0xFFF4E8, 1.6F);
    /** Its last phase's reach: root spikes under prey this far off, the tell before they burst, how many petals a volley. */
    private static final int SPIKE_RANGE = 14, SPIKE_TELL = 20, VOLLEY = 5;

    public final BossStateMachine<Phase> phase = new BossStateMachine<>(Phase.class, Phase.BLOOM)
            .allow((from, to) -> from != Phase.DYING).onChange((from, to) -> entered(from, to));

    private Moves move = Moves.IDLE;
    private int moveTicks, dragCooldown, puffCooldown, spitCooldown, biteCooldown, beamCooldown, spikeCooldown, podCooldown, feed, ceiling;
    private boolean blightcaps, lastCaps;
    /** Where its root spikes will burst. */
    private final List<Vec3> spikes = new ArrayList<>();
    /** Each glow-berry vine's top (it stands while that block is still a vine). */
    private final List<BlockPos> vines = new ArrayList<>();
    /** Who its vines are reaching for, and the beam's sweep (start bearing, in radians). */
    private final List<LivingEntity> dragged = new ArrayList<>();
    private double sweep;

    public CarrionBloom(EntityType<? extends Monster> type, Level level) {
        super(type, level, Wild.CARRION_BLOOM, BossEvent.BossBarColor.PURPLE, VINE_TO, BLOOM_LEASH, 60);
        phase.syncTo(entityData, PHASE);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(PHASE, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PHASE.equals(key) && level().isClientSide()) phase.onSynced(entityData.get(PHASE));
    }

    public Moves move() { return move; }

    public List<BlockPos> vines() { return vines; }

    /** How many of its vines still stand. */
    public int standing(ServerLevel level) {
        int n = 0;
        for (BlockPos p : vines) if (ours(level.getBlockState(p))) n++;
        return n;
    }

    private void entered(Phase from, Phase to) {
        move(Moves.IDLE);
        if (!(level() instanceof ServerLevel level)) return;
        if (from == Phase.BLOOM) {
            party(level);
            roar(level, SoundEvents.SPORE_BLOSSOM_BREAK, 0.8);                         // the petals open, a puff of spores
        }
        if (to == Phase.WILT) {
            playSound(SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 2F, 0.5F);
            level.sendParticles(MAGENTA, getX(), getY() + 1, getZ(), 40, 1, 0.5, 1, 0);
        }
        if (to == Phase.FEEDING && !blightcaps) {
            blightcaps = true;
            call(level, Wild.BLIGHTCAP, BLIGHTCAPS, 6);
        }
        if (to == Phase.CARRION && !lastCaps) {
            lastCaps = true;
            call(level, Wild.BLIGHTCAP, BLIGHTCAPS, 6);
        }
    }

    @Override
    public void tick() {
        super.tick();
        phase.tick();
    }

    @Override
    protected void fight(ServerLevel level) {
        if (dragCooldown > 0) dragCooldown--;
        if (puffCooldown > 0) puffCooldown--;
        if (spitCooldown > 0) spitCooldown--;
        if (biteCooldown > 0) biteCooldown--;
        if (beamCooldown > 0) beamCooldown--;
        if (spikeCooldown > 0) spikeCooldown--;
        if (podCooldown > 0) podCooldown--;
        setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));                       // rooted: it never moves
        switch (phase.state()) {
            case BLOOM -> {
                if (phase.ticks() % 5 == 0) level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight(), getZ(), 6, 0.6, 0.3, 0.6, 0);
                if (phase.ticks() >= WAKE_TICKS) phase.to(Phase.LURE);
            }
            case WILT -> {
                if (phase.ticks() >= ROAR_TICKS) phase.to(roared >= 2 ? Phase.CARRION : Phase.FEEDING);
            }
            case DYING -> { }
            default -> {
                float share = getHealth() / getMaxHealth();
                if (phase.is(Phase.LURE) && share <= FEEDING_AT || phase.is(Phase.FEEDING) && share <= CARRION_AT) {
                    roared++;
                    phase.to(Phase.WILT);
                } else {
                    LivingEntity t = getTarget();
                    if (t != null && !t.isAlive()) t = null;
                    if (phase.is(Phase.FEEDING)) feeding(level, t);
                    moves(level, t);
                }
            }
        }
        anim(switch (move) {
            case DRAG, PUFF, SPIT, BEAM_TELL, SPIKES, PODS -> StrikeBrain.State.TELL;
            case BITE, BEAM -> StrikeBrain.State.STRIKE;
            case STARVED, OPEN -> StrikeBrain.State.RECOVER;
            default -> phase.isAny(Phase.BLOOM, Phase.WILT) ? StrikeBrain.State.TELL : StrikeBrain.State.WALK;
        }, phase.isAny(Phase.BLOOM, Phase.WILT) ? phase.ticks() : moveTicks);
    }

    private void move(Moves next) {
        move = next;
        moveTicks = 0;
        struck.clear();
    }

    private void moves(ServerLevel level, LivingEntity t) {
        moveTicks++;
        switch (move) {
            case IDLE -> choose(level, t);
            case DRAG -> {
                for (LivingEntity e : dragged) line(level, position().add(0, 0.6, 0), e.position().add(0, 0.5, 0), VINE);   // the vine glows along its length
                if (moveTicks >= DRAG_TELL) {
                    playSound(SoundEvents.CAVE_VINES_BREAK, 1.6F, 0.6F);
                    for (LivingEntity e : dragged) {
                        if (!e.isAlive() || distanceTo(e) > PULL_RANGE + 2) continue;
                        Vec3 in = position().subtract(e.position());
                        if (in.lengthSqr() < 1.0E-4) continue;
                        hit(level, e, Attack.MAIN, DRAG_SHARE);
                        e.push(in.normalize().scale(PULL_SPEED).add(0, 0.2, 0));
                        e.hurtMarked = true;
                    }
                    dragged.clear();
                    move(Moves.IDLE);
                }
            }
            case PUFF -> {
                if (moveTicks % 3 == 0) level.sendParticles(MAGENTA, getX(), getY() + getBbHeight(), getZ(), 6, 0.4, 0.2, 0.4, 0);   // the heart swells
                if (moveTicks >= PUFF_TELL) {
                    puff(level, position(), PUFF_RADIUS, true);
                    move(Moves.IDLE);
                }
            }
            case SPIT -> {
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks >= 10) {
                    if (t != null) {
                        Vec3 eye = getEyePosition(), aim = t.getBoundingBox().getCenter().subtract(eye);
                        int n = phase.is(Phase.CARRION) ? VOLLEY : PETALS;              // its last phase: a wider volley
                        for (int k = 0; k < n; k++) {
                            Vec3 dir = aim.yRot((float) Math.toRadians((k - n / 2) * 10.0));
                            fire(level, eye.add(dir.normalize().scale(getBbWidth() * 0.5 + 0.3)), dir, "dusk_mote", PETAL_SHARE, SHOT_SPEED * 0.8, 0.02)
                                    .clinging().element(VerdureElements.type("spore"));
                        }
                    }
                    playSound(SoundEvents.SPORE_BLOSSOM_BREAK, 1.4F, 1.4F);
                    move(Moves.IDLE);
                }
            }
            case BITE -> {
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks == 8) {
                    playSound(SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 1.4F, 0.6F);
                    for (LivingEntity e : foes(level, getBoundingBox().inflate(profile.hitReach, 1, profile.hitReach)))
                        if (prey(e) && reach(e) <= profile.hitReach && struck.add(e.getId()) && hit(level, e, Attack.MAIN, BLOOM_BITE_SHARE)) mark(e);
                }
                if (moveTicks >= 14) move(Moves.IDLE);
            }
            case STARVED, OPEN -> {
                if (moveTicks % 6 == 0) level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight(), getZ(), 4, 0.3, 0.2, 0.3, 0);
                if (moveTicks >= (move == Moves.STARVED ? STARVED_TICKS : HEART_OPEN_TICKS)) move(Moves.IDLE);
            }
            case SPIKES -> {
                for (Vec3 at : spikes) if (moveTicks % 3 == 0) level.sendParticles(VINE, at.x, at.y + 0.1, at.z, 5, 0.4, 0.05, 0.4, 0);
                if (moveTicks >= SPIKE_TELL) {
                    for (Vec3 at : spikes) {
                        level.sendParticles(MAGENTA, at.x, at.y + 1, at.z, 20, 0.3, 1, 0.3, 0);
                        for (LivingEntity e : foes(level, new AABB(at, at).inflate(1.2, 2, 1.2)))
                            if (prey(e) && struck.add(e.getId()) && hit(level, e, Attack.MAIN, DRAG_SHARE)) {
                                e.push(0, 0.6, 0);
                                e.hurtMarked = true;
                            }
                    }
                    playSound(SoundEvents.ROOTED_DIRT_BREAK, 2F, 0.5F);
                    spikes.clear();
                    move(Moves.IDLE);
                }
            }
            case PODS -> {
                if (moveTicks % 3 == 0) level.sendParticles(MAGENTA, getX(), getY() + getBbHeight(), getZ(), 6, 0.5, 0.2, 0.5, 0);
                if (moveTicks >= PUFF_TELL) {
                    for (LivingEntity e : foes(level, getBoundingBox().inflate(BEAM_RANGE, 4, BEAM_RANGE)))
                        if (prey(e)) {
                            Vec3 at = e.position().add((random.nextDouble() - 0.5) * 2, 0, (random.nextDouble() - 0.5) * 2);
                            line(level, heart(), at, ParticleTypes.SPORE_BLOSSOM_AIR);
                            puff(level, at, 2.5, false);
                        }
                    move(Moves.IDLE);
                }
            }
            case BEAM_TELL -> {
                if (moveTicks % 3 == 0) level.sendParticles(WHITE, getX(), getY() + getBbHeight(), getZ(), 6, 0.3, 0.2, 0.3, 0);   // the heart burns white
                line(level, heart(), heart().add(bearing(sweep).scale(BEAM_RANGE)), ParticleTypes.SPORE_BLOSSOM_AIR);   // the sweep's start
                if (moveTicks >= BEAM_TELL) {
                    playSound(SoundEvents.SPORE_BLOSSOM_BREAK, 3F, 0.4F);
                    move(Moves.BEAM);
                }
            }
            case BEAM -> {
                double a = sweep + Math.PI / 2 * Math.min(1.0, moveTicks / (double) BEAM_TICKS);
                Vec3 from = heart(), dir = bearing(a);
                line(level, from, from.add(dir.scale(BEAM_RANGE)), MAGENTA);
                for (LivingEntity e : foes(level, getBoundingBox().inflate(BEAM_RANGE, 3, BEAM_RANGE))) {
                    Vec3 to = e.getBoundingBox().getCenter().subtract(from);
                    double along = to.dot(dir);
                    if (along < 0 || along > BEAM_RANGE || to.subtract(dir.scale(along)).length() > 1.2 + e.getBbWidth() * 0.5 || !struck.add(e.getId())) continue;
                    hit(level, e, Attack.SPORE, BEAM_SHARE);
                    VerdureElements.spores(level, this, e);
                }
                if (moveTicks >= BEAM_TICKS) move(Moves.OPEN);                         // the heart stays open after
            }
        }
    }

    /** Picks its next move; with its vines first, as they come round. */
    private void choose(ServerLevel level, LivingEntity t) {
        if (moveTicks < 6) return;
        boolean carrion = phase.is(Phase.CARRION);
        if (dragCooldown == 0) {
            dragged.clear();
            for (LivingEntity e : foes(level, getBoundingBox().inflate(PULL_RANGE)))
                if (dragged.size() < (carrion ? 2 : 1) && distanceTo(e) <= PULL_RANGE && reach(e) > 1.5 && prey(e) && hasLineOfSight(e)) dragged.add(e);
            if (carrion && dragged.size() == 1) dragged.add(dragged.get(0));          // a pair of drags at one
            if (!dragged.isEmpty()) {
                dragCooldown = PULL_EVERY;
                move(Moves.DRAG);
                return;
            }
        }
        if (t == null) return;
        double d = reach(t);
        if (d <= profile.hitReach && biteCooldown == 0) {
            biteCooldown = 20;
            move(Moves.BITE);
        } else if (carrion && spikeCooldown == 0 && d <= SPIKE_RANGE) {
            spikeCooldown = 120;
            spikes.clear();
            for (LivingEntity e : foes(level, getBoundingBox().inflate(SPIKE_RANGE, 4, SPIKE_RANGE)))
                if (prey(e) && spikes.size() < 4) spikes.add(e.position());                 // under where they stand now
            move(Moves.SPIKES);
        } else if (carrion && podCooldown == 0 && d <= BEAM_RANGE) {
            podCooldown = 160;
            move(Moves.PODS);
        } else if (carrion && beamCooldown == 0 && d <= BEAM_RANGE) {
            beamCooldown = 200;
            Vec3 to = flatTo(t.position());
            sweep = Math.atan2(to.z, to.x) - Math.PI / 4;                              // a quarter turn across where its prey stands
            move(Moves.BEAM_TELL);
        } else if (puffCooldown == 0 && d <= PUFF_RADIUS + 1) {
            puffCooldown = SPORE_EVERY;
            move(Moves.PUFF);
        } else if (spitCooldown == 0 && d <= 16 && hasLineOfSight(t)) {
            spitCooldown = 100;
            move(Moves.SPIT);
        }
    }

    /** Feeding: every FEED_EVERY it heals on a standing vine; with every vine gone it wilts, heart open. Spore clouds drop. */
    private void feeding(ServerLevel level, LivingEntity t) {
        if (++feed >= FEED_EVERY) {
            feed = 0;
            BlockPos fed = null;
            for (BlockPos p : vines) if (ours(level.getBlockState(p))) fed = p;
            if (fed != null) {
                line(level, position().add(0, 0.3, 0), Vec3.atCenterOf(fed), VINE);    // a tendril reaches out to the vine
                heal(getMaxHealth() * FEED_SHARE);
                playSound(SoundEvents.CAVE_VINES_PICK_BERRIES, 1.6F, 0.6F);
            } else if (home != null && move != Moves.STARVED) {
                move(Moves.STARVED);
                playSound(SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 2F, 0.4F);
            }
        }
        if (t != null && ++ceiling >= CEILING_SPORE_EVERY) {
            ceiling = 0;
            Vec3 at = t.position().add((random.nextDouble() - 0.5) * 3, 0, (random.nextDouble() - 0.5) * 3);
            level.sendParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, at.x, at.y + 5, at.z, 30, 0.6, 1.5, 0.6, 0);
            puff(level, at, 2.5, false);
        }
    }

    /** A poison spore cloud at at; its own puff also coats what it reaches in spores. */
    private void puff(ServerLevel level, Vec3 at, double radius, boolean own) {
        playSound(SoundEvents.SPORE_BLOSSOM_BREAK, 1.6F, 0.6F);
        for (LivingEntity e : foes(level, new AABB(at, at).inflate(radius, 2, radius))) {
            if (e.distanceToSqr(at) > radius * radius || !prey(e)) continue;
            if (own && struck.add(e.getId())) hit(level, e, Attack.SPORE, PUFF_SHARE);
            VerdureElements.spores(level, this, e);
        }
        AreaEffectCloud c = new AreaEffectCloud(level, at.x, at.y, at.z);
        c.setOwner(this);
        c.setRadius((float) radius);
        c.setRadiusPerTick(-0.01F);
        c.setDuration(SPORE_TICKS);
        c.setWaitTime(10);
        c.addEffect(new MobEffectInstance(MobEffects.POISON, MARK_TICKS, 0));
        level.addFreshEntity(c);
    }

    // ---- rooted: nothing moves it ----

    @Override
    public boolean isPushable() { return false; }

    @Override
    public void push(double x, double y, double z) { }

    @Override
    public boolean isPushedByFluid() { return false; }

    private Vec3 heart() { return position().add(0, getBbHeight() * 0.7, 0); }

    private static Vec3 bearing(double a) { return new Vec3(Math.cos(a), 0, Math.sin(a)); }

    /** What it fights: its prey, or a player near it is not at truce with. */
    private boolean prey(LivingEntity e) {
        if (e == getTarget()) return true;
        return e instanceof Player p && !p.isCreative() && !p.isSpectator() && !Truce.spares(this, p);
    }

    // ---- the arena: glow-berry vines from the ceiling ----

    @Override
    protected void raise(ServerLevel level) {
        ring.clear();
        vines.clear();
        for (int i = 0, tries = 0; i < VINES && tries < VINES * 6; tries++) {
            double a = Math.PI * 2 * (i + random.nextDouble() * 0.5) / VINES;
            double r = VINE_FROM + random.nextDouble() * (VINE_TO - VINE_FROM);
            BlockPos col = home.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
            BlockPos top = under(level, col);
            if (top == null) continue;
            List<BlockPos> vine = new ArrayList<>();
            for (int y = 0; y < VINE_LENGTH && open(level, top.below(y)) && level.getBlockState(top.below(y)).getFluidState().isEmpty(); y++) vine.add(top.below(y));
            if (vine.isEmpty()) continue;
            for (int k = 0; k < vine.size(); k++) {
                Block b = k == vine.size() - 1 ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT;
                level.setBlock(vine.get(k), b.defaultBlockState().setValue(CaveVines.BERRIES, true), Block.UPDATE_ALL);
            }
            ring.addAll(vine);
            vines.add(top);
            i++;
        }
    }

    /** The first open block under the ceiling over col, near its home's height; null if there is no ceiling there. */
    private static BlockPos under(ServerLevel level, BlockPos col) {
        for (int dy = 4; dy >= -12; dy--) {
            BlockPos p = col.above(dy);
            if (open(level, p) && !open(level, p.above()) && level.getBlockState(p.above()).isFaceSturdy(level, p.above(), net.minecraft.core.Direction.DOWN))
                return p;
        }
        return null;
    }

    // ---- what WildBoss asks ----

    @Override
    protected boolean immune() { return phase.isAny(Phase.BLOOM, Phase.WILT, Phase.DYING); }

    @Override
    protected void dying() {
        phase.to(Phase.DYING);
        move(Moves.IDLE);
    }

    @Override
    protected BlockState ringBlock() { return null; }

    @Override
    protected boolean ours(BlockState s) { return s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT); }

    @Override
    protected ItemStack trophy() { return new ItemStack(VerdureItems.CARRION_HEART.get()); }

    @Override
    protected void homeReady(ServerLevel level) { CarrionBudBlock.rest(level, home, false); }

    @Override
    protected void homeSpent(ServerLevel level) { CarrionBudBlock.rest(level, home, true); }

    @Override
    protected ParticleOptions dust() { return MAGENTA; }

    @Override
    protected boolean helper(WildMob w) { return w.profile == Wild.BLIGHTCAP; }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (move == Moves.STARVED || move == Moves.OPEN) amount *= 2;                  // its heart open: double
        return super.hurtServer(level, source, amount);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        phase.save(out, "phase");
        out.putBoolean("Blightcaps", blightcaps);
        out.putBoolean("LastCaps", lastCaps);
        out.store("Vines", BlockPos.CODEC.listOf(), vines);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        phase.load(in, "phase");
        blightcaps = in.getBooleanOr("Blightcaps", false);
        lastCaps = in.getBooleanOr("LastCaps", false);
        vines.clear();
        vines.addAll(in.read("Vines", BlockPos.CODEC.listOf()).orElse(List.of()));
    }
}
