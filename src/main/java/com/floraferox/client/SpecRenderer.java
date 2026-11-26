package com.floraferox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.floraferox.FloraFerox;
import com.floraferox.Looks;
import com.floraferox.VerdureLooks.Look;
import com.floraferox.VerdureModels;
import com.floraferox.model.AnimState;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/**
 * Generic renderer for every Flora Ferox mob: cutout base texture plus an additive, fullbright glow layer
 * (RenderTypes.eyes) that lights only the luminous parts painted in the {@code _glow} texture. Each of the mob's looks
 * (Looks, docs/DESIGN.md section 12) has its own texture and glow, and some their own model.
 */
public class SpecRenderer<T extends Mob> extends MobRenderer<T, SpecRenderState, SpecModel> {
    private static final float DEG = (float) Math.PI / 180F;
    private final Identifier texture;
    private final RenderType glow;
    private final SpecModel base;
    private final Identifier[][] textures;                // [phase - 1][look]: a boss's paint changes with its fight
    private final RenderType[][] glows;
    private final SpecModel[] models;
    private final Map<String, Integer> index = new HashMap<>();
    private final float scale;
    private int minBlockLight;
    private final BiConsumer<T, AnimState> extractor;

    /** own: a look's own model by its key ("mob/look"). */
    public SpecRenderer(EntityRendererProvider.Context ctx, SpecModel model, String name, float shadow, float scale, BiConsumer<T, AnimState> extractor,
                        Function<String, SpecModel> own) {
        super(ctx, model, shadow * scale);
        this.texture = FloraFerox.id("textures/entity/" + name + ".png");
        this.scale = scale;
        this.extractor = extractor;
        this.glow = RenderTypes.eyes(FloraFerox.id("textures/entity/" + name + "_glow.png"));
        this.base = model;
        Look[] looks = Looks.of(name);
        int phases = VerdureModels.phases(name);
        textures = new Identifier[phases][looks.length];
        glows = new RenderType[phases][looks.length];
        models = new SpecModel[looks.length];
        for (int i = 0; i < looks.length; i++) {
            String id = looks[i].id();
            index.put(id, i);
            textures[0][i] = i == 0 ? texture : FloraFerox.id("textures/entity/" + name + "/" + id + ".png");
            glows[0][i] = i == 0 ? glow : RenderTypes.eyes(FloraFerox.id("textures/entity/" + name + "/" + id + "_glow.png"));
            for (int n = 2; n <= phases; n++) {                // <name>_phase<n>.png, <name>/<look>_phase<n>.png
                String stem = "textures/entity/" + name + (i == 0 ? "" : "/" + id) + "_phase" + n;
                textures[n - 1][i] = FloraFerox.id(stem + ".png");
                glows[n - 1][i] = RenderTypes.eyes(FloraFerox.id(stem + "_glow.png"));
            }
            models[i] = looks[i].model() ? own.apply(name + "/" + id) : model;
        }
        addLayer(new EyesLayer<>(this) {
            @Override
            public void submit(PoseStack pose, SubmitNodeCollector collector, int light, SpecRenderState state, float yRot, float xRot) {
                if (state.isInvisible) return;
                // the glow's brightness comes from the animation (a pulse, a fade in death): tint the additive pass
                int g = Math.round(Math.max(0F, Math.min(1F, state.anim.glow)) * 255F);
                if (g == 0) return;
                collector.order(1).submitModel(getParentModel(), state, pose, glowType(state), light, OverlayTexture.NO_OVERLAY,
                        0xFF000000 | g << 16 | g << 8 | g, null, state.outlineColor, null);
            }

            @Override
            public RenderType renderType() { return glow; }
        });
    }

    /** The emissive pass; a creature with several forms picks one per render state. */
    protected RenderType glowType(SpecRenderState state) { return glows[phase(state)][state.look]; }

    /** Its paint's phase (0-based) for this render state: the animation's stage (1 when it has none), as far as it has. */
    private int phase(SpecRenderState state) { return Math.max(0, Math.min(textures.length, state.anim.stage) - 1); }

    /** Never render darker than this block light (small night flyers must stay readable). */
    public SpecRenderer<T> minBlockLight(int light) {
        this.minBlockLight = light;
        return this;
    }

    @Override
    protected int getBlockLightLevel(T entity, net.minecraft.core.BlockPos pos) {
        return Math.max(minBlockLight, super.getBlockLightLevel(entity, pos));
    }

    public static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(FloraFerox.id(name), "main");
    }

    @Override
    public SpecRenderState createRenderState() { return new SpecRenderState(); }

    @Override
    public void extractRenderState(T entity, SpecRenderState state, float partial) {
        super.extractRenderState(entity, state, partial);
        AnimState a = state.anim;
        a.reset();
        a.age = state.ageInTicks;
        a.limbSwing = state.walkAnimationPos;
        a.limbAmount = state.walkAnimationSpeed;
        a.headYaw = state.yRot * DEG;
        a.headPitch = state.xRot * DEG;
        extractor.accept(entity, a);
        state.look = index.getOrDefault(entity.getData(Looks.LOOK), 0);
    }

    @Override
    public void submit(SpecRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        this.model = models[state.look];
        super.submit(state, pose, collector, camera);
        this.model = base;
    }

    @Override
    public Identifier getTextureLocation(SpecRenderState state) { return textures[phase(state)][state.look]; }

    @Override
    protected void scale(SpecRenderState state, PoseStack pose) {
        float s = scale * state.ageScale;                      // a baby grazer at half size
        pose.scale(s, s, s);
    }
}
