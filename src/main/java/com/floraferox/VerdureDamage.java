package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Kind;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;

/**
 * Terraria's damage classes read from a Minecraft damage source (docs/DESIGN.md, section 3): a summon is anything
 * that fights for an owner (a tamed wolf, a player's golem, any owned creature); magic is what witches resist (potions,
 * thorns, evoker fangs, the mod's spells); ranged is any projectile; melee is a living thing's own blow. Anything else
 * (fire, falls, lava, explosions) is untyped and never scaled. The 1.1 weapons deal their own damage types, each tagged
 * with its class (floraferox:melee, ranged, magic, summon), and those tags are read first.
 */
public final class VerdureDamage {
    private VerdureDamage() { }

    private static final TagKey<DamageType> MELEE = tag("melee"), RANGED = tag("ranged"), MAGIC = tag("magic"), SUMMON = tag("summon");

    private static TagKey<DamageType> tag(String name) {
        return TagKey.create(Registries.DAMAGE_TYPE, FloraFerox.id(name));
    }

    /** Our damage types (data/floraferox/damage_type), one per class. */
    public static ResourceKey<DamageType> type(DamageClass cls) {
        String name = switch (cls) {
            case MELEE -> "slash";
            case MAGIC -> "petal";
            case SUMMON -> "lash";
            default -> "seed_pellet";
        };
        return ResourceKey.create(Registries.DAMAGE_TYPE, FloraFerox.id(name));
    }

    /** 1.16: the guns' own damage type, magic (their shots are seed magic, not arrows). */
    public static final ResourceKey<DamageType> SEED_BOLT = ResourceKey.create(Registries.DAMAGE_TYPE, FloraFerox.id("seed_bolt"));

    public static DamageSource source(ServerLevel level, DamageClass cls, Entity direct, Entity cause) {
        return source(level, type(cls), direct, cause);
    }

    public static DamageSource source(ServerLevel level, ResourceKey<DamageType> type, Entity direct, Entity cause) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type), direct, cause);
    }

    /**
     * 1.16: every class a hit counts as. Our own types may carry several tags (a whip's lash is summon and ranged); such
     * a hit is dealt once and counts as whichever class serves it best, never as both at once. Others have one class.
     */
    public static java.util.EnumSet<DamageClass> classes(DamageSource source) {
        java.util.EnumSet<DamageClass> out = java.util.EnumSet.noneOf(DamageClass.class);
        if (source.is(SUMMON)) out.add(DamageClass.SUMMON);
        if (source.is(MAGIC)) out.add(DamageClass.MAGIC);
        if (source.is(RANGED)) out.add(DamageClass.RANGED);
        if (source.is(MELEE)) out.add(DamageClass.MELEE);
        if (out.isEmpty()) out.add(classify(source));
        return out;
    }

    public static DamageClass classify(DamageSource source) {
        if (source.is(SUMMON)) return DamageClass.SUMMON;
        if (source.is(MAGIC)) return DamageClass.MAGIC;
        if (source.is(RANGED)) return DamageClass.RANGED;
        if (source.is(MELEE)) return DamageClass.MELEE;
        Entity cause = source.getEntity();
        Entity direct = source.getDirectEntity();
        if (cause != null && isSummon(cause)) return DamageClass.SUMMON;
        if (source.is(DamageTypeTags.WITCH_RESISTANT_TO)) return DamageClass.MAGIC;
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return DamageClass.RANGED;
        if (direct instanceof LivingEntity && direct == cause) return DamageClass.MELEE;
        return DamageClass.OTHER;
    }

    public static boolean isSummon(Entity e) {
        if (e instanceof OwnableEntity o && o.getOwnerReference() != null) return true;
        if (e instanceof IronGolem g && g.isPlayerCreated()) return true;
        return e instanceof SnowGolem;
    }

    /** The damage a Flora Ferox mob takes: full from its own family's class (any of a hit's classes), half from the rest. */
    public static float scale(Kind kind, DamageSource source, float amount) {
        float best = 0F;
        for (DamageClass c : classes(source)) best = Math.max(best, VerdureRules.damageScale(kind, c));
        return amount * best;
    }
}
