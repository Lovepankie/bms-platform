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
                    description =
                            "Only with on_payment: money recorded as collected today, by any method, for sales paid at the sale and"
                                    + " for payments on credit sales, less money paid back (Received). sales_minor is then Sold,"
                                    + " everything sold today")
            Long receivedMinor,

            @Schema(
                    description =
                            "Only with on_payment: what buyers still owe on the credit sales counted when the buyer pays, all dates (On credit)")
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

            @Schema(description = "Present only with retail.profit.read in this branch (ADR-055)")
            Long stockAtPriceMinor,

            @Schema(description = "Present only with retail.profit.read in this branch")
            Long stockAtCostMinor,

            @Schema(description = STOCK_ONLY) Integer outOfStock,
            @Schema(description = STOCK_ONLY) Integer lowStock,

            @Schema(
                    description =
                            "Only with on_payment, and with retail.sale.read in this branch: money collected today in this shop")
            Long todayReceivedMinor) {}

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
            List<Shop> shops,

            @Schema(
                    allowableValues = {"on_sale", "on_payment"},
                    description =
                            "How credit sales are counted (ADR-043): on_sale when they are made, on_payment when the buyer pays")
            String creditSaleRecognition,

            @Schema(description = "Only with on_payment: money collected in the last 7 days")
            Long last7ReceivedMinor,

            @Schema(description = "Only with on_payment: money collected in the last 30 days")
            Long last30ReceivedMinor,

            @Schema(
                    description =
                            "The first business day of the rule on_payment, when it was chosen; it can lie in the future."
                                    + " credit_sale_recognition is on_payment once that day has come")
            LocalDate creditRecognitionStartsOn) {}
}
