package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Move;
import com.floraferox.VerdureRules.Wild;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mod-bus wiring: the mobs' attributes and where they may spawn, the spawn eggs' tab, and the mod's own tab. */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureModEvents {
    private VerdureModEvents() { }

    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FloraFerox.MODID);
    /** Everything but the shots' sprites, then the twelve brews. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.floraferox"))
            .icon(() -> new ItemStack(VerdureItems.HERBS.get(Herb.DUSKPETAL).get()))
            .displayItems((params, out) -> {
                VerdureItems.ITEMS.getEntries().forEach(h -> {
                    if (!VerdureItems.SHOTS.containsKey(h.getId().getPath())) out.accept(h.get());
                });
                for (Herb h : Herb.values()) out.accept(PotionContents.createItemStack(Items.POTION, VerdurePotions.BREWS.get(h)));
            })
            .build());

    @SubscribeEvent
    public static void attributes(EntityAttributeCreationEvent e) {
        e.put(VerdureEntities.PULSE_GNAT.get(), PulseGnat.createAttributes().build());
        e.put(VerdureEntities.MIRE_GHAST.get(), MireGhast.createAttributes().build());
        e.put(VerdureEntities.DUSK_SPRIG.get(), DuskSprig.createAttributes().build());
        e.put(VerdureEntities.MOON_TICK.get(), MoonTick.createAttributes().build());
        for (Ferox f : Ferox.values()) e.put(VerdureEntities.ferox(f), FeroxMob.createAttributes(f).build());
        for (Wild w : Wild.values()) e.put(VerdureEntities.wild(w), WildMob.createAttributes(w).build());
    }

    @SubscribeEvent
    public static void spawnPlacements(RegisterSpawnPlacementsEvent e) {
        var op = RegisterSpawnPlacementsEvent.Operation.REPLACE;
        var h = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        e.register(VerdureEntities.PULSE_GNAT.get(), SpawnPlacementTypes.ON_GROUND, h, PulseGnat::checkSpawn, op);
        e.register(VerdureEntities.MIRE_GHAST.get(), SpawnPlacementTypes.ON_GROUND, h, MireGhast::checkSpawn, op);
        e.register(VerdureEntities.DUSK_SPRIG.get(), SpawnPlacementTypes.ON_GROUND, h, DuskSprig::checkSpawn, op);
        e.register(VerdureEntities.MOON_TICK.get(), SpawnPlacementTypes.ON_GROUND, h, MoonTick::checkSpawn, op);
        for (Ferox f : Ferox.values()) ferox(e, VerdureEntities.ferox(f), f);
        for (Wild w : Wild.values()) wild(e, VerdureEntities.wild(w), w);
    }

    private static <T extends FeroxMob> void ferox(RegisterSpawnPlacementsEvent e, EntityType<T> type, Ferox f) {
        e.register(type, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (t, level, reason, pos, r) -> FeroxMob.checkSpawn(f, t, level, reason, pos, r), RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    /** The fliers spawn in the air over their ground, the rest on it. */
    private static <T extends WildMob> void wild(RegisterSpawnPlacementsEvent e, EntityType<T> type, Wild w) {
        e.register(type, w.move == Move.FLY ? SpawnPlacementTypes.NO_RESTRICTIONS : SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (t, level, reason, pos, r) -> WildMob.checkSpawn(w, t, level, reason, pos, r), RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    @SubscribeEvent
    public static void tabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            e.accept(VerdureItems.PULSE_GNAT_EGG.get());
            e.accept(VerdureItems.MIRE_GHAST_EGG.get());
            e.accept(VerdureItems.DUSK_SPRIG_EGG.get());
            e.accept(VerdureItems.MOON_TICK_EGG.get());
            for (Ferox f : Ferox.values()) e.accept(VerdureItems.FEROX_EGGS.get(f).get());
            for (Wild w : Wild.values()) e.accept(VerdureItems.WILD_EGGS.get(w).get());
        }
    }
}
