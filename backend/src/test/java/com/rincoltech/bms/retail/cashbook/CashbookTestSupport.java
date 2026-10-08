package com.rincoltech.bms.retail.cashbook;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Calls the cash book API as the development principal and arranges fabricated lists. */
public final class CashbookTestSupport {

    /** The admin column for the cash book (chapter 8): all ten permissions. */
    public static final String CASHBOOK_ADMIN = "retail.cashbook.read,retail.savings.record,retail.savings.overwrite,"
            + "retail.banking.record,retail.expense.record,retail.expense.manage,retail.withdrawal.record,"
            + "retail.advance.create,retail.advance.repay,retail.cashbook.void";

    /** The sales role's cash book column: read, savings record, banking record, expense record. */
    public static final String CASHBOOK_SALES =
            "retail.cashbook.read,retail.savings.record,retail.banking.record,retail.expense.record";

    /** Everything an administrator holds for retail, cash book included. */
    public static final String ALL = RetailTestSupport.ADMIN + "," + CASHBOOK_ADMIN;

    /** The sales role including its sale permissions, as the shop's cashier signs in. */
    public static final String CASHIER = RetailTestSupport.SALES + "," + CASHBOOK_SALES;

    public final RetailTestSupport api;
    public final TestDatabase.Fixture tenant;

    public CashbookTestSupport(TestRestTemplate http, TestDatabase.Fixture tenant) {
        this.tenant = tenant;
        this.api = new RetailTestSupport(http, tenant);
    }

    public static String key() {
        return UUID.randomUUID().toString();
    }

    public static LocalDate daysAgo(int n) {
        return LocalDate.now().minusDays(n);
    }

    public ResponseEntity<JsonNode> post(String path, Object body, String permissions, String branches) {
        return api.postKeyed(path, body, permissions, branches, key());
    }

    public ResponseEntity<JsonNode> post(String path, Object body, String permissions) {
        return post(path, body, permissions, "*");
    }

    public ResponseEntity<JsonNode> get(String path, String permissions) {
        return api.get(path, permissions);
    }

    public ResponseEntity<JsonNode> get(String path, String permissions, String branches) {
        return api.call(HttpMethod.GET, path, null, permissions, branches, Map.of());
    }

    public ResponseEntity<JsonNode> patch(String path, Object body, String permissions, int version) {
        return api.call(HttpMethod.PATCH, path, body, permissions, "*", Map.of("If-Match", "\"" + version + "\""));
    }

    public ResponseEntity<JsonNode> voidIt(String path, String reason, String permissions) {
        return post(path + "/void", Map.of("reason", reason), permissions);
    }

    /** A category with one item; returns {category id, item id}. */
    public UUID[] categoryWithItem(String category, String item, boolean requiresExplanation) {
        UUID c = RetailTestSupport.id(post("/expense-categories", Map.of("name", category), CASHBOOK_ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", item);
        body.put("requires_explanation", requiresExplanation);
        UUID i = RetailTestSupport.id(post("/expense-categories/" + c + "/items", body, CASHBOOK_ADMIN));
        return new UUID[] {c, i};
    }

    public UUID party(String name, String kind) {
        return RetailTestSupport.id(post("/cash-parties", Map.of("name", name, "kind", kind), CASHBOOK_ADMIN));
    }

    public ResponseEntity<JsonNode> expense(UUID branch, UUID category, UUID item, long amount, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("category_id", category);
        body.put("item_id", item);
        body.put("amount_minor", amount);
        if (date != null) {
            body.put("business_date", date.toString());
        }
        return post("/expenses", body, CASHBOOK_ADMIN);
    }

    /** The head office's cash on hand from the ledger, as of the end of the given day. */
    public long cashOnHand(UUID branch, LocalDate asOf) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.branch_id = ? AND a.system_key = 'cash_on_hand' AND e.entry_date <= ?
                        """)
                .params(tenant.tenantId(), branch, java.sql.Date.valueOf(asOf))
                .query(Long.class)
                .single();
    }

    /** Debit less credit of an account in a branch over entries dated in a range. */
    public long accountMovement(String systemKey, UUID branch) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.branch_id = ? AND a.system_key = ?
                        """)
                .params(tenant.tenantId(), branch, systemKey)
                .query(Long.class)
                .single();
    }

    /** The number of audit rows for an action in this tenant. */
    public java.util.List<String> auditPayloads(String action) {
        return TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = ?")
                .params(tenant.tenantId(), action)
                .query(String.class)
                .list();
    }

    /** A one-line sale at a branch on a day, as an admin, with a fresh key. */
    public ResponseEntity<JsonNode> sale(UUID branch, String method, UUID product, String qty, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        body.put("sale_date", date.toString());
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", java.util.List.of(Map.of("product_id", product, "qty", qty)));
        return post("/sales", body, ALL);
    }

    public ResponseEntity<JsonNode> pay(UUID sale, long amount, String method, LocalDate paidOn) {
        return post(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", method, "paid_on", paidOn.toString()),
                ALL);
    }

    /** A restock of one product into one branch, paid by the given method. */
    public ResponseEntity<JsonNode> restock(
            UUID branch, UUID product, long cost, long sell, String qty, String method, LocalDate date) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", cost);
        line.put("sell_minor", sell);
        line.put("qty_by_branch", java.util.List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", date.toString());
        body.put("payment_method", method);
        body.put("lines", java.util.List.of(line));
        return post("/purchases", body, ALL);
    }

    /** Sets a tenant setting key of the cash book directly, as the owner. */
    public void setting(String key, long value) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (gen_random_uuid(), ?, jsonb_build_object(?::text, ?::bigint))
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = tenant_settings.settings || jsonb_build_object(?::text, ?::bigint)
                        """)
                .params(tenant.tenantId(), key, value, key, value)
                .update();
    }
}
