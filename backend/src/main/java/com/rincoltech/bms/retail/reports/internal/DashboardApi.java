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

            @Schema(
                    description = PROFIT_ONLY
                            + "; sales less the cost snapshots. With credit_sale_recognition on_payment: the profit of the"
                            + " money received today, a part payment giving part of the sale's profit (ADR-043)")
            Long grossProfitMinor,

            @Schema(
                    description = "Only with on_payment: money recorded as collected today, by any method, for "
                            + "sales paid at the sale and for payments on credit sales, less money paid back "
                            + "(Received). sales_minor is then Sold, everything sold today")
            Long receivedMinor,

            @Schema(
                    description = "Only with on_payment: what buyers still owe on the credit sales counted when "
                            + "the buyer pays, all dates (On credit)")
            Long onCreditMinor,

            @Schema(description = "Only with on_payment: the number of credit sales still owing")
            Integer onCreditCount) {}

    @Schema(name = "RetailDashboardDay")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Day(
            LocalDate date,
            long salesMinor,
            @Schema(description = PROFIT_ONLY) Long grossProfitMinor,

            @Schema(
                    description =
                            "Only with on_payment: money recorded as collected on the day, by any method (Received)")
            Long receivedMinor) {}

    @Schema(name = "RetailDashboardStock")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stock(
            @Schema(description = PROFIT_ONLY + " (ADR-055); stock above zero at the current sell price")
            Long valueAtPriceMinor,

            @Schema(description = PROFIT_ONLY + "; stock above zero at the current cost")
            Long valueAtCostMinor,

            @Schema(
                    description = "Active items whose balance is zero or less, as the stock list judges it: a "
                            + "branch alone, or the sum over the branches reported; only items one of them "
                            + "has held (a balance row or a movement, issue #490)")
            int outOfStock,

            @Schema(
                    description = "Active items at or below their minimum at a branch, else low_stock_threshold "
                            + "(ADR-029), at a branch that has held them (issue #490), out of stock ones "
                            + "included, as the stock list's low tab counts them")
            int lowStock,

            String lowStockThreshold) {}

    @Schema(name = "RetailDashboardShop")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Shop(
            UUID branchId,
            @Schema(description = SALES_ONLY) Long todaySalesMinor,

            @Schema(description = SALES_ONLY + "; absent with a range")
            Long last7SalesMinor,

            @Schema(description = SALES_ONLY + "; absent with a range")
            Long last30SalesMinor,

            @Schema(description = "Present only with retail.profit.read in this branch")
            Long todayGrossProfitMinor,

            @Schema(description = "Present only with retail.profit.read in this branch (ADR-055)")
            Long stockAtPriceMinor,

            @Schema(description = "Present only with retail.profit.read in this branch")
            Long stockAtCostMinor,

            @Schema(description = STOCK_ONLY) Integer outOfStock,
            @Schema(description = STOCK_ONLY) Integer lowStock,

            @Schema(
                    description = "Only with on_payment, and with retail.sale.read in this branch: money "
                            + "collected today in this shop")
            Long todayReceivedMinor,

            @Schema(description = "With a range (issue #428) and " + SALES_ONLY + ": sales in the range")
            Long rangeSalesMinor,

            @Schema(
                    description =
                            "With a range that has a period before it, and " + SALES_ONLY + ": sales in that period")
            Long previousSalesMinor,

            @Schema(description = "With a range, and with retail.profit.read in this branch: profit in the range")
            Long rangeGrossProfitMinor) {}

    @Schema(
            name = "RetailDashboardFigures",
            description = "The figures of a range or of the period before it (issue #428)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Figures(
            @Schema(description = "Everything sold, credit included, net of goods taken back")
            long salesMinor,

            long saleCount,

            @Schema(description = "sales_minor over sale_count, half up; absent with no sale")
            Long averageSaleMinor,

            @Schema(description = PROFIT_ONLY + "; by the rule of credit_sale_recognition, as on the Today card")
            Long grossProfitMinor,

            @Schema(description = "Only with on_payment: money recorded as collected, by any method (Received)")
            Long receivedMinor) {}

    @Schema(name = "RetailDashboardPoint", description = "One step of the range chart: a day, seven days or a month")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Point(
            @Schema(description = "First day of the step") LocalDate start,
            long salesMinor,
            @Schema(description = PROFIT_ONLY) Long grossProfitMinor,

            @Schema(description = "Sales of the same step of the period before, counted from its first day")
            Long previousSalesMinor) {}

    @Schema(name = "RetailDashboardBusiestDay")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BusiestDay(
            LocalDate date,
            long salesMinor,

            @Schema(description = "The busiest day of the period before")
            LocalDate previousDate,

            Long previousSalesMinor) {}

    @Schema(
            name = "RetailDashboardLeader",
            description = "The best shop, the best seller (an item) or the top salesperson of the range;"
                    + " previous_sales_minor is the same one's sales in the period before")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Leader(
            UUID id,

            @Schema(description = "The item code; an item only")
            String code,

            @Schema(description = "The item or person name; a shop is named from the branch list")
            String label,

            long salesMinor,
            long saleCount,
            Long previousSalesMinor) {}

    @Schema(name = "RetailDashboardRange", description = "The range chosen on the dashboard (issue #428)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Range(
            @Schema(allowableValues = {"today", "7d", "30d", "ytd", "1y", "all", "custom"})
            String period,

            LocalDate from,
            LocalDate to,

            @Schema(
                    description = "The period before, as long as the range (This year: the same days of last "
                            + "year); absent for all")
            LocalDate previousFrom,

            LocalDate previousTo,

            @Schema(
                    allowableValues = {"day", "week", "month"},
                    description = "A step of the chart: a day up to 62 days, seven days from the first day up to "
                            + "a year, else a month")
            String group,

            Figures current,
            Figures previous,
            List<Point> series,

            @Schema(description = "The day with the most sales; absent with no sale")
            BusiestDay busiestDay,

            @Schema(description = "The shop with the most sales, among the shops in scope")
            Leader bestShop,

            @Schema(description = "The item with the most sales")
            Leader bestSeller,

            @Schema(
                    description = "The person with the most sales; only with retail.profit.read in every branch "
                            + "covered, as the salesperson level of the sales summary")
            Leader topSalesperson) {}

    @Schema(name = "RetailDashboard")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(
            LocalDate date,
            String currency,

            @Schema(description = "True when profit figures are included")
            boolean profitVisible,

            Today today,

            @Schema(description = "The last 30 days ending today, one entry a day, oldest first; absent with a range")
            List<Day> days,

            long last7SalesMinor,
            long last30SalesMinor,
            @Schema(description = PROFIT_ONLY) Long last7GrossProfitMinor,
            @Schema(description = PROFIT_ONLY) Long last30GrossProfitMinor,
            @Schema(description = STOCK_ONLY) Stock stock,
            List<Shop> shops,

            @Schema(
                    allowableValues = {"on_sale", "on_payment"},
                    description = "How credit sales are counted (ADR-043): on_sale when they are made, "
                            + "on_payment when the buyer pays")
            String creditSaleRecognition,

            @Schema(description = "Only with on_payment: money collected in the last 7 days")
            Long last7ReceivedMinor,

            @Schema(description = "Only with on_payment: money collected in the last 30 days")
            Long last30ReceivedMinor,

            @Schema(
                    description = "The first business day of the rule on_payment, when it was chosen; it can lie "
                            + "in the future. credit_sale_recognition is on_payment once that day has come")
            LocalDate creditRecognitionStartsOn,

            @Schema(description = "Only when a period or dates were asked for (issue #428)")
            Range range) {}
}
