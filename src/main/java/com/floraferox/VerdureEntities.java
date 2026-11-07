package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Move;
import com.floraferox.VerdureRules.Wild;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Flora Ferox mobs' entity types (docs/DESIGN.md, section 6). Sizes follow each model at its scale. */
public final class VerdureEntities {
    private VerdureEntities() { }

    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(FloraFerox.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<PulseGnat>> PULSE_GNAT = ENTITIES.registerEntityType("pulse_gnat", PulseGnat::new, MobCategory.MONSTER,
            b -> b.sized(1.3F, 1.6F).eyeHeight(1.0F).clientTrackingRange(10));
    public static final DeferredHolder<EntityType<?>, EntityType<MireGhast>> MIRE_GHAST = ENTITIES.registerEntityType("mire_ghast", MireGhast::new, MobCategory.MONSTER,
            b -> b.sized(0.8F, 2.0F).eyeHeight(1.7F).clientTrackingRange(10));
    public static final DeferredHolder<EntityType<?>, EntityType<DuskSprig>> DUSK_SPRIG = ENTITIES.registerEntityType("dusk_sprig", DuskSprig::new, MobCategory.MONSTER,
            b -> b.sized(0.8F, 1.6F).eyeHeight(1.3F).clientTrackingRange(10));
    public static final DeferredHolder<EntityType<?>, EntityType<MoonTick>> MOON_TICK = ENTITIES.registerEntityType("moon_tick", MoonTick::new, MobCategory.MONSTER,
            b -> b.sized(1.5F, 1.3F).eyeHeight(0.8F).clientTrackingRange(10));
    // ---- 1.2's six: one class (FeroxMob, the Lantern Leech a FeroxFlier), each with its profile (VerdureRules.Ferox)
    public static final DeferredHolder<EntityType<?>, EntityType<FeroxMob>> SNAP_DRAKE = ground(Ferox.SNAP_DRAKE, 1.0F, 1.4F, 1.1F);
    public static final DeferredHolder<EntityType<?>, EntityType<FeroxMob>> REED_WRETCH = ground(Ferox.REED_WRETCH, 0.8F, 2.1F, 1.8F);
    public static final DeferredHolder<EntityType<?>, EntityType<FeroxFlier>> LANTERN_LEECH = ENTITIES.registerEntityType("lantern_leech",
            (t, l) -> new FeroxFlier(t, l, Ferox.LANTERN_LEECH), MobCategory.MONSTER, b -> b.sized(0.9F, 1.8F).eyeHeight(1.2F).clientTrackingRange(10));
    public static final DeferredHolder<EntityType<?>, EntityType<FeroxMob>> BURR_CRAWLER = ground(Ferox.BURR_CRAWLER, 1.2F, 0.9F, 0.6F);
    public static final DeferredHolder<EntityType<?>, EntityType<FeroxMob>> ORCHID_MANTIS = ground(Ferox.ORCHID_MANTIS, 0.8F, 2.2F, 1.9F);
    public static final DeferredHolder<EntityType<?>, EntityType<FeroxMob>> DEW_CRAB = ground(Ferox.DEW_CRAB, 1.4F, 1.1F, 0.9F);

    private static DeferredHolder<EntityType<?>, EntityType<FeroxMob>> ground(Ferox f, float w, float h, float eye) {
        return ENTITIES.registerEntityType(f.id, (t, l) -> new FeroxMob(t, l, f), MobCategory.MONSTER,
                b -> b.sized(w, h).eyeHeight(eye).clientTrackingRange(10));
    }

    /** 1.2's six by profile. */
    public static EntityType<? extends FeroxMob> ferox(Ferox f) {
        return switch (f) {
            case SNAP_DRAKE -> SNAP_DRAKE.get();
            case REED_WRETCH -> REED_WRETCH.get();
            case LANTERN_LEECH -> LANTERN_LEECH.get();
            case BURR_CRAWLER -> BURR_CRAWLER.get();
            case ORCHID_MANTIS -> ORCHID_MANTIS.get();
            case DEW_CRAB -> DEW_CRAB.get();
        };
    }

    /** 1.4's wild mobs (docs/DESIGN.md, section 10): a WildMob, a WildFlier, or the Rift Narwhal, each its own size. */
    public static final Map<Wild, DeferredHolder<EntityType<?>, ? extends EntityType<? extends WildMob>>> WILD = new EnumMap<>(Wild.class);

    static {
        for (Wild w : Wild.values()) {
            EntityType.EntityFactory<WildMob> make = w == Wild.RIFT_NARWHAL ? (t, l) -> new RiftNarwhal(t, l)
                    : w.move == Move.FLY ? (t, l) -> new WildFlier(t, l, w) : (t, l) -> new WildMob(t, l, w);
            WILD.put(w, ENTITIES.registerEntityType(w.id, make, MobCategory.MONSTER, b -> {
                b.sized(w.width, w.height).eyeHeight(w.height * 0.8F).clientTrackingRange(w.is(VerdureRules.BOSS) ? 12 : 10);
                if (w.fireproof()) b.fireImmune();
                return b;
            }));
        }
    }

    public static EntityType<? extends WildMob> wild(Wild w) { return WILD.get(w).get(); }

    /** Every 1.1 weapon's shot (Seedshot). */
    public static final DeferredHolder<EntityType<?>, EntityType<Seedshot>> SHOT = ENTITIES.registerEntityType("seedshot", Seedshot::new, MobCategory.MISC,
            b -> b.sized(0.35F, 0.35F).clientTrackingRange(6).updateInterval(10));
}
