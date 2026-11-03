package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.core.tenancy.Branches.Branch;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The item page (issue #813, ADR-121): one item's stock in each shop the caller may read, then every movement of it newest
 * first with the shop's balance after each. Quantities go to everyone who may read stock; the average buying price, a
 * movement's cost and a sale's profit only with {@code retail.profit.read} in that shop. The movements are the stock
 * movements ledger read ({@link StockService#movements}) with the running balance, one query per page.
 */
@Service
class ItemPageService {

    static final String STOCK_READ = "retail.stock.read";

    private final JdbcClient jdbc;
    private final StockService stock;
    private final RetailBranchContext branches;

    ItemPageService(JdbcClient jdbc, StockService stock, RetailBranchContext branches) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.branches = branches;
    }

    @Schema(name = "RetailItemShop", description = "The item in one shop or store")
    record ItemShop(
            UUID branchId,
            String code,
            String name,

            @Schema(description = "A closed branch, listed only while it still holds the item")
            boolean closed,

            String qty,
            boolean negative,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The shop's average buying price per piece (ADR-079); " + StockApi.COST_ONLY)
            Long avgCostMinor) {}

    @Schema(name = "RetailItemStock", description = "The top of the item page (issue #813)")
    record ItemStock(
            UUID productId,
            String code,
            String description,
            String unit,
            String category,
            boolean active,

            @Schema(description = "The sum over the shops listed")
            String totalQty,

            List<ItemShop> shops) {}

    private record Header(String code, String description, String unit, String category, boolean active) {}

    @Transactional(readOnly = true)
    ItemStock item(UUID productId) {
        Header h = header(productId).orElseThrow(ApiException::notFound);
        Principal principal = CurrentPrincipal.require();
        List<Branch> visible = branches.visible(STOCK_READ, new HashSet<>(branches.inactiveInScope(STOCK_READ)));
        Map<UUID, BigDecimal> qty = new HashMap<>();
        Map<UUID, Long> avg = new HashMap<>();
        if (!visible.isEmpty()) {
            jdbc.sql("""
                            SELECT branch_id, qty, avg_cost_minor FROM retail_stock_balances
                             WHERE product_id = :product AND branch_id IN (:branches)
                            """)
                    .param("product", productId)
                    .param("branches", visible.stream().map(Branch::id).toList())
                    .query((rs, n) -> {
                        UUID b = rs.getObject("branch_id", UUID.class);
                        qty.put(b, rs.getBigDecimal("qty"));
                        avg.put(b, rs.getObject("avg_cost_minor", Long.class));
                        return b;
                    })
                    .list();
        }
        BigDecimal total = BigDecimal.ZERO;
        List<ItemShop> shops = new ArrayList<>();
        for (Branch b : visible) {
            BigDecimal q = qty.getOrDefault(b.id(), BigDecimal.ZERO);
            if (!b.active() && q.signum() == 0) {
                continue;
            }
            total = total.add(q);
            shops.add(new ItemShop(
                    b.id(),
                    b.code(),
                    b.name(),
                    !b.active(),
                    Quantities.format(q),
                    q.signum() < 0,
                    principal.may(StockService.PROFIT_READ, b.id()) ? avg.get(b.id()) : null));
        }
        return new ItemStock(
                productId,
                h.code(),
                h.description(),
                h.unit(),
                h.category(),
                h.active(),
                Quantities.format(total),
                shops);
    }

    /**
     * The item's movements newest first with each one's balance after it. A branch asked for outside the caller's scope is
     * 404, as an item of another business is; the item itself is looked up only when a page comes back
     * empty (first page or a later one), so a page with rows is one query.
     */
    @Transactional(readOnly = true)
    MovementPage movements(
            UUID productId, List<UUID> branchIds, LocalDate from, LocalDate to, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        if (branchIds != null && branchIds.stream().anyMatch(b -> !principal.may(STOCK_READ, b))) {
            throw ApiException.notFound();
        }
        MovementPage page = stock.movements(branchIds, productId, from, to, null, true, limit, cursor, true);
        if (page.items().isEmpty() && header(productId).isEmpty()) {
            throw ApiException.notFound();
        }
        return page;
    }

    private Optional<Header> header(UUID productId) {
        return jdbc.sql("""
                        SELECT p.code, p.description, u.name AS unit, c.name AS category, p.active
                          FROM retail_products p
                          LEFT JOIN retail_units u ON u.id = p.unit_id
                          LEFT JOIN retail_categories c ON c.id = p.category_id
                         WHERE p.id = ?
                        """)
                .param(productId)
                .query((rs, n) -> new Header(
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        rs.getString("category"),
                        rs.getBoolean("active")))
                .optional();
    }
}
