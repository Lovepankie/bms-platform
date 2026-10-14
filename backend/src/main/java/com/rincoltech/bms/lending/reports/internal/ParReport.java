package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParBucket;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.par}: portfolio at risk and ageing (chapter 14 section 14.3, permission
 * {@code lending.reports.portfolio}). The figures are the insights page's own, through
 * {@link InsightsFigures}; this class only lays them out as two tables.
 *
 * <ul>
 *   <li>{@code par}: PAR 1, 30, 60 and 90 per group, as basis points of the group's principal outstanding (PAR x
 *       is the principal of active loans more than x days past due over the principal of all active loans),
 *       with the group's active loans, principal outstanding and arrears; the last row is the total.
 *   <li>{@code ageing}: per group and age bucket (current, 1 to 30, 31 to 60, 61 to 90, over 90 days past due) the
 *       loans, principal outstanding, share of the group's principal and arrears.
 * </ul>
 *
 * Written-off loans are not active and are in neither. A restructured loan's separate line (FR-LCL-04) arrives
 * with restructuring, which is not built yet.
 */
@Component
class ParReport implements ReportDefinition {

    static final String KEY = "lending.par";
    static final List<String> GROUPS = List.of("none", "branch", "product", "officer");

    private static final Map<String, String> BUCKET_LABELS = Map.of(
            "current", "Current (not late)",
            "1_30", "1 to 30 days late",
            "31_60", "31 to 60 days late",
            "61_90", "61 to 90 days late",
            "over_90", "Over 90 days late");

    private final InsightsFigures figures;
    private final ObjectMapper mapper;

    ParReport(InsightsFigures figures, ObjectMapper mapper) {
        this.figures = figures;
        this.mapper = mapper;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Portfolio at risk and ageing";
    }

    @Override
    public String description() {
        return "PAR 1, 30, 60 and 90 and the ageing of the active loans on a date, in your branches.";
    }

    @Override
    public String module() {
        return "lending";
    }

    @Override
    public String permission() {
        return "lending.reports.portfolio";
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param(
                        "as_at",
                        "date",
                        false,
                        "The date the loans stand at; today when absent. A past date reads the nightly snapshot."),
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."),
                new Param(
                        "group_by",
                        "enum",
                        false,
                        "Split the figures by branch, product or officer; none gives the total only.",
                        GROUPS));
    }

    @Override
    public Set<String> formats() {
        return Set.of("json", "csv");
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        LocalDate asked = ReportParams.date(params, "as_at", null);
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        String groupBy = ReportParams.choice(params, "group_by", GROUPS, "none");
        // One reading of the business date, taken by the port: an absent date is that day, and the scope keeps it, so a
        // request made as midnight passes cannot ask for yesterday with today's rules.
        PortfolioScope resolved = figures.scope(asked, asked, branches, null, null);
        LocalDate asAt = resolved.to();
        PortfolioScope scope = new PortfolioScope(
                asAt, asAt, resolved.today(), resolved.branchIds(), resolved.officerId(), resolved.productId());
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("as_at", asAt.toString());
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        normalised.put("group_by", groupBy);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = mapper.convertValue(scope, Map.class);
        return new Prepared(normalised, frozen, asAt);
    }

    @Override
    public boolean stillAllowed(Prepared run) {
        return figures.covers(mapper.convertValue(run.scope(), PortfolioScope.class));
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        PortfolioScope scope = mapper.convertValue(prepared.scope(), PortfolioScope.class);
        ParGroupBy by = ParGroupBy.valueOf(
                String.valueOf(prepared.params().get("group_by")).toUpperCase(Locale.ROOT));
        ParFigures f = figures.par(scope, by, out::checkpoint);
        out.note("Figures read from the "
                + (f.source().equals("live") ? "loan tables" : "nightly snapshot of " + f.asAt()) + ".");
        out.note("Only active loans are counted; written-off loans are not.");

        ReportOutput.Table par = out.table(
                "par",
                "Portfolio at risk",
                List.of(
                        new Column("group", "Group", Kind.TEXT),
                        new Column("active_loans", "Active loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("arrears", "Arrears", Kind.MONEY),
                        new Column("par1_bp", "PAR 1 (basis points)", Kind.BASIS_POINTS),
                        new Column("par30_bp", "PAR 30 (basis points)", Kind.BASIS_POINTS),
                        new Column("par60_bp", "PAR 60 (basis points)", Kind.BASIS_POINTS),
                        new Column("par90_bp", "PAR 90 (basis points)", Kind.BASIS_POINTS)));
        List<ParGroup> rows = new ArrayList<>(f.groups());
        rows.add(f.total());
        for (ParGroup g : rows) {
            par.row(
                    g == f.total() ? (by == ParGroupBy.NONE ? "All loans" : "Total") : g.label(),
                    g.loans(),
                    g.principalOutstandingMinor(),
                    g.arrearsMinor(),
                    g.par1Bp(),
                    g.par30Bp(),
                    g.par60Bp(),
                    g.par90Bp());
        }

        ReportOutput.Table ageing = out.table(
                "ageing",
                "Ageing of the active loans",
                List.of(
                        new Column("group", "Group", Kind.TEXT),
                        new Column("bucket", "Age", Kind.TEXT),
                        new Column("loans", "Loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("share_bp", "Share of principal (basis points)", Kind.BASIS_POINTS),
                        new Column("arrears", "Arrears", Kind.MONEY)));
        for (ParGroup g : rows) {
            String label = g == f.total() ? (by == ParGroupBy.NONE ? "All loans" : "Total") : g.label();
            for (ParBucket b : g.buckets()) {
                ageing.row(
                        label,
                        BUCKET_LABELS.get(b.bucket()),
                        b.loans(),
                        b.principalOutstandingMinor(),
                        b.shareBp(),
                        b.arrearsMinor());
            }
        }
    }
}
