package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Barcode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the barcodes of products (V90, ADR-070). Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class BarcodeRepository {

    private static final String SELECT_BARCODE = """
            SELECT b.id, b.product_id, b.code, b.format, b.unit_id, u.name AS unit, b.generated,
                   p.code AS product_code, p.description AS product_description, p.active AS product_active
              FROM retail_product_barcodes b
              JOIN retail_products p ON p.id = b.product_id
              LEFT JOIN retail_units u ON u.id = b.unit_id
            """;

    private final JdbcClient jdbc;

    BarcodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A barcode with the product it is on, for the lookup and the uniqueness rule. */
    record Held(
            Barcode barcode, UUID productId, String productCode, String productDescription, boolean productActive) {}

    private static Held held(ResultSet rs, int n) throws SQLException {
        return new Held(
                barcode(rs, n),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_code"),
                rs.getString("product_description"),
                rs.getBoolean("product_active"));
    }

    private static Barcode barcode(ResultSet rs, int n) throws SQLException {
        return new Barcode(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("code"),
                rs.getString("format"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getBoolean("generated"));
    }

    /**
     * The row holding this lookup key. Not locked (the application role may not update the table): two writers of the
     * same code meet at the unique index instead, and the second is refused.
     */
    Optional<Held> byKey(String key) {
        return jdbc.sql(SELECT_BARCODE + " WHERE b.lookup_key = ?")
                .param(key)
                .query(BarcodeRepository::held)
                .optional();
    }

    List<Barcode> of(UUID productId) {
        return jdbc.sql(SELECT_BARCODE + " WHERE b.product_id = ? ORDER BY b.created_at, b.id")
                .param(productId)
                .query(BarcodeRepository::barcode)
                .list();
    }

    /** The barcodes of many products in one statement, keyed by product. */
    Map<UUID, List<Barcode>> ofProducts(Collection<UUID> productIds) {
        Map<UUID, List<Barcode>> byProduct = new HashMap<>();
        if (productIds.isEmpty()) {
            return byProduct;
        }
        jdbc.sql(SELECT_BARCODE + " WHERE b.product_id IN (:ids) ORDER BY b.product_id, b.created_at, b.id")
                .param("ids", productIds)
                .query(BarcodeRepository::held)
                .list()
                .forEach(h -> byProduct
                        .computeIfAbsent(h.productId(), x -> new ArrayList<>())
                        .add(h.barcode()));
        return byProduct;
    }

    /** Every lookup key with whether its product is active, for an import's dry run. */
    Map<String, Held> allKeys() {
        Map<String, Held> keys = new HashMap<>();
        jdbc.sql(SELECT_BARCODE.replace("SELECT b.id,", "SELECT b.lookup_key, b.id,"))
                .query((rs, n) -> Map.entry(rs.getString("lookup_key"), held(rs, n)))
                .list()
                .forEach(e -> keys.put(e.getKey(), e.getValue()));
        return keys;
    }

    void insert(UUID id, UUID productId, UUID unitId, String code, String format, boolean generated, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_product_barcodes (id, tenant_id, product_id, unit_id, code, format, generated, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                        """).params(id, productId, unitId, code, format, generated, by).update();
    }

    /**
     * A made code, or nothing when another writer holds its lookup key already (true when inserted). ON CONFLICT keeps
     * the transaction usable, where a caught unique violation would abort it in PostgreSQL.
     */
    boolean insertGenerated(UUID id, UUID productId, String code, String format, UUID by) {
        return jdbc.sql("""
                        INSERT INTO retail_product_barcodes (id, tenant_id, product_id, unit_id, code, format, generated, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, NULL, ?, ?, true, ?)
                        ON CONFLICT (tenant_id, lookup_key) DO NOTHING
                        """).params(id, productId, code, format, by).update() == 1;
    }

    int delete(UUID id) {
        return jdbc.sql("DELETE FROM retail_product_barcodes WHERE id = ?")
                .param(id)
                .update();
    }

    Optional<Barcode> generatedOf(UUID productId) {
        return jdbc.sql(SELECT_BARCODE + " WHERE b.product_id = ? AND b.generated ORDER BY b.created_at LIMIT 1")
                .param(productId)
                .query(BarcodeRepository::barcode)
                .optional();
    }
}
