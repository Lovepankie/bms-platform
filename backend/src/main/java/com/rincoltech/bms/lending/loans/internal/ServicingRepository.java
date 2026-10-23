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
import java.time.OffsetDateTime;
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
        List<Item> items = jdbc.sql("SELECT * FROM lending_schedule_items WHERE loan_id = ? ORDER BY item_no")
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
        Map<UUID, Item> byId = new LinkedHashMap<>();
        items.forEach(i -> byId.put(i.id, i));
        jdbc.sql("""
                        SELECT schedule_item_id, business_date,
                               sum(amount_minor - cancelled_minor - waived_minor) FROM lending_loan_charges
                         WHERE loan_id = ? GROUP BY 1, 2
                        """)
                .param(loanId)
                .query((rs, n) -> {
                    Item i = byId.get(rs.getObject(1, UUID.class));
                    if (i != null) {
                        i.chargedOn(rs.getObject(2, LocalDate.class), rs.getLong(3));
                    }
                    return null;
                })
                .list();
        return items;
    }

    /** One row of a charged period: a first charge or a charge again, with what stands of it. */
    record PeriodRow(
            UUID id,
            UUID itemId,
            int periodNo,
            LocalDate date,
            boolean recharge,
            long amountMinor,
            long cancelledMinor,
            long waivedMinor) {

        long cancellable() {
            return amountMinor - cancelledMinor - waivedMinor;
        }
    }

    /** The loan's penalty rows, each charged period's first charge before its charges again, the newest last. */
    List<PeriodRow> penaltyRows(UUID loanId) {
        return jdbc.sql("""
                        SELECT id, schedule_item_id, period_no, business_date, charge_type = 'penalty_recharge',
                               amount_minor, cancelled_minor, waived_minor
                          FROM lending_loan_charges WHERE loan_id = ?
                         ORDER BY schedule_item_id, period_no, charge_type = 'penalty_recharge', created_at, id
                        """)
                .param(loanId)
                .query((rs, n) -> new PeriodRow(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getInt(3),
                        rs.getObject(4, LocalDate.class),
                        rs.getBoolean(5),
                        rs.getLong(6),
                        rs.getLong(7),
                        rs.getLong(8)))
                .list();
    }

    /**
     * What each item's standing payments paid of its principal and interest ({@code [0]}, the early settlement rebate
     * counted) and of its fees ({@code [1]}), by the value date of the payment the rows apply to: a reversed payment
     * nets to nothing, a re-allocated one counts as re-allocated.
     */
    Map<UUID, java.util.NavigableMap<LocalDate, long[]>> contractPaidByValueDate(UUID loanId) {
        Map<UUID, java.util.NavigableMap<LocalDate, long[]>> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT a.schedule_item_id, t.value_date,
                               coalesce(sum(a.amount_minor) FILTER (WHERE a.component IN
                                   ('principal', 'interest', 'interest_rebate')), 0),
                               coalesce(sum(a.amount_minor) FILTER (WHERE a.component = 'fee'), 0)
                          FROM lending_repayment_allocations a
                          JOIN lending_loan_transactions t ON t.id = a.applies_to_txn_id
                         WHERE t.loan_id = ? AND a.schedule_item_id IS NOT NULL
                         GROUP BY 1, 2
                        """)
                .param(loanId)
                .query((rs, n) -> out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.TreeMap<>())
                        .put(rs.getObject(2, LocalDate.class), new long[] {rs.getLong(3), rs.getLong(4)}))
                .list();
        return out;
    }

    /** Cancels part of one charge row, dated today, caused by a repayment or a reversal, timed with that transaction. */
    void cancelCharge(PeriodRow row, UUID loanId, long amountMinor, LocalDate today, String reason, UUID causeTxnId) {
        jdbc.sql("""
                        INSERT INTO lending_charge_cancels (id, tenant_id, created_at, loan_id, charge_id, amount_minor,
                            business_date, reason, cause_txn_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid,
                                (SELECT created_at FROM lending_loan_transactions WHERE id = ?), ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), causeTxnId, loanId, row.id(), amountMinor, today, reason, causeTxnId)
                .update();
        jdbc.sql("UPDATE lending_loan_charges SET cancelled_minor = cancelled_minor + ? WHERE id = ?")
                .params(amountMinor, row.id())
                .update();
        penaltiesDueChanged(row.itemId(), -amountMinor);
    }

    /** Charges a period again, as a new row dated the period's date and timed with the reversal that caused it. */
    void rechargePeriod(PeriodRow first, UUID loanId, long amountMinor, UUID causeTxnId) {
        jdbc.sql("""
                        INSERT INTO lending_loan_charges (id, tenant_id, created_at, loan_id, schedule_item_id, charge_type,
                            period_no, recharge_of, business_date, amount_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid,
                                (SELECT created_at FROM lending_loan_transactions WHERE id = ?), ?, ?, 'penalty_recharge',
                                ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        causeTxnId,
                        loanId,
                        first.itemId(),
                        first.periodNo(),
                        first.id(),
                        first.date(),
                        amountMinor)
                .update();
        penaltiesDueChanged(first.itemId(), amountMinor);
    }

    private void penaltiesDueChanged(UUID itemId, long delta) {
        jdbc.sql("UPDATE lending_schedule_items SET penalties_due_minor = penalties_due_minor + ?,"
                        + " updated_at = now() WHERE id = ?")
                .params(delta, itemId)
                .update();
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

    /** The nightly run's write (FR-ARR-01): each item's status as it stands on {@code today}, only where it moved. */
    void saveStatuses(List<Item> items, LocalDate today) {
        for (Item i : items) {
            jdbc.sql("""
                            UPDATE lending_schedule_items SET status = ?, updated_at = now(), version = version + 1
                             WHERE id = ? AND status IS DISTINCT FROM ?
                            """).params(i.status(today), i.id, i.status(today)).update();
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
                        INSERT INTO lending_loan_transactions (id, tenant_id, created_at, branch_id, loan_id, txn_type,
                            amount_minor, currency, value_date, payment_method_key, external_reference, receipt_no, reason,
                            reverses_txn_id, journal_entry_id, approval_request_id, idempotency_key, source, recorded_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, clock_timestamp(), :branchId, :loanId,
                            :txnType, :amountMinor,
                            :currency, :valueDate, :paymentMethodKey, :externalReference, :receiptNo, :reason,
                            :reversesTxnId, :journalEntryId, :approvalRequestId, :idempotencyKey, :source, :recordedBy)
                        """).paramSource(t).update();
    }

    /**
     * A disbursement or repayment from before go-live, brought in from the register (ADR-071): {@code is_historic}, with no
     * journal, receipt or voucher number, because its cash was never this app's.
     */
    void insertHistoricTxn(Txn t) {
        jdbc.sql("""
                        INSERT INTO lending_loan_transactions (id, tenant_id, created_at, branch_id, loan_id, txn_type,
                            amount_minor, currency, value_date, payment_method_key, external_reference, reason,
                            reverses_txn_id, approval_request_id, source, is_historic, recorded_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, clock_timestamp(), :branchId, :loanId,
                            :txnType, :amountMinor, :currency, :valueDate, :paymentMethodKey, :externalReference, :reason,
                            :reversesTxnId, :approvalRequestId, :source, true, :recordedBy)
                        """).paramSource(t).update();
    }

    /** The historic disbursement and repayments of a brought-in loan not yet reversed, in recording order (ADR-071). */
    List<Txn> historicNotReversed(UUID loanId) {
        return jdbc.sql("""
                        SELECT * FROM lending_loan_transactions t
                         WHERE t.loan_id = ? AND t.is_historic AND t.txn_type IN ('disbursement', 'repayment')
                           AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r WHERE r.reverses_txn_id = t.id)
                         ORDER BY t.created_at, t.id
                        """).param(loanId).query(ServicingRepository::txn).list();
    }

    /** The repayment a gateway payment reference (order tracking id) already booked, if any (spec 3.4.1: idempotent at the port). */
    Optional<Txn> gatewayRepayment(String externalReference) {
        return jdbc.sql("""
                        SELECT * FROM lending_loan_transactions
                         WHERE payment_method_key = 'gateway' AND external_reference = ?
                           AND txn_type IN ('repayment', 'recovery')
                        """)
                .param(externalReference)
                .query(ServicingRepository::txn)
                .optional();
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

    /** True for a transaction brought in from the paper register (ADR-071). */
    boolean historic(UUID txnId) {
        return jdbc.sql("SELECT is_historic FROM lending_loan_transactions WHERE id = ?")
                .param(txnId)
                .query(Boolean.class)
                .single();
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

    /**
     * The loan's penalty charges (R-PEN), their waivers (FR-ARR-04) and their cancels with their dates, for the
     * statement and the receipts (ADR-034).
     */
    List<LoanLedger.PenaltyEvent> penaltyEvents(UUID loanId) {
        return jdbc.sql("""
                        SELECT s.item_no,
                               -- A charge again is printed on the day it was recorded, as its cancel was; its period
                               -- date serves the penalty logic only (the delta review of #270).
                               CASE c.charge_type WHEN 'penalty' THEN c.business_date
                                    ELSE (c.created_at AT TIME ZONE (SELECT t.timezone FROM tenants t
                                                                      WHERE t.id = c.tenant_id))::date END,
                               c.created_at, c.amount_minor,
                               CASE c.charge_type WHEN 'penalty' THEN 'CHARGED' ELSE 'RECHARGED' END
                          FROM lending_loan_charges c
                          JOIN lending_schedule_items s ON s.id = c.schedule_item_id
                         WHERE c.loan_id = ?
                        UNION ALL
                        SELECT s.item_no, x.business_date, x.created_at, x.amount_minor, 'CANCELLED'
                          FROM lending_charge_cancels x
                          JOIN lending_loan_charges c ON c.id = x.charge_id
                          JOIN lending_schedule_items s ON s.id = c.schedule_item_id
                         WHERE x.loan_id = ?
                        UNION ALL
                        SELECT s.item_no, w.business_date, w.created_at, w.amount_minor, 'WAIVED'
                          FROM lending_charge_waivers w
                          JOIN lending_loan_charges c ON c.id = w.charge_id
                          JOIN lending_schedule_items s ON s.id = c.schedule_item_id
                         WHERE w.loan_id = ?
                         ORDER BY 2, 3, 1
                        """)
                .params(loanId, loanId, loanId)
                .query((rs, n) -> new LoanLedger.PenaltyEvent(
                        rs.getInt(1),
                        rs.getObject(2, LocalDate.class),
                        rs.getObject(3, OffsetDateTime.class).toInstant(),
                        rs.getLong(4),
                        LoanLedger.PenaltyEvent.Kind.valueOf(rs.getString(5))))
                .list();
    }

    /** One charge on a loan (R-PEN, FR-ARR-04). */
    record Charge(
            UUID id,
            UUID loanId,
            UUID itemId,
            int itemNo,
            String chargeType,
            Integer periodNo,
            LocalDate businessDate,
            long amountMinor,
            long waivedMinor,
            long cancelledMinor) {}

    private static final String CHARGE = """
            SELECT c.id, c.loan_id, c.schedule_item_id, s.item_no, c.charge_type, c.period_no, c.business_date,
                   c.amount_minor, c.waived_minor, c.cancelled_minor
              FROM lending_loan_charges c
              JOIN lending_schedule_items s ON s.id = c.schedule_item_id
            """;

    private static Charge charge(ResultSet rs, int n) throws SQLException {
        return new Charge(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getInt(4),
                rs.getString(5),
                rs.getObject(6, Integer.class),
                rs.getObject(7, LocalDate.class),
                rs.getLong(8),
                rs.getLong(9),
                rs.getLong(10));
    }

    Optional<Charge> charge(UUID chargeId) {
        return jdbc.sql(CHARGE + " WHERE c.id = ?")
                .param(chargeId)
                .query(ServicingRepository::charge)
                .optional();
    }

    /** Everything waived on the loan so far, for the waiver threshold (FR-APR-04). */
    long waivedOnLoan(UUID loanId) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM lending_charge_waivers WHERE loan_id = ?")
                .param(loanId)
                .query(Long.class)
                .single();
    }

    /** The loan's charges with something standing, oldest first; one cancelled in full shows on the statement only. */
    List<Charge> charges(UUID loanId) {
        return jdbc.sql(CHARGE + " WHERE c.loan_id = ? AND c.amount_minor > c.cancelled_minor"
                        + " ORDER BY c.business_date, s.item_no, c.period_no")
                .param(loanId)
                .query(ServicingRepository::charge)
                .list();
    }

    /** FR-ARR-04: records an approved waiver and adds it to the charge's waived amount. */
    void insertWaiver(
            UUID id,
            Charge charge,
            long amountMinor,
            LocalDate businessDate,
            String reason,
            UUID approvalId,
            UUID requestedBy,
            UUID approvedBy) {
        jdbc.sql("""
                        INSERT INTO lending_charge_waivers (id, tenant_id, loan_id, charge_id, amount_minor, business_date,
                            reason, approval_request_id, requested_by, approved_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        charge.loanId(),
                        charge.id(),
                        amountMinor,
                        businessDate,
                        reason,
                        approvalId,
                        requestedBy,
                        approvedBy)
                .update();
        jdbc.sql("UPDATE lending_loan_charges SET waived_minor = waived_minor + ? WHERE id = ?")
                .params(amountMinor, charge.id())
                .update();
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
                            List.copyOf(rows.getOrDefault(t.id(), List.of())),
                            rs.getBoolean("is_historic"));
                })
                .list();
    }

    Optional<LoanTransaction> transaction(UUID loanId, UUID txnId) {
        return transactions(loanId).stream().filter(t -> t.id().equals(txnId)).findFirst();
    }

    // ---- Loan balances --------------------------------------------------------------------

    Optional<LoanBalances> balances(UUID loanId) {
        return jdbc.sql("""
                        SELECT l.*, (SELECT coalesce(sum(c.amount_minor - c.cancelled_minor), 0) FROM lending_loan_charges c
                                      WHERE c.loan_id = l.id) AS penalties_assessed
                          FROM lending_loans l WHERE l.id = ?
                        """)
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
                            rs.getLong("credit_balance_minor"),
                            ServicingApi.parBucket(rs.getInt("days_past_due")),
                            rs.getLong("penalties_assessed"));
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

    /**
     * The nightly recompute (FR-ARR-01): writes the position only when it moved, and keeps the loan's version, so a
     * rerun writes nothing and a pending approval is not made stale overnight (ADR-015 addendum of 2026-10-22).
     */
    void positionIfChanged(UUID loanId, Position p) {
        jdbc.sql("""
                        UPDATE lending_loans SET principal_outstanding_minor = ?, interest_outstanding_minor = ?,
                            fees_outstanding_minor = ?, penalties_outstanding_minor = ?, arrears_minor = ?,
                            days_past_due = ?, next_due_date = ?, updated_at = now()
                         WHERE id = ?
                           AND (principal_outstanding_minor, interest_outstanding_minor, fees_outstanding_minor,
                                penalties_outstanding_minor, arrears_minor, days_past_due, next_due_date)
                               IS DISTINCT FROM (?, ?, ?, ?, ?, ?, ?::date)
                        """)
                .params(
                        p.principalOutstandingMinor(),
                        p.interestOutstandingMinor(),
                        p.feesOutstandingMinor(),
                        p.penaltiesOutstandingMinor(),
                        p.arrearsMinor(),
                        p.daysPastDue(),
                        p.nextDueDate(),
                        loanId,
                        p.principalOutstandingMinor(),
                        p.interestOutstandingMinor(),
                        p.feesOutstandingMinor(),
                        p.penaltiesOutstandingMinor(),
                        p.arrearsMinor(),
                        p.daysPastDue(),
                        p.nextDueDate())
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
