package com.floraferox;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import top.theillusivec4.curios.api.CuriosApi;

/** Curios, when it is installed (an optional dependency): only ever loaded after Truce has checked it is. */
final class CuriosCompat {
    private CuriosCompat() { }

    /** Whether this item is worn in one of the entity's Curios slots. */
    static boolean worn(LivingEntity e, Item item) {
        return CuriosApi.getCuriosInventory(e).flatMap(inv -> inv.findFirstCurio(s -> s.is(item))).isPresent();
    }

    /** Puts a stack in the entity's first slot of this Curios type (the GameTests use it); false without that slot. */
    static boolean equip(LivingEntity e, String slot, net.minecraft.world.item.ItemStack stack) {
        return CuriosApi.getCuriosInventory(e).map(inv -> {
            if (!inv.getCurios().containsKey(slot)) return false;
            inv.setEquippedCurio(slot, 0, stack);
            return true;
        }).orElse(false);
    }
}
