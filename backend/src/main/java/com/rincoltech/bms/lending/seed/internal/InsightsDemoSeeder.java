package com.rincoltech.bms.lending.seed.internal;

import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.lending.insights.InsightsSnapshots;
import com.rincoltech.bms.lending.loans.LoanServicing;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The fabricated "insights demo" enrichment of {@code seed-lending --insights-demo} (issue #153):
 * twelve months of believable history on top of the base seed, so a new demo tenant's insights
 * page shows trends. Two more fabricated branches, fabricated staff, members joining month by
 * month, applications that grow over the year with a few rejected and cancelled, and loans whose
 * borrowers pay on time, late, stop paying (and are written off, some later recovered) or settle
 * early with an overpayment. Every money event goes through {@link LoanServicing}, so the books
 * agree; then the year's daily snapshots are written. Deterministic: the same scale on the same
 * date writes the same story. Every name, number and amount is invented.
 */
@Component
public class InsightsDemoSeeder {

    /** What the enrichment wrote. */
    public record Report(
            int branches,
            int staff,
            int members,
            int applications,
            int disbursed,
            int repayments,
            int writeOffs,
            int recoveries,
            long snapshotRows) {

        public String render() {
            return "insights demo: " + branches + " more branches, " + staff + " staff, " + members + " members, "
                    + applications + " applications, " + disbursed + " disbursed loans, " + repayments
                    + " repayments, " + writeOffs + " write-offs, " + recoveries + " recoveries, " + snapshotRows
                    + " snapshot rows (all fabricated)";
        }
    }

    private record Staff(UUID officer, UUID cashier) {}

    private record BranchStaff(UUID branch, List<UUID> officers, UUID cashier) {}

    private record Member(UUID id, int branch, LocalDate joined) {}

    private record Product(
            UUID versionId,
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
            long maxPrincipal) {}

    private record Item(LocalDate due, long amount) {}

    private static final String[] METHODS = {"cash", "cash", "mtn_momo", "mtn_momo", "airtel_money", "bank"};

    private final JdbcClient jdbc;
    private final TenantSequences sequences;
    private final LoanServicing servicing;
    private final InsightsSnapshots snapshots;

    InsightsDemoSeeder(
            JdbcClient jdbc, TenantSequences sequences, LoanServicing servicing, InsightsSnapshots snapshots) {
        this.jdbc = jdbc;
        this.sequences = sequences;
        this.servicing = servicing;
        this.snapshots = snapshots;
    }

    private int repayments;
    private int writeOffs;
    private int recoveries;

    /**
     * Runs after the base seed has committed, with the tenant bound: the set-up in one transaction,
     * then one transaction per month of applications and one per month of snapshots. A single
     * transaction for the whole year would update each numbering sequence row tens of thousands
     * of times, and row versions a transaction leaves behind cannot be pruned until it ends, so every
     * later read of the row gets slower. {@code scale} multiplies the volumes.
     */
    Report write(UUID headOffice, String currency, LocalDate today, ZoneId zone, int scale, TransactionTemplate tx) {
        repayments = 0;
        writeOffs = 0;
        recoveries = 0;
        Random random = new Random(153L * scale + today.toEpochDay());
        LocalDate start = today.minusDays(365);

        record Setup(
                List<UUID> branchIds, UUID manager, UUID appraiser, List<BranchStaff> staffing, List<Member> members) {}
        Setup setup = tx.execute(status -> {
            // Branches and staff: the head office and two fabricated branches, an officer or two each.
            List<UUID> branchIds = List.of(
                    headOffice, branch("FABN", "Fabricated North branch"), branch("FABE", "Fabricated East branch"));
            UUID manager = staff("Fabricated Manager 01", 60);
            UUID appraiser = staff("Fabricated Appraiser 01", 61);
            List<BranchStaff> staffing = List.of(
                    new BranchStaff(
                            branchIds.get(0),
                            List.of(staff("Fabricated Officer 01", 62), staff("Fabricated Officer 02", 63)),
                            staff("Fabricated Cashier 01", 66)),
                    new BranchStaff(
                            branchIds.get(1),
                            List.of(staff("Fabricated Officer 03", 64)),
                            staff("Fabricated Cashier 02", 67)),
                    new BranchStaff(
                            branchIds.get(2),
                            List.of(staff("Fabricated Officer 04", 65)),
                            staff("Fabricated Cashier 03", 68)));

            // Members join through the year, more of them recently.
            List<Member> members = new ArrayList<>();
            int memberCount = 90 * scale;
            for (int n = 0; n < memberCount; n++) {
                double position = Math.sqrt((n + 1.0) / memberCount);
                LocalDate joined = start.minusDays(30).plusDays(Math.round(position * 390) - 1);
                if (joined.isAfter(today)) {
                    joined = today;
                }
                int b = pick(random, new int[] {50, 30, 20});
                BranchStaff bs = staffing.get(b);
                UUID officer = bs.officers().get(random.nextInt(bs.officers().size()));
                members.add(new Member(
                        member(branchIds.get(b), officer, currency, n + 1, joined, zone, random), b, joined));
            }
            return new Setup(branchIds, manager, appraiser, staffing, members);
        });
        List<UUID> branchIds = setup.branchIds();
        UUID manager = setup.manager();
        UUID appraiser = setup.appraiser();
        List<BranchStaff> staffing = setup.staffing();
        List<Member> members = setup.members();

        List<Product> products = tx.execute(status -> products());
        int[] counts = new int[2];
        for (int monthIndex = 0; monthIndex < 12; monthIndex++) {
            final int month = monthIndex;
            tx.executeWithoutResult(status -> {
                LocalDate monthStart = start.plusDays(month * 30L + 1);
                int count = (14 + month) * scale;
                for (int k = 0; k < count; k++) {
                    LocalDate day = monthStart.plusDays(random.nextInt(30));
                    if (!day.isBefore(today)) {
                        day = today.minusDays(1 + random.nextInt(3));
                    }
                    final LocalDate applied = day;
                    List<Member> eligible = members.stream()
                            .filter(x -> !x.joined().isAfter(applied))
                            .toList();
                    if (eligible.isEmpty()) {
                        continue;
                    }
                    Member member = eligible.get(random.nextInt(eligible.size()));
                    BranchStaff bs = staffing.get(member.branch());
                    UUID officer =
                            bs.officers().get(random.nextInt(bs.officers().size()));
                    Product product = products.get(pick(random, new int[] {20, 35, 25, 20}));
                    long principal = principal(product, random);
                    int term = Math.clamp(
                            product.defaultTerm() + random.nextInt(3) - 1, product.minTerm(), product.maxTerm());
                    int roll = random.nextInt(100);
                    String outcome = roll < 8 ? "rejected" : roll < 12 ? "cancelled" : "approved";
                    if (ChronoUnit.DAYS.between(applied, today) < 4 && roll >= 90) {
                        outcome = roll >= 95 ? "submitted" : "appraised";
                    }
                    UUID loan = application(
                            member,
                            branchIds.get(member.branch()),
                            officer,
                            appraiser,
                            manager,
                            product,
                            principal,
                            term,
                            outcome,
                            applied,
                            zone,
                            currency,
                            random);
                    counts[0]++;
                    if (!outcome.equals("approved")) {
                        continue;
                    }
                    LocalDate on = applied.plusDays(1);
                    if (on.isAfter(today)) {
                        continue;
                    }
                    servicing.disburseApproved(
                            loan, on, METHODS[random.nextInt(METHODS.length)], bs.cashier(), manager);
                    counts[1]++;
                    service(loan, on, today, bs.cashier(), manager, random);
                }
            });
        }
        long rows = 0;
        for (LocalDate from = start; from.isBefore(today); from = from.plusDays(31)) {
            LocalDate a = from;
            LocalDate b = from.plusDays(30).isBefore(today.minusDays(1)) ? from.plusDays(30) : today.minusDays(1);
            rows += tx.execute(status -> snapshots.backfill(a, b));
        }
        return new Report(2, 9, members.size(), counts[0], counts[1], repayments, writeOffs, recoveries, rows);
    }

    /** How a borrower behaves: pays on time, pays late, stops paying, or settles early. */
    private void service(UUID loan, LocalDate disbursedOn, LocalDate today, UUID cashier, UUID manager, Random random) {
        List<Item> items = new ArrayList<>(jdbc.sql("""
                        SELECT due_date, principal_due_minor + interest_due_minor + fees_due_minor AS amount
                          FROM lending_schedule_items WHERE loan_id = ? ORDER BY item_no
                        """)
                .param(loan)
                .query((rs, n) -> new Item(rs.getDate("due_date").toLocalDate(), rs.getLong("amount")))
                .list());
        int roll = random.nextInt(100);
        LocalDate last = disbursedOn;
        if (roll < 5 && items.size() > 1) {
            // Settles early with everything left in one payment: the rebate and rounding leave a credit.
            LocalDate first = clampDate(items.getFirst().due().plusDays(random.nextInt(3)), last);
            if (!first.isAfter(today)) {
                last = pay(loan, items.getFirst().amount(), first, cashier, random);
                LocalDate settle = clampDate(first.plusDays(5 + random.nextInt(20)), last);
                long rest = items.stream().skip(1).mapToLong(Item::amount).sum();
                if (!settle.isAfter(today)) {
                    pay(loan, rest + 1_000, settle, cashier, random);
                }
            }
            return;
        }
        boolean late = roll >= 5 && roll < 22;
        boolean stops = roll >= 22 && roll < 32;
        int paidItems = stops ? random.nextInt(items.size()) : items.size();
        for (int i = 0; i < paidItems; i++) {
            Item item = items.get(i);
            int delay = late ? 5 + random.nextInt(31) : random.nextInt(6) - 2;
            LocalDate date = clampDate(item.due().plusDays(delay), last);
            if (date.isAfter(today)) {
                return;
            }
            if (late && random.nextInt(4) == 0 && i + 1 < items.size()) {
                // A part payment now, the rest with the next instalment.
                long part = item.amount() * 6 / 10;
                last = pay(loan, part, date, cashier, random);
                items.set(
                        i + 1, new Item(items.get(i + 1).due(), items.get(i + 1).amount() + item.amount() - part));
            } else {
                last = pay(loan, item.amount(), date, cashier, random);
            }
        }
        if (!stops || paidItems == items.size()) {
            return;
        }
        // Stopped paying: written off about four months after the first missed due date, sometimes recovered.
        LocalDate writeOff = clampDate(items.get(paidItems).due().plusDays(120 + random.nextInt(30)), last);
        if (writeOff.isAfter(today)) {
            return;
        }
        servicing.writeOffApproved(loan, writeOff, "Fabricated: no contact for four months", cashier, manager);
        writeOffs++;
        if (random.nextInt(10) < 3) {
            LocalDate recovered = writeOff.plusDays(30 + random.nextInt(60));
            long principal = jdbc.sql("SELECT principal_disbursed_minor FROM lending_loans WHERE id = ?")
                    .param(loan)
                    .query(Long.class)
                    .single();
            if (!recovered.isAfter(today)) {
                servicing.recordRepayment(
                        loan, round(principal * (2 + random.nextInt(3)) / 10), recovered, "cash", cashier);
                recoveries++;
            }
        }
    }

    private LocalDate pay(UUID loan, long amount, LocalDate date, UUID cashier, Random random) {
        servicing.recordRepayment(loan, amount, date, METHODS[random.nextInt(METHODS.length)], cashier);
        repayments++;
        return date;
    }

    private static LocalDate clampDate(LocalDate date, LocalDate notBefore) {
        return date.isBefore(notBefore) ? notBefore : date;
    }

    private static long round(long amount) {
        return Math.max(1_000, Math.round(amount / 1_000.0) * 1_000);
    }

    private static int pick(Random random, int[] weights) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private static long principal(Product p, Random random) {
        double u = random.nextDouble();
        long span = p.maxPrincipal() - p.minPrincipal();
        long raw = p.minPrincipal() + Math.round(span * u * u * 0.6);
        long rounded = Math.round(raw / 50_000.0) * 50_000;
        return Math.clamp(rounded, p.minPrincipal(), p.maxPrincipal());
    }

    // ---- Rows written directly, as the import does ------------------------------------------

    private UUID branch(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO branches (id, tenant_id, code, name, location)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'Fabricated location')
                        """).params(id, code, name).update();
        return id;
    }

    /** A fabricated staff user who cannot sign in: deactivated, no credentials. */
    private UUID staff(String name, int phone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status)
                        VALUES (?, current_setting('app.tenant_id')::uuid, 'staff', ?, ?, 'deactivated')
                        """).params(id, name, "+2567000000%02d".formatted(phone)).update();
        return id;
    }

    private UUID member(
            UUID branch, UUID officer, String currency, int n, LocalDate joined, ZoneId zone, Random random) {
        UUID id = UUID.randomUUID();
        int kyc = random.nextInt(100);
        String status = kyc < 85 ? "verified" : kyc < 95 ? "pending_verification" : "incomplete";
        Instant at = joined.atTime(9 + random.nextInt(8), random.nextInt(60))
                .atZone(zone)
                .toInstant();
        jdbc.sql("""
                        INSERT INTO lending_members (id, tenant_id, created_at, branch_id, member_no, full_name, phone_e164,
                            id_type, occupation, monthly_income_minor, currency, kyc_status, kyc_verified_at, status,
                            officer_user_id, source, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, 'none', 'Fabricated trader', ?,
                            ?, ?, ?, 'active', ?, 'staff', ?)
                        """)
                .params(
                        id,
                        Timestamp.from(at),
                        branch,
                        "M%06d".formatted(sequences.next("member_no")),
                        "Demo Borrower %04d".formatted(n),
                        "+2567000000%02d".formatted(n % 100),
                        300_000L + 50_000L * random.nextInt(20),
                        currency,
                        status,
                        status.equals("verified") ? Timestamp.from(at) : null,
                        officer,
                        officer)
                .update();
        return id;
    }

    private List<Product> products() {
        return jdbc.sql("""
                        SELECT v.id, v.interest_method, v.interest_rate_bp, v.rate_unit, v.term_unit, v.min_term_count,
                               v.max_term_count, v.default_term_count, v.repayment_pattern, v.instalment_frequency,
                               v.min_principal_minor, v.max_principal_minor
                          FROM lending_loan_products p
                          JOIN lending_loan_product_versions v ON v.id = p.current_version_id
                         WHERE p.code IN ('FAB-BULLET', 'FAB-MONTHLY', 'FAB-DECLINE', 'FAB-WEEKLY')
                         ORDER BY CASE p.code WHEN 'FAB-BULLET' THEN 1 WHEN 'FAB-MONTHLY' THEN 2
                                              WHEN 'FAB-DECLINE' THEN 3 ELSE 4 END
                        """)
                .query((rs, n) -> new Product(
                        rs.getObject("id", UUID.class),
                        rs.getString("interest_method"),
                        rs.getInt("interest_rate_bp"),
                        rs.getString("rate_unit"),
                        rs.getString("term_unit"),
                        rs.getInt("min_term_count"),
                        rs.getInt("max_term_count"),
                        rs.getInt("default_term_count"),
                        rs.getString("repayment_pattern"),
                        rs.getString("instalment_frequency"),
                        rs.getLong("min_principal_minor"),
                        rs.getLong("max_principal_minor")))
                .list();
    }

    /** An application submitted on {@code applied}, appraised and decided, with its backdated history. */
    private UUID application(
            Member member,
            UUID branch,
            UUID officer,
            UUID appraiser,
            UUID manager,
            Product p,
            long principal,
            int term,
            String outcome,
            LocalDate applied,
            ZoneId zone,
            String currency,
            Random random) {
        UUID id = UUID.randomUUID();
        Instant created =
                applied.minusDays(1).atTime(10, random.nextInt(60)).atZone(zone).toInstant();
        Instant submitted = applied.atTime(9, random.nextInt(60)).atZone(zone).toInstant();
        Instant appraised = submitted.plus(2 + random.nextInt(20), ChronoUnit.HOURS);
        Instant decided = appraised.plus(1 + random.nextInt(6), ChronoUnit.HOURS);
        boolean approved = outcome.equals("approved");
        boolean wasAppraised = !outcome.equals("submitted") && !outcome.equals("cancelled");
        jdbc.sql("""
                        INSERT INTO lending_loans (id, tenant_id, created_at, branch_id, loan_no, member_id,
                            product_version_id, officer_user_id, status, channel, purpose_category, purpose_text, currency,
                            requested_principal_minor, requested_term_count, approved_principal_minor, approved_term_count,
                            term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern,
                            instalment_frequency, proposed_disbursement_date, submitted_by, submitted_at, appraised_by,
                            approved_by, approved_at, rejected_reason, cancelled_reason, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, 'staff', 'business',
                            'Fabricated stock purchase', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        Timestamp.from(created),
                        branch,
                        "LN%06d".formatted(sequences.next("loan_no")),
                        member.id(),
                        p.versionId(),
                        officer,
                        outcome,
                        currency,
                        principal,
                        term,
                        approved ? principal : null,
                        approved ? term : null,
                        p.termUnit(),
                        p.method(),
                        p.rateBp(),
                        p.rateUnit(),
                        p.pattern(),
                        p.frequency(),
                        Date.valueOf(applied.plusDays(1)),
                        officer,
                        Timestamp.from(submitted),
                        wasAppraised ? appraiser : null,
                        approved ? manager : null,
                        approved ? Timestamp.from(decided) : null,
                        outcome.equals("rejected") ? "Fabricated: repayment capacity too low" : null,
                        outcome.equals("cancelled") ? "Fabricated: withdrawn by the member" : null,
                        officer)
                .update();
        if (wasAppraised) {
            int score = switch (outcome) {
                case "rejected" -> 20 + random.nextInt(30);
                default -> 45 + random.nextInt(50);
            };
            String band = score >= 80 ? "A" : score >= 65 ? "B" : score >= 50 ? "C" : "D";
            String recommendation = score >= 65 ? "approve" : score >= 50 ? "review" : "decline";
            jdbc.sql("""
                            INSERT INTO lending_loan_appraisals (id, tenant_id, created_at, loan_id, appraised_by,
                                declared_monthly_income_minor, monthly_obligations_minor, visit_notes, score, band,
                                components, flags, exposure, weights, recommendation)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 0, 'Fabricated visit', ?, ?,
                                '{"fabricated": true}'::jsonb, '{}', '{"fabricated": true}'::jsonb,
                                '{"fabricated": true}'::jsonb, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            Timestamp.from(appraised),
                            id,
                            appraiser,
                            1_000_000L,
                            score,
                            band,
                            recommendation)
                    .update();
        }
        List<String> path = switch (outcome) {
            case "submitted" -> List.of("draft", "submitted");
            case "appraised" -> List.of("draft", "submitted", "appraised");
            case "cancelled" -> List.of("draft", "submitted", "cancelled");
            case "rejected" -> List.of("draft", "submitted", "appraised", "rejected");
            default -> List.of("draft", "submitted", "appraised", "approved");
        };
        String from = null;
        for (String to : path) {
            Instant at = switch (to) {
                case "draft" -> created;
                case "submitted" -> submitted;
                case "appraised" -> appraised;
                default -> decided;
            };
            UUID by = switch (to) {
                case "appraised" -> appraiser;
                case "approved", "rejected" -> manager;
                default -> officer;
            };
            jdbc.sql("""
                            INSERT INTO lending_loan_status_history (id, tenant_id, created_at, loan_id, from_status,
                                to_status, changed_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            """)
                    .params(UUID.randomUUID(), Timestamp.from(at), id, from, to, by)
                    .update();
            from = to;
        }
        return id;
    }
}
