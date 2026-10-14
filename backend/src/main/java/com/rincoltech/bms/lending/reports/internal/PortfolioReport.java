package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * What the portfolio reports of the insights figures share (chapter 14 section 14.3, permission
 * {@code lending.reports.portfolio}): the module, the permission, the parameters ({@code as_at}, {@code branch_id},
 * {@code group_by}), the four formats, and the two moments of a run. At request time the filter is resolved by the
 * insights module against the caller's permissions (branches and officers, exactly as its endpoints do) with the business
 * date of the request, and frozen into the run; at download time {@link InsightsFigures#covers} asks whether the caller's
 * access still covers it. A report that reads the portfolio extends this and only lays its tables out.
 */
abstract class PortfolioReport implements ReportDefinition {

    static final List<String> GROUPS = List.of("none", "branch", "product", "officer");

    final InsightsFigures figures;
    final ObjectMapper mapper;

    PortfolioReport(InsightsFigures figures, ObjectMapper mapper) {
        this.figures = figures;
        this.mapper = mapper;
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
        return Set.of("json", "csv", "pdf", "xlsx");
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
    public String describeScope(Prepared run) {
        PortfolioScope scope = scope(run);
        String branches = scope.branchIds() == null
                ? "All branches in your access"
                : scope.branchIds().isEmpty()
                        ? "No branches"
                        : scope.branchIds().size() == 1
                                ? "1 branch selected"
                                : scope.branchIds().size() + " branches selected";
        // The officer is forced to the caller without lending.insights.all_officers (the reports offer no officer
        // filter).
        return scope.officerId() == null ? branches : branches + ", your own loans only";
    }

    /** The frozen scope of a run. */
    PortfolioScope scope(Prepared prepared) {
        return mapper.convertValue(prepared.scope(), PortfolioScope.class);
    }

    /** The grouping a run was asked for. */
    static ParGroupBy groupBy(Prepared prepared) {
        return ParGroupBy.valueOf(
                String.valueOf(prepared.params().get("group_by")).toUpperCase(Locale.ROOT));
    }
}
