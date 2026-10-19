package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
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

    private final AccountStore store;
    private final DataKeys keys;
    private final PesapalSettings settings;

    VerifiedAccounts(AccountStore store, DataKeys keys, PesapalSettings settings) {
        this.store = store;
        this.keys = keys;
        this.settings = settings;
    }

    /** The merchant account of the bound tenant when it is connected, tested and allowed. */
    Optional<UUID> merchant() {
        return store.find()
                .filter(a -> "verified".equals(a.status()))
                .filter(a -> !refused(a))
                .map(AccountStore.Account::merchantId);
    }

    boolean verified() {
        return merchant().isPresent();
    }

    /** True when the stored consumer key is one the platform operator owns. */
    boolean refused(AccountStore.Account row) {
        try {
            return settings.isRefusedKey(new String(keys.open(row.consumerKeyBox()), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
