package com.floraferox.client;

import com.floraferox.FloraFerox;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 1.11, a seed gun's hold and kick, read from assets/floraferox/gun_anim/&lt;gun&gt;.json (tools/gen, art.gun_anim), a
 * light version of Ultima Ratio's gun_anim (Oculifelis, 2026-11-04: "held right", one handed, "shooting animations like
 * UR guns have kinda"). Each shot turns the gun muzzle-up about its grip and slides it back along the barrel, snapping
 * in over kick_ticks and settling (quartic) over recover_ticks; in first person the kick is put on the hand, so the arm
 * Fundamentum draws on the grip (FirstPersonHands, "gun") recoils with it. In third person the gun is held one handed
 * ({@link #PISTOL}, Fundamentum's pistol hold) and the arm rises arm_rise degrees with the kick.
 */
public final class GunAnim {
    /** The third-person arm's kick, 0 to arm_rise degrees, set on the local player's render state (GunHandling). */
    public static final ContextKey<Float> ARM_KICK = new ContextKey<>(FloraFerox.id("gun_arm_kick"));
    /** One arm up along the head's look, the other hand free; the arm kicks up with each shot. */
    public static final EnumProxy<HumanoidModel.ArmPose> PISTOL = new EnumProxy<>(HumanoidModel.ArmPose.class, false, false, (IArmPoseTransformer) GunAnim::pistol);

    private static final Map<String, Optional<GunAnim>> CACHE = new ConcurrentHashMap<>();

    final Vector3f grip, gripTop;
    final float[] fpDisplay;
    final float armPitch, armYaw;
    final float rise, kick, kickTicks, recoverTicks, armRise;

    private GunAnim(JsonObject o) {
        this.grip = vec(o.getAsJsonArray("grip"));
        this.gripTop = vec(o.getAsJsonArray("grip_top"));
        JsonArray d = o.getAsJsonArray("fp_display");
        this.fpDisplay = new float[9];
        for (int i = 0; i < 9; i++) this.fpDisplay[i] = d.get(i).getAsFloat();
        JsonArray arm = o.getAsJsonArray("arm");
        this.armPitch = arm.get(0).getAsFloat();
        this.armYaw = arm.get(1).getAsFloat();
        JsonObject r = o.getAsJsonObject("recoil");
        this.rise = r.get("rise").getAsFloat();
        this.kick = r.get("kick").getAsFloat();
        this.kickTicks = r.get("kick_ticks").getAsFloat();
        this.recoverTicks = r.get("recover_ticks").getAsFloat();
        this.armRise = r.get("arm_rise").getAsFloat();
    }

    /** The gun's animation by item id, or null if it has none; read once, again after a resource reload. */
    public static @Nullable GunAnim get(String id) {
        return CACHE.computeIfAbsent(id, GunAnim::load).orElse(null);
    }

    public static void reset() {
        CACHE.clear();
    }

    private static Optional<GunAnim> load(String id) {
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(FloraFerox.id("gun_anim/" + id + ".json"));
        if (res.isEmpty()) return Optional.empty();
        try (Reader in = res.get().openAsReader()) {
            return Optional.of(new GunAnim(JsonParser.parseReader(in).getAsJsonObject()));
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("gun_anim/{}.json: {}", id, e.toString());
            return Optional.empty();
        }
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    /** How far into the kick the gun is, {@code t} ticks after the shot: a sine snap in, then a quartic ease out. */
    public float kickAmount(float t) {
        if (t < 0.0F) return 0.0F;
        if (t < this.kickTicks) return (float) Math.sin(t / this.kickTicks * Math.PI / 2.0);
        float r = (t - this.kickTicks) / this.recoverTicks;
        if (r >= 1.0F) return 0.0F;
        float q = 1.0F - r;
        return q * q * q * q;
    }

    /**
     * The kick about the grip (muzzle up, slid back) carried through the model's first-person display (D K D^-1), put on
     * the first-person hand so the item and the arm drawn from the same pose recoil together. Left hands mirror it.
     */
    public void applyHandKick(PoseStack pose, float t, boolean left) {
        float a = kickAmount(t);
        if (a <= 0.0F) return;
        Matrix4f m = displayMatrix(left);
        Matrix4f k = new Matrix4f()
            .translate(this.grip.x / 16.0F, this.grip.y / 16.0F, this.grip.z / 16.0F)
            .rotateZ((float) Math.toRadians(this.rise * a))
            .translate(-this.grip.x / 16.0F, -this.grip.y / 16.0F, -this.grip.z / 16.0F)
            .translate(-this.kick * a / 16.0F, 0.0F, 0.0F);
        Matrix4f inv = new Matrix4f(m).invert();
        pose.mulPose(m.mul(k).mul(inv));
    }

    /**
     * Puts {@code pose} into the grip's frame for Fundamentum's "gun" fist: at the handle's top, its x the gun's z, its y
     * the barrel, its z the gun's up (Ultima Ratio's GunAnim.applyGripFrame).
     */
    public void applyGripFrame(PoseStack pose, boolean left) {
        float s = left ? -1.0F : 1.0F;
        Vector3f p = displayMatrix(left).transformPosition(new Vector3f(this.gripTop).div(16.0F));
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(this.fpDisplay[3]), (float) Math.toRadians(s * this.fpDisplay[4]),
            (float) Math.toRadians(s * this.fpDisplay[5])));
        pose.mulPose(new Matrix4f(0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F));
    }

    /** The model's first-person display transform, as ItemTransform.apply makes it, with the -0.5 centring. */
    private Matrix4f displayMatrix(boolean left) {
        float[] d = this.fpDisplay;
        float s = left ? -1.0F : 1.0F;
        return new Matrix4f()
            .translate(s * d[0] / 16.0F, d[1] / 16.0F, d[2] / 16.0F)
            .rotateXYZ((float) Math.toRadians(d[3]), (float) Math.toRadians(s * d[4]), (float) Math.toRadians(s * d[5]))
            .scale(d[6], d[7], d[8])
            .translate(-0.5F, -0.5F, -0.5F);
    }

    /** Fundamentum's pistol hold (one arm along the look) with the shot's kick lifting it. */
    private static void pistol(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm) {
        ModelPart a = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        Float kick = state.getRenderData(ARM_KICK);
        a.xRot = model.head.xRot - Mth.HALF_PI - (kick == null ? 0.0F : kick * Mth.DEG_TO_RAD);
        a.yRot = model.head.yRot + (arm == HumanoidArm.RIGHT ? -0.08F : 0.08F);
        a.zRot = 0.0F;
    }
}
