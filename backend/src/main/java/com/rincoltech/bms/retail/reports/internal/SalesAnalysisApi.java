package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of the sales analysis (issue #149, chapter 7 section 7.11.20). Cost and profit are absent without retail.profit.read. */
final class SalesAnalysisApi {

    static final String PROFIT_ONLY = "Present only with retail.profit.read in every branch the report covers";

    static final String STOCK_ONLY =
            "Present only with retail.stock.read and retail.sale.read in the branches the report covers";

    private SalesAnalysisApi() {}

    @Schema(name = "RetailSalesAnalysisTotals")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Totals(
            long salesMinor,
            long saleCount,

            @Schema(description = "Units sold, summed over lines of every unit of measure; a rough volume only")
            String qty,

            @Schema(description = PROFIT_ONLY + "; sales less the cost snapshots of the sale lines")
            Long grossProfitMinor,

            @Schema(description = PROFIT_ONLY + "; gross profit over sales in basis points, half up")
            Long marginBp) {}

    @Schema(name = "RetailSalesAnalysisPeriod")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Period(
            @Schema(description = "First day of the day, ISO week (Monday) or month")
            LocalDate periodStart,

            long salesMinor,
            long saleCount,
            @Schema(description = PROFIT_ONLY) Long grossProfitMinor,
            @Schema(description = PROFIT_ONLY) Long marginBp) {}

    @Schema(
            name = "RetailSalesAnalysisRow",
            description = "One branch, category, product or seller. code and unit are set for a product only.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Row(
            UUID id,
            String code,
            String label,
            String unit,
            String qty,
            long salesMinor,
            long saleCount,
            @Schema(description = PROFIT_ONLY) Long grossProfitMinor,
            @Schema(description = PROFIT_ONLY) Long marginBp) {}

    @Schema(name = "RetailStockedItem", description = "A product with stock on hand, summed over the reported branches")
    record StockedItem(
            UUID productId,
            String code,
            String description,
            String unit,
            String qtyOnHand,

            @Schema(description = "Quantity on hand times the current sell price")
            long stockAtPriceMinor) {}

    @Schema(name = "RetailSalesAnalysis")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Analysis(
            LocalDate from,
            LocalDate to,
            String currency,
            @Schema(description = "day, week or month") String group,
            int top,

            @Schema(description = "True when cost and profit figures are included")
            boolean profitVisible,

            Totals totals,
            List<Period> series,
            List<Row> byBranch,
            List<Row> byCategory,

            @Schema(description = "The top products by sales")
            List<Row> byProduct,

            @Schema(description = "The top products by quantity")
            List<Row> topByQuantity,

            List<Row> bySeller,
            int slowDays,

            @Schema(
                    description = "Items with stock on hand and no sale in the last slow_days days, as of today. "
                            + STOCK_ONLY)
            List<StockedItem> slowMovers,

            @Schema(description = STOCK_ONLY) Integer slowMoversTotal,

            @Schema(description = "Active items with no sale at all in the range. " + STOCK_ONLY)
            List<StockedItem> noSales,

            @Schema(description = STOCK_ONLY) Integer noSalesTotal) {}
}
