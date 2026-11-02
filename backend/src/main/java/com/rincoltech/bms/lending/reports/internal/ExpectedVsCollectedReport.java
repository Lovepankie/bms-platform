package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.CollectionFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.CollectionGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.expected_vs_collected}: what was due in a range against what came in (chapter 14 section 14.4,
 * permission {@code lending.reports.collections}). The figures are the insights page's own through
 * {@link InsightsFigures#collections}; this class lays them out as two tables.
 *
 * <ul>
 *   <li>{@code collections}: per group and in a total row, expected, collected on due, the collection rate, and the
 *       parts of the total collected (paid in the range on items due in it, arrears recovered, prepayments, penalties,
 *       overpayments, recoveries on written-off loans) and the total collected.
 *   <li>{@code equation}: the collections equation of section 14.4 for the whole selection, a line per part and the
 *       total; a line "Not explained by the parts" is printed only if the parts and the total ever differ.
 * </ul>
 */
@Component
class ExpectedVsCollectedReport extends RangeReport {

    static final String KEY = "lending.expected_vs_collected";

    ExpectedVsCollectedReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Expected and collected";
    }

    @Override
    public String description() {
        return "What was due in a date range against what was paid, the collection rate, and how the money collected adds up.";
    }

    @Override
    public String permission() {
        return "lending.reports.collections";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        ParGroupBy by = groupBy(prepared);
        CollectionFigures f = figures.collections(scope(prepared), by, out::checkpoint);
        out.note(
                "Expected is the principal, interest and fees due in the range on loans active in it; penalties are not"
                        + " scheduled, so they are not expected.");
        out.note(
                "Collected on due is what was paid toward those amounts by the end of the range, including early payments.");
        out.note(
                "Total collected is the repayments and recoveries dated in the range; reversed payments are left out.");

        ReportOutput.Table table = out.table(
                "collections",
                "Expected and collected",
                List.of(
                        new Column("group", "Group", Kind.TEXT),
                        new Column("expected", "Expected", Kind.MONEY),
                        new Column("collected_on_due", "Collected on due", Kind.MONEY),
                        new Column("collection_rate_bp", "Collection rate", Kind.BASIS_POINTS),
                        new Column("paid_on_due_in_range", "Paid in range on items due in range", Kind.MONEY),
                        new Column("arrears_recovered", "Arrears recovered", Kind.MONEY),
                        new Column("prepayments", "Prepayments", Kind.MONEY),
                        new Column("penalties", "Penalties collected", Kind.MONEY),
                        new Column("overpayments", "Overpayments", Kind.MONEY),
                        new Column("recoveries", "Recoveries", Kind.MONEY),
                        new Column("total_collected", "Total collected", Kind.MONEY)));
        for (CollectionGroup g : f.groups()) {
            table.row(cells(g.label(), g));
        }
        table.total(cells(by == ParGroupBy.NONE ? "All loans" : "Total", f.total()));

        CollectionGroup t = f.total();
        ReportOutput.Table equation = out.table(
                "equation",
                "How the total collected adds up",
                List.of(new Column("part", "Part", Kind.TEXT), new Column("amount", "Amount", Kind.MONEY)));
        equation.row("Paid in the range on items due in the range", t.paidOnDueInRangeMinor());
        equation.row("Plus arrears recovered (items due before the range)", t.arrearsRecoveredMinor());
        equation.row("Plus prepayments (items due after the range)", t.prepaymentsMinor());
        equation.row("Plus penalties collected", t.penaltiesMinor());
        equation.row("Plus overpayments", t.overpaymentsMinor());
        equation.row("Plus recoveries on loans written off", t.recoveriesMinor());
        if (t.unexplainedMinor() != 0) {
            equation.row("Not explained by the parts", t.unexplainedMinor());
        }
        equation.total("Equals total collected", t.totalCollectedMinor());
    }

    private static Object[] cells(String label, CollectionGroup g) {
        return new Object[] {
            label,
            g.expectedMinor(),
            g.collectedOnDueMinor(),
            g.collectionRateBp(),
            g.paidOnDueInRangeMinor(),
            g.arrearsRecoveredMinor(),
            g.prepaymentsMinor(),
            g.penaltiesMinor(),
            g.overpaymentsMinor(),
            g.recoveriesMinor(),
            g.totalCollectedMinor()
        };
    }
}
