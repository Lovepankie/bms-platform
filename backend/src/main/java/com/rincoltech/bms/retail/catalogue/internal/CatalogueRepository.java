package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Pack;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the retail catalogue. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class CatalogueRepository {

    private static final String SELECT_PRODUCT = """
            SELECT p.id, p.code, p.description, p.category_id, c.name AS category, p.unit_id, u.name AS unit,
                   p.sell_minor, p.cost_minor, p.currency, p.active, p.created_at, p.updated_at, p.version
              FROM retail_products p
              JOIN retail_categories c ON c.id = p.category_id
              JOIN retail_units u ON u.id = p.unit_id
            """;

    private final JdbcClient jdbc;

    CatalogueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Categories and units ------------------------------------------------------------

    List<Category> categories() {
        return jdbc.sql("""
                        SELECT c.id, c.name, c.active, c.version, (SELECT count(*) FROM retail_products p WHERE p.category_id = c.id) AS used
                          FROM retail_categories c ORDER BY lower(c.name), c.id
                        """)
                .query((rs, n) -> new Category(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("active"),
                        rs.getInt("used"),
                        rs.getInt("version")))
                .list();
    }

    List<Unit> units() {
        return jdbc.sql("""
                        SELECT u.id, u.name, u.active, u.version, (SELECT count(*) FROM retail_products p WHERE p.unit_id = u.id) AS used
                          FROM retail_units u ORDER BY lower(u.name), u.id
                        """)
                .query((rs, n) -> new Unit(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("active"),
                        rs.getInt("used"),
                        rs.getInt("version")))
                .list();
    }

    Optional<Category> category(UUID id) {
        return categories().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    Optional<Unit> unit(UUID id) {
        return units().stream().filter(u -> u.id().equals(id)).findFirst();
    }

    /** Rows changed: 0 when the version moved since it was read (the predicate is the optimistic lock). */
    int updateCategory(UUID id, String name, boolean active, int expectedVersion) {
        return jdbc.sql(
                        "UPDATE retail_categories SET name = ?, active = ?, updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(name, active, id, expectedVersion)
                .update();
    }

    int updateUnit(UUID id, String name, boolean active, int expectedVersion) {
        return jdbc.sql(
                        "UPDATE retail_units SET name = ?, active = ?, updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(name, active, id, expectedVersion)
                .update();
    }

    boolean categoryExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM retail_categories WHERE id = ?")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    boolean unitExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM retail_units WHERE id = ?")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    void insertCategory(UUID id, String name, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO retail_categories (id, tenant_id, name, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(id, name, createdBy).update();
    }

    void insertUnit(UUID id, String name, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO retail_units (id, tenant_id, name, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(id, name, createdBy).update();
    }

    /** Lower-cased name to id, for the import's lookups. */
    Map<String, UUID> categoryIdsByName() {
        return namesToIds("retail_categories");
    }

    Map<String, UUID> unitIdsByName() {
        return namesToIds("retail_units");
    }

    /** Lower-cased names of switched-off categories, which an import must not add items to. */
    Set<String> inactiveCategoryNames() {
        return inactiveNames("retail_categories");
    }

    Set<String> inactiveUnitNames() {
        return inactiveNames("retail_units");
    }

    private Set<String> inactiveNames(String table) {
        return new java.util.HashSet<>(jdbc.sql("SELECT lower(name) FROM " + table + " WHERE NOT active")
                .query(String.class)
                .list());
    }

    /** Decimal places of a currency (UGX 0, KES 2), from the currencies table. */
    int currencyExponent(String currency) {
        return jdbc.sql("SELECT exponent FROM currencies WHERE code = ?")
                .param(currency)
                .query(Integer.class)
                .single();
    }

    private Map<String, UUID> namesToIds(String table) {
        Map<String, UUID> ids = new java.util.HashMap<>();
        jdbc.sql("SELECT id, lower(name) AS n FROM " + table)
                .query((rs, n) -> {
                    ids.put(rs.getString("n"), rs.getObject("id", UUID.class));
                    return 0;
                })
                .list();
        return ids;
    }

    /** Every product code of the tenant, lower-cased. */
    java.util.Set<String> productCodes() {
        return new java.util.HashSet<>(jdbc.sql("SELECT lower(code) FROM retail_products")
                .query(String.class)
                .list());
    }

    // ---- Products ------------------------------------------------------------------------

    Optional<Product> find(UUID id) {
        return jdbc.sql(SELECT_PRODUCT + " WHERE p.id = ?")
                .param(id)
                .query(CatalogueRepository::product)
                .optional();
    }

    /**
     * The product row lock (FR-RET-06). {@code FOR UPDATE} would conflict with the {@code FOR KEY
     * SHARE} that every stock movement and sale line insert takes through its foreign key, and a
     * restock that locks the product before the balance would then deadlock with a sale, usage, void
     * or stock-take that locks the balance before inserting its movement (review F2). {@code FOR NO
     * KEY UPDATE} still serialises restocks and price edits, since the product's key never changes.
     */
    static final String LOCK = SELECT_PRODUCT + " WHERE p.id = ? FOR NO KEY UPDATE OF p";

    Optional<Product> lock(UUID id) {
        return jdbc.sql(LOCK).param(id).query(CatalogueRepository::product).optional();
    }

    /** One page ordered by code; {@code query} matches the code, the description or the category name. */
    List<Product> page(
            String query, UUID categoryId, Boolean active, UUID branchId, String afterCode, UUID afterId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql;
        if (branchId == null) {
            sql = new StringBuilder(SELECT_PRODUCT + " WHERE true");
        } else {
            // The balance of retail_stock_balances (the stock module's table), read for the search row.
            sql = new StringBuilder(
                    SELECT_PRODUCT.replace(" FROM retail_products p", ", b.qty FROM retail_products p")
                            + " LEFT JOIN retail_stock_balances b ON b.product_id = p.id AND b.branch_id = :branchId WHERE true");
            params.put("branchId", branchId);
        }
        if (query != null) {
            sql.append(" AND (p.code ILIKE :q OR p.description ILIKE :q OR c.name ILIKE :q)");
            params.put(
                    "q", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }
        if (active != null) {
            sql.append(" AND p.active = :active");
            params.put("active", active);
        }
        if (afterCode != null) {
            sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
            params.put("afterCode", afterCode);
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY p.code, p.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query(branchId == null ? CatalogueRepository::product : CatalogueRepository::productWithQty)
                .list();
    }

    void insert(Product p, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency, active, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :code, :description, :categoryId, :unitId,
                                :costMinor, :sellMinor, :currency, true, :createdBy)
                        """)
                .param("id", p.id())
                .param("code", p.code())
                .param("description", p.description())
                .param("categoryId", p.categoryId())
                .param("unitId", p.unitId())
                .param("costMinor", p.costMinor())
                .param("sellMinor", p.sellMinor())
                .param("currency", p.currency())
                .param("createdBy", createdBy)
                .update();
    }

    void update(Product p) {
        jdbc.sql("""
                        UPDATE retail_products SET code = :code, description = :description, category_id = :categoryId,
                               unit_id = :unitId, active = :active, updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("id", p.id())
                .param("code", p.code())
                .param("description", p.description())
                .param("categoryId", p.categoryId())
                .param("unitId", p.unitId())
                .param("active", p.active())
                .update();
    }

    /** The only statement that changes a price; its caller writes the history row alongside. */
    void setPrices(UUID id, long costMinor, long sellMinor) {
        jdbc.sql("""
                        UPDATE retail_products SET cost_minor = ?, sell_minor = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(costMinor, sellMinor, id).update();
    }

    // ---- Price history (append-only) -----------------------------------------------------

    // created_at is the statement's clock, not the transaction's: two changes in one restock keep
    // their order (FR-RET-06, latest line wins).

    void insertHistory(PriceChange h, UUID productId) {
        jdbc.sql("""
                        INSERT INTO retail_price_history (id, tenant_id, created_at, product_id, source, source_id,
                            old_cost_minor, new_cost_minor, old_sell_minor, new_sell_minor, currency, reason, changed_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, clock_timestamp(), :productId, :source,
                            :sourceId, :oldCost, :newCost, :oldSell, :newSell, :currency, :reason, :by)
                        """)
                .param("id", h.id())
                .param("productId", productId)
                .param("source", h.source())
                .param("sourceId", h.sourceId())
                .param("oldCost", h.oldCostMinor())
                .param("newCost", h.newCostMinor())
                .param("oldSell", h.oldSellMinor())
                .param("newSell", h.newSellMinor())
                .param("currency", h.currency())
                .param("reason", h.reason())
                .param("by", h.by())
                .update();
    }

    List<PriceChange> history(UUID productId) {
        return jdbc.sql("""
                        SELECT id, created_at, changed_by, source, source_id, old_cost_minor, new_cost_minor,
                               old_sell_minor, new_sell_minor, currency, reason
                          FROM retail_price_history WHERE product_id = ? ORDER BY created_at, id
                        """)
                .param(productId)
                .query((rs, n) -> new PriceChange(
                        rs.getObject("id", UUID.class),
                        instant(rs, "created_at"),
                        rs.getObject("changed_by", UUID.class),
                        rs.getString("source"),
                        rs.getObject("source_id", UUID.class),
                        rs.getObject("old_cost_minor", Long.class),
                        rs.getObject("new_cost_minor", Long.class),
                        rs.getObject("old_sell_minor", Long.class),
                        rs.getLong("new_sell_minor"),
                        rs.getString("currency"),
                        rs.getString("reason")))
                .list();
    }

    // ---- Pack sizes (ADR-033, issue #194) ------------------------------------------------------

    private static final String SELECT_PACK = """
            SELECT k.id, k.product_id, k.unit_id, u.name AS unit, k.factor, k.active, k.version
              FROM retail_product_units k JOIN retail_units u ON u.id = k.unit_id
            """;

    private static Pack pack(ResultSet rs, int n) throws SQLException {
        return new Pack(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getBigDecimal("factor").setScale(3).toPlainString(),
                rs.getBoolean("active"),
                rs.getInt("version"));
    }

    List<Pack> packs(UUID productId) {
        return jdbc.sql(SELECT_PACK + " WHERE k.product_id = ? ORDER BY lower(u.name), k.id")
                .param(productId)
                .query(CatalogueRepository::pack)
                .list();
    }

    /** The packs of many products in one statement, grouped by product; the key is absent for a product with none. */
    Map<UUID, List<Pack>> packsOf(java.util.Collection<UUID> productIds) {
        Map<UUID, List<Pack>> byProduct = new java.util.HashMap<>();
        if (productIds.isEmpty()) {
            return byProduct;
        }
        jdbc.sql(SELECT_PACK + " WHERE k.product_id IN (:ids) ORDER BY k.product_id, lower(u.name), k.id")
                .param("ids", productIds)
                .query(CatalogueRepository::pack)
                .list()
                .forEach(k -> byProduct
                        .computeIfAbsent(k.productId(), x -> new java.util.ArrayList<>())
                        .add(k));
        return byProduct;
    }

    Optional<Pack> pack(UUID productId, UUID unitId) {
        return jdbc.sql(SELECT_PACK + " WHERE k.product_id = ? AND k.unit_id = ?")
                .params(productId, unitId)
                .query(CatalogueRepository::pack)
                .optional();
    }

    void insertPack(UUID id, UUID productId, UUID unitId, java.math.BigDecimal factor, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_product_units (id, tenant_id, product_id, unit_id, factor, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, productId, unitId, factor, by).update();
    }

    /** Rows changed: 0 when the version moved since it was read. */
    int updatePack(UUID id, java.math.BigDecimal factor, boolean active, int expectedVersion) {
        return jdbc.sql(
                        "UPDATE retail_product_units SET factor = ?, active = ?, updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(factor, active, id, expectedVersion)
                .update();
    }

    /** Active packs only: a pack that was switched off no longer ties the product's unit or the pack's unit down. */
    boolean productHasPacks(UUID productId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_product_units WHERE product_id = ? AND active)")
                .param(productId)
                .query(Boolean.class)
                .single();
    }

    boolean unitHasPacks(UUID unitId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_product_units WHERE unit_id = ? AND active)")
                .param(unitId)
                .query(Boolean.class)
                .single();
    }

    static Product product(ResultSet rs, int n) throws SQLException {
        return new Product(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getLong("sell_minor"),
                rs.getLong("cost_minor"),
                rs.getString("currency"),
                rs.getBoolean("active"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getInt("version"),
                null,
                null);
    }

    static Product productWithQty(ResultSet rs, int n) throws SQLException {
        Product p = product(rs, n);
        BigDecimal qty = rs.getBigDecimal("qty");
        BigDecimal q = (qty == null ? BigDecimal.ZERO : qty).setScale(3);
        return new Product(
                p.id(),
                p.code(),
                p.description(),
                p.categoryId(),
                p.category(),
                p.unitId(),
                p.unit(),
                p.sellMinor(),
                p.costMinor(),
                p.currency(),
                p.active(),
                p.createdAt(),
                p.updatedAt(),
                p.version(),
                q.toPlainString(),
                q.signum() < 0,
                null);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
