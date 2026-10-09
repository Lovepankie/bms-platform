package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body of the business future evaluation (issue #149). Every figure here is profit or cost, so the whole
 * route needs retail.profit.read. These are estimates from current prices and recent sales, not forecasts.
 */
final class EvaluationApi {

    private EvaluationApi() {}

    @Schema(
            name = "RetailEvaluationPeriod",
            description = "What was sold in the chosen period, from the sale line snapshots")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Period(
            long salesMinor,

            @Schema(description = "Sales less the cost snapshots of the sale lines; usage and damage are not in it")
            long profitMinor,

            @Schema(description = "Profit over sales in basis points, half up; absent when sales are not above zero")
            Long marginBp) {}

    @Schema(
            name = "RetailEvaluationStock",
            description = "The stock on hand valued at today's prices; stock at or below zero is left out")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stock(
            long atCostMinor,
            long atPriceMinor,

            @Schema(description = "Stock at price less stock at cost")
            long expectedProfitMinor,

            @Schema(
                    description =
                            "Expected profit over stock at cost in basis points, half up; absent when cost is zero")
            Long overCostBp) {}

    @Schema(name = "RetailEvaluationRunRate", description = "The last 30 days of sales as a daily pace")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunRate(
            long salesMinor,
            long profitMinor,

            @Schema(description = "Sales over 30, rounded half up to the minor unit")
            long avgDailySalesMinor,

            long avgDailyProfitMinor,

            @Schema(description = "Stock at price over the daily sales, one place; absent when nothing sold")
            String daysOfStock,

            @Schema(description = "True when the days of stock are over 365 and show 365.0")
            boolean daysOfStockCapped) {}

    @Schema(name = "RetailEvaluationProduct")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Product(
            UUID productId,
            String code,
            String description,
            String unit,
            String qtyOnHand,
            Period period,
            Stock stock) {}

    @Schema(name = "RetailEvaluationCategory")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Category(
            UUID categoryId,
            String category,
            Period period,
            Stock stock,

            @Schema(description = "Items in the category with sales in the period or stock, before the row limit")
            int productsTotal,

            @Schema(description = "The largest expected profit first, at most top")
            List<Product> products) {}

    @Schema(name = "RetailEvaluationBranch")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Branch(UUID branchId, Period period, Stock stock, RunRate runRate, List<Category> categories) {}

    @Schema(name = "RetailEvaluationTotal")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Total(Period period, Stock stock, RunRate runRate) {}

    @Schema(name = "RetailBusinessEvaluation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(
            LocalDate from,
            LocalDate to,
            String currency,
            int top,
            int runRateDays,

            @Schema(description = "A plain statement that these are estimates, not forecasts")
            String note,

            Total total,
            List<Branch> branches) {}
}
