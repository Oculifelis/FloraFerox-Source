package com.floraferox;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 1.8: the seed guns' enchantments. They are data
 * (data/floraferox/enchantment, made by tools/gen/verdure/content.py); Weedkiller's bonus is a vanilla damage effect,
 * the rest is read here by level and applied in {@link VerdureWeapons.BloomGun} and {@link Seedshot}.
 */
public final class GunEnchants {
    private GunEnchants() { }

    public static final ResourceKey<Enchantment> HEARTSIGHT = key("heartsight");
    public static final ResourceKey<Enchantment> SEED_HOPPER = key("seed_hopper");
    public static final ResourceKey<Enchantment> WEEDKILLER = key("weedkiller");
    public static final ResourceKey<Enchantment> QUICKDRAW = key("quickdraw");
    public static final ResourceKey<Enchantment> BLOOM_SHOT = key("bloom_shot");
    public static final ResourceKey<Enchantment> HEARTMEND = key("heartmend");
    public static final ResourceKey<Enchantment> INFINITY = key("infinity");

    /** Who a Heartmend shot may heal, beyond the shooter's team and its own tamed or owned mobs. */
    public static final TagKey<EntityType<?>> HEAL_TARGETS = TagKey.create(Registries.ENTITY_TYPE, FloraFerox.id("heal_targets"));

    /** Seed Hopper: one pellet in three is not spent (half as many shots again). */
    public static final float HOPPER_SAVE = 1F / 3F;
    /** Quickdraw, per level: the cooldown shrinks by this share, the shot speeds up by the other. */
    public static final float QUICKDRAW_COOLDOWN = 0.2F, QUICKDRAW_SPEED = 0.15F;
    /** Bloom Shot: slower, a little harder, longer-lived, and a burst of this radius where it lands. */
    public static final float BLOOM_SPEED = 0.55F, BLOOM_DAMAGE = 1.25F, BLOOM_RADIUS = 2.75F;
    /** Heartmend: a shot heals this share of the damage it would have done. */
    public static final float MEND_SHARE = 0.6F;
    /** Heartsight: the view's zoom while sneaking with the gun (a FOV multiplier) and the ticks it takes to ease in. */
    public static final float SIGHT_FOV = 0.6F;
    public static final int SIGHT_TICKS = 4;

    private static ResourceKey<Enchantment> key(String id) {
        return ResourceKey.create(Registries.ENCHANTMENT, FloraFerox.id(id));
    }

    /** The enchantment's level on the stack, 0 if it has none. */
    public static int level(ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack.isEmpty()) return 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> e : stack.getEnchantments().entrySet()) {
            if (e.getKey().is(key)) return e.getIntValue();
        }
        return 0;
    }

    public static boolean has(ItemStack stack, ResourceKey<Enchantment> key) {
        return level(stack, key) > 0;
    }
}
