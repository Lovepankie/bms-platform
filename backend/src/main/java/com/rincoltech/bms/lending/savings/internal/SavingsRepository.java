package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.lending.savings.internal.SavingsApi.BalanceRow;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MovementRow;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Product;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.StatementLine;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Terms;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Transaction;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Plain SQL for the savings tables. No tenant predicate: row-level security applies it (ADR-003);
 * inserts take {@code tenant_id} from the bound tenant. Member numbers and names are read with a
 * join on {@code lending_members}, as the loan list does.
 */
@Repository
class SavingsRepository {

    private final JdbcClient jdbc;

    SavingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Products ---------------------------------------------------------------------------

    private static final String PRODUCT = """
            SELECT p.*, (SELECT count(*) FROM lending_savings_accounts a WHERE a.product_id = p.id) AS accounts
              FROM lending_savings_products p
            """;

    void insertProduct(UUID id, String code, String currency, Terms t, UUID by) {
        jdbc.sql("""
                        INSERT INTO lending_savings_products (id, tenant_id, code, name, currency, interest_rate_bp,
                            interest_calc, interest_posting, min_balance_for_interest_minor, min_opening_balance_minor,
                            min_balance_minor, withdrawal_fee_minor, max_withdrawal_minor, max_withdrawals_per_month,
                            dormancy_days, status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)
                        """)
                .params(
                        id,
                        code,
                        t.name().trim(),
                        currency,
                        t.interestRateBp(),
                        t.interestCalc(),
                        t.interestPosting(),
                        zero(t.minBalanceForInterestMinor()),
                        zero(t.minOpeningBalanceMinor()),
                        zero(t.minBalanceMinor()),
                        zero(t.withdrawalFeeMinor()),
                        t.maxWithdrawalMinor(),
                        t.maxWithdrawalsPerMonth(),
                        t.dormancyDays(),
                        by)
                .update();
    }

    void updateProduct(UUID id, Terms t, String status) {
        jdbc.sql("""
                        UPDATE lending_savings_products
                           SET name = ?, interest_rate_bp = ?, interest_calc = ?, interest_posting = ?,
                               min_balance_for_interest_minor = ?, min_opening_balance_minor = ?, min_balance_minor = ?,
                               withdrawal_fee_minor = ?, max_withdrawal_minor = ?, max_withdrawals_per_month = ?,
                               dormancy_days = ?, status = ?, version = version + 1, updated_at = now()
                         WHERE id = ?
                        """)
                .params(
                        t.name().trim(),
                        t.interestRateBp(),
                        t.interestCalc(),
                        t.interestPosting(),
                        zero(t.minBalanceForInterestMinor()),
                        zero(t.minOpeningBalanceMinor()),
                        zero(t.minBalanceMinor()),
                        zero(t.withdrawalFeeMinor()),
                        t.maxWithdrawalMinor(),
                        t.maxWithdrawalsPerMonth(),
                        t.dormancyDays(),
                        status,
                        id)
                .update();
    }

    Optional<Product> product(UUID id) {
        return jdbc.sql(PRODUCT + " WHERE p.id = ?")
                .param(id)
                .query(SavingsRepository::product)
                .optional();
    }

    Optional<Product> lockProduct(UUID id) {
        return jdbc.sql(PRODUCT + " WHERE p.id = ? FOR UPDATE OF p")
                .param(id)
                .query(SavingsRepository::product)
                .optional();
    }

    List<Product> products() {
        return jdbc.sql(PRODUCT + " ORDER BY p.status, p.code")
                .query(SavingsRepository::product)
                .list();
    }

    private static Product product(ResultSet rs, int n) throws SQLException {
        return new Product(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getInt("interest_rate_bp"),
                rs.getString("interest_calc"),
                rs.getString("interest_posting"),
                rs.getLong("min_balance_for_interest_minor"),
                rs.getLong("min_opening_balance_minor"),
                rs.getLong("min_balance_minor"),
                rs.getLong("withdrawal_fee_minor"),
                rs.getObject("max_withdrawal_minor", Long.class),
                rs.getObject("max_withdrawals_per_month", Integer.class),
                rs.getObject("dormancy_days", Integer.class),
                rs.getString("status"),
                rs.getLong("accounts"));
    }

    // ---- Accounts ---------------------------------------------------------------------------

    /** An account with its product's rules and its member's number and name. */
    record AccountRow(
            UUID id,
            int version,
            UUID branchId,
            String accountNo,
            UUID memberId,
            String memberNo,
            String memberName,
            UUID productId,
            String productCode,
            String productName,
            String currency,
            String status,
            String statusReason,
            long balanceMinor,
            long holdMinor,
            LocalDate openedOn,
            LocalDate closedOn,
            LocalDate lastMemberTxnOn,
            LocalDate lastInterestPostedTo,
            LocalDate balancesThrough,
            int txnCount,
            int interestRateBp,
            String interestCalc,
            String interestPosting,
            long minBalanceForInterestMinor,
            long minOpeningBalanceMinor,
            long minBalanceMinor,
            long withdrawalFeeMinor,
            Long maxWithdrawalMinor,
            Integer maxWithdrawalsPerMonth,
            Integer dormancyDays) {

        /** The day the next posting period starts: after the last posting, else the opening day. */
        LocalDate interestFrom() {
            return lastInterestPostedTo != null ? lastInterestPostedTo.plusDays(1) : openedOn;
        }
    }

    private static final String ACCOUNT = """
            SELECT a.*, m.member_no, m.full_name, p.code AS product_code, p.name AS product_name, p.interest_rate_bp,
                   p.interest_calc, p.interest_posting, p.min_balance_for_interest_minor, p.min_opening_balance_minor,
                   p.min_balance_minor, p.withdrawal_fee_minor, p.max_withdrawal_minor, p.max_withdrawals_per_month,
                   p.dormancy_days
              FROM lending_savings_accounts a
              JOIN lending_members m ON m.id = a.member_id
              JOIN lending_savings_products p ON p.id = a.product_id
            """;

    void insertAccount(
            UUID id,
            UUID branchId,
            String accountNo,
            UUID memberId,
            UUID productId,
            String currency,
            LocalDate openedOn,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO lending_savings_accounts (id, tenant_id, branch_id, account_no, member_id, product_id,
                            currency, status, opened_on, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, 'active', ?, ?)
                        """)
                .params(id, branchId, accountNo, memberId, productId, currency, Date.valueOf(openedOn), by)
                .update();
    }

    Optional<AccountRow> account(UUID id) {
        return jdbc.sql(ACCOUNT + " WHERE a.id = ?")
                .param(id)
                .query(SavingsRepository::account)
                .optional();
    }

    /** The account row locked until the transaction ends: every movement takes this lock (FR-SAV-04). */
    Optional<AccountRow> lock(UUID id) {
        return jdbc.sql(ACCOUNT + " WHERE a.id = ? FOR UPDATE OF a")
                .param(id)
                .query(SavingsRepository::account)
                .optional();
    }

    /** One page ordered by account number; {@code branchIds} already intersected with the scope. */
    List<AccountRow> page(
            List<UUID> branchIds,
            UUID memberId,
            List<String> statuses,
            UUID productId,
            String q,
            String afterNo,
            int limit) {
        StringBuilder sql = new StringBuilder(ACCOUNT).append(" WHERE true");
        Map<String, Object> p = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND a.branch_id IN (:branchIds)");
            p.put("branchIds", branchIds);
        }
        if (memberId != null) {
            sql.append(" AND a.member_id = :memberId");
            p.put("memberId", memberId);
        }
        if (!statuses.isEmpty()) {
            sql.append(" AND a.status IN (:statuses)");
            p.put("statuses", statuses);
        }
        if (productId != null) {
            sql.append(" AND a.product_id = :productId");
            p.put("productId", productId);
        }
        if (q != null) {
            sql.append(" AND (a.account_no ILIKE :prefix OR m.member_no ILIKE :prefix OR m.full_name ILIKE :part)");
            String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            p.put("prefix", escaped + "%");
            p.put("part", "%" + escaped + "%");
        }
        if (afterNo != null) {
            sql.append(" AND a.account_no > :afterNo");
            p.put("afterNo", afterNo);
        }
        sql.append(" ORDER BY a.account_no LIMIT :limit");
        p.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(p)
                .query(SavingsRepository::account)
                .list();
    }

    /** Accounts the nightly end of day still has days to write for, through {@code through}. */
    List<UUID> openAccountsBehind(LocalDate through) {
        return jdbc.sql("""
                        SELECT id FROM lending_savings_accounts
                         WHERE status <> 'closed' AND opened_on <= ?
                           AND (balances_through IS NULL OR balances_through < ?)
                         ORDER BY account_no
                        """)
                .params(Date.valueOf(through), Date.valueOf(through))
                .query(UUID.class)
                .list();
    }

    /** A money movement: the new balance, one more transaction, and the member activity date if any. */
    void moved(UUID id, long balanceMinor, LocalDate memberTxnOn) {
        jdbc.sql("""
                        UPDATE lending_savings_accounts
                           SET balance_minor = ?, txn_count = txn_count + 1, version = version + 1, updated_at = now(),
                               last_member_txn_on = CASE WHEN CAST(? AS date) IS NULL THEN last_member_txn_on
                                                         ELSE greatest(last_member_txn_on, CAST(? AS date)) END
                         WHERE id = ?
                        """)
                .params(balanceMinor, date(memberTxnOn), date(memberTxnOn), id)
                .update();
    }

    void status(UUID id, String status, String reason, LocalDate closedOn) {
        jdbc.sql("""
                        UPDATE lending_savings_accounts
                           SET status = ?, status_reason = ?, closed_on = ?, version = version + 1, updated_at = now()
                         WHERE id = ?
                        """).params(status, reason, date(closedOn), id).update();
    }

    void balancesThrough(UUID id, LocalDate through) {
        jdbc.sql("UPDATE lending_savings_accounts SET balances_through = ? WHERE id = ?")
                .params(Date.valueOf(through), id)
                .update();
    }

    void interestPostedTo(UUID id, LocalDate to) {
        jdbc.sql("UPDATE lending_savings_accounts SET last_interest_posted_to = ? WHERE id = ?")
                .params(Date.valueOf(to), id)
                .update();
    }

    boolean hasOpenAccounts(UUID branchId) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM lending_savings_accounts WHERE branch_id = ? AND status <> 'closed')")
                .param(branchId)
                .query(Boolean.class)
                .single();
    }

    private static AccountRow account(ResultSet rs, int n) throws SQLException {
        return new AccountRow(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("account_no"),
                rs.getObject("member_id", UUID.class),
                rs.getString("member_no"),
                rs.getString("full_name"),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_code"),
                rs.getString("product_name"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("status_reason"),
                rs.getLong("balance_minor"),
                rs.getLong("hold_minor"),
                rs.getObject("opened_on", LocalDate.class),
                rs.getObject("closed_on", LocalDate.class),
                rs.getObject("last_member_txn_on", LocalDate.class),
                rs.getObject("last_interest_posted_to", LocalDate.class),
                rs.getObject("balances_through", LocalDate.class),
                rs.getInt("txn_count"),
                rs.getInt("interest_rate_bp"),
                rs.getString("interest_calc"),
                rs.getString("interest_posting"),
                rs.getLong("min_balance_for_interest_minor"),
                rs.getLong("min_opening_balance_minor"),
                rs.getLong("min_balance_minor"),
                rs.getLong("withdrawal_fee_minor"),
                rs.getObject("max_withdrawal_minor", Long.class),
                rs.getObject("max_withdrawals_per_month", Integer.class),
                rs.getObject("dormancy_days", Integer.class));
    }

    // ---- Transactions -----------------------------------------------------------------------

    /** A row of {@code lending_savings_transactions} as written. */
    record Txn(
            UUID id,
            UUID branchId,
            UUID accountId,
            int seq,
            String txnType,
            long amountMinor,
            boolean credit,
            String currency,
            long balanceAfterMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            String receiptNo,
            String reason,
            UUID relatedTxnId,
            UUID reversesTxnId,
            UUID journalEntryId,
            UUID approvalRequestId,
            String source,
            UUID recordedBy) {}

    void insertTxn(Txn t) {
        jdbc.sql("""
                        INSERT INTO lending_savings_transactions (id, tenant_id, branch_id, account_id, seq, txn_type,
                            amount_minor, is_credit, currency, balance_after_minor, value_date, payment_method_key,
                            external_reference, receipt_no, reason, related_txn_id, reverses_txn_id, journal_entry_id,
                            approval_request_id, source, recorded_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?)
                        """)
                .params(
                        t.id(),
                        t.branchId(),
                        t.accountId(),
                        t.seq(),
                        t.txnType(),
                        t.amountMinor(),
                        t.credit(),
                        t.currency(),
                        t.balanceAfterMinor(),
                        Date.valueOf(t.valueDate()),
                        t.paymentMethodKey(),
                        t.externalReference(),
                        t.receiptNo(),
                        t.reason(),
                        t.relatedTxnId(),
                        t.reversesTxnId(),
                        t.journalEntryId(),
                        t.approvalRequestId(),
                        t.source(),
                        t.recordedBy())
                .update();
    }

    private static final String TXN = """
            SELECT t.*, r.id AS reversed_by FROM lending_savings_transactions t
              LEFT JOIN lending_savings_transactions r ON r.reverses_txn_id = t.id
            """;

    Optional<Transaction> transaction(UUID id) {
        return jdbc.sql(TXN + " WHERE t.id = ?")
                .param(id)
                .query(SavingsRepository::transaction)
                .optional();
    }

    /** Newest first, before {@code beforeSeq} when given. */
    List<Transaction> transactions(UUID accountId, Integer beforeSeq, int limit) {
        return jdbc.sql(TXN + " WHERE t.account_id = ? AND (CAST(? AS integer) IS NULL OR t.seq < ?)"
                        + " ORDER BY t.seq DESC LIMIT ?")
                .params(accountId, beforeSeq, beforeSeq, limit)
                .query(SavingsRepository::transaction)
                .list();
    }

    /** The fee row charged on a withdrawal, if any. */
    Optional<Transaction> feeOf(UUID withdrawalId) {
        return jdbc.sql(TXN + " WHERE t.related_txn_id = ? AND t.txn_type = 'fee'")
                .param(withdrawalId)
                .query(SavingsRepository::transaction)
                .optional();
    }

    Optional<UUID> accountOfTxn(UUID txnId) {
        return jdbc.sql("SELECT account_id FROM lending_savings_transactions WHERE id = ?")
                .param(txnId)
                .query(UUID.class)
                .optional();
    }

    Optional<UUID> journalOf(UUID txnId) {
        return jdbc.sql("SELECT journal_entry_id FROM lending_savings_transactions WHERE id = ?")
                .param(txnId)
                .query(UUID.class)
                .optional();
    }

    /** Withdrawals in the calendar month of {@code day} that are not reversed. */
    int withdrawalsInMonth(UUID accountId, LocalDate day) {
        LocalDate first = day.withDayOfMonth(1);
        return jdbc.sql("""
                        SELECT count(*) FROM lending_savings_transactions t
                         WHERE t.account_id = ? AND t.txn_type = 'withdrawal' AND t.value_date >= ? AND t.value_date < ?
                           AND NOT EXISTS (SELECT 1 FROM lending_savings_transactions r WHERE r.reverses_txn_id = t.id)
                        """)
                .params(accountId, Date.valueOf(first), Date.valueOf(first.plusMonths(1)))
                .query(Integer.class)
                .single();
    }

    private static Transaction transaction(ResultSet rs, int n) throws SQLException {
        return new Transaction(
                rs.getObject("id", UUID.class),
                rs.getInt("seq"),
                rs.getString("txn_type"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getBoolean("is_credit"),
                rs.getLong("balance_after_minor"),
                rs.getObject("value_date", LocalDate.class),
                rs.getString("payment_method_key"),
                rs.getString("external_reference"),
                rs.getString("receipt_no"),
                rs.getString("reason"),
                rs.getObject("related_txn_id", UUID.class),
                rs.getObject("reverses_txn_id", UUID.class),
                rs.getObject("reversed_by", UUID.class),
                rs.getObject("approval_request_id", UUID.class),
                rs.getString("source"),
                rs.getTimestamp("created_at").toInstant());
    }

    // ---- Balances by value date -------------------------------------------------------------

    private static final String SIGNED = "CASE WHEN t.is_credit THEN t.amount_minor ELSE -t.amount_minor END";

    /** The balance from every movement dated before {@code day}. */
    long balanceBefore(UUID accountId, LocalDate day) {
        return jdbc.sql("SELECT coalesce(sum(" + SIGNED + "), 0) FROM lending_savings_transactions t"
                        + " WHERE t.account_id = ? AND t.value_date < ?")
                .params(accountId, Date.valueOf(day))
                .query(Long.class)
                .single();
    }

    /** The end-of-day balance of every day from {@code from} to {@code to}, from the movements' value dates. */
    Map<LocalDate, Long> closingBalances(UUID accountId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> deltas = new TreeMap<>();
        jdbc.sql("SELECT t.value_date, sum(" + SIGNED + ") FROM lending_savings_transactions t"
                        + " WHERE t.account_id = ? AND t.value_date BETWEEN ? AND ? GROUP BY t.value_date")
                .params(accountId, Date.valueOf(from), Date.valueOf(to))
                .query((rs, n) -> deltas.put(rs.getObject(1, LocalDate.class), rs.getLong(2)))
                .list();
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        long balance = balanceBefore(accountId, from);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            balance += deltas.getOrDefault(d, 0L);
            out.put(d, balance);
        }
        return out;
    }

    void insertDailyBalances(UUID accountId, Map<LocalDate, Long> balances) {
        for (Map.Entry<LocalDate, Long> e : balances.entrySet()) {
            jdbc.sql("""
                            INSERT INTO lending_savings_daily_balances (tenant_id, account_id, business_date,
                                closing_balance_minor)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?)
                            ON CONFLICT DO NOTHING
                            """)
                    .params(accountId, Date.valueOf(e.getKey()), e.getValue())
                    .update();
        }
    }

    Map<LocalDate, Long> dailyBalances(UUID accountId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> out = new TreeMap<>();
        jdbc.sql("""
                        SELECT business_date, closing_balance_minor FROM lending_savings_daily_balances
                         WHERE account_id = ? AND business_date BETWEEN ? AND ?
                        """)
                .params(accountId, Date.valueOf(from), Date.valueOf(to))
                .query((rs, n) -> out.put(rs.getObject(1, LocalDate.class), rs.getLong(2)))
                .list();
        return out;
    }

    /** Returns false when the account already has a posting for this period end. */
    boolean insertPosting(UUID accountId, LocalDate start, LocalDate end, long interestMinor, UUID txnId) {
        return jdbc.sql("""
                        INSERT INTO lending_savings_interest_postings (id, tenant_id, account_id, period_start, period_end,
                            interest_minor, transaction_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, account_id, period_end) DO NOTHING
                        """)
                        .params(
                                UUID.randomUUID(),
                                accountId,
                                Date.valueOf(start),
                                Date.valueOf(end),
                                interestMinor,
                                txnId)
                        .update()
                == 1;
    }

    boolean hasPosting(UUID accountId, LocalDate end) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM lending_savings_interest_postings WHERE account_id = ? AND period_end = ?)")
                .params(accountId, Date.valueOf(end))
                .query(Boolean.class)
                .single();
    }

    /** Active accounts whose last member activity (or opening) is at least their product's dormancy days before {@code on}. */
    List<UUID> dueForDormancy(LocalDate on) {
        return jdbc.sql("""
                        SELECT a.id FROM lending_savings_accounts a JOIN lending_savings_products p ON p.id = a.product_id
                         WHERE a.status = 'active' AND p.dormancy_days IS NOT NULL
                           AND coalesce(a.last_member_txn_on, a.opened_on) + p.dormancy_days <= ?
                        """).param(Date.valueOf(on)).query(UUID.class).list();
    }

    // ---- Statement and reports --------------------------------------------------------------

    List<StatementLine> statementLines(UUID accountId, LocalDate from, LocalDate to, long opening) {
        List<StatementLine> lines = new ArrayList<>();
        long[] running = {opening};
        jdbc.sql("""
                        SELECT t.seq, t.value_date, t.txn_type, t.receipt_no, t.amount_minor, t.is_credit, t.reason,
                               o.txn_type AS reversed_type, o.receipt_no AS reversed_receipt
                          FROM lending_savings_transactions t
                          LEFT JOIN lending_savings_transactions o ON o.id = t.reverses_txn_id
                         WHERE t.account_id = ? AND t.value_date BETWEEN ? AND ?
                         ORDER BY t.value_date, t.seq
                        """)
                .params(accountId, Date.valueOf(from), Date.valueOf(to))
                .query((rs, n) -> {
                    long amount = rs.getLong("amount_minor");
                    boolean credit = rs.getBoolean("is_credit");
                    running[0] += credit ? amount : -amount;
                    String type = rs.getString("txn_type");
                    String description = switch (type) {
                        case "reversal" ->
                            "Reversal of " + rs.getString("reversed_type")
                                    + (rs.getString("reversed_receipt") != null
                                            ? " " + rs.getString("reversed_receipt")
                                            : "");
                        case "interest" -> "Interest";
                        case "fee" -> "Withdrawal fee";
                        default ->
                            Character.toUpperCase(type.charAt(0))
                                    + type.substring(1).replace('_', ' ');
                    };
                    lines.add(new StatementLine(
                            rs.getInt("seq"),
                            rs.getObject("value_date", LocalDate.class),
                            type,
                            rs.getString("receipt_no"),
                            description,
                            credit ? 0 : amount,
                            credit ? amount : 0,
                            running[0]));
                    return null;
                })
                .list();
        return lines;
    }

    /** Per account, the balance from movements dated on or before {@code asAt}; open then, or holding money. */
    List<BalanceRow> balancesAsAt(LocalDate asAt, List<UUID> branchIds, UUID productId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("asAt", Date.valueOf(asAt));
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.account_no, a.branch_id, m.member_no, m.full_name, pr.code, a.status,
                       coalesce(sum(%s) FILTER (WHERE t.value_date <= :asAt), 0) AS balance,
                       max(t.value_date) FILTER (WHERE t.value_date <= :asAt AND t.txn_type IN ('deposit', 'withdrawal'))
                           AS last_member
                  FROM lending_savings_accounts a
                  JOIN lending_members m ON m.id = a.member_id
                  JOIN lending_savings_products pr ON pr.id = a.product_id
                  LEFT JOIN lending_savings_transactions t ON t.account_id = a.id
                 WHERE a.opened_on <= :asAt
                """.formatted(SIGNED));
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND a.branch_id IN (:branchIds)");
            p.put("branchIds", branchIds);
        }
        if (productId != null) {
            sql.append(" AND a.product_id = :productId");
            p.put("productId", productId);
        }
        sql.append(" GROUP BY a.id, m.member_no, m.full_name, pr.code"
                + " HAVING a.closed_on IS NULL OR a.closed_on > :asAt"
                + " OR coalesce(sum(" + SIGNED + ") FILTER (WHERE t.value_date <= :asAt), 0) <> 0"
                + " ORDER BY a.account_no");
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> new BalanceRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("account_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("member_no"),
                        rs.getString("full_name"),
                        rs.getString("code"),
                        rs.getString("status"),
                        rs.getLong("balance"),
                        rs.getObject("last_member", LocalDate.class)))
                .list();
    }

    /** Per branch and product: opening, each movement type and closing, by value date. */
    List<MovementRow> movements(LocalDate from, LocalDate to, List<UUID> branchIds, UUID productId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", Date.valueOf(from));
        p.put("to", Date.valueOf(to));
        StringBuilder sql = new StringBuilder("""
                SELECT a.branch_id, pr.code,
                       coalesce(sum(%1$s) FILTER (WHERE t.value_date < :from), 0) AS opening,
                       coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'deposit' AND t.value_date BETWEEN :from AND :to), 0) AS deposits,
                       coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'withdrawal' AND t.value_date BETWEEN :from AND :to), 0) AS withdrawals,
                       coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'interest' AND t.value_date BETWEEN :from AND :to), 0) AS interest,
                       coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'fee' AND t.value_date BETWEEN :from AND :to), 0) AS fees,
                       coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'reversal' AND t.is_credit AND t.value_date BETWEEN :from AND :to), 0) AS rev_in,
                       coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'reversal' AND NOT t.is_credit AND t.value_date BETWEEN :from AND :to), 0) AS rev_out,
                       coalesce(sum(%1$s) FILTER (WHERE t.value_date <= :to), 0) AS closing
                  FROM lending_savings_accounts a
                  JOIN lending_savings_products pr ON pr.id = a.product_id
                  JOIN lending_savings_transactions t ON t.account_id = a.id
                 WHERE t.value_date <= :to
                """.formatted(SIGNED));
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND a.branch_id IN (:branchIds)");
            p.put("branchIds", branchIds);
        }
        if (productId != null) {
            sql.append(" AND a.product_id = :productId");
            p.put("productId", productId);
        }
        sql.append(" GROUP BY a.branch_id, pr.code ORDER BY pr.code, a.branch_id");
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> {
                    long opening = rs.getLong("opening");
                    long closing = rs.getLong("closing");
                    return new MovementRow(
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("code"),
                            opening,
                            rs.getLong("deposits"),
                            rs.getLong("withdrawals"),
                            rs.getLong("interest"),
                            rs.getLong("fees"),
                            rs.getLong("rev_in"),
                            rs.getLong("rev_out"),
                            closing - opening,
                            closing);
                })
                .list();
    }

    /** Accounts dormant now, in the branches. */
    long dormantAccounts(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT count(*) FROM lending_savings_accounts WHERE status = 'dormant'"
                        + (branchIds == null ? "" : " AND branch_id IN (:branchIds)"))
                .params(branchIds == null ? Map.of() : Map.of("branchIds", branchIds))
                .query(Long.class)
                .single();
    }

    private static long zero(Long v) {
        return v == null ? 0 : v;
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
