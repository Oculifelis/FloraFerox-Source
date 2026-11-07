package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.DamageClass;
import com.fundamentum.glow.Glows;
import com.fundamentum.whip.WhipCordFx;
import com.fundamentum.whip.WhipDefinition;
import com.fundamentum.whip.WhipHit;
import com.fundamentum.whip.WhipHooks;
import com.fundamentum.whip.Whips;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The 1.1 weapons (docs/DESIGN.md, section 7), one line per family and class, each in three strains. Terraria fires
 * them on a held button; here each fires on use with a short cooldown, as Minecraft's ranged tools do.
 */
public final class VerdureWeapons {
    private VerdureWeapons() { }

    private static String[] sprites(String a, String b, String c) {
        return new String[] {null, a, b, c};
    }

    private static final String[] SLASH = sprites("mire_arc", "flare_arc", "rime_arc");
    private static final String[] PETAL = sprites("dusk_mote", "ember_mote", "rime_mote");
    private static final String[] PIERCER = sprites("pulse_dart", "flare_dart", "rime_dart");

    private static void fire(Player player, ItemStack stack, Seedshot shot, float speed, SoundEvent sound, float pitch, int cooldown) {
        Level level = player.level();
        shot.shootFromRotation(player, player.getXRot(), player.getYRot(), 0F, speed, 0.5F);
        level.addFreshEntity(shot);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 0.8F, pitch);
        player.getCooldowns().addCooldown(stack, cooldown);
    }

    /** A Cleaver (melee): a sword; use sends a short slash flying. Each later strain marks what it cuts. */
    public static class BudBlade extends Item {
        private final int tier;

        public BudBlade(int tier, Properties properties) {
            super(properties);
            this.tier = tier;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide()) {
                fire(player, stack, new Seedshot(level, player, VerdureItems.SHOTS.get(SLASH[tier]).get(), DamageClass.MELEE, SLASH_DAMAGE[tier], tier, 8, 0.0),
                        1.3F, SoundEvents.PLAYER_ATTACK_SWEEP, 1.1F, Math.round(BLADE_COOLDOWN * VerdureGear.cooldownFactor(player, DamageClass.MELEE)));
                stack.hurtAndBreak(1, player, hand);
            }
            player.swing(hand);
            return InteractionResult.SUCCESS;
        }

        @Override
        public void postHurtEnemy(ItemStack stack, LivingEntity mob, LivingEntity attacker) {
            super.postHurtEnemy(stack, mob, attacker);
            Seedshot.strain(mob, tier);
        }
    }

    /**
     * A Sceptre (magic): a petal bolt; the Dusk Mote leans toward monsters. Wears with each cast. 1.12 (Oculifelis): left
     * click casts (GunTrigger), right click is the tier's support (SceptreSupport).
     */
    public static class PetalStaff extends Item {
        private final int tier;

        public PetalStaff(int tier, Properties properties) {
            super(properties);
            this.tier = tier;
        }

        public int tier() {
            return tier;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            return SceptreSupport.use(level, player, hand, tier);
        }

        /** One petal bolt from the sceptre in {@code hand}; callers check the cooldown. */
        public InteractionResult cast(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide()) {
                Seedshot petal = new Seedshot(level, player, VerdureItems.SHOTS.get(PETAL[tier]).get(), DamageClass.MAGIC, PETAL_DAMAGE[tier], tier, 30, 0.0);
                if (tier == 1) petal.homing();
                int cd = Math.round(STAFF_COOLDOWN * VerdureGear.cooldownFactor(player, DamageClass.MAGIC));
                fire(player, stack, petal, 1.5F, SoundEvents.EVOKER_CAST_SPELL, 1.6F, cd);
                stack.hurtAndBreak(1, player, hand);
            }
            player.swing(hand);
            return InteractionResult.SUCCESS;
        }
    }

    /**
     * A gun (magic since 1.16: its shots are seed magic): use fires a Seed Pellet or (1.3) a Burr Pellet, which slows, spent from the inventory (never in creative).
     * 1.8: its enchantments (GunEnchants) change the cooldown, the shot and what ammo is spent.
     */
    public static class BloomGun extends Item {
        private final int tier;

        public BloomGun(int tier, Properties properties) {
            super(properties);
            this.tier = tier;
        }

        public static ItemStack ammo(Player player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(VerdureItems.SEED_PELLET.get()) || s.is(VerdureItems.BURR_PELLET.get())) return s;
            }
            return ItemStack.EMPTY;
        }

        public int tier() {
            return tier;
        }

        /** Right click still fires; left click is the trigger (GunTrigger), as Quiverfall's guns. */
        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            return shoot(level, player, hand);
        }

        /** One shot from the gun in {@code hand}, if there are pellets (or Infinity); callers check the cooldown. */
        public InteractionResult shoot(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            boolean infinite = player.hasInfiniteMaterials() || GunEnchants.has(stack, GunEnchants.INFINITY);
            ItemStack ammo = ammo(player);
            if (ammo.isEmpty() && !infinite) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6F, 1.6F);
                return InteractionResult.FAIL;
            }
            if (!level.isClientSide()) {
                // 1.8: the gun's enchantments (GunEnchants)
                int quick = GunEnchants.level(stack, GunEnchants.QUICKDRAW);
                boolean bloom = GunEnchants.has(stack, GunEnchants.BLOOM_SHOT), mend = GunEnchants.has(stack, GunEnchants.HEARTMEND);
                float cdBase = GUN_COOLDOWN[tier] * VerdureGear.cooldownFactor(player, DamageClass.MAGIC) * (1F - GunEnchants.QUICKDRAW_COOLDOWN * quick);
                float speed = 3.0F * (1F + GunEnchants.QUICKDRAW_SPEED * quick);
                float damage = SEED_PELLET_DAMAGE[tier];
                int life = 40;
                if (bloom) {
                    speed *= GunEnchants.BLOOM_SPEED;
                    damage *= GunEnchants.BLOOM_DAMAGE;
                    life = 64;
                    cdBase *= 1.5F;
                }
                Seedshot shot = new Seedshot(level, player, VerdureItems.SHOTS.get(PIERCER[tier]).get(), DamageClass.MAGIC, damage, tier, life, 0.01).weapon(stack);
                if (bloom) shot.mode(Seedshot.BLOOM);
                if (mend) shot.mode(Seedshot.MEND);
                if (!ammo.isEmpty() && ammo.is(VerdureItems.BURR_PELLET.get())) shot.clinging();
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(player, new GunTrigger.Shot(player.getId()));
                fire(player, stack, shot, speed, bloom ? SoundEvents.FIRECHARGE_USE : SoundEvents.CROSSBOW_SHOOT, bloom ? 1.2F : 1.5F, Math.max(2, Math.round(cdBase)));
                boolean saved = GunEnchants.has(stack, GunEnchants.SEED_HOPPER) && player.getRandom().nextFloat() < GunEnchants.HOPPER_SAVE;
                if (!infinite && !saved) ammo.shrink(1);
                stack.hurtAndBreak(1, player, hand);
            }
            return InteractionResult.SUCCESS;
        }
    }

    /**
     * A Whip (summon and ranged since 1.16, never both at once), 1.6: a Fundamentum cord whip, swung with attack. Every lash deals summon damage, leaves Moon's
     * Spite (its next class wound cuts deeper) and the tier's strain, outlines the target in the whip's colour, and turns
     * every pet of yours nearby on it, as Terraria's whips direct minions. The tiers lash differently: the Moonwisp a
     * single cord that leans toward the hostile nearest the crosshair; the Moonflare two strands, each for its own
     * hostile, and a flaring crack; the Moonrime three strands in a wide cone, longer and harder, ringing frost. 1.22:
     * the Moonwisp's and Moonrime's cords glow full bright, the Moonflare's pulses with ember bands (Fundamentum cordFx).
     * Durability wears once per swing that hurts; the Orchid Charm shortens the cooldown.
     */
    public static class Whip extends Item {
        static final int[] COLOR = {0, 0x60C6EA, 0xFF8A3A, 0xBFEFFF};
        static final String[] IDS = {null, "moonwisp_whip", "moonflare_whip", "moonrime_whip"};
        /** Cord length per tier, about {@code LASH_RANGE}. */
        static final double[] LENGTH = {0, 5.5, 6.0, 6.5};
        /** Glow on a lashed target, in ticks. */
        static final int GLOW_TICKS = 100;

        public Whip(Properties properties) {
            super(properties);
        }

        /** Registers the three cord whips with Fundamentum (by id, before the items exist). */
        public static void register() {
            for (int t = 1; t <= 3; t++) Whips.register(FloraFerox.id(IDS[t]), definition(t));
        }

        public static WhipDefinition definition(int tier) {
            int argb = 0xFF000000 | COLOR[tier];
            WhipDefinition.Builder b = WhipDefinition.builder(FloraFerox.id(IDS[tier]))
                    .tuning(t -> t.length(LENGTH[tier]).damage(LASH_DAMAGE[tier]).cooldownTicks(LASH_COOLDOWN))
                    .texture(FloraFerox.id("textures/entity/whip/" + IDS[tier] + ".png"))
                    .coiled()
                    .shockwave(0xC0000000 | COLOR[tier])
                    .summonTag(new WhipDefinition.SummonTag(SCORN_TICKS, tier + 1, 1.0, 0.05 * tier))
                    .damage((player, stack) -> VerdureDamage.source(player.level(), DamageClass.SUMMON, player, player))
                    .hooks(hooks(tier));
            return switch (tier) {
                case 1 -> b.homing(20).width(0.08F, 0.03F).cordFx(WhipCordFx.GLOW)
                        .sounds(SoundEvents.PLAYER_ATTACK_SWEEP, SoundEvents.AMETHYST_BLOCK_CHIME)
                        .crackParticle(ParticleTypes.GLOW).build();
                case 2 -> b.tuning(t -> t.strands(2).strandSpread(12).maxTargets(4)).homing(40).strandTargets().width(0.09F, 0.035F).cordFx(WhipCordFx.ENERGY)
                        .sounds(SoundEvents.PLAYER_ATTACK_SWEEP, SoundEvents.FIRECHARGE_USE)
                        .crackParticle(ParticleTypes.FLAME)
                        .particles((level, strand, segment, along, pos, speed, random) -> {
                            if (random.nextFloat() < 0.08F * (float) along) level.addParticle(ParticleTypes.SMALL_FLAME, pos.x, pos.y, pos.z, 0, 0.02, 0);
                        }).build();
                default -> b.tuning(t -> t.strands(3).strandSpread(14).maxTargets(6)).homing(55).strandTargets().width(0.10F, 0.04F).cordFx(WhipCordFx.GLOW)
                        .sounds(SoundEvents.PLAYER_ATTACK_SWEEP, SoundEvents.GLASS_BREAK)
                        .crackParticle(ParticleTypes.SNOWFLAKE)
                        .particles((level, strand, segment, along, pos, speed, random) -> {
                            if (random.nextFloat() < 0.06F * (float) along) level.addParticle(ParticleTypes.SNOWFLAKE, pos.x, pos.y, pos.z, 0, -0.02, 0);
                        }).build();
            };
        }

        /** What every lash does on top of Fundamentum's hit: the tier's mark on the target, and the pets set on it. */
        static WhipHooks hooks(int tier) {
            return new WhipHooks() {
                @Override
                public void swingStart(ServerPlayer player, ItemStack stack, WhipDefinition whip) {
                    float factor = VerdureGear.lashFactor(player);
                    if (factor != 1F) player.getCooldowns().addCooldown(stack, Math.max(1, Math.round(whip.tuning.cooldownTicks() * factor)));
                }

                @Override
                public void landed(WhipHit hit, boolean hurt) {
                    LivingEntity target = hit.living();
                    if (!hurt || target == null) return;
                    Seedshot.strain(target, tier);
                    target.addEffect(new MobEffectInstance(VerdurePotions.MOONS_SPITE, SCORN_TICKS, 0), hit.player);
                    Glows.apply(target, COLOR[tier], GLOW_TICKS);
                    AABB around = hit.player.getBoundingBox().inflate(LASH_COMMAND_RANGE);
                    for (Mob pet : hit.player.level().getEntitiesOfClass(Mob.class, around, m -> isMine(m, hit.player) && m.isAlive() && m != target)) {
                        pet.setTarget(target);
                    }
                }
            };
        }

        static boolean isMine(Entity e, Player player) {
            return e instanceof OwnableEntity o && o.getOwner() == player;
        }
    }
}
