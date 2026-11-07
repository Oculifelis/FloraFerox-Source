package com.floraferox;

import static com.floraferox.VerdureRules.*;

/**
 * The Mire Ghast's mind (docs/DESIGN.md, sections 2.2 and 3): Terraria's fighter behaviour as Angry Bones walks it; here,
 * when it reaches its prey it stops, raises both arms on its root strings (the tell) and slams them down.
 *
 * <pre>
 * WALK -(prey within reach, cooldown done)-> TELL -> SLAM -> RECOVER -> WALK
 * </pre>
 */
public final class MireGhastBrain {
    public enum State { WALK, TELL, SLAM, RECOVER }

    public enum Event { NONE, TELL, SLAM }

    private State state = State.WALK;
    private int ticks;
    private int cooldown;

    public State state() { return state; }

    public int ticksInState() { return ticks; }

    private void go(State s) {
        state = s;
        ticks = 0;
    }

    public Event tick(boolean prey, double distance) {
        ticks++;
        if (cooldown > 0) cooldown--;
        switch (state) {
            case WALK -> {
                if (prey && cooldown == 0 && distance <= GHOUL_REACH) {
                    go(State.TELL);
                    return Event.TELL;
                }
            }
            case TELL -> {
                if (!prey) go(State.WALK);
                else if (ticks >= GHOUL_TELL_TICKS) {
                    go(State.SLAM);
                    return Event.SLAM;
                }
            }
            case SLAM -> {
                if (ticks >= GHOUL_SLAM_TICKS) go(State.RECOVER);
            }
            case RECOVER -> {
                if (ticks >= GHOUL_RECOVER_TICKS) {
                    go(State.WALK);
                    cooldown = GHOUL_COOLDOWN;
                }
            }
        }
        return Event.NONE;
    }

    /** Whether it stands still to strike (the tell and the slam). */
    public boolean rooted() { return state == State.TELL || state == State.SLAM; }

    /** 0..1: the arms raised for the slam (1 at the top of the tell). */
    public float raise() {
        return switch (state) {
            case TELL -> Math.min(1F, ticks / (float) GHOUL_TELL_TICKS);
            case SLAM -> Math.max(0F, 1F - ticks / (float) GHOUL_SLAM_TICKS);
            default -> 0F;
        };
    }
}
