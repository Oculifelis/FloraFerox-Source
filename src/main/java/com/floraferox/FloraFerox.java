package com.floraferox;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Flora Ferox (docs/DESIGN.md), inspired by Violent Verdure, the Terraria mod by Violytta Ran: the Pulse Gnat, the Mire
 * Ghast, the Dusk Sprig and the Moon Tick (1.0, first built inside Ars Moriendi), and in 1.1 the herbs they grow from, the
 * Grafting Bench, the brews, the gear, the tokens and the Weedwarden.
 */
@Mod(FloraFerox.MODID)
public final class FloraFerox {
    public static final String MODID = "floraferox";

    public FloraFerox(IEventBus modBus) {
        VerdureEntities.ENTITIES.register(modBus);
        VerdureBlocks.BLOCKS.register(modBus);
        VerdureBlocks.MENUS.register(modBus);
        VerdureBlocks.FEATURES.register(modBus);
        VerdureItems.ITEMS.register(modBus);
        VerdurePotions.EFFECTS.register(modBus);
        VerdurePotions.POTIONS.register(modBus);
        VerdureVillagers.POI_TYPES.register(modBus);
        VerdureVillagers.PROFESSIONS.register(modBus);
        VerdureModEvents.TABS.register(modBus);
        VerdureGameTests.FUNCTIONS.register(modBus);
        VerdureWeapons.Whip.register();                  // 1.6: the whips are Fundamentum cord whips
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
