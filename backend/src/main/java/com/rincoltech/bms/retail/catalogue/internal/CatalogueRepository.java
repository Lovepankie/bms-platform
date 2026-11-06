package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.StandardUnits;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Pack;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UsualPack;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UsualPackRequest;
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
                   p.sell_minor, p.cost_minor, p.currency, p.active, p.created_at, p.updated_at, p.version,
                   p.family_id, f.name AS family_name, p.variant_size, p.variant_colour, p.variant_sort,
                   p.photo_document_id AS photo_id, f.photo_document_id AS item_photo_id
              FROM retail_products p
              JOIN retail_categories c ON c.id = p.category_id
              JOIN retail_units u ON u.id = p.unit_id
              LEFT JOIN retail_product_families f ON f.id = p.family_id
            """;

    private final JdbcClient jdbc;

    CatalogueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Categories and units ------------------------------------------------------------

    List<Category> categories() {
        return jdbc.sql("""
                        SELECT c.id, c.name, c.active, c.version, c.code_prefix,
                               (SELECT count(*) FROM retail_products p WHERE p.category_id = c.id) AS used,
                               (SELECT c.code_prefix || '-' || CASE WHEN n < 10000 THEN lpad(n::text, 4, '0') ELSE n::text END
                                  FROM generate_series(c.code_next, c.code_next + 10000) n
                                 WHERE NOT EXISTS (SELECT 1 FROM retail_products p
                                                    WHERE p.tenant_id = c.tenant_id
                                                      AND lower(p.code) = lower(c.code_prefix || '-'
                                                          || CASE WHEN n < 10000 THEN lpad(n::text, 4, '0') ELSE n::text END))
                                 ORDER BY n LIMIT 1) AS next_code
                          FROM retail_categories c ORDER BY lower(c.name), c.id
                        """)
                .query((rs, n) -> new Category(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("active"),
                        rs.getString("code_prefix"),
                        rs.getString("next_code"),
                        rs.getInt("used"),
                        rs.getInt("version")))
                .list();
    }

    List<Unit> units() {
        return jdbc
                .sql("""
                        SELECT u.id, u.name, u.active, u.version, (SELECT count(*) FROM retail_products p WHERE p.unit_id = u.id) AS used,
                               u.pack_size, u.pack_of_unit_id, o.name AS pack_of, u.pack_purpose
                          FROM retail_units u
                          LEFT JOIN retail_units o ON o.id = u.pack_of_unit_id
                         ORDER BY lower(u.name), u.id
                        """)
                .query((rs, n) -> new Unit(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("active"),
                        rs.getInt("used"),
                        rs.getInt("version"),
                        rs.getBigDecimal("pack_size") == null
                                ? null
                                : new UsualPack(
                                        rs.getBigDecimal("pack_size")
                                                .setScale(3)
                                                .toPlainString(),
                                        rs.getObject("pack_of_unit_id", UUID.class),
                                        rs.getString("pack_of"),
                                        rs.getString("pack_purpose")),
                        RetailCatalogue.isPackageWord(rs.getString("name")),
                        StandardUnits.rank(rs.getString("name")) >= 0,
                        StandardUnits.fixedCount(rs.getString("name"))))
                .list()
                .stream()
                // Issue #709 (ADR-110): the standard units first, in their order, then the shop's own by name.
                .sorted(java.util.Comparator.comparingInt((Unit u) -> {
                    int rank = StandardUnits.rank(u.name());
                    return rank < 0 ? Integer.MAX_VALUE : rank;
                }))
                .toList();
    }

    Optional<Category> category(UUID id) {
        return categories().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    Optional<Unit> unit(UUID id) {
        return units().stream().filter(u -> u.id().equals(id)).findFirst();
    }

    /** Rows changed: 0 when the version moved since it was read (the predicate is the optimistic lock). */
    int updateCategory(UUID id, String name, boolean active, String codePrefix, int expectedVersion) {
        return jdbc.sql(
                        "UPDATE retail_categories SET name = ?, active = ?, code_prefix = ?, updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(name, active, codePrefix, id, expectedVersion)
                .update();
    }

    /** Issue #493: the prefix a new category of this name would get, free in the business (V96). */
    String suggestPrefix(String name, UUID self) {
        return jdbc.sql("SELECT bms_suggest_category_prefix(current_setting('app.tenant_id')::uuid, ?, ?)")
                .params(name, self)
                .query(String.class)
                .single();
    }

    /**
     * Issue #493: the category row, locked to the commit, before the import makes codes in it; the lock of {@link
     * #nextItemCode}, which leaves the foreign key checks of item inserts free.
     */
    void lockCategory(UUID categoryId) {
        jdbc.sql("SELECT id FROM retail_categories WHERE id = ? FOR NO KEY UPDATE")
                .param(categoryId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Issue #493, ADR-081: the category's next item code, such as BLB-0001, under the category row's lock to the commit, so a
     * second add in the same category waits and takes the next number (V96).
     */
    String nextItemCode(UUID categoryId) {
        return jdbc.sql("SELECT bms_next_item_code(?)")
                .param(categoryId)
                .query(String.class)
                .single();
    }

    /**
     * Review of #710 (ADR-107): one change of a usual pack at a time in the business, held to the end of the transaction, so
     * two changes cannot each pass the loop check and store a loop between them.
     */
    void lockUsualPacks() {
        jdbc.sql(
                        "SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id') || ':retail.usual_pack', 0))")
                .query(rs -> {});
    }

    /**
     * Issue #625, ADR-100: the usual size of a pack and what it is for; the V111 check keeps all three or none. A null
     * {@code pack} clears it (issue #637).
     */
    void setUsualPack(UUID id, UsualPackRequest pack) {
        jdbc.sql("UPDATE retail_units SET pack_size = ?, pack_of_unit_id = ?, pack_purpose = ? WHERE id = ?")
                .params(
                        pack == null ? null : pack.size(),
                        pack == null ? null : pack.ofUnitId(),
                        pack == null ? null : pack.purpose(),
                        id)
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

    /** A null prefix is filled from the name by the V96 trigger. */
    void insertCategory(UUID id, String name, String codePrefix, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO retail_categories (id, tenant_id, name, code_prefix, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """)
                .params(id, CatalogueNames.display(name), codePrefix, createdBy)
                .update();
    }

    void insertUnit(UUID id, String name, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO retail_units (id, tenant_id, name, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                        """).params(id, name, createdBy).update();
    }

    /**
     * Issue #632: adds the category or unit unless the business has one of that name in any case (the unique name index), so
     * two quick adds at once make it once. True when this call made it.
     */
    boolean insertIfMissing(String table, UUID id, String name, UUID createdBy) {
        return jdbc.sql("INSERT INTO " + table + " (id, tenant_id, name, created_by)"
                                + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?) ON CONFLICT DO NOTHING")
                        .params(id, name, createdBy)
                        .update()
                == 1;
    }

    /** Name key ({@code CatalogueNames.nameKey}) to id, for the import's lookups. */
    Map<String, UUID> categoryIdsByName() {
        return namesToIds("retail_categories");
    }

    // JUSTIFICATION-A3: the unit lookups by key replace lower(name) in one place, so every caller finds Piece.
    /**
     * Issue #698 (ADR-109): the units by their key ({@code CatalogueNames.unitKey}), so "pcs" finds Piece; where two units
     * share a key (stored before issue #698, left for the owner to join), the one {@link #sameUnit} would answer.
     */
    Map<String, UUID> unitIdsByName() {
        Map<String, UUID> ids = new java.util.HashMap<>();
        unitsByPreference().forEach(u -> ids.putIfAbsent(CatalogueNames.unitKey(u.name()), u.id()));
        return ids;
    }

    /**
     * Issue #698: the unit another name already stands for, other than {@code self}: of the units with its key, the switched
     * on one first, then the one named as the unit's word ("Piece" before "Pcs"), then by name.
     */
    Optional<Unit> sameUnit(String name, UUID self) {
        String key = CatalogueNames.unitKey(name);
        return unitsByPreference()
                .filter(u ->
                        !u.id().equals(self) && CatalogueNames.unitKey(u.name()).equals(key))
                .findFirst();
    }

    private java.util.stream.Stream<Unit> unitsByPreference() {
        return units().stream()
                .sorted(java.util.Comparator.comparing((Unit u) -> !u.active())
                        .thenComparing(u -> !CatalogueNames.unitKey(u.name())
                                .equalsIgnoreCase(u.name().trim())));
    }

    /** Issue #698: the tenant's lock for one unit key, so two people adding "pc" and "pcs" at once make one unit. */
    void lockUnitKey(String name) {
        jdbc.sql(
                        "SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id') || ':unit:' || :k, 0))")
                .param("k", CatalogueNames.unitKey(name))
                .query(rs -> {});
    }

    /** Name keys of switched-off categories, which an import must not add items to. */
    Set<String> inactiveCategoryNames() {
        return inactiveNames("retail_categories");
    }

    /** Issue #698: the unit keys whose unit ({@link #unitIdsByName}) is switched off. */
    Set<String> inactiveUnitNames() {
        Set<String> off = new java.util.HashSet<>();
        Set<String> seen = new java.util.HashSet<>();
        unitsByPreference().forEach(u -> {
            if (seen.add(CatalogueNames.unitKey(u.name())) && !u.active()) {
                off.add(CatalogueNames.unitKey(u.name()));
            }
        });
        return off;
    }

    private Set<String> inactiveNames(String table) {
        return new java.util.HashSet<>(
                jdbc.sql("SELECT name FROM " + table + " WHERE NOT active").query(String.class).list().stream()
                        .map(CatalogueNames::nameKey)
                        .toList());
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
        // Issue #698: keyed as names are compared, spaces inside counted once, so "Test  Cables" finds "Test Cables".
        jdbc.sql("SELECT id, name FROM " + table + " ORDER BY created_at, id")
                .query((rs, n) -> {
                    ids.putIfAbsent(CatalogueNames.nameKey(rs.getString("name")), rs.getObject("id", UUID.class));
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

    /**
     * Issue #697: the sizes ({@code variant_size}) or colours ({@code variant_colour}) the business's items have, each once
     * ignoring case, the most used first.
     */
    List<String> variantOptions(boolean sizes) {
        String column = sizes ? "variant_size" : "variant_colour";
        // Issue #726: byte order, so the spelling kept and the order of a tie are those of the mock.
        String first = "min(" + column + " COLLATE \"C\")";
        String sql = "SELECT " + first + " FROM retail_products WHERE btrim(coalesce(" + column + ", '')) <> ''"
                + " GROUP BY lower(btrim(" + column + ")) ORDER BY count(*) DESC, " + first + " COLLATE \"C\" LIMIT 60";
        return jdbc.sql(sql).query(String.class).list();
    }

    // ---- Names (issue #352) ---------------------------------------------------------------

    /** A product found by its name key, for the duplicate refusal and the near-duplicate warning. */
    record NameMatch(
            UUID id,
            String code,
            String description,
            UUID unitId,
            String unit,
            boolean active,
            String nameKey,
            UUID familyId) {}

    private static final String SELECT_NAME = """
            SELECT p.id, p.code, p.description, p.unit_id, u.name AS unit, p.active, p.name_key, p.family_id
              FROM retail_products p JOIN retail_units u ON u.id = p.unit_id
            """;

    private static NameMatch nameMatch(ResultSet rs, int n) throws SQLException {
        return new NameMatch(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getBoolean("active"),
                rs.getString("name_key"),
                rs.getObject("family_id", UUID.class));
    }

    /** Issue #384, ADR-044 one level up: an item with sizes and colours whose name has the same key as {@code name}. */
    record FamilyMatch(UUID id, String baseCode, String name, UUID unitId) {}

    List<FamilyMatch> sameFamilyName(String name, UUID excludeId) {
        return jdbc.sql("""
                        SELECT f.id, f.base_code, f.name, f.unit_id FROM retail_product_families f
                         WHERE bms_name_key(f.name) = bms_name_key(:d)
                           AND (CAST(:exclude AS uuid) IS NULL OR f.id <> CAST(:exclude AS uuid))
                         ORDER BY f.name, f.id
                        """)
                .param("d", name)
                .param("exclude", excludeId)
                .query((rs, n) -> new FamilyMatch(
                        rs.getObject("id", UUID.class),
                        rs.getString("base_code"),
                        rs.getString("name"),
                        rs.getObject("unit_id", UUID.class)))
                .list();
    }

    /**
     * Takes the tenant's lock for one name for the rest of the transaction, so two people adding the same name at the same
     * moment cannot both pass the check. The key is the database's own, the one the generated column holds.
     */
    void lockName(String description) {
        jdbc.sql(
                        "SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id') || ':' || bms_name_key(:d), 0))")
                .param("d", description)
                .query(rs -> {});
    }

    /** The name key of a description, as the database computes it. */
    String nameKey(String description) {
        return jdbc.sql("SELECT bms_name_key(:d)")
                .param("d", description)
                .query(String.class)
                .single();
    }

    /** Between a name key and a unit in {@link #productNames()}; the key holds only letters, digits and single spaces. */
    static final String NAME_SEP = "\0";

    /** Every product's name key and unit (lower-cased), as {@code key + NAME_SEP + unit}, to its code: the import's duplicate check. */
    Map<String, String> productNames() {
        Map<String, String> out = new java.util.HashMap<>();
        jdbc.sql(
                        "SELECT p.name_key, u.name AS unit, p.code FROM retail_products p JOIN retail_units u ON u.id = p.unit_id ORDER BY p.code")
                .query((rs, n) -> {
                    // Issue #698: the unit by its key, as the import keys a row's unit.
                    out.putIfAbsent(
                            rs.getString("name_key") + NAME_SEP + CatalogueNames.unitKey(rs.getString("unit")),
                            rs.getString("code"));
                    return 0;
                })
                .list();
        return out;
    }

    /**
     * Up to {@code limit} items as "name (unit)", newest first: what the setup helper tells the model the shop has already
     * (ADR-102), so it does not draft them again.
     */
    List<String> productLabels(int limit) {
        return jdbc.sql("""
                        SELECT p.description || ' (' || u.name || ')' FROM retail_products p
                          JOIN retail_units u ON u.id = p.unit_id
                         WHERE p.active ORDER BY p.created_at DESC, p.id LIMIT ?
                        """).param(limit).query(String.class).list();
    }

    /** Products whose name has the same key as the description, other than {@code excludeId}. */
    List<NameMatch> sameName(String description, UUID excludeId) {
        return jdbc.sql(SELECT_NAME
                        + " WHERE p.name_key = bms_name_key(:d) AND (CAST(:exclude AS uuid) IS NULL OR p.id <> CAST(:exclude AS uuid))"
                        + " ORDER BY p.code, p.id")
                .param("d", description)
                .param("exclude", excludeId)
                .query(CatalogueRepository::nameMatch)
                .list();
    }

    /**
     * Products whose name key is within {@code spread} characters of the given key's length: the only ones that can be a few
     * letters apart. The caller measures the edit distance; no text search operator is used, because none is usable under
     * row-level security (V40) and a catalogue is small.
     */
    List<NameMatch> neighbours(String nameKey, UUID excludeId, int spread, int limit) {
        return jdbc.sql(SELECT_NAME
                        + " WHERE length(p.name_key) BETWEEN :shortest AND :longest AND (CAST(:exclude AS uuid) IS NULL OR p.id <> CAST(:exclude AS uuid))"
                        + " ORDER BY p.code, p.id LIMIT :limit")
                .param("shortest", Math.max(0, nameKey.length() - spread))
                .param("longest", nameKey.length() + spread)
                .param("exclude", excludeId)
                .param("limit", limit)
                .query(CatalogueRepository::nameMatch)
                .list();
    }

    // ---- Products ------------------------------------------------------------------------

    Optional<Product> find(UUID id) {
        return jdbc.sql(SELECT_PRODUCT + " WHERE p.id = ?")
                .param(id)
                .query(CatalogueRepository::product)
                .optional();
    }

    /**
     * The low stock level of ADR-029 for an item with no minimum. It is the stock list's {@code low_stock_threshold}
     * (retail.stock, which this module may not depend on); {@code RetailSaleStockWarningIT} proves the two agree.
     */
    static final String LOW_STOCK_DEFAULT = "10.000";

    /**
     * Issue #815: the select-list column of the level at or below which the item is low at {@code branch} (a parameter), as
     * {@code LowStockRule.levelSql} judges it: the branch's own minimum, else the item's, else {@link #LOW_STOCK_DEFAULT}.
     */
    private static String lowStockLevel(String branch) {
        return ", coalesce((SELECT m.min_qty FROM retail_stock_minimums m WHERE m.tenant_id = p.tenant_id"
                + " AND m.product_id = p.id AND m.branch_id = " + branch + "),"
                + " (SELECT m.min_qty FROM retail_stock_minimums m WHERE m.tenant_id = p.tenant_id"
                + " AND m.product_id = p.id AND m.branch_id IS NULL), " + LOW_STOCK_DEFAULT + ") AS low_stock_level";
    }

    /** A product with the balance of a branch, as a search row has it (issue #345, the barcode lookup). */
    Optional<Product> findAt(UUID id, UUID branchId) {
        if (branchId == null) {
            return find(id);
        }
        return jdbc.sql(
                        SELECT_PRODUCT.replace(
                                        " FROM retail_products p",
                                        ", b.qty, b.reserved_qty" + lowStockLevel("?") + " FROM retail_products p")
                                + " LEFT JOIN retail_stock_balances b ON b.product_id = p.id AND b.branch_id = ? WHERE p.id = ?")
                .params(branchId, branchId, id)
                .query(CatalogueRepository::productWithQty)
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

    /** What a product search matches (ADR-039): the code, the description and the category name. */
    static final Search.Fields SEARCH =
            Search.Fields.of().names("p.description", "c.name").codes("p.code");

    /**
     * One page ordered by code, or by match rank and then code when {@code query} is given; the query
     * matches the code, the description or the category name.
     */
    List<Search.Ranked<Product>> page(
            Search.Query query,
            UUID categoryId,
            UUID unitId,
            Boolean active,
            UUID branchId,
            int afterRank,
            String afterCode,
            UUID afterId,
            int limit) {
        Search.Match match = query == null ? null : query.match(SEARCH);
        String select = match == null
                ? SELECT_PRODUCT
                : SELECT_PRODUCT.replace("SELECT p.id,", "SELECT " + match.rank() + " AS search_rank, p.id,");
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql;
        if (branchId == null) {
            sql = new StringBuilder(select + " WHERE true");
        } else {
            // The balance of retail_stock_balances (the stock module's table), read for the search row.
            sql = new StringBuilder(
                    select.replace(
                                    " FROM retail_products p",
                                    ", b.qty, b.reserved_qty" + lowStockLevel(":branchId") + " FROM retail_products p")
                            + " LEFT JOIN retail_stock_balances b ON b.product_id = p.id AND b.branch_id = :branchId WHERE true");
            params.put("branchId", branchId);
        }
        if (match != null) {
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }
        // Issue #863: a unit's items are those counted in it and those with an active package of it (Box, Dozen); the
        // package table is read under its own row-level security, so another tenant's unit matches nothing.
        if (unitId != null) {
            sql.append(" AND (p.unit_id = :unitId OR EXISTS (SELECT 1 FROM retail_product_units pu"
                    + " WHERE pu.product_id = p.id AND pu.unit_id = :unitId AND pu.active))");
            params.put("unitId", unitId);
        }
        if (active != null) {
            sql.append(" AND p.active = :active");
            params.put("active", active);
        }
        String statement;
        if (match == null) {
            if (afterCode != null) {
                sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
                params.put("afterCode", afterCode);
                params.put("afterId", afterId);
            }
            statement = sql + " ORDER BY p.code, p.id LIMIT :limit";
        } else {
            String keyset = null;
            if (afterCode != null) {
                keyset = "(search_rank, code, id) > (:afterRank, :afterCode, :afterId)";
                params.put("afterRank", afterRank);
                params.put("afterCode", afterCode);
                params.put("afterId", afterId);
            }
            statement = Search.page(sql.toString(), keyset, "search_rank, code, id");
        }
        params.put("limit", limit);
        return jdbc.sql(statement)
                .params(params)
                .query(Search.ranked(
                        branchId == null ? CatalogueRepository::product : CatalogueRepository::productWithQty,
                        match != null))
                .list();
    }

    void insert(Product p, UUID createdBy, String duplicateReason) {
        insert(p, createdBy, duplicateReason, null, null, null, null);
    }

    /**
     * A product, or with {@code familyId} a variant of an item with sizes and colours (issue #384): its size, colour (one
     * may be null) and place in the item's list.
     */
    void insert(
            Product p,
            UUID createdBy,
            String duplicateReason,
            UUID familyId,
            String size,
            String colour,
            Integer position) {
        jdbc.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency, active, created_by, duplicate_reason,
                                                     family_id, variant_size, variant_colour, variant_sort)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :code, :description, :categoryId, :unitId,
                                :costMinor, :sellMinor, :currency, :active, :createdBy, :duplicateReason,
                                :familyId, :size, :colour, :position)
                        """)
                .param("familyId", familyId, java.sql.Types.OTHER)
                .param("size", size, java.sql.Types.VARCHAR)
                .param("colour", colour, java.sql.Types.VARCHAR)
                .param("position", position, java.sql.Types.INTEGER)
                .param("duplicateReason", duplicateReason)
                .param("active", p.active())
                .param("id", p.id())
                .param("code", p.code())
                // Issue #698 (D86 = A): every item name is stored in the one display style, whichever way it was added.
                .param("description", CatalogueNames.display(p.description()))
                .param("categoryId", p.categoryId())
                .param("unitId", p.unitId())
                .param("costMinor", p.costMinor())
                .param("sellMinor", p.sellMinor())
                .param("currency", p.currency())
                .param("createdBy", createdBy)
                .update();
    }

    void update(Product p, String duplicateReason, boolean nameChanged) {
        jdbc.sql("""
                        UPDATE retail_products SET code = :code, description = :description, category_id = :categoryId,
                               unit_id = :unitId, active = :active, updated_at = now(), version = version + 1,
                               duplicate_reason = CASE WHEN :nameChanged THEN :duplicateReason ELSE duplicate_reason END
                         WHERE id = :id
                        """)
                .param("duplicateReason", duplicateReason, java.sql.Types.VARCHAR)
                .param("nameChanged", nameChanged)
                .param("id", p.id())
                .param("code", p.code())
                // A name is restyled only when it is changed: an edit of a price never moves it unaudited.
                .param("description", nameChanged ? CatalogueNames.display(p.description()) : p.description())
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

    /*
     * Issue #624: the suggested price is the piece price times the size, rounded half up once (Quantities.packPrice). A
     * selling pack is below cost when its price (its own, else the suggested one) is not above the size times the item's
     * average cost per piece: the branches' averages weighted by what each holds (ADR-079 decision 1), or the last paid
     * cost while no branch holds any. The service drops both for a caller who may not read them.
     */
    private static final String SELECT_PACK = """
            SELECT k.id, k.product_id, k.unit_id, u.name AS unit, k.factor, k.active, k.version, k.purpose, k.sell_minor,
                   round(k.factor * p.sell_minor)::bigint AS suggested_sell_minor,
                   coalesce(k.sell_minor, round(k.factor * p.sell_minor)) <= round(k.factor * coalesce(
                       (SELECT sum(b.qty * coalesce(b.avg_cost_minor, p.cost_minor)) / sum(b.qty)
                          FROM retail_stock_balances b
                         WHERE b.tenant_id = k.tenant_id AND b.product_id = k.product_id AND b.qty > 0),
                       p.cost_minor)) AS below_cost,
                   k.id IS NOT DISTINCT FROM (SELECT pack_id FROM (""" + RetailCatalogue.standardPackSql("p") + """
                   ) sp) AS standard,
                   k.holds_unit_id, hu.name AS holds_unit, k.holds_qty, k.usual_buy
              FROM retail_product_units k JOIN retail_units u ON u.id = k.unit_id
              JOIN retail_products p ON p.id = k.product_id
              LEFT JOIN retail_units hu ON hu.id = k.holds_unit_id
            """;

    private static Pack pack(ResultSet rs, int n) throws SQLException {
        return new Pack(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                rs.getBigDecimal("factor").setScale(3).toPlainString(),
                rs.getBoolean("active"),
                rs.getInt("version"),
                rs.getString("purpose"),
                rs.getObject("sell_minor", Long.class),
                rs.getObject("suggested_sell_minor", Long.class),
                rs.getObject("below_cost", Boolean.class),
                rs.getObject("sell_minor") != null,
                rs.getBoolean("standard"),
                rs.getObject("holds_unit_id", UUID.class),
                rs.getString("holds_unit"),
                rs.getObject("holds_qty", Integer.class),
                rs.getBoolean("usual_buy"));
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
    /** The sell price in force at {@code at} (see {@link com.rincoltech.bms.retail.catalogue.RetailCatalogue#sellMinorAt}). */
    long sellMinorAt(UUID productId, java.time.Instant at) {
        return jdbc.sql("""
                        SELECT coalesce(
                            (SELECT h.new_sell_minor FROM retail_price_history h
                              WHERE h.product_id = :p AND h.created_at <= :at ORDER BY h.created_at DESC, h.id DESC LIMIT 1),
                            (SELECT coalesce(h.old_sell_minor, h.new_sell_minor) FROM retail_price_history h
                              WHERE h.product_id = :p AND h.created_at > :at ORDER BY h.created_at, h.id LIMIT 1),
                            (SELECT p.sell_minor FROM retail_products p WHERE p.id = :p))
                        """)
                .param("p", productId)
                .param("at", java.sql.Timestamp.from(at))
                .query(Long.class)
                .single();
    }

    void insertPack(
            UUID id,
            UUID productId,
            UUID unitId,
            java.math.BigDecimal factor,
            String purpose,
            Long sellMinor,
            UUID holdsUnitId,
            Integer holdsQty,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_product_units
                            (id, tenant_id, product_id, unit_id, factor, purpose, sell_minor, holds_unit_id, holds_qty,
                             created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(id, productId, unitId, factor, purpose, sellMinor, holdsUnitId, holdsQty, by)
                .update();
    }

    /**
     * Issue #624: the whole pack at once, with its purpose and own selling price, and (issue #699) what it holds; 0 when the
     * version moved. A pack made only for selling, or switched off, is no longer the usual buying level.
     */
    int updatePack(
            UUID id,
            java.math.BigDecimal factor,
            boolean active,
            String purpose,
            Long sellMinor,
            UUID holdsUnitId,
            Integer holdsQty,
            int expectedVersion) {
        return jdbc.sql("""
                        UPDATE retail_product_units SET factor = ?, active = ?, purpose = ?, sell_minor = ?,
                               holds_unit_id = ?, holds_qty = ?,
                               usual_buy = usual_buy AND ? AND ? <> 'sell',
                               updated_at = now(), version = version + 1
                         WHERE id = ? AND version = ?
                        """)
                .params(factor, active, purpose, sellMinor, holdsUnitId, holdsQty, active, purpose, id, expectedVersion)
                .update();
    }

    /**
     * Issue #699 (ADR-107): a level's size and what it holds, for a level worked out again because one it holds changed or a
     * delivery's size was made usual. The caller holds the product's row lock, so no version is compared; it is still moved.
     * JUSTIFICATION-A3: the two writes the ladder needs, beside the pack's other writes.
     */
    void resizePack(UUID id, java.math.BigDecimal factor, UUID holdsUnitId, Integer holdsQty) {
        jdbc.sql("""
                        UPDATE retail_product_units SET factor = ?, holds_unit_id = ?, holds_qty = ?,
                               updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(factor, holdsUnitId, holdsQty, id).update();
    }

    /**
     * Issue #699: the level Buy stock opens on. Making a pack usual clears every other pack of the item first (one per item,
     * V118) and returns them, so each is audited (review of #710); clearing a pack touches that pack only (round 4 of the
     * review: false on a box never clears the carton) and returns none.
     */
    List<UUID> setUsualBuy(UUID productId, UUID packId, boolean usual) {
        if (!usual) {
            jdbc.sql("""
                            UPDATE retail_product_units SET usual_buy = false, updated_at = now(), version = version + 1
                             WHERE id = ? AND usual_buy
                            """).param(packId).update();
            return List.of();
        }
        List<UUID> cleared =
                jdbc.sql("""
                        UPDATE retail_product_units SET usual_buy = false, updated_at = now(), version = version + 1
                         WHERE product_id = ? AND usual_buy AND id <> ?
                        RETURNING id
                        """).params(productId, packId).query(UUID.class).list();
        jdbc.sql("""
                        UPDATE retail_product_units SET usual_buy = true, updated_at = now(), version = version + 1
                         WHERE id = ? AND NOT usual_buy
                        """).param(packId).update();
        return cleared;
    }

    /** Active packs only: a pack that was switched off no longer ties the product's unit or the pack's unit down. */
    boolean productHasPacks(UUID productId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_product_units WHERE product_id = ? AND active)")
                .param(productId)
                .query(Boolean.class)
                .single();
    }

    /** Any price list item of the product: items price per unit, so the base unit cannot change under them (issue #195). */
    boolean productHasPriceItems(UUID productId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_price_list_items WHERE product_id = ?)")
                .param(productId)
                .query(Boolean.class)
                .single();
    }

    /** Issue #342: a product's balance in one branch (the stock module's table), for the edit screen's warning. */
    record BranchBalance(UUID branchId, BigDecimal qty) {}

    List<BranchBalance> stockBalances(UUID productId) {
        return jdbc.sql("SELECT branch_id, qty FROM retail_stock_balances WHERE product_id = ? AND qty <> 0")
                .param(productId)
                .query((rs, n) -> new BranchBalance(rs.getObject("branch_id", UUID.class), rs.getBigDecimal("qty")))
                .list();
    }

    /** Any stock movement of the product, or a balance that is not zero: its quantities are counted in its unit (issue #351). */
    boolean productHasStock(UUID productId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_stock_movements WHERE product_id = ?)
                            OR EXISTS (SELECT 1 FROM retail_stock_balances WHERE product_id = ? AND qty <> 0)
                        """).params(productId, productId).query(Boolean.class).single();
    }

    /**
     * Review of #722 (ADR-110): a pack of the unit, on any item and switched on or off, holding another number than
     * {@code count} of what it holds (its factor when it names no level).
     */
    boolean unitHasPackNotHolding(UUID unitId, int count) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_product_units
                                        WHERE unit_id = ? AND COALESCE(holds_qty::numeric, factor) <> ?)
                        """).params(unitId, count).query(Boolean.class).single();
    }

    boolean unitHasPacks(UUID unitId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_product_units WHERE unit_id = ? AND active)")
                .param(unitId)
                .query(Boolean.class)
                .single();
    }

    static Product product(ResultSet rs, int n) throws SQLException {
        return plainProduct(rs)
                .withVariant(variantOf(rs))
                .withPhoto(rs.getObject("photo_id", UUID.class), rs.getObject("item_photo_id", UUID.class));
    }

    /** The item a product is a variant of (issue #384), or null for an item without variants. */
    static CatalogueApi.VariantOf variantOf(ResultSet rs) throws SQLException {
        UUID familyId = rs.getObject("family_id", UUID.class);
        if (familyId == null) {
            return null;
        }
        return new CatalogueApi.VariantOf(
                familyId,
                rs.getString("family_name"),
                rs.getString("variant_size"),
                rs.getString("variant_colour"),
                rs.getInt("variant_sort"));
    }

    private static Product plainProduct(ResultSet rs) throws SQLException {
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
                        null)
                .withVariant(p.variant())
                .withPhoto(p.photoId(), p.itemPhotoId())
                .withReserved(reserved(rs))
                .withLowStockLevel(
                        rs.getBigDecimal("low_stock_level").setScale(3).toPlainString());
    }

    /** What customer orders keep aside of the branch balance (issue #385), or null when nothing is. */
    private static String reserved(ResultSet rs) throws SQLException {
        BigDecimal kept = rs.getBigDecimal("reserved_qty");
        return kept == null || kept.signum() == 0 ? null : kept.setScale(3).toPlainString();
    }

    // ---- Items with sizes and colours (issue #384, ADR-051) --------------------------------------------

    /** An item with sizes and colours, as stored. */
    record FamilyRow(
            UUID id,
            String name,
            String baseCode,
            UUID categoryId,
            String category,
            UUID unitId,
            String unit,
            Instant createdAt,
            int version,
            UUID photoId) {}

    private static final String SELECT_FAMILY = """
            SELECT f.id, f.name, f.base_code, f.category_id, c.name AS category, f.unit_id, u.name AS unit, f.created_at,
                   f.version, f.photo_document_id
              FROM retail_product_families f
              JOIN retail_categories c ON c.id = f.category_id
              JOIN retail_units u ON u.id = f.unit_id
            """;

    private static FamilyRow familyRow(ResultSet rs, int n) throws SQLException {
        return new FamilyRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("base_code"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit"),
                instant(rs, "created_at"),
                rs.getInt("version"),
                rs.getObject("photo_document_id", UUID.class));
    }

    Optional<FamilyRow> family(UUID id) {
        return jdbc.sql(SELECT_FAMILY + " WHERE f.id = ?")
                .param(id)
                .query(CatalogueRepository::familyRow)
                .optional();
    }

    /** The item's row lock: serialises adding variants, renaming and moving the item. */
    Optional<FamilyRow> lockFamily(UUID id) {
        return jdbc.sql(SELECT_FAMILY + " WHERE f.id = ? FOR NO KEY UPDATE OF f")
                .param(id)
                .query(CatalogueRepository::familyRow)
                .optional();
    }

    void insertFamily(
            UUID id,
            String name,
            String baseCode,
            UUID categoryId,
            UUID unitId,
            UUID createdBy,
            String duplicateReason) {
        jdbc.sql("""
                        INSERT INTO retail_product_families (id, tenant_id, name, base_code, category_id, unit_id, created_by,
                                                             duplicate_reason)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :name, :base, :category, :unit, :by, :reason)
                        """)
                .param("id", id)
                .param("name", CatalogueNames.display(name))
                .param("base", baseCode)
                .param("category", categoryId)
                .param("unit", unitId)
                .param("by", createdBy)
                .param("reason", duplicateReason, java.sql.Types.VARCHAR)
                .update();
    }

    /** Rows changed: 0 when the version moved since it was read. */
    int updateFamily(
            UUID id, String name, UUID categoryId, int expectedVersion, boolean renamed, String duplicateReason) {
        return jdbc.sql("""
                        UPDATE retail_product_families SET name = :name, category_id = :category, updated_at = now(),
                               version = version + 1,
                               duplicate_reason = CASE WHEN :renamed THEN :reason ELSE duplicate_reason END
                         WHERE id = :id AND version = :version
                        """)
                .param("name", CatalogueNames.display(name))
                .param("category", categoryId)
                .param("renamed", renamed)
                .param("reason", duplicateReason, java.sql.Types.VARCHAR)
                .param("id", id)
                .param("version", expectedVersion)
                .update();
    }

    /** A product joining an item with sizes takes the item's category (issue #384). */
    void setCategory(UUID productId, UUID categoryId) {
        jdbc.sql("""
                        UPDATE retail_products SET category_id = ?, updated_at = now(), version = version + 1
                         WHERE id = ? AND category_id <> ?
                        """).params(categoryId, productId, categoryId).update();
    }

    /** Every variant of an item, active or not, in the item's order. */
    List<Product> variants(UUID familyId) {
        return jdbc.sql(SELECT_PRODUCT + " WHERE p.family_id = ? ORDER BY p.variant_sort, p.code, p.id")
                .param(familyId)
                .query(CatalogueRepository::product)
                .list();
    }

    /** The balances of an item's variants that are not zero, for the item's stock (the stock module's table). */
    record VariantBalance(UUID productId, UUID branchId, BigDecimal qty) {}

    List<VariantBalance> familyBalances(UUID familyId) {
        return jdbc.sql("""
                        SELECT b.product_id, b.branch_id, b.qty
                          FROM retail_stock_balances b JOIN retail_products p ON p.id = b.product_id
                         WHERE p.family_id = ? AND b.qty <> 0
                        """)
                .param(familyId)
                .query((rs, n) -> new VariantBalance(
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getBigDecimal("qty")))
                .list();
    }

    /** An item without variants joins an item as one of its variants, with its new description (issue #384). */
    void joinFamily(UUID productId, UUID familyId, String size, String colour, int position, String description) {
        jdbc.sql("""
                        UPDATE retail_products SET family_id = :familyId, variant_size = :size, variant_colour = :colour,
                               variant_sort = :position, description = :description, duplicate_reason = NULL,
                               updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("familyId", familyId)
                .param("size", size, java.sql.Types.VARCHAR)
                .param("colour", colour, java.sql.Types.VARCHAR)
                .param("position", position)
                .param("description", CatalogueNames.display(description))
                .param("id", productId)
                .update();
    }

    /** The reason a product kept a name that was in use, null when it has none; a join clears it. */
    String duplicateReason(UUID productId) {
        return jdbc.sql("SELECT duplicate_reason FROM retail_products WHERE id = ?")
                .param(productId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * The active pack sizes of a product copied to another (a new size of the same item), with their purpose, and their own
     * selling prices only when {@code withPrices}; rows copied.
     */
    int copyPacks(UUID fromProductId, UUID toProductId, boolean withPrices, UUID by) {
        return jdbc.sql("""
                        INSERT INTO retail_product_units
                            (id, tenant_id, product_id, unit_id, factor, purpose, sell_minor, created_by)
                        SELECT gen_random_uuid(), tenant_id, :to, unit_id, factor, purpose,
                               CASE WHEN :prices THEN sell_minor END, :by
                          FROM retail_product_units WHERE product_id = :from AND active
                        """)
                .param("from", fromProductId)
                .param("to", toProductId)
                .param("prices", withPrices)
                .param("by", by)
                .update();
    }

    /**
     * A price list price of a product, with the base units in its unit: 1 for the product's own unit, the factor of an
     * active pack, null for any other unit (a pack switched off, or one the product no longer has).
     */
    record PriceItemTerms(
            UUID priceListId,
            UUID unitId,
            BigDecimal minQty,
            long priceMinor,
            java.time.LocalDate validFrom,
            java.time.LocalDate validTo,
            BigDecimal factor) {}

    /** The price list prices of a product that are in force on {@code today} or start later (issue #395). */
    List<PriceItemTerms> priceItemsInForce(UUID productId, java.time.LocalDate today) {
        return jdbc.sql("""
                        SELECT i.price_list_id, i.unit_id, i.min_qty, i.price_minor, i.valid_from, i.valid_to,
                               CASE WHEN i.unit_id = p.unit_id THEN 1 ELSE k.factor END AS factor
                          FROM retail_price_list_items i
                          JOIN retail_products p ON p.tenant_id = i.tenant_id AND p.id = i.product_id
                          LEFT JOIN retail_product_units k
                            ON k.tenant_id = i.tenant_id AND k.product_id = i.product_id AND k.unit_id = i.unit_id
                           AND k.active
                         WHERE i.product_id = :product AND (i.valid_to IS NULL OR i.valid_to >= :today)
                         ORDER BY i.price_list_id, i.unit_id, i.min_qty, i.valid_from, i.id
                        """)
                .param("product", productId)
                .param("today", java.sql.Date.valueOf(today))
                .query((rs, n) -> new PriceItemTerms(
                        rs.getObject("price_list_id", UUID.class),
                        rs.getObject("unit_id", UUID.class),
                        rs.getBigDecimal("min_qty"),
                        rs.getLong("price_minor"),
                        rs.getDate("valid_from").toLocalDate(),
                        rs.getDate("valid_to") == null
                                ? null
                                : rs.getDate("valid_to").toLocalDate(),
                        rs.getBigDecimal("factor")))
                .list();
    }

    /** Adding variants changes the item: its version moves, so an item read before is known to be stale. */
    void touchFamily(UUID id) {
        jdbc.sql("UPDATE retail_product_families SET updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id)
                .update();
    }

    /** A variant's size, colour and description, after a rename of its item. */
    void setVariantName(UUID productId, String size, String colour, String description) {
        setVariantName(productId, size, colour, description, true);
    }

    /** As above; {@code bump} false when the caller's own update of the row has moved its version already. */
    void setVariantName(UUID productId, String size, String colour, String description, boolean bump) {
        jdbc.sql("""
                        UPDATE retail_products SET variant_size = :size, variant_colour = :colour, description = :description,
                               updated_at = now(), version = version + CASE WHEN :bump THEN 1 ELSE 0 END
                         WHERE id = :id
                        """)
                .param("bump", bump)
                .param("size", size, java.sql.Types.VARCHAR)
                .param("colour", colour, java.sql.Types.VARCHAR)
                .param("description", CatalogueNames.display(description))
                .param("id", productId)
                .update();
    }

    /** Another variant of the item with this size and colour (case ignored), other than {@code excludeId}. */
    boolean variantTaken(UUID familyId, String size, String colour, UUID excludeId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_products
                                        WHERE family_id = :familyId AND id <> :excludeId
                                          AND lower(coalesce(variant_size, '')) = lower(coalesce(CAST(:size AS text), ''))
                                          AND lower(coalesce(variant_colour, '')) = lower(coalesce(CAST(:colour AS text), '')))
                        """)
                .param("familyId", familyId)
                .param("excludeId", excludeId)
                .param("size", size, java.sql.Types.VARCHAR)
                .param("colour", colour, java.sql.Types.VARCHAR)
                .query(Boolean.class)
                .single();
    }

    /** Issue #384: a product's own photo (a document of core.documents), or none; its row's version moves. */
    void setProductPhoto(UUID productId, UUID documentId) {
        jdbc.sql("""
                        UPDATE retail_products SET photo_document_id = :doc, updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("doc", documentId, java.sql.Types.OTHER)
                .param("id", productId)
                .update();
    }

    /** Issue #384: an item's photo, shown for every size and colour that has none of its own. */
    void setFamilyPhoto(UUID familyId, UUID documentId) {
        jdbc.sql("""
                        UPDATE retail_product_families SET photo_document_id = :doc, updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("doc", documentId, java.sql.Types.OTHER)
                .param("id", familyId)
                .update();
    }

    /** The document id of a product's own photo (not its item's), for the audit row of a change. */
    Optional<UUID> ownPhoto(UUID productId) {
        return jdbc.sql("SELECT photo_document_id FROM retail_products WHERE id = ?")
                .param(productId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
                .optional()
                .flatMap(x -> x);
    }

    /** Every variant of an item follows its new category. */
    void setFamilyCategory(UUID familyId, UUID categoryId) {
        jdbc.sql("""
                        UPDATE retail_products SET category_id = ?, updated_at = now(), version = version + 1
                         WHERE family_id = ? AND category_id <> ?
                        """).params(categoryId, familyId, categoryId).update();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
