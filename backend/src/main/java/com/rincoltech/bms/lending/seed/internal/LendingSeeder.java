package com.rincoltech.bms.lending.seed.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanServicing;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code seed-lending} command's work (ADR-025; {@code docs/runbooks/seed-lending.md}): 15
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

    LendingSeeder(
            TenantJobs tenants,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            Branches branches,
            CurrentTenant currentTenant,
            TenantSequences sequences,
            BusinessClock clock,
            AuditLog audit,
            LoanServicing servicing) {
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.branches = branches;
        this.currentTenant = currentTenant;
        this.sequences = sequences;
        this.clock = clock;
        this.audit = audit;
        this.servicing = servicing;
    }

    /** What the seed wrote. */
    public record Report(String tenant, int members, int products, int applications, int disbursed, int repayments) {

        public String render() {
            return "seed-lending: tenant " + tenant + ": " + members + " members, " + products + " products, "
                    + applications + " applications, " + disbursed + " disbursed loans, " + repayments
                    + " repayments (all fabricated)";
        }
    }

    /**
     * @throws IllegalArgumentException for an unknown tenant or one without lending
     * @throws IllegalStateException when the tenant holds data or was seeded before; nothing is written
     */
    public Report seed(String tenantSlug) {
        return tenants.callAsTenant(
                tenantSlug,
                "lending",
                () -> transactions.execute(status -> {
                    refuseUnlessEmpty();
                    return write(tenantSlug);
                }));
    }

    private void refuseUnlessEmpty() {
        long marker = count("SELECT count(*) FROM audit_log WHERE action = '" + MARKER + "'");
        if (marker > 0) {
            throw new IllegalStateException("the tenant was seeded before (audit action " + MARKER + ")");
        }
        long data = count("SELECT (SELECT count(*) FROM lending_members) + (SELECT count(*) FROM lending_loan_products)"
                + " + (SELECT count(*) FROM lending_loans) + (SELECT count(*) FROM journal_entries)");
        if (data > 0) {
            throw new IllegalStateException(
                    "the tenant already holds members, loan products, loans or journals; the seed only fills an"
                            + " empty tenant");
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

    private Report write(String slug) {
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

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("members", members.size());
        after.put("products", 4);
        after.put("applications", applications);
        after.put("disbursed", loans.size());
        after.put("repayments", repayments);
        audit.record(AuditLog.Entry.created(
                MARKER, "core.tenant", currentTenant.profile().id(), branch, after));
        return new Report(slug, members.size(), 4, applications, loans.size(), repayments);
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
