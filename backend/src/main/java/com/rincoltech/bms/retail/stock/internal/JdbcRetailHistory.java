package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailHistory;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.HistoricalMovement;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcRetailHistory implements RetailHistory {

    private final JdbcClient jdbc;
    private final StockLedger stock;
    private final CurrentTenant tenant;

    JdbcRetailHistory(JdbcClient jdbc, StockLedger stock, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID importUsage(
            UUID branchId,
            UUID productId,
            String kind,
            String reason,
            BigDecimal qty,
            long unitCostMinor,
            Instant reportedAt,
            LocalDate reportedOn) {
        UUID id = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        long cost = Quantities.value(qty, unitCostMinor);
        jdbc.sql("""
                        INSERT INTO retail_usage_reports (id, tenant_id, created_at, branch_id, kind, reason, occurred_on,
                            currency, cost_total_minor, historical, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, true, ?)
                        """)
                .params(
                        id,
                        Timestamp.from(reportedAt),
                        branchId,
                        kind,
                        reason,
                        Date.valueOf(reportedOn),
                        tenant.profile().currency(),
                        cost,
                        IMPORT_ACTOR)
                .update();
        jdbc.sql("""
                        INSERT INTO retail_usage_lines (id, tenant_id, report_id, line_no, product_id, qty, unit_cost_minor,
                            line_cost_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 1, ?, ?, ?, ?)
                        """).params(lineId, id, productId, qty, unitCostMinor, cost).update();
        stock.recordHistorical(
                reportedOn,
                List.of(new HistoricalMovement(
                        branchId,
                        productId,
                        kind.equals("used") ? "usage" : "damage",
                        qty.negate(),
                        unitCostMinor,
                        reportedAt,
                        UsageService.USAGE,
                        id,
                        lineId,
                        reason)));
        return id;
    }
}
