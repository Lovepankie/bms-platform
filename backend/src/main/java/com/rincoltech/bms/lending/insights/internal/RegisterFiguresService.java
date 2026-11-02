package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.RegisterFigures;
import com.rincoltech.bms.lending.investments.InvestmentMetrics;
import com.rincoltech.bms.lending.savings.SavingsMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RegisterFigures} (issue #225). The collateral register is read from the collateral, valuation, event and pledge
 * tables with plain SQL, read only, as the insights module reads every other lending table (ADR-030 decision 1). The
 * regulator summary is assembled from figures that already exist: the loan book and PAR of {@link InsightsFigures#par},
 * aggregates over the same positions ({@link Positions#overLoans}), the disbursements and write-offs of the insights page
 * ({@link InsightsQueries}), and the savings and investment panels' metrics; it adds the counts of accounts, branches and
 * staff.
 */
@Service
class RegisterFiguresService implements RegisterFigures {

    static final Map<String, String> TYPES = Map.of(
            "land_title", "Land title",
            "vehicle_logbook", "Vehicle logbook",
            "vehicle", "Vehicle",
            "national_id", "National ID",
            "household_item", "Household item",
            "other", "Other");

    static final Map<String, String> CUSTODY = Map.of(
            "pledged", "Pledged",
            "in_custody", "In our custody",
            "released", "Released",
            "seized", "Seized",
            "disposed", "Disposed");

    static final Map<String, String> LOAN_STATUS = Map.ofEntries(
            Map.entry("draft", "draft"),
            Map.entry("submitted", "submitted"),
            Map.entry("appraised", "appraised"),
            Map.entry("approved", "approved"),
            Map.entry("rejected", "rejected"),
            Map.entry("cancelled", "cancelled"),
            Map.entry("active", "active"),
            Map.entry("closed", "closed"),
            Map.entry("written_off", "written off"));

    static final List<String> GENDERS = List.of("female", "male", "other", "unspecified");
    static final Map<String, String> GENDER_LABELS =
            Map.of("female", "Women", "male", "Men", "other", "Other", "unspecified", "Not recorded");

    static final Map<String, String> PURPOSES = new LinkedHashMap<>();

    static {
        PURPOSES.put("business", "Business");
        PURPOSES.put("agriculture", "Agriculture");
        PURPOSES.put("school_fees", "School fees");
        PURPOSES.put("medical", "Medical");
        PURPOSES.put("household", "Household");
        PURPOSES.put("construction", "Construction");
        PURPOSES.put("other", "Other");
    }

    static final Map<String, String> RATE_UNITS = Map.of(
            "per_term", "per term",
            "per_day", "per day",
            "per_week", "per week",
            "per_month", "per month",
            "per_year", "per year");

    /** The note an import registers an item with (chapter 13 section 13.6.8), lower case. */
    static final String IMPORT_NOTE = "imported; verify custody and value";

    /** The loan statuses after which an item's pledge no longer secures anything. */
    private static final String FINISHED = "('closed', 'cancelled', 'rejected')";

    private final JdbcClient jdbc;
    private final JdbcClient streaming;
    private final InsightsFigures figures;
    private final Positions positions;
    private final InsightsQueries queries;
    // Looked up when a summary is made, not at start: the investments module reaches the report registry through the
    // approvals and the documents, so a constructor dependency would be a cycle.
    private final ObjectProvider<SavingsMetrics> savings;
    private final ObjectProvider<InvestmentMetrics> investments;
    private final CurrentTenant tenant;
    private final InsightsService service;

    RegisterFiguresService(
            InsightsService service,
            JdbcClient jdbc,
            javax.sql.DataSource dataSource,
            InsightsFigures figures,
            Positions positions,
            InsightsQueries queries,
            ObjectProvider<SavingsMetrics> savings,
            ObjectProvider<InvestmentMetrics> investments,
            CurrentTenant tenant) {
        this.jdbc = jdbc;
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(Positions.FETCH);
        this.streaming = JdbcClient.create(template);
        this.figures = figures;
        this.positions = positions;
        this.queries = queries;
        this.savings = savings;
        this.investments = investments;
        this.tenant = tenant;
        this.service = service;
    }

    @Override
    public LocalDate today() {
        return service.today();
    }

    // ---- collateral register ------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public void collateral(CollateralFilter filter, Runnable checkpoint, Consumer<CollateralLine> lines) {
        checkpoint.run();
        if (filter.branchIds() != null && filter.branchIds().isEmpty()) {
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(" WHERE true");
        if (filter.branchIds() != null) {
            where.append(" AND ci.branch_id IN (:branches)");
            p.put("branches", filter.branchIds());
        }
        if (filter.type() != null) {
            where.append(" AND ci.collateral_type = :type");
            p.put("type", filter.type());
        }
        if (filter.custodyStatus() != null) {
            where.append(" AND ci.custody_status = :custody");
            p.put("custody", filter.custodyStatus());
        }
        if (filter.officerId() != null) {
            // A loan officer's register: the items that secure one of their loans (chapter 7, own loans only).
            where.append(
                    " AND EXISTS (SELECT 1 FROM lending_loan_collateral lc JOIN lending_loans l ON l.id = lc.loan_id"
                            + " WHERE lc.collateral_id = ci.id AND l.officer_user_id = :officer)");
            p.put("officer", filter.officerId());
        }
        // The Loans column names only the officer's own loans; release overdue still reads every loan of the item.
        String ownLoans = filter.officerId() != null ? " AND l.officer_user_id = :officer" : "";
        p.put("import_note", IMPORT_NOTE);
        String sql =
                """
                SELECT m.branch_id AS member_branch_id, b.name AS branch, m.member_no, m.full_name, ci.collateral_type,
                       ci.description, ci.reference_no,
                       ci.estimated_value_minor, v.market_value_minor, v.valued_on, ci.custody_status, ci.storage_location,
                       (SELECT string_agg(l.loan_no || ':' || l.status, ',' ORDER BY l.loan_no)
                          FROM lending_loan_collateral lc JOIN lending_loans l ON l.id = lc.loan_id
                         WHERE lc.collateral_id = ci.id""" + ownLoans + """
                       ) AS loans,
                       (ci.custody_status IN ('pledged', 'in_custody')
                        AND EXISTS (SELECT 1 FROM lending_loan_collateral lc WHERE lc.collateral_id = ci.id)
                        AND NOT EXISTS (SELECT 1 FROM lending_loan_collateral lc JOIN lending_loans l ON l.id = lc.loan_id
                                         WHERE lc.collateral_id = ci.id AND l.status NOT IN """ + FINISHED + """
                        )) AS release_overdue,
                       (v.valued_on IS NULL
                        AND EXISTS (SELECT 1 FROM lending_collateral_events e
                                     WHERE e.collateral_id = ci.id AND e.event_type = 'registered'
                                       AND lower(trim(coalesce(e.note, ''))) = :import_note)
                        AND NOT EXISTS (SELECT 1 FROM lending_collateral_events e
                                         WHERE e.collateral_id = ci.id AND e.event_type <> 'registered')) AS unverified
                  FROM lending_collateral_items ci
                  JOIN branches b ON b.id = ci.branch_id
                  JOIN lending_members m ON m.id = ci.member_id
                  LEFT JOIN LATERAL (SELECT cv.market_value_minor, cv.valued_on FROM lending_collateral_valuations cv
                                      WHERE cv.collateral_id = ci.id
                                      ORDER BY cv.valued_on DESC, cv.created_at DESC LIMIT 1) v ON true
                """ + where + " ORDER BY b.name, m.member_no, ci.created_at, ci.id";
        long[] seen = {0};
        try (java.util.stream.Stream<CollateralLine> rows = streaming
                .sql(sql)
                .params(p)
                .query((rs, n) -> {
                    // Who owns an item (the member's number and name, the reference, which can be an ID number, a plate
                    // or a title number, and the description, where staff often type the same) is shown only in the
                    // branches of member access, by the member's branch. Where it is kept is ours, so it stays.
                    boolean owner = filter.nameBranchIds() == null
                            || filter.nameBranchIds().contains(rs.getObject("member_branch_id", UUID.class));
                    return new CollateralLine(
                            rs.getString("branch"),
                            owner ? rs.getString("member_no") : null,
                            owner ? rs.getString("full_name") : null,
                            TYPES.getOrDefault(rs.getString("collateral_type"), rs.getString("collateral_type")),
                            owner ? rs.getString("description") : null,
                            owner ? rs.getString("reference_no") : null,
                            rs.getObject("estimated_value_minor", Long.class),
                            rs.getObject("market_value_minor", Long.class),
                            rs.getObject("valued_on", LocalDate.class),
                            CUSTODY.getOrDefault(rs.getString("custody_status"), rs.getString("custody_status")),
                            rs.getString("storage_location"),
                            loansInWords(rs.getString("loans")),
                            rs.getBoolean("release_overdue"),
                            rs.getBoolean("unverified"),
                            rs.getObject("valued_on") == null);
                })
                .stream()) {
            rows.forEach(l -> {
                if ((++seen[0] % InsightsFiguresService.CHECKPOINT_EVERY) == 0) {
                    checkpoint.run();
                }
                lines.accept(l);
            });
        }
        checkpoint.run();
    }

    /** {@code LN0001:active,LN0002:closed} reads {@code LN0001 (active), LN0002 (closed)}. */
    static String loansInWords(String loans) {
        if (loans == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String one : loans.split(",")) {
            int colon = one.lastIndexOf(':');
            String status = one.substring(colon + 1);
            out.add(one.substring(0, colon) + " (" + LOAN_STATUS.getOrDefault(status, status) + ")");
        }
        return String.join(", ", out);
    }

    // ---- regulator summary --------------------------------------------------------------------------

    private record Rates(UUID product, String unit, long loans, long po, long min, long max, BigDecimal weighted) {}

    @Override
    @Transactional(readOnly = true)
    public RegulatorySummary regulatory(PortfolioScope p, Runnable checkpoint) {
        // The book and its PAR are lending.par's own; it also applies the date rules (live, snapshot or refused).
        ParFigures par = figures.par(p, ParGroupBy.NONE, checkpoint);
        boolean live = par.source().equals("live");
        String currency = tenant.profile().currency();
        Scope s = new Scope(p.from(), p.to(), p.today(), p.branchIds(), p.officerId(), p.productId(), currency);

        Map<String, Long> gender = new LinkedHashMap<>();
        positions.overLoans(
                s,
                p.to(),
                live,
                "coalesce(mb.gender, 'unspecified') AS g, count(DISTINCT x.member_id) AS n",
                "1",
                (rs, n) -> gender.put(rs.getString("g"), rs.getLong("n")));
        // A value the list does not know is counted as not recorded, so the rows always add up to the borrowers.
        long unknownGender = gender.entrySet().stream()
                .filter(e -> !GENDERS.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        List<Count> byGender = new ArrayList<>();
        for (String g : GENDERS) {
            long n = gender.getOrDefault(g, 0L) + (g.equals("unspecified") ? unknownGender : 0);
            byGender.add(new Count(GENDER_LABELS.get(g), n));
        }
        long borrowers = positions.borrowers(s, p.to(), live, null).total();
        checkpoint.run();

        Map<String, long[]> purpose = new LinkedHashMap<>();
        positions.overLoans(
                s,
                p.to(),
                live,
                "ln.purpose_category AS g, count(*) AS n, coalesce(sum(x.po), 0) AS po",
                "1",
                (rs, n) -> purpose.put(rs.getString("g"), new long[] {rs.getLong("n"), rs.getLong("po")}));
        List<Book> byPurpose = new ArrayList<>();
        for (Map.Entry<String, String> e : PURPOSES.entrySet()) {
            long[] v = purpose.get(e.getKey());
            if (v != null) {
                byPurpose.add(new Book(e.getValue(), v[0], v[1]));
            }
        }
        // A purpose the list does not know, or none, is one row, so the rows always add up to the book's total.
        long[] unknownPurpose = new long[2];
        purpose.forEach((k, v) -> {
            if (k == null || !PURPOSES.containsKey(k)) {
                unknownPurpose[0] += v[0];
                unknownPurpose[1] += v[1];
            }
        });
        if (unknownPurpose[0] > 0) {
            byPurpose.add(new Book("Not recorded", unknownPurpose[0], unknownPurpose[1]));
        }

        List<Rates> rates = positions.overLoans(
                s,
                p.to(),
                live,
                "x.product_id, ln.rate_unit, count(*) AS n, coalesce(sum(x.po), 0) AS po,"
                        + " min(ln.interest_rate_bp) AS lo, max(ln.interest_rate_bp) AS hi,"
                        + " sum(ln.interest_rate_bp::numeric * x.po) AS weighted",
                "1, 2",
                (rs, n) -> new Rates(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("rate_unit"),
                        rs.getLong("n"),
                        rs.getLong("po"),
                        rs.getLong("lo"),
                        rs.getLong("hi"),
                        rs.getBigDecimal("weighted")));
        Map<UUID, String> productNames = queries.productNames();
        List<ProductRates> byProduct = new ArrayList<>();
        for (Rates r : rates) {
            Long weighted = r.po() == 0
                    ? null
                    : r.weighted()
                            .divide(BigDecimal.valueOf(r.po()), 0, RoundingMode.HALF_UP)
                            .longValueExact();
            byProduct.add(new ProductRates(
                    productNames.getOrDefault(r.product(), "Product"),
                    RATE_UNITS.getOrDefault(r.unit(), r.unit()),
                    r.loans(),
                    r.po(),
                    r.min(),
                    r.max(),
                    weighted));
        }
        byProduct.sort(Comparator.comparing(ProductRates::product).thenComparing(ProductRates::rateUnit));
        checkpoint.run();

        long[] disbursed = sum(queries.disbursedByDay(s));
        long[] writtenOff = sum(queries.writtenOffByDay(s));

        // Savings and investments have no responsible officer: a caller limited to their own loans sees none, as on the
        // insights page.
        Long savingsMinor = null;
        Long savingsAccounts = null;
        Long investmentsMinor = null;
        Long investmentCount = null;
        if (p.officerId() == null) {
            savingsMinor = metric(savings.getObject().metrics(p.from(), p.to(), p.branchIds()), "savings.balances");
            savingsAccounts = savingsAccounts(p.to(), p.branchIds());
            List<InvestmentMetrics.Metric> inv = investments.getObject().metrics(p.from(), p.to(), p.branchIds());
            investmentsMinor = inv.stream()
                    .filter(m -> m.key().equals(InvestmentMetrics.KEY + ".balance"))
                    .map(InvestmentMetrics.Metric::value)
                    .findFirst()
                    .orElse(0L);
            investmentCount = inv.stream()
                    .filter(m -> m.key().equals(InvestmentMetrics.KEY + ".open_count"))
                    .map(InvestmentMetrics.Metric::value)
                    .findFirst()
                    .orElse(0L);
        }
        checkpoint.run();

        return new RegulatorySummary(
                p.from(),
                p.to(),
                par.source(),
                currency,
                byGender,
                borrowers,
                par.total().loans(),
                par.total().principalOutstandingMinor(),
                par.total().par30Bp(),
                par.total().par90Bp(),
                byProduct,
                byPurpose,
                disbursed[1],
                disbursed[0],
                writtenOff[1],
                writtenOff[0],
                savingsMinor,
                savingsAccounts,
                investmentsMinor,
                investmentCount,
                branches(p.branchIds()),
                staff(p.branchIds()));
    }

    private static long[] sum(List<InsightsQueries.DayTotal> days) {
        long amount = 0;
        long count = 0;
        for (InsightsQueries.DayTotal d : days) {
            amount += d.amountMinor();
            count += d.count();
        }
        return new long[] {amount, count};
    }

    private static Long metric(List<SavingsMetrics.Metric> metrics, String key) {
        return metrics.stream()
                .filter(m -> m.key().equals(key))
                .map(SavingsMetrics.Metric::value)
                .findFirst()
                .orElse(0L);
    }

    /** Savings accounts opened on or before {@code to} and not closed by then. */
    private long savingsAccounts(LocalDate to, List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT count(*) FROM lending_savings_accounts a WHERE a.opened_on <= :to"
                        + " AND (a.closed_on IS NULL OR a.closed_on > :to)"
                        + (branchIds == null ? "" : " AND a.branch_id IN (:branches)"))
                .param("to", Date.valueOf(to))
                .params(branchIds == null ? Map.of() : Map.of("branches", branchIds))
                .query(Long.class)
                .single();
    }

    private long branches(List<UUID> branchIds) {
        if (branchIds != null) {
            return branchIds.isEmpty()
                    ? 0
                    : jdbc.sql("SELECT count(*) FROM branches WHERE id IN (:ids) AND status = 'active'")
                            .param("ids", branchIds)
                            .query(Long.class)
                            .single();
        }
        return jdbc.sql("SELECT count(*) FROM branches WHERE status = 'active'")
                .query(Long.class)
                .single();
    }

    /** Active staff with a role that is not revoked, in one of the branches or in every branch. */
    private long staff(List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(DISTINCT u.id) FROM users u
                          JOIN user_role_assignments ra ON ra.user_id = u.id AND ra.revoked_at IS NULL
                         WHERE u.kind = 'staff' AND u.status = 'active'""" + (branchIds == null ? "" : " AND (ra.branch_id IS NULL OR ra.branch_id IN (:branches))"))
                .params(branchIds == null ? Map.of() : Map.of("branches", branchIds))
                .query(Long.class)
                .single();
    }
}
