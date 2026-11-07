package com.floraferox;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.TradeSet;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Weedwarden (docs/DESIGN.md, section 7): Terraria's town NPC, a villager's trade here. Any unemployed villager near a
 * Grafting Bench takes it up; her "Bounties" (trade sets in data/floraferox/trade_set/weedwarden, written by
 * tools/gen/verdure/content.py) take the hunt's tokens for seeds, genes, guides, charms and weapons.
 */
public final class VerdureVillagers {
    private VerdureVillagers() { }

    public static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, FloraFerox.MODID);
    public static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, FloraFerox.MODID);

    public static final DeferredHolder<PoiType, PoiType> WEEDWARDEN_POI = POI_TYPES.register("weedwarden",
            () -> new PoiType(ImmutableSet.copyOf(VerdureBlocks.GRAFTING_BENCH.get().getStateDefinition().getPossibleStates()), 1, 1));

    private static ResourceKey<TradeSet> level(int n) {
        return ResourceKey.create(Registries.TRADE_SET, FloraFerox.id("weedwarden/level_" + n));
    }

    public static final DeferredHolder<VillagerProfession, VillagerProfession> WEEDWARDEN = PROFESSIONS.register("weedwarden",
            () -> new VillagerProfession(Component.translatable("entity.floraferox.villager.weedwarden"),
                    poi -> poi.is(WEEDWARDEN_POI.getKey()), poi -> poi.is(WEEDWARDEN_POI.getKey()), ImmutableSet.of(), ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_FARMER, Int2ObjectMap.ofEntries(Int2ObjectMap.entry(1, level(1)), Int2ObjectMap.entry(2, level(2)),
                    Int2ObjectMap.entry(3, level(3)), Int2ObjectMap.entry(4, level(4)), Int2ObjectMap.entry(5, level(5)))));
}
