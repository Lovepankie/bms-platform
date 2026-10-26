package com.rincoltech.bms.retail.catalogue.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the entry warnings (issue #353, ADR-093). Every statement reads one item's newest lines through an index
 * that leads on the product in the order asked for (V104, on sale, purchase and transfer lines) and stops at
 * {@code limit} rows; row-level security supplies the tenant predicate (ADR-003). The name hints read one tenant's catalogue,
 * bounded the same way as {@link CatalogueRepository#neighbours}.
 */
@Repository
class EntryChecksRepository {

    /** One line read: its quantity in base units and the unit it was entered in (null for the item's own unit). */
    record LineQty(BigDecimal baseQty, UUID unitId, String unit) {}

    /** The last sale: what was charged for the line and its quantity in base units. */
    record LastSale(long lineTotalMinor, BigDecimal baseQty, LocalDate saleDate) {}

    /** The item's newest purchase lines, voided restocks left out. */
    static final String PURCHASES = """
            SELECT l.qty_total AS qty, l.unit_id, u.name AS unit
              FROM retail_purchase_lines l LEFT JOIN retail_units u ON u.id = l.unit_id
             WHERE l.product_id = :p
               AND NOT EXISTS (SELECT 1 FROM retail_purchase_voids v WHERE v.purchase_id = l.purchase_id)
             ORDER BY l.created_at DESC, l.id DESC
             LIMIT :limit
            """;

    /** The item's newest sale lines, voided sales left out. */
    static final String SALES = """
            SELECT l.qty, l.sale_unit_id AS unit_id, u.name AS unit
              FROM retail_sale_lines l
              JOIN retail_sales s ON s.id = l.sale_id
              LEFT JOIN retail_units u ON u.id = l.sale_unit_id
             WHERE l.product_id = :p AND s.status = 'completed'
             ORDER BY l.created_at DESC, l.id DESC
             LIMIT :limit
            """;

    /** The item's newest moves, voided and cancelled ones left out. */
    static final String MOVES = """
            SELECT l.qty, CAST(NULL AS uuid) AS unit_id, CAST(NULL AS text) AS unit
              FROM retail_transfer_lines l JOIN retail_transfers t ON t.id = l.transfer_id
             WHERE l.product_id = :p AND t.status NOT IN ('voided', 'cancelled')
             ORDER BY l.created_at DESC, l.id DESC
             LIMIT :limit
            """;

    /**
     * The item's last completed sale (the newest of the item's newest {@code :window} lines, of {@code :seller} when given): an
     * index walk of at most {@code :window} entries, then a sort of those few only.
     */
    static final String LAST_SALE = """
            SELECT r.line_total_minor, r.qty, r.sale_date
              FROM (SELECT l.line_total_minor, l.qty, s.sale_date, s.created_by, l.created_at, l.id
                      FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
                     WHERE l.product_id = :p AND s.status = 'completed'
                     ORDER BY l.created_at DESC, l.id DESC
                     LIMIT :window) r
             WHERE CAST(:seller AS uuid) IS NULL OR r.created_by = CAST(:seller AS uuid)
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT 1
            """;

    /** How many of the item's newest sale lines a seller's own last price is looked for in. */
    static final int OWN_SALE_WINDOW = 200;

    private final JdbcClient jdbc;

    EntryChecksRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String nameKey(String text) {
        return jdbc.sql("SELECT bms_name_key(:d)")
                .param("d", text)
                .query(String.class)
                .single();
    }

    /** The item's base unit name, or null when no such item is visible. */
    String baseUnit(UUID productId) {
        return jdbc.sql("SELECT u.name FROM retail_products p JOIN retail_units u ON u.id = p.unit_id WHERE p.id = :p")
                .param("p", productId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    List<LineQty> purchases(UUID productId, int limit) {
        return jdbc.sql(PURCHASES)
                .param("p", productId)
                .param("limit", limit)
                .query(EntryChecksRepository::lineQty)
                .list();
    }

    List<LineQty> sales(UUID productId, int limit) {
        return jdbc.sql(SALES)
                .param("p", productId)
                .param("limit", limit)
                .query(EntryChecksRepository::lineQty)
                .list();
    }

    /** Moves are counted in the item's own unit (FR-RET-16), so the unit is always null. */
    List<LineQty> moves(UUID productId, int limit) {
        return jdbc.sql(MOVES)
                .param("p", productId)
                .param("limit", limit)
                .query(EntryChecksRepository::lineQty)
                .list();
    }

    /**
     * The item's last completed sale; with {@code sellerId}, the last of that seller's own among the item's newest
     * {@link #OWN_SALE_WINDOW} lines (ADR-055: a seller who sees only their own figures), so the read stays bounded.
     */
    LastSale lastSale(UUID productId, UUID sellerId) {
        return jdbc.sql(LAST_SALE)
                .param("p", productId)
                .param("seller", sellerId)
                .param("window", sellerId == null ? 1 : OWN_SALE_WINDOW)
                .query((rs, n) -> new LastSale(
                        rs.getLong("line_total_minor"),
                        rs.getBigDecimal("qty"),
                        rs.getObject("sale_date", LocalDate.class)))
                .optional()
                .orElse(null);
    }

    private static LineQty lineQty(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LineQty(rs.getBigDecimal("qty"), rs.getObject("unit_id", UUID.class), rs.getString("unit"));
    }

    /** A product the name hints offer, with its category and its name key without spaces. */
    record HintRow(
            UUID id, String code, String description, String unit, UUID categoryId, String category, String nameKey) {

        String compactKey() {
            return nameKey.replace(" ", "");
        }
    }

    private static final String SELECT_HINT = """
            SELECT p.id, p.code, p.description, u.name AS unit, p.category_id, c.name AS category, p.name_key
              FROM retail_products p
              JOIN retail_units u ON u.id = p.unit_id
              JOIN retail_categories c ON c.id = p.category_id
            """;

    private static HintRow hintRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new HintRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getString("unit"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category"),
                rs.getString("name_key"));
    }

    /** Active items whose name, without spaces, holds the typed name without spaces: those starting with it first. */
    List<HintRow> holding(String compactKey, int limit) {
        return jdbc.sql(SELECT_HINT + """
                         WHERE p.active AND strpos(replace(p.name_key, ' ', ''), :k) > 0
                         ORDER BY strpos(replace(p.name_key, ' ', ''), :k) = 1 DESC, length(p.name_key), p.code, p.id
                         LIMIT :limit
                        """)
                .param("k", compactKey)
                .param("limit", limit)
                .query(EntryChecksRepository::hintRow)
                .list();
    }

    /** Active items whose name key is within {@code spread} characters of the length of the typed one, for the edit distance. */
    List<HintRow> nearLength(int length, int spread, int limit) {
        return jdbc.sql(SELECT_HINT + """
                         WHERE p.active AND length(replace(p.name_key, ' ', '')) BETWEEN :shortest AND :longest
                         ORDER BY p.code, p.id
                         LIMIT :limit
                        """)
                .param("shortest", Math.max(0, length - spread))
                .param("longest", length + spread)
                .param("limit", limit)
                .query(EntryChecksRepository::hintRow)
                .list();
    }

    /**
     * Active items whose name holds one of the words as a whole word; the words are name-key words (letters and digits only),
     * so they carry no LIKE wildcard.
     */
    List<HintRow> sharingWords(List<String> words, int limit) {
        String[] patterns = words.stream().map(w -> "% " + w + " %").toArray(String[]::new);
        return jdbc.sql(SELECT_HINT + """
                         WHERE p.active AND (' ' || p.name_key || ' ') LIKE ANY (:patterns)
                         ORDER BY p.code, p.id
                         LIMIT :limit
                        """)
                .param("patterns", patterns)
                .param("limit", limit)
                .query(EntryChecksRepository::hintRow)
                .list();
    }
}
