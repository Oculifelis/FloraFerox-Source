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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Briar King's fight (docs/DESIGN.md section 15.2): woken at night on his Briar Throne by a Thorned Rose, he holds
 * court in a ring of bramble walls and sinks back at sunrise. Court: charges after a scrape (a wall or tree tangles him),
 * a bramble burst that slows, a hurled log. At 60% he roars into his Thorncourt: thorn walls traced ahead, thorns that
 * prick his strikers, two Root Grubs, charges that bend once, and a charge through his own wall tangles him and brings it
 * down. At 30% the Wild Rose: a petal storm that slows all not under trees, a knuckle slam, charges in pairs, and three
 * roses on his back that take double and stagger him, once each. His blows are of the body, and they slow.
 */
public class BriarKing extends WildBoss {
    public enum Phase { RISE, COURT, ROAR, THORNCOURT, WILD_ROSE, DYING }

    /** The move he is making (his charge itself is WildMob's CHARGE act, so it saves and syncs with the rest). */
    public enum Moves { IDLE, SCRAPE, CHARGE, TANGLED, BURST, UPROOT, WALLS, GLOW, STORM, REAR, STAGGER }

    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(BriarKing.class, EntityDataSerializers.INT);
    private static final DustParticleOptions GREEN = new DustParticleOptions(0x3F8F3A, 1.4F), ROSE = new DustParticleOptions(0xD8354F, 1.2F);
    /** How long his thorn walls stand. */
    private static final int WALL_LIFE = 300;
    /** Ticks his antler crown takes to flower or wither, and his great roses to open or shut. */
    private static final float CROWN_EASE = 20F, ROSES_EASE = 16F;

    public final BossStateMachine<Phase> phase = new BossStateMachine<>(Phase.class, Phase.RISE)
            .allow((from, to) -> from != Phase.DYING).onChange((from, to) -> entered(from, to));

    private Moves move = Moves.IDLE;
    private int moveTicks, tangle, chain, chargeCooldown, burstCooldown, uprootCooldown, wallCooldown, stormCooldown, knuckleCooldown, wallAge;
    private boolean bent, grubs;
    private float crown, crownO, rose, roseO;                    // client: how far the crown has flowered, the roses opened (0..1)
    /** The roses on his back still whole, one bit each. */
    private int roses = 0b111;
    /** His two thorn walls (each a line of bramble), and where the next ones will rise. */
    private final List<BlockPos> wallA = new ArrayList<>(), wallB = new ArrayList<>();
    private Vec3 wallFrom = Vec3.ZERO, wallTo = Vec3.ZERO, wallSide = Vec3.ZERO;

    public BriarKing(EntityType<? extends Monster> type, Level level) {
        super(type, level, Wild.BRIAR_KING, BossEvent.BossBarColor.GREEN, KING_RING, KING_LEASH, 70);
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

    /** The roses on his back still whole (bits), and his thorn walls, for tests. */
    public int roses() { return roses; }

    public List<BlockPos> walls() {
        List<BlockPos> all = new ArrayList<>(wallA);
        all.addAll(wallB);
        return all;
    }

    private void entered(Phase from, Phase to) {
        move(Moves.IDLE);
        if (!(level() instanceof ServerLevel level)) return;
        if (from == Phase.RISE) {
            party(level);
            roar(level, SoundEvents.RAVAGER_ROAR, 1.6);
        }
        if (to == Phase.ROAR) roar(level, SoundEvents.RAVAGER_ROAR, 1.2);
        if (to == Phase.THORNCOURT && !grubs) {
            grubs = true;
            call(level, Wild.ROOT_GRUB, GRUBS, 6);
        }
    }

    @Override
    public void tick() {
        super.tick();
        phase.tick();
        if (level().isClientSide()) {
            crownO = crown;
            roseO = rose;
            crown = ease(crown, phase.isAny(Phase.THORNCOURT, Phase.WILD_ROSE) ? 1F : 0F, CROWN_EASE);
            rose = ease(rose, phase.is(Phase.WILD_ROSE) ? 1F : 0F, ROSES_EASE);
        }
    }

    private static float ease(float v, float to, float ticks) {
        return v < to ? Math.min(to, v + 1F / ticks) : Math.max(to, v - 1F / ticks);
    }

    /** Client: how far his antler crown has flowered (0 bare, 1 in bloom): from phase 2 (his thorn court) on. */
    public float crown(float partial) { return crownO + (crown - crownO) * partial; }

    /** Client: how open the great roses on his back are (0 shut buds, 1 open): in phase 3 (the wild rose). */
    public float roses(float partial) { return roseO + (rose - roseO) * partial; }

    @Override
    protected void fight(ServerLevel level) {
        if (chargeCooldown > 0) chargeCooldown--;
        if (burstCooldown > 0) burstCooldown--;
        if (uprootCooldown > 0) uprootCooldown--;
        if (wallCooldown > 0) wallCooldown--;
        if (stormCooldown > 0) stormCooldown--;
        if (knuckleCooldown > 0) knuckleCooldown--;
        if (!wallA.isEmpty() || !wallB.isEmpty()) {
            if (++wallAge >= WALL_LIFE) {
                drop(level, wallA);
                drop(level, wallB);
            }
        }
        switch (phase.state()) {
            case RISE -> {
                getNavigation().stop();
                if (phase.ticks() % 4 == 0)
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DARK_OAK_LOG.defaultBlockState()), getX(), getY() + 0.2, getZ(), 16, 1, 0.2, 1, 0.1);
                if (phase.ticks() >= WAKE_TICKS) phase.to(Phase.COURT);
            }
            case ROAR -> {
                getNavigation().stop();
                if (phase.ticks() >= ROAR_TICKS) phase.to(roared >= 2 ? Phase.WILD_ROSE : Phase.THORNCOURT);
            }
            case DYING -> getNavigation().stop();
            default -> {
                float share = getHealth() / getMaxHealth();
                if (phase.is(Phase.COURT) && share <= THORNCOURT_AT || phase.is(Phase.THORNCOURT) && share <= WILD_ROSE_AT) {
                    if (act() == Act.CHARGE) charging(null, 0);
                    roared++;
                    phase.to(Phase.ROAR);
                } else {
                    LivingEntity t = getTarget();
                    moves(level, t != null && t.isAlive() ? t : null);
                }
            }
        }
        anim(switch (move) {
            case SCRAPE, BURST, UPROOT, WALLS, GLOW, REAR -> StrikeBrain.State.TELL;
            case CHARGE, STORM -> StrikeBrain.State.STRIKE;
            case TANGLED, STAGGER -> StrikeBrain.State.RECOVER;
            default -> phase.isAny(Phase.RISE, Phase.ROAR) ? StrikeBrain.State.TELL : StrikeBrain.State.WALK;
        }, phase.isAny(Phase.RISE, Phase.ROAR) ? phase.ticks() : moveTicks);
    }

    private void move(Moves next) {
        move = next;
        moveTicks = 0;
        struck.clear();
    }

    private void moves(ServerLevel level, LivingEntity t) {
        moveTicks++;
        if (act() == Act.CHARGE && move != Moves.CHARGE) move(Moves.CHARGE);           // a charge brought back from a save
        switch (move) {
            case IDLE -> choose(level, t);
            case SCRAPE -> {
                getNavigation().stop();
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks % 4 == 0) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(blockPosition().below())), getX(), getY() + 0.1, getZ(),
                            10, 0.6, 0.1, 0.6, 0.1);
                    playSound(SoundEvents.ROOTED_DIRT_BREAK, 1.2F, 0.6F);
                }
                if (moveTicks >= SCRAPE_TICKS) charge(t);
            }
            case CHARGE -> charge(level, t);
            case TANGLED, STAGGER -> {
                getNavigation().stop();
                if (moveTicks % 5 == 0) level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight() + 0.3, getZ(), 4, 0.6, 0.1, 0.6, 0);
                if (moveTicks >= tangle) move(Moves.IDLE);
            }
            case BURST -> {
                getNavigation().stop();
                circle(level, position(), BURST_RADIUS * Math.min(1.0, (moveTicks + 2) / (double) BURST_TELL), 24, GREEN);
                if (moveTicks >= BURST_TELL) {
                    playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 2F, 0.5F);
                    circle(level, position(), BURST_RADIUS, 30, ParticleTypes.CRIT);
                    for (LivingEntity e : foes(level, getBoundingBox().inflate(BURST_RADIUS, 1.5, BURST_RADIUS)))
                        if (e.distanceToSqr(getX(), e.getY(), getZ()) <= BURST_RADIUS * BURST_RADIUS && struck.add(e.getId()) && hit(level, e, Attack.MAIN, BURST_SHARE))
                            mark(e);
                    move(Moves.IDLE);
                }
            }
            case UPROOT -> {
                getNavigation().stop();
                if (t != null) getLookControl().setLookAt(t, 30F, 30F);
                if (moveTicks % 4 == 0)
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DARK_OAK_LOG.defaultBlockState()), getX(), getY() + getBbHeight(), getZ(),
                            8, 0.4, 0.3, 0.4, 0.05);
                if (moveTicks >= UPROOT_TELL) {
                    if (t != null) {
                        Vec3 from = position().add(0, getBbHeight() + 0.5, 0), aim = t.getBoundingBox().getCenter().subtract(from);
                        fire(level, from, aim.add(0, aim.horizontalDistance() * 0.08, 0), "mire_arc", UPROOT_SHARE, 1.2, 0.04);
                    }
                    playSound(SoundEvents.WOOD_BREAK, 2F, 0.5F);
                    move(Moves.IDLE);
                }
            }
            case WALLS -> {
                getNavigation().stop();
                for (int s = -1; s <= 1; s += 2) line(level, wallFrom.add(wallSide.scale(s * 3)), wallTo.add(wallSide.scale(s * 3)), GREEN);   // traced ahead
                if (moveTicks >= WALL_TELL) {
                    raiseWalls(level);
                    move(Moves.IDLE);
                }
            }
            case GLOW -> {
                getNavigation().stop();
                if (moveTicks % 3 == 0) level.sendParticles(ROSE, getX(), getY() + getBbHeight(), getZ(), 10, 1, 0.5, 1, 0);   // his roses glow
                if (moveTicks >= STORM_TELL) {
                    playSound(SoundEvents.CHERRY_LEAVES_BREAK, 3F, 0.5F);
                    move(Moves.STORM);
                }
            }
            case STORM -> {
                if (moveTicks % 10 == 0) storm(level);
                if (t != null) chase(t);
                if (moveTicks >= STORM_TICKS) move(Moves.IDLE);
            }
            case REAR -> {
                getNavigation().stop();
                if (moveTicks % 4 == 0) level.sendParticles(GREEN, getX(), getY() + getBbHeight() + 0.5, getZ(), 8, 0.8, 0.3, 0.8, 0);
                if (moveTicks >= KNUCKLE_TELL) {
                    playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.4F, 0.8F);
                    shockwave(level, position(), KNUCKLE_RADIUS, Attack.SLAM, KNUCKLE_SHARE);
                    move(Moves.IDLE);
                }
            }
        }
    }

    /** Picks his next move by the gap and his phase; or walks at his target. */
    private void choose(ServerLevel level, LivingEntity t) {
        if (t == null) return;
        double d = reach(t);
        boolean court = phase.is(Phase.THORNCOURT), rose = phase.is(Phase.WILD_ROSE);
        if (moveTicks < 10) {
            chase(t);
            return;
        }
        if (rose && stormCooldown == 0) {
            stormCooldown = 400;
            move(Moves.GLOW);
        } else if (d > CHARGE_FROM && d < CHARGE_TO && chargeCooldown == 0 && onGround() && hasLineOfSight(t)) {
            chain = rose ? 1 : 0;                                                      // in the Wild Rose his charges come in pairs
            move(Moves.SCRAPE);
        } else if (court && wallCooldown == 0 && wallA.isEmpty() && wallB.isEmpty()) {
            wallCooldown = 260;
            wallFrom = position();
            Vec3 dir = flatTo(t.position());
            wallTo = wallFrom.add(dir.scale(WALL_LENGTH));
            wallSide = new Vec3(-dir.z, 0, dir.x);
            move(Moves.WALLS);
        } else if (rose && knuckleCooldown == 0 && d < KNUCKLE_RADIUS - 1) {
            knuckleCooldown = 160;
            move(Moves.REAR);
        } else if (burstCooldown == 0 && d < BURST_RADIUS) {
            burstCooldown = 120;
            move(Moves.BURST);
        } else if (uprootCooldown == 0 && d >= 5 && hasLineOfSight(t)) {
            uprootCooldown = 160;
            move(Moves.UPROOT);
        } else {
            chase(t);
        }
    }

    /** Off on a charge at t (the scrape is done). */
    private void charge(LivingEntity t) {
        Vec3 dir = t != null ? flatTo(t.position()) : getLookAngle().multiply(1, 0, 1).normalize();
        charging(dir, CHARGE_TICKS);
        chargeCooldown = CHARGE_COOLDOWN;
        bent = false;
        playSound(SoundEvents.RAVAGER_ROAR, 1.2F, 1.0F);
        move(Moves.CHARGE);
    }

    /** His charge, each tick: he runs down whoever is in the way; a wall or a tree tangles him, his own thorn wall falls. */
    private void charge(ServerLevel level, LivingEntity t) {
        if (act() != Act.CHARGE) {
            move(Moves.IDLE);
            return;
        }
        Vec3 dir = chargeDir();
        int left = actTicks() - 1;
        getNavigation().stop();
        if (phase.isAny(Phase.THORNCOURT, Phase.WILD_ROSE) && !bent && t != null && left == CHARGE_TICKS / 2) {
            bent = true;                                                               // it bends once toward its prey
            dir = flatTo(t.position());
        }
        face(dir);
        setDeltaMovement(dir.x * CHARGE_SPEED, getDeltaMovement().y, dir.z * CHARGE_SPEED);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(0.4)))
            if (struck.add(e.getId()) && hit(level, e, Attack.MAIN, CHARGE_SHARE)) {
                mark(e);
                e.knockback(CHARGE_KNOCKBACK, -dir.x, -dir.z);
                e.hurtMarked = true;
            }
        List<BlockPos> wall = through(level);
        if (wall != null) {
            playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 2F, 0.4F);
            drop(level, wall);                                                         // his own wall comes down on him
            charging(null, 0);
            tangle(WALL_TANGLE_TICKS);
        } else if (horizontalCollision) {
            playSound(SoundEvents.WOOD_BREAK, 1.6F, 0.6F);
            charging(null, 0);
            tangle(TANGLE_TICKS);                                                      // a wall or a tree tangles him
        } else if (left <= 0) {
            charging(null, 0);
            if (chain-- > 0 && t != null) charge(t);
            else move(Moves.IDLE);
        } else {
            charging(dir, left);
        }
    }

    private void tangle(int ticks) {
        tangle = ticks;
        move(Moves.TANGLED);
    }

    /** Which of his thorn walls he is in, if any. */
    private List<BlockPos> through(ServerLevel level) {
        AABB box = getBoundingBox().inflate(0.3, 0, 0.3);
        for (List<BlockPos> w : List.of(wallA, wallB))
            for (BlockPos p : w) if (box.intersects(new AABB(p)) && ours(level.getBlockState(p))) return w;
        return null;
    }

    /** His two thorn walls rise along the traced lines, two blocks high, either side of the lane. */
    private void raiseWalls(ServerLevel level) {
        drop(level, wallA);
        drop(level, wallB);
        wallAge = 0;
        BlockState s = VerdureBlocks.BRIAR_BRAMBLE.get().defaultBlockState();
        for (int side = -1; side <= 1; side += 2) {
            List<BlockPos> w = side < 0 ? wallA : wallB;
            Vec3 a = wallFrom.add(wallSide.scale(side * 3)), b = wallTo.add(wallSide.scale(side * 3));
            for (int i = 0; i <= WALL_LENGTH; i++) {
                Vec3 at = a.lerp(b, i / (double) WALL_LENGTH);
                BlockPos f = floor(level, BlockPos.containing(at.x, getY(), at.z));
                for (int y = 0; y < 2; y++) if (!w.contains(f.above(y)) && put(level, f.above(y), s)) w.add(f.above(y));
            }
        }
        playSound(SoundEvents.SWEET_BERRY_BUSH_PLACE, 2F, 0.5F);
    }

    private void drop(ServerLevel level, List<BlockPos> wall) {
        for (BlockPos p : wall) if (ours(level.getBlockState(p))) level.removeBlock(p, false);
        wall.clear();
    }

    /** The petal storm's gust: Slowness II on everyone in the open (not under trees) round him. */
    private void storm(ServerLevel level) {
        double r = KING_RING;
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 3, getZ(), 60, r * 0.5, 2, r * 0.5, 0.05);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(r, 6, r)))
            if (level.canSeeSky(e.blockPosition().above())) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 1), this);
    }

    // ---- what WildBoss asks ----

    @Override
    protected boolean immune() { return phase.isAny(Phase.RISE, Phase.ROAR, Phase.DYING); }

    @Override
    protected void dying() {
        if (act() == Act.CHARGE) charging(null, 0);
        phase.to(Phase.DYING);
        move(Moves.IDLE);
    }

    @Override
    protected BlockState ringBlock() { return VerdureBlocks.BRIAR_BRAMBLE.get().defaultBlockState(); }

    @Override
    protected int ringHeight() { return 2; }

    @Override
    protected boolean ours(BlockState s) { return s.is(VerdureBlocks.BRIAR_BRAMBLE.get()); }

    @Override
    protected ItemStack trophy() { return new ItemStack(VerdureItems.KINGS_ROSEHIP.get()); }

    @Override
    protected void homeReady(ServerLevel level) { BriarThroneBlock.rest(level, home, false); }

    @Override
    protected void homeSpent(ServerLevel level) { BriarThroneBlock.rest(level, home, true); }

    /** He sinks back into his throne at sunrise. */
    @Override
    protected boolean leaves(ServerLevel level) { return level.isBrightOutside(); }

    @Override
    protected void fall(ServerLevel level) {
        super.fall(level);
        drop(level, wallA);
        drop(level, wallB);
    }

    @Override
    protected ParticleOptions dust() { return GREEN; }

    @Override
    protected boolean helper(WildMob w) { return w.profile == Wild.ROOT_GRUB; }

    @Override
    protected void chase(LivingEntity t) {
        if ((move == Moves.IDLE || move == Moves.STORM) && !immune()) super.chase(t);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean staggered = false;
        if (phase.is(Phase.WILD_ROSE) && roses != 0 && behind(source)) {
            amount *= 2;                                                               // a rose on his back takes double, once each
            staggered = true;
        }
        boolean hurt = super.hurtServer(level, source, amount);
        if (!hurt) return false;
        if (staggered) {
            roses &= roses - 1;                                                        // that rose is gone
            level.sendParticles(ROSE, getX(), getY() + getBbHeight() * 0.8, getZ(), 30, 0.5, 0.4, 0.5, 0.1);
            playSound(SoundEvents.SWEET_BERRY_BUSH_BREAK, 2F, 1.4F);
            if (act() == Act.CHARGE) charging(null, 0);
            tangle(ROSE_STAGGER);
            move = Moves.STAGGER;
        }
        if (phase.isAny(Phase.THORNCOURT, Phase.WILD_ROSE) && source.getDirectEntity() instanceof LivingEntity a && a != this && distanceTo(a) < 4.0)
            a.hurtServer(level, damageSources().thorns(this), THORNS);                // his thorns prick his strikers
        return true;
    }

    /** Whether a blow came at his back (the roses there). */
    private boolean behind(DamageSource source) {
        Vec3 from = source.getSourcePosition();
        if (from == null) return false;
        Vec3 to = from.subtract(position()).multiply(1, 0, 1);
        if (to.lengthSqr() < 1.0E-4) return false;
        Vec3 facing = Vec3.directionFromRotation(0, yBodyRot);
        return to.normalize().dot(facing) < -0.3;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        phase.save(out, "phase");
        out.putInt("Roses", roses);
        out.putBoolean("Grubs", grubs);
        out.putInt("KingCooldown", chargeCooldown);
        out.store("WallA", BlockPos.CODEC.listOf(), wallA);
        out.store("WallB", BlockPos.CODEC.listOf(), wallB);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        phase.load(in, "phase");
        roses = in.getIntOr("Roses", 0b111);
        grubs = in.getBooleanOr("Grubs", false);
        chargeCooldown = in.getIntOr("KingCooldown", 0);
        wallA.clear();
        wallB.clear();
        wallA.addAll(in.read("WallA", BlockPos.CODEC.listOf()).orElse(List.of()));
        wallB.addAll(in.read("WallB", BlockPos.CODEC.listOf()).orElse(List.of()));
    }
}
