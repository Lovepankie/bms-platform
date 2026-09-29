package com.rincoltech.bms.kernel;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant the current thread works for (ADR-003).
 *
 * <p>There are exactly two ways a tenant gets here, and neither takes a tenant id from a request
 * body or from a lookup that could match several tenants:
 *
 * <ul>
 *   <li>the tenant resolution filter, from the request host (or the development-only
 *       {@code X-Tenant} header), for the lifetime of one HTTP request;
 *   <li>{@link #callAs(UUID, Supplier)}, used by scheduled jobs that iterate
 *       {@code app_list_active_tenants()} one tenant at a time.
 * </ul>
 *
 * <p>The transaction manager reads this value when a transaction begins and binds it with
 * {@code set_config('app.tenant_id', ..., true)}. Row-level security then enforces it in the
 * database. Service code never passes a tenant id around, and inserts take {@code tenant_id} from
 * {@code current_setting('app.tenant_id')}, so a write path cannot pick a different tenant from
 * the one the request resolved to.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The bound tenant, or an exception. Never a default. */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant is bound to this thread");
        }
        return tenantId;
    }

    /**
     * Binds a tenant for the rest of the current request. Refuses to replace a different tenant,
     * so nothing can switch tenants half way through a request.
     */
    public static void bind(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        UUID existing = CURRENT.get();
        if (existing != null && !existing.equals(tenantId)) {
            throw new IllegalStateException("A different tenant is already bound to this thread");
        }
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs work for one tenant and restores the previous state. Refuses to run inside an open
     * transaction, because that transaction is already bound to whatever tenant (or none) was
     * current when it began, and would not see the new one.
     */
    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("callAs must start outside a transaction; open the transaction inside it");
        }
        UUID previous = CURRENT.get();
        if (previous != null) {
            throw new IllegalStateException("callAs cannot nest inside another tenant's context");
        }
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            CURRENT.remove();
        }
    }
}
