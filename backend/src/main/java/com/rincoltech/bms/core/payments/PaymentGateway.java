package com.rincoltech.bms.core.payments;

import java.util.UUID;

/**
 * The gateway port for hosted orders (ADR-035 decision 1, spec 3.3, chapter 12 section 12.4.1): the payer opens a link,
 * chooses a method on the provider's page and pays; the provider tells us, loosely, that something changed; and the
 * only evidence of payment is {@link #queryStatus}. Amounts are integer minor units at this boundary; the adapter
 * converts to the provider's decimal and back, once, and refuses a value its currency cannot hold.
 *
 * <p>Every method names the {@link Account} the call is made for: an IPN registration, an order and a token
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

    /**
     * The merchant account a call is made for. A tenant account is that lender's own and nothing else: there is no way
     * to ask for "the platform's account on behalf of a tenant", because the platform scope carries no tenant and the
     * tenant scope must carry one (ADR-035 amendment of 2026-10-18, issue #233). An adapter resolves the credentials from
     * the account alone and never falls back from one scope to the other.
     */
    record Account(Scope scope, UUID tenantId, UUID merchantId) {

        /** Any merchant account of the tenant (a connection test, a new order): the tenant's current one. */
        public Account(Scope scope, UUID tenantId) {
            this(scope, tenantId, null);
        }

        public Account {
            if (scope == null) {
                throw new IllegalArgumentException("an account needs a scope");
            }
            if (scope == Scope.TENANT && tenantId == null) {
                throw new IllegalArgumentException("a tenant account belongs to a tenant");
            }
            if (scope == Scope.PLATFORM && (tenantId != null || merchantId != null)) {
                throw new IllegalArgumentException("the platform account belongs to no tenant");
            }
        }

        public static Account tenant(UUID tenantId) {
            return new Account(Scope.TENANT, tenantId);
        }

        /**
         * The tenant's account as it was when an order was made: credentials resolve only while the stored account is
         * still that same merchant account (same consumer key); a different one is "no credentials", never a guess.
         */
        public static Account tenant(UUID tenantId, UUID merchantId) {
            return new Account(Scope.TENANT, tenantId, merchantId);
        }

        public static Account platform() {
            return new Account(Scope.PLATFORM, null);
        }

        /**
         * The account that may receive the money of a stored intent: a tenant intent only ever uses its own tenant's
         * account, and the platform's account is used only for a platform purpose (spec 3.11, the own-account rule).
         * Anything else is refused here, in code, whatever the row says.
         */
        public static Account forIntent(String scopeKey, UUID tenantId, String purpose) {
            return forIntent(scopeKey, tenantId, purpose, null);
        }

        /** As above, bound to the merchant account the intent was created under (null for an intent made before that was recorded). */
        public static Account forIntent(String scopeKey, UUID tenantId, String purpose, UUID merchantId) {
            Scope scope = Scope.ofKey(scopeKey);
            if (scope == Scope.PLATFORM) {
                if (purpose == null || !purpose.startsWith("platform.")) {
                    throw new IllegalStateException("the platform account is only for platform purposes");
                }
                return platform();
            }
            return tenant(tenantId, merchantId);
        }

        @Override
        public String toString() {
            return "Account[" + scope.key() + "]";
        }
    }

    /** The adapter's key, stored on the intent ({@code pesapal}). */
    String providerKey();

    /** True when calls for this account can be made: the platform's pair is in the host environment, or the lender's own credentials are saved and usable. */
    boolean available(Account account);

    /**
     * Creates the hosted order. The same merchant reference and body repeated after a lost answer returns the original
     * order (observed, spec 3.12), so a caller may repeat it; it must still check {@link OrderResult#merchantReference}
     * and the first status answer.
     */
    OrderResult createOrder(Account account, OrderRequest request) throws GatewayException;

    /** The truth about an order: asked with our own credentials, decided on the status code, never on a description. */
    StatusResult queryStatus(Account account, String providerReference) throws GatewayException;

    /** Best effort; the provider allows it for a pending or failed order, once. */
    void cancelOrder(Account account, String providerReference) throws GatewayException;

    /**
     * Asks the provider for a fresh token with the account's own credentials and does nothing else: do they work? Used to test an
     * account whose notification address is already registered, so the address orders were made with stays valid.
     */
    void checkCredentials(Account account) throws GatewayException;

    /** Registers the notification url for the account's pair (an operator command). Idempotent at the provider. */
    IpnRegistration registerIpn(Account account, String url, IpnType type) throws GatewayException;

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
