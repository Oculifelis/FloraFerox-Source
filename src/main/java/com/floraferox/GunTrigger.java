package com.floraferox;

import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 1.10, the seed guns' trigger, as Quiverfall's guns: left click fires. The client reports the attack button going down
 * and up while a seed gun is in the main hand (client/GunHandling); the server fires on the press and again each time
 * the gun comes off cooldown while it is held, so holding fires no faster than clicking. 1.12: a sceptre's trigger is
 * the same left click (its cast; right click is its support, SceptreSupport), and each shot is told to the players
 * watching the shooter ({@link Shot}), so their third-person arm kicks too.
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class GunTrigger {
    private GunTrigger() { }

    /** The trigger went down or came back up. */
    public record Payload(boolean down) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(FloraFerox.id("gun_trigger"));
        public static final StreamCodec<ByteBuf, Payload> STREAM_CODEC = ByteBufCodecs.BOOL.map(Payload::new, Payload::down);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    /** 1.12, server to the players watching: this player fired a seed gun (client/GunHandling kicks their arm). */
    public record Shot(int entity) implements CustomPacketPayload {
        public static final Type<Shot> TYPE = new Type<>(FloraFerox.id("gun_shot"));
        public static final StreamCodec<ByteBuf, Shot> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Shot::new, Shot::entity);

        @Override
        public Type<Shot> type() {
            return TYPE;
        }
    }

    /** Players holding the trigger, and the gun they held when they pulled it. */
    private static final Map<UUID, ItemStack> HELD = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void payloads(RegisterPayloadHandlersEvent e) {
        e.registrar("1").playToServer(Payload.TYPE, Payload.STREAM_CODEC, GunTrigger::handle)
                .playToClient(Shot.TYPE, Shot.STREAM_CODEC, (shot, context) -> com.floraferox.client.GunHandling.shotBy(shot.entity()));
    }

    static void handle(Payload p, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) set(player, p.down());
    }

    /** Trigger down: fire now if the gun is ready; up: stop. */
    public static void set(ServerPlayer player, boolean down) {
        if (!down) {
            HELD.remove(player.getUUID());
            return;
        }
        ItemStack gun = player.getMainHandItem();
        if (!triggers(gun)) return;
        HELD.put(player.getUUID(), gun);
        pull(player, gun, true);
    }

    /** A seed gun or (1.12) a sceptre: left click is its trigger. */
    public static boolean triggers(ItemStack stack) {
        return stack.getItem() instanceof VerdureWeapons.BloomGun || stack.getItem() instanceof VerdureWeapons.PetalStaff;
    }

    public static boolean held(ServerPlayer player) {
        return HELD.containsKey(player.getUUID());
    }

    /** Fires if ready. Held with no pellets left, only the press clicks empty, not every tick after. */
    private static void pull(ServerPlayer player, ItemStack gun, boolean press) {
        if (player.isSpectator() || player.getCooldowns().isOnCooldown(gun)) return;
        if (gun.getItem() instanceof VerdureWeapons.PetalStaff staff) {
            staff.cast(player.level(), player, InteractionHand.MAIN_HAND);
            return;
        }
        if (!press && VerdureWeapons.BloomGun.ammo(player).isEmpty() && !player.hasInfiniteMaterials() && !GunEnchants.has(gun, GunEnchants.INFINITY)) return;
        ((VerdureWeapons.BloomGun) gun.getItem()).shoot(player.level(), player, InteractionHand.MAIN_HAND);
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        ItemStack gun = HELD.get(player.getUUID());
        if (gun == null) return;
        if (player.getMainHandItem() != gun || !player.isAlive()) {
            // switched items or died with the trigger down
            HELD.remove(player.getUUID());
            return;
        }
        pull(player, gun, false);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        HELD.remove(e.getEntity().getUUID());
    }
}
