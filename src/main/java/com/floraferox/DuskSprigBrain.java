package com.floraferox;

import static com.floraferox.VerdureRules.*;

import java.util.function.IntUnaryOperator;

/**
 * The Dusk Sprig's mind (docs/DESIGN.md, sections 2.3 and 3): Terraria's snowman behaviour walks and hops it at its
 * prey; here it also rears back and gapes (the tell), lunges with its jaws open and snaps them shut, and bites what is
 * right in front of it. By day, with no prey, it roots in place with its mouth shut (it is a night bloom).
 *
 * <pre>
 * IDLE -(day)-> ROOTED -(night or hurt)-> IDLE
 * IDLE -(prey)-> CHASE -(in lunge range)-> REAR -> LUNGE -> SNAP -> RECOVER -> CHASE
 *                CHASE -(close)-> SNAP
 * </pre>
 */
public final class DuskSprigBrain {
    public enum State { IDLE, ROOTED, CHASE, REAR, LUNGE, SNAP, RECOVER }

    public enum Event { NONE, ROOT, UPROOT, REAR, LEAP, BITE }

    private State state = State.IDLE;
    private int ticks;
    private int cooldown = SPROUT_COOLDOWN_MIN;

    public State state() { return state; }

    public int ticksInState() { return ticks; }

    private void go(State s) {
        state = s;
        ticks = 0;
    }

    public Event tick(boolean prey, double distance, boolean inSight, boolean onGround, boolean daylight, IntUnaryOperator rand) {
        ticks++;
        if (cooldown > 0) cooldown--;
        switch (state) {
            case IDLE -> {
                if (prey) go(State.CHASE);
                else if (daylight) {
                    go(State.ROOTED);
                    return Event.ROOT;
                }
            }
            case ROOTED -> {
                if (prey || !daylight) {
                    go(prey ? State.CHASE : State.IDLE);
                    return Event.UPROOT;
                }
            }
            case CHASE -> {
                if (!prey) go(State.IDLE);
                else if (cooldown == 0 && inSight && onGround) {
                    if (distance <= SPROUT_BITE_REACH) {
                        go(State.SNAP);
                        return Event.BITE;
                    }
                    if (distance >= SPROUT_LUNGE_MIN && distance <= SPROUT_LUNGE_MAX) {
                        go(State.REAR);
                        return Event.REAR;
                    }
                }
            }
            case REAR -> {
                if (!prey) go(State.IDLE);
                else if (ticks >= SPROUT_REAR_TICKS) {
                    go(State.LUNGE);
                    return Event.LEAP;
                }
            }
            case LUNGE -> {
                if (ticks >= SPROUT_LUNGE_TICKS || (ticks > 3 && onGround)) {
                    go(State.SNAP);
                    return Event.BITE;
                }
            }
            case SNAP -> {
                if (ticks >= SPROUT_SNAP_TICKS) go(State.RECOVER);
            }
            case RECOVER -> {
                if (ticks >= SPROUT_RECOVER_TICKS) {
                    go(prey ? State.CHASE : State.IDLE);
                    cooldown = SPROUT_COOLDOWN_MIN + rand.applyAsInt(SPROUT_COOLDOWN_RANDOM);
                }
            }
        }
        return Event.NONE;
    }

    /** Whether it holds still (rooted, rearing, snapping): the entity stops its navigation. */
    public boolean still() { return state == State.ROOTED || state == State.REAR || state == State.SNAP; }

    /** 0..1: how wide the jaws gape. */
    public float gape() {
        return switch (state) {
            case REAR -> Math.min(1F, ticks / (float) SPROUT_REAR_TICKS);
            case LUNGE -> 1F;
            case SNAP -> Math.max(0F, 1F - ticks / 2F);
            case ROOTED -> 0F;
            default -> 0.15F;
        };
    }

    /** For tests: skip (or set) the wait before the next lunge. */
    public void forceCooldown(int t) { cooldown = t; }
}
