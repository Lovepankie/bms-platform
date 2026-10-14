package com.rincoltech.bms.core.notifications.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The recording fake (spec section 8): keeps every message it is given in memory, answers
 * {@code ACCEPTED} with a fabricated cost, and can be told to fail, time out or refuse, so the
 * channel's outcomes are testable without a network. Refused in production by the startup guard.
 * It logs nothing.
 */
final class RecordingSmsProvider implements SmsProvider {

    record Sent(String to, String body, String senderId, String clientRef) {}

    /** What a send does: answer, or throw one of the two provider exceptions. */
    @FunctionalInterface
    interface Answer {
        Outcome get() throws Transient, Ambiguous;
    }

    private final List<Sent> sent = new ArrayList<>();
    private Answer next = () -> new Outcome("fake", Status.ACCEPTED, null, 1, "UGX 32.0000", null);
    private int counter;

    @Override
    public String name() {
        return "fake";
    }

    synchronized void answer(Answer answer) {
        this.next = answer;
    }

    synchronized void failWith(String reason) {
        this.next = () -> {
            throw new Transient(reason);
        };
    }

    synchronized void timeOut(String reason) {
        this.next = () -> {
            throw new Ambiguous(reason);
        };
    }

    @Override
    public synchronized Outcome send(String to, String body, String senderId, String clientRef)
            throws Transient, Ambiguous {
        sent.add(new Sent(to, body, senderId, clientRef));
        counter++;
        Outcome outcome = next.get();
        return outcome.messageId() != null || outcome.status() != Status.ACCEPTED
                ? outcome
                : new Outcome(
                        outcome.provider(),
                        outcome.status(),
                        "fake-" + counter,
                        outcome.providerSegments(),
                        outcome.costRaw(),
                        outcome.failureReason());
    }

    synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }
}
