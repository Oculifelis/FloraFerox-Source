package com.floraferox.client.render;

import com.floraferox.Seedshot;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Flora Ferox's shots drawn as glowing effects instead of flat item sprites, ported from Ultima Ratio's projectile looks
 * (Ultima Ratio, Oculifelis): additive, full-bright, double-sided quads.
 * <ul>
 * <li>Arcs (the cleaver's slash): UR's flipbook crescent (FluxSlash), sweeping open over its first ticks, its convex
 * cutting edge leading, tilted toward the eye so it reads from behind, with a soft halo at its heart.</li>
 * <li>Motes (the sceptre's petal bolt): UR's glow bolt (a white-hot core in a pulsing halo with a short tapering trail)
 * with a petal spinning in it.</li>
 * <li>Darts (the seed gun's seed): UR's dart streak, a bright sliver along its flight with a white core, a small halo
 * and a glowing seed at its head.</li>
 * <li>Hearts (1.11, the seed gun's plain shot, Oculifelis 2026-11-04: "i liked when they shot a heart shaped shot"):
 * the heart that spins in a Bloom Shot or Heartmend mote, held upright and rocking a little, in its halo, with the
 * dart's streak behind it.</li>
 * </ul>
 * Which look a shot takes comes from its sprite item's id (VerdureItems.SHOTS); textures are in
 * textures/entity/shot/. Register {@link #GLOW} in RegisterRenderPipelinesEvent.
 */
public class ShotFxRenderer extends EntityRenderer<Seedshot, ShotFxRenderer.State> {
    public static final RenderPipeline GLOW = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("floraferox", "pipeline/shot_glow"))
        .withVertexShader("core/entity")
        .withFragmentShader("core/entity")
        .withShaderDefine("EMISSIVE")
        .withShaderDefine("NO_OVERLAY")
        .withShaderDefine("NO_CARDINAL_LIGHTING")
        .withSampler("Sampler0")
        .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
        .build();

    private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();
    private static final int FULL_BRIGHT = 15728880;
    private static final int ARC = 0, MOTE = 1, DART = 2, HEART = 3;
    private static final int FRAMES = 8;

    /** A shot's look: its kind, colours (main, second) and size; an arc's flipbook is its id. */
    record Look(int kind, int color, int color2, float size) {}

    private static final Map<String, Look> LOOKS = Map.of(
        "mire_arc", new Look(ARC, 0x84E25C, 0xECFFD6, 0.32F),
        "flare_arc", new Look(ARC, 0xFFA02C, 0xFFF4CE, 0.32F),
        "rime_arc", new Look(ARC, 0x96E2FF, 0xF2FCFF, 0.32F),
        "dusk_mote", new Look(MOTE, 0xFFC27A, 0xFF8A70, 0.17F),
        "ember_mote", new Look(MOTE, 0xFF9A2E, 0xE04A14, 0.17F),
        "rime_mote", new Look(MOTE, 0xC8F0FF, 0x6FB8F0, 0.17F),
        "pulse_dart", new Look(HEART, 0xFF5AB4, 0xFFB0E0, 0.16F),
        "flare_dart", new Look(HEART, 0xFFB040, 0xFF5A14, 0.16F),
        "rime_dart", new Look(HEART, 0xB8F0FF, 0x5EA8E8, 0.16F));
    private static final Look FALLBACK = new Look(MOTE, 0xFFFFFF, 0xC0FFC0, 0.15F);
    /** 1.8: a seed gun's Bloom Shot, by its dart: a big magenta pulse, a fire bomb, an ice shard. */
    private static final Map<String, Look> BLOOMS = Map.of(
        "pulse_dart", new Look(MOTE, 0xFF5AB4, 0xFFB0E0, 0.42F),
        "flare_dart", new Look(MOTE, 0xFFA02C, 0xFF5A14, 0.42F),
        "rime_dart", new Look(DART, 0xB8F0FF, 0x5EA8E8, 0.34F));
    /** 1.8: a Heartmend shot, which heals. */
    private static final Look MEND = new Look(MOTE, 0x7CFF9A, 0xFF8AC8, 0.2F);

    public static class State extends EntityRenderState {
        public String shot = "";
        public Look look = FALLBACK;
        public Vec3 velocity = Vec3.ZERO;
        public float age;
        public int seed;
    }

    public ShotFxRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(Seedshot e, State s, float pt) {
        super.extractRenderState(e, s, pt);
        s.shot = BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).getPath();
        s.look = switch (e.mode()) {
            case Seedshot.BLOOM -> BLOOMS.getOrDefault(s.shot, FALLBACK);
            case Seedshot.MEND -> MEND;
            default -> LOOKS.getOrDefault(s.shot, FALLBACK);
        };
        s.velocity = e.getDeltaMovement();
        s.age = e.tickCount + pt;
        s.seed = e.getId();
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.0F, s.boundingBoxHeight * 0.5F, 0.0F);
        switch (s.look.kind) {
            case ARC -> this.arc(s, pose, collector, camera);
            case DART -> this.dart(s, pose, collector, camera);
            case HEART -> this.heart(s, pose, collector, camera);
            default -> this.mote(s, pose, collector, camera);
        }
        pose.popPose();
        super.submit(s, pose, collector, camera);
    }

    // ---------------------------------------------------------------------------------------------------- the looks

    /** UR's FluxSlash: the crescent flipbook laid in the plane of the cut, sweeping open, then held while it flies. */
    private void arc(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Look l = s.look;
        this.billboard(pose, collector, camera, tex("halo"), l.size * 2.2F, argb(70, l.color), 0.0F);
        pose.pushPose();
        Vec3 v = s.velocity.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : s.velocity.normalize();
        pose.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(Math.atan2(v.x, v.z))));
        pose.mulPose(Axis.XP.rotationDegrees((float) -Math.toDegrees(Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z)))));
        // tilted up off the flight line so the shooter, behind it, sees its face rather than its edge
        pose.mulPose(Axis.XP.rotationDegrees(-28.0F));
        // the sweep plays over the first four ticks, then the full crescent holds (frame 3) with a faint shimmer
        int f = s.age < 4.0F ? (int) s.age : 3;
        float v0 = f / (float) FRAMES, v1 = (f + 1) / (float) FRAMES;
        float hw = l.size * (2.2F + Math.min(0.6F, s.age * 0.05F));
        float hh = hw * 0.5F;
        int a = (int) (235 + 20 * Mth.sin(s.age * 1.3F));
        int white = argb(a, 0xFFFFFF);
        collector.submitCustomGeometry(pose, glow(tex(s.shot)), (p, b) -> {
            v(b, p, -hw, 0.0F, hh, 0.0F, v0, white);
            v(b, p, hw, 0.0F, hh, 1.0F, v0, white);
            v(b, p, hw, 0.0F, -hh, 1.0F, v1, white);
            v(b, p, -hw, 0.0F, -hh, 0.0F, v1, white);
        });
        pose.popPose();
    }

    /** UR's glow bolt with a petal turning in it. */
    private void mote(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Look l = s.look;
        float pulse = 1.0F + 0.12F * Mth.sin(s.age * 1.7F);
        Vec3 side = side(s, camera);
        if (side != null) {
            Vec3 v = s.velocity.normalize();
            float len = Math.min(1.1F, 0.35F + (float) s.velocity.length() * 0.22F);
            Vector3f a = new Vector3f((float) (-v.x * len), (float) (-v.y * len), (float) (-v.z * len));
            Vector3f c = new Vector3f();
            Vector3f wide = new Vector3f((float) side.x, (float) side.y, (float) side.z).mul(l.size * 1.1F);
            Vector3f thin = new Vector3f(wide).mul(0.45F);
            collector.submitCustomGeometry(pose, glow(tex("streak")), (p, b) -> {
                ribbon(b, p, a, c, wide, 0.0F, 1.0F, argb(150, l.color2));
                ribbon(b, p, a, c, thin, 0.0F, 1.0F, argb(220, l.color));
            });
        }
        float spin = s.age * 14.0F + s.seed * 47.0F;
        this.billboard(pose, collector, camera, tex("halo"), l.size * 3.4F, argb(80, l.color2), 0.0F);
        this.billboard(pose, collector, camera, tex("halo"), l.size * 2.0F * pulse, argb(170, l.color), 0.0F);
        this.billboard(pose, collector, camera, tex("petal"), l.size * 1.6F, argb(255, l.color), spin);
        this.billboard(pose, collector, camera, tex("core"), l.size * 0.55F, argb(245, 0xFFFFFF), 0.0F);
    }

    /** UR's dart streak: a sliver of light along the flight, white at its core, a glowing seed at its head. */
    private void dart(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Look l = s.look;
        Vec3 side = side(s, camera);
        if (side != null) {
            Vec3 v = s.velocity.normalize();
            float len = 0.32F + l.size * 1.4F + Math.min(0.5F, (float) s.velocity.length() * 0.2F);
            Vector3f a = new Vector3f((float) (-v.x * len), (float) (-v.y * len), (float) (-v.z * len));
            Vector3f c = new Vector3f((float) (v.x * len * 0.25F), (float) (v.y * len * 0.25F), (float) (v.z * len * 0.25F));
            Vector3f wide = new Vector3f((float) side.x, (float) side.y, (float) side.z).mul(l.size * 0.9F);
            Vector3f thin = new Vector3f(wide).mul(0.35F);
            collector.submitCustomGeometry(pose, glow(tex("streak")), (p, b) -> {
                ribbon(b, p, a, c, wide, 0.0F, 1.0F, argb(200, l.color));
                ribbon(b, p, a, c, thin, 0.0F, 1.0F, argb(255, l.color2));
            });
        }
        this.billboard(pose, collector, camera, tex("halo"), l.size * 1.8F, argb(120, l.color), 0.0F);
        this.billboard(pose, collector, camera, tex("core"), l.size * 0.8F, argb(255, l.color2), 0.0F);
        this.billboard(pose, collector, camera, tex("core"), l.size * 0.4F, argb(240, 0xFFFFFF), 0.0F);
    }

    /** A glowing heart, upright and rocking, in its halo, with a short streak behind it. */
    private void heart(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Look l = s.look;
        Vec3 side = side(s, camera);
        if (side != null) {
            Vec3 v = s.velocity.normalize();
            float len = 0.3F + Math.min(0.45F, (float) s.velocity.length() * 0.18F);
            Vector3f a = new Vector3f((float) (-v.x * len), (float) (-v.y * len), (float) (-v.z * len));
            Vector3f c = new Vector3f();
            Vector3f wide = new Vector3f((float) side.x, (float) side.y, (float) side.z).mul(l.size * 0.6F);
            Vector3f thin = new Vector3f(wide).mul(0.4F);
            collector.submitCustomGeometry(pose, glow(tex("streak")), (p, b) -> {
                ribbon(b, p, a, c, wide, 0.0F, 1.0F, argb(140, l.color));
                ribbon(b, p, a, c, thin, 0.0F, 1.0F, argb(220, l.color2));
            });
        }
        float pulse = 1.0F + 0.1F * Mth.sin(s.age * 1.9F);
        float rock = 14.0F * Mth.sin(s.age * 0.9F + s.seed);
        this.billboard(pose, collector, camera, tex("halo"), l.size * 2.6F, argb(90, l.color), 0.0F);
        this.billboard(pose, collector, camera, tex("petal"), l.size * 1.5F * pulse, argb(255, l.color), rock);
        this.billboard(pose, collector, camera, tex("petal"), l.size * 0.8F * pulse, argb(220, l.color2), rock);
        this.billboard(pose, collector, camera, tex("core"), l.size * 0.35F, argb(230, 0xFFFFFF), 0.0F);
    }

    // ------------------------------------------------------------------------------------------------- the drawing

    /** Across the flight as the camera sees it, or null for a shot standing still or flying straight at the eye. */
    private static Vec3 side(State s, CameraRenderState camera) {
        if (s.velocity.lengthSqr() < 1.0E-4) {
            return null;
        }
        Vec3 toCam = camera.pos.subtract(s.x, s.y + s.boundingBoxHeight * 0.5, s.z).normalize();
        Vec3 side = s.velocity.normalize().cross(toCam);
        return side.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : side.normalize();
    }

    private void billboard(PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, Identifier tex, float r, int argb, float roll) {
        pose.pushPose();
        pose.mulPose(camera.orientation);
        if (roll != 0.0F) {
            pose.mulPose(Axis.ZP.rotationDegrees(roll));
        }
        collector.submitCustomGeometry(pose, glow(tex), (p, b) -> {
            v(b, p, -r, -r, 0, 0, 1, argb);
            v(b, p, r, -r, 0, 1, 1, argb);
            v(b, p, r, r, 0, 1, 0, argb);
            v(b, p, -r, r, 0, 0, 0, argb);
        });
        pose.popPose();
    }

    public static RenderType glow(Identifier texture) {
        return TYPES.computeIfAbsent(texture, t -> RenderType.create("floraferox_shot_glow", RenderSetup.builder(GLOW).withTexture("Sampler0", t).sortOnUpload().createRenderSetup()));
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("floraferox", "textures/entity/shot/" + name + ".png");
    }

    private static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static void ribbon(VertexConsumer b, PoseStack.Pose p, Vector3f a, Vector3f c, Vector3f side, float u0, float u1, int argb) {
        v(b, p, a.x - side.x, a.y - side.y, a.z - side.z, u0, 1, argb);
        v(b, p, c.x - side.x, c.y - side.y, c.z - side.z, u1, 1, argb);
        v(b, p, c.x + side.x, c.y + side.y, c.z + side.z, u1, 0, argb);
        v(b, p, a.x + side.x, a.y + side.y, a.z + side.z, u0, 0, argb);
    }

    private static void v(VertexConsumer b, PoseStack.Pose p, float x, float y, float z, float u, float v, int argb) {
        b.addVertex(p, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(p, 0.0F, 1.0F, 0.0F);
    }
}
