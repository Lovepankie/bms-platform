package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.notifications.PushPreferences;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.retail.stock.LowStockRule;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The low-stock push (issue #412, ADR-057). After stock leaves a branch (a sale, usage, damage, a transfer out, goods sent
 * back, a correction down), each item it touched is judged by {@link LowStockRule}: below its minimum at that branch, or
 * running out within the lead days at the pace of the last 30 days. A low item is told to the staff of that branch who read
 * stock and to the administrators, at most once per item, branch and day (the outbox key carries the day).
 *
 * <p>It runs in the transaction of the movement, inside a savepoint: a failure here is logged by class and the sale goes on.
 * Nothing is read while push is off for the server or the business has switched the low-stock notice off.
 */
@Component
class LowStockAlerts {

    static final String TEMPLATE = "retail.low_stock";

    private static final Logger log = LoggerFactory.getLogger(LowStockAlerts.class);

    private final JdbcClient jdbc;
    private final PushPreferences preferences;
    private final ApplicationEventPublisher events;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final TransactionTemplate savepoint;

    LowStockAlerts(
            JdbcClient jdbc,
            PushPreferences preferences,
            ApplicationEventPublisher events,
            CurrentTenant tenant,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.preferences = preferences;
        this.events = events;
        this.tenant = tenant;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** A branch and item that stock just left. */
    record Touched(UUID branchId, UUID productId) {}

    void afterOut(List<Touched> touched) {
        if (touched.isEmpty()) {
            return;
        }
        try {
            savepoint.executeWithoutResult(tx -> {
                if (!preferences.available() || !preferences.on(PushPreferences.LOW_STOCK)) {
                    return;
                }
                LocalDate today = clock.today(tenant.profile().timezone());
                touched.stream().distinct().forEach(t -> judge(t, today));
            });
        } catch (RuntimeException e) {
            // The movement is a business event; its notice is not worth it. No name, no value.
            log.warn("low stock check failed and was skipped: {}", e.getClass().getSimpleName());
        }
    }

    private record Position(String description, boolean active, BigDecimal qty, BigDecimal minQty) {}

    private void judge(Touched t, LocalDate today) {
        Optional<Position> found = jdbc.sql("""
                        SELECT p.description, p.active, b.qty,
                               coalesce((SELECT m.min_qty FROM retail_stock_minimums m
                                          WHERE m.product_id = p.id AND m.branch_id = b.branch_id),
                                        (SELECT m.min_qty FROM retail_stock_minimums m
                                          WHERE m.product_id = p.id AND m.branch_id IS NULL)) AS min_qty
                          FROM retail_stock_balances b
                          JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
                         WHERE b.branch_id = ? AND b.product_id = ?
                        """)
                .params(t.branchId(), t.productId())
                .query((rs, n) -> new Position(
                        rs.getString("description"),
                        rs.getBoolean("active"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("min_qty")))
                .optional();
        if (found.isEmpty() || !found.get().active()) {
            return;
        }
        Position p = found.get();
        BigDecimal sold = soldInPace(t, today);
        LowStockRule.judge(p.qty(), p.minQty(), sold, LowStockRule.DEFAULT_LEAD_DAYS)
                .ifPresent(verdict -> events.publishEvent(new PushNotice(
                        TEMPLATE,
                        t.branchId() + ":" + t.productId() + ":" + today,
                        new PushNotice.Holders("retail.stock.read", t.branchId(), null),
                        Map.of(
                                "branch_id", t.branchId().toString(),
                                "item_name", p.description(),
                                "reason", verdict.words(),
                                "path", "/staff/retail/stock-health"))));
    }

    /**
     * Units sold at the branch over the pace window, read as the Stock health report reads them (StockHealthRepository): the
     * lines of completed sales, so a voided sale does not count. The sale being recorded is already completed here: its lines
     * are written before its stock moves.
     */
    private BigDecimal soldInPace(Touched t, LocalDate today) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.qty), 0)
                          FROM retail_sales s
                          JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
                         WHERE s.branch_id = ? AND l.product_id = ? AND s.status = 'completed'
                           AND s.sale_date BETWEEN ? AND ?
                        """)
                .params(
                        t.branchId(),
                        t.productId(),
                        Date.valueOf(today.minusDays(LowStockRule.PACE_DAYS - 1L)),
                        Date.valueOf(today))
                .query(BigDecimal.class)
                .single();
    }
}
