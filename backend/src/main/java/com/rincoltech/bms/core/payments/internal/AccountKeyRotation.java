package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import com.rincoltech.bms.core.identity.SealedSecrets;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job {@code core.payments.reseal-accounts} (ADR-008, ADR-035 amendment of 2026-10-18): after the application data key is
 * rotated, every secret sealed with a retired key is opened with it and sealed again with the current key, one tenant at a
 * time, each in that tenant's own transaction: the lenders' stored payment credentials (with their blind-index digest, which
 * depends on the key) and the second-factor seeds of every user, plus the platform operators' seeds. Suspended tenants are
 * included (they are resumed one day and their secrets must open then). The host keeps the retired key in
 * {@code BMS_DATA_KEY_PREVIOUS} until a run logs {@code remaining=0} (runbook: docs/runbooks/payments.md). A tenant whose
 * secret cannot be opened (its key was dropped too early) is logged by id and error type, left as it is, and counted as a
 * failure so the scheduler records and retries it; nothing is ever deleted or guessed. No secret, key or box is logged.
 */
@Component
class AccountKeyRotation {

    private static final Logger log = LoggerFactory.getLogger(AccountKeyRotation.class);

    private final JdbcClient jdbc;
    private final TenantJobs tenantJobs;
    private final AccountStore store;
    private final DataKeys keys;
    private final SealedSecrets secrets;
    private final BusinessClock clock;
    private final TransactionTemplate tx;

    AccountKeyRotation(
            JdbcClient jdbc,
            TenantJobs tenantJobs,
            AccountStore store,
            DataKeys keys,
            SealedSecrets secrets,
            BusinessClock clock,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.keys = keys;
        this.secrets = secrets;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** One pass over up to 500 tenants and the platform operators; returns the number of secrets re-sealed. */
    int run() {
        String current = keys.currentKeyId();
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_sealed_secrets_to_reseal(?, ?)")
                .params(current, 500)
                .query(UUID.class)
                .list();
        int resealed = 0;
        int failures = 0;
        for (UUID tenantId : tenants) {
            Outcome o = resealTenant(tenantId, current);
            resealed += o.resealed();
            failures += o.failures();
        }
        try {
            resealed += tx.execute(status -> secrets.resealPlatformUsers());
        } catch (RuntimeException e) {
            failures++;
            log.error(
                    "platform operator secrets could not be re-sealed: {}",
                    e.getClass().getSimpleName());
        }
        long remaining = jdbc.sql("SELECT app_count_sealed_secrets_to_reseal(?)")
                .param(current)
                .query(Long.class)
                .single();
        log.info(
                "data key re-seal: key={} resealed={} remaining={} tenants={}",
                current,
                resealed,
                remaining,
                tenants.size());
        if (failures > 0) {
            throw new IllegalStateException(failures + " tenant(s) or operator secrets could not be re-sealed");
        }
        return resealed;
    }

    /** What one tenant's re-seal did. */
    record Outcome(int resealed, int failures) {}

    /**
     * One tenant: its payment accounts and its users' second-factor seeds, each in a transaction of its own and as that tenant
     * (suspended ones too: not callForTenant, which refuses them; the id is the platform's own worklist, never a request's),
     * so a problem with a payment account never rolls back the re-seal of the seeds, and the reverse.
     */
    Outcome resealTenant(UUID tenantId, String current) {
        Integer accounts = piece(tenantId, "payment accounts", () -> tx.execute(s -> resealAccounts(current)));
        Integer seeds = piece(tenantId, "second-factor seeds", () -> tx.execute(s -> secrets.resealBoundTenant()));
        return new Outcome(
                (accounts == null ? 0 : accounts) + (seeds == null ? 0 : seeds),
                (accounts == null ? 1 : 0) + (seeds == null ? 1 : 0));
    }

    /** One part of one tenant's re-seal, as that tenant; null (and a log line by type) when it failed. */
    private Integer piece(UUID tenantId, String what, java.util.function.Supplier<Integer> work) {
        try {
            return tenantJobs.callAs(tenantId, work);
        } catch (RuntimeException e) {
            log.error(
                    "{} of tenant {} could not be re-sealed: {}",
                    what,
                    tenantId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** The bound tenant's secrets sealed with another key: payment accounts and second-factor seeds. */
    int resealHere(String current) {
        return resealAccounts(current) + secrets.resealBoundTenant();
    }

    /** The bound tenant's payment accounts sealed with another key (in the caller's transaction). */
    int resealAccounts(String current) {
        int count = 0;
        for (AccountStore.Account row : store.sealedWithOtherKey(current)) {
            String key = new String(keys.open(row.consumerKeyBox()), StandardCharsets.UTF_8);
            byte[] keyBox = keys.seal(key.getBytes(StandardCharsets.UTF_8));
            byte[] secretBox = keys.seal(keys.open(row.consumerSecretBox()));
            store.reseal(
                    row.id(),
                    clock.now(),
                    keyBox,
                    secretBox,
                    keys.blindIndex(MerchantAccountService.KEY_INDEX, key),
                    current);
            count++;
        }
        return count;
    }
}
