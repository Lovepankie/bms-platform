package com.rincoltech.bms.lending.products.internal;

import com.rincoltech.bms.lending.products.internal.ProductApi.Fee;
import com.rincoltech.bms.lending.products.internal.ProductApi.Product;
import com.rincoltech.bms.lending.products.internal.ProductApi.Version;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for loan products, versions and fees. Row-level security supplies the tenant predicate
 * (ADR-003); versions and fees are only ever inserted (FR-PRD-04).
 */
@Repository
class ProductRepository {

    private static final String PRODUCT = """
            SELECT id, code, name, status, current_version_id, created_at, updated_at, version
              FROM lending_loan_products
            """;

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A product row before its versions are attached. */
    record Row(
            UUID id,
            String code,
            String name,
            String status,
            UUID currentVersionId,
            Instant createdAt,
            Instant updatedAt,
            int version) {

        Product with(Version current, List<Version> versions) {
            return new Product(id, code, name, status, current, versions, createdAt, updatedAt, version);
        }
    }

    List<Row> list() {
        return jdbc.sql(PRODUCT + " ORDER BY code")
                .query(ProductRepository::row)
                .list();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(PRODUCT + " WHERE id = ?")
                .param(id)
                .query(ProductRepository::row)
                .optional();
    }

    Optional<Row> lock(UUID id) {
        return jdbc.sql(PRODUCT + " WHERE id = ? FOR UPDATE")
                .param(id)
                .query(ProductRepository::row)
                .optional();
    }

    boolean codeExists(String code) {
        return jdbc.sql("SELECT count(*) FROM lending_loan_products WHERE code = ?")
                        .param(code)
                        .query(Long.class)
                        .single()
                > 0;
    }

    void insertProduct(UUID id, String code, String name, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO lending_loan_products (id, tenant_id, code, name, status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'active', ?)
                        """).params(id, code, name, createdBy).update();
    }

    /** At creation: version 1 becomes current without counting as an edit of the product. */
    void linkFirstVersion(UUID productId, UUID versionId) {
        jdbc.sql("UPDATE lending_loan_products SET current_version_id = ? WHERE id = ?")
                .params(versionId, productId)
                .update();
    }

    void setCurrentVersion(UUID productId, UUID versionId) {
        jdbc.sql("""
                        UPDATE lending_loan_products SET current_version_id = ?, updated_at = now(), version = version + 1
                        WHERE id = ?
                        """).params(versionId, productId).update();
    }

    void archive(UUID productId) {
        jdbc.sql(
                        "UPDATE lending_loan_products SET status = 'archived', updated_at = now(), version = version + 1 WHERE id = ?")
                .param(productId)
                .update();
    }

    int nextVersionNo(UUID productId) {
        return jdbc.sql(
                        "SELECT coalesce(max(version_no), 0) + 1 FROM lending_loan_product_versions WHERE product_id = ?")
                .param(productId)
                .query(Integer.class)
                .single();
    }

    void insertVersion(UUID productId, Version v, UUID createdBy) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", v.id());
        p.put("productId", productId);
        p.put("versionNo", v.versionNo());
        p.put("currency", v.currency());
        p.put("interestMethod", v.interestMethod());
        p.put("interestRateBp", v.interestRateBp());
        p.put("rateUnit", v.rateUnit());
        p.put("termUnit", v.termUnit());
        p.put("minTermCount", v.minTermCount());
        p.put("maxTermCount", v.maxTermCount());
        p.put("defaultTermCount", v.defaultTermCount());
        p.put("repaymentPattern", v.repaymentPattern());
        p.put("instalmentFrequency", v.instalmentFrequency());
        p.put("minPrincipalMinor", v.minPrincipalMinor());
        p.put("maxPrincipalMinor", v.maxPrincipalMinor());
        p.put("allocationOrder", "{" + String.join(",", v.allocationOrder()) + "}");
        p.put("penaltyMethod", v.penaltyMethod());
        p.put("penaltyGraceDays", v.penaltyGraceDays());
        p.put("penaltyPeriodUnit", v.penaltyPeriodUnit());
        p.put("penaltyFlatMinor", v.penaltyFlatMinor());
        p.put("penaltyRateBp", v.penaltyRateBp());
        p.put("penaltyCapBp", v.penaltyCapBp());
        p.put("flatEarlySettlementRebate", v.flatEarlySettlementRebate());
        p.put("requiresCollateral", v.requiresCollateral());
        p.put("minCollateralCoverBp", v.minCollateralCoverBp());
        p.put("requiresGuarantor", v.requiresGuarantor());
        p.put("createdBy", createdBy);
        jdbc.sql("""
                        INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                            interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                            default_term_count, repayment_pattern, instalment_frequency, min_principal_minor,
                            max_principal_minor, allocation_order, penalty_method, penalty_grace_days,
                            penalty_period_unit, penalty_flat_minor, penalty_rate_bp, penalty_cap_bp,
                            flat_early_settlement_rebate, requires_collateral, min_collateral_cover_bp,
                            requires_guarantor, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :productId, :versionNo, :currency,
                            :interestMethod, :interestRateBp, :rateUnit, :termUnit, :minTermCount, :maxTermCount,
                            :defaultTermCount, :repaymentPattern, :instalmentFrequency, :minPrincipalMinor,
                            :maxPrincipalMinor, CAST(:allocationOrder AS text[]), :penaltyMethod, :penaltyGraceDays,
                            :penaltyPeriodUnit, :penaltyFlatMinor, :penaltyRateBp, :penaltyCapBp,
                            :flatEarlySettlementRebate, :requiresCollateral, :minCollateralCoverBp,
                            :requiresGuarantor, :createdBy)
                        """).params(p).update();
        for (Fee f : v.fees()) {
            jdbc.sql("""
                            INSERT INTO lending_loan_product_fees (id, tenant_id, product_version_id, name, fee_type,
                                calc_method, amount_minor, rate_bp, timing)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            v.id(),
                            f.name(),
                            f.feeType(),
                            f.calcMethod(),
                            f.amountMinor(),
                            f.rateBp(),
                            f.timing())
                    .update();
        }
    }

    /** Every version of a product with its fees, newest first. */
    List<Version> versions(UUID productId) {
        Map<UUID, List<Fee>> fees = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT f.product_version_id, f.name, f.fee_type, f.calc_method, f.amount_minor, f.rate_bp, f.timing
                          FROM lending_loan_product_fees f
                          JOIN lending_loan_product_versions v ON v.id = f.product_version_id
                         WHERE v.product_id = ? ORDER BY f.created_at, f.id
                        """)
                .param(productId)
                .query((rs, n) -> {
                    fees.computeIfAbsent(
                                    rs.getObject("product_version_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(new Fee(
                                    rs.getString("name"),
                                    rs.getString("fee_type"),
                                    rs.getString("calc_method"),
                                    rs.getObject("amount_minor", Long.class),
                                    rs.getObject("rate_bp", Integer.class),
                                    rs.getString("timing")));
                    return null;
                })
                .list();
        return jdbc.sql("SELECT * FROM lending_loan_product_versions WHERE product_id = ? ORDER BY version_no DESC")
                .param(productId)
                .query((rs, n) -> version(rs, fees.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    private static Version version(ResultSet rs, List<Fee> fees) throws SQLException {
        return new Version(
                rs.getObject("id", UUID.class),
                rs.getInt("version_no"),
                rs.getString("currency"),
                rs.getString("interest_method"),
                rs.getInt("interest_rate_bp"),
                rs.getString("rate_unit"),
                rs.getString("term_unit"),
                rs.getInt("min_term_count"),
                rs.getInt("max_term_count"),
                rs.getInt("default_term_count"),
                rs.getString("repayment_pattern"),
                rs.getString("instalment_frequency"),
                rs.getLong("min_principal_minor"),
                rs.getLong("max_principal_minor"),
                List.of((String[]) rs.getArray("allocation_order").getArray()),
                rs.getString("penalty_method"),
                rs.getInt("penalty_grace_days"),
                rs.getString("penalty_period_unit"),
                rs.getObject("penalty_flat_minor", Long.class),
                rs.getObject("penalty_rate_bp", Integer.class),
                rs.getObject("penalty_cap_bp", Integer.class),
                rs.getBoolean("flat_early_settlement_rebate"),
                rs.getBoolean("requires_collateral"),
                rs.getObject("min_collateral_cover_bp", Integer.class),
                rs.getBoolean("requires_guarantor"),
                List.copyOf(fees),
                instant(rs.getTimestamp("created_at")));
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getObject("current_version_id", UUID.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
