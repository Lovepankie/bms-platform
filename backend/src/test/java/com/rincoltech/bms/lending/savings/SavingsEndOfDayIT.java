package com.rincoltech.bms.lending.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-SAV-05 on real PostgreSQL: the nightly end of day writes one end-of-day balance per account
 * and day, posts each period's interest once (golden amounts worked by hand, to the unit), is
 * idempotent however often it runs, refuses movements dated into a closed day, and posts on the run
 * date when the period end's accounting month is closed. Fixed past dates through
 * {@link SavingsServicing}, as the seed and the job use it. All figures fabricated.
 */
class SavingsEndOfDayIT extends LoanFixtures {

    @Autowired
    SavingsServicing servicing;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    BusinessClock clock;

    final UUID cashier = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();

    static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    <T> T inTenant(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tenantJobs.callAsTenant(t.slug(), "lending", () -> tx.execute(s -> work.get()));
    }

    UUID product(String code, String calc, int rateBp, String posting) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test " + code);
        terms.put("interest_rate_bp", rateBp);
        terms.put("interest_calc", calc);
        terms.put("interest_posting", posting);
        return UUID.fromString(send(
                        HttpMethod.POST,
                        "/api/v1/lending/savings-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", code, "terms", terms))
                .getBody()
                .get("id")
                .asString());
    }

    UUID member(String n) {
        return UUID.fromString(member("Test Saver " + n, "+2567000003" + n, t.headOffice(), true));
    }

    List<Long> postings(UUID account) {
        return TestDatabase.owner()
                .sql(
                        "SELECT interest_minor FROM lending_savings_interest_postings WHERE account_id = ? ORDER BY period_end")
                .param(account)
                .query(Long.class)
                .list();
    }

    long dayBalance(UUID account, String day) {
        return TestDatabase.owner()
                .sql(
                        "SELECT closing_balance_minor FROM lending_savings_daily_balances WHERE account_id = ? AND business_date = ?")
                .params(account, d(day))
                .query(Long.class)
                .single();
    }

    /**
     * daily_balance at 12% a year, monthly. June 2025: 10 days at 100,000, 10 at 250,000, 10 at
     * 175,000: 5,250,000 x 1,200 / 3,650,000 = 1,726.03, posted 1,726 on 30 June. July: 176,726 for
     * 31 days: 5,478,506 x 1,200 / 3,650,000 = 1,801.15, posted 1,801 on 31 July.
     */
    @Test
    void frSav05_dailyBalanceInterestPostsOncePerMonthToTheUnit() {
        UUID product = product("DAILY", "daily_balance", 1_200, "monthly");
        UUID member = member("01");
        UUID account = inTenant(() -> {
            UUID id = servicing.openAccount(member, product, d("2025-06-01"), officer);
            servicing.deposit(id, 100_000, d("2025-06-01"), "cash", cashier);
            servicing.endOfDay(d("2025-06-10"));
            servicing.deposit(id, 150_000, d("2025-06-11"), "bank", cashier);
            servicing.endOfDay(d("2025-06-20"));
            servicing.withdraw(id, 75_000, d("2025-06-21"), "cash", cashier, manager);
            assertThat(servicing.endOfDay(d("2025-07-31"))).isEqualTo(2);
            return id;
        });
        assertThat(postings(account)).containsExactly(1_726L, 1_801L);
        assertThat(dayBalance(account, "2025-06-29")).isEqualTo(175_000);
        // The period end's balance includes the interest credited that day.
        assertThat(dayBalance(account, "2025-06-30")).isEqualTo(176_726);
        assertThat(dayBalance(account, "2025-07-31")).isEqualTo(178_527);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_savings_daily_balances WHERE account_id = ?")
                        .param(account)
                        .query(Long.class)
                        .single())
                .isEqualTo(61);
        assertThat(TestDatabase.owner()
                        .sql("SELECT balance_minor FROM lending_savings_accounts WHERE id = ?")
                        .param(account)
                        .query(Long.class)
                        .single())
                .isEqualTo(178_527);

        // Idempotent: running again, or for an earlier day, posts nothing more.
        assertThat(inTenant(() -> servicing.endOfDay(d("2025-07-31")))).isZero();
        assertThat(inTenant(() -> servicing.endOfDay(d("2025-06-30")))).isZero();
        assertThat(postings(account)).hasSize(2);

        // A movement dated into a day the end of day has written is refused.
        assertThatThrownBy(() -> inTenant(() -> servicing.deposit(account, 1_000, d("2025-07-15"), "cash", cashier)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("value_date_closed");
    }

    /**
     * minimum_monthly_balance at 8% a year, quarterly, opened 1 July 2025: lowest balances 200,000,
     * 150,000 (17 August) and 300,000; (650,000 x 800) / 120,000 = 4,333.33, posted 4,333 on 30
     * September, once.
     */
    @Test
    void frSav05_minimumMonthlyBalanceInterestPostsAtTheQuarterEnd() {
        UUID product = product("MINMON", "minimum_monthly_balance", 800, "quarterly");
        UUID member = member("02");
        UUID account = inTenant(() -> {
            UUID id = servicing.openAccount(member, product, d("2025-07-01"), officer);
            servicing.deposit(id, 200_000, d("2025-07-01"), "cash", cashier);
            servicing.endOfDay(d("2025-08-16"));
            servicing.withdraw(id, 50_000, d("2025-08-17"), "cash", cashier, manager);
            servicing.endOfDay(d("2025-08-17"));
            servicing.deposit(id, 30_000, d("2025-08-18"), "cash", cashier);
            servicing.endOfDay(d("2025-08-31"));
            servicing.deposit(id, 120_000, d("2025-09-01"), "cash", cashier);
            servicing.endOfDay(d("2025-09-29"));
            return id;
        });
        assertThat(postings(account)).isEmpty();
        inTenant(() -> servicing.endOfDay(d("2025-10-31")));
        assertThat(postings(account)).containsExactly(4_333L);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT a.system_key || ' ' || l.debit || ' ' || l.credit FROM lending_savings_transactions t
                                  JOIN journal_lines l ON l.entry_id = t.journal_entry_id
                                  JOIN gl_accounts a ON a.id = l.account_id
                                 WHERE t.account_id = ? AND t.txn_type = 'interest' ORDER BY l.line_no
                                """)
                        .param(account)
                        .query(String.class)
                        .list())
                .containsExactly("savings_interest_expense 4333 0", "member_savings 0 4333");
    }

    /** Period aware: interest for a period end in a closed accounting month posts on the run date. */
    @Test
    void frSav05_aClosedMonthPostsTheInterestOnTheRunDate() {
        UUID product = product("CLOSEDP", "daily_balance", 3_650, "monthly");
        UUID member = member("03");
        UUID account = inTenant(() -> {
            UUID id = servicing.openAccount(member, product, d("2025-03-01"), officer);
            servicing.deposit(id, 10_000, d("2025-03-01"), "cash", cashier);
            return id;
        });
        TestDatabase.owner()
                .sql("INSERT INTO gl_periods (id, tenant_id, year, month, status) VALUES (?, ?, 2025, 3, 'closed')"
                        + " ON CONFLICT (tenant_id, year, month) DO UPDATE SET status = 'closed'")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        inTenant(() -> servicing.endOfDay(d("2025-03-31")));
        // 31 days x 10,000 x 3,650 / 3,650,000 = 310, dated 31 March on the account, booked today.
        assertThat(postings(account)).containsExactly(310L);
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT t.value_date, e.entry_date FROM lending_savings_transactions t
                          JOIN journal_entries e ON e.id = t.journal_entry_id
                         WHERE t.account_id = ? AND t.txn_type = 'interest'
                        """).param(account).query().singleRow();
        assertThat(row.get("value_date").toString()).isEqualTo("2025-03-31");
        assertThat(row.get("entry_date").toString())
                .isEqualTo(clock.today(BusinessClock.DEFAULT_ZONE).toString());
    }
}
