package com.rincoltech.bms.retail.cashbook.internal;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL of the cash book tables (chapter 6 section 6.11.5). Row-level security supplies the tenant
 * and inserts take {@code tenant_id} from {@code app.tenant_id}; the branch filters come from the
 * caller's scope. Table and column names in the aggregate helpers are constants of this package,
 * never request input.
 */
@Repository
class CashbookRepository {

    private final JdbcClient jdbc;

    CashbookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    // -------------------------------------------------------------------------------- setup lists

    record CategoryRow(UUID id, String name, UUID expenseAccountId, boolean active, int sortOrder, int version) {}

    record ItemRow(UUID id, UUID categoryId, String name, boolean requiresExplanation, boolean active, int version) {}

    record PartyRow(UUID id, String name, String contact, String kind, boolean active, Instant createdAt) {}

    private CategoryRow category(ResultSet rs) throws SQLException {
        return new CategoryRow(
                uuid(rs, "id"),
                rs.getString("name"),
                uuid(rs, "expense_account_id"),
                rs.getBoolean("active"),
                rs.getInt("sort_order"),
                rs.getInt("version"));
    }

    private ItemRow item(ResultSet rs) throws SQLException {
        return new ItemRow(
                uuid(rs, "id"),
                uuid(rs, "category_id"),
                rs.getString("name"),
                rs.getBoolean("requires_explanation"),
                rs.getBoolean("active"),
                rs.getInt("version"));
    }

    private PartyRow party(ResultSet rs) throws SQLException {
        return new PartyRow(
                uuid(rs, "id"),
                rs.getString("name"),
                rs.getString("contact"),
                rs.getString("kind"),
                rs.getBoolean("active"),
                instant(rs, "created_at"));
    }

    List<CategoryRow> categories(Boolean active) {
        return jdbc.sql("""
                        SELECT id, name, expense_account_id, active, sort_order, version FROM retail_expense_categories
                         WHERE (CAST(:active AS boolean) IS NULL OR active = :active)
                         ORDER BY sort_order, lower(name), id
                        """)
                .param("active", active)
                .query((rs, n) -> category(rs))
                .list();
    }

    Optional<CategoryRow> category(UUID id, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT id, name, expense_account_id, active, sort_order, version FROM retail_expense_categories
                         WHERE id = ?""" + (forUpdate ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> category(rs))
                .optional();
    }

    List<ItemRow> items(Boolean active) {
        return jdbc.sql("""
                        SELECT id, category_id, name, requires_explanation, active, version FROM retail_expense_items
                         WHERE (CAST(:active AS boolean) IS NULL OR active = :active)
                         ORDER BY lower(name), id
                        """).param("active", active).query((rs, n) -> item(rs)).list();
    }

    Optional<ItemRow> item(UUID id, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT id, category_id, name, requires_explanation, active, version FROM retail_expense_items
                         WHERE id = ?""" + (forUpdate ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> item(rs))
                .optional();
    }

    boolean isExpenseAccount(UUID accountId) {
        return jdbc.sql("""
                        SELECT count(*) FROM gl_accounts
                         WHERE id = ? AND account_type = 'expense' AND is_postable AND is_active
                        """).param(accountId).query(Long.class).single() > 0;
    }

    boolean categoryNameTaken(String name, UUID exceptId) {
        return jdbc.sql("""
                        SELECT count(*) FROM retail_expense_categories
                         WHERE lower(name) = lower(?) AND (CAST(? AS uuid) IS NULL OR id <> ?)
                        """).params(name, exceptId, exceptId).query(Long.class).single() > 0;
    }

    boolean itemNameTaken(UUID categoryId, String name, UUID exceptId) {
        return jdbc.sql("""
                        SELECT count(*) FROM retail_expense_items
                         WHERE category_id = ? AND lower(name) = lower(?) AND (CAST(? AS uuid) IS NULL OR id <> ?)
                        """)
                        .params(categoryId, name, exceptId, exceptId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    void insertCategory(UUID id, String name, UUID accountId, int sortOrder, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_expense_categories (id, tenant_id, name, expense_account_id, sort_order, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, name, accountId, sortOrder, by).update();
    }

    void updateCategory(UUID id, String name, UUID accountId, boolean active, int sortOrder) {
        jdbc.sql("""
                        UPDATE retail_expense_categories
                           SET name = ?, expense_account_id = ?, active = ?, sort_order = ?,
                               version = version + 1, updated_at = now()
                         WHERE id = ?
                        """).params(name, accountId, active, sortOrder, id).update();
    }

    void insertItem(UUID id, UUID categoryId, String name, boolean requiresExplanation, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_expense_items (id, tenant_id, category_id, name, requires_explanation, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, categoryId, name, requiresExplanation, by).update();
    }

    void updateItem(UUID id, String name, boolean requiresExplanation, boolean active) {
        jdbc.sql("""
                        UPDATE retail_expense_items
                           SET name = ?, requires_explanation = ?, active = ?, version = version + 1, updated_at = now()
                         WHERE id = ?
                        """).params(name, requiresExplanation, active, id).update();
    }

    List<PartyRow> parties(String query, String kind, Instant afterCreated, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, contact, kind, active, created_at FROM retail_cash_parties WHERE true""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND lower(name) LIKE :q");
            params.put("q", "%" + query.trim().toLowerCase().replace("%", "").replace("_", "") + "%");
        }
        if (kind != null) {
            sql.append(" AND kind = :kind");
            params.put("kind", kind);
        }
        if (afterCreated != null) {
            sql.append(" AND (created_at, id) > (:afterCreated, :afterId)");
            params.put("afterCreated", Timestamp.from(afterCreated));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY created_at, id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> party(rs))
                .list();
    }

    Optional<PartyRow> party(UUID id) {
        return jdbc.sql("SELECT id, name, contact, kind, active, created_at FROM retail_cash_parties WHERE id = ?")
                .param(id)
                .query((rs, n) -> party(rs))
                .optional();
    }

    boolean partyNameTaken(String kind, String name) {
        return jdbc.sql("SELECT count(*) FROM retail_cash_parties WHERE kind = ? AND lower(name) = lower(?)")
                        .params(kind, name)
                        .query(Long.class)
                        .single()
                > 0;
    }

    void insertParty(UUID id, String name, String contact, String kind, UUID by) {
        jdbc.sql("""
                        INSERT INTO retail_cash_parties (id, tenant_id, name, contact, kind, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """).params(id, name, contact, kind, by).update();
    }

    // -------------------------------------------------------------------------------- the records

    /** The void columns every record carries, with the actor's display name. */
    record Void(Instant at, UUID by, String reason) {

        boolean voided() {
            return at != null;
        }
    }

    private static Void voidOf(ResultSet rs) throws SQLException {
        return new Void(instant(rs, "voided_at"), uuid(rs, "voided_by"), rs.getString("void_reason"));
    }

    record SavingsRow(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            long amountMinor,
            String currency,
            Long suggestedMinor,
            boolean overwritten,
            long totalSoldMinor,
            Instant createdAt,
            UUID by,
            String byName,
            UUID journalEntryId,
            boolean historical,
            Void voided) {}

    record BankingRow(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            long amountMinor,
            String currency,
            long expectedMinor,
            Instant bankedAt,
            String reference,
            Instant createdAt,
            UUID by,
            String byName,
            UUID journalEntryId,
            boolean historical,
            Void voided) {}

    record WithdrawalRow(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            long amountMinor,
            String currency,
            Instant withdrawnAt,
            String purpose,
            Instant createdAt,
            UUID by,
            String byName,
            UUID journalEntryId,
            boolean historical,
            Void voided) {}

    record ExpenseRow(
            UUID id,
            UUID branchId,
            LocalDate businessDate,
            UUID categoryId,
            String categoryName,
            UUID itemId,
            String itemName,
            UUID partyId,
            String partyName,
            long amountMinor,
            String currency,
            String explanation,
            UUID receiptDocumentId,
            Instant createdAt,
            UUID by,
            String byName,
            UUID journalEntryId,
            boolean historical,
            Void voided) {}

    record AdvanceRow(
            UUID id,
            String advanceNo,
            UUID branchId,
            LocalDate businessDate,
            UUID partyId,
            String partyName,
            UUID takenByPartyId,
            String takenByName,
            long principalMinor,
            long repaidMinor,
            String currency,
            String purpose,
            String note,
            Instant createdAt,
            UUID by,
            String byName,
            UUID journalEntryId,
            boolean historical,
            Void voided) {}

    record RepaymentRow(
            UUID id,
            UUID advanceId,
            UUID branchId,
            long amountMinor,
            String currency,
            String method,
            LocalDate paidOn,
            Instant createdAt,
            UUID by,
            String byName,
            UUID journalEntryId,
            boolean historical,
            Void voided) {}

    /** The WHERE fragment and parameters shared by the record lists. */
    private static final class Filter {
        final StringBuilder sql = new StringBuilder(" WHERE true");
        final Map<String, Object> params = new LinkedHashMap<>();

        Filter branches(String column, List<UUID> branchIds) {
            if (branchIds != null) {
                sql.append(" AND ").append(column).append(" IN (:branchIds)");
                params.put("branchIds", branchIds.isEmpty() ? List.of(new UUID(0, 0)) : branchIds);
            }
            return this;
        }

        Filter dates(String column, LocalDate from, LocalDate to) {
            if (from != null) {
                sql.append(" AND ").append(column).append(" >= :from");
                params.put("from", Date.valueOf(from));
            }
            if (to != null) {
                sql.append(" AND ").append(column).append(" <= :to");
                params.put("to", Date.valueOf(to));
            }
            return this;
        }

        Filter live(String alias, boolean includeVoided) {
            if (!includeVoided) {
                sql.append(" AND ").append(alias).append(".voided_at IS NULL");
            }
            return this;
        }

        Filter cursor(String alias, Instant afterCreated, UUID afterId) {
            if (afterCreated != null) {
                sql.append(" AND (")
                        .append(alias)
                        .append(".created_at, ")
                        .append(alias)
                        .append(".id) < (:afterCreated, :afterId)");
                params.put("afterCreated", Timestamp.from(afterCreated));
                params.put("afterId", afterId);
            }
            return this;
        }

        Filter eq(String column, String name, Object value) {
            if (value != null) {
                sql.append(" AND ").append(column).append(" = :").append(name);
                params.put(name, value);
            }
            return this;
        }
    }

    private static final String NAME = " LEFT JOIN users u ON u.id = t.recorded_by";

    // ---- savings

    private static final String SAVINGS_SELECT = """
            SELECT t.id, t.branch_id, t.business_date, t.amount_minor, t.currency, t.suggested_minor, t.overwritten,
                   t.total_sold_minor, t.created_at, t.recorded_by, u.full_name AS by_name, t.journal_entry_id,
                   t.historical, t.voided_at, t.voided_by, t.void_reason
              FROM retail_daily_savings t""" + NAME;

    private SavingsRow savings(ResultSet rs) throws SQLException {
        return new SavingsRow(
                uuid(rs, "id"),
                uuid(rs, "branch_id"),
                rs.getDate("business_date").toLocalDate(),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                nullableLong(rs, "suggested_minor"),
                rs.getBoolean("overwritten"),
                rs.getLong("total_sold_minor"),
                instant(rs, "created_at"),
                uuid(rs, "recorded_by"),
                rs.getString("by_name"),
                uuid(rs, "journal_entry_id"),
                rs.getBoolean("historical"),
                voidOf(rs));
    }

    void insertSavings(
            UUID id,
            UUID branch,
            LocalDate date,
            long amount,
            String currency,
            Long suggested,
            boolean overwritten,
            String reason,
            long totalSold,
            Instant occurredAt,
            UUID by,
            UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_daily_savings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                            suggested_minor, overwritten, overwrite_reason, total_sold_minor, occurred_at, recorded_by,
                            journal_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branch, :date, :amount, :currency,
                            :suggested, :overwritten, :reason, :sold, :at, :by, :entry)
                        """)
                .param("id", id)
                .param("branch", branch)
                .param("date", Date.valueOf(date))
                .param("amount", amount)
                .param("currency", currency)
                .param("suggested", suggested)
                .param("overwritten", overwritten)
                .param("reason", reason)
                .param("sold", totalSold)
                .param("at", Timestamp.from(occurredAt))
                .param("by", by)
                .param("entry", entryId)
                .update();
    }

    Optional<SavingsRow> savings(UUID id, boolean forUpdate) {
        return jdbc.sql(SAVINGS_SELECT + " WHERE t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((rs, n) -> savings(rs))
                .optional();
    }

    Optional<UUID> activeSavings(UUID branch, LocalDate date) {
        return jdbc.sql("""
                        SELECT id FROM retail_daily_savings
                         WHERE branch_id = ? AND business_date = ? AND voided_at IS NULL
                        """)
                .params(branch, Date.valueOf(date))
                .query(UUID.class)
                .optional();
    }

    List<SavingsRow> savingsPage(
            List<UUID> branches,
            LocalDate from,
            LocalDate to,
            boolean includeVoided,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .live("t", includeVoided)
                .cursor("t", afterCreated, afterId);
        f.params.put("limit", limit);
        return jdbc.sql(SAVINGS_SELECT + f.sql + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .params(f.params)
                .query((rs, n) -> savings(rs))
                .list();
    }

    // ---- banking

    private static final String BANKING_SELECT = """
            SELECT t.id, t.branch_id, t.business_date, t.amount_minor, t.currency, t.expected_minor, t.banked_at,
                   t.reference, t.created_at, t.recorded_by, u.full_name AS by_name, t.journal_entry_id, t.historical,
                   t.voided_at, t.voided_by, t.void_reason
              FROM retail_cash_bankings t""" + NAME;

    private BankingRow banking(ResultSet rs) throws SQLException {
        return new BankingRow(
                uuid(rs, "id"),
                uuid(rs, "branch_id"),
                rs.getDate("business_date").toLocalDate(),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getLong("expected_minor"),
                instant(rs, "banked_at"),
                rs.getString("reference"),
                instant(rs, "created_at"),
                uuid(rs, "recorded_by"),
                rs.getString("by_name"),
                uuid(rs, "journal_entry_id"),
                rs.getBoolean("historical"),
                voidOf(rs));
    }

    void insertBanking(
            UUID id,
            UUID branch,
            LocalDate date,
            long amount,
            String currency,
            long expected,
            Instant bankedAt,
            String reference,
            UUID by,
            UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_cash_bankings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                            expected_minor, banked_at, reference, recorded_by, journal_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branch, :date, :amount, :currency,
                            :expected, :bankedAt, :reference, :by, :entry)
                        """)
                .param("id", id)
                .param("branch", branch)
                .param("date", Date.valueOf(date))
                .param("amount", amount)
                .param("currency", currency)
                .param("expected", expected)
                .param("bankedAt", Timestamp.from(bankedAt))
                .param("reference", reference)
                .param("by", by)
                .param("entry", entryId)
                .update();
    }

    Optional<BankingRow> banking(UUID id, boolean forUpdate) {
        return jdbc.sql(BANKING_SELECT + " WHERE t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((rs, n) -> banking(rs))
                .optional();
    }

    List<BankingRow> bankingPage(
            List<UUID> branches,
            LocalDate from,
            LocalDate to,
            boolean includeVoided,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .live("t", includeVoided)
                .cursor("t", afterCreated, afterId);
        f.params.put("limit", limit);
        return jdbc.sql(BANKING_SELECT + f.sql + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .params(f.params)
                .query((rs, n) -> banking(rs))
                .list();
    }

    /** Every banking record of the branches in a date range (voided or not), oldest first: the report's entries. */
    List<BankingRow> bankingEntries(List<UUID> branches, LocalDate from, LocalDate to) {
        Filter f = new Filter().branches("t.branch_id", branches).dates("t.business_date", from, to);
        return jdbc.sql(BANKING_SELECT + f.sql + " ORDER BY t.business_date, t.banked_at, t.id")
                .params(f.params)
                .query((rs, n) -> banking(rs))
                .list();
    }

    // ---- withdrawals

    private static final String WITHDRAWAL_SELECT = """
            SELECT t.id, t.branch_id, t.business_date, t.amount_minor, t.currency, t.withdrawn_at, t.purpose,
                   t.created_at, t.recorded_by, u.full_name AS by_name, t.journal_entry_id, t.historical,
                   t.voided_at, t.voided_by, t.void_reason
              FROM retail_cash_withdrawals t""" + NAME;

    private WithdrawalRow withdrawal(ResultSet rs) throws SQLException {
        return new WithdrawalRow(
                uuid(rs, "id"),
                uuid(rs, "branch_id"),
                rs.getDate("business_date").toLocalDate(),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                instant(rs, "withdrawn_at"),
                rs.getString("purpose"),
                instant(rs, "created_at"),
                uuid(rs, "recorded_by"),
                rs.getString("by_name"),
                uuid(rs, "journal_entry_id"),
                rs.getBoolean("historical"),
                voidOf(rs));
    }

    void insertWithdrawal(
            UUID id,
            UUID branch,
            LocalDate date,
            long amount,
            String currency,
            Instant withdrawnAt,
            String purpose,
            UUID by,
            UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_cash_withdrawals (id, tenant_id, branch_id, business_date, amount_minor, currency,
                            withdrawn_at, purpose, recorded_by, journal_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branch, :date, :amount, :currency,
                            :withdrawnAt, :purpose, :by, :entry)
                        """)
                .param("id", id)
                .param("branch", branch)
                .param("date", Date.valueOf(date))
                .param("amount", amount)
                .param("currency", currency)
                .param("withdrawnAt", Timestamp.from(withdrawnAt))
                .param("purpose", purpose)
                .param("by", by)
                .param("entry", entryId)
                .update();
    }

    Optional<WithdrawalRow> withdrawal(UUID id, boolean forUpdate) {
        return jdbc.sql(WITHDRAWAL_SELECT + " WHERE t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((rs, n) -> withdrawal(rs))
                .optional();
    }

    List<WithdrawalRow> withdrawalPage(
            List<UUID> branches,
            LocalDate from,
            LocalDate to,
            boolean includeVoided,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .live("t", includeVoided)
                .cursor("t", afterCreated, afterId);
        f.params.put("limit", limit);
        return jdbc.sql(WITHDRAWAL_SELECT + f.sql + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .params(f.params)
                .query((rs, n) -> withdrawal(rs))
                .list();
    }

    // ---- expenses

    private static final String EXPENSE_SELECT = """
            SELECT t.id, t.branch_id, t.business_date, t.category_id, t.category_name, t.item_id, t.item_name,
                   t.party_id, p.name AS party_name, t.amount_minor, t.currency, t.explanation, t.receipt_document_id,
                   t.created_at, t.recorded_by, u.full_name AS by_name, t.journal_entry_id, t.historical,
                   t.voided_at, t.voided_by, t.void_reason
              FROM retail_expenses t""" + NAME + " LEFT JOIN retail_cash_parties p ON p.id = t.party_id";

    private ExpenseRow expense(ResultSet rs) throws SQLException {
        return new ExpenseRow(
                uuid(rs, "id"),
                uuid(rs, "branch_id"),
                rs.getDate("business_date").toLocalDate(),
                uuid(rs, "category_id"),
                rs.getString("category_name"),
                uuid(rs, "item_id"),
                rs.getString("item_name"),
                uuid(rs, "party_id"),
                rs.getString("party_name"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("explanation"),
                uuid(rs, "receipt_document_id"),
                instant(rs, "created_at"),
                uuid(rs, "recorded_by"),
                rs.getString("by_name"),
                uuid(rs, "journal_entry_id"),
                rs.getBoolean("historical"),
                voidOf(rs));
    }

    void insertExpense(
            UUID id,
            UUID branch,
            LocalDate date,
            UUID categoryId,
            UUID itemId,
            String categoryName,
            String itemName,
            UUID partyId,
            long amount,
            String currency,
            String explanation,
            UUID receipt,
            Instant occurredAt,
            UUID by,
            UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_expenses (id, tenant_id, branch_id, business_date, category_id, item_id,
                            category_name, item_name, party_id, amount_minor, currency, explanation, receipt_document_id,
                            occurred_at, recorded_by, journal_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branch, :date, :category, :item,
                            :categoryName, :itemName, :party, :amount, :currency, :explanation, :receipt,
                            :at, :by, :entry)
                        """)
                .param("id", id)
                .param("branch", branch)
                .param("date", Date.valueOf(date))
                .param("category", categoryId)
                .param("item", itemId)
                .param("categoryName", categoryName)
                .param("itemName", itemName)
                .param("party", partyId)
                .param("amount", amount)
                .param("currency", currency)
                .param("explanation", explanation)
                .param("receipt", receipt)
                .param("at", Timestamp.from(occurredAt))
                .param("by", by)
                .param("entry", entryId)
                .update();
    }

    Optional<ExpenseRow> expense(UUID id, boolean forUpdate) {
        return jdbc.sql(EXPENSE_SELECT + " WHERE t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((rs, n) -> expense(rs))
                .optional();
    }

    List<ExpenseRow> expensePage(
            List<UUID> branches,
            LocalDate from,
            LocalDate to,
            UUID categoryId,
            UUID itemId,
            boolean includeVoided,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .eq("t.category_id", "category", categoryId)
                .eq("t.item_id", "item", itemId)
                .live("t", includeVoided)
                .cursor("t", afterCreated, afterId);
        f.params.put("limit", limit);
        return jdbc.sql(EXPENSE_SELECT + f.sql + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .params(f.params)
                .query((rs, n) -> expense(rs))
                .list();
    }

    // ---- advances and repayments

    private static final String ADVANCE_SELECT = """
            SELECT t.id, t.advance_no, t.branch_id, t.business_date, t.party_id, p.name AS party_name,
                   t.taken_by_party_id, tb.name AS taken_by_name, t.principal_minor, t.repaid_minor, t.currency,
                   t.purpose, t.note, t.created_at, t.recorded_by, u.full_name AS by_name, t.journal_entry_id,
                   t.historical, t.voided_at, t.voided_by, t.void_reason
              FROM retail_advances t""" + NAME + " JOIN retail_cash_parties p ON p.id = t.party_id"
            + " LEFT JOIN retail_cash_parties tb ON tb.id = t.taken_by_party_id";

    private AdvanceRow advance(ResultSet rs) throws SQLException {
        return new AdvanceRow(
                uuid(rs, "id"),
                rs.getString("advance_no"),
                uuid(rs, "branch_id"),
                rs.getDate("business_date").toLocalDate(),
                uuid(rs, "party_id"),
                rs.getString("party_name"),
                uuid(rs, "taken_by_party_id"),
                rs.getString("taken_by_name"),
                rs.getLong("principal_minor"),
                rs.getLong("repaid_minor"),
                rs.getString("currency"),
                rs.getString("purpose"),
                rs.getString("note"),
                instant(rs, "created_at"),
                uuid(rs, "recorded_by"),
                rs.getString("by_name"),
                uuid(rs, "journal_entry_id"),
                rs.getBoolean("historical"),
                voidOf(rs));
    }

    void insertAdvance(
            UUID id,
            String advanceNo,
            UUID branch,
            LocalDate date,
            UUID partyId,
            UUID takenBy,
            long principal,
            String currency,
            String purpose,
            Instant occurredAt,
            UUID by,
            UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_advances (id, tenant_id, advance_no, branch_id, party_id, taken_by_party_id,
                            principal_minor, currency, purpose, business_date, occurred_at, recorded_by, journal_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :no, :branch, :party, :takenBy,
                            :principal, :currency, :purpose, :date, :at, :by, :entry)
                        """)
                .param("id", id)
                .param("no", advanceNo)
                .param("branch", branch)
                .param("party", partyId)
                .param("takenBy", takenBy)
                .param("principal", principal)
                .param("currency", currency)
                .param("purpose", purpose)
                .param("date", Date.valueOf(date))
                .param("at", Timestamp.from(occurredAt))
                .param("by", by)
                .param("entry", entryId)
                .update();
    }

    Optional<AdvanceRow> advance(UUID id, boolean forUpdate) {
        return jdbc.sql(ADVANCE_SELECT + " WHERE t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((rs, n) -> advance(rs))
                .optional();
    }

    List<AdvanceRow> advancePage(
            List<UUID> branches,
            UUID partyId,
            boolean openOnly,
            LocalDate from,
            LocalDate to,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .eq("t.party_id", "party", partyId)
                .cursor("t", afterCreated, afterId);
        if (openOnly) {
            f.sql.append(" AND t.voided_at IS NULL AND t.repaid_minor < t.principal_minor");
        }
        f.params.put("limit", limit);
        return jdbc.sql(ADVANCE_SELECT + f.sql + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit")
                .params(f.params)
                .query((rs, n) -> advance(rs))
                .list();
    }

    /** Non-voided advances with a balance, for the outstanding-by-party report. */
    List<AdvanceRow> openAdvances(List<UUID> branches) {
        Filter f = new Filter().branches("t.branch_id", branches);
        f.sql.append(" AND t.voided_at IS NULL AND t.repaid_minor < t.principal_minor");
        return jdbc.sql(ADVANCE_SELECT + f.sql + " ORDER BY t.business_date, t.id")
                .params(f.params)
                .query((rs, n) -> advance(rs))
                .list();
    }

    private static final String REPAYMENT_SELECT = """
            SELECT t.id, t.advance_id, t.branch_id, t.amount_minor, t.currency, t.method, t.paid_on, t.created_at,
                   t.recorded_by, u.full_name AS by_name, t.journal_entry_id, t.historical,
                   t.voided_at, t.voided_by, t.void_reason
              FROM retail_advance_repayments t""" + NAME;

    private RepaymentRow repayment(ResultSet rs) throws SQLException {
        return new RepaymentRow(
                uuid(rs, "id"),
                uuid(rs, "advance_id"),
                uuid(rs, "branch_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getDate("paid_on").toLocalDate(),
                instant(rs, "created_at"),
                uuid(rs, "recorded_by"),
                rs.getString("by_name"),
                uuid(rs, "journal_entry_id"),
                rs.getBoolean("historical"),
                voidOf(rs));
    }

    void insertRepayment(
            UUID id,
            UUID advanceId,
            UUID branch,
            long amount,
            String currency,
            String method,
            LocalDate paidOn,
            UUID by,
            UUID entryId) {
        jdbc.sql("""
                        INSERT INTO retail_advance_repayments (id, tenant_id, advance_id, branch_id, amount_minor, currency,
                            method, paid_on, recorded_by, journal_entry_id)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :advance, :branch, :amount, :currency,
                            :method, :paidOn, :by, :entry)
                        """)
                .param("id", id)
                .param("advance", advanceId)
                .param("branch", branch)
                .param("amount", amount)
                .param("currency", currency)
                .param("method", method)
                .param("paidOn", Date.valueOf(paidOn))
                .param("by", by)
                .param("entry", entryId)
                .update();
    }

    List<RepaymentRow> repayments(UUID advanceId) {
        return jdbc.sql(REPAYMENT_SELECT + " WHERE t.advance_id = ? ORDER BY t.paid_on, t.created_at, t.id")
                .param(advanceId)
                .query((rs, n) -> repayment(rs))
                .list();
    }

    Optional<RepaymentRow> repayment(UUID id, boolean forUpdate) {
        return jdbc.sql(REPAYMENT_SELECT + " WHERE t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((rs, n) -> repayment(rs))
                .optional();
    }

    /** Moves the stored running total by {@code delta} under the advance's row lock held by the caller. */
    void addRepaid(UUID advanceId, long delta) {
        jdbc.sql("UPDATE retail_advances SET repaid_minor = repaid_minor + ? WHERE id = ?")
                .params(delta, advanceId)
                .update();
    }

    long nonVoidedRepayments(UUID advanceId) {
        return jdbc.sql("SELECT count(*) FROM retail_advance_repayments WHERE advance_id = ? AND voided_at IS NULL")
                .param(advanceId)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------------------------- voids

    /** The only UPDATE of a record: the void columns, once (a guard trigger enforces it). */
    void markVoided(String table, UUID id, UUID by, String reason) {
        if (!List.of(
                        "retail_daily_savings",
                        "retail_cash_bankings",
                        "retail_cash_withdrawals",
                        "retail_expenses",
                        "retail_advances",
                        "retail_advance_repayments")
                .contains(table)) {
            throw new IllegalArgumentException(table);
        }
        jdbc.sql("UPDATE " + table + " SET voided_at = now(), voided_by = ?, void_reason = ? WHERE id = ?")
                .params(by, reason, id)
                .update();
    }

    // ------------------------------------------------------------------------------- aggregates

    /** A sum over a date, and whether an imported row is among them. */
    record Agg(long sum, boolean historical) {

        static final Agg ZERO = new Agg(0, false);
    }

    /**
     * Sum of {@code amountColumn} by the record's own date, voided or not (ADR-022 decision 9: a
     * record counts on its day whatever happens to it later). {@code extra} is a constant fragment.
     */
    Map<LocalDate, Agg> sumByDate(
            String table,
            String dateColumn,
            String amountColumn,
            String extra,
            UUID branch,
            LocalDate from,
            LocalDate to) {
        Map<LocalDate, Agg> out = new TreeMap<>();
        jdbc.sql("SELECT " + dateColumn + " AS day, sum(" + amountColumn
                        + ") AS total, bool_or(historical) AS hist FROM "
                        + table + " WHERE branch_id = :branch AND " + dateColumn + " BETWEEN :from AND :to" + extra
                        + " GROUP BY " + dateColumn)
                .param("branch", branch)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, n) ->
                        out.put(rs.getDate("day").toLocalDate(), new Agg(rs.getLong("total"), rs.getBoolean("hist"))))
                .list();
        return out;
    }

    /** Sum by the day the record was voided in the tenant's zone (the void line of that day). */
    Map<LocalDate, Long> sumByVoidDate(
            String table, String amountColumn, String extra, UUID branch, LocalDate from, LocalDate to, ZoneId zone) {
        Map<LocalDate, Long> out = new TreeMap<>();
        jdbc.sql("SELECT (voided_at AT TIME ZONE :zone)::date AS day, sum(" + amountColumn + ") AS total FROM " + table
                        + " WHERE branch_id = :branch AND voided_at >= :start AND voided_at < :end" + extra
                        + " GROUP BY 1")
                .param("zone", zone.getId())
                .param("branch", branch)
                .param("start", Timestamp.from(from.atStartOfDay(zone).toInstant()))
                .param("end", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
                .query((rs, n) -> out.put(rs.getDate("day").toLocalDate(), rs.getLong("total")))
                .list();
        return out;
    }

    /** Sum of the non-voided banking records of one branch and day. */
    long bankedSoFar(UUID branch, LocalDate date) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount_minor), 0) FROM retail_cash_bankings
                         WHERE branch_id = ? AND business_date = ? AND voided_at IS NULL
                        """)
                .params(branch, Date.valueOf(date))
                .query(Long.class)
                .single();
    }

    /**
     * The first day the branch's cash book is live: the day after the imported opening entry when
     * there is one, else the earliest date of a live (not imported) record or live cash sale. Null
     * when the branch has none.
     */
    LocalDate firstLiveDay(UUID branch) {
        Date opening = jdbc.sql("""
                        SELECT max(e.entry_date) FROM retail_import_refs r
                          JOIN journal_entries e ON e.id = r.target_id
                         WHERE r.source_file = 'cash_opening' AND r.source_ref = ?
                        """)
                .param(branch.toString())
                .query(Date.class)
                .optional()
                .orElse(null);
        Date earliest =
                jdbc.sql("""
                        SELECT min(d) FROM (
                            SELECT min(business_date) AS d FROM retail_daily_savings WHERE branch_id = :b AND NOT historical
                            UNION ALL SELECT min(business_date) FROM retail_cash_bankings WHERE branch_id = :b AND NOT historical
                            UNION ALL SELECT min(business_date) FROM retail_cash_withdrawals WHERE branch_id = :b AND NOT historical
                            UNION ALL SELECT min(business_date) FROM retail_expenses WHERE branch_id = :b AND NOT historical
                            UNION ALL SELECT min(business_date) FROM retail_advances WHERE branch_id = :b AND NOT historical
                            UNION ALL SELECT min(paid_on) FROM retail_advance_repayments WHERE branch_id = :b AND NOT historical
                            UNION ALL SELECT min(sale_date) FROM retail_sales WHERE branch_id = :b AND NOT historical
                        ) x
                        """).param("b", branch).query(Date.class).optional().orElse(null);
        LocalDate afterOpening = opening == null ? null : opening.toLocalDate().plusDays(1);
        LocalDate live = earliest == null ? null : earliest.toLocalDate();
        if (afterOpening == null) {
            return live;
        }
        return live == null || afterOpening.isAfter(live) ? afterOpening : live;
    }

    // -------------------------------------------------------------------------------- reporting

    record ExpenseGroupRow(String key, String label, UUID branchId, long total, long count) {}

    /** Expense totals by category, item, branch or month over a range; voided rows only when asked. */
    List<ExpenseGroupRow> expenseGroups(
            String groupBy, List<UUID> branches, LocalDate from, LocalDate to, boolean includeVoided) {
        String key;
        String label;
        String group;
        switch (groupBy) {
            case "item" -> {
                key = "t.item_id::text";
                label = "t.category_name || ' / ' || t.item_name";
                group = "t.item_id, t.category_name, t.item_name";
            }
            case "branch" -> {
                key = "t.branch_id::text";
                label = "b.name";
                group = "t.branch_id, b.name";
            }
            case "month" -> {
                key = "to_char(t.business_date, 'YYYY-MM')";
                label = "to_char(t.business_date, 'YYYY-MM')";
                group = "to_char(t.business_date, 'YYYY-MM')";
            }
            default -> {
                key = "t.category_id::text";
                label = "t.category_name";
                group = "t.category_id, t.category_name";
            }
        }
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .live("t", includeVoided);
        return jdbc.sql("SELECT " + key + " AS k, " + label + " AS label, sum(t.amount_minor) AS total, count(*) AS n"
                        + " FROM retail_expenses t JOIN branches b ON b.id = t.branch_id" + f.sql
                        + " GROUP BY " + group + " ORDER BY sum(t.amount_minor) DESC, 2")
                .params(f.params)
                .query((rs, n) -> new ExpenseGroupRow(
                        rs.getString("k"), rs.getString("label"), null, rs.getLong("total"), rs.getLong("n")))
                .list();
    }

    /** Savings records, one per branch and day (the active one), for the savings report. */
    List<SavingsRow> savingsReport(List<UUID> branches, LocalDate from, LocalDate to) {
        Filter f = new Filter()
                .branches("t.branch_id", branches)
                .dates("t.business_date", from, to)
                .live("t", false);
        return jdbc.sql(SAVINGS_SELECT + f.sql + " ORDER BY t.business_date DESC, t.branch_id")
                .params(f.params)
                .query((rs, n) -> savings(rs))
                .list();
    }

    /** Days in a range on which the branches have any cash book record, for the daily report's day list. */
    List<LocalDate> activeDays(UUID branch, LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        jdbc.sql("""
                        SELECT DISTINCT d FROM (
                            SELECT business_date AS d FROM retail_daily_savings WHERE branch_id = :b
                            UNION ALL SELECT business_date FROM retail_cash_bankings WHERE branch_id = :b
                            UNION ALL SELECT business_date FROM retail_cash_withdrawals WHERE branch_id = :b
                            UNION ALL SELECT business_date FROM retail_expenses WHERE branch_id = :b
                            UNION ALL SELECT business_date FROM retail_advances WHERE branch_id = :b
                            UNION ALL SELECT paid_on FROM retail_advance_repayments WHERE branch_id = :b
                        ) x WHERE d BETWEEN :from AND :to
                        """)
                .param("b", branch)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, n) -> days.add(rs.getDate(1).toLocalDate()))
                .list();
        return days;
    }
}
