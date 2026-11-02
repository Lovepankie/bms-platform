package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * A lending report over a date range (chapter 14 sections 14.3 and 14.4, slice D3, issue #222): the parameters
 * {@code from} and {@code to} (inclusive; the first of the month of {@code to} and today when absent, as the insights
 * page), {@code branch_id} and, when {@link #grouped()}, {@code group_by}. The range is resolved by the insights module
 * against the caller's permissions exactly as its page does (the same range errors, the same branches, the caller's own
 * loans without {@code lending.insights.all_officers}) and frozen into the run; the figures stand at the end of the
 * range. Download time and the scope line are those of {@link PortfolioReport}.
 */
abstract class RangeReport extends PortfolioReport {

    RangeReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    /** True when the report takes {@code group_by}. */
    boolean grouped() {
        return true;
    }

    @Override
    public List<Param> params() {
        List<Param> out = new ArrayList<>();
        out.add(new Param("from", "date", false, "The first day of the range; the first of the month when absent."));
        out.add(new Param("to", "date", false, "The last day of the range; today when absent."));
        out.add(new Param(
                "branch_id", "uuid_list", false, "Branches to include; every branch in your scope when absent."));
        if (grouped()) {
            out.add(new Param(
                    "group_by",
                    "enum",
                    false,
                    "Split the figures by branch, product or officer; none gives the total only.",
                    GROUPS));
        }
        return out;
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        LocalDate from = ReportParams.date(params, "from", null);
        LocalDate to = ReportParams.date(params, "to", null);
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        String groupBy = grouped() ? ReportParams.choice(params, "group_by", GROUPS, "none") : null;
        PortfolioScope scope = figures.scope(from, to, branches, null, null);
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("from", scope.from().toString());
        normalised.put("to", scope.to().toString());
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        if (groupBy != null) {
            normalised.put("group_by", groupBy);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = mapper.convertValue(scope, Map.class);
        return new Prepared(normalised, frozen, scope.to());
    }
}
