package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.reporting.DataExportSection;
import com.rincoltech.bms.core.reporting.DataExportSheets;
import com.rincoltech.bms.core.reporting.DataExportSheets.DatedRows;
import com.rincoltech.bms.core.reporting.DataExportSheets.Rows;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The retail sheets of "Download my data" (issue #334 step 1, ADR-074), laid out like the spreadsheet a shop kept before
 * BMS: Products, Suppliers, Customers, Credit buyers with what each owes, Stock balances, then the dated tables in date
 * order: Sales and Sale lines, Sale payments, Account payments, Restocks (with the branch or branches each line was stocked
 * into, and the quantity of each when it was split), Goods receipts and the Cash book. Every branch;
 * a row a person voided or reversed stays, with its status. Read in the run's snapshot, so the sheets agree with each
 * other.
 */
@Component
class RetailDataExport implements DataExportSection {

    private static final Column BRANCH = new Column("branch", "Branch", Kind.TEXT);
    private static final Column CODE = new Column("product_code", "Product code", Kind.TEXT);
    private static final Column PRODUCT = new Column("product", "Product", Kind.TEXT);
    private static final Column QUANTITY = new Column("quantity", "Quantity", Kind.QUANTITY);

    /**
     * The columns left out of the weekly copy sent by email (ADR-074 decision 6): a customer's or supplier's phone, email or
     * other contact. They stay in the copy downloaded after signing in.
     */
    private static final Set<String> PERSONAL = Set.of("contact");

    /** What a credit sale still owes: its unpaid total and a delivery fee left on the account (the credit pages' rule). */
    private static final String OWED = "(s.total_minor - s.paid_minor + coalesce((SELECT o.delivery_fee_minor"
            + " FROM retail_customer_orders o WHERE o.tenant_id = s.tenant_id AND o.sale_id = s.id"
            + " AND o.fee_method = 'account' AND o.fee_paid_at IS NULL), 0))";

    /**
     * The Restocks sheet. The Branch column reads the line's purchase movements by their purchase ({@code source_id}) as well
     * as their line, so the {@code retail_stock_movements_source} index finds them; by the line alone it scanned every
     * purchase movement of the business once per line ({@code RetailDataExportPlanIT}).
     */
    static final String RESTOCKS = """
            SELECT pu.purchased_on, pu.purchase_no,
                   (SELECT string_agg(CASE WHEN x.branches = 1 THEN x.name
                                           ELSE x.name || ' (' || trim_scale(x.qty)::text || ')' END,
                                      ', ' ORDER BY x.code)
                      FROM (SELECT b.name, b.code, sum(m.qty) AS qty, count(*) OVER () AS branches
                              FROM retail_stock_movements m
                              JOIN branches b ON b.tenant_id = m.tenant_id AND b.id = m.branch_id
                             WHERE m.tenant_id = l.tenant_id AND m.source_type = 'retail.purchase'
                               AND m.source_id = l.purchase_id AND m.kind = 'purchase' AND m.source_line_id = l.id
                             GROUP BY b.name, b.code) x) AS branch,
                   sup.name AS supplier, pu.payment_method, l.line_no, p.code,
                   p.description, coalesce(l.unit_qty_total, l.qty_total) AS qty,
                   coalesce(l.unit_cost_minor, l.cost_minor) AS cost, l.sell_minor, l.line_total_minor
              FROM retail_purchase_lines l
              JOIN retail_purchases pu ON pu.tenant_id = l.tenant_id AND pu.id = l.purchase_id
              JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
              LEFT JOIN retail_suppliers sup ON sup.tenant_id = pu.tenant_id AND sup.id = pu.supplier_id
             ORDER BY pu.purchased_on, pu.created_at, pu.id, l.line_no
            """;

    /** Rows fetched from the server at a time: a table of any length is never held whole (ADR-038). */
    static final int FETCH = 2000;

    private final JdbcClient jdbc;
    private final JdbcTemplate streaming;

    RetailDataExport(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        // Joins the run's snapshot transaction like any read; the fetch size makes the driver hand rows over in blocks.
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(FETCH);
    }

    @Override
    public String module() {
        return "retail";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void write(DataExportSheets sheets) {
        products(sheets);
        suppliers(sheets);
        customers(sheets);
        creditBuyers(sheets);
        stock(sheets);
        sheets.checkpoint();
        sales(sheets);
        saleLines(sheets);
        salePayments(sheets);
        accountPayments(sheets);
        restocks(sheets);
        receipts(sheets);
        cashBook(sheets);
    }

    private interface Row {
        void write(ResultSet rs) throws SQLException;
    }

    /** Writes each row as the server returns it, {@link #FETCH} at a time; nothing is collected. */
    private void each(String sql, Row row) {
        streaming.query(sql, (RowCallbackHandler) row::write);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String yesNo(boolean b) {
        return b ? "Yes" : "No";
    }

    private static Long money(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Long.class);
    }

    private static LocalDate day(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private void products(DataExportSheets sheets) {
        Rows rows = sheets.table(
                "products",
                "Products",
                List.of(
                        CODE,
                        PRODUCT,
                        new Column("category", "Category", Kind.TEXT),
                        new Column("unit", "Unit", Kind.TEXT),
                        new Column("size", "Size", Kind.TEXT),
                        new Column("colour", "Colour", Kind.TEXT),
                        new Column("cost_price", "Buying price", Kind.MONEY),
                        new Column("selling_price", "Selling price", Kind.MONEY),
                        new Column("active", "Active", Kind.TEXT),
                        new Column("added_on", "Added on", Kind.DATE)));
        each(
                """
                SELECT p.code, p.description, c.name AS category, u.name AS unit, p.variant_size, p.variant_colour,
                       p.cost_minor, p.sell_minor, p.active, p.created_at
                  FROM retail_products p
                  LEFT JOIN retail_categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                  LEFT JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
                 ORDER BY p.code, p.id
                """,
                rs -> rows.row(
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("category"),
                        rs.getString("unit"),
                        rs.getString("variant_size"),
                        rs.getString("variant_colour"),
                        money(rs, "cost_minor"),
                        money(rs, "sell_minor"),
                        yesNo(rs.getBoolean("active")),
                        sheets.date(rs.getTimestamp("created_at"))));
    }

    private void suppliers(DataExportSheets sheets) {
        Rows rows = sheets.table(
                "suppliers",
                "Suppliers",
                List.of(
                        new Column("name", "Name", Kind.TEXT),
                        new Column("contact", "Contact", Kind.TEXT),
                        new Column("active", "Active", Kind.TEXT),
                        new Column("added_on", "Added on", Kind.DATE)),
                PERSONAL);
        each(
                "SELECT name, contact, active, created_at FROM retail_suppliers ORDER BY name, id",
                rs -> rows.row(
                        rs.getString("name"),
                        rs.getString("contact"),
                        yesNo(rs.getBoolean("active")),
                        sheets.date(rs.getTimestamp("created_at"))));
    }

    private void customers(DataExportSheets sheets) {
        Rows rows = sheets.table(
                "customers",
                "Customers",
                List.of(
                        new Column("name", "Name", Kind.TEXT),
                        new Column("contact", "Contact", Kind.TEXT),
                        new Column("group", "Group", Kind.TEXT),
                        new Column("credit_limit", "Credit limit", Kind.MONEY),
                        new Column("terms_days", "Days to pay", Kind.COUNT),
                        new Column("added_on", "Added on", Kind.DATE)),
                PERSONAL);
        each(
                """
                SELECT c.name, c.contact, g.name AS group_name, c.credit_limit_minor, c.terms_days, c.created_at
                  FROM retail_customers c
                  LEFT JOIN retail_customer_groups g ON g.tenant_id = c.tenant_id AND g.id = c.customer_group_id
                 ORDER BY c.name, c.id
                """,
                rs -> rows.row(
                        rs.getString("name"),
                        rs.getString("contact"),
                        rs.getString("group_name"),
                        money(rs, "credit_limit_minor"),
                        rs.getObject("terms_days", Integer.class),
                        sheets.date(rs.getTimestamp("created_at"))));
    }

    private void creditBuyers(DataExportSheets sheets) {
        Rows rows = sheets.table(
                "credit_buyers",
                "Credit buyers",
                List.of(
                        new Column("name", "Name", Kind.TEXT),
                        new Column("contact", "Contact", Kind.TEXT),
                        new Column("open_sales", "Unpaid credit sales", Kind.COUNT),
                        new Column("owed", "Owed", Kind.MONEY),
                        new Column("oldest_due", "Oldest due date", Kind.DATE),
                        new Column("credit_limit", "Credit limit", Kind.MONEY)),
                PERSONAL);
        each(
                "SELECT c.name, c.contact, count(s.id) AS open_sales, coalesce(sum(" + OWED + "), 0) AS owed,"
                        + " min(s.due_date) AS oldest_due, c.credit_limit_minor"
                        + " FROM retail_customers c"
                        + " LEFT JOIN retail_sales s ON s.tenant_id = c.tenant_id AND s.customer_id = c.id"
                        + " AND s.payment_method = 'credit' AND s.status = 'completed' AND " + OWED + " > 0"
                        + " GROUP BY c.id, c.name, c.contact, c.credit_limit_minor"
                        + " ORDER BY owed DESC, c.name, c.id",
                rs -> rows.row(
                        rs.getString("name"),
                        rs.getString("contact"),
                        rs.getLong("open_sales"),
                        rs.getLong("owed"),
                        day(rs, "oldest_due"),
                        money(rs, "credit_limit_minor")));
    }

    private void stock(DataExportSheets sheets) {
        Rows rows = sheets.table(
                "stock_balances",
                "Stock balances",
                List.of(BRANCH, CODE, PRODUCT, QUANTITY, new Column("reserved", "Kept for orders", Kind.QUANTITY)));
        each(
                """
                SELECT b.name AS branch, p.code, p.description, sb.qty, sb.reserved_qty
                  FROM retail_stock_balances sb
                  JOIN branches b ON b.tenant_id = sb.tenant_id AND b.id = sb.branch_id
                  JOIN retail_products p ON p.tenant_id = sb.tenant_id AND p.id = sb.product_id
                 ORDER BY b.code, p.code, p.id
                """,
                rs -> rows.row(
                        rs.getString("branch"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("reserved_qty")));
    }

    private void sales(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "sales",
                "Sales",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("time", "Time", Kind.TEXT),
                        new Column("sale_no", "Sale no", Kind.TEXT),
                        BRANCH,
                        new Column("payment", "Payment", Kind.TEXT),
                        new Column("buyer", "Buyer", Kind.TEXT),
                        new Column("total", "Total", Kind.MONEY),
                        new Column("paid", "Paid", Kind.MONEY),
                        new Column("due_date", "Due date", Kind.DATE),
                        new Column("status", "Status", Kind.TEXT),
                        new Column("sold_by", "Sold by", Kind.TEXT),
                        new Column("void_reason", "Void reason", Kind.TEXT)),
                count("SELECT count(*) FROM retail_sales"));
        each(
                """
                SELECT s.sale_date, s.created_at, s.sale_no, b.name AS branch, s.payment_method,
                       coalesce(c.name, s.buyer_name) AS buyer, s.total_minor, s.paid_minor, s.due_date, s.status,
                       u.full_name AS sold_by, s.void_reason
                  FROM retail_sales s
                  JOIN branches b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id
                  LEFT JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
                  LEFT JOIN users u ON u.tenant_id = s.tenant_id AND u.id = s.created_by
                 ORDER BY s.sale_date, s.created_at, s.id
                """,
                rs -> rows.row(
                        day(rs, "sale_date"),
                        day(rs, "sale_date"),
                        sheets.time(rs.getTimestamp("created_at")),
                        rs.getString("sale_no"),
                        rs.getString("branch"),
                        rs.getString("payment_method"),
                        rs.getString("buyer"),
                        money(rs, "total_minor"),
                        money(rs, "paid_minor"),
                        day(rs, "due_date"),
                        rs.getString("status"),
                        rs.getString("sold_by"),
                        rs.getString("void_reason")));
    }

    private void saleLines(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "sale_lines",
                "Sale lines",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("sale_no", "Sale no", Kind.TEXT),
                        BRANCH,
                        new Column("line", "Line", Kind.COUNT),
                        CODE,
                        PRODUCT,
                        QUANTITY,
                        new Column("unit", "Unit", Kind.TEXT),
                        new Column("unit_price", "Selling price", Kind.MONEY),
                        new Column("line_total", "Line total", Kind.MONEY),
                        new Column("sale_status", "Sale status", Kind.TEXT)),
                count("SELECT count(*) FROM retail_sale_lines"));
        each(
                """
                SELECT s.sale_date, s.sale_no, b.name AS branch, l.line_no, p.code, p.description,
                       coalesce(l.sale_qty, l.qty) AS qty, coalesce(su.name, pu.name) AS unit,
                       coalesce(l.sale_unit_price_minor, l.unit_price_minor) AS unit_price, l.line_total_minor, s.status
                  FROM retail_sale_lines l
                  JOIN retail_sales s ON s.tenant_id = l.tenant_id AND s.id = l.sale_id
                  JOIN branches b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id
                  JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                  LEFT JOIN retail_units su ON su.tenant_id = l.tenant_id AND su.id = l.sale_unit_id
                  LEFT JOIN retail_units pu ON pu.tenant_id = p.tenant_id AND pu.id = p.unit_id
                 ORDER BY s.sale_date, s.created_at, s.id, l.line_no
                """,
                rs -> rows.row(
                        day(rs, "sale_date"),
                        day(rs, "sale_date"),
                        rs.getString("sale_no"),
                        rs.getString("branch"),
                        rs.getInt("line_no"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getBigDecimal("qty"),
                        rs.getString("unit"),
                        money(rs, "unit_price"),
                        money(rs, "line_total_minor"),
                        rs.getString("status")));
    }

    private void salePayments(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "sale_payments",
                "Sale payments",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("sale_no", "Sale no", Kind.TEXT),
                        BRANCH,
                        new Column("buyer", "Buyer", Kind.TEXT),
                        new Column("method", "Method", Kind.TEXT),
                        new Column("amount", "Amount", Kind.MONEY)),
                count("SELECT count(*) FROM retail_sale_payments"));
        each(
                """
                SELECT sp.paid_on, s.sale_no, b.name AS branch, coalesce(c.name, s.buyer_name) AS buyer, sp.method,
                       sp.amount_minor
                  FROM retail_sale_payments sp
                  JOIN retail_sales s ON s.tenant_id = sp.tenant_id AND s.id = sp.sale_id
                  JOIN branches b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id
                  LEFT JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
                 ORDER BY sp.paid_on, sp.created_at, sp.id
                """,
                rs -> rows.row(
                        day(rs, "paid_on"),
                        day(rs, "paid_on"),
                        rs.getString("sale_no"),
                        rs.getString("branch"),
                        rs.getString("buyer"),
                        rs.getString("method"),
                        money(rs, "amount_minor")));
    }

    private void accountPayments(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "account_payments",
                "Account payments",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("receipt_no", "Receipt no", Kind.TEXT),
                        new Column("buyer", "Buyer", Kind.TEXT),
                        BRANCH,
                        new Column("method", "Method", Kind.TEXT),
                        new Column("amount", "Amount", Kind.MONEY),
                        new Column("status", "Status", Kind.TEXT)),
                count("SELECT count(*) FROM retail_account_payments"));
        each(
                """
                SELECT ap.paid_on, ap.receipt_no, c.name AS buyer, b.name AS branch, ap.method, ap.amount_minor, ap.status
                  FROM retail_account_payments ap
                  JOIN retail_customers c ON c.tenant_id = ap.tenant_id AND c.id = ap.customer_id
                  LEFT JOIN branches b ON b.tenant_id = ap.tenant_id AND b.id = ap.branch_id
                 ORDER BY ap.paid_on, ap.created_at, ap.id
                """,
                rs -> rows.row(
                        day(rs, "paid_on"),
                        day(rs, "paid_on"),
                        rs.getString("receipt_no"),
                        rs.getString("buyer"),
                        rs.getString("branch"),
                        rs.getString("method"),
                        money(rs, "amount_minor"),
                        rs.getString("status")));
    }

    private void restocks(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "restocks",
                "Restocks",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("restock_no", "Restock no", Kind.TEXT),
                        BRANCH,
                        new Column("supplier", "Supplier", Kind.TEXT),
                        new Column("payment", "Payment", Kind.TEXT),
                        new Column("line", "Line", Kind.COUNT),
                        CODE,
                        PRODUCT,
                        QUANTITY,
                        new Column("cost_price", "Buying price", Kind.MONEY),
                        new Column("selling_price", "Selling price", Kind.MONEY),
                        new Column("line_total", "Line total", Kind.MONEY)),
                count("SELECT count(*) FROM retail_purchase_lines"));
        each(
                RESTOCKS,
                rs -> rows.row(
                        day(rs, "purchased_on"),
                        day(rs, "purchased_on"),
                        rs.getString("purchase_no"),
                        rs.getString("branch"),
                        rs.getString("supplier"),
                        rs.getString("payment_method"),
                        rs.getInt("line_no"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getBigDecimal("qty"),
                        money(rs, "cost"),
                        money(rs, "sell_minor"),
                        money(rs, "line_total_minor")));
    }

    private void receipts(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "goods_receipts",
                "Goods receipts",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("receipt_no", "Receipt no", Kind.TEXT),
                        BRANCH,
                        new Column("supplier", "Supplier", Kind.TEXT),
                        new Column("line", "Line", Kind.COUNT),
                        CODE,
                        PRODUCT,
                        QUANTITY,
                        new Column("cost_price", "Buying price", Kind.MONEY),
                        new Column("line_total", "Line total", Kind.MONEY),
                        new Column("status", "Status", Kind.TEXT)),
                count("SELECT count(*) FROM retail_goods_receipt_lines"));
        each(
                """
                SELECT r.received_on, r.receipt_no, b.name AS branch, sup.name AS supplier, l.line_no, p.code,
                       p.description, coalesce(l.unit_qty, l.qty) AS qty, coalesce(l.entered_cost_minor, l.cost_minor) AS cost,
                       l.line_total_minor, r.status
                  FROM retail_goods_receipt_lines l
                  JOIN retail_goods_receipts r ON r.tenant_id = l.tenant_id AND r.id = l.receipt_id
                  JOIN branches b ON b.tenant_id = r.tenant_id AND b.id = r.branch_id
                  JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                  LEFT JOIN retail_suppliers sup ON sup.tenant_id = r.tenant_id AND sup.id = r.supplier_id
                 ORDER BY r.received_on, r.created_at, r.id, l.line_no
                """,
                rs -> rows.row(
                        day(rs, "received_on"),
                        day(rs, "received_on"),
                        rs.getString("receipt_no"),
                        rs.getString("branch"),
                        rs.getString("supplier"),
                        rs.getInt("line_no"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getBigDecimal("qty"),
                        money(rs, "cost"),
                        money(rs, "line_total_minor"),
                        rs.getString("status")));
    }

    /** Every entry of the cash book, in one table as the cash book page lists them. */
    private static final String CASH_BOOK = """
            SELECT tenant_id, business_date AS d, created_at AS at, branch_id, 'Savings set aside' AS entry, NULL AS detail,
                   amount_minor, voided_at, recorded_by FROM retail_daily_savings
            UNION ALL
            SELECT tenant_id, business_date, banked_at, branch_id, 'Banked', reference, amount_minor, voided_at, recorded_by
              FROM retail_cash_bankings
            UNION ALL
            SELECT tenant_id, business_date, withdrawn_at, branch_id, 'Withdrawal', purpose, amount_minor, voided_at,
                   recorded_by
              FROM retail_cash_withdrawals
            UNION ALL
            SELECT tenant_id, business_date, occurred_at, branch_id, 'Expense',
                   concat_ws(': ', category_name, item_name, explanation), amount_minor, voided_at, recorded_by
              FROM retail_expenses
            UNION ALL
            SELECT a.tenant_id, a.business_date, a.occurred_at, a.branch_id, 'Owner advance',
                   concat_ws(': ', cp.name, a.purpose), a.principal_minor, a.voided_at, a.recorded_by
              FROM retail_advances a
              LEFT JOIN retail_cash_parties cp ON cp.tenant_id = a.tenant_id AND cp.id = a.party_id
            UNION ALL
            SELECT r.tenant_id, r.paid_on, r.created_at, r.branch_id, 'Advance repaid', r.method, r.amount_minor,
                   r.voided_at, r.recorded_by
              FROM retail_advance_repayments r
            """;

    private void cashBook(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "cash_book",
                "Cash book",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("time", "Time", Kind.TEXT),
                        BRANCH,
                        new Column("entry", "Entry", Kind.TEXT),
                        new Column("detail", "Detail", Kind.TEXT),
                        new Column("amount", "Amount", Kind.MONEY),
                        new Column("voided", "Voided", Kind.TEXT),
                        new Column("recorded_by", "Recorded by", Kind.TEXT)),
                count("SELECT count(*) FROM (" + CASH_BOOK + ") x"));
        each(
                "SELECT x.d, x.at, b.name AS branch, x.entry, x.detail, x.amount_minor, x.voided_at, u.full_name"
                        + " FROM (" + CASH_BOOK + ") x"
                        + " JOIN branches b ON b.tenant_id = x.tenant_id AND b.id = x.branch_id"
                        + " LEFT JOIN users u ON u.tenant_id = x.tenant_id AND u.id = x.recorded_by"
                        + " ORDER BY x.d, x.at, x.entry",
                rs -> rows.row(
                        day(rs, "d"),
                        day(rs, "d"),
                        sheets.time(rs.getTimestamp("at")),
                        rs.getString("branch"),
                        rs.getString("entry"),
                        rs.getString("detail"),
                        money(rs, "amount_minor"),
                        yesNo(rs.getTimestamp("voided_at") != null),
                        rs.getString("full_name")));
    }
}
