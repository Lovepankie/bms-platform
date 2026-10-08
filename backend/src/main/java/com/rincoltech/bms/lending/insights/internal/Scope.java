package com.rincoltech.bms.lending.insights.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one insights request may see and asked for: the date range (inclusive), the business date,
 * the branches (null for every branch, empty for none), the responsible officer (forced to the
 * caller without {@code lending.insights.all_officers}) and the product. Every query adds
 * {@link #loanFilter} so the filter is written once.
 */
record Scope(
        LocalDate from,
        LocalDate to,
        LocalDate today,
        List<UUID> branchIds,
        UUID officerId,
        UUID productId,
        String currency) {

    Scope {
        branchIds = branchIds == null ? null : List.copyOf(branchIds);
    }

    /** True when the caller's branch scope leaves nothing to show. */
    boolean empty() {
        return branchIds != null && branchIds.isEmpty();
    }

    Scope withRange(LocalDate newFrom, LocalDate newTo) {
        return new Scope(newFrom, newTo, today, branchIds, officerId, productId, currency);
    }

    /**
     * The branch, officer and product predicates on a {@code lending_loans} alias, as
     * {@code " AND ..."}; the parameters go into {@code p}. Call {@link #empty()} first.
     */
    String loanFilter(String alias, Map<String, Object> p) {
        StringBuilder sql = new StringBuilder();
        if (branchIds != null) {
            sql.append(" AND ").append(alias).append(".branch_id IN (:branches)");
            p.put("branches", branchIds);
        }
        if (officerId != null) {
            sql.append(" AND ").append(alias).append(".officer_user_id = :officer");
            p.put("officer", officerId);
        }
        if (productId != null) {
            sql.append(" AND ")
                    .append(alias)
                    .append(
                            ".product_version_id IN (SELECT id FROM lending_loan_product_versions WHERE product_id = :product)");
            p.put("product", productId);
        }
        return sql.toString();
    }

    /**
     * The same predicates on a {@code lending_loan_daily_snapshots} alias, which carries the
     * branch, officer and product of each row, so a scan of many dates needs no join to the loans.
     */
    String snapshotFilter(String alias, Map<String, Object> p) {
        StringBuilder sql = new StringBuilder();
        if (branchIds != null) {
            sql.append(" AND ").append(alias).append(".branch_id IN (:branches)");
            p.put("branches", branchIds);
        }
        if (officerId != null) {
            sql.append(" AND ").append(alias).append(".officer_user_id = :officer");
            p.put("officer", officerId);
        }
        if (productId != null) {
            sql.append(" AND ").append(alias).append(".product_id = :product");
            p.put("product", productId);
        }
        return sql.toString();
    }
}
