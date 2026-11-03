package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.RegisterFigures;
import com.rincoltech.bms.lending.insights.RegisterFigures.Book;
import com.rincoltech.bms.lending.insights.RegisterFigures.Count;
import com.rincoltech.bms.lending.insights.RegisterFigures.ProductRates;
import com.rincoltech.bms.lending.insights.RegisterFigures.RegulatorySummary;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.regulatory_summary} (chapter 14 section 14.7, permission {@code lending.reports.compliance}): the figures
 * a regulator's return usually asks for, over a period, through {@link RegisterFigures#regulatory}. It is labelled "Data
 * summary. Not a filed return." until the pilot tenant's return formats are known. Parameters {@code from} (the first of
 * the month of {@code to} when absent), {@code to} (today when absent) and {@code branch_id}; the scope is the insights
 * page's, resolved and frozen as for the other lending reports, so it also needs {@code lending.insights.read}.
 *
 * <p>Six tables: {@code loans} (the book, disbursements, write-offs, savings and investments, each a number and an
 * amount), {@code risk} (PAR 30 and PAR 90), {@code people} (borrowers, branches, staff), {@code borrowers} (active
 * borrowers by gender), {@code products} (the book by product with the rate range and the weighted average rate) and
 * {@code purposes} (the book by loan purpose). Each total is the sum of its rows. A figure with nothing to measure (PAR
 * on an empty book) prints blank ({@link #blankMissingFigures}).
 */
@Component
class RegulatorySummaryReport extends PortfolioReport {

    static final String KEY = "lending.regulatory_summary";
    static final String COVER = "Data summary. Not a filed return.";

    /** A date in a note as the report prints dates: {@code 7 Oct 2026}. */
    static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

    private final RegisterFigures registers;

    RegulatorySummaryReport(InsightsFigures figures, ObjectMapper mapper, RegisterFigures registers) {
        super(figures, mapper);
        this.registers = registers;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Regulator data summary";
    }

    @Override
    public String description() {
        return "The figures a regulator's return usually needs for a period: borrowers, loans, portfolio at risk, rates,"
                + " savings, investments, branches and staff. A data summary, not a filed return.";
    }

    @Override
    public String permission() {
        return "lending.reports.compliance";
    }

    /** PAR on an empty book, or the rate of a product owing nothing, prints blank, not as a dash. */
    @Override
    public boolean blankMissingFigures() {
        return true;
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param("from", "date", false, "The first day of the period; the first of the month when absent."),
                new Param("to", "date", false, "The last day of the period; today when absent."),
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."));
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        LocalDate from = ReportParams.date(params, "from", null);
        LocalDate to = ReportParams.date(params, "to", null);
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        PortfolioScope scope = figures.scope(from, to, branches, null, null);
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("from", scope.from().toString());
        normalised.put("to", scope.to().toString());
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = mapper.convertValue(scope, Map.class);
        return new Prepared(normalised, frozen, scope.to());
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        RegulatorySummary s = registers.regulatory(scope(prepared), out::checkpoint);
        out.note(COVER);
        out.note("The loan book is read from the "
                + (s.source().equals("live") ? "loan tables" : "nightly snapshot of " + DATE.format(s.to()))
                + " at the end of the period; its PAR 30 and PAR 90 are those of the portfolio at risk report.");
        out.note("Only active loans are in the book; loans written off are not.");
        // A caller limited to their own loans gets no savings, investments, branches or staff: none is an officer's.
        boolean ownLoans = s.savingsMinor() == null;
        if (ownLoans) {
            out.note("Savings, investments, branches and staff are not shown: they belong to no loan officer, and this"
                    + " report covers your own loans only.");
        } else {
            out.note("Open investments, branches and staff are counted as they are now.");
        }
        out.note("A figure with nothing to measure, such as PAR on an empty book, is left blank.");

        // Three short tables rather than one with a column per kind, so no cell of a figure is left empty.
        ReportOutput.Table loans = out.table(
                "loans",
                "Loans, savings and investments",
                List.of(
                        new Column("figure", "Figure", Kind.TEXT),
                        new Column("count", "Number", Kind.COUNT),
                        new Column("amount", "Amount", Kind.MONEY)));
        loans.row("Active loans and principal outstanding", s.activeLoans(), s.principalOutstandingMinor());
        loans.row("Loans disbursed in the period", s.disbursedLoans(), s.disbursedMinor());
        loans.row("Loans written off in the period", s.writtenOffLoans(), s.writtenOffMinor());
        if (s.savingsMinor() != null) {
            loans.row("Savings held from members (accounts, balance)", s.savingsAccounts(), s.savingsMinor());
            loans.row("Investments held from members (open, principal)", s.investments(), s.investmentsMinor());
        }

        ReportOutput.Table risk = out.table(
                "risk",
                "Portfolio at risk",
                List.of(new Column("figure", "Figure", Kind.TEXT), new Column("rate_bp", "Rate", Kind.BASIS_POINTS)));
        risk.row("Portfolio at risk over 30 days (PAR 30)", s.par30Bp());
        risk.row("Portfolio at risk over 90 days (PAR 90)", s.par90Bp());

        ReportOutput.Table people = out.table(
                "people",
                "Borrowers, branches and staff",
                List.of(new Column("figure", "Figure", Kind.TEXT), new Column("count", "Number", Kind.COUNT)));
        people.row("Active borrowers at the end of the period", s.borrowers());
        if (!ownLoans) {
            people.row("Branches", s.branches());
            people.row("Staff", s.staff());
        }

        ReportOutput.Table borrowers = out.table(
                "borrowers",
                "Active borrowers by gender",
                List.of(new Column("gender", "Gender", Kind.TEXT), new Column("borrowers", "Borrowers", Kind.COUNT)));
        // Each total is the sum of its own rows, so the rows always add up to it.
        long byGender = 0;
        for (Count c : s.borrowersByGender()) {
            borrowers.row(c.label(), c.count());
            byGender += c.count();
        }
        borrowers.total("Total", byGender);

        ReportOutput.Table products = out.table(
                "products",
                "Loan book by product, with interest rates",
                List.of(
                        new Column("product", "Product", Kind.TEXT),
                        new Column("rate_unit", "Rate", Kind.TEXT),
                        new Column("loans", "Active loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("min_rate_bp", "Lowest rate", Kind.BASIS_POINTS),
                        new Column("max_rate_bp", "Highest rate", Kind.BASIS_POINTS),
                        new Column("weighted_rate_bp", "Weighted average rate", Kind.BASIS_POINTS)));
        for (ProductRates r : s.byProduct()) {
            products.row(
                    r.product(),
                    r.rateUnit(),
                    r.loans(),
                    r.principalOutstandingMinor(),
                    r.minRateBp(),
                    r.maxRateBp(),
                    r.weightedRateBp());
        }

        ReportOutput.Table purposes = out.table(
                "purposes",
                "Loan book by purpose",
                List.of(
                        new Column("purpose", "Purpose", Kind.TEXT),
                        new Column("loans", "Active loans", Kind.COUNT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY)));
        long purposeLoans = 0;
        long purposePo = 0;
        for (Book b : s.byPurpose()) {
            purposes.row(b.label(), b.loans(), b.principalOutstandingMinor());
            purposeLoans += b.loans();
            purposePo += b.principalOutstandingMinor();
        }
        purposes.total("Total", purposeLoans, purposePo);
    }
}
