package com.rincoltech.bms.core.payments.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The provider's circuit breaker (chapter 12 section 12.1, ADR-035 decision 13): after {@code threshold} consecutive
 * failures it opens for {@code openFor}, during which every call is refused without a network call; then it lets exactly
 * one probe call through (half open) and closes on its success or opens again on its failure. One breaker serves the
 * whole process, since the provider's health is not a tenant's. Time comes from the kernel clock so tests move it by hand.
 *
 * <p>Every call that is let through holds a {@link Permit} and reports back with it, exactly once, in a {@code finally}:
 * {@link #recordSuccess}, {@link #recordFailure} or {@link #release}. A permit carries the breaker's <em>generation</em>
 * (it changes each time the breaker opens or closes), so the late result of an ordinary call that began before the
 * breaker opened is ignored. A probe permit also carries its own <em>ticket</em>: only the probe holding the current
 * ticket can close the breaker, open it again, or give back the slot, so a late settle of an older probe changes nothing.
 * (The same shape as the language model gateway's breaker in {@code core.ai}; a module may not reach into another's
 * internals, so this module keeps its own.)
 */
final class CircuitBreaker {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** A call let through. {@code probe} marks the single call that decides a half open breaker. */
    record Permit(long generation, boolean probe, long ticket) {}

    private final int threshold;
    private final Duration openFor;
    private final Supplier<Instant> now;
    private State state = State.CLOSED;
    private long generation;
    private int consecutiveFailures;
    private Instant openedAt;
    private long lastTicket;
    private long probeTicket;

    CircuitBreaker(int threshold, Duration openFor, Supplier<Instant> now) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.now = now;
    }

    /** A permit when a call may go to the provider, empty when it must be refused without a call. */
    synchronized Optional<Permit> tryAcquire() {
        switch (state) {
            case CLOSED:
                return Optional.of(new Permit(generation, false, 0));
            case OPEN:
                if (now.get().isBefore(openedAt.plus(openFor))) {
                    return Optional.empty();
                }
                state = State.HALF_OPEN;
                return Optional.of(newProbe());
            case HALF_OPEN:
                return probeTicket != 0 ? Optional.empty() : Optional.of(newProbe());
            default:
                return Optional.empty();
        }
    }

    private Permit newProbe() {
        probeTicket = ++lastTicket;
        return new Permit(generation, true, probeTicket);
    }

    synchronized void recordSuccess(Permit permit) {
        if (permit.probe()) {
            if (currentProbe(permit)) {
                close();
            }
        } else if (permit.generation() == generation && state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    synchronized void recordFailure(Permit permit) {
        if (permit.probe()) {
            if (currentProbe(permit)) {
                open();
            }
        } else if (permit.generation() == generation && state == State.CLOSED) {
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                open();
            }
        }
    }

    /** A call that gave no verdict on the provider's health: gives back a probe's slot and changes nothing else. */
    synchronized void release(Permit permit) {
        if (permit.probe() && currentProbe(permit)) {
            probeTicket = 0;
        }
    }

    synchronized State state() {
        return state;
    }

    private boolean currentProbe(Permit permit) {
        return state == State.HALF_OPEN && permit.generation() == generation && permit.ticket() == probeTicket;
    }

    private void open() {
        state = State.OPEN;
        generation++;
        openedAt = now.get();
        consecutiveFailures = 0;
        probeTicket = 0;
    }

    private void close() {
        state = State.CLOSED;
        generation++;
        consecutiveFailures = 0;
        probeTicket = 0;
    }
}
