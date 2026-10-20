package com.rincoltech.bms.core.notifications.internal;

/**
 * Sends one Web Push message to one subscription. The real one, {@link WebPushSender}, encrypts the payload and calls the
 * browser vendor's push service; tests replace it with a recording fake, so no test touches a network. Nothing here may log
 * the endpoint, the keys or the payload.
 */
interface PushSender {

    /**
     * What came of a send. {@link #SENT}: the push service accepted it. {@link #GONE}: the subscription no longer exists (404
     * or 410) and is to be deleted. {@link #REJECTED}: the push service or this server refuses this subscription (any other
     * 4xx, a bad key, an endpoint outside the allowed hosts) and retrying cannot help. {@link #TRANSIENT}: not accepted now
     * (429, 5xx, a network failure, a timeout) and worth retrying.
     */
    enum Outcome {
        SENT,
        GONE,
        REJECTED,
        TRANSIENT
    }

    /** One device: where to post, and the two values the payload is encrypted with. */
    record Target(String endpoint, String p256dh, String auth) {

        @Override
        public String toString() {
            return "Target[]";
        }
    }

    /**
     * @param payload the JSON text the service worker reads; at most a few hundred bytes
     * @param ttlSeconds how long the push service may hold the message for an offline device
     */
    Outcome send(Target target, String payload, int ttlSeconds);
}
