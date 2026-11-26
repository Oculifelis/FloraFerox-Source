package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Wild;
import com.floraferox.client.SpecModel;
import com.floraferox.client.SpecRenderer;
import com.floraferox.client.render.ShotFxRenderer;
import com.fundamentum.thrown.ThrownWeaponRenderer;
import com.floraferox.model.AnimState;
import com.floraferox.model.ModelSpec;
import com.floraferox.model.Pose;
import com.floraferox.VerdureLooks.Look;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * The client: the mobs' renderers, each drawn with its model (VerdureModels, from tools/gen/verdure/game.py) and
 * VerdureAnimations; the shots' glow (ShotFxRenderer, from Ultima Ratio's fx); the Grafting Bench's screen; and the items' descriptions (lang item.*.descN).
 */
@EventBusSubscriber(modid = FloraFerox.MODID, value = Dist.CLIENT)
public final class VerdureClient {
    private VerdureClient() { }

    private static final String[] MOBS = { "pulse_gnat", "mire_ghast", "dusk_sprig", "moon_tick",
            "snap_drake", "reed_wretch", "lantern_leech", "burr_crawler", "orchid_mantis", "dew_crab", "bloomsprout", "glowgill", "dripleaf_toad", "coil_snail" };

    /** The shots' additive glow pipeline; without it the first shot drawn crashes the game. */
    @SubscribeEvent
    public static void pipelines(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(ShotFxRenderer.GLOW);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) {
        List<String> mobs = new ArrayList<>(List.of(MOBS));
        for (Wild w : Wild.values()) mobs.add(w.id);
        for (String mob : mobs) {
            ModelSpec spec = VerdureModels.get(mob);
            e.registerLayerDefinition(SpecRenderer.layer(mob), () -> SpecModel.createLayer(spec));
            // the looks with a shape of their own
            for (Look l : Looks.of(mob)) {
                if (!l.model()) continue;
                ModelSpec own = VerdureModels.get(mob + "/" + l.id());
                e.registerLayerDefinition(SpecRenderer.layer(mob + "/" + l.id()), () -> SpecModel.createLayer(own));
            }
        }
    }

    private static void grazer(EntityRenderersEvent.RegisterRenderers e, EntityType<CaveGrazer> type, String mob) {
        model(e, type, mob, (m, s) -> {
            brain(m.anim(), m.onGround(), s);
            if (m.snail()) s.clocks[0] = m.hidden(s.age - (float) Math.floor(s.age));   // how far it is in its shell
        }, key -> {
            VerdureAnimations.WildRig rig = new VerdureAnimations.WildRig(VerdureModels.get(key), key, CaveGrazer.TIMING, false, false);
            return (p, s) -> VerdureAnimations.wild(rig, p, s);
        }, 0.5F);
    }

    /** anim: a model's motion by its key (the mob, or "mob/look" for a look with its own shape). */
    private static <T extends Mob> void model(EntityRenderersEvent.RegisterRenderers e, EntityType<T> type, String mob,
                                              BiConsumer<T, AnimState> extract, Function<String, BiConsumer<Pose, AnimState>> anim, float shadow) {
        e.registerEntityRenderer(type, (EntityRendererProvider.Context ctx) -> new SpecRenderer<T>(ctx,
                new SpecModel(ctx.bakeLayer(SpecRenderer.layer(mob)), VerdureModels.get(mob), anim.apply(mob)), mob, shadow, 1.0F, extract,
                key -> new SpecModel(ctx.bakeLayer(SpecRenderer.layer(key)), VerdureModels.get(key), anim.apply(key))));
    }

    /** The brain's synced state (state * 1000 + ticks) into the animation's mode and action. */
    private static void brain(int anim, boolean grounded, AnimState s) {
        s.mode = anim / 1000;
        s.action = anim % 1000 + (s.age - (float) Math.floor(s.age));
        if (grounded) s.flags |= AnimState.FLAG_GROUNDED;
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        model(e, VerdureEntities.PULSE_GNAT.get(), "pulse_gnat", (m, s) -> brain(m.anim(), m.onGround(), s), key -> {
            ModelSpec fly = VerdureModels.get(key);
            VerdureAnimations.PulseGnatRig flyRig = new VerdureAnimations.PulseGnatRig(fly);
            VerdureAnimations.Baked flyBaked = VerdureAnimations.Baked.of(fly, key);
            return flyBaked != null ? (p, s) -> VerdureAnimations.pulseGnat(flyBaked, p, s) : (p, s) -> VerdureAnimations.pulseGnat(flyRig, p, s);
        }, 0.5F);
        model(e, VerdureEntities.MIRE_GHAST.get(), "mire_ghast", (m, s) -> brain(m.anim(), m.onGround(), s), key -> {
            ModelSpec ghoul = VerdureModels.get(key);
            VerdureAnimations.MireGhastRig ghoulRig = new VerdureAnimations.MireGhastRig(ghoul);
            VerdureAnimations.Baked ghoulBaked = VerdureAnimations.Baked.of(ghoul, key);
            return ghoulBaked != null ? (p, s) -> VerdureAnimations.mireGhast(ghoulBaked, p, s) : (p, s) -> VerdureAnimations.mireGhast(ghoulRig, p, s);
        }, 0.6F);
        model(e, VerdureEntities.DUSK_SPRIG.get(), "dusk_sprig", (m, s) -> brain(m.anim(), m.onGround(), s), key -> {
            ModelSpec sprout = VerdureModels.get(key);
            VerdureAnimations.DuskSprigRig sproutRig = new VerdureAnimations.DuskSprigRig(sprout);
            VerdureAnimations.Baked sproutBaked = VerdureAnimations.Baked.of(sprout, key);
            return sproutBaked != null ? (p, s) -> VerdureAnimations.duskSprig(sproutBaked, p, s) : (p, s) -> VerdureAnimations.duskSprig(sproutRig, p, s);
        }, 0.5F);
        model(e, VerdureEntities.MOON_TICK.get(), "moon_tick", (m, s) -> brain(m.anim(), m.onGround(), s), key -> {
            ModelSpec louse = VerdureModels.get(key);
            VerdureAnimations.MoonTickRig louseRig = new VerdureAnimations.MoonTickRig(louse);
            VerdureAnimations.Baked louseBaked = VerdureAnimations.Baked.of(louse, key);
            return louseBaked != null ? (p, s) -> VerdureAnimations.moonTick(louseBaked, p, s) : (p, s) -> VerdureAnimations.moonTick(louseRig, p, s);
        }, 0.9F);
        grazer(e, VerdureEntities.BLOOMSPROUT.get(), "bloomsprout");
        grazer(e, VerdureEntities.GLOWGILL.get(), "glowgill");
        grazer(e, VerdureEntities.DRIPLEAF_TOAD.get(), "dripleaf_toad");
        grazer(e, VerdureEntities.COIL_SNAIL.get(), "coil_snail");
        for (Ferox f : Ferox.values()) ferox(e, VerdureEntities.ferox(f), f);
        for (Wild w : Wild.values()) wild(e, VerdureEntities.wild(w), w);
        e.registerEntityRenderer(VerdureEntities.SHOT.get(), ShotFxRenderer::new);
        e.registerEntityRenderer(VerdureEntities.RUNED_SEED.get(), c -> new ThrownWeaponRenderer<>(c, "tumble", 24F));
        e.registerEntityRenderer(VerdureEntities.SPORE_FLASK.get(), c -> new ThrownWeaponRenderer<>(c, "tumble", 20F));
        e.registerEntityRenderer(VerdureEntities.THORN_JAVELIN.get(), c -> new ThrownWeaponRenderer<>(c, "none", 0F));
    }

    /** 1.2's six: their sheets' own motion, baked as the wild mobs' is; their shadows by their size. */
    private static <T extends FeroxMob> void ferox(EntityRenderersEvent.RegisterRenderers e, EntityType<T> type, Ferox f) {
        float shadow = switch (f) {
            case SNAP_DRAKE, BURR_CRAWLER -> 0.6F;
            case DEW_CRAB -> 0.8F;
            case LANTERN_LEECH -> 0.4F;
            default -> 0.5F;
        };
        model(e, type, f.id, (m, s) -> brain(m.anim(), m.onGround(), s), key -> {
            VerdureAnimations.WildRig rig = new VerdureAnimations.WildRig(VerdureModels.get(key), key, f, f == Ferox.LANTERN_LEECH, false);
            return (p, s) -> VerdureAnimations.wild(rig, p, s);
        }, shadow);
    }

    /** 1.4's wild mobs: their shadows by their hitbox. */
    private static <T extends WildMob> void wild(EntityRenderersEvent.RegisterRenderers e, EntityType<T> type, Wild w) {
        model(e, type, w.id, (m, s) -> {
            brain(m.anim(), m.onGround(), s);
            if (w.is(VerdureRules.HOP)) s.extra = m.hop.phase(m.onGround(), s.age);   // where it is in its hop
            if (m instanceof ThornMatriarch tm) {                // her phase looks: the crest bloom, the wings, the brood flaps
                float partial = s.age - (float) Math.floor(s.age);
                s.clocks[0] = tm.crest(partial);
                s.clocks[1] = tm.wings(partial);
                s.clocks[2] = tm.brood(partial);
                s.stage = tm.stage();                            // and her phase texture
            }
            if (m instanceof BriarKing bk) {                    // his phase looks: the crown in flower, the roses open
                float partial = s.age - (float) Math.floor(s.age);
                s.clocks[0] = bk.crown(partial);
                s.clocks[1] = bk.roses(partial);
            }
            if (m instanceof CrimsonBehemoth cb) {               // its last phase's cracks, and their flare
                float partial = s.age - (float) Math.floor(s.age);
                s.clocks[2] = cb.cracks(partial);
                s.clocks[3] = cb.flare(partial);
                s.stage = cb.stage();
            }
        }, key -> {
            VerdureAnimations.WildRig rig = new VerdureAnimations.WildRig(VerdureModels.get(key), key, w, w.move != VerdureRules.Move.GROUND,
                    w.is(VerdureRules.HOP));
            return (p, s) -> VerdureAnimations.wild(rig, p, s);
        }, Math.min(1.5F, w.width * 0.5F));
    }

    /** The Grafting Bench's screen: tools/gen/verdure/art.py draws it (art.gui, art.BOTANY_SLOTS). */
    public static final class GraftingScreen extends ItemCombinerScreen<GraftingMenu> {
        private static final Identifier TEXTURE = FloraFerox.id("textures/gui/container/grafting_bench.png");

        public GraftingScreen(GraftingMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title, TEXTURE);
        }

        @Override
        protected void extractErrorIcon(GuiGraphicsExtractor graphics, int xo, int yo) { }
    }

    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent e) {
        e.register(VerdureBlocks.GRAFTING_MENU.get(), GraftingScreen::new);
    }

    /**
     * Each of our items' description lines (item.floraferox.ID.tip0, tip1, ...; not descN, which Fundamentum 1.22.1 shows itself), grey under its name; a herb's or its
     * seeds' botanical name (item.floraferox.ID.latin) first, in green italics like a label.
     */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent e) {
        var key = e.getItemStack().getItem().getDescriptionId();
        if (!key.startsWith("item." + FloraFerox.MODID + ".") && !key.startsWith("block." + FloraFerox.MODID + ".")) return;
        String base = "item." + FloraFerox.MODID + "." + e.getItemStack().typeHolder().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        Language lang = Language.getInstance();
        int at = 1;
        if (lang.has(base + ".latin")) {
            e.getToolTip().add(Math.min(at++, e.getToolTip().size()),
                    Component.translatable(base + ".latin").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC));
        }
        for (int i = 0; lang.has(base + ".tip" + i); i++) {
            e.getToolTip().add(Math.min(at++, e.getToolTip().size()), Component.translatable(base + ".tip" + i).withStyle(ChatFormatting.GRAY));
        }
    }
}
