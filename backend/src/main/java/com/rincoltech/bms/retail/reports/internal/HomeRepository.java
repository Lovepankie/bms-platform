package com.rincoltech.bms.retail.reports.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two reads of the shop Home summary (issue #485) that no report had: the shops to give a line each, and the open customer
 * orders as one count. Every money figure comes from the dashboard, credit control and sales analysis queries.
 */
@Repository
class HomeRepository {

    private final JdbcClient jdbc;

    HomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Shop(UUID id, String name) {}

    /** Money of one way of paying and one kind: sales, payments, fees, or out (given back). */
    record Taking(String method, String kind, long amount) {}

    /**
     * Today's takings by way of paying, read the way the cash book reads a day (ADR-022, {@code JdbcCashSales}) for every way and
     * not only cash, so the card agrees with the cash summary it links to. In: sales paid at the sale dated today (cancelled
     * later or not); payments on credit sales dated today, at the branch that took them; delivery fees taken at the door on
     * deliveries of today, and fees left on an account and paid today. Out: sales paid at the sale cancelled today, payments on
     * account reversed today, money refunded today on goods taken back (at the till that paid it), and the door fee of an order
     * whose sale was cancelled today; a return cancelled today brings its refund back in. Every part out is read for cash,
     * mobile money and bank only, as the fees are: what a return takes off a buyer's balance is not money. Payments are chosen by {@code paid_on}
     * alone, as the cash book chooses them, whenever they were entered, through the index {@code (tenant_id, paid_on)} (V100,
     * issue #488, bench query k17).
     */
    List<Taking> takings(List<UUID> branchIds, LocalDate today, ZoneId zone) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", Date.valueOf(today));
        params.put("zone", zone.getId());
        params.put("start", Timestamp.from(today.atStartOfDay(zone).toInstant()));
        params.put("end", Timestamp.from(today.plusDays(1).atStartOfDay(zone).toInstant()));
        if (branchIds != null) {
            params.put("branchIds", branchIds);
        }
        java.util.function.Function<String, String> in =
                column -> branchIds == null ? "" : " AND " + column + " IN (:branchIds)";
        String money = "('cash', 'mobile_money', 'bank')";
        return jdbc.sql("SELECT t.method, t.kind, sum(t.amount) AS amount FROM ("
                        + " SELECT s.payment_method AS method, 'sales' AS kind, s.total_minor AS amount FROM retail_sales s"
                        + " WHERE s.sale_date = :today AND s.payment_method <> 'credit'" + in.apply("s.branch_id")
                        + " UNION ALL SELECT s.payment_method, 'out', s.total_minor FROM retail_sales s"
                        + " WHERE s.payment_method <> 'credit' AND s.voided_at >= :start AND s.voided_at < :end"
                        + in.apply("s.branch_id")
                        + " UNION ALL SELECT p.method, 'payments', p.amount_minor FROM retail_sale_payments p"
                        + " JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id"
                        + " LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id"
                        + " LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id"
                        + " WHERE p.paid_on = :today"
                        + in.apply("coalesce(ap.branch_id, s.branch_id)")
                        + " UNION ALL SELECT ap.method, 'out', ap.amount_minor FROM retail_account_payments ap"
                        + " WHERE ap.method IN " + money + " AND ap.status = 'reversed' AND ap.reversed_on = :today"
                        + in.apply("ap.branch_id")
                        + " UNION ALL SELECT r.refund_method, 'out', r.refund_minor FROM retail_sale_returns r"
                        + " WHERE r.refund_method IN " + money + " AND r.refund_minor > 0 AND r.returned_on = :today"
                        + in.apply("r.refund_branch_id")
                        + " UNION ALL SELECT r.refund_method, 'out', -r.refund_minor FROM retail_sale_returns r"
                        + " WHERE r.refund_method IN " + money + " AND r.refund_minor > 0 AND r.status = 'voided'"
                        + " AND r.voided_on = :today"
                        + in.apply("r.refund_branch_id")
                        + " UNION ALL SELECT o.fee_method, 'fees', o.delivery_fee_minor FROM retail_customer_orders o"
                        + " WHERE o.status = 'delivered' AND o.fee_method IN " + money
                        + " AND o.fee_entry_id IS NOT NULL AND o.delivered_on = :today" + in.apply("o.branch_id")
                        + " UNION ALL SELECT o.fee_paid_method, 'fees', o.delivery_fee_minor FROM retail_customer_orders o"
                        + " WHERE o.fee_paid_method IN " + money + " AND o.fee_paid_on = :today"
                        + in.apply("o.branch_id")
                        + " UNION ALL SELECT o.fee_method, 'out', o.delivery_fee_minor FROM retail_customer_orders o"
                        + " JOIN retail_sales s ON s.tenant_id = o.tenant_id AND s.id = o.sale_id"
                        + " WHERE o.fee_method IN " + money + " AND o.fee_entry_id IS NOT NULL AND s.status = 'voided'"
                        + " AND s.voided_at >= :start AND s.voided_at < :end" + in.apply("o.branch_id")
                        + ") t GROUP BY t.method, t.kind")
                .params(params)
                .query((rs, n) -> new Taking(rs.getString("method"), rs.getString("kind"), rs.getLong("amount")))
                .list();
    }

    /** Active branches that sell (a Store does not, ADR-048), in the filter (every branch when null), by name. */
    List<Shop> shops(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND id IN (:branchIds)";
        }
        return jdbc.sql("SELECT id, name FROM branches WHERE status = 'active' AND sells_goods" + branch
                        + " ORDER BY name, id")
                .params(params)
                .query((rs, n) -> new Shop(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    /** Branches by id, whatever their state: a branch that sold today and is no longer an active shop still gets its line. */
    List<Shop> named(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, name FROM branches WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new Shop(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    /** Customer orders ordered, packed or out for delivery: the open steps of the orders board, added up. */
    int openOrders(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String branch = "";
        if (branchIds != null) {
            params.put("branchIds", branchIds);
            branch = " AND branch_id IN (:branchIds)";
        }
        return jdbc.sql("SELECT count(*) FROM retail_customer_orders"
                        + " WHERE status IN ('ordered', 'packed', 'out_for_delivery')" + branch)
                .params(params)
                .query(Integer.class)
                .single();
    }
}
