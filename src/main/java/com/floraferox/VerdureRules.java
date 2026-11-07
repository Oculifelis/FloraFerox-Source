package com.floraferox;

/**
 * Flora Ferox's mobs in Minecraft (docs/DESIGN.md): their numbers, the class weakness that is each one's
 * identity, and the timings their brains run on. Version-independent: the three targets read the same numbers, and the
 * GameTests drive the brains directly.
 *
 * <p>The source gives all four the same line (100 life, 20 contact damage, 5 defence, knockback resistance 0.5). Its
 * zombie (45 life, 14 damage) maps to ours (20 health, 3 damage), so they become a little tougher than a zombie.
 */
public final class VerdureRules {
    private VerdureRules() { }

    /** The four families' monsters, each its herb come alive. */
    public enum Kind {
        PULSE_GNAT("pulse_gnat", DamageClass.RANGED),
        MIRE_GHAST("mire_ghast", DamageClass.MELEE),
        DUSK_SPRIG("dusk_sprig", DamageClass.MAGIC),
        MOON_TICK("moon_tick", DamageClass.SUMMON);

        public final String id;
        /** The one class it takes full damage from: its own family's hunters. */
        public final DamageClass weakness;

        Kind(String id, DamageClass weakness) {
            this.id = id;
            this.weakness = weakness;
        }
    }

    /** Terraria's damage classes, read from a Minecraft damage source by each target (VerdureDamage). */
    public enum DamageClass { MELEE, RANGED, MAGIC, SUMMON, OTHER }

    public static final double HEALTH = 24.0;
    public static final double ATTACK = 4.0;
    public static final double ARMOR = 4.0;
    public static final double KNOCKBACK_RESISTANCE = 0.5;
    /** What the three other classes deal: half, as in the source. Untyped harm (fire, falls, lava) is never scaled. */
    public static final float RESISTED = 0.5F;

    public static float damageScale(Kind kind, DamageClass cls) {
        return cls == DamageClass.OTHER || cls == kind.weakness ? 1F : RESISTED;
    }

    // ---- drops ------------------------------------------------------------------------------------------------

    // ---- the harvest: picking a grown herb always wakes its family's monster (the source's rule); picking a flower that
    // looks like one of the herbs now and then does too (1.0's stand-in, kept as a rare fallback in 1.1) -----------------
    public static final int CROP_WAKE_ONE_IN = 1;
    public static final int HARVEST_ONE_IN = 20;

    // ---- 1.1: the class bonuses (section 7): the potions' effects, the charms, the armour sets ----------------------
    /** Each level of a family's potion effect (Dusk Veil, Pulse Fever, Moonsheen, Mire Reek): +10% of its class. */
    public static final float EFFECT_BONUS_PER_LEVEL = 0.10F;
    /** A family's charm anywhere in the inventory: +15% of its class (Terraria's charms). */
    public static final float CHARM_BONUS = 0.15F;
    // 1.16 (Oculifelis): a full set no longer adds damage or critical chance; its helmet picks its set effect.
    public static final float CRIT_MULTIPLIER = 1.5F;
    /** The third tier's mage, ranger and (1.16) melee sets: their weapons recover this much faster (melee: swings too). */
    public static final float SET3_COOLDOWN = 0.8F;
    /** 1.12, the sceptres' supports (SceptreSupport): cooldown in ticks by tier, shared by every sceptre. */
    public static final int[] SUPPORT_COOLDOWN = {0, 300, 400, 500};
    /** Dusk Bloom: heals you and your allies within its radius. */
    public static final float BLOOM_HEAL = 4F;
    public static final double BLOOM_RADIUS = 4.0;
    /** Ember Ward: a ring of fire round you for its time, and Fire Resistance a little longer. */
    public static final int WARD_TICKS = 100;
    public static final double WARD_RADIUS = 3.0;
    /** Rime Shell: Resistance I and four hearts of Absorption, and the hostiles near you slowed and frozen. */
    public static final int SHELL_TICKS = 160, SHELL_SLOW_TICKS = 100;
    public static final double SHELL_RADIUS = 5.0;

    // ---- 1.12: every class helmet's own set effect (VerdureSets), none tied to a class -------------------------------
    /** Leafguard Sallet, Sunbather: every interval, in daylight under the sky, heals this much (more standing still). */
    public static final int SUN_INTERVAL = 40;
    public static final float SUN_HEAL = 1F, SUN_HEAL_STILL = 2F;
    /** Leafguard and Emberbark Armets, Pollen Trail and Cinderstep: how long a spot lasts and how far it reaches. */
    public static final int TRAIL_TICKS = 80;
    public static final double TRAIL_RADIUS = 1.2;
    /** Leafguard Cowl, Rootbound: sneaking, the damage you still take. */
    public static final float ROOT_TAKEN = 0.7F;
    /** Leafguard Visor, Overgrowth: every interval, this many tries at a plant within the radius. */
    public static final int GROW_INTERVAL = 60, GROW_TRIES = 3, GROW_RADIUS = 4;
    /** Emberbark Sallet, Phoenix Bloom: once in this many ticks, a killing blow leaves you at this health. */
    public static final int PHOENIX_COOLDOWN = 6000;
    public static final float PHOENIX_HEALTH = 8F;
    public static final double PHOENIX_RADIUS = 4.0;
    /** Emberbark Visor, Flashpoint: the chance a blow sets it off, its cooldown and reach. */
    public static final float FLASH_CHANCE = 0.25F;
    public static final int FLASH_COOLDOWN = 60;
    public static final double FLASH_RADIUS = 3.0;
    /** Rimebark Sallet, Frostwalk: how far round your feet water freezes. */
    public static final int FROST_RADIUS = 2;
    /** Rimebark Armet, Glacial Aegis: the ice shield turns one blow, then regrows in this many ticks. */
    public static final int AEGIS_COOLDOWN = 400;
    /** Rimebark Cowl, Hibernate: a blow that would leave you under this health seals you in ice instead. */
    public static final float HIBERNATE_BELOW = 6F;
    public static final int HIBERNATE_TICKS = 60, HIBERNATE_COOLDOWN = 1200;
    /** Rimebark Visor, Permafrost: its reach, and how much speed a shot keeps each time it is slowed. */
    public static final double PERMAFROST_RADIUS = 5.0;
    public static final double PERMAFROST_SHOT_SLOW = 0.8;

    /** Moon's Spite (a lash's mark): the next class blow it takes is this much harder, then it fades. */
    public static final float SCORN_MULTIPLIER = 1.5F;
    public static final int SCORN_TICKS = 100;

    public static float classBonus(int effectLevel, boolean charm) {
        return effectLevel * EFFECT_BONUS_PER_LEVEL + (charm ? CHARM_BONUS : 0F);
    }

    // ---- 1.1: the weapons, by tier (index 1 to 3) ------------------------------------------------------------------
    public static final float[] SLASH_DAMAGE = {0F, 4F, 5.5F, 7F};
    public static final float[] PETAL_DAMAGE = {0F, 5F, 7F, 9F};
    public static final float[] SEED_PELLET_DAMAGE = {0F, 5F, 7F, 9F};
    public static final float[] LASH_DAMAGE = {0F, 4F, 6F, 8F};
    public static final int BLADE_COOLDOWN = 20, STAFF_COOLDOWN = 14, LASH_COOLDOWN = 16, LASH_MISS_COOLDOWN = 8;
    public static final int[] GUN_COOLDOWN = {0, 12, 10, 8};
    public static final double LASH_RANGE = 6.0, LASH_COMMAND_RANGE = 16.0;

    // ---- 1.1: the genes ---------------------------------------------------------------------------------------------
    /** Ember Graft from a Blaze or Magma Cube, Rime Graft from a Stray, when a player kills it. */
    public static final float GENE_DROP_CHANCE = 0.10F;
    /** Rime Graft frozen in packed or blue ice, broken without Silk Touch. */
    public static final int ICE_GENE_ONE_IN = 10;

    // ---- natural spawns (biome weights; the source spawns them at a fifth of its usual chance) ----------------------
    public static final int WEIGHT_PULSE_GNAT = 12;
    public static final int WEIGHT_MIRE_GHAST = 20;
    public static final int WEIGHT_DUSK_SPRIG = 14;
    public static final int WEIGHT_MOON_TICK = 12;

    // ---- the Pulse Gnat (bat behaviour): orbit, the tell, the heart shot, the flare ---------------------------------------
    public static final double FLY_ORBIT_MIN = 3.0, FLY_ORBIT_MAX = 5.0;
    public static final int FLY_TELL_TICKS = 10;
    public static final int FLY_DART_TICKS = 14;
    public static final int FLY_RECOVER_TICKS = 16;
    public static final int FLY_COOLDOWN_MIN = 40, FLY_COOLDOWN_RANDOM = 50;
    public static final double FLY_DART_SPEED = 0.85;
    public static final double FLY_DART_RANGE = 9.0;

    // ---- the Mire Ghast (fighter behaviour): walk, raise both arms, slam ----------------------------------------------------
    public static final int GHOUL_TELL_TICKS = 12;
    public static final int GHOUL_SLAM_TICKS = 4;
    public static final int GHOUL_RECOVER_TICKS = 14;
    public static final double GHOUL_REACH = 2.3;
    public static final double GHOUL_HIT_REACH = 3.0;
    public static final int GHOUL_COOLDOWN = 10;

    // ---- the Dusk Sprig (snowman behaviour): hop, rear and gape, lunge, snap ---------------------------------------------
    public static final int SPROUT_REAR_TICKS = 14;
    public static final int SPROUT_LUNGE_TICKS = 10;
    public static final int SPROUT_SNAP_TICKS = 6;
    public static final int SPROUT_RECOVER_TICKS = 16;
    public static final double SPROUT_LUNGE_MIN = 2.2, SPROUT_LUNGE_MAX = 5.5;
    public static final double SPROUT_BITE_REACH = 1.9;
    public static final int SPROUT_COOLDOWN_MIN = 30, SPROUT_COOLDOWN_RANDOM = 30;
    public static final double SPROUT_LEAP_SPEED = 0.62, SPROUT_LEAP_UP = 0.36;

    // ---- the Moon Tick (slime behaviour, Hoppin' Jack): crouch, bound, glide, land ---------------------------------------
    public static final int LOUSE_CROUCH_TICKS = 6;
    public static final int LOUSE_LAND_TICKS = 5;
    public static final int LOUSE_WAIT_MIN = 10, LOUSE_WAIT_RANDOM = 12;
    public static final int LOUSE_IDLE_WAIT_MIN = 50, LOUSE_IDLE_WAIT_RANDOM = 60;
    public static final double LOUSE_HOP_SPEED = 0.55, LOUSE_HOP_UP = 0.52;
    /** In the air its wing cases open and the hind wings buzz: it falls slower than a stone. */
    public static final double LOUSE_GLIDE_GRAVITY = 0.6;

    // ---- 1.2's six (StrikeBrain): each walks (or floats) at its prey, holds a tell, strikes, recovers --------------
    /**
     * The six 1.2 mobs, each one of the four families (so it shares that family's weakness, token and seeds): its tell,
     * strike and recovery in ticks, the cooldown after, the reach that starts the tell and the reach the blow lands in,
     * its walking speed, and its biome weight. The sheets' attack (tools/gen/verdure/ferox.py, pose) runs 0..1; the
     * tell ends at {@code tellEnd} and the strike at {@code strikeEnd} of it.
     */
    public enum Ferox implements StrikeBrain.Timing {
        SNAP_DRAKE("snap_drake", Kind.DUSK_SPRIG, 12, 6, 14, 20, 2.6, 3.2, 0.26, 12, 0.40F, 0.55F),
        REED_WRETCH("reed_wretch", Kind.MIRE_GHAST, 14, 5, 16, 16, 2.6, 3.2, 0.22, 14, 0.38F, 0.55F),
        LANTERN_LEECH("lantern_leech", Kind.MOON_TICK, 10, 8, 16, 24, 1.8, 2.4, 0.25, 10, 0.40F, 0.65F),
        BURR_CRAWLER("burr_crawler", Kind.PULSE_GNAT, 12, 5, 14, 18, 1.8, 2.4, 0.24, 14, 0.45F, 0.60F),
        ORCHID_MANTIS("orchid_mantis", Kind.MOON_TICK, 8, 5, 14, 20, 2.4, 3.0, 0.27, 10, 0.38F, 0.55F),
        DEW_CRAB("dew_crab", Kind.MIRE_GHAST, 14, 6, 16, 20, 2.2, 2.8, 0.20, 12, 0.35F, 0.60F);

        public final String id;
        public final Kind kind;
        public final int tell, strike, recover, cooldown;
        public final double reach, hitReach, speed;
        public final int weight;
        public final float tellEnd, strikeEnd;

        Ferox(String id, Kind kind, int tell, int strike, int recover, int cooldown, double reach, double hitReach, double speed, int weight,
              float tellEnd, float strikeEnd) {
            this.id = id;
            this.kind = kind;
            this.tell = tell;
            this.strike = strike;
            this.recover = recover;
            this.cooldown = cooldown;
            this.reach = reach;
            this.hitReach = hitReach;
            this.speed = speed;
            this.weight = weight;
            this.tellEnd = tellEnd;
            this.strikeEnd = strikeEnd;
        }

        @Override public int tell() { return tell; }
        @Override public int strike() { return strike; }
        @Override public int recover() { return recover; }
        @Override public int cooldown() { return cooldown; }
        @Override public double reach() { return reach; }
        @Override public float tellEnd() { return tellEnd; }
        @Override public float strikeEnd() { return strikeEnd; }
    }

    /** The Snap Drake's snap carries it forward at its prey. */
    public static final double SNAP_LUNGE = 0.45;
    /** The Lantern Leech drinks: each bite heals it this much. */
    public static final float LEECH_HEAL = 3F;
    /** The Burr Crawler's hooks and the Dew Crab's glue: Slowness for this long. */
    public static final int CLING_TICKS = 60;
    /** The Lantern Leech hovers this high over the ground. */
    public static final double LEECH_HOVER = 1.2;

    // ---- 1.3: the six's trophies and the charms made of them (VerdureGear); each charm keeps its family's +15% ----
    /** The Snapjaw Charm: a chance of a 1.5x magic hit. */
    public static final float SNAPJAW_CRIT = 0.15F;
    /** The Lantern Charm: each summon hit heals its player this much. */
    public static final float LANTERN_HEAL = 1F;
    /** The Hookburr and Sundew Charms, and the Burr Pellet: Slowness I for this long. */
    public static final int CHARM_SLOW_TICKS = 40;
    /** The Orchid Charm: whips recover this much sooner. */
    public static final float ORCHID_COOLDOWN = 0.75F;

    // ---- 1.4: the wild mobs (section 10), the mockups Oculifelis liked: each the 1.2 strike brain with its own move --------
    /** How it gets about: walks, flies, or stays rooted where it grew. */
    public enum Move { GROUND, FLY, STILL }

    /** How it hits: in reach; leaping or lunging at its prey and biting on contact; a shot; a burst round itself. */
    public enum Style { MELEE, LEAP, LUNGE, SHOT, BURST }

    /** What a landed blow (or shot, or burst) does beyond its damage. */
    public enum Mark { NONE, SLOW, POISON, WEAKNESS, BURN, TWIN }

    /** When and where it may spawn: by day under open sky, in the dark as a monster, or anywhere its biome lists it. */
    public enum When { DAY, NIGHT, ANY }

    /**
     * The wild mobs, each one of the four families (its weakness, token and seeds): its move and style, its health,
     * blow and armour, its speed, its tell, strike and recovery in ticks and the cooldown after, the reach that starts
     * the tell (a shooter's range) and the reach its blow lands in (a burst's radius), what its hits do, when it spawns,
     * its hitbox (from its model, tools/gen/verdure/wild.py), and flags: a miniboss (tougher, never despawns), neutral (it fights
     * back only), tameable (the Rift Narwhal), an ambusher (it waits hidden), a blinker (the Pearl Pod).
     */
    public enum Wild implements StrikeBrain.Timing {
        THORN_HOPPER("thorn_hopper", Kind.PULSE_GNAT, Move.GROUND, Style.LEAP, 20, 4, 2, 0.27, 10, 10, 14, 30, 5.0, 2.0, Mark.NONE, When.DAY, 0.9F, 1.15F, HOP),
        POLLEN_PUFF("pollen_puff", Kind.DUSK_SPRIG, Move.FLY, Style.BURST, 14, 3, 0, 0.25, 14, 6, 16, 40, 2.5, 3.0, Mark.WEAKNESS, When.DAY, 0.9F, 1.4F, NEUTRAL),
        GRAFT_TWIN("graft_twin", Kind.MOON_TICK, Move.GROUND, Style.SHOT, 26, 4, 4, 0.22, 14, 4, 16, 30, 12.0, 0, Mark.TWIN, When.DAY, 1.3F, 1.9F, 0),
        ROOT_GRUB("root_grub", Kind.MIRE_GHAST, Move.GROUND, Style.LUNGE, 22, 5, 4, 0.24, 12, 6, 14, 24, 3.2, 2.2, Mark.NONE, When.NIGHT, 1.1F, 1.0F, 0),
        PITCHER_SPITTER("pitcher_spitter", Kind.MIRE_GHAST, Move.GROUND, Style.SHOT, 22, 3, 4, 0.20, 16, 4, 16, 36, 10.0, 0, Mark.SLOW, When.DAY, 1.1F, 1.45F, 0),
        BELL_STALKER("bell_stalker", Kind.MOON_TICK, Move.GROUND, Style.MELEE, 22, 5, 2, 0.22, 14, 6, 16, 24, 2.8, 3.4, Mark.SLOW, When.NIGHT, 1.2F, 1.55F, 0),
        THORN_MATRIARCH("thorn_matriarch", Kind.PULSE_GNAT, Move.GROUND, Style.LEAP, 120, 8, 6, 0.30, 16, 12, 20, 40, 7.0, 3.0, Mark.NONE, When.DAY, 1.8F, 2.2F, BOSS),
        BRIAR_KING("briar_king", Kind.MIRE_GHAST, Move.GROUND, Style.BURST, 140, 9, 8, 0.24, 18, 6, 20, 36, 3.2, 4.0, Mark.SLOW, When.NIGHT, 1.6F, 2.9F, BOSS),
        CARRION_BLOOM("carrion_bloom", Kind.MIRE_GHAST, Move.STILL, Style.MELEE, 100, 7, 6, 0.0, 14, 6, 18, 24, 4.5, 5.0, Mark.SLOW, When.ANY, 1.8F, 1.4F, BOSS),
        CRIMSON_BEHEMOTH("crimson_behemoth", Kind.MIRE_GHAST, Move.GROUND, Style.BURST, 160, 10, 10, 0.22, 20, 6, 22, 40, 4.0, 5.0, Mark.BURN, When.ANY, 3.2F, 3.6F, BOSS),
        PEARL_POD("pearl_pod", Kind.DUSK_SPRIG, Move.FLY, Style.BURST, 16, 4, 2, 0.25, 12, 6, 14, 30, 2.5, 3.0, Mark.NONE, When.ANY, 0.7F, 1.2F, BLINK),
        VOID_JELLY("void_jelly", Kind.DUSK_SPRIG, Move.FLY, Style.MELEE, 20, 4, 0, 0.25, 12, 6, 16, 24, 2.4, 3.0, Mark.POISON, When.ANY, 0.7F, 1.6F, 0),
        RIFT_NARWHAL("rift_narwhal", Kind.MOON_TICK, Move.FLY, Style.LUNGE, 80, 7, 6, 0.35, 16, 8, 18, 40, 5.0, 3.5, Mark.NONE, When.ANY, 4.0F, 2.7F, NEUTRAL | TAME),
        WARTBACK("wartback", Kind.MIRE_GHAST, Move.GROUND, Style.LUNGE, 26, 5, 4, 0.25, 12, 10, 16, 30, 5.0, 2.4, Mark.NONE, When.ANY, 1.35F, 1.4F, 0),
        WARPED_STILT("warped_stilt", Kind.DUSK_SPRIG, Move.GROUND, Style.BURST, 22, 3, 2, 0.22, 14, 6, 16, 36, 2.8, 3.2, Mark.POISON, When.ANY, 0.8F, 2.0F, NEUTRAL),
        SOULWICK("soulwick", Kind.PULSE_GNAT, Move.GROUND, Style.LEAP, 20, 4, 2, 0.26, 12, 8, 14, 28, 4.5, 2.2, Mark.BURN, When.ANY, 1.0F, 1.5F, 0),
        CINDER_THISTLE("cinder_thistle", Kind.PULSE_GNAT, Move.GROUND, Style.SHOT, 18, 4, 2, 0.22, 14, 4, 14, 30, 10.0, 0, Mark.BURN, When.ANY, 1.05F, 1.7F, 0),
        LANTERNPUFF("lanternpuff", Kind.MOON_TICK, Move.FLY, Style.SHOT, 16, 3, 0, 0.18, 14, 4, 16, 40, 8.0, 0, Mark.BURN, When.ANY, 0.7F, 1.3F, NEUTRAL),
        NYLIUM_LURKER("nylium_lurker", Kind.MIRE_GHAST, Move.GROUND, Style.LUNGE, 22, 5, 4, 0.28, 8, 8, 14, 24, 4.0, 2.4, Mark.NONE, When.ANY, 1.8F, 0.8F, AMBUSH);

        public final String id;
        public final Kind kind;
        public final Move move;
        public final Style style;
        public final double health, attack, armor, speed, reach, hitReach;
        public final int tell, strike, recover, cooldown;
        public final Mark mark;
        public final When when;
        public final float width, height;
        public final int flags;

        Wild(String id, Kind kind, Move move, Style style, double health, double attack, double armor, double speed, int tell, int strike,
             int recover, int cooldown, double reach, double hitReach, Mark mark, When when, float width, float height, int flags) {
            this.id = id;
            this.kind = kind;
            this.move = move;
            this.style = style;
            this.health = health;
            this.attack = attack;
            this.armor = armor;
            this.speed = speed;
            this.tell = tell;
            this.strike = strike;
            this.recover = recover;
            this.cooldown = cooldown;
            this.reach = reach;
            this.hitReach = hitReach;
            this.mark = mark;
            this.when = when;
            this.width = width;
            this.height = height;
            this.flags = flags;
        }

        public boolean is(int flag) { return (flags & flag) != 0; }

        /** The Nether's take no harm from fire or lava's heat. */
        public boolean fireproof() {
            return switch (this) {
                case CRIMSON_BEHEMOTH, WARTBACK, WARPED_STILT, SOULWICK, CINDER_THISTLE, LANTERNPUFF, NYLIUM_LURKER -> true;
                default -> false;
            };
        }

        @Override public int tell() { return tell; }
        @Override public int strike() { return strike; }
        @Override public int recover() { return recover; }
        @Override public int cooldown() { return cooldown; }
        @Override public double reach() { return reach; }
        /** Their sheets (tools/gen/verdure/ferox*.py, pose) all hold the tell to 0.35-0.4 of the attack and the blow to 0.5-0.6. */
        @Override public float tellEnd() { return WILD_TELL_END; }
        @Override public float strikeEnd() { return WILD_STRIKE_END; }
    }

    public static final int BOSS = 1, NEUTRAL = 2, TAME = 4, AMBUSH = 8, BLINK = 16, HOP = 32;
    /** A hopper (the Thorn Hopper) gets about by hops, as a grasshopper does: this far forward and up, this often. */
    public static final double HOP_SPEED = 0.42, HOP_UP = 0.46;
    public static final int HOP_REST = 8;
    /** A hopper den (HopperDenBlock) keeps this many hoppers out, and they keep within this many blocks of it. */
    public static final int DEN_BROOD = 3, DEN_HOME = 10;
    public static final float WILD_TELL_END = 0.38F, WILD_STRIKE_END = 0.55F;
    /** A leap: this fast at its prey and this far up; a lunge goes low and fast. */
    public static final double LEAP_SPEED = 0.7, LEAP_UP = 0.42, LUNGE_SPEED = 0.8, LUNGE_UP = 0.08;
    /** A shot's speed, and how long its mark lasts (the burn in seconds, the rest in ticks). */
    public static final double SHOT_SPEED = 1.1;
    public static final int MARK_TICKS = 60, BURN_SECONDS = 4;
    /** A minibosses' knockback resistance; the others take the families' 0.5. */
    public static final double BOSS_KNOCKBACK_RESISTANCE = 0.9;
    /** The ambusher only wakes to prey this close; the blinker jumps this far after its burst. */
    public static final double AMBUSH_RANGE = 6.0, BLINK_RANGE = 6.0;
    /** The Rift Narwhal: one chorus fruit in this many tames it; ridden, it flies this fast. */
    public static final int NARWHAL_TAME_ONE_IN = 4;
    public static final double NARWHAL_RIDE_SPEED = 0.12;
    /** A flier drifts this high over the ground when it has nothing to chase. */
    public static final double WILD_HOVER = 1.5;

    // ---- 1.5: each one's own ways (docs/DESIGN.md, section 11) ------------------------------------------------------
    /** Neutral ones call their kind within this far to help when struck. */
    public static final double ALERT_RANGE = 16.0;
    /** Shooters back off from prey closer than this share of their range. */
    public static final double KITE_SHARE = 0.35;
    /** After a blow the Thorn Hopper springs away, the Pollen Puff drifts off, the narwhal wheels round: this long. */
    public static final int HOP_AWAY_TICKS = 20, PUFF_FLEE_TICKS = 60, NARWHAL_WHEEL_TICKS = 30, LEECH_FLEE_TICKS = 30;
    /** The Root Grub burrows to prey farther than this, erupts under it, and waits this long to burrow again. */
    public static final double BURROW_FROM = 5.0, ERUPT_REACH = 1.6, ERUPT_UP = 0.7;
    public static final int BURROW_MAX_TICKS = 200, BURROW_COOLDOWN = 100;
    /** The Pitcher Spitter gulps prey this close instead of spitting at it. */
    public static final double GULP_REACH = 2.2;
    /** The Bell Stalker creeps only while its prey looks away (its view this close to the stalker's eyes). */
    public static final double WATCHED_DOT = 0.9;
    /** The Graft Twin fires both heads every third volley. */
    public static final int TWIN_VOLLEY_EVERY = 3;
    /** The Thorn Matriarch's landing: a shockwave this wide; its brood of Thorn Hoppers at two thirds and a third of its health. */
    public static final double SLAM_RADIUS = 3.5, SLAM_KNOCKBACK = 1.0;
    public static final int BROOD = 2;
    /** The Briar King's and Crimson Behemoth's charge: from this far, this fast, this long, this often. */
    public static final double CHARGE_FROM = 6.0, CHARGE_TO = 16.0, CHARGE_SPEED = 0.45, CHARGE_KNOCKBACK = 1.2;
    public static final int CHARGE_TICKS = 30, CHARGE_COOLDOWN = 120;
    /** The Briar King's thorns, under half its health: what a blow at it costs its striker. */
    public static final float THORNS = 2F;
    /** The Crimson Behemoth's fire volley at prey this far: three fireballs, this often. */
    public static final double VOLLEY_FROM = 8.0;
    public static final int VOLLEY_COOLDOWN = 100;
    /** The Carrion Bloom's vines drag prey within this far toward it, and its spores hang in a poison cloud: this often. */
    public static final double PULL_RANGE = 10.0, PULL_SPEED = 0.6, SPORE_RANGE = 8.0;
    public static final int PULL_EVERY = 100, SPORE_EVERY = 160, SPORE_TICKS = 100;
    /** The Pearl Pod blinks away from a blow this often (1 in). */
    public static final int POD_DODGE_ONE_IN = 2;
    /** The Void Jelly's every other sting lifts its prey (Levitation) this long. */
    public static final int JELLY_LIFT_TICKS = 30;
    /** The narwhal's gore and the Wartback's toss. */
    public static final double GORE_KNOCKBACK = 1.6, TOSS_UP = 0.6;
    /** The Soulwick flares once, under this share of its health, in a burst this wide. */
    public static final float FLARE_AT = 0.3F;
    public static final double FLARE_RADIUS = 3.0;
    /** The Cinder Thistle flings three spikes this far apart (degrees). */
    public static final float FAN_SPREAD = 12F;
    /** The Lanternpuff's flash blinds its striker within this far, this long. */
    public static final double FLASH_RANGE = 6.0;
    public static final int FLASH_TICKS = 40;
    /** The Nylium Lurker's jaws hold (Slowness IV) this long; it goes back to its lair farther than this. */
    public static final int GRIP_TICKS = 30;
    public static final double LAIR_RANGE = 2.0;
    /** The Snap Drake's second snap and the Orchid Mantis's second slash, this many ticks after the first. */
    public static final int FOLLOW_UP_TICKS = 5;
    /** The Reed Wretch's scythe sweeps this wide a front (degrees either side). */
    public static final double SWEEP_HALF_ANGLE = 50.0;
    /** The Burr Crawler throws a clinging burr at prey this far off, this often. */
    public static final double BURR_FROM = 3.5, BURR_TO = 9.0;
    public static final int BURR_COOLDOWN = 60;
    /** The Dew Crab's grip: Slowness III this long; and it sidles round prey it cannot strike yet. */
    public static final int CRAB_GRIP_TICKS = 30;
    /** The Peace Lily Charm (Truce): the creatures leave its wearer be, unless struck by it in the last five seconds. */
    public static final int TRUCE_CHECK_TICKS = 20;
}
