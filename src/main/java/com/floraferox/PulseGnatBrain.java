package com.floraferox;

import static com.floraferox.VerdureRules.*;

import java.util.function.IntUnaryOperator;

/**
 * The Pulse Gnat's mind (docs/DESIGN.md, sections 2.1 and 3): Terraria's bat behaviour in three dimensions. It flutters
 * when it has no prey; with prey it orbits three to five blocks out, and every few seconds folds its wings shut into a
 * heart (the tell), then darts straight through where the prey was (the heart shot), and flares open to recover.
 *
 * <pre>
 * FLUTTER -(prey)-> ORBIT -(cooldown done, in sight, in range)-> TELL -> DART -> RECOVER -> ORBIT
 * </pre>
 */
public final class PulseGnatBrain {
    public enum State { FLUTTER, ORBIT, TELL, DART, RECOVER }

    public enum Event { NONE, TELL, DART, DART_END }

    private State state = State.FLUTTER;
    private int ticks;
    private int cooldown = FLY_COOLDOWN_MIN;

    public State state() { return state; }

    public int ticksInState() { return ticks; }

    private void go(State s) {
        state = s;
        ticks = 0;
    }

    /** One server tick. rand(bound) returns 0..bound-1. */
    public Event tick(boolean prey, double distance, boolean inSight, IntUnaryOperator rand) {
        ticks++;
        if (cooldown > 0) cooldown--;
        switch (state) {
            case FLUTTER -> {
                if (prey) go(State.ORBIT);
            }
            case ORBIT -> {
                if (!prey) go(State.FLUTTER);
                else if (cooldown == 0 && inSight && distance <= FLY_DART_RANGE) {
                    go(State.TELL);
                    return Event.TELL;
                }
            }
            case TELL -> {
                if (!prey) go(State.FLUTTER);
                else if (ticks >= FLY_TELL_TICKS) {
                    go(State.DART);
                    return Event.DART;
                }
            }
            case DART -> {
                if (ticks >= FLY_DART_TICKS) {
                    go(State.RECOVER);
                    return Event.DART_END;
                }
            }
            case RECOVER -> {
                if (ticks >= FLY_RECOVER_TICKS) {
                    go(prey ? State.ORBIT : State.FLUTTER);
                    cooldown = FLY_COOLDOWN_MIN + rand.applyAsInt(FLY_COOLDOWN_RANDOM);
                }
            }
        }
        return Event.NONE;
    }

    /** The dart struck home: it flares off at once. */
    public void struck() {
        if (state == State.DART) go(State.RECOVER);
    }

    /** 0 at rest .. 1 with the wings folded tight (the tell and the dart). */
    public float fold() {
        return switch (state) {
            case TELL -> Math.min(1F, ticks / (float) FLY_TELL_TICKS);
            case DART -> 1F;
            case RECOVER -> Math.max(0F, 1F - ticks / 6F);
            default -> 0F;
        };
    }

    /** For tests: skip (or set) the wait before the next heart shot. */
    public void forceCooldown(int t) { cooldown = t; }
}
