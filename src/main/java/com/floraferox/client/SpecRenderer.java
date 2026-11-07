package com.floraferox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.floraferox.FloraFerox;
import com.floraferox.model.AnimState;
import java.util.function.BiConsumer;
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
 * (RenderTypes.eyes) that lights only the luminous parts painted in the {@code _glow} texture.
 */
public class SpecRenderer<T extends Mob> extends MobRenderer<T, SpecRenderState, SpecModel> {
    private static final float DEG = (float) Math.PI / 180F;
    private final Identifier texture;
    private final RenderType glow;
    private final float scale;
    private int minBlockLight;
    private final BiConsumer<T, AnimState> extractor;

    public SpecRenderer(EntityRendererProvider.Context ctx, SpecModel model, String name, float shadow, float scale, BiConsumer<T, AnimState> extractor) {
        super(ctx, model, shadow * scale);
        this.texture = FloraFerox.id("textures/entity/" + name + ".png");
        this.scale = scale;
        this.extractor = extractor;
        this.glow = RenderTypes.eyes(FloraFerox.id("textures/entity/" + name + "_glow.png"));
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
    protected RenderType glowType(SpecRenderState state) { return glow; }

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
    }

    @Override
    public Identifier getTextureLocation(SpecRenderState state) { return texture; }

    @Override
    protected void scale(SpecRenderState state, PoseStack pose) {
        pose.scale(scale, scale, scale);
    }
}
