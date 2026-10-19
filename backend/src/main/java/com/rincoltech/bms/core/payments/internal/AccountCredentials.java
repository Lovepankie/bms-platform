package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.payments.PaymentGateway.Account;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where the adapter gets the credentials of an account (ADR-035 amendment of 2026-10-18, issue #233), and the one place
 * the own-account rule is enforced for credentials:
 *
 * <ul>
 *   <li>the platform account is the platform pair from the host environment, and nothing else;
 *   <li>a tenant account is read from that tenant's own row, decrypted with the application data key, for the tenant
 *       that is bound to the thread and no other. A request for another tenant's account, or for a tenant account with
 *       no tenant bound, is answered with nothing; the row-level security of the database would answer the same way, so
 *       a mistake here cannot read a neighbour's secret either;
 *   <li>there is no fallback: a tenant with no usable account gets nothing, never the platform's pair.
 * </ul>
 *
 * A tenant's pair carries the notification registration only while the account is {@code verified}: a failed or
 * untested account can be asked for a token (that is what Test connection does) but cannot make an order.
 */
@Component
class AccountCredentials implements PesapalGateway.Credentials {

    private static final Logger log = LoggerFactory.getLogger(AccountCredentials.class);

    private final PesapalSettings settings;
    private final AccountStore store;
    private final DataKeys keys;
    private final CurrentTenant tenant;
    private final TransactionTemplate read;
    private final com.rincoltech.bms.kernel.BusinessClock clock;
    private final java.util.Map<java.util.UUID, Instant> lastLogged = new java.util.concurrent.ConcurrentHashMap<>();

    AccountCredentials(
            PesapalSettings settings,
            AccountStore store,
            DataKeys keys,
            CurrentTenant tenant,
            com.rincoltech.bms.kernel.BusinessClock clock,
            PlatformTransactionManager transactions) {
        this.settings = settings;
        this.store = store;
        this.keys = keys;
        this.tenant = tenant;
        this.clock = clock;
        // A transaction of its own, so a caller that happens to hold one is never affected and the secret is read once.
        this.read = new TransactionTemplate(transactions);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setReadOnly(true);
    }

    @Override
    public Optional<PesapalSettings.Pair> find(Account account) {
        if (account.scope() == Scope.PLATFORM) {
            return settings.platformPair().usable() ? Optional.of(settings.platformPair()) : Optional.empty();
        }
        try {
            if (!account.tenantId().equals(tenant.profile().id())) {
                log.warn("a payment account of another tenant was asked for and refused");
                return Optional.empty();
            }
            return read.execute(status -> store.find()
                    // Belt and braces beside row-level security: never hand back a row of another tenant.
                    .filter(row -> row.tenantId().equals(account.tenantId()))
                    // An order made under one merchant account is never checked with another's credentials.
                    .filter(row ->
                            account.merchantId() == null || account.merchantId().equals(row.merchantId()))
                    .flatMap(row -> open(row)));
        } catch (RuntimeException e) {
            // No tenant bound, or the key cannot open the box: no credentials. Only the type is logged, once an hour
            // for a tenant, because the poller asks on every pass and a dropped key would flood the log.
            Instant now = clock.now();
            Instant last = lastLogged.get(account.tenantId());
            if (last == null || now.isAfter(last.plus(Duration.ofHours(1)))) {
                lastLogged.put(account.tenantId(), now);
                log.error(
                        "payment account credentials of tenant {} could not be read: {}",
                        account.tenantId(),
                        e.getClass().getSimpleName());
            }
            return Optional.empty();
        }
    }

    private Optional<PesapalSettings.Pair> open(AccountStore.Account row) {
        String key = new String(keys.open(row.consumerKeyBox()), StandardCharsets.UTF_8);
        String secret = new String(keys.open(row.consumerSecretBox()), StandardCharsets.UTF_8);
        String ipnId = "verified".equals(row.status()) ? row.ipnId() : null;
        if (settings.isRefusedKey(key)) {
            // One of the platform operator's own keys: never used for a lender, whatever was saved and when.
            return Optional.empty();
        }
        return Optional.of(new PesapalSettings.Pair(key, secret, ipnId, null));
    }
}
