package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportPack;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.monthly_pack}: the monthly report pack (ADR-038 decision 4, issue #229; chapter 14 section 14.10). The
 * seven formal reports of the board pack (collections is two: expected and collected, and the arrears list), run as one
 * from one snapshot, as one PDF with a cover, contents and sign off, and one workbook.
 *
 * <p>Parameters: {@code period_end} (today when absent) and {@code branch_id}. The month runs from the first day of the
 * month of the period end to the period end: the reports over dates cover that range, the reports at a date stand at the
 * period end (a past date reads its nightly snapshot), and the collateral register is the items as they stand when the
 * pack is made (custody and value are not kept by date). The portfolio, PAR, collections and disbursement figures are split
 * by branch, with a total.
 *
 * <p>The framework requires {@code lending.reports.portfolio} (this report's own permission) and every report's
 * permission, and prepares each report with its own rules, so a loan officer's pack holds only their own loans and a name
 * or phone is printed only in the branches where the requester may see it, as when each report is run alone.
 */
@Component
class MonthlyPackReport extends PortfolioReport implements ReportPack {

    static final String KEY = "lending.monthly_pack";

    /** The reports of the pack, in the order chapter 14 section 14.10 lists them. */
    static final List<String> PARTS = List.of(
            PortfolioOutstandingReport.KEY,
            ParReport.KEY,
            ExpectedVsCollectedReport.KEY,
            ArrearsListReport.KEY,
            DisbursementsReport.KEY,
            OfficerPerformanceReport.KEY,
            CollateralRegisterReport.KEY,
            RegulatorySummaryReport.KEY);

    MonthlyPackReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Monthly report pack";
    }

    @Override
    public String description() {
        return "All eight lending reports for one month from one reading of your books: one PDF with a cover, contents and"
                + " sign off, and one Excel workbook. Needs the permission of every report.";
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param(
                        "period_end",
                        "date",
                        false,
                        "The last day the pack covers, usually the last day of a month; today when absent. The month runs"
                                + " from the first day of that month."),
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."));
    }

    @Override
    public Set<String> formats() {
        return Set.of(ReportPack.FORMAT);
    }

    @Override
    public List<String> parts() {
        return PARTS;
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        LocalDate asked = ReportParams.date(params, "period_end", null);
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        // The port resolves the range as the insights page does (no future date, the caller's branches and
        // officers) and takes the business date once; an absent end is today, and the month is the end's.
        PortfolioScope scope =
                figures.scope(asked == null ? null : asked.withDayOfMonth(1), asked, branches, null, null);
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("period_end", scope.to().toString());
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = mapper.convertValue(scope, Map.class);
        return new Prepared(normalised, frozen, scope.to());
    }

    @Override
    public Map<String, Object> partParams(String partKey, Prepared pack) {
        LocalDate to = pack.asOf();
        Map<String, Object> out = new LinkedHashMap<>();
        switch (partKey) {
            case PortfolioOutstandingReport.KEY, ParReport.KEY -> {
                out.put("as_at", to.toString());
                out.put("group_by", "branch");
            }
            case ArrearsListReport.KEY -> out.put("as_at", to.toString());
            case ExpectedVsCollectedReport.KEY, DisbursementsReport.KEY -> {
                out.put("from", to.withDayOfMonth(1).toString());
                out.put("to", to.toString());
                out.put("group_by", "branch");
            }
            case OfficerPerformanceReport.KEY, RegulatorySummaryReport.KEY -> {
                out.put("from", to.withDayOfMonth(1).toString());
                out.put("to", to.toString());
            }
            default -> {
                // The collateral register takes no date: the items as they stand.
            }
        }
        Object branches = pack.params().get("branch_id");
        if (branches != null) {
            out.put("branch_id", branches);
        }
        return out;
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        throw new IllegalStateException("A pack is run through its parts.");
    }
}
