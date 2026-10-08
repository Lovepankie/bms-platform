package com.rincoltech.bms.lending.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The fabricated lending seed (#108, ADR-026): it fills an empty tenant with the promised counts,
 * every money event balanced and the subledger equal to the control account, and it refuses a
 * tenant that holds data or was seeded before.
 */
class LendingSeedIT extends IntegrationTest {

    @Autowired
    LendingSeeder seeder;

    static long count(UUID tenant, String sql) {
        return TestDatabase.owner().sql(sql).param(tenant).query(Long.class).single();
    }

    @Test
    void seedsAnEmptyTenantOnceWithBalancedBooks() {
        TestDatabase.Fixture t = TestDatabase.tenant("seed", true);

        LendingSeeder.Report report = seeder.seed(t.slug());

        assertThat(report.render()).contains("15 members", "4 products", "12 applications", "6 disbursed loans");
        assertThat(count(t.tenantId(), "SELECT count(*) FROM lending_members WHERE tenant_id = ?"))
                .isEqualTo(15);
        assertThat(count(t.tenantId(), "SELECT count(*) FROM lending_loan_products WHERE tenant_id = ?"))
                .isEqualTo(4);
        assertThat(count(t.tenantId(), "SELECT count(*) FROM lending_loans WHERE tenant_id = ?"))
                .isEqualTo(18);
        assertThat(count(
                        t.tenantId(),
                        "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND disbursed_on IS NOT NULL"))
                .isEqualTo(6);
        List<String> statuses = TestDatabase.owner()
                .sql("SELECT DISTINCT status FROM lending_loans WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(statuses)
                .contains("draft", "submitted", "appraised", "approved", "rejected", "cancelled", "active", "closed");
        // The bullet loan was paid off with 25,000 over: a closed loan holding the member's credit.
        assertThat(
                        count(
                                t.tenantId(),
                                "SELECT sum(credit_balance_minor) FROM lending_loans WHERE tenant_id = ? AND status = 'closed'"))
                .isEqualTo(25_000);
        assertThat(
                        count(
                                t.tenantId(),
                                "SELECT count(*) FROM lending_loan_transactions WHERE tenant_id = ? AND txn_type = 'repayment'"))
                .isEqualTo(report.repayments());
        // The trial balance balances and loans receivable equals the principal outstanding, loan by loan.
        assertThat(count(t.tenantId(), "SELECT sum(debit) - sum(credit) FROM journal_lines WHERE tenant_id = ?"))
                .isZero();
        assertThat(count(t.tenantId(), """
                        SELECT count(*) FROM lending_loans l WHERE l.tenant_id = ? AND l.principal_outstanding_minor <>
                            (SELECT coalesce(sum(j.debit - j.credit), 0) FROM journal_lines j
                               JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'loans_receivable'
                              WHERE j.subledger_id = l.id)
                        """)).isZero();
        assertThat(
                        count(
                                t.tenantId(),
                                "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND full_name NOT LIKE 'Test Borrower %'"))
                .isZero();
        // Investments (#152): three products, ten placed over twelve months and one waiting for funding.
        assertThat(report.render()).contains("3 investment products", "10 investments");
        List<String> investmentStatuses = TestDatabase.owner()
                .sql("SELECT DISTINCT status FROM lending_investments WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(investmentStatuses).contains("pending_funding", "active", "matured", "paid_out", "rolled_over");
        // The monthly income deposit had ten returns collected, one a month.
        assertThat(
                        count(
                                t.tenantId(),
                                "SELECT count(*) FROM lending_investment_transactions WHERE tenant_id = ? AND txn_type = 'return_payout'"))
                .isEqualTo(10);
        // Every accrual was posted on its own period's end date, never twice.
        assertThat(count(t.tenantId(), """
                        SELECT count(*) FROM lending_investment_transactions t
                          JOIN lending_investment_schedule_items s ON s.accrued_txn_id = t.id
                         WHERE t.tenant_id = ? AND t.value_date <> s.period_end
                        """)).isZero();
        assertThat(count(t.tenantId(), """
                        SELECT count(*) FROM lending_investments i WHERE i.tenant_id = ? AND (i.principal_held_minor <>
                            (SELECT coalesce(sum(j.credit - j.debit), 0) FROM journal_lines j
                               JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'investments_payable'
                              WHERE j.subledger_id = i.id)
                          OR i.return_accrued_minor - i.return_paid_minor <>
                            (SELECT coalesce(sum(j.credit - j.debit), 0) FROM journal_lines j
                               JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'investment_returns_payable'
                              WHERE j.subledger_id = i.id))
                        """)).isZero();

        // Savings (#151): eleven accounts on three products, one member holding two, a year of movements.
        assertThat(report.render()).contains("11 savings accounts");
        assertThat(count(t.tenantId(), "SELECT count(*) FROM lending_savings_accounts WHERE tenant_id = ?"))
                .isEqualTo(11);
        assertThat(count(
                        t.tenantId(),
                        "SELECT count(*) FROM lending_savings_transactions WHERE tenant_id = ? AND txn_type IN"
                                + " ('deposit', 'withdrawal')"))
                .isEqualTo(report.savingsMovements());
        assertThat(count(
                        t.tenantId(),
                        "SELECT max(n) FROM (SELECT count(*) AS n FROM lending_savings_accounts WHERE tenant_id = ?"
                                + " GROUP BY member_id) x"))
                .isEqualTo(2);
        assertThat(
                        count(
                                t.tenantId(),
                                "SELECT count(*) FROM lending_savings_interest_postings WHERE tenant_id = ? AND interest_minor > 0"))
                .isGreaterThan(20);
        assertThat(count(
                        t.tenantId(),
                        "SELECT count(*) FROM lending_savings_accounts WHERE tenant_id = ? AND status = 'dormant'"))
                .isEqualTo(1);
        assertThat(count(t.tenantId(), """
                        SELECT count(*) FROM lending_savings_accounts s WHERE s.tenant_id = ? AND s.balance_minor <>
                            (SELECT coalesce(sum(j.credit - j.debit), 0) FROM journal_lines j
                               JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'member_savings'
                              WHERE j.subledger_id = s.id)
                        """)).isZero();
        // The end of day ran through yesterday for every account, and the seed sent no SMS.
        assertThat(count(t.tenantId(), """
                        SELECT count(*) FROM lending_savings_accounts WHERE tenant_id = ?
                           AND balances_through <> (now() AT TIME ZONE 'Africa/Kampala')::date - 1
                        """)).isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ?")
                        .param("lending.savings.sms:" + t.tenantId() + "%")
                        .query(Long.class)
                        .single())
                .isZero();

        assertThatThrownBy(() -> seeder.seed(t.slug()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seeded before");
    }

    @Test
    void refusesATenantThatAlreadyHoldsData() {
        TestDatabase.Fixture t = TestDatabase.tenant("seed-real", true);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                            currency, kyc_status, status, source)
                        VALUES (?, ?, ?, 'M000001', 'Test Borrower 01', '+256700000001', 'none', 'UGX', 'verified',
                            'active', 'staff')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice())
                .update();

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = LendingSeedCommand.run(
                seeder,
                t.slug(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("already holds");
        assertThat(count(t.tenantId(), "SELECT count(*) FROM lending_members WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(count(t.tenantId(), "SELECT count(*) FROM lending_loan_products WHERE tenant_id = ?"))
                .isZero();
    }

    @Test
    void theCommandNeedsATenant() {
        assertThatThrownBy(() -> LendingSeedCommand.parse(new String[] {LendingSeedCommand.NAME}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(LendingSeedCommand.parse(new String[] {LendingSeedCommand.NAME, "--tenant", "pilot"}))
                .isEqualTo("pilot");
    }
}
