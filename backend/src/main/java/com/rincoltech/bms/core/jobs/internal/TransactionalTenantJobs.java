package com.rincoltech.bms.core.jobs.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TransactionalTenantJobs implements TenantJobs {

    private static final Logger log = LoggerFactory.getLogger(TransactionalTenantJobs.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    TransactionalTenantJobs(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public int forEachActiveTenant(String jobName, Consumer<UUID> work) {
        return run(
                jobName,
                jdbc.sql("SELECT app_list_active_tenants()").query(UUID.class).list(),
                work);
    }

    @Override
    public int forEachActiveTenantWithModule(String jobName, String moduleKey, Consumer<UUID> work) {
        return run(
                jobName,
                jdbc.sql("SELECT app_list_active_tenants_with_module(?)")
                        .param(moduleKey)
                        .query(UUID.class)
                        .list(),
                work);
    }

    @Override
    public <T> T callAsTenant(String slug, String moduleKey, Supplier<T> work) {
        UUID tenantId = jdbc.sql("SELECT id FROM app_resolve_tenant_status(?) WHERE status = 'active'")
                .param(slug)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No active tenant with slug " + slug + "."));
        boolean enabled = jdbc.sql("SELECT app_list_active_tenants_with_module(?)")
                .param(moduleKey)
                .query(UUID.class)
                .list()
                .contains(tenantId);
        if (!enabled) {
            throw new IllegalArgumentException("Tenant " + slug + " does not have the " + moduleKey + " module on.");
        }
        return TenantContext.callAs(tenantId, work);
    }

    private int run(String jobName, List<UUID> tenants, Consumer<UUID> work) {
        int failures = 0;
        for (UUID tenantId : tenants) {
            try {
                // The tenant is bound before the transaction opens, so the transaction manager
                // binds app.tenant_id on its first statement.
                TenantContext.callAs(
                        tenantId,
                        () -> transactions.execute(status -> {
                            work.accept(tenantId);
                            return null;
                        }));
                log.info("job {} ran for tenant {}", jobName, tenantId);
            } catch (RuntimeException e) {
                failures++;
                log.error("job {} failed for tenant {}", jobName, tenantId, e);
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("job " + jobName + " failed for " + failures + " tenant(s)");
        }
        return tenants.size();
    }
}
