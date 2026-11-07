package com.floraferox;

import com.floraferox.VerdureRules.Kind;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The twelve brews (docs/DESIGN.md, section 7): an Awkward Potion and a herb give its family's drink at the herb's tier.
 * Terraria's buffs become each family's class effect (+10% of its class's damage a level, VerdureGear) with the vanilla
 * effects nearest the source's: Dusk Veil's mana regeneration is Night Vision; Pulse Fever's firing speed is Haste;
 * Moonsheen's extra minions are its owner's pets hitting harder; Mire Reek' defence and movement are Resistance and
 * Speed. The burning strains add Fire Resistance; the frozen ones a third perk each.
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdurePotions {
    private VerdurePotions() { }

    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, FloraFerox.MODID);
    public static final DeferredRegister<Potion> POTIONS = DeferredRegister.create(Registries.POTION, FloraFerox.MODID);

    /** A class effect: no ticking of its own; VerdureGear reads its level when its holder (or its pets) deal damage. */
    public static final class ClassEffect extends MobEffect {
        public ClassEffect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }

    public static final DeferredHolder<MobEffect, MobEffect> DUSK_VEIL = EFFECTS.register("dusk_veil", () -> new ClassEffect(MobEffectCategory.BENEFICIAL, 0xE6D166));
    public static final DeferredHolder<MobEffect, MobEffect> PULSE_FEVER = EFFECTS.register("pulse_fever", () -> new ClassEffect(MobEffectCategory.BENEFICIAL, 0xFF6298));
    public static final DeferredHolder<MobEffect, MobEffect> MOONSHEEN = EFFECTS.register("moonsheen", () -> new ClassEffect(MobEffectCategory.BENEFICIAL, 0x60C6EA));
    public static final DeferredHolder<MobEffect, MobEffect> MIRE_REEK = EFFECTS.register("mire_reek", () -> new ClassEffect(MobEffectCategory.BENEFICIAL, 0x88BB68));
    /** A lash's mark (Terraria's Moon's Spite): the next class blow is harder (VerdureRules.SCORN_MULTIPLIER). */
    public static final DeferredHolder<MobEffect, MobEffect> MOONS_SPITE = EFFECTS.register("moons_spite", () -> new ClassEffect(MobEffectCategory.HARMFUL, 0x8273B9));

    public static Holder<MobEffect> classEffect(Kind kind) {
        return switch (kind) {
            case DUSK_SPRIG -> DUSK_VEIL;
            case PULSE_GNAT -> PULSE_FEVER;
            case MOON_TICK -> MOONSHEEN;
            case MIRE_GHAST -> MIRE_REEK;
        };
    }

    private static final int MINUTES_3 = 3 * 60 * 20;

    public static final Map<Herb, DeferredHolder<Potion, Potion>> BREWS = new EnumMap<>(Herb.class);

    private static void brew(Herb herb, String name, MobEffectInstance... extra) {
        MobEffectInstance[] all = new MobEffectInstance[extra.length + 1];
        all[0] = new MobEffectInstance(classEffect(herb.kind), MINUTES_3, herb.tier - 1);
        System.arraycopy(extra, 0, all, 1, extra.length);
        BREWS.put(herb, POTIONS.register(name, () -> new Potion(name, all)));
    }

    private static MobEffectInstance e(Holder<MobEffect> effect, int amplifier) {
        return new MobEffectInstance(effect, MINUTES_3, amplifier);
    }

    static {
        brew(Herb.DUSKPETAL, "duskpetal_draught", e(MobEffects.NIGHT_VISION, 0));
        brew(Herb.DUSKFLARE, "duskflare_draught", e(MobEffects.NIGHT_VISION, 0), e(MobEffects.FIRE_RESISTANCE, 0));
        brew(Herb.DUSKRIME, "duskrime_draught", e(MobEffects.NIGHT_VISION, 0), e(MobEffects.ABSORPTION, 0));
        brew(Herb.PULSEROOT, "pulseroot_punch", e(MobEffects.HASTE, 0));
        brew(Herb.PULSEFLARE, "pulseflare_punch", e(MobEffects.HASTE, 0), e(MobEffects.FIRE_RESISTANCE, 0));
        brew(Herb.PULSERIME, "pulserime_punch", e(MobEffects.HASTE, 0), new MobEffectInstance(MobEffects.REGENERATION, 60 * 20, 0));
        brew(Herb.MOONWISP, "moonwisp_mead");
        brew(Herb.MOONFLARE, "moonflare_mead", e(MobEffects.FIRE_RESISTANCE, 0));
        brew(Herb.MOONRIME, "moonrime_mead", e(MobEffects.SLOW_FALLING, 0));
        brew(Herb.MIRECAP, "mirecap_grog", e(MobEffects.RESISTANCE, 0));
        brew(Herb.MIREFLARE, "mireflare_grog", e(MobEffects.RESISTANCE, 0), e(MobEffects.SPEED, 0), e(MobEffects.FIRE_RESISTANCE, 0));
        brew(Herb.MIRERIME, "mirerime_grog", e(MobEffects.RESISTANCE, 0), e(MobEffects.SPEED, 0));
    }

    @SubscribeEvent
    public static void brewing(RegisterBrewingRecipesEvent e) {
        for (Herb h : Herb.values()) e.getBuilder().addMix(Potions.AWKWARD, VerdureItems.HERBS.get(h).get(), BREWS.get(h));
    }
}
