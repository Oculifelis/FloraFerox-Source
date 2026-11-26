package com.floraferox;

import static com.floraferox.VerdureRules.*;
import com.floraferox.VerdureElements.Attack;
import com.floraferox.VerdureRules.Wild;
import com.fundamentum.element.ElementAuras;
import com.fundamentum.state.BossStateMachine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Crimson Behemoth's fight (docs/DESIGN.md section 14): woken from its mound by a Rotting Offering, it fights in a
 * ring of crimson roots through three phases, each opened by an immune roar. Stampede: gore charges, the stamp's rolling
 * ring and a cap swipe. Sporefall: spore mortars and root eruptions too, and a coat of spores a fire hit flashes off.
 * Rot Bloom: chained charges that end in stamps, and the sporestorm only the shroomlight pillars shelter from; magma
 * cracks glow over its hide, and they flare white-hot while it rears for a stamp and as the stamp lands: any hit then
 * does FLARE_BONUS times its damage. Its cap never opens.
 * A plain spawn (a GameTest, a spawn egg) fights the same way with no arena.
 */
public class CrimsonBehemoth extends WildMob {
    public enum Phase { WAKE, STAMPEDE, ROAR, SPOREFALL, ROT_BLOOM, DYING }

    /** The move it is making; a wind-up (PAW, REAR, the tells) then its blow. Not saved: after a load it starts afresh. */
    public enum Moves { IDLE, PAW, GORE, STUNNED, REAR, STAMP, SWIPE, MORTAR, ERUPT, CHANNEL }

    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(CrimsonBehemoth.class, EntityDataSerializers.INT);
    /** Whether its cracks flare (flaring()), for the client's looks. */
    private static final EntityDataAccessor<Boolean> FLARE = SynchedEntityData.defineId(CrimsonBehemoth.class, EntityDataSerializers.BOOLEAN);
    /** Ticks its cracks take to light at phase 3, and to flare up and die back. */
    private static final float CRACKS_EASE = 20F, FLARE_UP = 3F, FLARE_DOWN = 8F;
    static final Identifier PARTY = FloraFerox.id("behemoth_party"), RAGE_DAMAGE = FloraFerox.id("behemoth_rage_damage"),
            RAGE_SPEED = FloraFerox.id("behemoth_rage_speed");
    /** The player tag that says they have had their first-kill Heart. */
    public static final String HEART_TAG = "floraferox.behemoth_heart";
    private static final DustParticleOptions RED_DUST = new DustParticleOptions(0xB0182A, 1.6F);

    public final BossStateMachine<Phase> phase = new BossStateMachine<>(Phase.class, Phase.WAKE)
            .allow((from, to) -> from != Phase.DYING).onChange((from, to) -> entered(from, to));
    private final ServerBossEvent bar = new ServerBossEvent(Mth.createInsecureUUID(random), getDisplayName(), BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);

    private Moves move = Moves.IDLE;
    private int moveTicks, stunFor, chain, mortarCooldown, eruptCooldown, stormCooldown, alone, roared;
    private boolean called;
    private Vec3 goreDir = Vec3.ZERO;
    private final List<Vec3> marks = new ArrayList<>();
    private final Set<Integer> struck = new HashSet<>();
    /** The core it woke from, and the arena it raised there (null and empty for a plain spawn). */
    private BlockPos core;
    private float cracks, cracksO, flare, flareO;                 // client: how lit the cracks are, how hard they flare (0..1)
    private List<BlockPos> ring = new ArrayList<>(), pillars = new ArrayList<>();

    public CrimsonBehemoth(EntityType<? extends Monster> type, Level level) {
        super(type, level, Wild.CRIMSON_BEHEMOTH);
        phase.syncTo(entityData, PHASE);
        xpReward = 80;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(PHASE, 0);
        b.define(FLARE, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PHASE.equals(key) && level().isClientSide()) phase.onSynced(entityData.get(PHASE));
    }

    /** The move, for tests. */
    public Moves move() { return move; }

    /**
     * Whether its cracks flare: in its last phase, while it rears for a stamp and for FLARE_GRACE ticks as a stamp lands
     * (a chained charge's stamp too). Any hit then does FLARE_BONUS times its damage.
     */
    public boolean flaring() {
        return phase.is(Phase.ROT_BLOOM) && (move == Moves.REAR || move == Moves.STAMP && moveTicks <= FLARE_GRACE);
    }

    /** Client: how lit its magma cracks are (0 none, 1 lit): they light in its last phase. */
    public float cracks(float partial) { return cracksO + (cracks - cracksO) * partial; }

    /** Client: how hard the cracks flare (0 not, 1 white-hot): while flaring(). */
    public float flare(float partial) { return flareO + (flare - flareO) * partial; }

    /** Which of its three phases it is in (1..3): its biome looks' growths show by it (VerdureAnimations.wild). */
    public int stage() {
        return phase.isAny(Phase.ROT_BLOOM, Phase.DYING) ? 3 : phase.isAny(Phase.ROAR, Phase.SPOREFALL) ? 2 : 1;
    }

    /** For tests: rears for a stamp now. */
    public void rear() { move(Moves.REAR); }

    public BlockPos core() { return core; }

    public List<BlockPos> ring() { return ring; }

    public List<BlockPos> pillars() { return pillars; }

    /** Wakes from a mound's core: the arena rises round it and it keeps to it. */
    public void wakeAt(ServerLevel level, BlockPos at) {
        core = at;
        setHomeTo(at, RING_RADIUS - 2);
        raise(level);
    }

    private void entered(Phase from, Phase to) {
        move(Moves.IDLE);
        if (level() instanceof ServerLevel level) {
            if (from == Phase.WAKE) party(level);
            if (to == Phase.ROAR) roar(level);
            if (to == Phase.SPOREFALL) {
                coat(level);
                if (!called) lurkers(level);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        phase.tick();
        if (level().isClientSide()) {
            cracksO = cracks;
            flareO = flare;
            cracks = ease(cracks, phase.isAny(Phase.ROT_BLOOM, Phase.DYING) ? 1F : 0F, 1F / CRACKS_EASE);
            boolean on = entityData.get(FLARE);
            flare = ease(flare, on ? 1F : 0F, on ? 1F / FLARE_UP : 1F / FLARE_DOWN);
        } else {
            entityData.set(FLARE, flaring());
        }
    }

    private static float ease(float v, float to, float step) {
        return v < to ? Math.min(to, v + step) : Math.max(to, v - step);
    }

    @Override
    protected boolean fights(ServerLevel level) {
        bar.setProgress(getHealth() / getMaxHealth());
        if (mortarCooldown > 0) mortarCooldown--;
        if (eruptCooldown > 0) eruptCooldown--;
        if (stormCooldown > 0) stormCooldown--;
        if (core != null) arena(level);
        if (isRemoved()) return true;
        switch (phase.state()) {
            case WAKE -> {
                getNavigation().stop();
                if (phase.ticks() % 10 == 0) level.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 1, getZ(), 30, 2, 1, 2, 0.05);
                if (phase.ticks() >= WAKE_TICKS) phase.to(Phase.STAMPEDE);
            }
            case ROAR -> {
                getNavigation().stop();
                if (phase.ticks() >= ROAR_TICKS) phase.to(roared >= 2 ? Phase.ROT_BLOOM : Phase.SPOREFALL);
            }
            case DYING -> getNavigation().stop();
            default -> {
                float share = getHealth() / getMaxHealth();
                if (phase.is(Phase.STAMPEDE) && share <= SPOREFALL_AT || phase.is(Phase.SPOREFALL) && share <= ROT_BLOOM_AT) {
                    roared++;
                    phase.to(Phase.ROAR);
                } else {
                    LivingEntity t = getTarget();
                    rage(t);
                    moves(level, t != null && t.isAlive() ? t : null);
                }
            }
        }
        anim(switch (move) {
            case PAW, REAR, MORTAR, ERUPT, CHANNEL -> StrikeBrain.State.TELL;
            case GORE, STAMP, SWIPE -> StrikeBrain.State.STRIKE;
            case STUNNED -> StrikeBrain.State.RECOVER;
            default -> phase.isAny(Phase.WAKE, Phase.ROAR) ? StrikeBrain.State.TELL : StrikeBrain.State.WALK;
        }, phase.isAny(Phase.WAKE, Phase.ROAR) ? phase.ticks() : moveTicks);
        return true;
    }

    // ---- the moves ----

    private void move(Moves next) {
        move = next;
        moveTicks = 0;
        struck.clear();
    }

    private void moves(ServerLevel level, LivingEntity t) {
        moveTicks++;
        switch (move) {
            case IDLE -> choose(level, t);
            case PAW -> {
                getNavigation().stop();
                if (t != null) {
                    getLookControl().setLookAt(t, 30F, 30F);
                    goreDir = t.position().subtract(position()).multiply(1, 0, 1);
                    goreDir = goreDir.lengthSqr() < 1.0E-4 ? getLookAngle().multiply(1, 0, 1).normalize() : goreDir.normalize();
                    for (int i = 1; i < 12; i += 2)
                        level.sendParticles(RED_DUST, getX() + goreDir.x * i, getY() + 0.2, getZ() + goreDir.z * i, 2, 0.3, 0.1, 0.3, 0);
                }
                if (moveTicks % 6 == 0) playSound(SoundEvents.RAVAGER_STEP, 1.2F, 0.6F);
                if (moveTicks >= PAW_TICKS) {
                    playSound(SoundEvents.RAVAGER_ROAR, 1.5F, 0.8F);
                    move(Moves.GORE);
                }
            }
            case GORE -> gore(level);
            case STUNNED -> {
                getNavigation().stop();
                if (moveTicks % 5 == 0) level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight() + 0.3, getZ(), 4, 0.5, 0.1, 0.5, 0);
                if (moveTicks >= stunFor) move(Moves.IDLE);
            }
            case REAR -> {
                getNavigation().stop();
                ring(level, STAMP_RADIUS, 24, RED_DUST);
                if (moveTicks >= REAR_TICKS) {
                    playSound(SoundEvents.RAVAGER_STUNNED, 1.5F, 0.7F);
                    move(Moves.STAMP);
                }
            }
            case STAMP -> stamp(level);
            case SWIPE -> {
                getNavigation().stop();
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks == SWIPE_TICKS / 2) {
                    playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.4F, 0.6F);
                    Vec3 ahead = getLookAngle().multiply(1, 0, 1).normalize();
                    for (LivingEntity e : foes(level, getBoundingBox().inflate(SWIPE_REACH, 1, SWIPE_REACH)))
                        if (e.position().subtract(position()).multiply(1, 0, 1).normalize().dot(ahead) > 0.2 && reach(e) <= SWIPE_REACH)
                            hit(level, e, Attack.MAIN, SWIPE_SHARE);
                }
                if (moveTicks >= SWIPE_TICKS) move(Moves.IDLE);
            }
            case MORTAR -> mortar(level, t);
            case ERUPT -> erupt(level);
            case CHANNEL -> channel(level);
        }
    }

    /** Picks its next move by the gap to its target and what its phase allows; or walks at it. */
    private void choose(ServerLevel level, LivingEntity t) {
        if (t == null) return;
        double d = reach(t);
        boolean spores = phase.isAny(Phase.SPOREFALL, Phase.ROT_BLOOM), bloom = phase.is(Phase.ROT_BLOOM);
        int slow = phase.is(Phase.SPOREFALL) ? 10 : 0;                         // charges and stamps come slower in phase 2
        if (moveTicks < 10 + slow) {
            chase(t);
            return;
        }
        if (bloom && stormCooldown == 0) {
            stormCooldown = STORM_COOLDOWN;
            playSound(SoundEvents.WARDEN_SONIC_CHARGE, 2F, 0.6F);
            move(Moves.CHANNEL);
        } else if (spores && mortarCooldown == 0 && d >= MORTAR_FROM) {
            mortarCooldown = MORTAR_COOLDOWN;
            marks.clear();
            int pods = bloom ? 5 : 3 + random.nextInt(3);
            for (int i = 0; i < pods; i++) {
                Vec3 at = i == 0 ? t.position() : t.position().add((random.nextDouble() - 0.5) * 2 * MORTAR_SPREAD, 0, (random.nextDouble() - 0.5) * 2 * MORTAR_SPREAD);
                marks.add(ground(level, at));
            }
            move(Moves.MORTAR);
        } else if (spores && eruptCooldown == 0 && d >= 3 && d < ERUPT_POINTS * ERUPT_GAP + 4) {
            eruptCooldown = ERUPT_COOLDOWN;
            marks.clear();
            Vec3 dir = t.position().subtract(position()).multiply(1, 0, 1).normalize();
            for (int i = 0; i < ERUPT_POINTS; i++) marks.add(ground(level, t.position().add(dir.scale((i - ERUPT_POINTS / 2) * ERUPT_GAP))));
            move(Moves.ERUPT);
        } else if (d <= SWIPE_REACH && random.nextInt(3) > 0) {
            move(Moves.SWIPE);
        } else if (d <= STAMP_REACH && random.nextBoolean()) {
            move(Moves.REAR);
        } else if (d >= GORE_FROM) {
            chain = bloom ? CHAIN - 1 : random.nextInt(2);                         // up to 2 in a row, 3 in the last phase
            move(Moves.PAW);
        } else {
            chase(t);
        }
    }

    /** The charge: straight along its heading, goring what it meets; a wall or pillar stuns it. */
    private void gore(ServerLevel level) {
        setDeltaMovement(goreDir.x * GORE_SPEED, getDeltaMovement().y, goreDir.z * GORE_SPEED);
        setYRot((float) (Mth.atan2(goreDir.z, goreDir.x) * Mth.RAD_TO_DEG) - 90F);
        yBodyRot = getYRot();
        if (moveTicks % 3 == 0) level.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 0.3, getZ(), 8, 0.8, 0.2, 0.8, 0.02);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(0.6))) {
            if (struck.add(e.getId()) && hit(level, e, Attack.MAIN, GORE_SHARE)) {
                e.knockback(GORE_KNOCKBACK, -goreDir.x, -goreDir.z);
                e.hurtMarked = true;
            }
        }
        if (horizontalCollision && moveTicks > 2) {
            playSound(SoundEvents.RAVAGER_STUNNED, 2F, 0.6F);
            level.sendParticles(ParticleTypes.EXPLOSION, getX() + goreDir.x * 1.5, getY() + 1, getZ() + goreDir.z * 1.5, 2, 0.4, 0.4, 0.4, 0);
            stun(WALL_STUN_TICKS);
        } else if (moveTicks >= GORE_TICKS) {
            setDeltaMovement(getDeltaMovement().multiply(0.2, 1, 0.2));
            if (phase.is(Phase.ROT_BLOOM)) move(Moves.STAMP);                // its chained charges each end in a stamp
            else if (chain-- > 0) move(Moves.PAW);
            else move(Moves.IDLE);
        }
    }

    public void stun(int ticks) {
        stunFor = ticks;
        chain = 0;
        move(Moves.STUNNED);
    }

    /** The stamp: a ring of crimson spikes rolls out STAMP_RADIUS blocks; whoever it meets on the ground is hit and thrown up. */
    private void stamp(ServerLevel level) {
        getNavigation().stop();
        double r = STAMP_RADIUS * moveTicks / STAMP_TICKS, was = STAMP_RADIUS * (moveTicks - 1) / STAMP_TICKS;
        if (moveTicks == 1) playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.5F);
        ring(level, r, (int) (8 + r * 5), ParticleTypes.CRIMSON_SPORE);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(STAMP_RADIUS + 1, 2, STAMP_RADIUS + 1))) {
            double d = Math.sqrt(e.distanceToSqr(getX(), e.getY(), getZ()));
            if (d <= r + 0.75 && d >= was - 0.75 && e.onGround() && Math.abs(e.getY() - getY()) < 2 && struck.add(e.getId())
                    && hit(level, e, Attack.SLAM, STAMP_SHARE)) {
                e.push(0, STAMP_UP, 0);
                e.hurtMarked = true;
            }
        }
        if (moveTicks >= STAMP_TICKS) {
            if (phase.is(Phase.ROT_BLOOM) && chain-- > 0) move(Moves.PAW);
            else move(Moves.IDLE);
        }
    }

    /** The spore mortar: a red spore circle where each pod will land, then the pods, each leaving a spore cloud. */
    private void mortar(ServerLevel level, LivingEntity t) {
        getNavigation().stop();
        if (t != null) getLookControl().setLookAt(t, 30F, 30F);
        if (moveTicks == MORTAR_TELL) {
            playSound(SoundEvents.SNIFFER_EGG_PLOP, 2F, 0.5F);
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight(), getZ(), 20, 0.5, 0.5, 0.5, 0.1);
        }
        if (moveTicks % 4 == 0) for (Vec3 m : marks) circle(level, m, MORTAR_RADIUS, 12, RED_DUST);
        if (moveTicks >= MORTAR_TELL + MORTAR_FLIGHT) {
            for (Vec3 m : marks) {
                level.sendParticles(ParticleTypes.CRIMSON_SPORE, m.x, m.y + 0.5, m.z, 40, 1, 0.5, 1, 0.05);
                for (LivingEntity e : foes(level, new AABB(m, m).inflate(MORTAR_RADIUS, 1.5, MORTAR_RADIUS)))
                    hit(level, e, Attack.SPORE, MORTAR_SHARE);
                AreaEffectCloud cloud = new AreaEffectCloud(level, m.x, m.y, m.z);
                cloud.setOwner(this);
                cloud.setRadius((float) MORTAR_RADIUS);
                cloud.setDuration(MORTAR_CLOUD_TICKS);
                cloud.setWaitTime(0);
                cloud.setRadiusPerTick(0F);
                cloud.setCustomParticle(ParticleTypes.CRIMSON_SPORE);
                cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
                level.addFreshEntity(cloud);
            }
            playSound(SoundEvents.SNIFFER_EGG_CRACK, 2F, 0.6F);
            marks.clear();
            coat(level);
            move(Moves.IDLE);
        }
    }

    /** Root eruption: cracks and dust along a line through its target, then roots burst up there. */
    private void erupt(ServerLevel level) {
        getNavigation().stop();
        if (moveTicks % 3 == 0) for (Vec3 m : marks) level.sendParticles(RED_DUST, m.x, m.y + 0.1, m.z, 4, 0.4, 0.05, 0.4, 0);
        if (moveTicks >= ERUPT_TELL) {
            playSound(SoundEvents.ROOTED_DIRT_BREAK, 2F, 0.5F);
            for (Vec3 m : marks) {
                level.sendParticles(ParticleTypes.CRIMSON_SPORE, m.x, m.y + 1, m.z, 15, 0.3, 1, 0.3, 0.05);
                for (LivingEntity e : foes(level, new AABB(m, m).inflate(ERUPT_RADIUS, 2, ERUPT_RADIUS)))
                    if (struck.add(e.getId()) && hit(level, e, Attack.ERUPT, ERUPT_SHARE)) {
                        e.push(0, ROOT_UP, 0);
                        e.hurtMarked = true;
                    }
            }
            marks.clear();
            move(Moves.IDLE);
        }
    }

    /** The sporestorm: it stands and channels, then spore fills the ring, save behind a shroomlight pillar. */
    private void channel(ServerLevel level) {
        getNavigation().stop();
        if (moveTicks % 5 == 0) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + getBbHeight(), getZ(), 20, 1, 0.5, 1, 0.1);
            for (BlockPos p : pillars) level.sendParticles(ParticleTypes.GLOW, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0.3, 0.3, 0.3, 0);
        }
        if (moveTicks % 20 == 0) playSound(SoundEvents.WARDEN_HEARTBEAT, 2F, 0.6F);
        if (moveTicks >= CHANNEL_TICKS) {
            playSound(SoundEvents.WARDEN_SONIC_BOOM, 2F, 0.5F);
            Vec3 c = core != null ? Vec3.atBottomCenterOf(core) : position();
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, c.x, c.y + 2, c.z, 400, RING_RADIUS * 0.5, 2, RING_RADIUS * 0.5, 0.05);
            for (LivingEntity e : foes(level, new AABB(c, c).inflate(RING_RADIUS, 8, RING_RADIUS)))
                if (e.distanceToSqr(c.x, e.getY(), c.z) <= RING_RADIUS * RING_RADIUS && !sheltered(level, e))
                    hit(level, e, Attack.SPORE, STORM_SHARE);
            move(Moves.IDLE);
        }
    }

    /** Whether a shroomlight pillar stands between it and e. */
    public boolean sheltered(ServerLevel level, LivingEntity e) {
        Vec3 from = position().add(0, getBbHeight() * 0.5, 0), to = e.getEyePosition();
        double len = from.distanceTo(to);
        Vec3 step = to.subtract(from).normalize().scale(0.5);
        Vec3 at = from;
        for (double d = 0; d < len; d += 0.5, at = at.add(step))
            if (level.getBlockState(BlockPos.containing(at)).is(VerdureBlocks.BEHEMOTH_PILLAR.get())) return true;
        return false;
    }

    // ---- phases' side effects ----

    /** Party scaling at the end of its wake: +EXTRA_PLAYER_HEALTH health for each player in the ring past the first. */
    private void party(ServerLevel level) {
        Vec3 c = core != null ? Vec3.atBottomCenterOf(core) : position();
        long players = level.players().stream().filter(p -> !p.isSpectator() && p.distanceToSqr(c) <= RING_RADIUS * RING_RADIUS).count();
        AttributeInstance health = getAttribute(Attributes.MAX_HEALTH);
        if (health == null) return;
        health.removeModifier(PARTY);
        if (players > 1)
            health.addPermanentModifier(new AttributeModifier(PARTY, EXTRA_PLAYER_HEALTH * (players - 1), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        setHealth(getMaxHealth());
    }

    /** The roar: immune for ROAR_TICKS, it throws back whoever is near. */
    private void roar(ServerLevel level) {
        playSound(SoundEvents.RAVAGER_ROAR, 3F, 0.5F);
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 2, getZ(), 120, 3, 1.5, 3, 0.1);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(6))) {
            e.knockback(2.0, getX() - e.getX(), getZ() - e.getZ());
            e.hurtMarked = true;
        }
    }

    /** Coats itself in spores: a fire hit sets off the flash and staggers it. */
    private void coat(ServerLevel level) {
        ElementAuras.apply(this, VerdureElements.SPORE, VerdureElements.SPORE_AURA,
                VerdureDamage.source(level, VerdureElements.type("spore"), this, this));
    }

    private void lurkers(ServerLevel level) {
        called = true;
        for (int i = 0; i < LURKERS; i++) {
            Vec3 at = position().add((random.nextDouble() - 0.5) * 8, 0, (random.nextDouble() - 0.5) * 8);
            WildMob m = VerdureEntities.wild(Wild.NYLIUM_LURKER).spawn(level, BlockPos.containing(ground(level, at)), EntitySpawnReason.MOB_SUMMONED);
            if (m != null && getTarget() != null) m.setTarget(getTarget());
        }
    }

    /** Leaving the ring enrages it: faster and harder-hitting until its target comes back in. */
    private void rage(LivingEntity t) {
        boolean out = core != null && t != null && t.distanceToSqr(core.getX() + 0.5, t.getY(), core.getZ() + 0.5) > RING_RADIUS * RING_RADIUS;
        modifier(Attributes.ATTACK_DAMAGE, RAGE_DAMAGE, ENRAGE_DAMAGE, out);
        modifier(Attributes.MOVEMENT_SPEED, RAGE_SPEED, ENRAGE_SPEED, out);
    }

    public boolean enraged() {
        AttributeInstance a = getAttribute(Attributes.ATTACK_DAMAGE);
        return a != null && a.hasModifier(RAGE_DAMAGE);
    }

    private void modifier(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, Identifier id, double amount, boolean on) {
        AttributeInstance a = getAttribute(attribute);
        if (a == null || a.hasModifier(id) == on) return;
        if (on) a.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        else a.removeModifier(id);
    }

    // ---- the arena ----

    /** Raises the ring of roots and the four shroomlight pillars round its core. */
    private void raise(ServerLevel level) {
        BlockState root = VerdureBlocks.BEHEMOTH_ROOTS.get().defaultBlockState(), pillar = VerdureBlocks.BEHEMOTH_PILLAR.get().defaultBlockState();
        ring.clear();
        pillars.clear();
        int steps = (int) (Math.PI * 2 * RING_RADIUS * 1.5);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2 * i / steps;
            BlockPos col = core.offset((int) Math.round(Math.cos(a) * RING_RADIUS), 0, (int) Math.round(Math.sin(a) * RING_RADIUS));
            if (!seen.add(BlockPos.asLong(col.getX(), 0, col.getZ()))) continue;
            BlockPos base = floor(level, col);
            for (int y = 0; y < RING_HEIGHT; y++) if (put(level, base.above(y), root)) ring.add(base.above(y));
        }
        int[][] at = { { PILLAR_OFFSET, 0 }, { -PILLAR_OFFSET - 1, 0 }, { 0, PILLAR_OFFSET }, { 0, -PILLAR_OFFSET - 1 } };
        for (int[] o : at)
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) {
                    BlockPos base = floor(level, core.offset(o[0] + dx, 0, o[1] + dz));
                    for (int y = 0; y < PILLAR_HEIGHT; y++) if (put(level, base.above(y), pillar)) pillars.add(base.above(y));
                }
    }

    /** The first open block over the ground near col's height. */
    private static BlockPos floor(ServerLevel level, BlockPos col) {
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos p = col.above(dy);
            if (open(level, p) && !open(level, p.below())) return p;
        }
        return col;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.canBeReplaced();
    }

    private static boolean put(ServerLevel level, BlockPos p, BlockState s) {
        if (!open(level, p)) return false;
        level.setBlock(p, s, Block.UPDATE_ALL);
        return true;
    }

    /** The arena each tick: roots regrow, the leash counts down while no one is near. */
    private void arena(ServerLevel level) {
        if (tickCount % RING_REGROW == 0) {
            BlockState root = VerdureBlocks.BEHEMOTH_ROOTS.get().defaultBlockState();
            for (BlockPos p : ring) if (open(level, p)) level.setBlock(p, root, Block.UPDATE_ALL);
        }
        Vec3 c = Vec3.atBottomCenterOf(core);
        boolean near = level.players().stream().anyMatch(p -> !p.isSpectator() && p.distanceToSqr(c) <= LEASH_RANGE * LEASH_RANGE);
        alone = near ? 0 : alone + 1;
        if (alone >= LEASH_TICKS && !phase.is(Phase.DYING)) sink(level);
    }

    /** No one came back: it sinks into its mound, healed, and the ring falls; the mound can be woken again. */
    public void sink(ServerLevel level) {
        fall(level);
        if (level.getBlockState(core).getBlock() instanceof BehemothCoreBlock) BehemothCoreBlock.ready(level, core);
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 1, getZ(), 80, 2, 1, 2, 0.05);
        discard();
    }

    /** The ring and the pillars go. */
    private void fall(ServerLevel level) {
        for (BlockPos p : ring) if (level.getBlockState(p).is(VerdureBlocks.BEHEMOTH_ROOTS.get())) level.removeBlock(p, false);
        for (BlockPos p : pillars) if (level.getBlockState(p).is(VerdureBlocks.BEHEMOTH_PILLAR.get())) level.removeBlock(p, false);
        ring.clear();
        pillars.clear();
    }

    // ---- helpers ----

    /** One blow of attack, a share of its attack damage. */
    private boolean hit(ServerLevel level, LivingEntity e, Attack attack, double share) {
        boolean[] hurt = new boolean[1];
        as(attack, () -> hurt[0] = e.hurtServer(level, damageSources().mobAttack(this), (float) (getAttributeValue(Attributes.ATTACK_DAMAGE) * share)));
        if (hurt[0]) strikes++;
        return hurt[0];
    }

    /** Who its blows hit: living things but itself, its lurkers and players out of the fight. */
    private List<LivingEntity> foes(ServerLevel level, AABB box) {
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e != this && e.isAlive() && !(e instanceof WildMob w && w.profile == Wild.NYLIUM_LURKER)
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e));
    }

    private Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos p = floor(level, BlockPos.containing(at.x, getY(), at.z));
        return new Vec3(at.x, p.getY(), at.z);
    }

    private void ring(ServerLevel level, double r, int n, net.minecraft.core.particles.ParticleOptions particle) {
        circle(level, position(), r, n, particle);
    }

    private static void circle(ServerLevel level, Vec3 c, double r, int n, net.minecraft.core.particles.ParticleOptions particle) {
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.sendParticles(particle, c.x + Math.cos(a) * r, c.y + 0.15, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    // ---- vanilla hooks ----

    @Override
    protected void chase(LivingEntity t) {
        if (move == Moves.IDLE && !phase.isAny(Phase.WAKE, Phase.ROAR, Phase.DYING)) super.chase(t);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (phase.isAny(Phase.WAKE, Phase.ROAR, Phase.DYING) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (flaring()) amount *= FLARE_BONUS;                                       // its cracks flare: any hit bites deeper
        boolean coated = ElementAuras.find(this, VerdureElements.SPORE) != null && VerdureElements.FIRE.matches(source);
        boolean hurt = super.hurtServer(level, source, amount);
        if (coated && hurt && ElementAuras.find(this, VerdureElements.SPORE) == null && isAlive()) stun(FLASH_STUN_TICKS);   // the spore flash staggers it
        return hurt;
    }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!(level() instanceof ServerLevel level)) return;
        phase.to(Phase.DYING);
        move(Moves.IDLE);
        bar.setProgress(0F);
        hearts(level);
        if (core != null) {
            fall(level);
            if (level.getBlockState(core).getBlock() instanceof BehemothCoreBlock) BehemothCoreBlock.spent(level, core);
        }
    }

    /** The first kill for each player in the fight gives them a Behemoth Heart. */
    private void hearts(ServerLevel level) {
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(LEASH_RANGE), p -> !p.isSpectator())) {
            if (!p.addTag(HEART_TAG)) continue;
            ItemEntity heart = new ItemEntity(level, getX(), getY() + 1, getZ(), new ItemStack(VerdureItems.BEHEMOTH_HEART.get()));
            heart.setTarget(p.getUUID());
            level.addFreshEntity(heart);
        }
    }

    /** It topples and rots for DYING_TICKS. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (level() instanceof ServerLevel level && deathTime % 5 == 0)
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 1, getZ(), 20, 1.5, 1, 1.5, 0.02);
        if (deathTime >= DYING_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(Entity.RemovalReason.KILLED);
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bar.removePlayer(player);
    }

    @Override
    public boolean checkSpawnRules(net.minecraft.world.level.LevelAccessor level, EntitySpawnReason reason) {
        return reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION && super.checkSpawnRules(level, reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        phase.save(out, "phase");
        out.putInt("Roared", roared);
        out.putBoolean("Called", called);
        out.putInt("Alone", alone);
        out.putInt("MortarCooldown", mortarCooldown);
        out.putInt("EruptCooldown", eruptCooldown);
        out.putInt("StormCooldown", stormCooldown);
        if (core != null) {
            out.putLong("Core", core.asLong());
            out.store("Ring", BlockPos.CODEC.listOf(), ring);
            out.store("Pillars", BlockPos.CODEC.listOf(), pillars);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        phase.load(in, "phase");
        roared = in.getIntOr("Roared", 0);
        called = in.getBooleanOr("Called", false);
        alone = in.getIntOr("Alone", 0);
        mortarCooldown = in.getIntOr("MortarCooldown", 0);
        eruptCooldown = in.getIntOr("EruptCooldown", 0);
        stormCooldown = in.getIntOr("StormCooldown", 0);
        core = in.getLong("Core").map(BlockPos::of).orElse(null);
        ring = new ArrayList<>(in.read("Ring", BlockPos.CODEC.listOf()).orElse(List.of()));
        pillars = new ArrayList<>(in.read("Pillars", BlockPos.CODEC.listOf()).orElse(List.of()));
        if (core != null) setHomeTo(core, RING_RADIUS - 2);
        if (hasCustomName()) bar.setName(getDisplayName());
    }

    @Override
    public void setCustomName(net.minecraft.network.chat.Component name) {
        super.setCustomName(name);
        bar.setName(getDisplayName());
    }
}
