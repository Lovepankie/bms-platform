package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.AdminSecurity;
import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PaymentGateway.Account;
import com.rincoltech.bms.core.payments.PaymentGateway.IpnType;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lender's own payment account: "Receive payments" (ADR-035 amendment of 2026-10-18, issue #233). A tenant
 * administrator enters the consumer key and secret of the lender's own Pesapal merchant account; the secret is
 * write-only (sealed with the application data key and never returned by any endpoint, log line or audit row); Test
 * connection asks the provider for a token and registers this account's notification url, which is how the account
 * becomes {@code verified}. Until it is, nothing is collected for the tenant, and the platform's own account is never
 * a substitute ({@link PaymentGateway.Account}).
 *
 * <p>Changing the account changes where every later repayment is paid, so: only the holders of
 * {@link #MANAGE} (the tenant admin) may do it; saving and removing ask for a second-factor code (step-up); every
 * change is audited and tells the other holders by email; the platform's own consumer key is refused, and so is a
 * consumer key another tenant is already connected with (a keyed digest, unique across tenants).
 *
 * <p>Saving and testing are separate steps on purpose. Saving makes no outbound call, so it can never fail because the
 * provider is down; testing makes exactly the calls of a real order's first use (token, then registration) and nothing
 * else, outside any transaction (spec principle 4), and at most once every half minute for a business. A refusal of the
 * credentials or of the registration marks the account {@code failed}; a provider that could not be reached, a backoff
 * after an earlier refusal and a bare 401 mark nothing, because a hiccup must not switch a lender's collections off.
 * Credentials are not replaced, and the account is not removed, while an intent still needs them (one still scheduled
 * for a check), unless the consumer key is unchanged (a new secret for the same account); the platform operator can
 * release an account in that case (audited).
 */
@Service
class MerchantAccountService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAccountService.class);

    /** The permission that changes where a lender's repayments are paid: the tenant admin's alone (migration V57). */
    static final String MANAGE = "core.payment_account.manage";

    /** The purpose of the blind index of a consumer key. */
    static final String KEY_INDEX = "payment-account-key";

    /** One test a half minute for a business: the provider throttles registrations and every test registers an address. */
    static final Duration TEST_SPACING = Duration.ofSeconds(30);

    static final String TEMPLATE_CHANGED = "payments.account_changed";

    /** What a consumer key or secret may look like: printable ASCII without spaces, 8 to 256 characters. */
    private static final Pattern CREDENTIAL = Pattern.compile("^[\\x21-\\x7E]{8,256}$");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'");

    /**
     * What the screen and the operator may read. There is no field for the secret, and none for the key beyond its
     * last four characters, which only a holder of the manage permission is given.
     */
    @Schema(name = "MerchantAccount")
    record View(
            @Schema(description = "not_connected, verified or failed")
            String status,

            @Schema(description = "Credentials are saved but not yet tested")
            boolean credentialsSaved,

            @Schema(
                    description =
                            "The last four characters of the consumer key, or null (given to those who may change the account only)")
            String keyHint,

            @Schema(description = "When the last test was made, or null")
            Instant lastCheckedAt,

            @Schema(
                    description =
                            "A closed word saying why the last test failed: invalid_credentials or registration_refused")
            String failureReason,

            @Schema(
                    description =
                            "The host can make the calls a test needs (given to those who may change the account only)")
            boolean hostReady) {}

    /** The input of a save. {@link #toString} names no value. */
    record Credentials(String consumerKey, String consumerSecret, String totpCode) {

        @Override
        public String toString() {
            return "Credentials[]";
        }
    }

    private final AccountStore store;
    private final PaymentGateway gateway;
    private final PesapalSettings settings;
    private final DataKeys keys;
    private final AuditLog audit;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AdminSecurity security;
    private final Outbox outbox;
    private final VerifiedAccounts verifiedAccounts;
    private final com.rincoltech.bms.core.jobs.TenantJobs tenantJobs;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;
    private final Map<UUID, Instant> lastTest = new ConcurrentHashMap<>();

    MerchantAccountService(
            AccountStore store,
            PaymentGateway gateway,
            PesapalSettings settings,
            DataKeys keys,
            AuditLog audit,
            CurrentTenant tenant,
            BusinessClock clock,
            AdminSecurity security,
            Outbox outbox,
            VerifiedAccounts verifiedAccounts,
            com.rincoltech.bms.core.jobs.TenantJobs tenantJobs,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.gateway = gateway;
        this.settings = settings;
        this.keys = keys;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
        this.security = security;
        this.outbox = outbox;
        this.verifiedAccounts = verifiedAccounts;
        this.tenantJobs = tenantJobs;
        this.tx = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    /** Forgets when each business last tested (for tests that move the clock back). */
    void forgetTests() {
        lastTest.clear();
    }

    View view() {
        return read.execute(status -> view(store.find().orElse(null)));
    }

    /** Whether the signed-in user may change the account: only they see the key's last four characters and the host's state. */
    private static boolean manager() {
        return CurrentPrincipal.require().permissions().contains(MANAGE);
    }

    private View view(AccountStore.Account row) {
        boolean manager = manager();
        boolean ready = manager && settings.tenantAccountsEnabled();
        if (row == null) {
            return new View("not_connected", false, null, null, null, ready);
        }
        String hint = manager ? row.keyHint() : null;
        return switch (row.status()) {
            case "verified" -> new View("verified", true, hint, row.lastCheckedAt(), null, ready);
            case "failed" -> new View("failed", true, hint, row.lastCheckedAt(), row.failureReason(), ready);
            default -> new View("not_connected", true, hint, row.lastCheckedAt(), null, ready);
        };
    }

    /** True when the bound tenant has a connected and tested account of its own (read in the caller's transaction). */
    boolean verified() {
        return verifiedMerchant().isPresent();
    }

    /** The merchant account of the bound tenant when it is connected and tested, for an intent to record. */
    java.util.Optional<UUID> verifiedMerchant() {
        return verifiedAccounts.merchant();
    }

    /**
     * As {@link #verifiedMerchant()} for a caller that records the merchant in the transaction it is in (an intent): the
     * account row is share-locked until that transaction ends, so a key change cannot slip in between (issue #309).
     */
    java.util.Optional<UUID> verifiedMerchantForShare() {
        return verifiedAccounts.merchantForShare();
    }

    /** Saves the credentials sealed with the data key. Makes no call to the provider. */
    View save(Credentials input) {
        String key = input.consumerKey() == null ? "" : input.consumerKey().trim();
        String secret =
                input.consumerSecret() == null ? "" : input.consumerSecret().trim();
        List<FieldProblem> problems = new ArrayList<>();
        if (!CREDENTIAL.matcher(key).matches()) {
            problems.add(new FieldProblem(
                    "consumer_key", "invalid", "Paste the consumer key exactly as Pesapal shows it, with no spaces."));
        }
        if (!CREDENTIAL.matcher(secret).matches()) {
            problems.add(new FieldProblem(
                    "consumer_secret",
                    "invalid",
                    "Paste the consumer secret exactly as Pesapal shows it, with no spaces."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        // The second factor first, outside the transaction: a wrong code counts toward the lockout and rolls nothing
        // back, and nothing about the key (is it the platform's?) is told to someone who has not passed it.
        security.requireTotp(input.totpCode());
        if (settings.isRefusedKey(key)) {
            throw inUse();
        }
        UUID user = CurrentPrincipal.require().userId();
        String hmac = keys.blindIndex(KEY_INDEX, PesapalSettings.fold(key));
        View saved;
        try {
            saved = tx.execute(status -> {
                AccountStore.Account before = store.lock().orElse(null);
                // The same consumer key (a new secret for the account) is the same merchant; another key is another
                // one.
                UUID merchantId = merchantIdOf(tenant.profile().id(), key);
                if (store.keyInUseElsewhere(keyDigests(key), tenant.profile().id())) {
                    throw inUse();
                }
                if (before != null
                        && !merchantId.equals(before.merchantId())
                        && store.hasWorkInFlight(before.merchantId())) {
                    throw inFlight();
                }
                Instant now = clock.now();
                UUID id = before == null ? UUID.randomUUID() : before.id();
                String hint = key.substring(key.length() - 4);
                store.save(
                        id,
                        tenant.profile().id(),
                        merchantId,
                        now,
                        keys.seal(key.getBytes(StandardCharsets.UTF_8)),
                        keys.seal(secret.getBytes(StandardCharsets.UTF_8)),
                        hmac,
                        hint,
                        keys.currentKeyId(),
                        user);
                audit.record(new AuditLog.Entry(
                        "core.payments.account.saved",
                        "payment_gateway_account",
                        id,
                        null,
                        state(before == null ? null : before.status(), before == null ? null : before.keyHint()),
                        state("saved", hint)));
                int retired = store.retireIntentsOfOtherMerchants(merchantId, now);
                if (retired > 0) {
                    audit.record(new AuditLog.Entry(
                            "core.payments.account.intents_retired",
                            "payment_gateway_account",
                            id,
                            null,
                            Map.of(),
                            Map.of("intents", retired, "reason", "merchant_changed")));
                }
                notifyHolders(id, "saved", null, now);
                return view(store.find().orElseThrow());
            });
        } catch (DuplicateKeyException e) {
            // Two lenders saving the same merchant account at once: the unique digest decides, not our check.
            throw inUse();
        }
        log.info("payment account credentials saved");
        return saved;
    }

    /**
     * The digests a stored key could be found by: the case-folded form (what is stored since #309) and, for a row saved before
     * that, the key exactly as entered, each under the current data key and every retired one.
     */
    private List<String> keyDigests(String key) {
        List<String> all = new ArrayList<>(keys.blindIndexes(KEY_INDEX, PesapalSettings.fold(key)));
        all.addAll(keys.blindIndexes(KEY_INDEX, key));
        return all;
    }

    private static ApiException inUse() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "merchant_account_in_use",
                "This account cannot be used",
                "This merchant account is already connected elsewhere. Use your own Pesapal account.");
    }

    /**
     * Test connection: asks the provider for a token with the saved credentials and registers this account's
     * notification url. Not in a transaction; the result is written in one.
     */
    View test() {
        AccountStore.Account row = read.execute(status -> store.find().orElse(null));
        if (row == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "account_not_saved",
                    "Nothing to test",
                    "Enter the consumer key and secret first.");
        }
        if (!settings.tenantAccountsEnabled() || settings.callbackBase() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "gateway_not_configured",
                    "Not available",
                    "The payment provider is not set up on this host yet. Ask the platform operator.");
        }
        Instant started = clock.now();
        UUID tenantId = tenant.profile().id();
        Instant previous = lastTest.get(tenantId);
        if (previous != null && started.isBefore(previous.plus(TEST_SPACING))) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "test_too_soon",
                    "Try again shortly",
                    "A test was made a moment ago. Wait half a minute before testing again.");
        }
        lastTest.put(tenantId, started);
        UUID user = CurrentPrincipal.require().userId();
        if (verifiedAccounts.refused(row)) {
            // Saved while the list was empty or before this key was added to it: it can never become a lender's
            // account.
            return failed(row, "account_not_allowed", user);
        }
        // An account whose notification address is already registered keeps it (orders made before notify that address,
        // and a
        // new random token would kill it): the test only asks whether the credentials work. A new registration is made
        // only
        // when there is none.
        boolean registered = row.ipnId() != null;
        String token = registered ? null : AccountTokens.newToken();
        String url = registered ? null : settings.callbackBase() + PaymentsConfiguration.IPN_PATH + token;
        String ipnId = null;
        try {
            if (registered) {
                gateway.checkCredentials(Account.tenant(tenantId));
            } else {
                ipnId = gateway.registerIpn(Account.tenant(tenantId), url, IpnType.GET)
                        .ipnId();
            }
        } catch (GatewayException e) {
            switch (e.kind()) {
                case AUTH -> {
                    if (credentialsRefused(e)) {
                        return failed(row, "invalid_credentials", user);
                    }
                    throw busy();
                }
                case REFUSED -> {
                    return failed(row, "registration_refused", user);
                }
                case THROTTLED -> throw busy();
                default ->
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "provider_unreachable",
                            "Could not reach the provider",
                            "The payment provider could not be reached. Nothing was changed; try again shortly.");
            }
        }
        String registeredId = ipnId;
        return tx.execute(status -> {
            AccountStore.Account current = store.lock().orElse(null);
            if (current == null
                    || !current.id().equals(row.id())
                    || !current.updatedAt().equals(row.updatedAt())) {
                // The credentials were replaced or removed while the test ran: this result belongs to the old ones.
                throw changed();
            }
            Instant now = clock.now();
            if (registered) {
                if (store.markChecked(row.id(), now, user) == 0) {
                    throw changed();
                }
            } else {
                store.markVerified(row.id(), now, registeredId, AccountTokens.digest(token), user);
            }
            audit.record(new AuditLog.Entry(
                    "core.payments.account.verified",
                    "payment_gateway_account",
                    row.id(),
                    null,
                    state(row.status(), row.keyHint()),
                    state("verified", row.keyHint())));
            notifyHolders(row.id(), "tested and working", null, now);
            return view(store.find().orElseThrow());
        });
    }

    /** The provider said the key and secret are wrong, as against a backoff or a bare 401 that says nothing about them. */
    private static boolean credentialsRefused(GatewayException e) {
        return e.code() != null && !e.code().equals("auth_backoff");
    }

    private static ApiException busy() {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "provider_busy",
                "Try again shortly",
                "The payment provider asked us to wait. Try again in a minute.");
    }

    private static ApiException changed() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "account_changed",
                "The account changed",
                "The credentials were changed while the test ran. Test again.");
    }

    private View failed(AccountStore.Account tested, String reason, UUID user) {
        return tx.execute(status -> {
            AccountStore.Account current = store.lock().orElse(null);
            // A refusal of the credentials that were tested says nothing about credentials saved since.
            if (current == null
                    || !current.id().equals(tested.id())
                    || !current.updatedAt().equals(tested.updatedAt())) {
                throw changed();
            }
            Instant now = clock.now();
            store.markFailed(tested.id(), now, reason, user);
            audit.record(new AuditLog.Entry(
                    "core.payments.account.failed",
                    "payment_gateway_account",
                    tested.id(),
                    null,
                    state(current.status(), current.keyHint()),
                    state("failed", current.keyHint(), reason)));
            notifyHolders(tested.id(), "tested and refused by the provider", null, now);
            return view(store.find().orElseThrow());
        });
    }

    /** Removes the credentials. Needs the second factor. Refused while an order or a review still needs them. */
    View disconnect(String totpCode) {
        security.requireTotp(totpCode);
        return tx.execute(status -> {
            AccountStore.Account before = store.lock().orElse(null);
            if (before == null) {
                return view(null);
            }
            if (store.hasWorkInFlight(before.merchantId())) {
                throw inFlight();
            }
            store.delete(before.id());
            Instant now = clock.now();
            audit.record(new AuditLog.Entry(
                    "core.payments.account.removed",
                    "payment_gateway_account",
                    before.id(),
                    null,
                    state(before.status(), before.keyHint()),
                    state("not_connected", null)));
            notifyHolders(before.id(), "removed", null, now);
            return view(null);
        });
    }

    /**
     * Tells every holder of the permission, the one who made the change included (a business with one administrator must hear
     * of a hijacked session's change), by email through the outbox: what changed, who, and when; never a value.
     */
    private void notifyHolders(UUID accountId, String change, String who, Instant now) {
        String name = who != null ? who : security.actorName();
        for (AdminSecurity.Holder holder : security.holders(MANAGE)) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("business_name", tenant.profile().name());
            params.put("change", change);
            params.put("who", name == null || name.isBlank() ? "an administrator" : name);
            params.put("when", WHEN.format(now.atZone(ZoneOffset.UTC)));
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    holder.email(),
                    TEMPLATE_CHANGED,
                    params,
                    TEMPLATE_CHANGED + ":" + accountId + ":" + change.hashCode() + ":" + now.toEpochMilli() + ":"
                            + holder.userId(),
                    null,
                    null));
        }
    }

    /** The platform operator released the tenant's account: its administrators are told, in their own tenant's transaction. */
    void notifyReleased(UUID tenantId, UUID accountId) {
        // callAs, not callForTenant: that refuses a suspended tenant, whose administrators should still hear of it. The
        // id is
        // the operator's own request on the platform host (an existing tenant, checked by the release itself).
        tenantJobs.callAs(
                tenantId,
                () -> tx.execute(status -> {
                    notifyHolders(accountId, "removed by the platform operator", "the platform operator", clock.now());
                    return null;
                }));
    }

    /**
     * The merchant account a consumer key names, the same for the same key of the same tenant wherever and whenever it is saved
     * (so reconnecting the same key after a release is the same merchant, and its orders are still booked), and another for
     * another key. A name-based id of the tenant and the SHA-256 of the key: the key itself is not recoverable from it.
     */
    static UUID merchantIdOf(UUID tenantId, String key) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + java.util.HexFormat.of().formatHex(PesapalSettings.sha256(key)))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException inFlight() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "payments_in_flight",
                "Payments are still being confirmed",
                "Some payments still need this account to be confirmed. Wait until they finish, or keep the same consumer key and change only the secret.");
    }

    /** What an audit row says about an account: a status word and the key's last four characters, never a secret. */
    private static Map<String, Object> state(String status, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status == null ? "not_connected" : status);
        if (hint != null) {
            m.put("key_hint", hint);
        }
        return m;
    }

    private static Map<String, Object> state(String status, String hint, String reason) {
        Map<String, Object> m = state(status, hint);
        m.put("reason", reason);
        return m;
    }
}
