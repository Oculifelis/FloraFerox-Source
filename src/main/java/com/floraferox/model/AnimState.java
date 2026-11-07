package com.floraferox.model;

/**
 * Plain animation inputs, filled by each target from its entity (1.20.1/1.21.1) or render state (26.1).
 * Meaning of the generic slots is documented per creature in {@link CreatureAnimations}.
 */
public final class AnimState {
    public float age;
    public float limbSwing;
    public float limbAmount;
    /** radians */
    public float headYaw;
    /** radians */
    public float headPitch;
    /** 0..1 progress of the current attack/action animation, 0 when idle */
    public float action;
    /** creature-specific state value (phase, extinguished candles, ...) */
    public int mode;
    /** creature-specific flags */
    public int flags;
    public float extra;
    /** Seconds since each of up to four one-shot animations started, or -1 when not playing (creature-specific). */
    public final float[] clocks = { -1f, -1f, -1f, -1f };
    /** Brightness of the emissive glow layer, 0..1 (pulses, fades on death). */
    public float glow = 1f;
    /** Where the creature's target is, relative to its body (radians): an eye's pupil or weapon turns toward it. */
    public float lookYaw, lookPitch;

    public static final int FLAG_TAMED = 1;
    public static final int FLAG_SITTING = 2;
    public static final int FLAG_GROUNDED = 4;
    public static final int FLAG_FEEDING = 8;
    public static final int FLAG_IN_WATER = 16;
    public static final int FLAG_SHIELDED = 32;
    public static final int FLAG_INHALING = 64;
    public static final int FLAG_DYING = 128;
    /** The creature's second form (Exsomnis's maw). */
    public static final int FLAG_SECOND_FORM = 256;

    public boolean has(int flag) { return (flags & flag) != 0; }

    public AnimState reset() {
        age = limbSwing = limbAmount = headYaw = headPitch = action = extra = lookYaw = lookPitch = 0f;
        mode = flags = 0;
        clocks[0] = clocks[1] = clocks[2] = clocks[3] = -1f;
        glow = 1f;
        return this;
    }
}
