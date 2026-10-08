package com.rincoltech.bms.lending.seed.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.investments.InvestmentServicing;
import com.rincoltech.bms.lending.loans.LoanServicing;
import com.rincoltech.bms.lending.savings.SavingsServicing;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code seed-lending} command's work (ADR-026; {@code docs/runbooks/seed-lending.md}): 15
 * fabricated members, 4 loan products, 12 applications in mixed states and 6 disbursed loans with
 * repayments, in one transaction for one tenant. Every name, phone number, amount and rate is
 * invented. Refused, with nothing written, when the tenant already holds members, products, loans
 * or journals, or carries the marker of an earlier seed.
 */
@Service
public class LendingSeeder {

    /** The audit action that marks a seeded tenant; a second run finds it and refuses. */
    static final String MARKER = "lending.seed.fabricated";

    /** Fabricated staff ids: the seed writes as these, never as a real user. */
    static final UUID OFFICER = UUID.fromString("00000000-0000-4000-8000-00000000f001");

    static final UUID APPRAISER = UUID.fromString("00000000-0000-4000-8000-00000000f002");
    static final UUID MANAGER = UUID.fromString("00000000-0000-4000-8000-00000000f003");
    static final UUID CASHIER = UUID.fromString("00000000-0000-4000-8000-00000000f004");

    private final TenantJobs tenants;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Branches branches;
    private final CurrentTenant currentTenant;
    private final TenantSequences sequences;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final LoanServicing servicing;
    private final SavingsServicing savings;
    private final InvestmentServicing investing;
    private final InsightsDemoSeeder demo;

    LendingSeeder(
            TenantJobs tenants,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            Branches branches,
            CurrentTenant currentTenant,
            TenantSequences sequences,
            BusinessClock clock,
            AuditLog audit,
            LoanServicing servicing,
            SavingsServicing savings,
            InvestmentServicing investing,
            InsightsDemoSeeder demo) {
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.branches = branches;
        this.currentTenant = currentTenant;
        this.sequences = sequences;
        this.clock = clock;
        this.audit = audit;
        this.servicing = servicing;
        this.savings = savings;
        this.investing = investing;
        this.demo = demo;
    }

    /** What the seed wrote; {@code demo} is null without {@code --insights-demo}. */
    public record Report(
            String tenant,
            int members,
            int products,
            int applications,
            int disbursed,
            int repayments,
            int savingsAccounts,
            int savingsMovements,
            int investmentProducts,
            int investments,
            InsightsDemoSeeder.Report demo) {

        public String render() {
            return "seed-lending: tenant " + tenant + ": " + members + " members, " + products + " products, "
                    + applications + " applications, " + disbursed + " disbursed loans, " + repayments
                    + " repayments, " + savingsAccounts + " savings accounts, " + savingsMovements
                    + " savings movements, " + investmentProducts + " investment products, " + investments
                    + " investments (all fabricated)"
                    + (demo == null ? "" : "; " + demo.render());
        }
    }

    /**
     * What to write: the base seed only, or with the insights demo history at a volume scale (1 for a
     * demo tenant, 20 for the performance measurement of {@code docs/specs/lending-insights-metrics.md}).
     */
    public record Options(boolean insightsDemo, int scale) {

        public static final Options BASE = new Options(false, 1);

        public Options {
            if (scale < 1 || scale > 50) {
                throw new IllegalArgumentException("--scale is 1 to 50");
            }
        }
    }

    /**
     * @throws IllegalArgumentException for an unknown tenant or one without lending
     * @throws IllegalStateException when the tenant holds data or was seeded before; nothing is written
     */
    public Report seed(String tenantSlug) {
        return seed(tenantSlug, Options.BASE);
    }

    public Report seed(String tenantSlug, Options options) {
        return tenants.callAsTenant(tenantSlug, "lending", () -> {
            Report base = transactions.execute(status -> {
                refuseUnlessEmpty();
                return write(tenantSlug, options);
            });
            if (!options.insightsDemo()) {
                return base;
            }
            // The base seed has committed with its marker; the year of history commits month by month.
            UUID headOffice = transactions.execute(status -> headOffice());
            InsightsDemoSeeder.Report history = demo.write(
                    headOffice,
                    currentTenant.profile().currency(),
                    clock.today(currentTenant.profile().timezone()),
                    currentTenant.profile().timezone(),
                    options.scale(),
                    transactions);
            return new Report(
                    base.tenant(),
                    base.members(),
                    base.products(),
                    base.applications(),
                    base.disbursed(),
                    base.repayments(),
                    base.savingsAccounts(),
                    base.savingsMovements(),
                    base.investmentProducts(),
                    base.investments(),
                    history);
        });
    }

    private UUID headOffice() {
        return branches.all().stream()
                .filter(b -> b.headOffice() && b.active())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the tenant has no active head office branch"))
                .id();
    }

    private void refuseUnlessEmpty() {
        long marker = count("SELECT count(*) FROM audit_log WHERE action = '" + MARKER + "'");
        if (marker > 0) {
            throw new IllegalStateException("the tenant was seeded before (audit action " + MARKER + ")");
        }
        long data = count("SELECT (SELECT count(*) FROM lending_members) + (SELECT count(*) FROM lending_loan_products)"
                + " + (SELECT count(*) FROM lending_loans) + (SELECT count(*) FROM journal_entries)"
                + " + (SELECT count(*) FROM lending_savings_products)"
                + " + (SELECT count(*) FROM lending_investment_products)");
        if (data > 0) {
            throw new IllegalStateException(
                    "the tenant already holds members, loan or investment products, loans or journals; the seed only"
                            + " fills an empty tenant");
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---- The data -------------------------------------------------------------------------

    private record Product(UUID id, UUID versionId, String code, Terms terms) {}

    private record Terms(
            String method,
            int rateBp,
            String rateUnit,
            String termUnit,
            int minTerm,
            int maxTerm,
            int defaultTerm,
            String pattern,
            String frequency,
            long minPrincipal,
            long maxPrincipal,
            boolean flatRebate) {}

    private record Fee(String name, String type, Long amount, Integer rateBp, String timing) {}

    private Report write(String slug, Options options) {
        UUID branch = branches.all().stream()
                .filter(b -> b.headOffice() && b.active())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the tenant has no active head office branch"))
                .id();
        String currency = currentTenant.profile().currency();
        LocalDate today = clock.today(currentTenant.profile().timezone());

        List<UUID> members = new ArrayList<>();
        for (int n = 1; n <= 15; n++) {
            members.add(member(branch, currency, n));
        }
        Product bullet = product(
                "FAB-BULLET",
                "Fabricated bullet loan",
                currency,
                new Terms("flat", 1500, "per_term", "month", 1, 3, 1, "bullet", null, 100_000, 2_000_000, false),
                List.of());
        Product monthly = product(
                "FAB-MONTHLY",
                "Fabricated monthly flat loan",
                currency,
                new Terms(
                        "flat",
                        800,
                        "per_month",
                        "month",
                        2,
                        6,
                        4,
                        "instalments",
                        "monthly",
                        200_000,
                        5_000_000,
                        false),
                List.of(new Fee("Processing", "processing", null, 200, "deducted_at_disbursement")));
        Product declining = product(
                "FAB-DECLINE",
                "Fabricated declining balance loan",
                currency,
                new Terms(
                        "declining",
                        1200,
                        "per_month",
                        "month",
                        3,
                        12,
                        3,
                        "instalments",
                        "monthly",
                        500_000,
                        10_000_000,
                        false),
                List.of(new Fee("Application", "application", 10_000L, null, "paid_upfront")));
        Product weekly = product(
                "FAB-WEEKLY",
                "Fabricated weekly loan",
                currency,
                new Terms("flat", 300, "per_week", "week", 4, 12, 8, "instalments", "weekly", 100_000, 1_000_000, true),
                List.of(new Fee("Insurance", "insurance", 8_000L, null, "added_to_loan")));

        // Twelve applications that have not reached the money: every pre-disbursement state.
        int applications = 0;
        String[] states = {
            "draft",
            "draft",
            "submitted",
            "submitted",
            "appraised",
            "appraised",
            "approved",
            "approved",
            "approved",
            "rejected",
            "rejected",
            "cancelled"
        };
        Product[] cycle = {bullet, monthly, declining, weekly};
        for (int k = 0; k < states.length; k++) {
            Product p = cycle[k % cycle.length];
            long principal = Math.clamp(
                    300_000L + 100_000L * k, p.terms().minPrincipal(), p.terms().maxPrincipal());
            loan(
                    branch,
                    currency,
                    members.get(6 + (k % 9)),
                    p,
                    principal,
                    p.terms().defaultTerm(),
                    states[k],
                    today,
                    today);
            applications++;
        }

        // Six disbursed loans, backdated so the schedule has items paid, due and overdue.
        record Disbursed(Product product, int member, long principal, int term, int daysAgo, String method) {}
        List<Disbursed> book = List.of(
                new Disbursed(bullet, 0, 500_000, 1, 100, "cash"),
                new Disbursed(monthly, 1, 1_200_000, 4, 75, "mtn_momo"),
                new Disbursed(declining, 2, 1_000_000, 3, 60, "bank"),
                new Disbursed(weekly, 3, 400_000, 8, 45, "cash"),
                new Disbursed(monthly, 4, 800_000, 3, 30, "airtel_money"),
                new Disbursed(bullet, 5, 300_000, 1, 10, "cash"));
        List<UUID> loans = new ArrayList<>();
        for (Disbursed d : book) {
            LocalDate on = today.minusDays(d.daysAgo());
            UUID id = loan(
                    branch,
                    currency,
                    members.get(d.member()),
                    d.product(),
                    d.principal(),
                    d.term(),
                    "approved",
                    on,
                    on);
            servicing.disburseApproved(id, on, d.method(), CASHIER, MANAGER);
            loans.add(id);
        }
        int repayments = 0;
        // Paid off with an overpayment: the excess is held as the member's credit.
        repayments += repay(loans.get(0), today, 75, 300_000, "cash");
        repayments += repay(loans.get(0), today, 60, 300_000, "cash");
        // One instalment paid in full, the next in part.
        repayments += repay(loans.get(1), today, 44, 396_000, "mtn_momo");
        repayments += repay(loans.get(1), today, 12, 200_000, "mtn_momo");
        // A small part payment: the first instalment is overdue.
        repayments += repay(loans.get(2), today, 25, 100_000, "bank");
        // Five weekly instalments on time; the sixth is overdue.
        for (int days : new int[] {38, 31, 24, 17, 10}) {
            repayments += repay(loans.get(3), today, days, 63_000, "cash");
        }
        repayments += repay(loans.get(4), today, 1, 150_000, "airtel_money");

        int[] investments = investments(currency, members, today);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("members", members.size());
        after.put("products", 4);
        after.put("applications", applications);
        after.put("disbursed", loans.size());
        after.put("repayments", repayments);
        int[] saved = savings(currency, members, today);
        after.put("savings_accounts", saved[0]);
        after.put("savings_movements", saved[1]);
        after.put("investment_products", investments[0]);
        after.put("investments", investments[1]);
        if (options.insightsDemo()) {
            after.put("insights_demo_scale", options.scale());
        }
        audit.record(AuditLog.Entry.created(
                MARKER, "core.tenant", currentTenant.profile().id(), branch, after));
        return new Report(
                slug,
                members.size(),
                4,
                applications,
                loans.size(),
                repayments,
                saved[0],
                saved[1],
                investments[0],
                investments[1],
                null);
    }

    // ---- Savings (increment 9, #151; ADR-032) ---------------------------------------------

    /** One fabricated movement: a deposit, or a withdrawal when the amount is negative. */
    private record Movement(int account, long amountMinor, String method) {}

    /**
     * Three fabricated savings products and eleven accounts (one member holds two), with twelve
     * months of deposits and withdrawals. The days run in order: before each day with movements the
     * end of day runs through the day before, so end-of-day balances, monthly and quarterly interest
     * and dormancy happen exactly as the nightly job would have done them. Returns the accounts and
     * the movements written.
     */
    private int[] savings(String currency, List<UUID> members, LocalDate today) {
        UUID passbook = savingsProduct(
                "FAB-SAVE",
                "Fabricated passbook savings",
                currency,
                500,
                "daily_balance",
                "monthly",
                0,
                5_000,
                1_000,
                null,
                null,
                180);
        UUID target = savingsProduct(
                "FAB-TARGET",
                "Fabricated target savings",
                currency,
                800,
                "minimum_monthly_balance",
                "quarterly",
                50_000,
                0,
                0,
                300_000L,
                1,
                90);
        UUID plain = savingsProduct(
                "FAB-PLAIN", "Fabricated plain savings", currency, 0, "none", "monthly", 0, 0, 0, null, null, null);
        LocalDate start = today.minusMonths(12);
        UUID[] products = {passbook, passbook, target, plain, passbook, target, passbook, plain, passbook, target, plain
        };
        int[] holders = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        List<UUID> accounts = new ArrayList<>();
        Map<LocalDate, List<Movement>> days = new TreeMap<>();
        for (int k = 0; k < products.length; k++) {
            LocalDate opened = start.plusDays(3L * k);
            accounts.add(null);
            days.computeIfAbsent(opened, d -> new ArrayList<>()).add(new Movement(k, 0, "open"));
            // Account 5 stops after three months, so its 90 dormancy days pass and it turns dormant.
            int months = k == 5 ? 3 : 12;
            for (int m = 0; m < months; m++) {
                LocalDate day = opened.plusMonths(m).plusDays(m == 0 ? 0 : 2);
                if (day.isAfter(today)) {
                    break;
                }
                long deposit = 60_000L + 10_000L * ((k + m) % 5);
                days.computeIfAbsent(day, d -> new ArrayList<>())
                        .add(new Movement(k, deposit, k % 2 == 0 ? "cash" : "mtn_momo"));
                // Every third month most accounts take some out, two weeks after the deposit.
                if (m % 3 == 2 && k != 5) {
                    LocalDate out = day.plusDays(14);
                    if (!out.isAfter(today)) {
                        days.computeIfAbsent(out, d -> new ArrayList<>()).add(new Movement(k, -40_000, "cash"));
                    }
                }
            }
        }
        int movements = 0;
        for (Map.Entry<LocalDate, List<Movement>> day : days.entrySet()) {
            LocalDate d = day.getKey();
            savings.endOfDay(d.minusDays(1));
            for (Movement mv : day.getValue()) {
                if (mv.method().equals("open")) {
                    accounts.set(
                            mv.account(),
                            savings.openAccount(
                                    members.get(holders[mv.account()]), products[mv.account()], d, OFFICER));
                } else if (mv.amountMinor() > 0) {
                    savings.deposit(accounts.get(mv.account()), mv.amountMinor(), d, mv.method(), CASHIER);
                    movements++;
                } else {
                    savings.withdraw(accounts.get(mv.account()), -mv.amountMinor(), d, mv.method(), CASHIER, MANAGER);
                    movements++;
                }
            }
        }
        savings.endOfDay(today.minusDays(1));
        return new int[] {accounts.size(), movements};
    }

    private UUID savingsProduct(
            String code,
            String name,
            String currency,
            int rateBp,
            String calc,
            String posting,
            long minOpening,
            long minBalance,
            long fee,
            Long maxWithdrawal,
            Integer maxPerMonth,
            Integer dormancyDays) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO lending_savings_products (id, tenant_id, code, name, currency, interest_rate_bp,
                            interest_calc, interest_posting, min_opening_balance_minor, min_balance_minor,
                            withdrawal_fee_minor, max_withdrawal_minor, max_withdrawals_per_month, dormancy_days, status,
                            created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)
                        """)
                .params(
                        id,
                        code,
                        name,
                        currency,
                        rateBp,
                        calc,
                        posting,
                        minOpening,
                        minBalance,
                        fee,
                        maxWithdrawal,
                        maxPerMonth,
                        dormancyDays,
                        OFFICER)
                .update();
        return id;
    }

    // ---- Investments (issue #152) ---------------------------------------------------------

    /** One fabricated investment: who, what, how much, for how long, and when it started. */
    private record Placement(
            int member, UUID product, long amount, int term, String instruction, int monthsAgo, int days) {}

    /**
     * Three fabricated investment products and ten investments placed over the last twelve months,
     * replayed day by day through the nightly job's own work, so every accrual, maturity, rollover
     * and payout lands on its own date: monthly returns collected, a fixed deposit paid out at
     * maturity, a recurring deposit renewed four times, one matured and waiting for the member, one
     * maturing within the week and one not yet funded. Returns {products, investments}.
     */
    private int[] investments(String currency, List<UUID> members, LocalDate today) {
        UUID fixed = investmentProduct(
                "FAB-FD",
                "Fabricated fixed deposit",
                currency,
                "fixed_term",
                "{3,6,12}",
                1200,
                "flat",
                "at_maturity",
                500_000,
                "reduced_rate",
                600,
                100);
        UUID monthly = investmentProduct(
                "FAB-FD-MONTHLY",
                "Fabricated monthly income deposit",
                currency,
                "fixed_term",
                "{6,12}",
                1500,
                "flat",
                "monthly",
                1_000_000,
                null,
                null,
                0);
        UUID recurring = investmentProduct(
                "FAB-RECURRING",
                "Fabricated recurring deposit",
                currency,
                "recurring",
                "{3,6}",
                1400,
                "compound",
                "at_maturity",
                200_000,
                "forfeit_return",
                null,
                200);
        List<Placement> book = List.of(
                new Placement(6, fixed, 2_000_000, 6, "payout", 12, 0),
                new Placement(7, fixed, 5_000_000, 12, null, 11, 0),
                new Placement(8, monthly, 3_000_000, 12, null, 10, 0),
                new Placement(9, recurring, 1_000_000, 3, null, 12, 0),
                new Placement(10, fixed, 1_500_000, 3, null, 3, -10),
                new Placement(11, monthly, 2_000_000, 6, null, 5, 3),
                new Placement(12, fixed, 4_000_000, 3, null, 3, 5),
                new Placement(13, recurring, 600_000, 6, "payout", 7, 0),
                new Placement(14, fixed, 800_000, 6, "rollover_all", 6, 0));
        Map<LocalDate, List<UUID>> fundings = new TreeMap<>();
        List<UUID> collected = new ArrayList<>();
        List<UUID> paidOut = new ArrayList<>();
        int count = 0;
        for (Placement p : book) {
            UUID id = investing.open(
                    members.get(p.member()), p.product(), p.amount(), p.term(), p.instruction(), OFFICER);
            fundings.computeIfAbsent(today.minusMonths(p.monthsAgo()).plusDays(p.days()), d -> new ArrayList<>())
                    .add(id);
            if (p.product().equals(monthly) && p.member() == 8) {
                collected.add(id);
            }
            if ("payout".equals(p.instruction())) {
                paidOut.add(id);
            }
            count++;
        }
        // One opened today and not yet funded: it waits for the cashier.
        investing.open(members.get(5), fixed, 1_000_000, 6, null, OFFICER);
        count++;
        LocalDate first = fundings.keySet().iterator().next();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            for (UUID id : fundings.getOrDefault(d, List.of())) {
                investing.fundApproved(id, d, "cash", CASHIER, MANAGER);
            }
            investing.runDaily(d);
            for (UUID id : collected) {
                if (returnDue(id) > 0) {
                    investing.payDueReturn(id, d, "mtn_momo", CASHIER);
                }
            }
            for (UUID id : paidOut) {
                if (status(id).equals("matured")) {
                    investing.payOutMatured(id, d, "bank", CASHIER);
                }
            }
        }
        return new int[] {3, count};
    }

    private long returnDue(UUID id) {
        return jdbc.sql("SELECT return_due_minor - return_paid_minor FROM lending_investments WHERE id = ?")
                .param(id)
                .query(Long.class)
                .single();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM lending_investments WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    private UUID investmentProduct(
            String code,
            String name,
            String currency,
            String type,
            String terms,
            int rateBp,
            String method,
            String payout,
            long min,
            String earlyRule,
            Integer earlyRateBp,
            int penaltyBp) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO lending_investment_products (id, tenant_id, code, name, currency, product_type,
                            allowed_terms_months, return_rate_bp, return_method, payout_frequency, min_amount_minor,
                            max_amount_minor, early_withdrawal_allowed, early_withdrawal_rule, early_withdrawal_rate_bp,
                            early_withdrawal_penalty_bp, status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?::integer[], ?, ?, ?, ?,
                            50000000, ?, ?, ?, ?, 'active', ?)
                        """)
                .params(
                        id,
                        code,
                        name,
                        currency,
                        type,
                        terms,
                        rateBp,
                        method,
                        payout,
                        min,
                        earlyRule != null,
                        earlyRule,
                        earlyRateBp,
                        penaltyBp,
                        OFFICER)
                .update();
        return id;
    }

    private int repay(UUID loan, LocalDate today, int daysAgo, long amount, String method) {
        servicing.recordRepayment(loan, amount, today.minusDays(daysAgo), method, CASHIER);
        return 1;
    }

    private UUID member(UUID branch, String currency, int n) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                            occupation, monthly_income_minor, currency, kyc_status, kyc_verified_by, kyc_verified_at,
                            status, officer_user_id, source, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 'none', 'Fabricated trader', ?,
                            ?, 'verified', ?, ?, 'active', ?, 'staff', ?)
                        """)
                .params(
                        id,
                        branch,
                        "M%06d".formatted(sequences.next("member_no")),
                        "Test Borrower %02d".formatted(n),
                        "+2567000000%02d".formatted(n),
                        500_000L + 50_000L * n,
                        currency,
                        MANAGER,
                        Timestamp.from(clock.now()),
                        OFFICER,
                        OFFICER)
                .update();
        return id;
    }

    private Product product(String code, String name, String currency, Terms t, List<Fee> fees) {
        UUID id = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO lending_loan_products (id, tenant_id, code, name, status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'active', ?)
                        """).params(id, code, name, OFFICER).update();
        jdbc.sql("""
                        INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                            interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                            default_term_count, repayment_pattern, instalment_frequency, min_principal_minor,
                            max_principal_minor, flat_early_settlement_rebate, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        version,
                        id,
                        currency,
                        t.method(),
                        t.rateBp(),
                        t.rateUnit(),
                        t.termUnit(),
                        t.minTerm(),
                        t.maxTerm(),
                        t.defaultTerm(),
                        t.pattern(),
                        t.frequency(),
                        t.minPrincipal(),
                        t.maxPrincipal(),
                        t.flatRebate(),
                        OFFICER)
                .update();
        for (Fee f : fees) {
            jdbc.sql("""
                            INSERT INTO lending_loan_product_fees (id, tenant_id, product_version_id, name, fee_type,
                                calc_method, amount_minor, rate_bp, timing)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            version,
                            f.name(),
                            f.type(),
                            f.amount() != null ? "flat" : "percent_of_principal",
                            f.amount(),
                            f.rateBp(),
                            f.timing())
                    .update();
        }
        jdbc.sql("UPDATE lending_loan_products SET current_version_id = ? WHERE id = ?")
                .params(version, id)
                .update();
        return new Product(id, version, code, t);
    }

    /** One application in {@code status}, with the history of how it got there. */
    private UUID loan(
            UUID branch,
            String currency,
            UUID member,
            Product p,
            long principal,
            int term,
            String status,
            LocalDate proposed,
            LocalDate decidedOn) {
        List<String> path = switch (status) {
            case "draft" -> List.of("draft");
            case "submitted" -> List.of("draft", "submitted");
            case "appraised" -> List.of("draft", "submitted", "appraised");
            case "approved" -> List.of("draft", "submitted", "appraised", "approved");
            case "rejected" -> List.of("draft", "submitted", "appraised", "rejected");
            case "cancelled" -> List.of("draft", "submitted", "cancelled");
            default -> throw new IllegalArgumentException(status);
        };
        boolean submitted = path.contains("submitted");
        boolean appraised = path.contains("appraised");
        boolean approved = status.equals("approved");
        Instant decided = decidedOn
                .atStartOfDay(currentTenant.profile().timezone())
                .toInstant()
                .minus(1, ChronoUnit.DAYS);
        UUID id = UUID.randomUUID();
        Terms t = p.terms();
        jdbc.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, purpose_text, currency,
                            requested_principal_minor, requested_term_count, approved_principal_minor, approved_term_count,
                            term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern,
                            instalment_frequency, proposed_disbursement_date, submitted_by, submitted_at, appraised_by,
                            approved_by, approved_at, rejected_reason, cancelled_reason, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, 'staff', 'business',
                            'Fabricated stock purchase', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        branch,
                        "LN%06d".formatted(sequences.next("loan_no")),
                        member,
                        p.versionId(),
                        OFFICER,
                        status,
                        currency,
                        principal,
                        term,
                        approved ? principal : null,
                        approved ? term : null,
                        t.termUnit(),
                        t.method(),
                        t.rateBp(),
                        t.rateUnit(),
                        t.pattern(),
                        t.frequency(),
                        proposed,
                        submitted ? OFFICER : null,
                        submitted ? Timestamp.from(decided.minus(2, ChronoUnit.DAYS)) : null,
                        appraised ? APPRAISER : null,
                        approved ? MANAGER : null,
                        approved ? Timestamp.from(decided) : null,
                        status.equals("rejected") ? "Fabricated: income not verified" : null,
                        status.equals("cancelled") ? "Fabricated: withdrawn by the member" : null,
                        OFFICER)
                .update();
        String from = null;
        for (String to : path) {
            UUID by = switch (to) {
                case "appraised" -> APPRAISER;
                case "approved", "rejected" -> MANAGER;
                default -> OFFICER;
            };
            jdbc.sql("""
                            INSERT INTO lending_loan_status_history (id, tenant_id, loan_id, from_status, to_status,
                                changed_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                            """).params(UUID.randomUUID(), id, from, to, by).update();
            from = to;
        }
        return id;
    }
}
