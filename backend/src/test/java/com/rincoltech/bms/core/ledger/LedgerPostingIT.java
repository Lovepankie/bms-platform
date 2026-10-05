package com.rincoltech.bms.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Money;
import com.rincoltech.bms.kernel.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ledger invariants of ADR-004 and chapter 15 section 15.6, against real PostgreSQL as bms_app. */
class LedgerPostingIT extends IntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 6, 30);

    @Autowired
    LedgerPosting ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        t = TestDatabase.tenant("ledger", true);
    }

    EntryRequest disbursement(List<Line> lines) {
        return new EntryRequest(
                t.headOffice(), DAY, "LN000001", null, "lending", "loan_transaction", UUID.randomUUID(), null, lines);
    }

    <T> T inTenant(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), () -> tx.execute(s -> work.get()));
    }

    @Test
    void aBalancedEntryPostsWithGapFreeNumbers() {
        Money principal = Money.of(1_000_000, "UGX");
        PostedEntry first = inTenant(() -> ledger.post(disbursement(List.of(
                Line.debit(t.account("loans_receivable"), principal).withSubledger("lending.loan", UUID.randomUUID()),
                Line.credit(t.account("cash_on_hand"), principal)))));
        PostedEntry second = inTenant(() -> ledger.post(disbursement(List.of(
                Line.debit(t.account("loans_receivable"), principal),
                Line.credit(t.account("cash_on_hand"), principal)))));

        assertThat(first.entryNo()).isEqualTo("JE00000001");
        assertThat(second.entryNo()).isEqualTo("JE00000002");
        long lines = TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_lines WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(lines).isEqualTo(4);
    }

    /** ADR-004: a correction is a reversal, posted once, with every line turned around. */
    @Test
    void anEntryIsReversedOnceWithItsLinesTurnedAround() {
        Money principal = Money.of(250_000, "UGX");
        PostedEntry original = inTenant(() -> ledger.post(disbursement(List.of(
                Line.debit(t.account("loans_receivable"), principal),
                Line.credit(t.account("cash_on_hand"), principal)))));
        PostedEntry reversal = inTenant(() -> ledger.reverse(
                new LedgerPosting.ReversalRequest(original.entryId(), DAY, "LN000001-R", "Test reversal", null)));
        UUID reverses = TestDatabase.owner()
                .sql("SELECT reverses_entry_id FROM journal_entries WHERE id = ?")
                .param(reversal.entryId())
                .query(UUID.class)
                .single();
        assertThat(reverses).isEqualTo(original.entryId());
        long net = TestDatabase.owner()
                .sql("SELECT sum(debit - credit) FROM journal_lines WHERE tenant_id = ? AND account_id = ?")
                .params(t.tenantId(), t.account("loans_receivable"))
                .query(Long.class)
                .single();
        assertThat(net).isZero();
        assertThatThrownBy(() -> inTenant(() -> ledger.reverse(
                        new LedgerPosting.ReversalRequest(original.entryId(), DAY, "LN000001-R", null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("entry_reversed"));
    }

    @Test
    void anUnbalancedEntryIsRejectedAndNothingIsWritten() {
        assertThatThrownBy(() -> inTenant(() -> ledger.post(disbursement(List.of(
                        Line.debit(t.account("loans_receivable"), Money.of(1_000_000, "UGX")),
                        Line.credit(t.account("cash_on_hand"), Money.of(900_000, "UGX")))))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("unbalanced_entry"));
        assertThat(entries()).isZero();
    }

    /** The deferred trigger rejects an unbalanced entry at commit even when written with raw SQL. */
    @Test
    void theDatabaseRejectsAnUnbalancedEntryAtCommitEvenFromRawSql() {
        UUID period = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> inTenant(() -> {
                    jdbc.sql(
                                    "INSERT INTO gl_periods (id, tenant_id, year, month, status) VALUES (?, ?, 2026, 5, 'open')")
                            .params(period, t.tenantId())
                            .update();
                    jdbc.sql(
                                    "INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id, reference,"
                                            + " source_module, source_type) VALUES (?, ?, ?, 'JE90000001', DATE '2026-05-01', ?, 'RAW', 'test', 'raw')")
                            .params(entry, t.tenantId(), t.headOffice(), period)
                            .update();
                    jdbc.sql(
                                    "INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)"
                                            + " VALUES (?, ?, ?, 1, ?, 500, 0, 'UGX'), (?, ?, ?, 2, ?, 0, 400, 'UGX')")
                            .params(
                                    UUID.randomUUID(),
                                    t.tenantId(),
                                    entry,
                                    t.account("loans_receivable"),
                                    UUID.randomUUID(),
                                    t.tenantId(),
                                    entry,
                                    t.account("cash_on_hand"))
                            .update();
                    return null;
                }))
                .hasStackTraceContaining("is unbalanced");
        assertThat(entries()).isZero();
    }

    @Test
    void anEntryWithoutLinesIsRejectedAtCommit() {
        UUID period = UUID.randomUUID();
        assertThatThrownBy(() -> inTenant(() -> {
                    jdbc.sql(
                                    "INSERT INTO gl_periods (id, tenant_id, year, month, status) VALUES (?, ?, 2026, 4, 'open')")
                            .params(period, t.tenantId())
                            .update();
                    return jdbc.sql(
                                    "INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id,"
                                            + " reference, source_module, source_type) VALUES (?, ?, ?, 'JE90000002', DATE '2026-04-01', ?,"
                                            + " 'RAW', 'test', 'raw')")
                            .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), period)
                            .update();
                }))
                .hasStackTraceContaining("at least two are required");
    }

    @Test
    void headerAccountsAndForeignCurrenciesAreRefused() {
        UUID header = TestDatabase.owner()
                .sql("SELECT id FROM gl_accounts WHERE tenant_id = ? AND code = '1000'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        assertThatThrownBy(() -> inTenant(() -> ledger.post(disbursement(List.of(
                        Line.debit(header, Money.of(100, "UGX")),
                        Line.credit(t.account("cash_on_hand"), Money.of(100, "UGX")))))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("account_not_postable"));
        assertThatThrownBy(() -> inTenant(() -> ledger.post(disbursement(List.of(
                        Line.debit(t.account("loans_receivable"), Money.of(100, "KES")),
                        Line.credit(t.account("cash_on_hand"), Money.of(100, "KES")))))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("currency_mismatch"));
    }

    @Test
    void postingIntoAClosedPeriodIsRefused() {
        TestDatabase.owner()
                .sql("INSERT INTO gl_periods (id, tenant_id, year, month, status) VALUES (?, ?, 2026, 6, 'closed')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        assertThatThrownBy(() -> inTenant(() -> ledger.post(disbursement(List.of(
                        Line.debit(t.account("loans_receivable"), Money.of(100, "UGX")),
                        Line.credit(t.account("cash_on_hand"), Money.of(100, "UGX")))))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("period_closed"));
    }

    @Test
    void postingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> TenantContext.callAs(
                        t.tenantId(),
                        () -> ledger.post(disbursement(List.of(
                                Line.debit(t.account("loans_receivable"), Money.of(100, "UGX")),
                                Line.credit(t.account("cash_on_hand"), Money.of(100, "UGX")))))))
                .hasMessageContaining("mandatory");
    }

    long entries() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }
}
