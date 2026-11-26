package com.floraferox;

import static com.floraferox.VerdureRules.*;
import com.floraferox.VerdureElements.Attack;
import com.floraferox.VerdureRules.Wild;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the three summoned bosses of docs/DESIGN.md section 15 share (ThornMatriarch, BriarKing, CarrionBloom): the bar,
 * the home they were woken at and the ring raised round it, the leash that sends them home healed, party scaling, the
 * first-kill trophy and the slow death. Each runs its own phases on a BossStateMachine. A plain spawn (a GameTest, a
 * spawn egg) has no home and fights with no arena.
 */
public abstract class WildBoss extends WildMob {
    private static final Identifier PARTY = FloraFerox.id("boss_party");

    protected final ServerBossEvent bar;
    private final int ringRadius, leash;
    private final String trophyTag;
    /** The home it woke at, and its ring there (null and empty for a plain spawn). */
    protected BlockPos home;
    protected List<BlockPos> ring = new ArrayList<>();
    protected int alone, roared;
    /** Who the move under way has already hit (each once). */
    protected final Set<Integer> struck = new HashSet<>();

    protected WildBoss(EntityType<? extends Monster> type, Level level, Wild w, BossEvent.BossBarColor color, int ringRadius, int leash, int xp) {
        super(type, level, w);
        this.ringRadius = ringRadius;
        this.leash = leash;
        this.trophyTag = trophyTag(w);
        bar = new ServerBossEvent(Mth.createInsecureUUID(random), getDisplayName(), color, BossEvent.BossBarOverlay.NOTCHED_10);
        xpReward = xp;
    }

    // ---- what each boss says ----

    /** Its phase's own fight, each tick (the bar, the arena and the leash are done). */
    protected abstract void fight(ServerLevel level);

    /** Whether it is between fights just now (waking, calling or roaring, dying): it takes no harm. */
    protected abstract boolean immune();

    /** It died: the phase goes to its dying. */
    protected abstract void dying();

    /** What its ring is made of (regrown every RING_REGROW), or null for none. */
    protected abstract BlockState ringBlock();

    /** Whether a block standing in its ring (or any arena part) is still its own. */
    protected abstract boolean ours(BlockState s);

    /** The first-kill trophy. */
    protected abstract ItemStack trophy();

    /** Its home is ready again (it went back) or spent (it fell). */
    protected abstract void homeReady(ServerLevel level);

    protected abstract void homeSpent(ServerLevel level);

    /** Whether it goes home now besides the leash (the King at sunrise). */
    protected boolean leaves(ServerLevel level) { return false; }

    /** The particle it sheds (going home, dying). */
    protected abstract ParticleOptions dust();

    public BlockPos home() { return home; }

    public List<BlockPos> ring() { return ring; }

    public static String trophyTag(Wild w) { return "floraferox." + w.id + "_trophy"; }

    /** Wakes at its home: the ring rises round it and it keeps to it. */
    public void wakeAt(ServerLevel level, BlockPos at) {
        home = at.immutable();
        setHomeTo(home, ringRadius - 2);
        raise(level);
    }

    @Override
    protected boolean fights(ServerLevel level) {
        bar.setProgress(getHealth() / getMaxHealth());
        if (home != null) arena(level);
        if (isRemoved()) return true;
        fight(level);
        return true;
    }

    // ---- the arena ----

    /** Raises its ring (one block high, round its home); no block where it could not stand. */
    protected void raise(ServerLevel level) {
        BlockState s = ringBlock();
        ring.clear();
        if (s == null) return;
        int steps = (int) (Math.PI * 2 * ringRadius * 1.5);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2 * i / steps;
            BlockPos col = home.offset((int) Math.round(Math.cos(a) * ringRadius), 0, (int) Math.round(Math.sin(a) * ringRadius));
            if (!seen.add(BlockPos.asLong(col.getX(), 0, col.getZ()))) continue;
            for (int y = 0, h = ringHeight(); y < h; y++) {
                BlockPos p = floor(level, col).above(y);
                if (s.canSurvive(level, p) && put(level, p, s)) ring.add(p);
            }
        }
    }

    protected int ringHeight() { return 1; }

    /** The arena each tick: the ring regrows, the leash counts down while no one is near. */
    private void arena(ServerLevel level) {
        BlockState s = ringBlock();
        if (s != null && tickCount % RING_REGROW == 0) for (BlockPos p : ring) if (open(level, p)) level.setBlock(p, s, Block.UPDATE_ALL);
        Vec3 c = Vec3.atBottomCenterOf(home);
        boolean near = level.players().stream().anyMatch(p -> !p.isSpectator() && p.distanceToSqr(c) <= leash * leash);
        alone = near ? 0 : alone + 1;
        if ((alone >= LEASH_TICKS || leaves(level)) && !dead()) goHome(level);
    }

    /** No one came back (or the sun rose): it goes home, healed, and the ring falls; it can be woken there again. */
    public void goHome(ServerLevel level) {
        fall(level);
        homeReady(level);
        level.sendParticles(dust(), getX(), getY() + 1, getZ(), 80, 2, 1, 2, 0.05);
        discard();
    }

    /** The ring (and whatever else it raised) goes. */
    protected void fall(ServerLevel level) {
        for (BlockPos p : ring) if (ours(level.getBlockState(p))) level.removeBlock(p, false);
        ring.clear();
    }

    private boolean dead() { return isDeadOrDying(); }

    /** The first open block over the ground near col's height. */
    protected static BlockPos floor(ServerLevel level, BlockPos col) {
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos p = col.above(dy);
            if (open(level, p) && !open(level, p.below())) return p;
        }
        return col;
    }

    protected static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.canBeReplaced();
    }

    protected static boolean put(ServerLevel level, BlockPos p, BlockState s) {
        if (!open(level, p)) return false;
        level.setBlock(p, s, Block.UPDATE_ALL);
        return true;
    }

    // ---- shared moves ----

    /** Party scaling at the end of its wake: +EXTRA_PLAYER_HEALTH health for each player near past the first. */
    protected void party(ServerLevel level) {
        Vec3 c = home != null ? Vec3.atBottomCenterOf(home) : position();
        long players = level.players().stream().filter(p -> !p.isSpectator() && p.distanceToSqr(c) <= ringRadius * ringRadius).count();
        AttributeInstance health = getAttribute(Attributes.MAX_HEALTH);
        if (health == null) return;
        health.removeModifier(PARTY);
        if (players > 1)
            health.addPermanentModifier(new AttributeModifier(PARTY, EXTRA_PLAYER_HEALTH * (players - 1), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        setHealth(getMaxHealth());
    }

    /** A roar or shriek: it throws back whoever is near (no damage). */
    protected void roar(ServerLevel level, SoundEvent sound, double strength) {
        playSound(sound, 3F, 0.6F);
        level.sendParticles(dust(), getX(), getY() + 2, getZ(), 80, 3, 1.5, 3, 0.1);
        for (LivingEntity e : foes(level, getBoundingBox().inflate(6))) {
            e.knockback(strength, getX() - e.getX(), getZ() - e.getZ());
            e.hurtMarked = true;
        }
    }

    /** Its kind's helpers, summoned round it at its target. */
    protected void call(ServerLevel level, Wild w, int n, double spread) {
        for (int i = 0; i < n; i++) {
            Vec3 at = position().add((random.nextDouble() - 0.5) * spread, 0, (random.nextDouble() - 0.5) * spread);
            WildMob m = VerdureEntities.wild(w).spawn(level, BlockPos.containing(ground(level, at)), EntitySpawnReason.MOB_SUMMONED);
            if (m != null && getTarget() != null) m.setTarget(getTarget());
        }
    }

    /** One blow of attack, a share of its attack damage. */
    protected boolean hit(ServerLevel level, LivingEntity e, Attack attack, double share) {
        boolean[] hurt = new boolean[1];
        as(attack, () -> hurt[0] = e.hurtServer(level, damageSources().mobAttack(this), (float) (getAttributeValue(Attributes.ATTACK_DAMAGE) * share)));
        if (hurt[0]) strikes++;
        return hurt[0];
    }

    /** A shockwave round at: hits and throws back whoever is on the ground in it, each once. */
    protected void shockwave(ServerLevel level, Vec3 at, double radius, Attack attack, double share) {
        circle(level, at, radius, (int) (radius * 6), ParticleTypes.EXPLOSION);
        for (LivingEntity e : foes(level, new AABB(at, at).inflate(radius, 2, radius)))
            if (e.distanceToSqr(at.x, e.getY(), at.z) <= radius * radius && struck.add(e.getId()) && hit(level, e, attack, share)) {
                e.knockback(SLAM_KNOCKBACK, at.x - e.getX(), at.z - e.getZ());
                e.hurtMarked = true;
            }
    }

    /** One of its shots (a thorn, a petal, a log): a Seedshot of a share of its attack, from from along dir. */
    protected Seedshot fire(ServerLevel level, Vec3 from, Vec3 dir, String sprite, double share, double speed, double gravity) {
        Seedshot s = new Seedshot(level, this, VerdureItems.SHOTS.get(sprite).get(), VerdureRules.DamageClass.RANGED,
                (float) (getAttributeValue(Attributes.ATTACK_DAMAGE) * share), 1, 60, gravity);
        s.setPos(from);
        s.shoot(dir.x, dir.y, dir.z, (float) speed, 1.0F);
        level.addFreshEntity(s);
        shots++;
        return s;
    }

    /** Who its blows hit: living things but itself and its own kind's helpers, and players out of the fight. */
    protected List<LivingEntity> foes(ServerLevel level, AABB box) {
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e != this && e.isAlive() && !(e instanceof WildMob w && helper(w))
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e));
    }

    /** Whether a wild mob is one of its helpers (spared by its blows). */
    protected boolean helper(WildMob w) { return false; }

    protected Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos p = floor(level, BlockPos.containing(at.x, getY(), at.z));
        return new Vec3(at.x, p.getY(), at.z);
    }

    protected static void circle(ServerLevel level, Vec3 c, double r, int n, ParticleOptions particle) {
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.sendParticles(particle, c.x + Math.cos(a) * r, c.y + 0.15, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    /** A line of particles from a to b (the tells). */
    protected static void line(ServerLevel level, Vec3 a, Vec3 b, ParticleOptions particle) {
        double len = a.distanceTo(b);
        for (double d = 0; d <= len; d += 0.75) {
            Vec3 p = a.lerp(b, len < 1.0E-4 ? 0 : d / len);
            level.sendParticles(particle, p.x, p.y + 0.15, p.z, 1, 0.1, 0, 0.1, 0);
        }
    }

    protected Vec3 flatTo(Vec3 to) {
        Vec3 d = to.subtract(position()).multiply(1, 0, 1);
        return d.lengthSqr() < 1.0E-4 ? getLookAngle().multiply(1, 0, 1).normalize() : d.normalize();
    }

    protected void face(Vec3 dir) {
        setYRot((float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90F);
        yBodyRot = getYRot();
    }

    // ---- vanilla hooks ----

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (immune() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return super.hurtServer(level, source, amount);
    }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!(level() instanceof ServerLevel level)) return;
        dying();
        bar.setProgress(0F);
        trophies(level);
        if (home != null) {
            fall(level);
            homeSpent(level);
        }
    }

    /** The first kill for each player in the fight gives them its trophy. */
    private void trophies(ServerLevel level) {
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(leash), p -> !p.isSpectator())) award(level, p);
    }

    /** Its trophy for p, dropped for p alone; null if p has had one already. */
    ItemEntity award(ServerLevel level, ServerPlayer p) {
        if (!p.addTag(trophyTag)) return null;
        ItemEntity t = new ItemEntity(level, getX(), getY() + 1, getZ(), trophy());
        t.setTarget(p.getUUID());
        level.addFreshEntity(t);
        return t;
    }

    /** It falls and fades for DYING_TICKS. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (level() instanceof ServerLevel level && deathTime % 5 == 0)
            level.sendParticles(dust(), getX(), getY() + 1, getZ(), 12, 1, 0.8, 1, 0.02);
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

    /** Never a natural spawn: it is only summoned. */
    @Override
    public boolean checkSpawnRules(net.minecraft.world.level.LevelAccessor level, EntitySpawnReason reason) {
        return reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION && super.checkSpawnRules(level, reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("Roared", roared);
        out.putInt("Alone", alone);
        if (home != null) {
            out.putLong("Home", home.asLong());
            out.store("Ring", BlockPos.CODEC.listOf(), ring);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        roared = in.getIntOr("Roared", 0);
        alone = in.getIntOr("Alone", 0);
        home = in.getLong("Home").map(BlockPos::of).orElse(null);
        ring = new ArrayList<>(in.read("Ring", BlockPos.CODEC.listOf()).orElse(List.of()));
        if (home != null) setHomeTo(home, ringRadius - 2);
        if (hasCustomName()) bar.setName(getDisplayName());
    }

    @Override
    public void setCustomName(net.minecraft.network.chat.Component name) {
        super.setCustomName(name);
        if (bar != null) bar.setName(getDisplayName());
    }
}
