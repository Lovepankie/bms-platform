package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.stock.internal.StockRepository.Mismatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-RET-03: compares every balance with the sum of its movements for the bound tenant. A
 * difference means something wrote a balance outside {@code StockLedger}; it is reported (a system
 * audit row naming each branch and product, and a warning in the log with the count only), never
 * corrected silently.
 */
@Service
class StockReconciliation {

    static final int REPORTED_ROWS = 50;
    private static final Logger LOG = LoggerFactory.getLogger(StockReconciliation.class);

    private final StockRepository repo;
    private final CurrentTenant tenant;
    private final AuditLog audit;

    StockReconciliation(StockRepository repo, CurrentTenant tenant, AuditLog audit) {
        this.repo = repo;
        this.tenant = tenant;
        this.audit = audit;
    }

    @Transactional
    List<Mismatch> run() {
        List<Mismatch> mismatches = repo.mismatches();
        if (mismatches.isEmpty()) {
            return mismatches;
        }
        LOG.warn("retail stock reconciliation: {} balance(s) differ from their movements", mismatches.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mismatches", mismatches.size());
        data.put(
                "rows",
                mismatches.stream()
                        .limit(REPORTED_ROWS)
                        .map(m -> Map.of(
                                "branch_id", m.branchId().toString(),
                                "product_id", m.productId().toString(),
                                "balance", m.balance().toPlainString(),
                                "movements", m.movements().toPlainString()))
                        .toList());
        audit.record(
                AuditLog.Entry.created(
                        "retail.stock.reconciliation_mismatch",
                        "retail.stock",
                        tenant.profile().id(),
                        null,
                        data),
                null,
                "system");
        return mismatches;
    }
}
