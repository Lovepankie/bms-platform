package com.rincoltech.bms.retail.cashbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * FR-RET-26 and FR-RET-28 at the database: connected as {@code bms_app} (never the owner), a cash
 * book record can be voided once and nothing else about it can change, no row can be deleted, the
 * stored {@code repaid_minor} of an advance cannot be set to anything but the sum of its non-voided
 * repayments, and a category cannot name a header, asset or inactive account. Fabricated rows.
 */
class RetailCashbookGuardsIT {

    static TestDatabase.Fixture t;
    static UUID user = UUID.randomUUID();
    static UUID category = UUID.randomUUID();
    static UUID item = UUID.randomUUID();
    static UUID party = UUID.randomUUID();

    @BeforeAll
    static void seed() {
        t = TestDatabase.tenant("cb-guards", false, true);
        var owner = TestDatabase.owner();
        owner.sql("INSERT INTO retail_expense_categories (id, tenant_id, name) VALUES (?, ?, 'Test Category 01')")
                .params(category, t.tenantId())
                .update();
        owner.sql(
                        "INSERT INTO retail_expense_items (id, tenant_id, category_id, name) VALUES (?, ?, ?, 'Test Item 01')")
                .params(item, t.tenantId(), category)
                .update();
        owner.sql("INSERT INTO retail_cash_parties (id, tenant_id, name, kind) VALUES (?, ?, 'Test Owner 01', 'owner')")
                .params(party, t.tenantId())
                .update();
    }

    interface Work {
        void run(Connection c) throws SQLException;
    }

    /** One bms_app transaction bound to the tenant, committed (a deferred check fires at commit). */
    static void asApp(Work work) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, t.tenantId().toString());
                ps.execute();
            }
            try {
                work.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    static int daysAgo = 10;

    UUID savings(long amount) throws SQLException {
        return savings(amount, daysAgo++);
    }

    UUID savings(long amount, int ago) throws SQLException {
        UUID id = UUID.randomUUID();
        asApp(c -> exec(c, """
                        INSERT INTO retail_daily_savings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                                                          occurred_at, recorded_by)
                        VALUES (?, ?, ?, CURRENT_DATE - ?, ?, 'UGX', now(), ?)
                        """, id, t.tenantId(), t.headOffice(), ago, amount, user));
        return id;
    }

    UUID advance(long principal) throws SQLException {
        UUID id = UUID.randomUUID();
        asApp(c -> exec(
                c,
                """
                        INSERT INTO retail_advances (id, tenant_id, advance_no, branch_id, party_id, principal_minor, currency,
                                                     business_date, occurred_at, recorded_by)
                        VALUES (?, ?, ?, ?, ?, ?, 'UGX', CURRENT_DATE - 3, now(), ?)
                        """,
                id,
                t.tenantId(),
                "RA" + id.toString().substring(0, 8),
                t.headOffice(),
                party,
                principal,
                user));
        return id;
    }

    @Test
    void anAmountCannotBeChangedButTheVoidIsAllowedOnce() throws SQLException {
        UUID id = savings(1_000);

        assertThatThrownBy(() ->
                        asApp(c -> exec(c, "UPDATE retail_daily_savings SET amount_minor = 2000 WHERE id = ?", id)))
                .hasMessageContaining("only the void may change");
        assertThatThrownBy(() -> asApp(
                        c -> exec(c, "UPDATE retail_daily_savings SET business_date = CURRENT_DATE WHERE id = ?", id)))
                .hasMessageContaining("only the void may change");

        asApp(c -> exec(
                c,
                "UPDATE retail_daily_savings SET voided_at = now(), voided_by = ?, void_reason = 'Entered twice' WHERE id = ?",
                user,
                id));
        assertThatThrownBy(() -> asApp(c ->
                        exec(c, "UPDATE retail_daily_savings SET void_reason = 'Another reason' WHERE id = ?", id)))
                .hasMessageContaining("only the void may change");
        assertThatThrownBy(() -> asApp(c -> exec(c, "DELETE FROM retail_daily_savings WHERE id = ?", id)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aVoidNeedsItsReasonAndItsActorTogether() throws SQLException {
        UUID id = savings(500);

        assertThatThrownBy(
                        () -> asApp(c -> exec(c, "UPDATE retail_daily_savings SET voided_at = now() WHERE id = ?", id)))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> asApp(c -> exec(
                        c,
                        "UPDATE retail_daily_savings SET voided_at = now(), voided_by = ?, void_reason = ' ' WHERE id = ?",
                        user,
                        id)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void onlyOneActiveSavingsRecordExistsPerBranchAndDateAndAVoidFreesTheDay() throws SQLException {
        UUID first = savings(100, 5);
        assertThatThrownBy(() -> savings(200, 5)).hasMessageContaining("retail_daily_savings_active");
        asApp(c -> exec(
                c,
                "UPDATE retail_daily_savings SET voided_at = now(), voided_by = ?, void_reason = 'Wrong day' WHERE id = ?",
                user,
                first));
        assertThat(savings(200, 5)).isNotNull();
    }

    @Test
    void anOverwrittenAmountNeedsASuggestionAndAReason() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> asApp(c -> exec(c, """
                        INSERT INTO retail_daily_savings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                                                          suggested_minor, overwritten, occurred_at, recorded_by)
                        VALUES (?, ?, ?, CURRENT_DATE - 9, 300, 'UGX', 500, true, now(), ?)
                        """, id, t.tenantId(), t.headOffice(), user)))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> asApp(c -> exec(c, """
                        INSERT INTO retail_daily_savings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                                                          suggested_minor, overwritten, occurred_at, recorded_by)
                        VALUES (?, ?, ?, CURRENT_DATE - 9, 300, 'UGX', 300, true, now(), ?)
                        """, id, t.tenantId(), t.headOffice(), user)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void repaidMinorCannotBeSetToAnArbitraryFigure() throws SQLException {
        UUID advance = advance(1_000);

        assertThatThrownBy(() ->
                        asApp(c -> exec(c, "UPDATE retail_advances SET repaid_minor = 400 WHERE id = ?", advance)))
                .hasMessageContaining("repaid_minor must equal the sum");

        // The repayment and the running total move together, in one transaction.
        UUID repayment = UUID.randomUUID();
        asApp(c -> {
            exec(c, """
                            INSERT INTO retail_advance_repayments (id, tenant_id, advance_id, branch_id, amount_minor, currency,
                                                                   method, paid_on, recorded_by)
                            VALUES (?, ?, ?, ?, 400, 'UGX', 'cash', CURRENT_DATE - 2, ?)
                            """, repayment, t.tenantId(), advance, t.headOffice(), user);
            exec(c, "UPDATE retail_advances SET repaid_minor = repaid_minor + 400 WHERE id = ?", advance);
        });
        assertThat(repaid(advance)).isEqualTo(400);

        // A repayment without the matching running total is refused at commit.
        assertThatThrownBy(
                        () -> asApp(c -> exec(c, """
                        INSERT INTO retail_advance_repayments (id, tenant_id, advance_id, branch_id, amount_minor, currency,
                                                               method, paid_on, recorded_by)
                        VALUES (?, ?, ?, ?, 100, 'UGX', 'cash', CURRENT_DATE - 2, ?)
                        """, UUID.randomUUID(), t.tenantId(), advance, t.headOffice(), user)))
                .hasMessageContaining("repaid_minor must equal the sum");

        // Voiding the repayment lowers the total in the same transaction.
        asApp(c -> {
            exec(
                    c,
                    "UPDATE retail_advance_repayments SET voided_at = now(), voided_by = ?, void_reason = 'Wrong advance' WHERE id = ?",
                    user,
                    repayment);
            exec(c, "UPDATE retail_advances SET repaid_minor = repaid_minor - 400 WHERE id = ?", advance);
        });
        assertThat(repaid(advance)).isZero();
        // A total above the principal is refused by the row check.
        assertThatThrownBy(() ->
                        asApp(c -> exec(c, "UPDATE retail_advances SET repaid_minor = 2000 WHERE id = ?", advance)))
                .isInstanceOf(SQLException.class);
    }

    long repaid(UUID advance) {
        return TestDatabase.owner()
                .sql("SELECT repaid_minor FROM retail_advances WHERE id = ?")
                .param(advance)
                .query(Long.class)
                .single();
    }

    @Test
    void aCategoryAccountMustBeAnActivePostableExpenseAccount() {
        UUID header = TestDatabase.owner()
                .sql("SELECT id FROM gl_accounts WHERE tenant_id = ? AND code = '5000'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        UUID asset = t.account("cash_on_hand");
        for (UUID bad : new UUID[] {header, asset}) {
            assertThatThrownBy(() -> asApp(c -> exec(
                            c,
                            "INSERT INTO retail_expense_categories (id, tenant_id, name, expense_account_id) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(),
                            t.tenantId(),
                            "Bad " + bad,
                            bad)))
                    .hasMessageContaining("active, postable expense account");
        }
        UUID good = t.account("operating_expenses");
        assertThat(good).isNotNull();
    }

    @Test
    void theCashBookSeedsTheReserveAndAdvanceAccountsForARetailTenant() {
        assertThat(t.account("savings_reserve")).isNotNull();
        assertThat(t.account("owner_advances")).isNotNull();
        assertThat(t.account("operating_expenses")).isNotNull();
    }
}
