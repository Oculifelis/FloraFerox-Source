package com.floraferox;

import static com.floraferox.VerdureRules.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 1.12, a sceptre's right click (Oculifelis: "left click casts; right click gets a support use per tier"), one cooldown
 * shared by every sceptre a player holds: the Dusk Sceptre's Dusk Bloom heals you and your allies near you; the Ember
 * Sceptre's Ember Ward rings you in fire and makes you fireproof a while; the Rime Sceptre's Rime Shell hardens you and
 * slows the hostiles round you.
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class SceptreSupport {
    private SceptreSupport() { }

    private static final String READY_AT = FloraFerox.MODID + ":support_ready";
    /** Players inside an Ember Ward, and the game time it ends. */
    private static final Map<UUID, Long> WARDS = new ConcurrentHashMap<>();

    public static InteractionResult use(Level level, Player player, InteractionHand hand, int tier) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        long now = level.getGameTime(), ready = player.getPersistentData().getLongOr(READY_AT, 0L);
        if (now < ready) {
            player.sendOverlayMessage(Component.translatable("message.floraferox.support_wait", Component.translatable("support.floraferox." + tier),
                    (ready - now + 19) / 20));
            return InteractionResult.FAIL;
        }
        player.getPersistentData().putLong(READY_AT, now + SUPPORT_COOLDOWN[tier]);
        ServerLevel sl = (ServerLevel) level;
        switch (tier) {
            case 1 -> {
                java.util.List<LivingEntity> allies = new java.util.ArrayList<>(List.of(player));      // the caster always, found or not
                allies.addAll(level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(BLOOM_RADIUS), e -> e != player && ally(e, player)));
                for (LivingEntity ally : allies) {
                    ally.heal(BLOOM_HEAL);
                    sl.sendParticles(ParticleTypes.HEART, ally.getX(), ally.getY(1.0) + 0.3, ally.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
                }
                ring(sl, player, BLOOM_RADIUS, ParticleTypes.HAPPY_VILLAGER);
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
            }
            case 2 -> {
                WARDS.put(player.getUUID(), now + WARD_TICKS);
                player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, WARD_TICKS + 40, 0));
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.9F, 0.8F);
            }
            default -> {
                player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, SHELL_TICKS, 0));
                player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, SHELL_TICKS, 1));
                for (LivingEntity foe : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(SHELL_RADIUS), e -> e instanceof Enemy && e.isAlive())) {
                    foe.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SHELL_SLOW_TICKS, 1), player);
                    foe.setTicksFrozen(Math.max(foe.getTicksFrozen(), foe.getTicksRequiredToFreeze()));
                }
                ring(sl, player, SHELL_RADIUS, ParticleTypes.SNOWFLAKE);
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.9F, 1.5F);
            }
        }
        player.getItemInHand(hand).hurtAndBreak(1, player, hand);
        return InteractionResult.SUCCESS;
    }

    /** You, other players, and the pets of anyone near (never a hostile). */
    static boolean ally(LivingEntity e, Player player) {
        return e.isAlive() && (e instanceof Player || e instanceof OwnableEntity o && o.getOwnerReference() != null);
    }

    static void ring(ServerLevel level, LivingEntity around, double radius, net.minecraft.core.particles.ParticleOptions particle) {
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            level.sendParticles(particle, around.getX() + Math.cos(a) * radius, around.getY() + 0.2, around.getZ() + Math.sin(a) * radius, 1, 0, 0.05, 0, 0.0);
        }
    }

    /** The Ember Ward burns every hostile that comes within its ring, twice a second. */
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level) || p.tickCount % 10 != 0) return;
        Long until = WARDS.get(p.getUUID());
        if (until == null) return;
        if (level.getGameTime() > until || !p.isAlive()) {
            WARDS.remove(p.getUUID());
            return;
        }
        ring(level, p, WARD_RADIUS, ParticleTypes.FLAME);
        for (LivingEntity foe : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(WARD_RADIUS), x -> x instanceof Enemy && x.isAlive())) {
            foe.igniteForSeconds(3F);
        }
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        WARDS.remove(e.getEntity().getUUID());
    }
}
