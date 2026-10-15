package com.rincoltech.bms.core.payments;

import java.util.UUID;

/**
 * The gateway port for hosted orders (ADR-035 decision 1, spec 3.3, chapter 12 section 12.4.1): the payer opens a link,
 * chooses a method on the provider's page and pays; the provider tells us, loosely, that something changed; and the
 * only evidence of payment is {@link #queryStatus}. Amounts are integer minor units at this boundary; the adapter
 * converts to the provider's decimal and back, once, and refuses a value its currency cannot hold.
 *
 * <p>Every method names the credential {@link Scope} the call is made for: an IPN registration, an order and a token
 * belong to one credential pair, and a pair's id is useless to another. Calls take no database transaction and must not
 * be made inside one (spec principle 4). {@code read_notification} of the spec is not on this port: a notification is
 * read by the endpoint, which takes two identifiers from it and nothing else.
 */
public interface PaymentGateway {

    /** Which credential pair a call is made with: the lender's merchant account, or the platform's own. */
    enum Scope {
        TENANT("tenant"),
        PLATFORM("platform");

        private final String key;

        Scope(String key) {
            this.key = key;
        }

        /** The value stored in {@code payment_intents.scope}. */
        public String key() {
            return key;
        }

        public static Scope ofKey(String key) {
            for (Scope s : values()) {
                if (s.key.equals(key)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("unknown scope");
        }
    }

    /** The adapter's key, stored on the intent ({@code pesapal}). */
    String providerKey();

    /** True when calls for this scope can be made: the host environment holds everything the scope needs. */
    boolean available(Scope scope);

    /**
     * Creates the hosted order. The same merchant reference and body repeated after a lost answer returns the original
     * order (observed, spec 3.12), so a caller may repeat it; it must still check {@link OrderResult#merchantReference}
     * and the first status answer.
     */
    OrderResult createOrder(Scope scope, OrderRequest request) throws GatewayException;

    /** The truth about an order: asked with our own credentials, decided on the status code, never on a description. */
    StatusResult queryStatus(Scope scope, String providerReference) throws GatewayException;

    /** Best effort; the provider allows it for a pending or failed order, once. */
    void cancelOrder(Scope scope, String providerReference) throws GatewayException;

    /** Registers the notification url for the scope's pair (an operator command). Idempotent at the provider. */
    IpnRegistration registerIpn(Scope scope, String url, IpnType type) throws GatewayException;

    /**
     * @param merchantReference our unique reference, at most 50 characters from the provider's alphabet
     * @param description at most 100 characters, shown to the payer; carries no personal data
     */
    record OrderRequest(
            UUID intentId,
            String merchantReference,
            long amountMinor,
            String currency,
            String payerPhoneE164,
            String description) {

        @Override
        public String toString() {
            return "OrderRequest[merchantReference=" + merchantReference + ", amountMinor=" + amountMinor + "]";
        }
    }

    record OrderResult(String providerReference, String merchantReference, String redirectUrl) {}

    /**
     * What the status query means for the intent. Decided on the provider's numeric status code:
     *
     * <ul>
     *   <li>{@code PENDING}: code 0 with {@code payment_details_not_found}, which is how an unpaid order reads;
     *   <li>{@code COMPLETED}, {@code FAILED}, {@code REVERSED}: codes 1, 2 and 3;
     *   <li>{@code ANOMALY}: anything else (an unknown order, a credential error, an unknown code, an amount with a
     *       fraction of a shilling). Counted and alerted; it never books and never ends an intent by itself.
     * </ul>
     */
    enum Status {
        PENDING,
        COMPLETED,
        FAILED,
        REVERSED,
        ANOMALY
    }

    /**
     * @param amountMinor the amount the provider reports, in minor units; null when absent or not whole
     * @param anomaly a closed word when {@code status} is {@code ANOMALY}, else null
     */
    record StatusResult(
            Status status,
            String providerReference,
            String merchantReference,
            Long amountMinor,
            String currency,
            String paymentMethod,
            String confirmationCode,
            String anomaly) {

        @Override
        public String toString() {
            return "StatusResult[status=" + status + ", anomaly=" + anomaly + "]";
        }
    }

    enum IpnType {
        GET,
        POST
    }

    record IpnRegistration(String ipnId, String url, IpnType type) {}
}
