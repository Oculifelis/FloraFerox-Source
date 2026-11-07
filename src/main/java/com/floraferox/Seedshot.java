package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Every shot the 1.1 weapons fire (docs/DESIGN.md, section 7): a Cleaver's arc, a Sceptre's mote, a gun's
 * Seed Pellet. Drawn as its sprite item (VerdureItems.SHOTS); it deals its class's damage type (VerdureDamage), and its
 * strain's effect: the burning strains set their mark alight, the frozen ones slow and chill it. 1.8: a seed gun's shot
 * keeps its gun (Weedkiller's bonus), and may be a Bloom Shot (bigger, bursting) or a Heartmend shot (heals allies only).
 */
public class Seedshot extends ThrowableItemProjectile {
    public static final int PLAIN = 0, BLOOM = 1, MEND = 2;
    private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(Seedshot.class, EntityDataSerializers.BYTE);
    private ItemStack weapon = ItemStack.EMPTY;
    private DamageClass cls = DamageClass.RANGED;
    private float damage = 4F;
    private int tier = 1;
    private int life = 40;
    private double gravity = 0.0;
    private boolean homing;
    /** 1.3: a Burr Pellet's hooks slow what it hits. */
    private boolean clinging;

    public Seedshot(EntityType<? extends Seedshot> type, Level level) {
        super(type, level);
    }

    public Seedshot(Level level, LivingEntity owner, Item sprite, DamageClass cls, float damage, int tier, int life, double gravity) {
        super(VerdureEntities.SHOT.get(), owner, level, new ItemStack(sprite));
        this.cls = cls;
        this.damage = damage;
        this.tier = tier;
        this.life = life;
        this.gravity = gravity;
    }

    public Seedshot clinging() {
        clinging = true;
        return this;
    }

    public Seedshot homing() {
        homing = true;
        return this;
    }

    /** 1.16: a gun's shot is seed magic (VerdureDamage.SEED_BOLT); every other shot deals its class's type. */
    private DamageSource source(ServerLevel sl, Entity owner) {
        return weapon.getItem() instanceof VerdureWeapons.BloomGun ? VerdureDamage.source(sl, VerdureDamage.SEED_BOLT, this, owner)
                : VerdureDamage.source(sl, cls, this, owner);
    }

    /** 1.8: the gun that fired it, whose enchantments (Weedkiller) shape the damage. */
    public Seedshot weapon(ItemStack stack) {
        weapon = stack.copy();
        return this;
    }

    /** 1.8: {@link #BLOOM} or {@link #MEND}; synced, so the renderer draws it. */
    public Seedshot mode(int mode) {
        entityData.set(MODE, (byte) mode);
        return this;
    }

    public int mode() {
        return entityData.get(MODE);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MODE, (byte) PLAIN);
    }

    @Override
    public ItemStack getWeaponItem() {
        return weapon;
    }

    /** Heartmend: someone the shooter would heal (its team, its own tamed or owned mobs, #floraferox:heal_targets). */
    public boolean ally(Entity e) {
        Entity owner = getOwner();
        if (owner == null || e == owner || !(e instanceof LivingEntity)) return false;
        return e.isAlliedTo(owner) || (e instanceof OwnableEntity o && o.getOwner() == owner) || e.getType().builtInRegistryHolder().is(GunEnchants.HEAL_TARGETS);
    }

    @Override
    protected boolean canHitEntity(Entity e) {
        return super.canHitEntity(e) && (mode() != MEND || ally(e));
    }

    @Override
    protected Item getDefaultItem() {
        return VerdureItems.SEED_PELLET.get();
    }

    @Override
    protected double getDefaultGravity() {
        return gravity;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            if (tier == 2) level().addParticle(ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 0, 0, 0);
            if (tier == 3) level().addParticle(ParticleTypes.SNOWFLAKE, getX(), getY(), getZ(), 0, 0, 0);
            return;
        }
        if (mode() == MEND && tickCount > 2) {                 // a Heartmend shot seeks the nearest ally ahead
            Vec3 v = getDeltaMovement();
            LivingEntity best = null;
            double bestD = 144;
            for (LivingEntity m : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(12), m -> m.isAlive() && ally(m))) {
                Vec3 to = m.getBoundingBox().getCenter().subtract(position());
                double d = to.lengthSqr();
                if (d < bestD && to.normalize().dot(v.normalize()) > 0.2) {
                    best = m;
                    bestD = d;
                }
            }
            if (best != null) {
                Vec3 want = best.getBoundingBox().getCenter().subtract(position()).normalize().scale(v.length());
                setDeltaMovement(v.scale(0.7).add(want.scale(0.3)));
            }
        }
        if (homing && tickCount > 3) {                        // the Night Petal leans toward the nearest monster ahead
            Vec3 v = getDeltaMovement();
            Mob best = null;
            double bestD = 64;
            for (Mob m : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(8), m -> m instanceof Enemy && m.isAlive())) {
                Vec3 to = m.getBoundingBox().getCenter().subtract(position());
                double d = to.lengthSqr();
                if (d < bestD && to.normalize().dot(v.normalize()) > 0.5) {
                    best = m;
                    bestD = d;
                }
            }
            if (best != null) {
                Vec3 want = best.getBoundingBox().getCenter().subtract(position()).normalize().scale(v.length());
                setDeltaMovement(v.scale(0.85).add(want.scale(0.15)));
            }
        }
        if (--life <= 0) discard();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        Entity e = hit.getEntity();
        if (!(level() instanceof ServerLevel sl) || e == getOwner() || !(e instanceof LivingEntity target)) return;
        if (mode() == MEND) {                                // Heartmend never hurts: it heals an ally
            if (ally(target)) {
                target.heal(damage * GunEnchants.MEND_SHARE);
                sl.sendParticles(ParticleTypes.HEART, target.getX(), target.getY(0.6), target.getZ(), 3, 0.3, 0.3, 0.3, 0.0);
            }
            return;
        }
        DamageSource src = source(sl, getOwner());
        float dmg = weapon.isEmpty() ? damage : EnchantmentHelper.modifyDamage(sl, weapon, target, src, damage);
        if (target.hurtServer(sl, src, dmg)) {
            strain(target, tier);
            if (clinging) target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, VerdureRules.CHARM_SLOW_TICKS, 0), getOwner() instanceof LivingEntity o ? o : null);
        }
    }

    /** The strain's mark: burning (second tier) or frozen (third). */
    public static void strain(LivingEntity target, int tier) {
        if (tier == 2) target.igniteForSeconds(4);
        if (tier == 3) {
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
            target.setTicksFrozen(Math.min(target.getTicksRequiredToFreeze() + 60, target.getTicksFrozen() + 100));
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel sl) {
            if (mode() == BLOOM) burst(sl, result instanceof EntityHitResult eh ? eh.getEntity() : null);
            sl.sendParticles(tier == 3 ? ParticleTypes.SNOWFLAKE : tier == 2 ? ParticleTypes.FLAME : ParticleTypes.COMPOSTER,
                    getX(), getY(), getZ(), 8, 0.2, 0.2, 0.2, 0.02);
            discard();
        }
    }

    /**
     * A Bloom Shot's burst where it lands, by gun: the Pulse Pistol's ring of petals hurts what stands round it, the
     * Scorch Spitter's ring of flame sets it alight, the Rime Repeater's frost nova slows and chills it.
     */
    private void burst(ServerLevel sl, Entity hit) {
        double r = GunEnchants.BLOOM_RADIUS;
        Entity owner = getOwner();
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            double x = getX() + Math.cos(a) * r * 0.8, z = getZ() + Math.sin(a) * r * 0.8;
            if (tier == 1) sl.sendParticles(new DustParticleOptions(0xFF5AB4, 1.6F), x, getY(), z, 2, 0.1, 0.1, 0.1, 0.0);
            else if (tier == 2) sl.sendParticles(ParticleTypes.FLAME, x, getY(), z, 2, 0.05, 0.1, 0.05, 0.01);
            else sl.sendParticles(ParticleTypes.SNOWFLAKE, x, getY(), z, 2, 0.1, 0.1, 0.1, 0.02);
        }
        if (tier == 1) sl.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY(), getZ(), 20, r * 0.4, 0.3, r * 0.4, 0.0);
        for (LivingEntity m : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r), m -> m.isAlive() && m != owner && m != hit
                && !(owner != null && m.isAlliedTo(owner)) && m.distanceToSqr(this) <= r * r)) {
            if (tier == 1) m.hurtServer(sl, source(sl, owner), damage * 0.5F);
            else if (tier == 2) m.igniteForSeconds(4);
            else {
                m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1), owner instanceof LivingEntity o ? o : null);
                m.setTicksFrozen(Math.min(m.getTicksRequiredToFreeze() + 60, m.getTicksFrozen() + 120));
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        life = 0;                                             // a shot from a saved world just fades
    }
}
