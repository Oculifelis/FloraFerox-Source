package com.floraferox;

import com.floraferox.VerdureRules.Kind;

/**
 * The twelve herbs of Flora Ferox (docs/DESIGN.md, section 7): four families (Dusk, Pulse, Moon, Mire), one per class,
 * each in three strains. The first grows from seeds spliced at the Grafting Bench; the second (-flare) is spliced with
 * an Ember Graft; the third (-rime) with a Rime Graft. Picking a grown first strain wakes its family's monster;
 * the later strains wake one of the family's new hunters (1.3).
 */
public enum Herb {
    DUSKPETAL("duskpetal", Kind.DUSK_SPRIG, 1), DUSKFLARE("duskflare", Kind.DUSK_SPRIG, 2), DUSKRIME("duskrime", Kind.DUSK_SPRIG, 3),
    PULSEROOT("pulseroot", Kind.PULSE_GNAT, 1), PULSEFLARE("pulseflare", Kind.PULSE_GNAT, 2), PULSERIME("pulserime", Kind.PULSE_GNAT, 3),
    MOONWISP("moonwisp", Kind.MOON_TICK, 1), MOONFLARE("moonflare", Kind.MOON_TICK, 2), MOONRIME("moonrime", Kind.MOON_TICK, 3),
    MIRECAP("mirecap", Kind.MIRE_GHAST, 1), MIREFLARE("mireflare", Kind.MIRE_GHAST, 2), MIRERIME("mirerime", Kind.MIRE_GHAST, 3);

    public final String id;
    /** The family: the monster it wakes, and the class its gear serves (Kind.weakness). */
    public final Kind kind;
    public final int tier;

    Herb(String id, Kind kind, int tier) {
        this.id = id;
        this.kind = kind;
        this.tier = tier;
    }

    public static Herb of(Kind kind, int tier) {
        for (Herb h : values()) if (h.kind == kind && h.tier == tier) return h;
        throw new IllegalArgumentException(kind + " " + tier);
    }
}
