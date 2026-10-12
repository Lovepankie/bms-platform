package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.catalogue.RetailPricing.ListedPrice;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.Group;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.Item;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceList;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for price lists, their items and customer groups. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class PriceListRepository {

    private final JdbcClient jdbc;

    PriceListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An item's range, for the overlap rule. */
    record Range(UUID id, LocalDate validFrom, LocalDate validTo) {}

    // ---- Lists -------------------------------------------------------------------------------

    private static final String SELECT_LIST = """
            SELECT l.id, l.name, l.currency, l.strict, l.active, l.version,
                   (SELECT count(*) FROM retail_price_list_items i
                     WHERE i.price_list_id = l.id AND (i.valid_to IS NULL OR i.valid_to >= :today)) AS current_items
              FROM retail_price_lists l
            """;

    private static PriceList list(ResultSet rs, int n) throws SQLException {
        return new PriceList(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("currency").strip(),
                rs.getBoolean("strict"),
                rs.getBoolean("active"),
                rs.getInt("current_items"),
                rs.getInt("version"));
    }

    List<PriceList> lists(LocalDate today) {
        return jdbc.sql(SELECT_LIST + " ORDER BY lower(l.name), l.id")
                .param("today", Date.valueOf(today))
                .query(PriceListRepository::list)
                .list();
    }

    Optional<PriceList> list(UUID id, LocalDate today) {
        return jdbc.sql(SELECT_LIST + " WHERE l.id = :id")
                .param("today", Date.valueOf(today))
                .param("id", id)
                .query(PriceListRepository::list)
                .optional();
    }

    /** The list row, locked for the rest of the transaction: item writes of one list serialise on it. */
    Optional<PriceList> lockList(UUID id, LocalDate today) {
        return jdbc.sql(SELECT_LIST + " WHERE l.id = :id FOR UPDATE OF l")
                .param("today", Date.valueOf(today))
                .param("id", id)
                .query(PriceListRepository::list)
                .optional();
    }

    void insertList(UUID id, String name, String currency, boolean strict, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_price_lists (id, tenant_id, name, currency, strict, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, name, currency, strict, by).update();
    }

    /** Rows changed: 0 when the version moved since it was read. */
    int updateList(UUID id, String name, boolean strict, boolean active, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE retail_price_lists SET name = ?, strict = ?, active = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ?
                        """).params(name, strict, active, id, expectedVersion).update();
    }

    // ---- Items -------------------------------------------------------------------------------

    private static final String SELECT_ITEM = """
            SELECT i.id, i.price_list_id, i.product_id, p.code, p.description, i.unit_id, u.name AS unit, i.min_qty,
                   i.price_minor, i.valid_from, i.valid_to,
                   coalesce(CASE WHEN i.unit_id = p.unit_id THEN i.price_minor <= p.cost_minor
                                 ELSE i.price_minor <= round(k.factor * p.cost_minor) END, false) AS below_cost,
                   lower(p.description) AS sort_key
              FROM retail_price_list_items i
              JOIN retail_products p ON p.id = i.product_id
              JOIN retail_units u ON u.id = i.unit_id
              LEFT JOIN retail_product_units k ON k.product_id = i.product_id AND k.unit_id = i.unit_id
            """;

    /** The cost flag is computed here and removed by the service for a caller who may not read cost. */
    record ItemRow(Item item, String sortKey) {}

    private static ItemRow itemRow(ResultSet rs, LocalDate today) throws SQLException {
        Date to = rs.getDate("valid_to");
        LocalDate from = rs.getDate("valid_from").toLocalDate();
        LocalDate end = to == null ? null : to.toLocalDate();
        String status = from.isAfter(today) ? "scheduled" : end != null && end.isBefore(today) ? "ended" : "current";
        return new ItemRow(
                new Item(
                        rs.getObject("id", UUID.class),
                        rs.getObject("price_list_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getObject("unit_id", UUID.class),
                        rs.getString("unit"),
                        rs.getBigDecimal("min_qty").setScale(3).toPlainString(),
                        rs.getLong("price_minor"),
                        from,
                        end,
                        status,
                        rs.getBoolean("below_cost")),
                rs.getString("sort_key"));
    }

    Optional<ItemRow> item(UUID id, LocalDate today) {
        return jdbc.sql(SELECT_ITEM + " WHERE i.id = ?")
                .param(id)
                .query((rs, n) -> itemRow(rs, today))
                .optional();
    }

    /** What an item search matches (ADR-039): the product's code and description. */
    static final Search.Fields ITEM_SEARCH =
            Search.Fields.of().names("p.description").codes("p.code");

    /**
     * By product description, then id, or by match rank and then description when {@code query} is given; ended
     * items only when asked for.
     */
    List<Search.Ranked<ItemRow>> items(
            UUID listId,
            UUID productId,
            Search.Query query,
            boolean includeEnded,
            LocalDate today,
            int afterRank,
            String afterKey,
            UUID afterId,
            int limit) {
        Search.Match match = query == null ? null : query.match(ITEM_SEARCH);
        String select = match == null
                ? SELECT_ITEM
                : SELECT_ITEM.replace("SELECT i.id,", "SELECT " + match.rank() + " AS search_rank, i.id,");
        StringBuilder sql = new StringBuilder(select + " WHERE i.price_list_id = :list");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("list", listId);
        if (productId != null) {
            sql.append(" AND i.product_id = :product");
            params.put("product", productId);
        }
        if (match != null) {
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (!includeEnded) {
            sql.append(" AND (i.valid_to IS NULL OR i.valid_to >= :today)");
            params.put("today", Date.valueOf(today));
        }
        String statement;
        if (match == null) {
            if (afterKey != null) {
                sql.append(" AND (lower(p.description), i.id) > (:afterKey, :afterId)");
                params.put("afterKey", afterKey);
                params.put("afterId", afterId);
            }
            statement = sql + " ORDER BY lower(p.description), i.id LIMIT :limit";
        } else {
            String keyset = null;
            if (afterKey != null) {
                keyset = "(search_rank, sort_key, id) > (:afterRank, :afterKey, :afterId)";
                params.put("afterRank", afterRank);
                params.put("afterKey", afterKey);
                params.put("afterId", afterId);
            }
            statement = Search.page(sql.toString(), keyset, "search_rank, sort_key, id");
        }
        params.put("limit", limit);
        return jdbc.sql(statement)
                .params(params)
                .query(Search.ranked((rs, n) -> itemRow(rs, today), match != null))
                .list();
    }

    /** Items of the same list, product, unit and break whose range meets [from, to] (to null: open ended). */
    List<Range> overlapping(UUID listId, UUID productId, UUID unitId, BigDecimal minQty, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT id, valid_from, valid_to FROM retail_price_list_items
                         WHERE price_list_id = :list AND product_id = :product AND unit_id = :unit AND min_qty = :min
                           AND (valid_to IS NULL OR valid_to >= :from)
                           AND (CAST(:to AS date) IS NULL OR valid_from <= CAST(:to AS date))
                         ORDER BY valid_from, id
                        """)
                .param("list", listId)
                .param("product", productId)
                .param("unit", unitId)
                .param("min", minQty)
                .param("from", Date.valueOf(from))
                .param("to", to == null ? null : Date.valueOf(to), java.sql.Types.DATE)
                .query((rs, n) -> {
                    Date to2 = rs.getDate("valid_to");
                    return new Range(
                            rs.getObject("id", UUID.class),
                            rs.getDate("valid_from").toLocalDate(),
                            to2 == null ? null : to2.toLocalDate());
                })
                .list();
    }

    void insertItem(
            UUID id,
            UUID listId,
            UUID productId,
            UUID unitId,
            BigDecimal minQty,
            long priceMinor,
            LocalDate validFrom,
            LocalDate validTo,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_price_list_items (id, tenant_id, price_list_id, product_id, unit_id, min_qty,
                            price_minor, valid_from, valid_to, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        listId,
                        productId,
                        unitId,
                        minQty,
                        priceMinor,
                        Date.valueOf(validFrom),
                        validTo == null ? null : Date.valueOf(validTo),
                        by)
                .update();
    }

    /** Sets the last day of an open item: the only change an item ever takes (a guard trigger enforces it). */
    void closeItem(UUID id, LocalDate validTo) {
        jdbc.sql("UPDATE retail_price_list_items SET valid_to = ? WHERE id = ? AND valid_to IS NULL")
                .params(Date.valueOf(validTo), id)
                .update();
    }

    /** The price a sale line takes, for the sale path. */
    Optional<ListedPrice> price(UUID listId, UUID productId, UUID unitId, BigDecimal qty, LocalDate on) {
        return jdbc.sql("""
                        SELECT price_minor, min_qty FROM retail_price_list_items
                         WHERE price_list_id = ? AND product_id = ? AND unit_id = ? AND min_qty <= ?
                           AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)
                         ORDER BY min_qty DESC, valid_from DESC, id LIMIT 1
                        """)
                .params(listId, productId, unitId, qty, Date.valueOf(on), Date.valueOf(on))
                .query((rs, n) -> new ListedPrice(rs.getLong("price_minor"), rs.getBigDecimal("min_qty")))
                .optional();
    }

    // ---- Customer groups ---------------------------------------------------------------------

    private static final String SELECT_GROUP = """
            SELECT g.id, g.name, g.price_list_id, l.name AS price_list, g.active, g.version,
                   (SELECT count(*) FROM retail_customers c WHERE c.customer_group_id = g.id) AS customers
              FROM retail_customer_groups g LEFT JOIN retail_price_lists l ON l.id = g.price_list_id
            """;

    private static Group group(ResultSet rs, int n) throws SQLException {
        return new Group(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("price_list_id", UUID.class),
                rs.getString("price_list"),
                rs.getBoolean("active"),
                rs.getInt("customers"),
                rs.getInt("version"));
    }

    List<Group> groups() {
        return jdbc.sql(SELECT_GROUP + " ORDER BY lower(g.name), g.id")
                .query(PriceListRepository::group)
                .list();
    }

    Optional<Group> group(UUID id) {
        return jdbc.sql(SELECT_GROUP + " WHERE g.id = ?")
                .param(id)
                .query(PriceListRepository::group)
                .optional();
    }

    void insertGroup(UUID id, String name, UUID priceListId, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_customer_groups (id, tenant_id, name, price_list_id, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(id, name, priceListId, by).update();
    }

    /** Rows changed: 0 when the version moved since it was read. */
    int updateGroup(UUID id, String name, UUID priceListId, boolean active, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE retail_customer_groups SET name = ?, price_list_id = ?, active = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ?
                        """)
                .params(name, priceListId, active, id, expectedVersion)
                .update();
    }
}
