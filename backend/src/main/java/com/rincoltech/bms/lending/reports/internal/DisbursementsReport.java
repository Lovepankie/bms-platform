package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.DisbursementFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.DisbursementGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.disbursements}: the loans paid out in a range (chapter 14 section 14.3, permission
 * {@code lending.reports.portfolio}), through {@link InsightsFigures#disbursements}. Two tables:
 *
 * <ul>
 *   <li>{@code loans}: each loan disbursed, by date then loan number: loan, date, member number, branch, product,
 *       officer, principal and the upfront fee taken at disbursement; the last row is the total.
 *   <li>{@code summary}: per group and in a total row, the loans, principal disbursed, upfront fees, and the average and
 *       median loan.
 * </ul>
 *
 * The detail table is written first and as the loans are read, so a long range never sits in memory; the summary
 * comes from the database's own totals.
 */
@Component
class DisbursementsReport extends RangeReport {

    static final String KEY = "lending.disbursements";

    DisbursementsReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Loans disbursed";
    }

    @Override
    public String description() {
        return "Every loan paid out in a date range, with the upfront fees and the average and median loan, in your branches.";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        ParGroupBy by = groupBy(prepared);
        out.note("A disbursement that was reversed is left out, and so is its upfront fee.");
        ReportOutput.Table loans = out.table(
                "loans",
                "Loans disbursed",
                List.of(
                        new Column("loan_no", "Loan", Kind.TEXT),
                        new Column("disbursed_on", "Date", Kind.DATE),
                        new Column("member_no", "Member", Kind.TEXT),
                        new Column("branch", "Branch", Kind.TEXT),
                        new Column("product", "Product", Kind.TEXT),
                        new Column("officer", "Officer", Kind.TEXT),
                        new Column("principal", "Principal", Kind.MONEY),
                        new Column("upfront_fee", "Upfront fee", Kind.MONEY)));
        DisbursementFigures f = figures.disbursements(
                scope(prepared),
                by,
                out::checkpoint,
                l -> loans.row(
                        l.loanNo(),
                        l.disbursedOn(),
                        l.memberNo(),
                        l.branch(),
                        l.product(),
                        l.officer(),
                        l.principalMinor(),
                        l.feesMinor()));
        DisbursementGroup t = f.total();
        loans.total(
                "Total, " + t.loans() + (t.loans() == 1 ? " loan" : " loans"),
                null,
                null,
                null,
                null,
                null,
                t.principalMinor(),
                t.feesMinor());

        ReportOutput.Table summary = out.table(
                "summary",
                "Summary",
                List.of(
                        new Column("group", "Group", Kind.TEXT),
                        new Column("loans", "Loans", Kind.COUNT),
                        new Column("principal", "Principal disbursed", Kind.MONEY),
                        new Column("upfront_fees", "Upfront fees", Kind.MONEY),
                        new Column("average", "Average loan", Kind.MONEY),
                        new Column("median", "Median loan", Kind.MONEY)));
        for (DisbursementGroup g : f.groups()) {
            summary.row(g.label(), g.loans(), g.principalMinor(), g.feesMinor(), g.averageMinor(), g.medianMinor());
        }
        summary.total(
                by == ParGroupBy.NONE ? "All loans" : "Total",
                t.loans(),
                t.principalMinor(),
                t.feesMinor(),
                t.averageMinor(),
                t.medianMinor());
    }
}
