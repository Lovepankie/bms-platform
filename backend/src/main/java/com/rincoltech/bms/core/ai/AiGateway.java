package com.rincoltech.bms.core.ai;

import java.util.UUID;

/**
 * The one way to reach a language model (ADR-037). A call runs for the bound tenant and is
 * answered in one of two ways: an answer, or a fallback with a closed
 * {@link Reason} so the caller can show its deterministic text (spec 5.7); a provider problem never
 * reaches the caller as an exception. Everything that can
 * refuse a call does so before any network call: the tenant switch, the platform allow-list, an
 * unconfigured provider, a request over the per call cap, a request that looks like it carries personal data, the daily token and cost caps of the
 * tenant and of the platform, and an open circuit breaker.
 *
 * <p>Call it outside a transaction: it opens its own short transactions to reserve the call
 * against the caps and to finish the log row, and the network call runs between them with no
 * database connection held. The caller must not pass anything but aggregates and aliases (spec 5.4);
 * the gateway never stores the text it is given, only its hash.
 */
public interface AiGateway {

    /**
     * @param purpose a lower case word for the log: {@code brief}, {@code question}, {@code evaluation}
     * @param askedBy the staff user who asked, for the log only and never sent; null for a job
     */
    AiResult complete(String purpose, UUID askedBy, LlmClient.Request request);

    /** Why a call did not produce an answer. The names are the values stored in {@code ai_calls.reason}. */
    enum Reason {
        NOT_ENABLED("not_enabled"),
        NOT_ALLOWED("not_allowed"),
        PROVIDER_OFF("provider_off"),
        INPUT_TOO_LARGE("input_too_large"),
        /** The text held something that looks like personal data (digits, an email, a phone, an ID number). */
        INPUT_REFUSED("input_refused"),
        /** Our own failure, not the provider's (for example the database): nothing was sent. */
        INTERNAL_ERROR("internal_error"),
        TENANT_TOKEN_CAP("tenant_token_cap"),
        TENANT_COST_CAP("tenant_cost_cap"),
        PLATFORM_TOKEN_CAP("platform_token_cap"),
        PLATFORM_COST_CAP("platform_cost_cap"),
        BREAKER_OPEN("breaker_open"),
        TIMEOUT("timeout"),
        NETWORK("network"),
        RATE_LIMITED("rate_limited"),
        PROVIDER_ERROR("provider_error"),
        AUTH_ERROR("auth_error"),
        BAD_RESPONSE("bad_response"),
        BAD_REQUEST("bad_request");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * @param text the model's answer; null for a fallback
     * @param reason null when the call was answered
     * @param callId the {@code ai_calls} row; null when nothing was logged (the feature is off)
     */
    record AiResult(String text, int inputTokens, int outputTokens, String finishReason, Reason reason, UUID callId) {

        public boolean answered() {
            return reason == null;
        }

        public static AiResult fallback(Reason reason, UUID callId) {
            return new AiResult(null, 0, 0, null, reason, callId);
        }

        @Override
        public String toString() {
            return "AiResult[answered=" + answered() + ", reason=" + reason + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens + "]";
        }
    }
}
