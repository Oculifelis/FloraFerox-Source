package com.floraferox;

import static com.floraferox.VerdureRules.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 1.12, every class helmet's own set effect, on top of the class bonus the sets already give (VerdureGear). Oculifelis:
 * "more interesting set effects, but still have them vary by helmet. they should be thematic per tier and the helms
 * should enable varied playstyles", then (round 1) "far more interesting non class based effects". So none of these
 * touches a class's blows: Leafguard grows, Emberbark burns, Rimebark freezes, and each helmet plays its own way.
 * <ul>
 * <li>Leafguard: the Sallet's Sunbather (heals in daylight under the sky, faster standing still); the Armet's Pollen
 * Trail (sprinting leaves pollen that weakens and slows what follows); the Cowl's Rootbound (sneaking roots you: no
 * knockback, less damage); the Visor's Overgrowth (plants round you grow as if bonemealed).</li>
 * <li>Emberbark: the Sallet's Phoenix Bloom (once in a while a killing blow leaves you standing in a burst of fire); the
 * Armet's Cinderstep (sprinting leaves embers that set alight what follows); the Cowl's Smelter's Touch (what you mine
 * drops smelted); the Visor's Flashpoint (struck, you may flare and throw back what is near).</li>
 * <li>Rimebark: the Sallet's Frostwalk (water freezes under your feet); the Armet's Glacial Aegis (an ice shield that
 * turns one blow every so often); the Cowl's Hibernate (near death you are sealed in ice a moment, healing); the Visor's
 * Permafrost (hostiles and their shots slow near you).</li>
 * </ul>
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureSets {
    private VerdureSets() { }

    private static final String PHOENIX_READY = FloraFerox.MODID + ":phoenix_ready", AEGIS_READY = FloraFerox.MODID + ":aegis_ready",
            HIBERNATE_READY = FloraFerox.MODID + ":hibernate_ready", FLASH_READY = FloraFerox.MODID + ":flash_ready";
    private static final net.minecraft.resources.Identifier ROOTED = FloraFerox.id("rootbound");

    /** A sprinter's trail (Pollen Trail, Cinderstep): where they ran and when each spot fades. */
    record Spot(Vec3 at, long until) { }

    private static final Map<UUID, Deque<Spot>> TRAILS = new ConcurrentHashMap<>();

    static boolean ready(Player p, String key) {
        return p.level().getGameTime() >= p.getPersistentData().getLongOr(key, 0L);
    }

    static void spend(Player p, String key, int ticks) {
        p.getPersistentData().putLong(key, p.level().getGameTime() + ticks);
    }

    // ---- being hurt -------------------------------------------------------------------------------------------------
    /** After VerdureGear's own handler (fire proofing, class bonuses), so a cancelled blow does nothing here. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void incoming(LivingIncomingDamageEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel level)) return;
        Herb s = VerdureGear.set(p);
        if (s == null) return;
        float mult = hurt(p, s, e.getSource(), e.getAmount());
        if (mult <= 0F) e.setCanceled(true);
        else if (mult != 1F) e.setAmount(e.getAmount() * mult);
    }

    /** What the wearer's set does to a blow against them: a multiplier, 0 to turn it aside. */
    static float hurt(Player p, Herb s, DamageSource source, float amount) {
        ServerLevel level = (ServerLevel) p.level();
        switch (s) {
            case MOONWISP -> {
                if (p.isShiftKeyDown()) return ROOT_TAKEN;
            }
            case MIREFLARE -> {
                if (source.getEntity() instanceof LivingEntity && ready(p, FLASH_READY) && p.getRandom().nextFloat() < FLASH_CHANCE) {
                    spend(p, FLASH_READY, FLASH_COOLDOWN);
                    for (LivingEntity foe : near(p, FLASH_RADIUS)) {
                        foe.igniteForSeconds(3F);
                        Vec3 away = foe.position().subtract(p.position()).normalize();
                        foe.push(away.x * 1.1, 0.35, away.z * 1.1);
                        foe.hurtMarked = true;
                    }
                    burst(level, p, ParticleTypes.FLAME, 40, 1.6);
                    level.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY(0.5), p.getZ(), 1, 0, 0, 0, 0);
                    level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6F, 1.4F);
                }
            }
            case PULSERIME -> {
                if (ready(p, AEGIS_READY) && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                    spend(p, AEGIS_READY, AEGIS_COOLDOWN);
                    burst(level, p, ParticleTypes.SNOWFLAKE, 24, 0.8);
                    level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.3F);
                    return 0F;
                }
            }
            case MOONRIME -> {
                if (p.getHealth() - amount < HIBERNATE_BELOW && ready(p, HIBERNATE_READY)) {
                    spend(p, HIBERNATE_READY, HIBERNATE_COOLDOWN);
                    p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, HIBERNATE_TICKS, 4));
                    p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, HIBERNATE_TICKS, 2));
                    p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, HIBERNATE_TICKS, 6));
                    p.setTicksFrozen(p.getTicksRequiredToFreeze());
                    burst(level, p, ParticleTypes.SNOWFLAKE, 40, 0.6);
                    level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.POWDER_SNOW_PLACE, SoundSource.PLAYERS, 1.0F, 0.7F);
                    return 0F;
                }
            }
            default -> { }
        }
        return 1F;
    }

    /** Emberbark Sallet: Phoenix Bloom, a killing blow turned aside now and then. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void death(LivingDeathEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel level)) return;
        if (VerdureGear.set(p) != Herb.DUSKFLARE || !ready(p, PHOENIX_READY)) return;
        e.setCanceled(true);
        phoenix(p, level);
    }

    static void phoenix(Player p, ServerLevel level) {
        spend(p, PHOENIX_READY, PHOENIX_COOLDOWN);
        p.setHealth(PHOENIX_HEALTH);
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 0));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        for (LivingEntity foe : near(p, PHOENIX_RADIUS)) {
            foe.igniteForSeconds(5F);
            Vec3 away = foe.position().subtract(p.position()).normalize();
            foe.push(away.x * 1.4, 0.5, away.z * 1.4);
            foe.hurtMarked = true;
        }
        burst(level, p, ParticleTypes.FLAME, 80, 2.2);
        level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY(1.1), p.getZ(), 6, 0.6, 0.3, 0.6, 0);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.8F, 1.3F);
    }

    // ---- every few ticks --------------------------------------------------------------------------------------------
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level)) return;
        Herb s = VerdureGear.set(p);
        rootbound(p, s == Herb.MOONWISP && p.isShiftKeyDown());
        if (s == null) {
            TRAILS.remove(p.getUUID());
            return;
        }
        long now = level.getGameTime();
        switch (s) {
            case DUSKPETAL -> {
                if (p.tickCount % SUN_INTERVAL == 0 && level.isBrightOutside() && level.canSeeSky(p.blockPosition().above())) {
                    boolean still = p.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4;
                    p.heal(still ? SUN_HEAL_STILL : SUN_HEAL);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY(1.0), p.getZ(), still ? 3 : 1, 0.3, 0.3, 0.3, 0);
                }
            }
            case PULSEROOT, PULSEFLARE -> trail(p, level, s == Herb.PULSEFLARE, now);
            case MIRECAP -> {
                if (p.tickCount % GROW_INTERVAL == 0) overgrow(p, level);
            }
            case DUSKRIME -> {
                if (p.tickCount % 2 == 0 && p.onGround()) frostwalk(p, level);
            }
            case MIRERIME -> {
                if (p.tickCount % 5 == 0) permafrost(p, level);
            }
            default -> { }
        }
    }

    /** Leafguard Cowl: sneaking, you take no knockback. */
    static void rootbound(Player p, boolean on) {
        var kb = p.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb == null) return;
        boolean has = kb.getModifier(ROOTED) != null;
        if (on && !has) kb.addTransientModifier(new AttributeModifier(ROOTED, 1.0, AttributeModifier.Operation.ADD_VALUE));
        else if (!on && has) kb.removeModifier(ROOTED);
        if (on && p.tickCount % 10 == 0 && p.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.COMPOSTER, p.getX(), p.getY() + 0.1, p.getZ(), 3, 0.4, 0.05, 0.4, 0);
        }
    }

    /** Pollen Trail and Cinderstep: a sprinter leaves spots behind that mark the hostiles crossing them. */
    static void trail(Player p, ServerLevel level, boolean embers, long now) {
        Deque<Spot> spots = TRAILS.computeIfAbsent(p.getUUID(), u -> new ArrayDeque<>());
        while (!spots.isEmpty() && spots.peekFirst().until < now) spots.pollFirst();
        if (p.isSprinting() && p.onGround() && p.tickCount % 4 == 0) spots.addLast(new Spot(p.position(), now + TRAIL_TICKS));
        if (p.tickCount % 5 != 0 || spots.isEmpty()) return;
        ParticleOptions look = embers ? ParticleTypes.SMALL_FLAME : ParticleTypes.SPORE_BLOSSOM_AIR;
        for (Spot spot : spots) {
            level.sendParticles(look, spot.at.x, spot.at.y + 0.15, spot.at.z, 1, 0.25, 0.05, 0.25, 0);
            for (LivingEntity foe : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(spot.at, spot.at).inflate(TRAIL_RADIUS),
                    x -> x instanceof Enemy && x.isAlive())) {
                if (embers) foe.igniteForSeconds(3F);
                else {
                    foe.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1), p);
                    foe.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0), p);
                }
            }
        }
    }

    /** Leafguard Visor: a few plants round you grow a step, as if bonemealed. */
    static void overgrow(Player p, ServerLevel level) {
        BlockPos at = p.blockPosition();
        for (int i = 0; i < GROW_TRIES; i++) {
            BlockPos pos = at.offset(p.getRandom().nextInt(GROW_RADIUS * 2 + 1) - GROW_RADIUS, p.getRandom().nextInt(3) - 1,
                    p.getRandom().nextInt(GROW_RADIUS * 2 + 1) - GROW_RADIUS);
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)) continue;          // no meadows sprouting everywhere
            if (BoneMealItem.applyBonemeal(new ItemStack(Items.BONE_MEAL), level, pos, p)) {
                level.levelEvent(1505, pos, 3);                                                  // the bonemeal sparkle
            }
        }
    }

    /** Rimebark Sallet: still water round your feet freezes, as Frost Walker. */
    static void frostwalk(Player p, ServerLevel level) {
        BlockPos under = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(under.offset(-FROST_RADIUS, -1, -FROST_RADIUS), under.offset(FROST_RADIUS, -1, FROST_RADIUS))) {
            if (pos.distSqr(under.below()) > FROST_RADIUS * FROST_RADIUS + 1) continue;
            if (level.getFluidState(pos).is(Fluids.WATER) && level.getFluidState(pos).isSource() && level.getBlockState(pos.above()).isAir()) {
                level.setBlockAndUpdate(pos, Blocks.FROSTED_ICE.defaultBlockState());
                level.scheduleTick(pos.immutable(), Blocks.FROSTED_ICE, 60 + p.getRandom().nextInt(60));
            }
        }
    }

    /** Rimebark Visor: hostiles near you slow, and shots flying at you lose speed. */
    static void permafrost(Player p, ServerLevel level) {
        for (LivingEntity foe : near(p, PERMAFROST_RADIUS)) foe.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 0), p);
        for (Projectile shot : level.getEntitiesOfClass(Projectile.class, p.getBoundingBox().inflate(PERMAFROST_RADIUS),
                x -> x.getOwner() != p && !x.onGround())) {
            shot.setDeltaMovement(shot.getDeltaMovement().scale(PERMAFROST_SHOT_SLOW));
            level.sendParticles(ParticleTypes.SNOWFLAKE, shot.getX(), shot.getY(), shot.getZ(), 1, 0, 0, 0, 0);
        }
    }

    // ---- what you mine ----------------------------------------------------------------------------------------------
    /** Emberbark Cowl: Smelter's Touch, blocks you break drop what a furnace would make of them. */
    @SubscribeEvent
    public static void drops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof Player p) || VerdureGear.set(p) != Herb.MOONFLARE) return;
        ServerLevel level = e.getLevel();
        boolean smelted = false;
        for (var drop : e.getDrops()) {
            ItemStack out = smelt(level, drop.getItem());
            if (!out.isEmpty()) {
                drop.setItem(out);
                smelted = true;
            }
        }
        if (smelted) level.sendParticles(ParticleTypes.FLAME, e.getPos().getX() + 0.5, e.getPos().getY() + 0.5, e.getPos().getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
    }

    static ItemStack smelt(ServerLevel level, ItemStack in) {
        SingleRecipeInput input = new SingleRecipeInput(in);
        return level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level)
                .map(h -> h.value().assemble(input).copyWithCount(in.getCount()))
                .orElse(ItemStack.EMPTY);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------
    static List<LivingEntity> near(Entity around, double radius) {
        return around.level().getEntitiesOfClass(LivingEntity.class, around.getBoundingBox().inflate(radius), x -> x instanceof Enemy && x.isAlive());
    }

    static void burst(ServerLevel level, Entity at, ParticleOptions particle, int count, double spread) {
        level.sendParticles(particle, at.getX(), at.getY(0.5), at.getZ(), count, spread, 0.5, spread, 0.05);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        TRAILS.remove(e.getEntity().getUUID());
    }
}
