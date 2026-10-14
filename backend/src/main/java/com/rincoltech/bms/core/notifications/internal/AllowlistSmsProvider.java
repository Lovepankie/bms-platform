package com.rincoltech.bms.core.notifications.internal;

import java.util.Set;

/**
 * Mode {@code allowlist} (ADR-036 decision 4): a real send only to the numbers in
 * {@code BMS_SMS_ALLOWLIST}, every other number goes to the sink. The list is an environment value;
 * the guard has already checked its entries and, outside production, that the real provider points
 * at the sandbox.
 */
final class AllowlistSmsProvider implements SmsProvider {

    private final SmsProvider real;
    private final SmsProvider sink;
    private final Set<String> allowed;

    AllowlistSmsProvider(SmsProvider real, SmsProvider sink, Set<String> allowed) {
        this.real = real;
        this.sink = sink;
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public String name() {
        return real.name();
    }

    @Override
    public Outcome send(String to, String body, String senderId, String clientRef) throws Transient, Ambiguous {
        return allowed.contains(to)
                ? real.send(to, body, senderId, clientRef)
                : sink.send(to, body, senderId, clientRef);
    }
}
