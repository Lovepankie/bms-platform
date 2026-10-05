package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bodies of the retail reports (chapter 7 section 7.11.20). Cost fields are absent without retail.profit.read. */
final class ReportsApi {

    static final String COST_ONLY = "Present only with retail.profit.read";

    private ReportsApi() {}

    @Schema(name = "RetailValuationRow")
    record ValuationRow(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            String unit,
            String qty,
            boolean negative,
            long sellMinor,

            @Schema(description = "qty times the current sell price")
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + "; qty times the current cost")
            Long valueAtCostMinor) {}

    @Schema(name = "RetailValuationBranch")
    record BranchTotal(
            UUID branchId,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; the inventory account's balance for the branch")
            Long inventoryAccountMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            COST_ONLY
                                    + "; value at cost less the inventory account: the revaluation difference of ADR-020 decision 8")
            Long revaluationDifferenceMinor) {}

    @Schema(name = "RetailValuation")
    record Valuation(
            LocalDate asOf,
            String currency,
            List<ValuationRow> rows,
            List<BranchTotal> branches,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor) {}

    @Schema(name = "RetailDailyProfitRow")
    record ProfitRow(
            UUID branchId,
            LocalDate date,
            long salesMinor,
            long costOfSalesMinor,
            long grossProfitMinor,
            long usageCostMinor,
            long profitMinor) {}

    @Schema(name = "RetailDailyProfit")
    record DailyProfit(
            LocalDate from,
            LocalDate to,
            String currency,
            List<ProfitRow> rows,
            long salesMinor,
            long costOfSalesMinor,
            long usageCostMinor,
            long profitMinor) {}
}
