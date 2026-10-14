package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioGroup;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.portfolio_outstanding}: the loan book on a date (chapter 14 section 14.3, permission
 * {@code lending.reports.portfolio}). The figures are the insights page's own through {@link InsightsFigures}, from the
 * same positions as {@code lending.par}; this class only lays them out as one table: per group (the whole book, or a
 * branch, product or officer each) the active loans, their distinct borrowers, the principal and interest outstanding, the
 * arrears and the average principal per loan; the last row is the total.
 *
 * <p>Not here yet, and said on the report: fees and penalties outstanding, which the nightly snapshot does not hold (they
 * arrive with the arrears job of increment 6, which owns the snapshot), and the grouping by loan purpose.
 */
@Component
class PortfolioOutstandingReport extends PortfolioReport {

    static final String KEY = "lending.portfolio_outstanding";

    PortfolioOutstandingReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Loan portfolio outstanding";
    }

    @Override
    public String description() {
        return "The active loans on a date: borrowers, principal and interest outstanding, arrears and the average loan, in your branches.";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        ParGroupBy by = groupBy(prepared);
        PortfolioFigures f = figures.portfolio(scope(prepared), by, out::checkpoint);
        out.note("Figures read from the "
                + (f.source().equals("live") ? "loan tables" : "nightly snapshot of " + f.asAt()) + ".");
        out.note("Only active loans are counted; written-off loans are not.");
        out.note("Borrowers are counted once in each group, so the groups can add up to more than the total.");
        out.note("Fees and penalties outstanding are not shown: the loan book on a past date does not hold them yet.");
        out.note("The loans cannot be grouped by purpose: the loan book on a past date does not hold it yet.");

        ReportOutput.Table table = out.table(
                "portfolio",
                "Loan portfolio",
                List.of(
                        new Column("group", "Group", Kind.TEXT),
                        new Column("active_loans", "Active loans", Kind.COUNT),
                        new Column("borrowers", "Borrowers", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("interest_outstanding", "Interest outstanding", Kind.MONEY),
                        new Column("arrears", "Arrears", Kind.MONEY),
                        new Column("average_principal", "Average principal per loan", Kind.MONEY)));
        for (PortfolioGroup g : f.groups()) {
            table.row(
                    g.label(),
                    g.loans(),
                    g.borrowers(),
                    g.principalOutstandingMinor(),
                    g.interestOutstandingMinor(),
                    g.arrearsMinor(),
                    g.averagePrincipalMinor());
        }
        PortfolioGroup t = f.total();
        table.total(
                by == ParGroupBy.NONE ? "All loans" : "Total",
                t.loans(),
                t.borrowers(),
                t.principalOutstandingMinor(),
                t.interestOutstandingMinor(),
                t.arrearsMinor(),
                t.averagePrincipalMinor());
    }
}
