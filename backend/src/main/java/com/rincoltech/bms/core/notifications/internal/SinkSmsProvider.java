package com.rincoltech.bms.core.notifications.internal;

/**
 * The provider that sends nothing (ADR-036 decision 4): it records that a message would have gone. It is
 * the only one that may run outside production without any provider account, and the staging default;
 * outside production the real adapter may run too, but only against the provider's sandbox (allowlist
 * mode, guarded at start). It holds no HTTP client, no credentials and no URL, so it cannot reach a
 * network by construction; the startup guard keeps it out of production.
 */
final class SinkSmsProvider implements SmsProvider {

    static final String NAME = "sink";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome send(String to, String body, String senderId, String clientRef) {
        return new Outcome(NAME, Status.SUNK, "sink-" + Integer.toHexString(clientRef.hashCode()), null, null, null);
    }
}
