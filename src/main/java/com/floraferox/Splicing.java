package com.floraferox;

import com.floraferox.VerdureRules.Kind;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Grafting Bench's splices (docs/DESIGN.md, section 7), Terraria's mutations and crossbreeds made Minecraft's:
 * <ul>
 * <li>wheat seeds and a flower that looks like a herb (VerdureEvents.family) give that family's first seeds;</li>
 * <li>first seeds and an Ember Graft give the family's burning strain;</li>
 * <li>burning seeds and a Rime Graft give its frozen strain.</li>
 * </ul>
 * Either order; one of each is spent for one seed.
 */
public final class Splicing {
    private Splicing() { }

    public static ItemStack result(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return ItemStack.EMPTY;
        ItemStack r = oneWay(a, b);
        return r.isEmpty() ? oneWay(b, a) : r;
    }

    private static ItemStack oneWay(ItemStack a, ItemStack b) {
        if (a.is(Items.WHEAT_SEEDS) && b.getItem() instanceof BlockItem bi) {
            Kind k = VerdureEvents.family(bi.getBlock().defaultBlockState());
            if (k != null) return seeds(Herb.of(k, 1));
        }
        Herb h = seedHerb(a.getItem());
        if (h == null) return ItemStack.EMPTY;
        if (h.tier == 1 && b.is(VerdureItems.EMBER_GRAFT.get())) return seeds(Herb.of(h.kind, 2));
        if (h.tier == 2 && b.is(VerdureItems.RIME_GRAFT.get())) return seeds(Herb.of(h.kind, 3));
        return ItemStack.EMPTY;
    }

    public static Herb seedHerb(Item item) {
        for (Herb h : Herb.values()) if (VerdureItems.SEEDS.get(h).get() == item) return h;
        return null;
    }

    private static ItemStack seeds(Herb h) {
        return new ItemStack(VerdureItems.SEEDS.get(h).get());
    }
}
