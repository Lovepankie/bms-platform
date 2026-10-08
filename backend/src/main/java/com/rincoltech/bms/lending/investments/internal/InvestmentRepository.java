package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.lending.investments.internal.InvestmentApi.InvestmentTransaction;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Product;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ProductTerms;
import com.rincoltech.bms.lending.investments.internal.InvestmentReturns.Period;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for investments: products, investments, schedule items and transactions. Row-level security
 * supplies the tenant predicate (ADR-003); {@code tenant_id} is written from the bound tenant on
 * insert. The caller holds the investment's row lock for every write to it.
 */
@Repository
class InvestmentRepository {

    private final JdbcClient jdbc;

    InvestmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Products -------------------------------------------------------------------------

    List<Product> products() {
        return jdbc.sql("SELECT * FROM lending_investment_products ORDER BY status, code")
                .query(InvestmentRepository::product)
                .list();
    }

    Optional<Product> product(UUID id) {
        return jdbc.sql("SELECT * FROM lending_investment_products WHERE id = ?")
                .param(id)
                .query(InvestmentRepository::product)
                .optional();
    }

    Optional<Product> lockProduct(UUID id) {
        return jdbc.sql("SELECT * FROM lending_investment_products WHERE id = ? FOR UPDATE")
                .param(id)
                .query(InvestmentRepository::product)
                .optional();
    }

    boolean productCodeTaken(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending_investment_products WHERE code = ?)")
                .param(code)
                .query(Boolean.class)
                .single();
    }

    void insertProduct(UUID id, String code, String name, String currency, ProductTerms t, UUID by) {
        jdbc.sql("""
                        INSERT INTO lending_investment_products (id, tenant_id, code, name, currency, product_type,
                            allowed_terms_months, return_rate_bp, return_method, payout_frequency, min_amount_minor,
                            max_amount_minor, early_withdrawal_allowed, early_withdrawal_rule, early_withdrawal_rate_bp,
                            early_withdrawal_penalty_bp, status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?::integer[], ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, 'active', ?)
                        """)
                .params(
                        id,
                        code,
                        name,
                        currency,
                        t.productType(),
                        termsArray(t.allowedTermsMonths()),
                        t.returnRateBp(),
                        t.returnMethod(),
                        t.payoutFrequency(),
                        t.minAmountMinor(),
                        t.maxAmountMinor(),
                        t.earlyWithdrawalAllowed(),
                        t.earlyWithdrawalAllowed() ? t.earlyWithdrawalRule() : null,
                        t.earlyWithdrawalAllowed() ? t.earlyWithdrawalRateBp() : null,
                        penalty(t),
                        by)
                .update();
    }

    void updateProduct(UUID id, String name, ProductTerms t) {
        jdbc.sql("""
                        UPDATE lending_investment_products SET name = ?, product_type = ?,
                            allowed_terms_months = ?::integer[], return_rate_bp = ?, return_method = ?,
                            payout_frequency = ?, min_amount_minor = ?, max_amount_minor = ?,
                            early_withdrawal_allowed = ?, early_withdrawal_rule = ?, early_withdrawal_rate_bp = ?,
                            early_withdrawal_penalty_bp = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """)
                .params(
                        name,
                        t.productType(),
                        termsArray(t.allowedTermsMonths()),
                        t.returnRateBp(),
                        t.returnMethod(),
                        t.payoutFrequency(),
                        t.minAmountMinor(),
                        t.maxAmountMinor(),
                        t.earlyWithdrawalAllowed(),
                        t.earlyWithdrawalAllowed() ? t.earlyWithdrawalRule() : null,
                        t.earlyWithdrawalAllowed() ? t.earlyWithdrawalRateBp() : null,
                        penalty(t),
                        id)
                .update();
    }

    void archiveProduct(UUID id) {
        jdbc.sql("""
                        UPDATE lending_investment_products SET status = 'archived', updated_at = now(),
                            version = version + 1 WHERE id = ?
                        """).param(id).update();
    }

    private static int penalty(ProductTerms t) {
        return t.earlyWithdrawalAllowed() && t.earlyWithdrawalPenaltyBp() != null ? t.earlyWithdrawalPenaltyBp() : 0;
    }

    private static String termsArray(List<Integer> terms) {
        return "{"
                + String.join(
                        ",",
                        terms.stream().sorted().distinct().map(String::valueOf).toList()) + "}";
    }

    private static Product product(ResultSet rs, int n) throws SQLException {
        return new Product(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getString("product_type"),
                ints(rs.getArray("allowed_terms_months")),
                rs.getInt("return_rate_bp"),
                rs.getString("return_method"),
                rs.getString("payout_frequency"),
                rs.getLong("min_amount_minor"),
                (Long) rs.getObject("max_amount_minor"),
                rs.getBoolean("early_withdrawal_allowed"),
                rs.getString("early_withdrawal_rule"),
                (Integer) rs.getObject("early_withdrawal_rate_bp"),
                rs.getInt("early_withdrawal_penalty_bp"),
                instant(rs, "created_at"));
    }

    private static List<Integer> ints(Array array) throws SQLException {
        return Arrays.stream((Integer[]) array.getArray()).toList();
    }

    // ---- Investments ----------------------------------------------------------------------

    /** One investment row with its member's and product's display fields. */
    record Inv(
            UUID id,
            String accountNo,
            UUID branchId,
            UUID memberId,
            String memberNo,
            String memberName,
            UUID productId,
            String productCode,
            String productName,
            String productType,
            String currency,
            String status,
            int version,
            long principalMinor,
            int returnRateBp,
            String returnMethod,
            int termMonths,
            String payoutFrequency,
            boolean earlyWithdrawalAllowed,
            String earlyWithdrawalRule,
            Integer earlyWithdrawalRateBp,
            int earlyWithdrawalPenaltyBp,
            LocalDate startDate,
            LocalDate maturityDate,
            Long agreedReturnMinor,
            long principalHeldMinor,
            long returnAccruedMinor,
            long returnDueMinor,
            long returnPaidMinor,
            String maturityInstruction,
            UUID rolledOverFromId,
            UUID rolledOverToId,
            String certificateNo,
            String channel,
            LocalDate preMaturityRemindedOn,
            LocalDate postMaturityRemindedOn,
            LocalDate closedOn,
            Instant createdAt) {

        /** Accrued and not paid: the investment's returns payable subledger. */
        long returnPayableMinor() {
            return returnAccruedMinor - returnPaidMinor;
        }

        /** Due (its payout date has come) and not paid. */
        long returnAvailableMinor() {
            return returnDueMinor - returnPaidMinor;
        }
    }

    private static final String SELECT_INV = """
            SELECT i.*, m.member_no, m.full_name AS member_name, p.code AS product_code, p.name AS product_name
              FROM lending_investments i
              JOIN lending_members m ON m.id = i.member_id
              JOIN lending_investment_products p ON p.id = i.product_id
            """;

    Optional<Inv> find(UUID id) {
        return jdbc.sql(SELECT_INV + " WHERE i.id = ?")
                .param(id)
                .query(InvestmentRepository::inv)
                .optional();
    }

    /** The investment's row lock, held until the caller's transaction ends; every money event takes it first. */
    Optional<Inv> lock(UUID id) {
        if (jdbc.sql("SELECT id FROM lending_investments WHERE id = ? FOR UPDATE")
                .param(id)
                .query(UUID.class)
                .optional()
                .isEmpty()) {
            return Optional.empty();
        }
        return find(id);
    }

    /** A filtered page in account number order, newest first; {@code after} is the last account number seen. */
    List<Inv> page(
            List<UUID> branchIds,
            List<String> statuses,
            UUID memberId,
            UUID productId,
            LocalDate maturingBy,
            String q,
            String after,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_INV).append(" WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND i.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND i.status IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (memberId != null) {
            sql.append(" AND i.member_id = :memberId");
            params.put("memberId", memberId);
        }
        if (productId != null) {
            sql.append(" AND i.product_id = :productId");
            params.put("productId", productId);
        }
        if (maturingBy != null) {
            sql.append(" AND i.maturity_date <= :maturingBy");
            params.put("maturingBy", maturingBy);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (i.account_no ILIKE :q OR m.member_no ILIKE :q OR m.full_name ILIKE :q)");
            params.put("q", "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (after != null) {
            sql.append(" AND i.account_no < :after");
            params.put("after", after);
        }
        sql.append(" ORDER BY i.account_no DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query(InvestmentRepository::inv)
                .list();
    }

    void insert(
            UUID id,
            String accountNo,
            UUID branchId,
            UUID memberId,
            Product p,
            long principalMinor,
            int termMonths,
            String instruction,
            String channel,
            UUID by) {
        jdbc.sql("""
                        INSERT INTO lending_investments (id, tenant_id, branch_id, account_no, member_id, product_id,
                            currency, status, principal_minor, return_rate_bp, return_method, term_months,
                            payout_frequency, product_type, early_withdrawal_allowed, early_withdrawal_rule,
                            early_withdrawal_rate_bp, early_withdrawal_penalty_bp, maturity_instruction, channel,
                            created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, 'pending_funding', ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        branchId,
                        accountNo,
                        memberId,
                        p.id(),
                        p.currency(),
                        principalMinor,
                        p.returnRateBp(),
                        p.returnMethod(),
                        termMonths,
                        p.payoutFrequency(),
                        p.productType(),
                        p.earlyWithdrawalAllowed(),
                        p.earlyWithdrawalRule(),
                        p.earlyWithdrawalRateBp(),
                        p.earlyWithdrawalPenaltyBp(),
                        instruction,
                        channel,
                        by)
                .update();
    }

    /** FR-INV-03: funded; the start and maturity dates, agreed return and certificate number are set once. */
    void funded(UUID id, LocalDate start, LocalDate maturity, long agreedReturn, String certificateNo) {
        jdbc.sql("""
                        UPDATE lending_investments SET status = 'active', start_date = ?, maturity_date = ?,
                            agreed_return_minor = ?, principal_held_minor = principal_minor, certificate_no = ?,
                            updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(start, maturity, agreedReturn, certificateNo, id).update();
    }

    /** Writes the balance and status columns together; every money event ends here. */
    void balances(UUID id, String status, long principalHeld, long accrued, long due, long paid, LocalDate closedOn) {
        jdbc.sql("""
                        UPDATE lending_investments SET status = ?, principal_held_minor = ?, return_accrued_minor = ?,
                            return_due_minor = ?, return_paid_minor = ?, closed_on = ?, updated_at = now(),
                            version = version + 1
                         WHERE id = ?
                        """)
                .params(status, principalHeld, accrued, due, paid, closedOn, id)
                .update();
    }

    void instruction(UUID id, String instruction) {
        jdbc.sql("""
                        UPDATE lending_investments SET maturity_instruction = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(instruction, id).update();
    }

    void rolledOver(UUID oldId, UUID newId) {
        jdbc.sql("UPDATE lending_investments SET rolled_over_to_id = ? WHERE id = ?")
                .params(newId, oldId)
                .update();
        jdbc.sql("UPDATE lending_investments SET rolled_over_from_id = ? WHERE id = ?")
                .params(oldId, newId)
                .update();
    }

    void reminded(UUID id, boolean beforeMaturity, LocalDate on) {
        jdbc.sql("UPDATE lending_investments SET "
                        + (beforeMaturity ? "pre_maturity_reminded_on" : "post_maturity_reminded_on")
                        + " = ? WHERE id = ?")
                .params(on, id)
                .update();
    }

    /** Active investments with a period ended on or before {@code date} that is not accrued yet. */
    List<UUID> withAccrualDue(LocalDate date) {
        return jdbc.sql("""
                        SELECT DISTINCT s.investment_id FROM lending_investment_schedule_items s
                          JOIN lending_investments i ON i.id = s.investment_id
                         WHERE s.status = 'scheduled' AND s.period_end <= ? AND i.status = 'active'
                         ORDER BY s.investment_id
                        """).param(date).query(UUID.class).list();
    }

    /** Active investments whose maturity date has come. */
    List<UUID> maturing(LocalDate date) {
        return jdbc.sql("""
                        SELECT id FROM lending_investments WHERE status = 'active' AND maturity_date <= ? ORDER BY id
                        """).param(date).query(UUID.class).list();
    }

    /** FR-INV-07 and FR-INV-05: investments a reminder is owed for on {@code date}. */
    List<UUID> reminderDue(LocalDate date, boolean beforeMaturity) {
        String sql = beforeMaturity ? """
                  SELECT id FROM lending_investments
                   WHERE status = 'active' AND pre_maturity_reminded_on IS NULL
                     AND maturity_date > ? AND maturity_date <= ? ORDER BY id
                  """ : """
                  SELECT id FROM lending_investments
                   WHERE status = 'matured' AND post_maturity_reminded_on IS NULL AND maturity_instruction IS NULL
                     AND maturity_date <= ? ORDER BY id
                  """;
        return beforeMaturity
                ? jdbc.sql(sql).params(date, date.plusDays(7)).query(UUID.class).list()
                : jdbc.sql(sql).param(date.minusDays(7)).query(UUID.class).list();
    }

    private static Inv inv(ResultSet rs, int n) throws SQLException {
        return new Inv(
                rs.getObject("id", UUID.class),
                rs.getString("account_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("member_no"),
                rs.getString("member_name"),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_code"),
                rs.getString("product_name"),
                rs.getString("product_type"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getLong("principal_minor"),
                rs.getInt("return_rate_bp"),
                rs.getString("return_method"),
                rs.getInt("term_months"),
                rs.getString("payout_frequency"),
                rs.getBoolean("early_withdrawal_allowed"),
                rs.getString("early_withdrawal_rule"),
                (Integer) rs.getObject("early_withdrawal_rate_bp"),
                rs.getInt("early_withdrawal_penalty_bp"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class),
                (Long) rs.getObject("agreed_return_minor"),
                rs.getLong("principal_held_minor"),
                rs.getLong("return_accrued_minor"),
                rs.getLong("return_due_minor"),
                rs.getLong("return_paid_minor"),
                rs.getString("maturity_instruction"),
                rs.getObject("rolled_over_from_id", UUID.class),
                rs.getObject("rolled_over_to_id", UUID.class),
                rs.getString("certificate_no"),
                rs.getString("channel"),
                rs.getObject("pre_maturity_reminded_on", LocalDate.class),
                rs.getObject("post_maturity_reminded_on", LocalDate.class),
                rs.getObject("closed_on", LocalDate.class),
                instant(rs, "created_at"));
    }

    /** The pending approval on an investment (funding, early withdrawal), if any. */
    Optional<UUID> pendingApproval(UUID investmentId) {
        return jdbc.sql("""
                        SELECT id FROM approval_requests
                         WHERE subject_id = ? AND status = 'pending' ORDER BY requested_at DESC LIMIT 1
                        """).param(investmentId).query(UUID.class).optional();
    }

    // ---- Schedule items -------------------------------------------------------------------

    /** One schedule row as stored. */
    record Item(
            UUID id,
            int periodNo,
            LocalDate periodStart,
            LocalDate periodEnd,
            long openingBalanceMinor,
            long returnMinor,
            boolean payout,
            String status) {}

    void insertItems(UUID investmentId, List<Period> periods) {
        for (Period p : periods) {
            jdbc.sql("""
                            INSERT INTO lending_investment_schedule_items (id, tenant_id, investment_id, period_no,
                                period_start, period_end, return_minor, opening_balance_minor, is_payout, status)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, 'scheduled')
                            """)
                    .params(
                            UUID.randomUUID(),
                            investmentId,
                            p.no(),
                            p.start(),
                            p.end(),
                            p.returnMinor(),
                            p.openingBalanceMinor(),
                            p.payout())
                    .update();
        }
    }

    List<Item> items(UUID investmentId) {
        return jdbc.sql("""
                        SELECT * FROM lending_investment_schedule_items WHERE investment_id = ? ORDER BY period_no
                        """)
                .param(investmentId)
                .query((rs, n) -> new Item(
                        rs.getObject("id", UUID.class),
                        rs.getInt("period_no"),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getLong("opening_balance_minor"),
                        rs.getLong("return_minor"),
                        rs.getBoolean("is_payout"),
                        rs.getString("status")))
                .list();
    }

    void itemAccrued(UUID itemId, UUID txnId) {
        jdbc.sql("""
                        UPDATE lending_investment_schedule_items SET status = 'accrued', accrued_txn_id = ?,
                            updated_at = now(), version = version + 1
                         WHERE id = ? AND status = 'scheduled'
                        """).params(txnId, itemId).update();
    }

    /** The periods not accrued yet stop: early withdrawal, or a funding reversed. */
    void cancelScheduled(UUID investmentId) {
        jdbc.sql("""
                        UPDATE lending_investment_schedule_items SET status = 'cancelled', updated_at = now(),
                            version = version + 1
                         WHERE investment_id = ? AND status = 'scheduled'
                        """).param(investmentId).update();
    }

    // ---- Transactions ---------------------------------------------------------------------

    /** One transaction to insert. */
    record Txn(
            UUID id,
            UUID branchId,
            UUID investmentId,
            String txnType,
            long amountMinor,
            long principalMinor,
            long returnMinor,
            long penaltyMinor,
            String currency,
            LocalDate valueDate,
            Integer periodNo,
            String paymentMethodKey,
            String externalReference,
            String receiptNo,
            String reason,
            UUID reversesTxnId,
            UUID journalEntryId,
            UUID approvalRequestId,
            String source,
            UUID recordedBy) {}

    void insertTxn(Txn t) {
        jdbc.sql("""
                        INSERT INTO lending_investment_transactions (id, tenant_id, branch_id, investment_id, txn_type,
                            amount_minor, principal_minor, return_minor, penalty_minor, currency, value_date, period_no,
                            payment_method_key, external_reference, receipt_no, reason, reverses_txn_id,
                            journal_entry_id, approval_request_id, source, recorded_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, ?)
                        """)
                .params(
                        t.id(),
                        t.branchId(),
                        t.investmentId(),
                        t.txnType(),
                        t.amountMinor(),
                        t.principalMinor(),
                        t.returnMinor(),
                        t.penaltyMinor(),
                        t.currency(),
                        t.valueDate(),
                        t.periodNo(),
                        t.paymentMethodKey(),
                        t.externalReference(),
                        t.receiptNo(),
                        t.reason(),
                        t.reversesTxnId(),
                        t.journalEntryId(),
                        t.approvalRequestId(),
                        t.source(),
                        t.recordedBy())
                .update();
    }

    private static final String SELECT_TXN = """
            SELECT t.*, r.id AS reversed_by_txn_id FROM lending_investment_transactions t
              LEFT JOIN lending_investment_transactions r ON r.reverses_txn_id = t.id
            """;

    Optional<InvestmentTransaction> txn(UUID id) {
        return jdbc.sql(SELECT_TXN + " WHERE t.id = ?")
                .param(id)
                .query(InvestmentRepository::txn)
                .optional();
    }

    /** In posting order: value date, then the order they were written. */
    List<InvestmentTransaction> txns(UUID investmentId) {
        return jdbc.sql(SELECT_TXN + " WHERE t.investment_id = ? ORDER BY t.value_date, t.created_at, t.id")
                .param(investmentId)
                .query(InvestmentRepository::txn)
                .list();
    }

    /** Transactions on the investment other than {@code except}; a funding is reversible only when there are none. */
    boolean hasOtherTxns(UUID investmentId, UUID except) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM lending_investment_transactions WHERE investment_id = ? AND id <> ?)")
                .params(investmentId, except)
                .query(Boolean.class)
                .single();
    }

    private static InvestmentTransaction txn(ResultSet rs, int n) throws SQLException {
        return new InvestmentTransaction(
                rs.getObject("id", UUID.class),
                rs.getObject("investment_id", UUID.class),
                rs.getString("txn_type"),
                rs.getLong("amount_minor"),
                rs.getLong("principal_minor"),
                rs.getLong("return_minor"),
                rs.getLong("penalty_minor"),
                rs.getString("currency"),
                rs.getObject("value_date", LocalDate.class),
                rs.getObject("period_no") == null ? null : rs.getInt("period_no"),
                rs.getString("payment_method_key"),
                rs.getString("external_reference"),
                rs.getString("receipt_no"),
                rs.getString("reason"),
                rs.getObject("reverses_txn_id", UUID.class),
                rs.getObject("reversed_by_txn_id", UUID.class),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getObject("approval_request_id", UUID.class),
                rs.getString("source"),
                rs.getObject("recorded_by", UUID.class),
                instant(rs, "created_at"));
    }

    // ---- Maturities -----------------------------------------------------------------------

    /** Active and matured investments with principal held, maturing on or before {@code by}, earliest first. */
    List<Inv> maturities(List<UUID> branchIds, LocalDate by) {
        StringBuilder sql = new StringBuilder(SELECT_INV)
                .append(" WHERE i.status IN ('active', 'matured') AND i.principal_held_minor > 0"
                        + " AND i.maturity_date <= :by");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", by);
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND i.branch_id IN (:branchIds)");
            params.put("branchIds", branchIds);
        }
        sql.append(" ORDER BY i.maturity_date, i.account_no");
        return jdbc.sql(sql.toString())
                .params(params)
                .query(InvestmentRepository::inv)
                .list();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
