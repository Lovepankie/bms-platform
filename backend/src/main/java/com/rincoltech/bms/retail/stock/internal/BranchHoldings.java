package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.tenancy.BranchClosingCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What retail still holds at a branch, for the tenancy module's close and not-stocking checks (issue #329, ADR-041): the stock
 * with a non-zero balance, valued at each product's current cost as the stock valuation does, and the transfers dispatched
 * from or to it and not yet received, and the stock-takes started and not committed. Read only: nothing is moved, posted or written off.
 */
@Component
class BranchHoldings implements BranchClosingCheck {

    private final JdbcClient jdbc;

    BranchHoldings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Finding> findings(UUID branchId) {
        List<Finding> found = new ArrayList<>();
        Finding stock = jdbc.sql("""
                        SELECT count(*) AS items, coalesce(round(sum(b.qty * p.cost_minor)), 0)::bigint AS value
                          FROM retail_stock_balances b JOIN retail_products p ON p.id = b.product_id
                         WHERE b.qty <> 0 AND b.branch_id = ?
                        """)
                .param(branchId)
                .query((rs, n) -> new Finding("stock", rs.getInt("items"), rs.getLong("value")))
                .single();
        if (stock.count() > 0) {
            found.add(stock);
        }
        Finding road = jdbc.sql("""
                        SELECT count(*) AS n, coalesce(sum(cost_total_minor), 0)::bigint AS value
                          FROM retail_transfers
                         WHERE status = 'in_transit' AND (from_branch_id = ? OR to_branch_id = ?)
                        """)
                .params(branchId, branchId)
                .query((rs, n) -> new Finding("transfers_in_flight", rs.getInt("n"), rs.getLong("value")))
                .single();
        if (road.count() > 0) {
            found.add(road);
        }
        // A count started and not committed: committing it later would write stock into the branch.
        int drafts = jdbc.sql("SELECT count(*) FROM retail_stocktakes WHERE status = 'draft' AND branch_id = ?")
                .param(branchId)
                .query(Integer.class)
                .single();
        if (drafts > 0) {
            found.add(new Finding("open_stocktakes", drafts, 0));
        }
        return found;
    }
}
