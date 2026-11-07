package com.floraferox;

/**
 * The mind of 1.2's six and 1.4's wild mobs (docs/DESIGN.md, sections 8 and 10): the Mire Ghast's fighter behaviour with each
 * mob's own timings (VerdureRules.Ferox, VerdureRules.Wild). It closes on its prey; in reach it stops and shows its
 * tell; the blow lands as the tell ends.
 *
 * <pre>
 * WALK -(prey within reach, cooldown done)-> TELL -> STRIKE -> RECOVER -> WALK
 * </pre>
 */
public final class StrikeBrain {
    public enum State { WALK, TELL, STRIKE, RECOVER }

    public enum Event { NONE, TELL, STRIKE }

    /** A mob's timings: its tell, blow and recovery in ticks, the cooldown after, the reach that starts the tell. */
    public interface Timing {
        int tell();

        int strike();

        int recover();

        int cooldown();

        double reach();

        /** Where its sheets' tell ends and its blow ends, in the attack's 0..1. */
        float tellEnd();

        float strikeEnd();
    }

    public final Timing profile;
    private State state = State.WALK;
    private int ticks;
    private int cooldown;

    public StrikeBrain(Timing profile) { this.profile = profile; }

    public State state() { return state; }

    public int ticksInState() { return ticks; }

    public void forceCooldown(int c) { cooldown = c; }

    private void go(State s) {
        state = s;
        ticks = 0;
    }

    public Event tick(boolean prey, double distance) {
        ticks++;
        if (cooldown > 0) cooldown--;
        switch (state) {
            case WALK -> {
                if (prey && cooldown == 0 && distance <= profile.reach()) {
                    go(State.TELL);
                    return Event.TELL;
                }
            }
            case TELL -> {
                if (!prey) go(State.WALK);
                else if (ticks >= profile.tell()) {
                    go(State.STRIKE);
                    return Event.STRIKE;
                }
            }
            case STRIKE -> {
                if (ticks >= profile.strike()) go(State.RECOVER);
            }
            case RECOVER -> {
                if (ticks >= profile.recover()) {
                    go(State.WALK);
                    cooldown = profile.cooldown();
                }
            }
        }
        return Event.NONE;
    }

    /** Whether it stands still to strike (the tell and the blow). */
    public boolean rooted() { return state == State.TELL || state == State.STRIKE; }

    /** Where the attack is, 0..1, as the review sheets draw it (0 outside it): the tell, then the blow, then back. */
    public static float attack(Timing f, State st, float ticks) {
        return switch (st) {
            case TELL -> f.tellEnd() * Math.min(1F, ticks / f.tell());
            case STRIKE -> f.tellEnd() + (f.strikeEnd() - f.tellEnd()) * Math.min(1F, ticks / f.strike());
            case RECOVER -> f.strikeEnd() + (1F - f.strikeEnd()) * Math.min(1F, ticks / f.recover());
            default -> 0F;
        };
    }
}
