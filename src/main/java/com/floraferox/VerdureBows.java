package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 1.16 (Oculifelis: the guns deal magic now, so the Pulse family gets real ranged weapons): the bows and crossbows, in
 * three strains each. They fire ordinary arrows, so their hits are ranged (VerdureDamage). A bow's full draw leaves a
 * heart seed in what it hits that bursts a moment later, hurting what stands round it (the burning strain's sets them
 * alight, the frozen one's slows and chills them); a crossbow's bolt pierces one mob and roots the first it hits. The
 * third-tier ranged set winds every bow and crossbow 20% faster (VerdureGear.cooldownFactor).
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureBows {
    private VerdureBows() { }

    static final String SEED = "floraferox:heart_seed", ROOT = "floraferox:root_bolt", ROOTED = "floraferox:rooted";
    public static final int SEED_DELAY = 15;                        // after the arrow's own hit's invulnerable ticks
    public static final double SEED_RADIUS = 2.5;
    public static final float[] SEED_DAMAGE = {0F, 4F, 5F, 6F};
    public static final int[] ROOT_TICKS = {0, 40, 50, 60};

    public static class HeartBow extends BowItem {
        public final int tier;

        public HeartBow(int tier, Properties p) {
            super(p);
            this.tier = tier;
        }

        /** Only a full draw (vanilla's critical arrow) carries the seed. */
        @Override
        public AbstractArrow customArrow(AbstractArrow arrow, ItemStack projectile, ItemStack weapon) {
            if (arrow.isCritArrow()) arrow.getPersistentData().putInt(SEED, tier);
            return arrow;
        }
    }

    public static class Arbalest extends CrossbowItem {
        public final int tier;

        public Arbalest(int tier, Properties p) {
            super(p);
            this.tier = tier;
        }

        @Override
        public AbstractArrow customArrow(AbstractArrow arrow, ItemStack projectile, ItemStack weapon) {
            if (arrow.getPierceLevel() < 1) pierce(arrow, 1);
            arrow.getPersistentData().putInt(ROOT, tier);
            return arrow;
        }
    }

    // AbstractArrow keeps its pierce setter private (enchantments set it from the weapon); 26.1 ships unobfuscated.
    private static final Method SET_PIERCE;

    static {
        try {
            SET_PIERCE = AbstractArrow.class.getDeclaredMethod("setPierceLevel", byte.class);
            SET_PIERCE.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void pierce(AbstractArrow arrow, int level) {
        try {
            SET_PIERCE.invoke(arrow, (byte) level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Seed(ServerLevel level, LivingEntity target, Entity owner, int tier, long at, Vec3[] last) { }

    private static final List<Seed> SEEDS = new ArrayList<>();

    /** A heart seed's arrow or a root bolt hurt something: plant the seed, or root the first mob the bolt meets. */
    @SubscribeEvent
    public static void hurt(LivingDamageEvent.Post e) {
        if (!(e.getSource().getDirectEntity() instanceof AbstractArrow arrow) || !(e.getEntity().level() instanceof ServerLevel sl)) return;
        LivingEntity target = e.getEntity();
        var tag = arrow.getPersistentData();
        int seed = tag.getIntOr(SEED, 0), root = tag.getIntOr(ROOT, 0);
        if (seed > 0) {
            tag.remove(SEED);
            SEEDS.add(new Seed(sl, target, arrow.getOwner(), seed, sl.getGameTime() + SEED_DELAY, new Vec3[] {target.position()}));
        }
        if (root > 0 && !tag.getBooleanOr(ROOTED, false)) {
            tag.putBoolean(ROOTED, true);
            root(target, root, arrow.getOwner());
        }
    }

    /** Held fast for a moment: slowed past walking (vanilla's Slowness X), and the strain's mark. */
    public static void root(LivingEntity target, int tier, Entity owner) {
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ROOT_TICKS[tier], 9), owner);
        Seedshot.strain(target, tier);
        if (target.level() instanceof ServerLevel sl)
            sl.sendParticles(ParticleTypes.COMPOSTER, target.getX(), target.getY(0.1), target.getZ(), 12, 0.4, 0.05, 0.4, 0.0);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        if (SEEDS.isEmpty()) return;
        SEEDS.removeIf(s -> {
            if (s.target.isAlive()) s.last[0] = s.target.position();
            if (s.level.getGameTime() < s.at) return false;
            burst(s.level, s.last[0].add(0, s.target.getBbHeight() * 0.5, 0), s.tier, s.owner);
            return true;
        });
    }

    /** The seed bursts: a ring of petals (flame, frost) hurts what stands round it, and the strain marks them. */
    public static void burst(ServerLevel sl, Vec3 at, int tier, Entity owner) {
        double r = SEED_RADIUS;
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI * 2 / 20, x = at.x + Math.cos(a) * r * 0.7, z = at.z + Math.sin(a) * r * 0.7;
            if (tier == 2) sl.sendParticles(ParticleTypes.FLAME, x, at.y, z, 2, 0.05, 0.1, 0.05, 0.01);
            else if (tier == 3) sl.sendParticles(ParticleTypes.SNOWFLAKE, x, at.y, z, 2, 0.1, 0.1, 0.1, 0.02);
            else sl.sendParticles(new DustParticleOptions(0xFF5AB4, 1.4F), x, at.y, z, 2, 0.1, 0.1, 0.1, 0.0);
        }
        sl.sendParticles(ParticleTypes.HEART, at.x, at.y, at.z, 4, 0.3, 0.3, 0.3, 0.0);
        for (LivingEntity m : sl.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(r),
                m -> m.isAlive() && m != owner && !(owner != null && m.isAlliedTo(owner)) && m.distanceToSqr(at) <= r * r)) {
            if (m.hurtServer(sl, VerdureDamage.source(sl, DamageClass.RANGED, null, owner), SEED_DAMAGE[tier])) Seedshot.strain(m, tier);
        }
    }

    /** Pending seeds, for the tests. */
    static int pendingSeeds() {
        return SEEDS.size();
    }

    /**
     * The third-tier ranged set: every bow and crossbow winds 20% faster. Each tick of use counts one tick on, and one
     * in every 1 / (1 - factor) is skipped, so the draw (and its pulling frames) runs that much quicker on both sides.
     */
    @SubscribeEvent
    public static void winding(LivingEntityUseItemEvent.Tick e) {
        if (!(e.getEntity() instanceof Player player) || !(e.getItem().getItem() instanceof ProjectileWeaponItem)) return;
        float f = VerdureGear.cooldownFactor(player, DamageClass.RANGED);
        if (f >= 1F) return;
        int every = Math.round(1F / (1F - f));
        if (e.getDuration() % every == 0) e.setDuration(e.getDuration() - 1);
    }
}
