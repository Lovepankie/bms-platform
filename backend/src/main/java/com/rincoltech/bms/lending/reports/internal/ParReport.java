package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParBucket;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class ParReport extends PortfolioReport {

    static final String KEY = "lending.par";

    private static final Map<String, String> BUCKET_LABELS = Map.of(
            "current", "Current (not late)",
            "1_30", "1 to 30 days late",
            "31_60", "31 to 60 days late",
            "61_90", "61 to 90 days late",
            "over_90", "Over 90 days late");

    ParReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
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
    public void query(Prepared prepared, ReportOutput out) {
        ParGroupBy by = groupBy(prepared);
        ParFigures f = figures.par(scope(prepared), by, out::checkpoint);
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
                        new Column("par1_bp", "PAR 1", Kind.BASIS_POINTS),
                        new Column("par30_bp", "PAR 30", Kind.BASIS_POINTS),
                        new Column("par60_bp", "PAR 60", Kind.BASIS_POINTS),
                        new Column("par90_bp", "PAR 90", Kind.BASIS_POINTS)));
        List<ParGroup> rows = new ArrayList<>(f.groups());
        rows.add(f.total());
        String totalLabel = by == ParGroupBy.NONE ? "All loans" : "Total";
        for (ParGroup g : f.groups()) {
            par.row(
                    g.label(),
                    g.loans(),
                    g.principalOutstandingMinor(),
                    g.arrearsMinor(),
                    g.par1Bp(),
                    g.par30Bp(),
                    g.par60Bp(),
                    g.par90Bp());
        }
        ParGroup t = f.total();
        // The total is the last row of the table, set off in the PDF and the workbook.
        par.total(
                totalLabel,
                t.loans(),
                t.principalOutstandingMinor(),
                t.arrearsMinor(),
                t.par1Bp(),
                t.par30Bp(),
                t.par60Bp(),
                t.par90Bp());

        ReportOutput.Table ageing = out.table(
                "ageing",
                "Ageing of the active loans",
                List.of(
                        new Column("group", "Group", Kind.TEXT),
                        new Column("bucket", "Age", Kind.TEXT),
                        new Column("loans", "Loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("share_bp", "Share of principal", Kind.BASIS_POINTS),
                        new Column("arrears", "Arrears", Kind.MONEY)));
        for (ParGroup g : rows) {
            String label = g == f.total() ? totalLabel : g.label();
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
