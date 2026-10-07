package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.Servicing.Allocation;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.AllocationRow;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanBalances;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanTransaction;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for loan servicing: schedule items, loan transactions, repayment allocations and the
 * balance columns of {@code lending_loans}. Row-level security supplies the tenant predicate; the
 * caller holds the loan's row lock for every write.
 */
@Repository
class ServicingRepository {

    private final JdbcClient jdbc;

    ServicingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Schedule items -------------------------------------------------------------------

    void insertItems(UUID loanId, List<ScheduleCalculator.Item> items, LocalDate today) {
        for (ScheduleCalculator.Item i : items) {
            Item state = new Item(null, i.no(), i.dueDate(), i.principalMinor(), i.interestMinor(), i.feeMinor(), 0);
            jdbc.sql("""
                            INSERT INTO lending_schedule_items (id, tenant_id, loan_id, item_no, due_date,
                                principal_due_minor, interest_due_minor, fees_due_minor, status)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            loanId,
                            i.no(),
                            i.dueDate(),
                            i.principalMinor(),
                            i.interestMinor(),
                            i.feeMinor(),
                            state.status(today))
                    .update();
        }
    }

    /** The loan's items in item order, with what has been paid and waived. */
    List<Item> items(UUID loanId) {
        return jdbc.sql("SELECT * FROM lending_schedule_items WHERE loan_id = ? ORDER BY item_no")
                .param(loanId)
                .query((rs, n) -> {
                    Item i = new Item(
                            rs.getObject("id", UUID.class),
                            rs.getInt("item_no"),
                            rs.getObject("due_date", LocalDate.class),
                            rs.getLong("principal_due_minor"),
                            rs.getLong("interest_due_minor"),
                            rs.getLong("fees_due_minor"),
                            rs.getLong("penalties_due_minor"));
                    i.principalPaid = rs.getLong("principal_paid_minor");
                    i.interestPaid = rs.getLong("interest_paid_minor");
                    i.feesPaid = rs.getLong("fees_paid_minor");
                    i.penaltiesPaid = rs.getLong("penalties_paid_minor");
                    i.interestWaived = rs.getLong("interest_waived_minor");
                    i.feesWaived = rs.getLong("fees_waived_minor");
                    i.penaltiesWaived = rs.getLong("penalties_waived_minor");
                    i.paidOn = rs.getObject("paid_on", LocalDate.class);
                    return i;
                })
                .list();
    }

    /** Writes each item's paid, waived and status columns as they stand on {@code today}. */
    void saveItems(List<Item> items, LocalDate today) {
        for (Item i : items) {
            jdbc.sql("""
                            UPDATE lending_schedule_items SET principal_paid_minor = ?, interest_paid_minor = ?,
                                fees_paid_minor = ?, penalties_paid_minor = ?, interest_waived_minor = ?,
                                fees_waived_minor = ?, penalties_waived_minor = ?, status = ?, paid_on = ?,
                                updated_at = now(), version = version + 1
                             WHERE id = ?
                            """)
                    .params(
                            i.principalPaid,
                            i.interestPaid,
                            i.feesPaid,
                            i.penaltiesPaid,
                            i.interestWaived,
                            i.feesWaived,
                            i.penaltiesWaived,
                            i.status(today),
                            i.settled() ? i.paidOn : null,
                            i.id)
                    .update();
        }
    }

    /** FR-LCL-02: each unsettled item records what is written off and becomes {@code written_off}. */
    void writeOffItems(List<Item> items) {
        for (Item i : items) {
            if (i.settled()) {
                continue;
            }
            jdbc.sql("""
                            UPDATE lending_schedule_items SET written_off_minor = ?, status = 'written_off',
                                updated_at = now(), version = version + 1
                             WHERE id = ?
                            """).params(i.unpaidTotal(), i.id).update();
        }
    }

    /** The schedule view rows of FR-DIS-04, with the written-off and status columns as stored. */
    List<ServicingApi.ScheduleRow> scheduleRows(UUID loanId) {
        return jdbc.sql("SELECT * FROM lending_schedule_items WHERE loan_id = ? ORDER BY item_no")
                .param(loanId)
                .query((rs, n) -> {
                    long principalDue = rs.getLong("principal_due_minor");
                    long interestDue = rs.getLong("interest_due_minor");
                    long feesDue = rs.getLong("fees_due_minor");
                    long penaltiesDue = rs.getLong("penalties_due_minor");
                    long principalPaid = rs.getLong("principal_paid_minor");
                    long interestPaid = rs.getLong("interest_paid_minor");
                    long feesPaid = rs.getLong("fees_paid_minor");
                    long penaltiesPaid = rs.getLong("penalties_paid_minor");
                    long waived = rs.getLong("interest_waived_minor")
                            + rs.getLong("fees_waived_minor")
                            + rs.getLong("penalties_waived_minor");
                    long writtenOff = rs.getLong("written_off_minor");
                    long due = principalDue + interestDue + feesDue + penaltiesDue;
                    long paid = principalPaid + interestPaid + feesPaid + penaltiesPaid;
                    return new ServicingApi.ScheduleRow(
                            rs.getInt("item_no"),
                            rs.getObject("due_date", LocalDate.class),
                            principalDue,
                            interestDue,
                            feesDue,
                            penaltiesDue,
                            due,
                            principalPaid,
                            interestPaid,
                            feesPaid,
                            penaltiesPaid,
                            paid,
                            waived,
                            writtenOff,
                            due - paid - waived - writtenOff,
                            rs.getString("status"),
                            rs.getObject("paid_on", LocalDate.class));
                })
                .list();
    }

    // ---- Transactions ---------------------------------------------------------------------

    record Txn(
            UUID id,
            UUID branchId,
            UUID loanId,
            String txnType,
            long amountMinor,
            String currency,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,
            String receiptNo,
            String reason,
            UUID reversesTxnId,
            UUID journalEntryId,
            UUID approvalRequestId,
            String idempotencyKey,
            String source,
            UUID recordedBy,
            Instant createdAt) {}

    void insertTxn(Txn t) {
        jdbc.sql("""
                        INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type, amount_minor,
                            currency, value_date, payment_method_key, external_reference, receipt_no, reason,
                            reverses_txn_id, journal_entry_id, approval_request_id, idempotency_key, source, recorded_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branchId, :loanId, :txnType, :amountMinor,
                            :currency, :valueDate, :paymentMethodKey, :externalReference, :receiptNo, :reason,
                            :reversesTxnId, :journalEntryId, :approvalRequestId, :idempotencyKey, :source, :recordedBy)
                        """).paramSource(t).update();
    }

    Optional<Txn> txn(UUID id) {
        return jdbc.sql("SELECT * FROM lending_loan_transactions WHERE id = ?")
                .param(id)
                .query(ServicingRepository::txn)
                .optional();
    }

    boolean reversed(UUID txnId) {
        return jdbc.sql("SELECT count(*) FROM lending_loan_transactions WHERE reverses_txn_id = ?")
                        .param(txnId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Repayments not reversed, in the order R-ALLOC applies them: value date, then recording order. */
    List<Txn> activeRepayments(UUID loanId) {
        return jdbc.sql("""
                        SELECT t.* FROM lending_loan_transactions t
                         WHERE t.loan_id = ? AND t.txn_type = 'repayment'
                           AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r WHERE r.reverses_txn_id = t.id)
                         ORDER BY t.value_date, t.created_at, t.id
                        """).param(loanId).query(ServicingRepository::txn).list();
    }

    void insertAllocations(UUID transactionId, UUID appliesToTxnId, List<Allocation> rows) {
        for (Allocation a : rows) {
            jdbc.sql("""
                            INSERT INTO lending_repayment_allocations (id, tenant_id, transaction_id, applies_to_txn_id,
                                schedule_item_id, component, amount_minor)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            transactionId,
                            appliesToTxnId,
                            a.itemId(),
                            a.component(),
                            a.amountMinor())
                    .update();
        }
    }

    /** Every allocation row of the loan's repayments, by the repayment whose money it is. */
    Map<UUID, List<Allocation>> allocationsByRepayment(UUID loanId) {
        Map<UUID, List<Allocation>> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT a.applies_to_txn_id, a.schedule_item_id, a.component, a.amount_minor
                          FROM lending_repayment_allocations a
                          JOIN lending_loan_transactions t ON t.id = a.applies_to_txn_id
                         WHERE t.loan_id = ?
                         ORDER BY a.created_at, a.id
                        """)
                .param(loanId)
                .query((rs, n) -> rows.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Allocation(rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4))))
                .list();
        return rows;
    }

    /** The loan's transactions, newest first, each with the allocation rows it wrote. */
    List<LoanTransaction> transactions(UUID loanId) {
        Map<UUID, List<AllocationRow>> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT a.transaction_id, a.applies_to_txn_id, s.item_no, a.component, a.amount_minor
                          FROM lending_repayment_allocations a
                          JOIN lending_loan_transactions t ON t.id = a.transaction_id
                          LEFT JOIN lending_schedule_items s ON s.id = a.schedule_item_id
                         WHERE t.loan_id = ?
                         ORDER BY a.created_at, a.applies_to_txn_id, s.item_no NULLS LAST,
                               array_position(ARRAY['penalty', 'fee', 'interest', 'principal', 'interest_rebate',
                                                    'overpayment'], a.component)
                        """)
                .param(loanId)
                .query((rs, n) -> rows.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new AllocationRow(
                                rs.getObject(2, UUID.class),
                                rs.getObject(3, Integer.class),
                                rs.getString(4),
                                rs.getLong(5))))
                .list();
        return jdbc.sql("""
                        SELECT t.*, r.id AS reversed_by FROM lending_loan_transactions t
                          LEFT JOIN lending_loan_transactions r ON r.reverses_txn_id = t.id
                         WHERE t.loan_id = ?
                         ORDER BY t.created_at DESC, t.id DESC
                        """)
                .param(loanId)
                .query((rs, n) -> {
                    Txn t = txn(rs, n);
                    return new LoanTransaction(
                            t.id(),
                            t.txnType(),
                            t.amountMinor(),
                            t.currency(),
                            t.valueDate(),
                            t.paymentMethodKey(),
                            t.externalReference(),
                            t.receiptNo(),
                            t.reason(),
                            t.reversesTxnId(),
                            rs.getObject("reversed_by", UUID.class),
                            t.journalEntryId(),
                            t.approvalRequestId(),
                            t.recordedBy(),
                            t.createdAt(),
                            List.copyOf(rows.getOrDefault(t.id(), List.of())));
                })
                .list();
    }

    Optional<LoanTransaction> transaction(UUID loanId, UUID txnId) {
        return transactions(loanId).stream().filter(t -> t.id().equals(txnId)).findFirst();
    }

    // ---- Loan balances --------------------------------------------------------------------

    Optional<LoanBalances> balances(UUID loanId) {
        return jdbc.sql("SELECT * FROM lending_loans WHERE id = ?")
                .param(loanId)
                .query((rs, n) -> {
                    long principal = rs.getLong("principal_outstanding_minor");
                    long interest = rs.getLong("interest_outstanding_minor");
                    long fees = rs.getLong("fees_outstanding_minor");
                    long penalties = rs.getLong("penalties_outstanding_minor");
                    return new LoanBalances(
                            rs.getObject("disbursed_on", LocalDate.class),
                            rs.getObject("maturity_date", LocalDate.class),
                            rs.getObject("closed_on", LocalDate.class),
                            rs.getObject("written_off_on", LocalDate.class),
                            rs.getLong("principal_disbursed_minor"),
                            principal,
                            interest,
                            fees,
                            penalties,
                            principal + interest + fees + penalties,
                            rs.getLong("arrears_minor"),
                            rs.getInt("days_past_due"),
                            rs.getObject("next_due_date", LocalDate.class),
                            rs.getObject("last_repayment_on", LocalDate.class),
                            rs.getLong("total_paid_minor"),
                            rs.getLong("credit_balance_minor"));
                })
                .optional();
    }

    /** Disbursement: the dates and the principal; the balances follow from {@link #position}. */
    void disbursed(UUID loanId, LocalDate disbursedOn, LocalDate maturity, long principal) {
        jdbc.sql("""
                        UPDATE lending_loans SET disbursed_on = ?, maturity_date = ?, principal_disbursed_minor = ?,
                            updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(disbursedOn, maturity, principal, loanId).update();
    }

    /** The balances, arrears and DPD of R-DPD as computed from the items. */
    void position(UUID loanId, Position p) {
        jdbc.sql("""
                        UPDATE lending_loans SET principal_outstanding_minor = ?, interest_outstanding_minor = ?,
                            fees_outstanding_minor = ?, penalties_outstanding_minor = ?, arrears_minor = ?,
                            days_past_due = ?, next_due_date = ?, updated_at = now(), version = version + 1
                         WHERE id = ?
                        """)
                .params(
                        p.principalOutstandingMinor(),
                        p.interestOutstandingMinor(),
                        p.feesOutstandingMinor(),
                        p.penaltiesOutstandingMinor(),
                        p.arrearsMinor(),
                        p.daysPastDue(),
                        p.nextDueDate(),
                        loanId)
                .update();
    }

    /** What has been received on the loan, and the credit held for the member (R-ALLOC step 4). */
    void receipts(UUID loanId, long totalPaid, LocalDate lastRepaymentOn, long creditBalance) {
        jdbc.sql("""
                        UPDATE lending_loans SET total_paid_minor = ?, last_repayment_on = ?, credit_balance_minor = ?,
                            updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(totalPaid, lastRepaymentOn, creditBalance, loanId).update();
    }

    /** Closed on a date, or reopened ({@code null}); the status move itself goes through {@link LoanRepository#move}. */
    void closedOn(UUID loanId, LocalDate closedOn) {
        jdbc.sql("UPDATE lending_loans SET closed_on = ? WHERE id = ?")
                .params(closedOn, loanId)
                .update();
    }

    void writtenOffOn(UUID loanId, LocalDate on) {
        jdbc.sql("UPDATE lending_loans SET written_off_on = ? WHERE id = ?")
                .params(on, loanId)
                .update();
    }

    long totalPaid(UUID loanId) {
        return jdbc.sql("SELECT total_paid_minor FROM lending_loans WHERE id = ?")
                .param(loanId)
                .query(Long.class)
                .single();
    }

    long creditBalance(UUID loanId) {
        return jdbc.sql("SELECT credit_balance_minor FROM lending_loans WHERE id = ?")
                .param(loanId)
                .query(Long.class)
                .single();
    }

    Optional<LocalDate> lastRepaymentOn(UUID loanId) {
        return jdbc.sql("""
                        SELECT max(t.value_date) FROM lending_loan_transactions t
                         WHERE t.loan_id = ? AND t.txn_type IN ('repayment', 'recovery')
                           AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r WHERE r.reverses_txn_id = t.id)
                        """)
                .param(loanId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)))
                .single();
    }

    private static Txn txn(ResultSet rs, int n) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new Txn(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("loan_id", UUID.class),
                rs.getString("txn_type"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getObject("value_date", LocalDate.class),
                rs.getString("payment_method_key"),
                rs.getString("external_reference"),
                rs.getString("receipt_no"),
                rs.getString("reason"),
                rs.getObject("reverses_txn_id", UUID.class),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getObject("approval_request_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("source"),
                rs.getObject("recorded_by", UUID.class),
                created == null ? null : created.toInstant());
    }
}
