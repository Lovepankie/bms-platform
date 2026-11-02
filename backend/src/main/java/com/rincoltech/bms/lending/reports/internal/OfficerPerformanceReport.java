package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.OfficerFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.OfficerGroup;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.officer_performance} (FR-CLN-06; chapter 14 section 14.4, permission {@code lending.reports.collections}):
 * one row per responsible officer and a total row, through {@link InsightsFigures#officers}. At the end of the range:
 * active loans, principal outstanding and PAR 30. Over the range: loans disbursed and their principal, expected,
 * collected on due and the collection rate, the collection actions logged on their loans, and the promises to pay settled
 * kept and broken. A loan officer without {@code lending.insights.all_officers} gets their own row only.
 */
@Component
class OfficerPerformanceReport extends RangeReport {

    static final String KEY = "lending.officer_performance";

    /** A date in a note as the report prints dates: {@code 6 Oct 2026}. */
    static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

    OfficerPerformanceReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    @Override
    boolean grouped() {
        return false;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Officer performance";
    }

    @Override
    public String description() {
        return "For each loan officer: the loans they look after, what they paid out and collected in a date range, and their follow up.";
    }

    @Override
    public String permission() {
        return "lending.reports.collections";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        OfficerFigures f = figures.officers(scope(prepared), out::checkpoint);
        out.note("Active loans, principal outstanding and PAR 30 are read from the "
                + (f.source().equals("live") ? "loan tables" : "nightly snapshot of " + DATE.format(f.to()))
                + ", at the end of the range.");
        out.note(
                "Each loan counts for the officer responsible for it now; actions and promises count by that officer too.");

        ReportOutput.Table table = out.table(
                "officers",
                "Officer performance",
                List.of(
                        new Column("officer", "Officer", Kind.TEXT),
                        new Column("active_loans", "Active loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("par30_bp", "PAR 30", Kind.BASIS_POINTS),
                        new Column("disbursed_loans", "Loans disbursed", Kind.COUNT),
                        new Column("disbursed", "Disbursed", Kind.MONEY),
                        new Column("expected", "Expected", Kind.MONEY),
                        new Column("collected_on_due", "Collected on due", Kind.MONEY),
                        new Column("collection_rate_bp", "Collection rate", Kind.BASIS_POINTS),
                        new Column("actions", "Collection actions", Kind.COUNT),
                        new Column("promises_kept", "Promises kept", Kind.COUNT),
                        new Column("promises_broken", "Promises broken", Kind.COUNT)));
        for (OfficerGroup g : f.groups()) {
            table.row(cells(g.label(), g));
        }
        table.total(cells("Total", f.total()));
    }

    private static Object[] cells(String label, OfficerGroup g) {
        return new Object[] {
            label,
            g.activeLoans(),
            g.principalOutstandingMinor(),
            g.par30Bp(),
            g.disbursedLoans(),
            g.disbursedMinor(),
            g.expectedMinor(),
            g.collectedOnDueMinor(),
            g.collectionRateBp(),
            g.actions(),
            g.promisesKept(),
            g.promisesBroken()
        };
    }
}
