package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.model.AnimState;
import com.floraferox.model.ModelSpec;
import com.floraferox.model.Pose;
import java.util.HashMap;
import java.util.Map;

/**
 * The Flora Ferox mobs' animations in the game, the same motions as the review sheets (tools/gen/verdure/*.py,
 * pose()). Parts are found by name, so one animation drives every concept of a mob (they share their part names; a
 * concept's extra parts animate when present). Wired only for a mob whose concept Oculifelis has approved
 * (tools/gen/verdure/game.py, CHOSEN); until then the game draws a stand-in.
 *
 * <p>AnimState, as each target fills it: age (ticks), limbSwing/limbAmount, headYaw/headPitch, mode = the brain's state
 * (its ordinal), action = ticks in that state, and FLAG_GROUNDED.
 */
public final class VerdureAnimations {
    private VerdureAnimations() { }

    private static final float TAU = (float) (Math.PI * 2);

    private static float sin(float a) { return (float) Math.sin(a); }

    private static float cos(float a) { return (float) Math.cos(a); }

    private static float smooth(float a, float b, float t) {
        float x = Math.max(0F, Math.min(1F, (t - a) / (b - a)));
        return x * x * (3 - 2 * x);
    }

    private static float frac(float x) { return x - (float) Math.floor(x); }

    /** A model's parts by name; -1 for a part this concept lacks (the pose calls skip it). */
    public static final class Rig {
        private final Map<String, Integer> index = new HashMap<>();

        public Rig(ModelSpec spec) {
            for (int i = 0; i < spec.parts.length; i++) index.put(spec.parts[i].name, i);
        }

        public int at(String name) { return index.getOrDefault(name, -1); }

        int[] all(String prefix, int n, String suffix) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = at(prefix + i + suffix);
            return out;
        }
    }

    private static void rot(Pose p, int i, float x, float y, float z) { if (i >= 0) p.rot(i, x, y, z); }

    private static void move(Pose p, int i, float x, float y, float z) { if (i >= 0) p.move(i, x, y, z); }

    private static void show(Pose p, int i, boolean v) { if (i >= 0) p.visible(i, v); }

    // ---- the Pulse Gnat ----------------------------------------------------------------------------------------------
    public static final class PulseGnatRig {
        final int root, body;
        final boolean flier;
        final int[][] wing = new int[2][4], leg1 = new int[2][3], leg2 = new int[2][3], pincer0 = new int[2][1], pincer1 = new int[2][1];
        final int[][] tendril = new int[2][5], wingBack = new int[2][4];
        final int[] seeds;

        public PulseGnatRig(ModelSpec spec) {
            Rig r = new Rig(spec);
            root = r.at("root");
            body = r.at("body");
            flier = r.at("head") >= 0;
            String[] s = { "l", "r" };
            for (int k = 0; k < 2; k++) {
                wing[k][0] = r.at("wing_" + s[k]);
                for (int b = 0; b < 3; b++) wing[k][b + 1] = r.at("wing_" + s[k] + b);
                for (int i = 0; i < 3; i++) {
                    leg1[k][i] = r.at("leg_" + s[k] + i + "_1");
                    leg2[k][i] = r.at("leg_" + s[k] + i + "_2");
                }
                pincer0[k][0] = r.at("pincer_" + s[k] + "0");
                pincer1[k][0] = r.at("pincer_" + s[k] + "1");
                tendril[k] = r.all("tendril_" + s[k], 5, "");
                wingBack[k][0] = r.at("wing_" + s[k] + "b");
                for (int b = 0; b < 3; b++) wingBack[k][b + 1] = r.at("wing_" + s[k] + "b" + b);
            }
            seeds = r.all("seed", 5, "");
        }
    }

    /** The wingbeat (about three a second), the bob, and the heart shot's tell (wings shut), dart and flare. */
    public static void pulseGnat(PulseGnatRig r, Pose p, AnimState s) {
        PulseGnatBrain.State st = PulseGnatBrain.State.values()[Math.max(0, Math.min(4, s.mode))];
        float t = frac(s.age / 7F);
        float spread = 0.5F * (1 - cos(TAU * t));
        float tell = switch (st) {
            case TELL -> Math.min(1F, s.action / FLY_TELL_TICKS);
            case DART -> 1F;
            case RECOVER -> Math.max(0F, 1F - s.action / 6F);
            default -> 0F;
        };
        float go = st == PulseGnatBrain.State.DART ? Math.min(1F, s.action / 3F) : 0F;
        float flare = st == PulseGnatBrain.State.RECOVER ? sin((float) Math.PI * Math.min(1F, s.action / FLY_RECOVER_TICKS)) : 0F;
        float beat = 1F - tell;
        float flap = beat * (0.08F + spread * 0.95F) + (1 - beat) * (-0.12F - 0.16F * tell) + 1.1F * flare;
        float sweep = beat * sin(TAU * t) * 0.18F + 0.35F * go;
        float fold = 0.25F * go;
        float bob = beat * sin(TAU * t - 0.8F) * 1.2F - go;
        float lean = (r.flier ? 0.25F : 0.55F) * go - 0.2F * flare;
        move(p, r.root, 0, bob, 0);
        rot(p, r.body, lean + (r.flier ? s.headPitch * 0.3F : 0F), 0, 0);
        for (int i = 0; i < r.seeds.length; i++) {          // the seeds it carries drift on the air about it
            float ph = s.age * 0.06F + i * 1.3F;
            move(p, r.seeds[i], sin(ph) * 1.2F, -bob + cos(ph * 0.7F) * 1.0F, cos(ph) * 0.8F);
            rot(p, r.seeds[i], sin(ph) * 0.3F, ph * 0.25F, cos(ph) * 0.3F);
        }
        for (int k = 0; k < 2; k++) {
            float side = k == 0 ? -1F : 1F;
            rot(p, r.wing[k][0], 0, r.flier ? side * sweep : -side * sweep, side * flap);
            rot(p, r.wing[k][2], 0, -side * fold, 0);
            rot(p, r.wing[k][3], 0, -side * fold, 0);
            // the back petal, a beat behind the front one
            float lagSpread = 0.5F * (1 - cos(TAU * t - 0.5F));
            float backFlap = beat * (0.08F + lagSpread * 0.95F) * 0.85F + (1 - beat) * (-0.12F - 0.16F * tell) + 1.0F * flare;
            rot(p, r.wingBack[k][0], 0, (r.flier ? side : -side) * sweep * 0.8F, side * backFlap);
            rot(p, r.wingBack[k][2], 0, -side * fold, 0);
            rot(p, r.wingBack[k][3], 0, -side * fold, 0);
            for (int i = 0; i < 3; i++) {
                float w = s.age * 0.15F + i * 0.9F + (k == 0 ? 0F : 1.6F);
                rot(p, r.leg1[k][i], sin(w) * 0.12F, 0, side * cos(w) * 0.1F);
                rot(p, r.leg2[k][i], sin(w - 0.6F) * 0.15F, 0, 0);
            }
            rot(p, r.pincer1[k][0], 0, side * 0.12F * sin(s.age * 0.5F), 0);
            rot(p, r.pincer0[k][0], 0, -side * 0.35F * tell + side * 0.5F * go, 0);
            for (int j = 1; j < 5; j++) {
                float w = s.age * 0.12F - j * 0.8F + (k == 0 ? 0F : 1.1F);
                rot(p, r.tendril[k][j], sin(w) * 0.12F, cos(w) * 0.08F * side, 0);
            }
        }
    }

    // ---- the Dusk Sprig -------------------------------------------------------------------------------------------
    public static final class DuskSprigRig {
        final int root, neck, jawTop, jawBottom;
        final int[] stem, rays, raysInner, tongue;
        final int[][] drool = new int[2][];
        final int[][] leaf = new int[2][4];

        public DuskSprigRig(ModelSpec spec) {
            Rig r = new Rig(spec);
            root = r.at("root");
            neck = r.at("neck");
            jawTop = r.at("jaw_top");
            jawBottom = r.at("jaw_bottom");
            stem = r.all("stem", 5, "");
            rays = r.all("ray", 12, "b");
            raysInner = r.all("rayi", 12, "b");
            tongue = r.all("tongue", 3, "");
            for (int k = 0; k < 2; k++) drool[k] = r.all("drool" + k + "_", 3, "");
            String[] s = { "l", "r" };
            for (int k = 0; k < 2; k++) {
                leaf[k][0] = r.at("leaf_" + s[k]);
                for (int b = 0; b < 3; b++) leaf[k][b + 1] = r.at("leaf_" + s[k] + b);
            }
        }
    }

    /** The hop (its walk), the rear-and-gape tell, the lunge and snap, and rooting by day. */
    public static void duskSprig(DuskSprigRig r, Pose p, AnimState s) {
        DuskSprigBrain.State st = DuskSprigBrain.State.values()[Math.max(0, Math.min(6, s.mode))];
        float[] bend = new float[5];
        float gape = 0.12F + 0.1F * (0.5F + 0.5F * sin(s.age * 0.6F));
        float leaves = 0F, flare = 0F, hop = 0F;
        switch (st) {
            case ROOTED -> {
                bend = new float[] { 0.35F, 0.2F, 0.25F, 0.3F, 0.2F };
                gape = 0F;
                leaves = 0.9F;
            }
            case REAR -> {
                float rear = Math.min(1F, s.action / SPROUT_REAR_TICKS);
                bend = new float[] { -0.3F * rear, 0.25F * rear, 0.25F * rear, 0.2F * rear, 0.1F * rear };
                gape = 0.15F + 0.75F * rear;
                leaves = 0.35F * rear;
                flare = rear;
            }
            case LUNGE -> {
                bend = new float[] { 0.35F, 0.1F, -0.15F, -0.2F, 0F };
                gape = 0.9F;
                leaves = -0.3F;
            }
            case SNAP -> {
                gape = Math.max(0F, 0.9F - s.action * 0.45F);
                bend = new float[] { 0.2F, 0F, -0.1F, -0.1F, 0F };
            }
            default -> {
                // walking: the stalk crouches and springs with the stride, the leaves flap for balance
                hop = s.limbAmount * Math.abs(sin(s.limbSwing * 0.6F));
                float c = s.limbAmount * Math.max(0F, -sin(s.limbSwing * 0.6F));
                bend = new float[] { 0.25F * c, -0.2F * c, -0.15F * c, 0.1F * c, 0F };
                leaves = -0.45F * hop + 0.3F * c;
            }
        }
        float sum = 0F;
        for (int j = 0; j < 5; j++) {
            rot(p, r.stem[j], bend[j], 0, 0);
            sum += bend[j];
        }
        rot(p, r.neck, -sum * 0.8F + s.headPitch * 0.3F, s.headYaw * 0.5F, 0);
        move(p, r.root, 0, -hop * 2.5F, 0);
        rot(p, r.jawTop, -gape * 0.85F, 0, 0);
        rot(p, r.jawBottom, gape * 0.35F, 0, 0);
        for (int k = 0; k < 2; k++) {
            float side = k == 0 ? -1F : 1F;
            rot(p, r.leaf[k][1], 0, 0, side * leaves * 0.6F + side * sin(s.age * 0.08F + k) * 0.04F);
            rot(p, r.leaf[k][2], 0, 0, side * leaves * 0.3F);
            if (st == DuskSprigBrain.State.ROOTED) rot(p, r.leaf[k][0], 0, side * 0.9F, 0);
        }
        for (int i : r.rays) rot(p, i, 0.95F * flare + (st == DuskSprigBrain.State.ROOTED ? 1.1F : 0F), 0, 0);
        for (int i : r.raysInner) rot(p, i, 0.7F * flare + (st == DuskSprigBrain.State.ROOTED ? 1.2F : 0F), 0, 0);
        rot(p, r.tongue[0], -0.25F * gape, 0, 0);
        rot(p, r.tongue[1], sin(s.age * 0.5F) * 0.12F - 0.3F * gape, 0, 0);
        rot(p, r.tongue[2], sin(s.age * 0.5F - 0.8F) * 0.15F, 0, 0);
        for (int k = 0; k < 2; k++) {
            for (int j = 0; j < 3; j++) rot(p, r.drool[k][j], sin(s.age * 0.15F - j * 0.9F + k) * 0.18F, 0, cos(s.age * 0.15F - j + k) * 0.12F);
            show(p, r.drool[k][0], gape > 0.12F);
            if (r.drool[k][0] >= 0) p.scale(r.drool[k][0], 1F, 0.6F + 1.6F * gape, 1F);
        }
    }

    // ---- the Moon Tick --------------------------------------------------------------------------------------------
    public static final class MoonTickRig {
        final int root, head;
        final int[] cases = new int[2], wings = new int[2];
        final int[][] antenna = new int[2][];
        final int[][] leg0 = new int[2][3], leg1 = new int[2][3], leg2 = new int[2][3];

        public MoonTickRig(ModelSpec spec) {
            Rig r = new Rig(spec);
            root = r.at("root");
            head = r.at("head");
            String[] s = { "l", "r" };
            for (int k = 0; k < 2; k++) {
                cases[k] = r.at("case_" + s[k]);
                antenna[k] = r.all("antenna_" + s[k], 5, "");
                wings[k] = r.at("wing_" + s[k]);
                for (int i = 0; i < 3; i++) {
                    leg0[k][i] = r.at("leg_" + s[k] + i + "_0");
                    leg1[k][i] = r.at("leg_" + s[k] + i + "_1");
                    leg2[k][i] = r.at("leg_" + s[k] + i + "_2");
                }
            }
        }
    }

    /** The crawl (a tripod gait), the crouch, and the bound: the cases open, the hind wings buzz, the legs tuck. */
    public static void moonTick(MoonTickRig r, Pose p, AnimState s) {
        MoonTickBrain.State st = MoonTickBrain.State.values()[Math.max(0, Math.min(3, s.mode))];
        float open = st == MoonTickBrain.State.AIR ? Math.min(1F, s.action / 4F) : 0F;
        float crouch = switch (st) {
            case CROUCH -> Math.min(1F, s.action / LOUSE_CROUCH_TICKS);
            case LAND -> Math.max(0F, 1F - s.action / LOUSE_LAND_TICKS);
            default -> 0F;
        };
        float tuck = open;
        float buzz = sin(s.age * 2.6F) * open;
        move(p, r.root, 0, 3F * crouch, 0);
        rot(p, r.head, s.headPitch * 0.4F, s.headYaw * 0.4F, 0);
        for (int k = 0; k < 2; k++) {
            float side = k == 0 ? -1F : 1F;
            rot(p, r.cases[k], 0.25F * open, side * 0.15F * open, -side * 0.95F * open);
            for (int j = 1; j < 5; j++) {
                float w = s.age * (open > 0 ? 0.3F : 0.15F) - j * 0.7F + (k == 0 ? 0F : 1.2F);
                rot(p, r.antenna[k][j], sin(w) * 0.12F + (j == 1 ? 0.25F * open : 0F), side * cos(w) * 0.1F, 0);
            }
            // the hind wings fold inside the body and rise 2 px as the cases open; a low tilt and buzz keep them under
            // the lifted cases, never through them
            rot(p, r.wings[k], 0, side * (0.25F + 1.15F * open), side * (-0.05F * open + 0.25F * buzz));
            move(p, r.wings[k], 0, -2F * open, 0);
            show(p, r.wings[k], open > 0.02F);
            for (int i = 0; i < 3; i++) {
                float ph = s.limbSwing * 0.9F + (((i % 2 == 0) == (k == 0)) ? (float) Math.PI : 0F);
                rot(p, r.leg0[k][i], 0, side * 0.3F * sin(ph) * s.limbAmount, 0);
                rot(p, r.leg1[k][i], -0.25F * Math.max(0F, cos(ph)) * s.limbAmount + 0.35F * tuck - 0.3F * crouch, 0, 0);
                rot(p, r.leg2[k][i], -0.6F * tuck + 0.3F * crouch, 0, 0);
            }
        }
    }

    // ---- the Mire Ghast ----------------------------------------------------------------------------------------------
    public static final class MireGhastRig {
        final int hips, torso, head;
        final int[] thigh = new int[2], shin = new int[2], foot = new int[2], arm = new int[2], forearm = new int[2];
        final int[][] strings = new int[5][], drips = new int[4][], stalk = new int[1][], mud = new int[4][];

        public MireGhastRig(ModelSpec spec) {
            Rig r = new Rig(spec);
            hips = r.at("hips");
            torso = r.at("torso");
            head = r.at("head");
            String[] s = { "l", "r" };
            for (int k = 0; k < 2; k++) {
                thigh[k] = r.at("thigh_" + s[k]);
                shin[k] = r.at("shin_" + s[k]);
                foot[k] = r.at("foot_" + s[k]);
                arm[k] = r.at("arm_" + s[k]);
                forearm[k] = r.at("forearm_" + s[k]);
            }
            for (int i = 0; i < 5; i++) strings[i] = r.all("string" + i + "_", 6, "");
            for (int k = 0; k < 2; k++) for (int i = 0; i < 2; i++) mud[k * 2 + i] = r.all("mud_" + s[k] + i + "_", 3, "");
            for (int i = 0; i < 4; i++) drips[i] = r.all("drip" + i + "_", 5, "");
            stalk[0] = r.all("stalk", 5, "");
        }
    }

    private static float jerk(float ph) {
        float s = sin(ph);
        return Math.copySign((float) Math.pow(Math.abs(s), 0.45), s);
    }

    /** The lurch (a puppet's stride, jerked, the head lolling), the arms-up tell and the slam; the strings sway. */
    public static void mireGhast(MireGhastRig r, Pose p, AnimState s) {
        MireGhastBrain.State st = MireGhastBrain.State.values()[Math.max(0, Math.min(3, s.mode))];
        float ph = s.limbSwing * 0.55F;
        float stride = jerk(ph) * s.limbAmount;
        float up = st == MireGhastBrain.State.TELL ? Math.min(1F, s.action / GHOUL_TELL_TICKS) : 0F;
        float down = switch (st) {
            case SLAM -> 1F;
            case RECOVER -> Math.max(0F, 1F - s.action / GHOUL_RECOVER_TICKS);
            default -> 0F;
        };
        float spread = st == MireGhastBrain.State.TELL ? smooth(0F, 0.33F, up) : 0F;
        for (int k = 0; k < 2; k++) {
            float side = k == 0 ? -1F : 1F;
            rot(p, r.thigh[k], -0.55F * stride * side, 0, 0);
            rot(p, r.shin[k], 0.4F * Math.max(0F, stride * side), 0, 0);
            if (r.foot[k] >= 0) p.scale(r.foot[k], 1F + 0.08F * sin(s.age * 0.3F + side), 0.9F + 0.12F * cos(s.age * 0.3F + side), 1F);
            rot(p, r.arm[k], 0.12F * sin(ph + (k == 0 ? 0F : (float) Math.PI) - 0.6F) * s.limbAmount - 1.4F * up + 0.55F * down, 0, 0);
            // the tell: the arms shift out first, so they rise beside the head, not through it (hidden shoulders hold them)
            move(p, r.arm[k], side * 2.5F * spread, 0, 0);
            rot(p, r.forearm[k], -0.3F * up + 0.2F * down, 0, 0);
        }
        rot(p, r.torso, 0.04F * sin(s.age * 0.1F) - 0.25F * up + 0.3F * down, 0, 0.08F * stride);
        rot(p, r.head, 0.08F * sin(s.age * 0.1F - 1.2F) + 0.15F * up - 0.1F * down + s.headPitch * 0.3F,
                s.headYaw * 0.5F, -0.14F * jerk(ph - 1F) * s.limbAmount);
        move(p, r.hips, 0, -0.8F * Math.abs(sin(ph)) * s.limbAmount, 0);
        float sway = 0.08F + 0.12F * s.limbAmount + 0.2F * up;
        for (int[] m : r.mud) {
            for (int j = 0; j < 3; j++) rot(p, m[j], sin(s.age * 0.2F - j * 0.9F) * 0.15F, 0, cos(s.age * 0.1F - j) * 0.1F);
        }
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 6; j++) {
                float w = s.age * 0.1F - j * 0.7F + i * 2F;
                rot(p, r.strings[i][j], sin(w) * sway * 0.6F, cos(w * 0.8F) * sway * 0.5F, 0);
            }
        }
        for (int k = 0; k < 4; k++) {
            for (int j = 0; j < 5; j++) rot(p, r.drips[k][j], sin(s.age * 0.2F - j * 0.9F + k) * 0.08F, 0, cos(s.age * 0.1F - j + k) * 0.06F);
        }
        for (int j = 0; j < 5; j++) rot(p, r.stalk[0][j], sin(s.age * 0.1F - j * 0.6F) * 0.05F, 0, cos(s.age * 0.1F - j * 0.5F) * 0.04F);
    }

    // ---- the four originals' reworks (tools/gen/verdure/ferox12.py): their sheets' own clips, baked -----------------------
    /**
     * One of the four originals played from its baked clips (VerdureModels.clips, in ferox12.STATES's order), once its
     * rework is the picked concept; until then of() gives null and the hand-written motion above plays.
     */
    public static final class Baked {
        final VerdureModels.Clip[] clips;
        final int head;
        final int[] wings;

        private Baked(ModelSpec spec, VerdureModels.Clip[] clips) {
            this.clips = clips;
            Rig r = new Rig(spec);
            head = r.at("head");
            wings = new int[] { r.at("wing_l"), r.at("wing_r") };
        }

        public static Baked of(ModelSpec spec, String id) {
            VerdureModels.Clip[] c = VerdureModels.clips(id);
            return c == null ? null : new Baked(spec, c);
        }
    }

    /** A looping clip, this many cycles in; a one-shot at t (0..1). */
    private static void loop(Pose p, VerdureModels.Clip c, float cycles, float amt) { play(p, c, cycles * c.frames(), true, amt); }

    private static void at(Pose p, VerdureModels.Clip c, float t, float amt) {
        play(p, c, Math.max(0F, Math.min(1F, t)) * (c.frames() - 1), false, amt);
    }

    /** Its idle by its clock, crossfaded into its walk by how fast it goes (stride: limb swing per walk cycle). */
    private static void stroll(Pose p, VerdureModels.Clip idle, VerdureModels.Clip walk, AnimState s, float idleTicks, float stride) {
        float w = Math.min(1F, s.limbAmount * 1.5F);
        loop(p, idle, s.age / idleTicks, 1F - w);
        loop(p, walk, s.limbSwing / stride, w);
    }

    private static void look(Baked b, Pose p, AnimState s) { rot(p, b.head, s.headPitch * 0.3F, s.headYaw * 0.4F, 0); }

    /** Hover (two beats in 14 ticks) or fly on; the heart shot: the tell (0..0.3), the dart (to 0.6), the flare. */
    public static void pulseGnat(Baked b, Pose p, AnimState s) {
        PulseGnatBrain.State st = PulseGnatBrain.State.values()[Math.max(0, Math.min(4, s.mode))];
        switch (st) {
            case TELL -> at(p, b.clips[2], 0.3F * s.action / FLY_TELL_TICKS, 1F);
            case DART -> at(p, b.clips[2], 0.3F + 0.3F * s.action / FLY_DART_TICKS, 1F);
            case RECOVER -> at(p, b.clips[2], 0.6F + 0.4F * s.action / FLY_RECOVER_TICKS, 1F);
            default -> loop(p, b.clips[st == PulseGnatBrain.State.ORBIT ? 1 : 0], s.age / 14F, 1F);
        }
    }

    /** Idle or hop; rooted by day (eased in); the rear, then the lunge (0..0.35), snap (to 0.55) and recovery. */
    public static void duskSprig(Baked b, Pose p, AnimState s) {
        DuskSprigBrain.State st = DuskSprigBrain.State.values()[Math.max(0, Math.min(6, s.mode))];
        switch (st) {
            case ROOTED -> {
                float k = smooth(0F, 10F, s.action);
                loop(p, b.clips[0], s.age / 60F, 1F - k);
                loop(p, b.clips[2], s.age / 80F, k);
            }
            case REAR -> at(p, b.clips[3], s.action / SPROUT_REAR_TICKS, 1F);
            case LUNGE -> at(p, b.clips[4], 0.35F * s.action / SPROUT_LUNGE_TICKS, 1F);
            case SNAP -> at(p, b.clips[4], 0.35F + 0.2F * s.action / SPROUT_SNAP_TICKS, 1F);
            case RECOVER -> at(p, b.clips[4], 0.55F + 0.45F * s.action / SPROUT_RECOVER_TICKS, 1F);
            default -> stroll(p, b.clips[0], b.clips[1], s, 60F, 5.24F);
        }
        look(b, p, s);
    }

    /** Idle or crawl; the crouch, the bound (cases up, the wings out only now), the landing (the crouch run back). */
    public static void moonTick(Baked b, Pose p, AnimState s) {
        MoonTickBrain.State st = MoonTickBrain.State.values()[Math.max(0, Math.min(3, s.mode))];
        switch (st) {
            case CROUCH -> at(p, b.clips[2], s.action / LOUSE_CROUCH_TICKS, 1F);
            case AIR -> {
                float k = Math.min(1F, s.action / 4F);
                at(p, b.clips[2], 1F, 1F - k);
                loop(p, b.clips[3], s.age / 8F, k);
            }
            case LAND -> at(p, b.clips[2], 1F - s.action / LOUSE_LAND_TICKS, 1F);
            default -> stroll(p, b.clips[0], b.clips[1], s, 40F, 6.98F);
        }
        for (int w : b.wings) show(p, w, st == MoonTickBrain.State.AIR);
        look(b, p, s);
    }

    /** Idle or the lumbering stride; the slam: the tell (0..0.35), the slam (to 0.5), the recovery. */
    public static void mireGhast(Baked b, Pose p, AnimState s) {
        MireGhastBrain.State st = MireGhastBrain.State.values()[Math.max(0, Math.min(3, s.mode))];
        switch (st) {
            case TELL -> at(p, b.clips[2], 0.35F * s.action / GHOUL_TELL_TICKS, 1F);
            case SLAM -> at(p, b.clips[2], 0.35F + 0.15F * s.action / GHOUL_SLAM_TICKS, 1F);
            case RECOVER -> at(p, b.clips[2], 0.5F + 0.5F * s.action / GHOUL_RECOVER_TICKS, 1F);
            default -> stroll(p, b.clips[0], b.clips[1], s, 60F, 11.42F);
        }
        look(b, p, s);
    }

    // ---- 1.4's wild mobs and 1.2's six (tools/gen/verdure/wild.py): their sheets' own walk and attack, baked ----------------------
    /**
     * A mob played from its baked clips (VerdureModels.clips): 1.4's wild mobs and 1.2's six; its timings, whether it
     * floats, and its head, if its model names one.
     */
    public static final class WildRig {
        final StrikeBrain.Timing timing;
        final boolean floats, hops;
        final VerdureModels.Clip walk, attack, idle;
        final VerdureModels.Clip crest, wings;            // a boss's two phase looks, laid over the rest (the Thorn
                                                          // Matriarch's crest and wings, the Briar King's crown and roses)
        final VerdureModels.Spin spin;
        final int head, pollen;
        final int[] cracks, flare;                        // the Crimson Behemoth's phase-3 looks: its crk_ and flr_ shells
        final int[] blooms;                               // the Briar King's crown blooms, shown while his crown flowers
        final int[] brood;                                // the Thorn Matriarch's brood flaps, torn open from her phase 2
        final int[] tuck;                                 // the Coil Snail's head, stalks, tail and shell, drawn in to hide
        private final String[] names;
        final int[][] stageOnly, stageFrom;               // a Behemoth look's growths (boss_variants.py): st<n>_ parts show
                                                          // in phase n only; ish/ooz/mag<n>_ from phase n on

        public WildRig(ModelSpec spec, String id, StrikeBrain.Timing timing, boolean floats, boolean hops) {
            this.timing = timing;
            this.floats = floats;
            this.hops = hops;
            VerdureModels.Clip[] c = VerdureModels.clips(id);
            walk = c[0];
            attack = c[1];
            idle = c.length > 2 ? c[2] : null;              // the overhaul's idle (wildmotion.py), once it is picked
            crest = c.length > 3 ? c[3] : null;             // tools/gen/verdure/boss_tm.py, boss_bk6.py OVERLAYS
            wings = c.length > 4 ? c[4] : null;
            spin = VerdureModels.spin(id);
            names = new String[spec.parts.length];
            for (int i = 0; i < names.length; i++) names[i] = spec.parts[i].name;
            Rig rig = new Rig(spec);
            head = rig.at("head");
            pollen = crest != null ? rig.at("pollen") : -1;
            cracks = named(spec, "crk_");                  // tools/gen/verdure/boss_tell.py (b_cracks)
            flare = named(spec, "flr_");
            blooms = id.startsWith("briar_king") ? named(spec, "bloom") : new int[0];   // boss_bk6.py (b6_boss)
            brood = id.startsWith("thorn_matriarch") ? named(spec, "brood_") : new int[0];   // boss_tm4.py (t4_springqueen)
            tuck = id.startsWith("coil_snail") ? new int[] { head, rig.at("stalk_l"), rig.at("stalk_r"), rig.at("tail"), rig.at("shell") }
                    : null;                                // tools/gen/verdure/coil_snail.py (tuck)
            stageOnly = new int[3][];
            stageFrom = new int[3][];
            for (int n = 1; n <= 3; n++) {
                stageOnly[n - 1] = named(spec, "st" + n + "_");
                stageFrom[n - 1] = concat(named(spec, "ish" + n + "_"), named(spec, "ooz" + n + "_"), named(spec, "mag" + n + "_"));
            }
        }

        private static int[] concat(int[]... parts) {
            int n = 0;
            for (int[] a : parts) n += a.length;
            int[] out = new int[n];
            n = 0;
            for (int[] a : parts) for (int i : a) out[n++] = i;
            return out;
        }

        /** -1 for a left part ("_l"), else 1, as the generator's sides. */
        float side(int part) { return names[part].endsWith("_l") ? -1F : 1F; }

        /** The Thorn Matriarch's brood flaps (none on any other model). */
        public int[] brood() { return brood.clone(); }

        /** Whether its model grows by phase (a Behemoth biome look): then its plain cracks stay hidden. */
        public boolean staged() {
            for (int n = 0; n < 3; n++) if (stageOnly[n].length > 0 || stageFrom[n].length > 0) return true;
            return false;
        }

        private static int[] named(ModelSpec spec, String prefix) {
            int n = 0;
            for (var q : spec.parts) if (q.name.startsWith(prefix)) n++;
            int[] out = new int[n];
            n = 0;
            for (int i = 0; i < spec.parts.length; i++) if (spec.parts[i].name.startsWith(prefix)) out[n++] = i;
            return out;
        }

        public WildRig(ModelSpec spec, VerdureRules.Wild w) {
            this(spec, w.id, w, w.move != VerdureRules.Move.GROUND, w.is(VerdureRules.HOP));
        }
    }

    /** Walk cycles a ground mob runs per unit of limb swing; a flier, or the rooted Carrion Bloom, by age. */
    private static final float WILD_STRIDE = 1F / 9F, WILD_DRIFT = 1F / 40F, WILD_IDLE = 60F;
    /** Ticks a cycle of the crest's puffing (two puffs) and of the wings' beating (two beats). */
    private static final float CREST_CYCLE = 40F, WINGS_CYCLE = 28F;

    /**
     * Its attack (0..1 through the tell, the blow and the recovery: StrikeBrain.attack) while it strikes, else its walk:
     * a ground mob's scaled by how fast it goes, a flier's always (its sheets' walk is how it floats). One with an idle
     * plays it by its clock, crossfaded into its walk by how fast it goes. Its head follows its look.
     */
    public static void wild(WildRig r, Pose p, AnimState s) {
        StrikeBrain.State st = StrikeBrain.State.values()[Math.max(0, Math.min(3, s.mode))];
        float t = StrikeBrain.attack(r.timing, st, s.action);
        if (st != StrikeBrain.State.WALK) {
            at(p, r.attack, t, 1F);
        } else {
            float go = Math.min(1F, s.limbAmount * 1.5F);           // how fast it goes
            if (r.idle != null) loop(p, r.idle, s.age / WILD_IDLE, 1F - go);
            float move = r.idle != null || !(r.hops || r.floats) ? go : 1F;
            if (r.hops) loop(p, r.walk, s.extra, move);            // its hop on its own clock (extra: HopClock.phase)
            else if (!r.floats) loop(p, r.walk, s.limbSwing * WILD_STRIDE, move);
            else loop(p, r.walk, s.age * WILD_DRIFT, move);
        }
        if (r.spin != null) {                                     // the Pollen Puff's flower: round and round, by its clock
            float turn = s.age * WILD_DRIFT * r.spin.perCycle();
            for (int part : r.spin.parts()) p.rot(part, 0, turn, 0);
        }
        if (r.crest != null) {                                   // clocks[0]: how open the bloom is; [1]: the wings
            float open = Math.max(0F, s.clocks[0]);
            loop(p, r.crest, s.age / CREST_CYCLE, open);
            if (r.pollen >= 0) {
                p.visible(r.pollen, open > 0.05F);
                if (open > 0.05F) p.scale(r.pollen, open * (1F + 0.25F * (0.5F + 0.5F * (float) Math.sin(2 * Math.PI * 2 * s.age / CREST_CYCLE))));
            }
        }
        if (r.wings != null) loop(p, r.wings, s.age / WINGS_CYCLE, Math.max(0F, s.clocks[1]));
        if (r.brood.length > 0) {                                // clocks[2]: how far her brood flaps have torn open
            float b = Math.max(0F, Math.min(1F, s.clocks[2]));
            float grow = b < 0.4F ? b / 0.4F * 0.8F : 0.8F + 0.2F * (b - 0.4F) / 0.6F;   // they burst out, then settle
            float peel = b * b * (3F - 2F * b);
            for (int i : r.brood) {
                p.visible(i, b > 0.05F);
                if (b <= 0.05F) continue;
                p.scale(i, grow);
                p.rot(i, 0, 0, r.side(i) * (-0.3F + 0.45F * peel));   // from the burst's lean to peeled right back
            }
        }
        if (r.blooms.length > 0) {                               // clocks[0]: how far his crown has flowered
            float open = Math.max(0F, s.clocks[0]);
            for (int i : r.blooms) {
                p.visible(i, open > 0.05F);
                if (open > 0.05F) p.scale(i, open);
            }
        }
        boolean staged = r.staged();
        if (staged) {                                            // stage: its phase; its look's growths in place of the cracks
            for (int n = 1; n <= 3; n++) {
                for (int i : r.stageOnly[n - 1]) p.visible(i, n == s.stage);
                for (int i : r.stageFrom[n - 1]) p.visible(i, n <= s.stage);
            }
            for (int i : r.cracks) p.visible(i, false);
            for (int i : r.flare) p.visible(i, false);
        } else if (r.cracks.length > 0) {                        // clocks[2]: how lit its cracks are; [3]: how hard they flare
            boolean lit = s.clocks[2] > 0.05F, flare = s.clocks[3] > 0.05F;
            for (int i : r.cracks) p.visible(i, lit);
            for (int i : r.flare) p.visible(i, flare);
        }
        float hidden = 0F;
        if (r.tuck != null) {                                    // clocks[0]: how far the Coil Snail is in its shell
            hidden = Math.max(0F, Math.min(1F, s.clocks[0]));
            if (st != StrikeBrain.State.WALK) {                  // its ram's clip holds the tuck's turns and shifts, not its scale
                tuck(p, r.tuck, Math.max(hidden, smooth(0F, 0.3F, t) * (1F - smooth(0.8F, 1F, t))), false);
            } else if (hidden > 0F) {
                tuck(p, r.tuck, hidden, true);
            }
        }
        rot(p, r.head, s.headPitch * 0.3F * (1F - hidden), s.headYaw * 0.5F * (1F - hidden), 0);
    }

    /** The Coil Snail drawn into its shell by a (0 out .. 1 in), as coil_snail.py's tuck: head, stalk_l, stalk_r, tail, shell. */
    private static void tuck(Pose p, int[] i, float a, boolean turns) {
        for (int k = 1; k <= 2; k++) {
            if (i[k] < 0) continue;
            p.scale(i[k], 1F, 1F - 0.85F * a, 1F);
            if (turns) p.rot(i[k], 0.9F * a, 0, 0);
        }
        if (i[0] >= 0) p.scale(i[0], 1F - 0.5F * a);
        if (!turns) return;
        if (i[0] >= 0) p.move(i[0], 0, 1.5F * a, 4F * a);
        if (i[3] >= 0) p.move(i[3], 0, 0, -3F * a);
        if (i[4] >= 0) p.move(i[4], 0, 1.0F * a, 0);
    }

    /** Ticks a hopper is off the ground on a flat hop (HOP_UP under vanilla gravity and drag), its kick, its landing. */
    private static final float HOP_AIR = airTicks(HOP_UP), HOP_KICK = 3F, HOP_LAND = 4F;

    private static float airTicks(double vy) {
        double y = 0;
        int t = 0;
        while (t == 0 || y > 0) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            t++;
        }
        return t;
    }

    /**
     * A hopper's hop played by its render clock rather than its per-tick rise, so it moves every frame and never snaps:
     * off the ground it kicks out over HOP_KICK ticks then sails through the tuck for the rest of a flat hop's airtime;
     * landing, it settles from wherever it got to back into its crouch over HOP_LAND ticks.
     */
    public static final class HopClock {
        private boolean air;
        private float off, land = -1000F, landed;

        /** Where it is in its walk clip (0..1; 0 is crouched on the ground). */
        public float phase(boolean grounded, float age) {
            if (!grounded && !air) {
                air = true;
                off = age;
            } else if (grounded && air) {
                air = false;
                landed = flight(age - off);
                land = age;
            }
            if (air) return flight(age - off);
            float k = (age - land) / HOP_LAND;
            return k >= 1F ? 0F : landed + (1F - landed) * k;
        }

        private static float flight(float dt) {
            return dt < HOP_KICK ? 0.3F * dt / HOP_KICK : 0.3F + 0.55F * Math.min(1F, (dt - HOP_KICK) / (HOP_AIR - HOP_KICK));
        }
    }

    /** A clip at frame f (between two keyframes, on a Catmull-Rom curve through their neighbours; a loop wraps), scaled by amt. */
    private static void play(Pose p, VerdureModels.Clip c, float f, boolean loop, float amt) {
        if (amt <= 0F || c.parts().length == 0) return;
        int n = c.frames();
        if (loop) f = ((f % n) + n) % n;
        else f = Math.max(0F, Math.min(n - 1, f));
        int a = (int) Math.floor(f);
        int z = key(a - 1, n, loop), b = key(a + 1, n, loop), c2 = key(a + 2, n, loop);
        float k = f - a;
        int np = c.parts().length;
        float[] d = c.data();
        for (int i = 0; i < np; i++) {
            int iz = (z * np + i) * 6, ia = (a * np + i) * 6, ib = (b * np + i) * 6, ic = (c2 * np + i) * 6;
            p.rot(c.parts()[i], curve(d, iz, ia, ib, ic, k) * amt, curve(d, iz + 1, ia + 1, ib + 1, ic + 1, k) * amt,
                    curve(d, iz + 2, ia + 2, ib + 2, ic + 2, k) * amt);
            p.move(c.parts()[i], curve(d, iz + 3, ia + 3, ib + 3, ic + 3, k) * amt, curve(d, iz + 4, ia + 4, ib + 4, ic + 4, k) * amt,
                    curve(d, iz + 5, ia + 5, ib + 5, ic + 5, k) * amt);
        }
    }

    private static int key(int i, int n, boolean loop) { return loop ? ((i % n) + n) % n : Math.max(0, Math.min(n - 1, i)); }

    private static float curve(float[] d, int z, int a, int b, int c, float k) {
        float p0 = d[z], p1 = d[a], p2 = d[b], p3 = d[c];
        return 0.5F * (2F * p1 + (p2 - p0) * k + (2F * p0 - 5F * p1 + 4F * p2 - p3) * k * k + (3F * (p1 - p2) + p3 - p0) * k * k * k);
    }
}
