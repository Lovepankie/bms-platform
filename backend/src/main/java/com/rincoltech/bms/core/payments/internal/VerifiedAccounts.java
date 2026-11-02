package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Whether the bound tenant has a connected and tested payment account of its own that may be used (issue #233): status
 * {@code verified}, and a consumer key that is not one of the platform operator's own. The key is checked here every time, not
 * only when it was saved: a key saved while the refused list was empty (or before a digest was added to it) is refused from the
 * moment the list names it. Runs in the caller's transaction. A component of its own so that the settings group and the
 * account service can both ask without depending on each other.
 */
@Component
class VerifiedAccounts {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VerifiedAccounts.class);

    /** The unreadable box warning is logged at most once in this time per tenant (issue #311). */
    static final Duration WARN_SPACING = Duration.ofHours(1);

    private final AccountStore store;
    private final DataKeys keys;
    private final PesapalSettings settings;
    private final BusinessClock clock;
    private final Map<UUID, Instant> lastWarned = new ConcurrentHashMap<>();

    VerifiedAccounts(AccountStore store, DataKeys keys, PesapalSettings settings, BusinessClock clock) {
        this.store = store;
        this.keys = keys;
        this.settings = settings;
        this.clock = clock;
    }

    /** The merchant account of the bound tenant when it is connected, tested and allowed. */
    Optional<UUID> merchant() {
        return store.find().filter(this::usable).map(AccountStore.Account::merchantId);
    }

    /**
     * As {@link #merchant()} but reads the account row {@code FOR SHARE} (issue #309): the caller is about to record the
     * merchant on something (a new intent), so a key change, which holds the row {@code FOR UPDATE} while it replaces the
     * merchant, either finishes first (and this sees the new merchant or none) or waits until the caller's transaction ends
     * (and finds the new work in flight). The lock lasts to the end of the caller's transaction.
     */
    Optional<UUID> merchantForShare() {
        return store.findForShare().filter(this::usable).map(AccountStore.Account::merchantId);
    }

    private boolean usable(AccountStore.Account a) {
        return "verified".equals(a.status()) && !refused(a);
    }

    boolean verified() {
        return merchant().isPresent();
    }

    /**
     * True when the stored consumer key is one the platform operator owns, or when that cannot be told: a box that cannot be
     * opened (a data key dropped too early, a damaged row) is treated as refused, never as allowed (issue #309), so an account
     * whose key cannot be read is not collected into. Nothing about the box or the key is logged.
     */
    boolean refused(AccountStore.Account row) {
        return refusal(row).isPresent();
    }

    /**
     * Why the account may not be used, or empty when it may: {@code account_unreadable} when its key box cannot be opened,
     * {@code account_not_allowed} when the key is one of the platform operator's (issue #311). The warning for a box that
     * cannot be opened is logged at most once an hour per tenant, since this runs on every collection check.
     */
    Optional<String> refusal(AccountStore.Account row) {
        try {
            return settings.isRefusedKey(new String(keys.open(row.consumerKeyBox()), StandardCharsets.UTF_8))
                    ? Optional.of("account_not_allowed")
                    : Optional.empty();
        } catch (RuntimeException e) {
            Instant now = clock.now();
            // One atomic step per tenant, so two threads at the same moment log once between them. One entry per tenant
            // that ever had an unreadable box; never pruned, which is small at any expected number of lenders.
            boolean[] due = {false};
            lastWarned.compute(row.tenantId(), (tenant, last) -> {
                due[0] = last == null || !now.isBefore(last.plus(WARN_SPACING));
                return due[0] ? now : last;
            });
            if (due[0]) {
                log.warn(
                        "a payment account's consumer key could not be opened; the account is treated as refused (tenant {})",
                        row.tenantId());
            }
            return Optional.of("account_unreadable");
        }
    }
}
