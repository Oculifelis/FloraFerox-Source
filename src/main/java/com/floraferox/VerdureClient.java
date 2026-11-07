package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Wild;
import com.floraferox.client.SpecModel;
import com.floraferox.client.SpecRenderer;
import com.floraferox.client.render.ShotFxRenderer;
import com.floraferox.model.AnimState;
import com.floraferox.model.ModelSpec;
import com.floraferox.model.Pose;
import java.util.function.BiConsumer;
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
            "snap_drake", "reed_wretch", "lantern_leech", "burr_crawler", "orchid_mantis", "dew_crab" };

    /** The shots' additive glow pipeline; without it the first shot drawn crashes the game. */
    @SubscribeEvent
    public static void pipelines(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(ShotFxRenderer.GLOW);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) {
        for (String mob : MOBS) {
            ModelSpec spec = VerdureModels.get(mob);
            e.registerLayerDefinition(SpecRenderer.layer(mob), () -> SpecModel.createLayer(spec));
        }
        for (Wild w : Wild.values()) {
            ModelSpec spec = VerdureModels.get(w.id);
            e.registerLayerDefinition(SpecRenderer.layer(w.id), () -> SpecModel.createLayer(spec));
        }
    }

    private static <T extends Mob> void model(EntityRenderersEvent.RegisterRenderers e, EntityType<T> type, String mob, ModelSpec spec,
                                              BiConsumer<T, AnimState> extract, BiConsumer<Pose, AnimState> anim, float shadow) {
        e.registerEntityRenderer(type, (EntityRendererProvider.Context ctx) -> new SpecRenderer<T>(ctx,
                new SpecModel(ctx.bakeLayer(SpecRenderer.layer(mob)), spec, anim), mob, shadow, 1.0F, extract));
    }

    /** The brain's synced state (state * 1000 + ticks) into the animation's mode and action. */
    private static void brain(int anim, boolean grounded, AnimState s) {
        s.mode = anim / 1000;
        s.action = anim % 1000 + (s.age - (float) Math.floor(s.age));
        if (grounded) s.flags |= AnimState.FLAG_GROUNDED;
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        ModelSpec fly = VerdureModels.get("pulse_gnat");
        VerdureAnimations.PulseGnatRig flyRig = new VerdureAnimations.PulseGnatRig(fly);
        model(e, VerdureEntities.PULSE_GNAT.get(), "pulse_gnat", fly, (m, s) -> brain(m.anim(), m.onGround(), s), (p, s) -> VerdureAnimations.pulseGnat(flyRig, p, s), 0.5F);
        ModelSpec ghoul = VerdureModels.get("mire_ghast");
        VerdureAnimations.MireGhastRig ghoulRig = new VerdureAnimations.MireGhastRig(ghoul);
        model(e, VerdureEntities.MIRE_GHAST.get(), "mire_ghast", ghoul, (m, s) -> brain(m.anim(), m.onGround(), s), (p, s) -> VerdureAnimations.mireGhast(ghoulRig, p, s), 0.6F);
        ModelSpec sprout = VerdureModels.get("dusk_sprig");
        VerdureAnimations.DuskSprigRig sproutRig = new VerdureAnimations.DuskSprigRig(sprout);
        model(e, VerdureEntities.DUSK_SPRIG.get(), "dusk_sprig", sprout, (m, s) -> brain(m.anim(), m.onGround(), s), (p, s) -> VerdureAnimations.duskSprig(sproutRig, p, s), 0.5F);
        ModelSpec louse = VerdureModels.get("moon_tick");
        VerdureAnimations.MoonTickRig louseRig = new VerdureAnimations.MoonTickRig(louse);
        model(e, VerdureEntities.MOON_TICK.get(), "moon_tick", louse, (m, s) -> brain(m.anim(), m.onGround(), s), (p, s) -> VerdureAnimations.moonTick(louseRig, p, s), 0.9F);
        for (Ferox f : Ferox.values()) ferox(e, VerdureEntities.ferox(f), f);
        for (Wild w : Wild.values()) wild(e, VerdureEntities.wild(w), w);
        e.registerEntityRenderer(VerdureEntities.SHOT.get(), ShotFxRenderer::new);
    }

    /** 1.2's six: their sheets' own motion, baked as the wild mobs' is; their shadows by their size. */
    private static <T extends FeroxMob> void ferox(EntityRenderersEvent.RegisterRenderers e, EntityType<T> type, Ferox f) {
        ModelSpec spec = VerdureModels.get(f.id);
        VerdureAnimations.WildRig rig = new VerdureAnimations.WildRig(spec, f.id, f, f == Ferox.LANTERN_LEECH, false);
        float shadow = switch (f) {
            case SNAP_DRAKE, BURR_CRAWLER -> 0.6F;
            case DEW_CRAB -> 0.8F;
            case LANTERN_LEECH -> 0.4F;
            default -> 0.5F;
        };
        model(e, type, f.id, spec, (m, s) -> brain(m.anim(), m.onGround(), s), (p, s) -> VerdureAnimations.wild(rig, p, s), shadow);
    }

    /** 1.4's wild mobs: their shadows by their hitbox. */
    private static <T extends WildMob> void wild(EntityRenderersEvent.RegisterRenderers e, EntityType<T> type, Wild w) {
        ModelSpec spec = VerdureModels.get(w.id);
        VerdureAnimations.WildRig rig = new VerdureAnimations.WildRig(spec, w);
        model(e, type, w.id, spec, (m, s) -> {
            brain(m.anim(), m.onGround(), s);
            if (w.is(VerdureRules.HOP)) s.extra = m.hop.phase(m.onGround(), s.age);   // where it is in its hop
        }, (p, s) -> VerdureAnimations.wild(rig, p, s),
                Math.min(1.5F, w.width * 0.5F));
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
