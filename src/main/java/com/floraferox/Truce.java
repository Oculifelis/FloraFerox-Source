package com.floraferox;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 1.5's Peace Lily Charm (docs/DESIGN.md, section 11): with it on, every Flora Ferox creature is neutral to you. It
 * never takes you for prey, and one that is after you lets you be, unless you have struck it in the last five seconds
 * (the game's own memory of who last hurt it); then it fights back as a neutral one does, and forgets once you stop.
 *
 * <p>Worn in a Curios charm slot when Curios is installed; without Curios, carried anywhere in the inventory. Held in
 * either hand it works too (and so for any creature, which the GameTests use).
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class Truce {
    private Truce() { }

    /** One of ours: a Flora Ferox creature (not its shots). */
    public static boolean ours(Entity e) {
        return e instanceof Mob && FloraFerox.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace());
    }

    /** Whether this one has the charm on. */
    public static boolean wearing(LivingEntity e) {
        Item charm = VerdureItems.PEACE_LILY_CHARM.get();
        if (e.getMainHandItem().is(charm) || e.getOffhandItem().is(charm)) return true;
        if (ModList.get().isLoaded("curios")) return CuriosCompat.worn(e, charm);
        return e instanceof Player p && p.getInventory().contains(s -> s.is(charm));
    }

    /** Whether the creature leaves this one be: it wears the charm and has not struck the creature lately. */
    public static boolean spares(Mob mob, LivingEntity t) {
        return wearing(t) && mob.getLastHurtByMob() != t;
    }

    @SubscribeEvent
    public static void changeTarget(LivingChangeTargetEvent e) {
        LivingEntity t = e.getNewAboutToBeSetTarget();
        if (t != null && e.getEntity() instanceof Mob mob && ours(mob) && spares(mob, t)) e.setNewAboutToBeSetTarget(null);
    }

    /** One already after you when you put the charm on (or that you stopped striking) lets you go. */
    @SubscribeEvent
    public static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob mob) || mob.level().isClientSide() || mob.tickCount % VerdureRules.TRUCE_CHECK_TICKS != 0) return;
        LivingEntity t = mob.getTarget();
        if (t != null && ours(mob) && spares(mob, t)) mob.setTarget(null);
    }
}
