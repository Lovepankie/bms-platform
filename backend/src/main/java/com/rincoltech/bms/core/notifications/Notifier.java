package com.rincoltech.bms.core.notifications;

import java.util.Map;

/**
 * Sends one message to one recipient. Callers name a template and its parameters; the adapter
 * renders and delivers. Delivery is best effort and never part of the caller's transaction:
 * a flow that must not depend on a provider (a staff invitation, FR-IAM-01) also shows its link
 * to the person who started it.
 */
public interface Notifier {

    void send(Message message);

    /**
     * @param channel {@code email} or {@code sms}
     * @param to an email address or an E.164 phone number; never logged unmasked
     * @param templateKey for example {@code core.staff_invitation}
     */
    record Message(String channel, String to, String templateKey, Map<String, String> params) {

        public Message {
            params = Map.copyOf(params);
        }
    }
}
