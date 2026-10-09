package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of the owner dashboard (issue #149). Profit parts need retail.profit.read, stock parts retail.stock.read. */
final class DashboardApi {

    static final String PROFIT_ONLY = "Present only with retail.profit.read in every branch the report covers";
    static final String SALES_ONLY = "Present only with retail.sale.read in this branch";
    static final String STOCK_ONLY = "Present only with retail.stock.read";

    private DashboardApi() {}

    @Schema(name = "RetailDashboardToday")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Today(
            long salesMinor,
            long saleCount,

            @Schema(description = "Sales paid at the sale: cash, mobile money or bank")
            long cashMinor,

            @Schema(description = "Sales on credit, whether or not paid since")
            long creditMinor,

            @Schema(description = PROFIT_ONLY + "; sales less the cost snapshots")
            Long grossProfitMinor) {}

    @Schema(name = "RetailDashboardDay")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Day(
            LocalDate date,
            long salesMinor,
            @Schema(description = PROFIT_ONLY) Long grossProfitMinor) {}

    @Schema(name = "RetailDashboardStock")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stock(
            long valueAtPriceMinor,

            @Schema(description = PROFIT_ONLY + "; stock above zero at the current cost")
            Long valueAtCostMinor,

            @Schema(
                    description =
                            "Active items whose balance is zero or less, as the stock list judges it: a branch alone, or the sum over the branches reported")
            int outOfStock,

            @Schema(
                    description =
                            "Active items at or below low_stock_threshold (ADR-029), out of stock ones included, as the stock list's low tab counts them")
            int lowStock,

            String lowStockThreshold) {}

    @Schema(name = "RetailDashboardShop")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Shop(
            UUID branchId,
            @Schema(description = SALES_ONLY) Long todaySalesMinor,
            @Schema(description = SALES_ONLY) Long last7SalesMinor,
            @Schema(description = SALES_ONLY) Long last30SalesMinor,

            @Schema(description = "Present only with retail.profit.read in this branch")
            Long todayGrossProfitMinor,

            @Schema(description = STOCK_ONLY) Long stockAtPriceMinor,

            @Schema(description = "Present only with retail.profit.read in this branch")
            Long stockAtCostMinor,

            @Schema(description = STOCK_ONLY) Integer outOfStock,
            @Schema(description = STOCK_ONLY) Integer lowStock) {}

    @Schema(name = "RetailDashboard")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(
            LocalDate date,
            String currency,

            @Schema(description = "True when profit figures are included")
            boolean profitVisible,

            Today today,

            @Schema(description = "The last 30 days ending today, one entry a day, oldest first")
            List<Day> days,

            long last7SalesMinor,
            long last30SalesMinor,
            @Schema(description = PROFIT_ONLY) Long last7GrossProfitMinor,
            @Schema(description = PROFIT_ONLY) Long last30GrossProfitMinor,
            @Schema(description = STOCK_ONLY) Stock stock,
            List<Shop> shops) {}
}
