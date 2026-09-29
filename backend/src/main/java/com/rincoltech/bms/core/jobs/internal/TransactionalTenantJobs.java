package com.rincoltech.bms.core.jobs.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
        List<UUID> tenants =
                jdbc.sql("SELECT app_list_active_tenants()").query(UUID.class).list();
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
