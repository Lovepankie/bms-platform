package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.LowStockRule;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The low-stock minimum of an item (issue #412, ADR-057): "Warn me when stock is below", for every shop and store, with a
 * different level for a chosen branch if wanted. Read with {@code retail.stock.read} (the branch levels of the branches the
 * caller reads); the level for every branch is set by holders of {@code retail.catalogue.manage}, a branch's own level also
 * by whoever may commit a stock-take at that branch ({@code retail.stocktake.commit}, the storekeeper's permission), since
 * that is the person who counts the shelf. Changes are audited with before and after.
 */
@Service
class StockMinimumService {

    static final String CATALOGUE = "retail.catalogue.manage";
    static final String STOCKTAKE = "retail.stocktake.commit";
    static final String READ = "retail.stock.read";

    private final JdbcClient jdbc;
    private final RetailCatalogue catalogue;
    private final RetailBranchContext branches;
    private final AuditLog audit;

    StockMinimumService(JdbcClient jdbc, RetailCatalogue catalogue, RetailBranchContext branches, AuditLog audit) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.branches = branches;
        this.audit = audit;
    }

    @Schema(name = "RetailStockMinimumBranch")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BranchLevel(
            UUID branchId,
            String branchName,

            @Schema(description = "True for a store (keeps goods, does not sell)")
            boolean store,

            @Schema(
                    description = "This branch's own level, absent when it follows the level for every branch",
                    type = "string")
            String minQty,

            @Schema(
                    description =
                            "The level that applies here: the branch's own, else the one for every branch; absent when none")
            String appliesQty,

            @Schema(description = "True when the caller may set this branch's own level")
            boolean canSet) {}

    @Schema(name = "RetailStockMinimum", description = "Warn me when stock is below (issue #412)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record View(
            UUID productId,

            @Schema(description = "The level for every shop and store, in the item's unit; absent when none is set")
            String minQty,

            @Schema(description = "True when the caller may set the level for every branch (retail.catalogue.manage)")
            boolean canSetEveryBranch,

            @Schema(
                    description =
                            "With no level, or as well as it, an item is low when it runs out within this many days at the pace of the last 30 days")
            int leadDays,

            List<BranchLevel> branches) {}

    @Schema(name = "SetRetailStockMinimumRequest")
    record SetRequest(
            @Positive
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "10",
                    description = "Warn when stock is below this; null removes the level")
            BigDecimal minQty) {}

    @Transactional(readOnly = true)
    View get(UUID productId) {
        requireProduct(productId);
        return view(productId);
    }

    @Transactional
    View setEveryBranch(UUID productId, SetRequest r) {
        requireProduct(productId);
        // The level for every branch reaches branches a branch-scoped catalogue manager does not cover: refused for
        // them.
        boolean everywhere = CurrentPrincipal.require()
                .scopeOf(CATALOGUE)
                .map(Principal.BranchScope::all)
                .orElse(false);
        if (!everywhere) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only someone who manages the catalogue in every branch can set the level for every shop and store.");
        }
        write(productId, null, r.minQty());
        return view(productId);
    }

    @Transactional
    View setBranch(UUID productId, UUID branchId, SetRequest r) {
        requireProduct(productId);
        Principal principal = CurrentPrincipal.require();
        boolean visible = branches.visible(READ).stream().anyMatch(b -> b.id().equals(branchId));
        if (!visible) {
            throw ApiException.validation(
                    List.of(new FieldProblem("branch_id", "unknown_branch", "No such active branch in your scope.")));
        }
        if (!mayAt(principal, branchId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only an administrator, or someone who counts the stock at this branch, can set its low-stock level.");
        }
        write(productId, branchId, r.minQty());
        return view(productId);
    }

    private static boolean mayAt(Principal principal, UUID branchId) {
        return principal.may(CATALOGUE, branchId) || principal.may(STOCKTAKE, branchId);
    }

    private void requireProduct(UUID productId) {
        if (catalogue.find(productId).isEmpty()) {
            throw ApiException.notFound();
        }
    }

    private void write(UUID productId, UUID branchId, BigDecimal minQty) {
        if (minQty != null && (!Quantities.exact(minQty) || minQty.compareTo(Quantities.MAX_QTY) > 0)) {
            throw ApiException.validation(List.of(
                    new FieldProblem("min_qty", "invalid", "Use a quantity above 0 with at most three places.")));
        }
        BigDecimal before = level(productId, branchId);
        if (minQty == null ? before == null : before != null && before.compareTo(minQty) == 0) {
            // Nothing changes: no write and no audit row (removing a level that never existed, or saving the same one).
            return;
        }
        UUID by = CurrentPrincipal.require().userId();
        if (minQty == null) {
            jdbc.sql("DELETE FROM retail_stock_minimums WHERE product_id = ? AND branch_id IS NOT DISTINCT FROM ?")
                    .params(productId, branchId)
                    .update();
        } else {
            jdbc.sql("""
                            INSERT INTO retail_stock_minimums (id, tenant_id, product_id, branch_id, min_qty, updated_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, product_id, branch_id)
                            DO UPDATE SET min_qty = EXCLUDED.min_qty, updated_at = now(), updated_by = EXCLUDED.updated_by
                            """)
                    .params(UUID.randomUUID(), productId, branchId, minQty, by)
                    .update();
        }
        // No null values in an audit map: an absent min_qty means no level.
        Map<String, Object> was = new LinkedHashMap<>();
        if (before != null) {
            was.put("min_qty", Quantities.format(before));
        }
        Map<String, Object> now = new LinkedHashMap<>();
        if (minQty != null) {
            now.put("min_qty", Quantities.format(minQty));
        }
        now.put("scope", branchId == null ? "every_branch" : "branch");
        audit.record(
                new AuditLog.Entry("retail.stock_minimum.changed", "retail.product", productId, branchId, was, now));
    }

    private BigDecimal level(UUID productId, UUID branchId) {
        return jdbc.sql("""
                        SELECT min_qty FROM retail_stock_minimums
                         WHERE product_id = ? AND branch_id IS NOT DISTINCT FROM ?
                        """)
                .params(productId, branchId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private View view(UUID productId) {
        Principal principal = CurrentPrincipal.require();
        Map<UUID, BigDecimal> own = new HashMap<>();
        BigDecimal[] every = {null};
        jdbc.sql("SELECT branch_id, min_qty FROM retail_stock_minimums WHERE product_id = ?")
                .param(productId)
                .query((rs, n) -> {
                    UUID b = rs.getObject("branch_id", UUID.class);
                    if (b == null) {
                        every[0] = rs.getBigDecimal("min_qty");
                    } else {
                        own.put(b, rs.getBigDecimal("min_qty"));
                    }
                    return null;
                })
                .list();
        List<BranchLevel> levels = branches.visible(READ).stream()
                .filter(Branches.Branch::stocksGoods)
                .map(b -> {
                    BigDecimal mine = own.get(b.id());
                    BigDecimal applies = mine != null ? mine : every[0];
                    return new BranchLevel(
                            b.id(),
                            b.name(),
                            !b.sellsGoods(),
                            mine == null ? null : Quantities.format(mine),
                            applies == null ? null : Quantities.format(applies),
                            mayAt(principal, b.id()));
                })
                .toList();
        return new View(
                productId,
                every[0] == null ? null : Quantities.format(every[0]),
                principal.scopeOf(CATALOGUE).map(Principal.BranchScope::all).orElse(false),
                LowStockRule.DEFAULT_LEAD_DAYS,
                levels);
    }
}
