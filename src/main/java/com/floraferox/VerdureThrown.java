package com.floraferox;

import com.fundamentum.element.ElementAuras;
import com.fundamentum.thrown.ThrownWeapon;
import com.fundamentum.thrown.ThrownWeapons;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 1.20's plant throwables, on Fundamentum's thrown weapons (stats in data/floraferox/fundamentum/thrown/):
 * the Runed Seed (a grenade: an arcane rune ring that throws foes back and slows them), the Spore Flask (a molotov:
 * fire and a lingering spore cloud), the Leafrang (a boomerang: plain {@link ThrownWeapons#THROWN_WEAPON}, flown by
 * its stats) and the Thorn Javelin (sticks; a hit roots the target and seeds spores in it).
 */
public final class VerdureThrown {
    private VerdureThrown() { }

    public static final double RUNE_RADIUS = 3.5;
    public static final double RUNE_PUSH = 0.9;
    public static final int RUNE_SLOW_TICKS = 60;
    public static final double FLASK_RADIUS = 2.5;
    public static final int FLASK_BURN_SECONDS = 4;
    public static final int FLASK_CLOUD_TICKS = 100;
    public static final int SNARE_TICKS = 40;
    /** Ticks between grenade or flask throws (Fundamentum sets none). */
    public static final int THROW_COOLDOWN = 20;

    /** use(): throws one as {@code make}, then a cooldown when {@code cooldown} > 0. */
    public static class ThrowItem extends Item {
        private final BiFunction<Level, ItemStack, ? extends ThrownWeapon> make;
        private final int cooldown;

        public ThrowItem(Properties p, BiFunction<Level, ItemStack, ? extends ThrownWeapon> make, int cooldown) {
            super(p);
            this.make = make;
            this.cooldown = cooldown;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack held = player.getItemInHand(hand);
            Item item = held.getItem();
            InteractionResult r = ThrownWeapons.throwFrom(level, player, hand, make);
            if (cooldown > 0 && !level.isClientSide()) player.getCooldowns().addCooldown(new ItemStack(item), cooldown);
            return r;
        }
    }

    /** Living things near {@code at} worth hitting: alive, not the thrower, not on our side of the truce. */
    static Iterable<LivingEntity> near(ServerLevel level, Entity at, double radius, Entity owner) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(at.position(), at.position()).inflate(radius, 2, radius),
            e -> e.isAlive() && e != owner && e.distanceToSqr(at) <= radius * radius);
    }

    /** The grenade: a ring of runes bursts out, shoving everything near away and slowing it. */
    public static class RunedSeed extends ThrownWeapon {
        public RunedSeed(EntityType<? extends ThrownWeapon> type, Level level) {
            super(type, level);
        }

        @Override
        protected void onBreak(ServerLevel level) {
            super.onBreak(level);
            Entity owner = getOwner();
            for (LivingEntity e : near(level, this, RUNE_RADIUS, owner)) {
                double dx = e.getX() - getX(), dz = e.getZ() - getZ();
                double d = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
                double push = RUNE_PUSH * (1.0 - 0.5 * d / RUNE_RADIUS);
                e.knockback(push, -dx / d, -dz / d);
                e.hurtMarked = true;
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, RUNE_SLOW_TICKS, 1), owner);
            }
            for (int i = 0; i < 28; i++) {                       // the rune ring, flat at the burst's height
                double a = i * Math.PI * 2 / 28;
                level.sendParticles(ParticleTypes.ENCHANT, getX() + Math.cos(a) * RUNE_RADIUS, getY() + 0.2,
                    getZ() + Math.sin(a) * RUNE_RADIUS, 2, 0.05, 0.1, 0.05, 0.4);
            }
            level.sendParticles(ParticleTypes.WITCH, getX(), getY() + 0.3, getZ(), 24, 0.6, 0.3, 0.6, 0.05);
            level.playSound(null, blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.9F, 1.3F);
        }
    }

    /** The molotov: bursts into fire, sets things alight, seeds spores in them and leaves a spore cloud. */
    public static class SporeFlask extends ThrownWeapon {
        public SporeFlask(EntityType<? extends ThrownWeapon> type, Level level) {
            super(type, level);
        }

        @Override
        protected void onBreak(ServerLevel level) {
            super.onBreak(level);
            Entity owner = getOwner();
            for (LivingEntity e : near(level, this, FLASK_RADIUS, owner)) {
                e.igniteForSeconds(FLASK_BURN_SECONDS);
                ElementAuras.apply(e, VerdureElements.SPORE, VerdureElements.SPORE_AURA,
                    VerdureDamage.source(level, VerdureElements.type("spore"), this, owner == null ? this : owner));
            }
            BlockPos c = blockPosition();                        // a few tongues of fire where they can stand
            BlockState fire = Blocks.FIRE.defaultBlockState();
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
                if (level.getBlockState(p).isAir() && fire.canSurvive(level, p) && random.nextFloat() < 0.5F) {
                    level.setBlockAndUpdate(p, fire);
                }
            }
            AreaEffectCloud cloud = new AreaEffectCloud(level, getX(), getY(), getZ());
            if (owner instanceof LivingEntity lo) cloud.setOwner(lo);
            cloud.setRadius((float) FLASK_RADIUS);
            cloud.setRadiusPerTick(-0.01F);
            cloud.setDuration(FLASK_CLOUD_TICKS);
            cloud.setWaitTime(10);
            cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
            level.addFreshEntity(cloud);
            level.sendParticles(ParticleTypes.FLAME, getX(), getY() + 0.2, getZ(), 30, 0.8, 0.3, 0.8, 0.04);
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + 0.5, getZ(), 40, 1.2, 0.6, 1.2, 0.0);
            level.playSound(null, blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.9F, 0.9F);
        }
    }

    /** The javelin: a hit roots the target (Slowness IV) and seeds spores in it; it sticks where it lands. */
    public static class ThornJavelin extends ThrownWeapon {
        public ThornJavelin(EntityType<? extends ThrownWeapon> type, Level level) {
            super(type, level);
        }

        @Override
        protected void onStrike(ServerLevel level, Entity target, boolean hurt) {
            if (!hurt || !(target instanceof LivingEntity e)) return;
            Entity owner = getOwner();
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SNARE_TICKS, 3), owner);
            ElementAuras.apply(e, VerdureElements.SPORE, VerdureElements.SPORE_AURA,
                VerdureDamage.source(level, VerdureElements.type("spore"), this, owner == null ? this : owner));
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()),
                e.getX(), e.getY() + 0.1, e.getZ(), 14, 0.4, 0.1, 0.4, 0.1);
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(), 10, 0.3, 0.4, 0.3, 0.0);
        }

        @Override
        protected void onStick(ServerLevel level, BlockHitResult result) {
            super.onStick(level, result);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.HANGING_ROOTS.defaultBlockState()),
                getX(), getY(), getZ(), 6, 0.1, 0.1, 0.1, 0.05);
        }
    }
}
