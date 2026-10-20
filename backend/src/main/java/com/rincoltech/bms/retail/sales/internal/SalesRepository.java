package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.LinePrice;
import com.rincoltech.bms.retail.sales.internal.SalesApi.OpenSale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Payment;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLine;
import com.rincoltech.bms.retail.sales.internal.SalesApi.WriteOffInfo;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for sales and credit buyers. Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class SalesRepository {

    private static final String SELECT_SALE = """
            SELECT id, sale_no, branch_id, sale_date, payment_method, customer_id, buyer_name, buyer_contact,
                   due_date, status, currency, total_minor, paid_minor, cost_total_minor, historical, created_at,
                   created_by, voided_at, voided_by, void_reason, version, sale_entry_id, cost_entry_id, price_list_id,
                   (SELECT l.name FROM retail_price_lists l WHERE l.id = retail_sales.price_list_id) AS price_list,
                   (SELECT w.written_off_on FROM retail_sale_writeoffs w WHERE w.sale_id = retail_sales.id) AS written_off_on,
                   (SELECT w.unpaid_minor FROM retail_sale_writeoffs w WHERE w.sale_id = retail_sales.id) AS written_off_unpaid_minor,
                   (SELECT w.reason FROM retail_sale_writeoffs w WHERE w.sale_id = retail_sales.id) AS written_off_reason
              FROM retail_sales
            """;

    private final JdbcClient jdbc;

    SalesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A sale header with its journal entries, for the service. */
    /** A listed buyer with the key the list is ordered by, as the database computed it (for the cursor). */
    record CustomerRow(Customer customer, String sortKey, int rank) {}

    record Header(Sale sale, UUID saleEntryId, UUID costEntryId) {}

    /** A line to insert. */
    record NewLine(
            UUID id,
            int lineNo,
            UUID productId,
            BigDecimal qty,
            long unitPriceMinor,
            long unitCostMinor,
            long lineTotalMinor,
            long lineCostMinor,
            UUID saleUnitId,
            BigDecimal saleQty,
            Long saleUnitPriceMinor,
            BigDecimal unitFactor,
            LinePricing pricing) {}

    /** How the price of a line was chosen (price_lists capability); null on a line made without it. */
    record LinePricing(
            String source,
            UUID priceListId,
            BigDecimal breakMinQty,
            long resolvedUnitPriceMinor,
            String overrideReason) {}

    /** The group and the own price list of a credit buyer. */
    record CustomerPricing(UUID customerGroupId, UUID priceListId) {}

    // ---- Sales ---------------------------------------------------------------------------

    Optional<Header> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT_SALE + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> new Header(
                        header(rs),
                        rs.getObject("sale_entry_id", UUID.class),
                        rs.getObject("cost_entry_id", UUID.class)))
                .optional()
                .map(h -> new Header(withLines(h.sale()), h.saleEntryId(), h.costEntryId()));
    }

    List<Sale> page(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            Search.Query buyer,
            String status,
            String owing,
            LocalDate today,
            UUID sellerId,
            UUID categoryId,
            boolean newestFirst,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_SALE + " WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        appendFilters(
                sql,
                params,
                branchIds,
                from,
                to,
                customerId,
                paymentMethod,
                productId,
                buyer,
                status,
                owing,
                today,
                sellerId,
                categoryId);
        if (afterCreated != null) {
            sql.append(newestFirst ? " AND (created_at, id) < " : " AND (created_at, id) > ")
                    .append("(:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(newestFirst ? " ORDER BY created_at DESC, id DESC" : " ORDER BY created_at, id")
                .append(" LIMIT :limit");
        params.put("limit", limit);
        return withLines(jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> header(rs))
                .list());
    }

    /**
     * The filters the sales list and the sales summary share (issue #328): one definition, so a grouped figure and the
     * list under it always agree. An empty {@code branchIds} must be answered by the caller (no rows).
     */
    static void appendFilters(
            StringBuilder sql,
            Map<String, Object> params,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            String paymentMethod,
            UUID productId,
            Search.Query buyer,
            String status,
            String owing,
            LocalDate today,
            UUID sellerId,
            UUID categoryId) {
        if (branchIds != null) {
            sql.append(" AND retail_sales.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (from != null) {
            sql.append(" AND retail_sales.sale_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND retail_sales.sale_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        if (customerId != null) {
            sql.append(" AND retail_sales.customer_id = :customerId");
            params.put("customerId", customerId);
        }
        if (paymentMethod != null) {
            sql.append(" AND retail_sales.payment_method = :paymentMethod");
            params.put("paymentMethod", paymentMethod);
        }
        if (productId != null) {
            // Issue #384: the id of an item with sizes and colours finds the sales of any of its variants.
            sql.append(" AND EXISTS (SELECT 1 FROM retail_sale_lines l WHERE l.sale_id = retail_sales.id AND "
                    + SalesSummaryRepository.productOrItem("l.product_id", "productId") + ")");
            params.put("productId", productId);
        }
        if (sellerId != null) {
            sql.append(" AND retail_sales.created_by = :sellerId");
            params.put("sellerId", sellerId);
        }
        if (categoryId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM retail_sale_lines cl JOIN retail_products cp"
                    + " ON cp.tenant_id = cl.tenant_id AND cp.id = cl.product_id"
                    + " WHERE cl.sale_id = retail_sales.id AND cp.category_id = :categoryId)");
            params.put("categoryId", categoryId);
        }
        if (buyer != null) {
            Search.Match match = buyer.match(BUYER_SEARCH);
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        if (status != null) {
            sql.append(" AND retail_sales.status = :status");
            params.put("status", status);
        }
        if (owing != null) {
            sql.append(" AND retail_sales.payment_method = 'credit' AND retail_sales.status = 'completed'");
            switch (owing) {
                case "owing" -> sql.append(" AND retail_sales.total_minor > retail_sales.paid_minor");
                case "overdue" -> {
                    sql.append(
                            " AND retail_sales.total_minor > retail_sales.paid_minor AND retail_sales.due_date < :today");
                    params.put("today", Date.valueOf(today));
                }
                default -> sql.append(" AND retail_sales.total_minor <= retail_sales.paid_minor");
            }
        }
    }

    void insert(Sale s) {
        jdbc.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            buyer_name, buyer_contact, due_date, currency, total_minor, cost_total_minor, paid_minor, status,
                            created_by, price_list_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branch, :no, :date, :method, :customer,
                            :buyerName, :buyerContact, :due, :currency, :total, :cost, :paid, 'completed', :by, :priceList)
                        """)
                .param("id", s.id())
                .param("branch", s.branchId())
                .param("no", s.saleNo())
                .param("date", Date.valueOf(s.saleDate()))
                .param("method", s.paymentMethod())
                .param("customer", s.customerId())
                .param("buyerName", s.buyerName())
                .param("buyerContact", s.buyerContact())
                .param("due", s.dueDate() == null ? null : Date.valueOf(s.dueDate()))
                .param("currency", s.currency())
                .param("total", s.totalMinor())
                .param("cost", s.costTotalMinor())
                .param("paid", s.paidMinor())
                .param("by", s.createdBy())
                .param("priceList", s.priceListId())
                .update();
    }

    void insertLine(UUID saleId, NewLine l) {
        jdbc.sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor, sale_unit_id, sale_qty,
                            sale_unit_price_minor, unit_factor, price_source, price_list_id, price_break_min_qty,
                            resolved_unit_price_minor, price_override_reason)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        l.id(),
                        saleId,
                        l.lineNo(),
                        l.productId(),
                        l.qty(),
                        l.unitPriceMinor(),
                        l.unitCostMinor(),
                        l.lineTotalMinor(),
                        l.lineCostMinor(),
                        l.saleUnitId(),
                        l.saleQty(),
                        l.saleUnitPriceMinor(),
                        l.unitFactor(),
                        l.pricing() == null ? null : l.pricing().source(),
                        l.pricing() == null ? null : l.pricing().priceListId(),
                        l.pricing() == null ? null : l.pricing().breakMinQty(),
                        l.pricing() == null ? null : l.pricing().resolvedUnitPriceMinor(),
                        l.pricing() == null ? null : l.pricing().overrideReason())
                .update();
    }

    void setEntries(UUID saleId, UUID saleEntryId, UUID costEntryId) {
        jdbc.sql("UPDATE retail_sales SET sale_entry_id = ?, cost_entry_id = ? WHERE id = ?")
                .params(saleEntryId, costEntryId, saleId)
                .update();
    }

    void markVoided(UUID saleId, UUID by, String reason) {
        jdbc.sql("""
                        UPDATE retail_sales SET status = 'voided', voided_at = now(), voided_by = ?, void_reason = ?,
                               updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(by, reason, saleId).update();
    }

    private Sale withLines(Sale s) {
        return withLines(List.of(s)).getFirst();
    }

    /** The lines of every sale of a page in one statement, not one per sale (issue #107). */
    private List<Sale> withLines(List<Sale> sales) {
        if (sales.isEmpty()) {
            return sales;
        }
        Map<UUID, List<SaleLine>> bySale = new HashMap<>();
        jdbc.sql("""
                        SELECT l.sale_id, l.id, l.line_no, l.product_id, p.code, p.description, l.qty, l.unit_price_minor,
                               l.line_total_minor, l.unit_cost_minor, l.line_cost_minor, l.sale_unit_id, su.name AS sale_unit,
                               l.sale_qty, l.sale_unit_price_minor, l.unit_factor, l.price_source, l.price_list_id,
                               pl.name AS price_list, l.price_break_min_qty, l.resolved_unit_price_minor,
                               l.price_override_reason
                          FROM retail_sale_lines l JOIN retail_products p ON p.id = l.product_id
                          LEFT JOIN retail_units su ON su.id = l.sale_unit_id
                          LEFT JOIN retail_price_lists pl ON pl.id = l.price_list_id
                         WHERE l.sale_id IN (:ids) ORDER BY l.sale_id, l.line_no
                        """).param("ids", sales.stream().map(Sale::id).toList()).query(rs -> {
            bySale.computeIfAbsent(rs.getObject("sale_id", UUID.class), k -> new ArrayList<>())
                    .add(new SaleLine(
                            rs.getObject("id", UUID.class),
                            rs.getInt("line_no"),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("description"),
                            Quantities.format(rs.getBigDecimal("qty")),
                            rs.getLong("unit_price_minor"),
                            rs.getLong("line_total_minor"),
                            rs.getLong("unit_cost_minor"),
                            rs.getLong("line_cost_minor"),
                            rs.getObject("sale_unit_id", UUID.class),
                            rs.getString("sale_unit"),
                            rs.getBigDecimal("sale_qty") == null
                                    ? null
                                    : Quantities.format(rs.getBigDecimal("sale_qty")),
                            rs.getObject("sale_unit_price_minor", Long.class),
                            rs.getBigDecimal("unit_factor") == null
                                    ? null
                                    : Quantities.format(rs.getBigDecimal("unit_factor")),
                            rs.getString("price_source") == null
                                    ? null
                                    : new LinePrice(
                                            rs.getString("price_source"),
                                            rs.getObject("price_list_id", UUID.class),
                                            rs.getString("price_list"),
                                            rs.getBigDecimal("price_break_min_qty") == null
                                                    ? null
                                                    : Quantities.format(rs.getBigDecimal("price_break_min_qty")),
                                            rs.getLong("resolved_unit_price_minor"),
                                            rs.getString("price_override_reason"))));
        });
        return sales.stream()
                .map(s -> withLines(s, bySale.getOrDefault(s.id(), new ArrayList<>())))
                .toList();
    }

    private static Sale withLines(Sale s, List<SaleLine> lines) {
        return new Sale(
                s.id(),
                s.saleNo(),
                s.branchId(),
                s.saleDate(),
                s.paymentMethod(),
                s.customerId(),
                s.buyerName(),
                s.buyerContact(),
                s.dueDate(),
                s.status(),
                s.currency(),
                s.totalMinor(),
                s.paidMinor(),
                s.balanceMinor(),
                s.costTotalMinor(),
                s.profitMinor(),
                s.priceListId(),
                s.priceList(),
                lines,
                s.historical(),
                s.createdAt(),
                s.createdBy(),
                s.voidedAt(),
                s.voidedBy(),
                s.voidReason(),
                s.version(),
                s.writeOff(),
                s.countedWhenPaid());
    }

    private static Sale header(ResultSet rs) throws SQLException {
        long total = rs.getLong("total_minor");
        long paid = rs.getLong("paid_minor");
        long cost = rs.getLong("cost_total_minor");
        boolean voided = rs.getString("status").equals("voided");
        Date due = rs.getDate("due_date");
        return new Sale(
                rs.getObject("id", UUID.class),
                rs.getString("sale_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getDate("sale_date").toLocalDate(),
                rs.getString("payment_method"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("buyer_name"),
                rs.getString("buyer_contact"),
                due == null ? null : due.toLocalDate(),
                rs.getString("status"),
                rs.getString("currency"),
                total,
                paid,
                voided ? 0 : total - paid,
                cost,
                total - cost,
                rs.getObject("price_list_id", UUID.class),
                rs.getString("price_list"),
                List.of(),
                rs.getBoolean("historical"),
                instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class),
                instant(rs, "voided_at"),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"),
                rs.getInt("version"),
                rs.getDate("written_off_on") == null
                        ? null
                        : new WriteOffInfo(
                                rs.getDate("written_off_on").toLocalDate(),
                                rs.getLong("written_off_unpaid_minor"),
                                rs.getString("written_off_reason")),
                false);
    }

    // ---- Payments --------------------------------------------------------------------------

    void insertPayment(Payment p) {
        jdbc.sql("""
                        INSERT INTO retail_sale_payments (id, tenant_id, sale_id, amount_minor, currency, method, paid_on,
                            journal_entry_id, created_by, created_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        p.id(),
                        p.saleId(),
                        p.amountMinor(),
                        p.currency(),
                        p.method(),
                        Date.valueOf(p.paidOn()),
                        p.journalEntryId(),
                        p.createdBy(),
                        Timestamp.from(p.createdAt()))
                .update();
    }

    void addPaid(UUID saleId, long amountMinor) {
        jdbc.sql("""
                        UPDATE retail_sales SET paid_minor = paid_minor + ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(amountMinor, saleId).update();
    }

    /**
     * Gives a reversed payment on account back to the sale (V44): the sales guard lets paid_minor fall only while the
     * transaction-local setting {@code bms.reverse_account_payment} holds the id of the reversed receipt that allocated
     * {@code amountMinor} to this sale, and only by exactly that amount. The setting is raised for this one statement and
     * cleared after it; when the statement throws the transaction is aborted and the setting dies with it.
     */
    void releasePaid(UUID saleId, UUID receiptId, long amountMinor) {
        jdbc.sql("SELECT set_config('bms.reverse_account_payment', ?, true)")
                .param(receiptId.toString())
                .query()
                .singleValue();
        jdbc.sql("""
                        UPDATE retail_sales SET paid_minor = paid_minor - ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(amountMinor, saleId).update();
        jdbc.sql("SELECT set_config('bms.reverse_account_payment', '', true)")
                .query()
                .singleValue();
    }

    /**
     * Gives the account credit of a voided return back to the sale (V45): the sales guard lets paid_minor fall only while
     * the transaction-local setting {@code bms.void_sale_return} holds the id of a voided return of this sale that gave
     * exactly {@code amountMinor}. Raised for this one statement and cleared after it, like {@link #releasePaid}.
     */
    void releasePaidForReturn(UUID saleId, UUID returnId, long amountMinor) {
        jdbc.sql("SELECT set_config('bms.void_sale_return', ?, true)")
                .param(returnId.toString())
                .query()
                .singleValue();
        jdbc.sql("""
                        UPDATE retail_sales SET paid_minor = paid_minor - ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(amountMinor, saleId).update();
        jdbc.sql("SELECT set_config('bms.void_sale_return', '', true)").query().singleValue();
    }

    List<Payment> payments(UUID saleId) {
        return jdbc.sql("""
                        SELECT id, sale_id, amount_minor, currency, method, paid_on, journal_entry_id, created_at, created_by
                          FROM retail_sale_payments p
                         WHERE sale_id = ?
                           AND NOT EXISTS (SELECT 1 FROM retail_account_payment_allocations a
                                             JOIN retail_account_payments r ON r.id = a.receipt_id
                                            WHERE a.sale_payment_id = p.id AND r.status = 'reversed')
                         ORDER BY paid_on, created_at, id
                        """)
                .param(saleId)
                .query((rs, n) -> new Payment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sale_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getString("method"),
                        rs.getDate("paid_on").toLocalDate(),
                        rs.getObject("journal_entry_id", UUID.class),
                        instant(rs, "created_at"),
                        rs.getObject("created_by", UUID.class)))
                .list();
    }

    // ---- Customers -----------------------------------------------------------------------

    void insertCustomer(Customer c, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_customers (id, tenant_id, name, contact, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                        """).params(c.id(), c.name(), c.contact(), by).update();
    }

    Optional<Customer> customer(UUID id) {
        return jdbc.sql(
                        "SELECT id, name, contact, created_at, version, customer_group_id, price_list_id, credit_limit_minor, terms_days FROM retail_customers WHERE id = ?")
                .param(id)
                .query(SalesRepository::customer)
                .optional();
    }

    /** Rows changed: 0 when the version moved since it was read (the predicate is the optimistic lock). */
    int updateCustomer(UUID id, String name, String contact, int expectedVersion) {
        return jdbc.sql(
                        "UPDATE retail_customers SET name = ?, contact = ?, updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(name, contact, id, expectedVersion)
                .update();
    }

    /** Rows changed: 0 when the version moved since it was read. */
    int updateCustomerPricing(UUID id, UUID customerGroupId, UUID priceListId, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE retail_customers SET customer_group_id = ?, price_list_id = ?, updated_at = now(),
                               version = version + 1
                         WHERE id = ? AND version = ?
                        """)
                .params(customerGroupId, priceListId, id, expectedVersion)
                .update();
    }

    /** What a credit buyer search matches (ADR-039): the name and the contact, a phone in any written form. */
    static final Search.Fields CUSTOMER_SEARCH =
            Search.Fields.of().names("c.name").phones("c.contact");

    /**
     * What a sales search matches (ADR-039): the buyer's name, the sale number and the buyer's contact,
     * typed on the sale or held by the linked credit buyer. The sales list keeps its time order, so only
     * the condition is used, not the rank.
     */
    static final Search.Fields BUYER_SEARCH = Search.Fields.of()
            .names("retail_sales.buyer_name")
            .codes("retail_sales.sale_no")
            .phones("retail_sales.buyer_contact")
            // The linked credit buyer is reached through an uncorrelated subquery, so it runs once per
            // statement and not once per sale.
            .related(
                    "retail_sales.customer_id",
                    "retail_customers c",
                    "c.id",
                    Search.Fields.of().names("c.name").phones("c.contact"));

    /**
     * The list, each buyer with what they owe on credit sales in the given branches (null: every
     * branch). Ordered by name, or by match rank and then name when {@code query} is given.
     */
    List<CustomerRow> customers(
            Search.Query query,
            int limit,
            List<UUID> branchIds,
            int afterRank,
            com.rincoltech.bms.kernel.Cursor.Key after) {
        if (branchIds != null && branchIds.isEmpty()) {
            return customers(query, limit, List.of(new UUID(0, 0)), afterRank, after);
        }
        Search.Match match = query == null ? null : query.match(CUSTOMER_SEARCH);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.name, c.contact, c.created_at, c.version, c.customer_group_id, c.price_list_id,
                       c.credit_limit_minor, c.terms_days, lower(c.name) AS sort_key,
                       (SELECT coalesce(sum(s.total_minor - s.paid_minor), 0) FROM retail_sales s
                         WHERE s.customer_id = c.id AND s.payment_method = 'credit' AND s.status = 'completed'
                           AND s.paid_minor < s.total_minor""");
        if (branchIds != null) {
            sql.append(" AND s.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        sql.append(") AS owed");
        if (match != null) {
            sql.append(", ").append(match.rank()).append(" AS search_rank");
        }
        sql.append(" FROM retail_customers c WHERE true");
        if (match != null) {
            sql.append(" AND ").append(match.where());
            params.putAll(match.params());
        }
        String statement;
        if (match == null) {
            if (after != null) {
                sql.append(" AND (lower(c.name), c.id) > (:afterName, :afterId)");
                params.put("afterName", after.sortKey());
                params.put("afterId", after.id());
            }
            statement = sql + " ORDER BY lower(c.name), c.id LIMIT :limit";
        } else {
            String keyset = null;
            if (after != null) {
                keyset = "(search_rank, sort_key, id) > (:afterRank, :afterName, :afterId)";
                params.put("afterRank", afterRank);
                params.put("afterName", after.sortKey());
                params.put("afterId", after.id());
            }
            statement = Search.page(sql.toString(), keyset, "search_rank, sort_key, id");
        }
        params.put("limit", limit);
        return jdbc.sql(statement)
                .params(params)
                .query((rs, n) -> new CustomerRow(
                        new Customer(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("contact"),
                                instant(rs, "created_at"),
                                rs.getLong("owed"),
                                rs.getObject("customer_group_id", UUID.class),
                                rs.getObject("price_list_id", UUID.class),
                                rs.getObject("credit_limit_minor", Long.class),
                                rs.getObject("terms_days", Integer.class),
                                rs.getInt("version")),
                        rs.getString("sort_key"),
                        match == null ? 0 : rs.getInt("search_rank")))
                .list();
    }

    /** An open credit sale and the moment it was entered (which, with its date, says how it is counted, ADR-043). */
    record OpenRow(OpenSale sale, java.time.Instant createdAt) {}

    /** Credit sales of a customer that still owe, in the given branches (null: every branch). */
    List<OpenSale> openSales(UUID customerId, List<UUID> branchIds) {
        return openSaleRows(customerId, branchIds).stream().map(OpenRow::sale).toList();
    }

    List<OpenRow> openSaleRows(UUID customerId, List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customer", customerId);
        String branchFilter = "";
        if (branchIds != null) {
            branchFilter = " AND branch_id IN (:branchIds)";
            params.put("branchIds", branchIds);
        }
        return jdbc.sql("""
                        SELECT id, sale_no, branch_id, sale_date, due_date, total_minor, paid_minor, historical, created_at
                          FROM retail_sales
                         WHERE customer_id = :customer AND payment_method = 'credit' AND status = 'completed'
                           AND paid_minor < total_minor""" + branchFilter + " ORDER BY sale_date, created_at, id")
                .params(params)
                .query((rs, n) -> {
                    Date due = rs.getDate("due_date");
                    return new OpenRow(
                            new OpenSale(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("sale_no"),
                                    rs.getObject("branch_id", UUID.class),
                                    rs.getDate("sale_date").toLocalDate(),
                                    due == null ? null : due.toLocalDate(),
                                    rs.getLong("total_minor"),
                                    rs.getLong("paid_minor"),
                                    rs.getLong("total_minor") - rs.getLong("paid_minor"),
                                    rs.getBoolean("historical") ? Boolean.TRUE : null,
                                    null),
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();
    }

    private static Customer customer(ResultSet rs, int n) throws SQLException {
        return new Customer(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("contact"),
                instant(rs, "created_at"),
                null,
                rs.getObject("customer_group_id", UUID.class),
                rs.getObject("price_list_id", UUID.class),
                rs.getObject("credit_limit_minor", Long.class),
                rs.getObject("terms_days", Integer.class),
                rs.getInt("version"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
