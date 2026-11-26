package com.floraferox;

import com.fundamentum.element.ElementAuras;
import com.fundamentum.element.ElementEvents;
import com.fundamentum.element.ElementRef;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The mobs' elements (docs/DESIGN.md, section 13): which Fundamentum element each mob's attack is made of, how its look
 * changes that, and what its look adds to its strengths and weaknesses. The types, tags, the spore aura, the spore flash
 * reaction and each species' own strengths and weaknesses are data (tools/gen/verdure/content.py).
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureElements {
    private VerdureElements() { }

    /** Which of a mob's attacks it is making, for the mobs whose attacks differ. */
    public enum Attack { MAIN, ERUPT, SLAM, EMBER, RIME, SPORE }

    public static final ElementRef FIRE = ElementRef.parse("#fundamentum:element/fire"), ICE = ElementRef.parse("#fundamentum:element/ice"),
            SPORE = ElementRef.parse("#floraferox:element/spore");
    /** The Carrion Bloom's spore puff: the spore aura alone, as a spore hit would leave it (8 s, up to 3 stacks). */
    public static final ElementAuras.AuraDef SPORE_AURA = new ElementAuras.AuraDef(Optional.of(SPORE), 1F, 160, 0F, 3, 0F);
    /** What an ember or rime look adds to the species' own resistance: half against its own element, 1.5x from the other. */
    public static final float LOOK_RESIST = 0.5F;

    /** The species' element for an attack (a damage type's path), or null for a plain physical blow. */
    static String base(String mob, Attack attack) {
        return switch (mob) {
            case "mire_ghast", "dew_crab", "pitcher_spitter", "glowgill", "dripleaf_toad" -> "soak";
            case "root_grub" -> attack == Attack.ERUPT ? "quake" : null;
            case "thorn_matriarch", "briar_king" -> attack == Attack.SLAM ? "quake" : null;
            case "carrion_bloom" -> attack == Attack.SPORE ? "spore" : null;
            case "crimson_behemoth" -> attack == Attack.SLAM || attack == Attack.ERUPT ? "quake" : attack == Attack.SPORE ? "spore" : null;
            case "pollen_puff", "bloomsprout" -> "gust";
            case "graft_twin" -> attack == Attack.RIME ? "frost" : "scorch";
            case "bell_stalker" -> "toll";
            case "pearl_pod" -> "warp";
            case "void_jelly" -> "venom";
            case "warped_stilt", "blightcap" -> "spore";
            case "soulwick" -> "soul_scorch";
            case "cinder_thistle", "lanternpuff" -> "scorch";
            default -> null;
        };
    }

    /** The look's rule: ember turns physical and ice into fire, rime turns physical and fire into ice; the rest stay. */
    static String withLook(String element, String kind) {
        if (kind.equals("ember") && (element == null || element.equals("frost"))) return "scorch";
        if (kind.equals("rime") && (element == null || element.equals("scorch") || element.equals("soul_scorch"))) return "frost";
        return element;
    }

    /** The element this mob's attack is made of, as a damage type's path, or null for a physical blow (or not ours). */
    public static String element(Entity mob, Attack attack) {
        String id = Looks.mob(mob);
        if ("bloomsprout".equals(id)) {
            return switch (Looks.look(mob).kind()) {
                case "ember" -> "scorch";
                case "rime" -> "frost";
                default -> base(id, attack);
            };
        }
        return id == null ? null : withLook(base(id, attack), Looks.look(mob).kind());
    }

    public static ResourceKey<DamageType> type(String element) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, FloraFerox.id(element));
    }

    /** A rime mob chills where its kind would set alight. */
    public static boolean chills(Entity mob) {
        return Looks.mob(mob) != null && Looks.look(mob).kind().equals("rime");
    }

    /** Sets alight, or chills for a rime mob (Seedshot.strain's frost). */
    public static void burn(Entity mob, LivingEntity target, int seconds) {
        if (!chills(mob)) target.igniteForSeconds(seconds);
        else target.setTicksFrozen(Math.min(target.getTicksRequiredToFreeze() + 60, target.getTicksFrozen() + seconds * 25));
    }

    /** The spore puff coats what it reaches in spores (aura only: its Poison cloud does the harm). */
    public static void spores(ServerLevel level, LivingEntity bloom, LivingEntity target) {
        ElementAuras.apply(target, SPORE, SPORE_AURA, VerdureDamage.source(level, type("spore"), bloom, bloom));
    }

    /** The look's shift on top of the species' own (data/fundamentum/data_maps/entity_type/element_affinity.json). */
    @SubscribeEvent
    public static void affinity(ElementEvents.Affinity e) {
        if (Looks.mob(e.getEntity()) == null) return;
        String kind = Looks.look(e.getEntity()).kind();
        float own = kind.equals("ember") ? 1 : kind.equals("rime") ? -1 : 0;
        if (own == 0) return;
        if (FIRE.matches(e.getSource())) e.addResistance(own * LOOK_RESIST);
        else if (ICE.matches(e.getSource())) e.addResistance(-own * LOOK_RESIST);
    }

    /**
     * A mob's own damage sources: its blows (mobAttack) are of its element for the attack it is making. Everything
     * else, its thorns included, is vanilla's.
     */
    public static final class Sources extends DamageSources {
        private final LivingEntity mob;
        private final RegistryAccess registries;
        private Attack attack = Attack.MAIN;

        public Sources(LivingEntity mob) {
            super(mob.level().registryAccess());
            this.mob = mob;
            this.registries = mob.level().registryAccess();
        }

        /** The attack its next blows are, until set back to MAIN. */
        public void attack(Attack attack) {
            this.attack = attack;
        }

        @Override
        public DamageSource mobAttack(LivingEntity attacker) {
            String element = attacker == mob ? element(mob, attack) : null;
            return element == null ? super.mobAttack(attacker)
                    : new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type(element)), attacker);
        }
    }
}
