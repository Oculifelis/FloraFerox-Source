package com.floraferox;

import static com.floraferox.VerdureRules.*;

import java.util.function.IntUnaryOperator;

/**
 * The Moon Tick's mind (docs/DESIGN.md, sections 2.4 and 3): Terraria's slime behaviour as Hoppin' Jack hops it. It
 * waits on the ground, crouches (the tell), bounds at its prey, opens its wing cases in the air so the bound carries,
 * and lands with a crunch. With no prey it hops now and then, slower.
 *
 * <pre>
 * REST -(wait done)-> CROUCH -> AIR -(lands)-> LAND -> REST
 * </pre>
 */
public final class MoonTickBrain {
    public enum State { REST, CROUCH, AIR, LAND }

    public enum Event { NONE, CROUCH, JUMP, LANDED }

    private State state = State.REST;
    private int ticks;
    private int wait = LOUSE_WAIT_MIN;

    public State state() { return state; }

    public int ticksInState() { return ticks; }

    private void go(State s) {
        state = s;
        ticks = 0;
    }

    public Event tick(boolean prey, boolean onGround, IntUnaryOperator rand) {
        ticks++;
        switch (state) {
            case REST -> {
                if (onGround && --wait <= 0) {
                    go(State.CROUCH);
                    return Event.CROUCH;
                }
            }
            case CROUCH -> {
                if (ticks >= LOUSE_CROUCH_TICKS) {
                    go(State.AIR);
                    return Event.JUMP;
                }
            }
            case AIR -> {
                if (onGround && ticks > 3) {
                    go(State.LAND);
                    return Event.LANDED;
                }
            }
            case LAND -> {
                if (ticks >= LOUSE_LAND_TICKS) {
                    go(State.REST);
                    wait = prey ? LOUSE_WAIT_MIN + rand.applyAsInt(LOUSE_WAIT_RANDOM) : LOUSE_IDLE_WAIT_MIN + rand.applyAsInt(LOUSE_IDLE_WAIT_RANDOM);
                }
            }
        }
        return Event.NONE;
    }

    /** 0..1: the wing cases open (only in the air). */
    public float open() {
        return state == State.AIR ? Math.min(1F, ticks / 4F) : 0F;
    }
}
