package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body of the shop Home summary (issue #485, ADR-076): everything the Shop cards on Home show, in one answer. Each part is
 * absent for a caller who may not see it.
 */
final class HomeApi {

    static final String MONEY_ONLY =
            "Present only with retail.sale.all_sellers in every branch the summary covers (ADR-055): everybody's money";

    private HomeApi() {}

    @Schema(name = "RetailHomeSummary")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(
            @Schema(description = "The business day in the tenant's zone (Africa/Kampala by default)")
            LocalDate date,

            String currency,

            @Schema(description = "The Monday of this week: the first day of the top items of the week")
            LocalDate weekFrom,

            @Schema(description = MONEY_ONLY + "; the same figure as today on the dashboard")
            Sales salesToday,

            @Schema(description = MONEY_ONLY) Takings takings,
            @Schema(description = MONEY_ONLY) CreditOwed creditOwed,
            @Schema(description = MONEY_ONLY) TopItems topItems,

            @Schema(
                    description =
                            MONEY_ONLY
                                    + "; and only when the summary covers two or more shops: one line per shop, most sales first")
            List<BranchLine> branches,

            @Schema(
                    description = "Present only with retail.stock.read: active items low as the dashboard counts "
                            + "them, at or below their minimum or else the low stock threshold, at a branch "
                            + "that has held them (issue #490)")
            Integer lowStock,

            @Schema(
                    description = "Present only with retail.order.manage: customer orders ordered, packed or out "
                            + "for delivery")
            Integer openOrders) {}

    @Schema(name = "RetailHomeSales")
    record Sales(long salesMinor, long saleCount) {}

    @Schema(
            name = "RetailHomeTakings",
            description = "Money taken today by way of paying, read the way the cash book reads a day for every way:"
                    + " sales paid at the sale dated today, payments on credit sales dated today and delivery fees taken"
                    + " today, less sales cancelled today, payments on account reversed today and money refunded today on"
                    + " goods taken back. Besides retail.sale.all_sellers it needs, as the cash summary, retail.cashbook.read"
                    + " in every branch it covers and retail.till.close (ADR-076)")
    record Takings(long totalMinor, List<Method> methods) {}

    @Schema(name = "RetailHomeTakingsMethod")
    record Method(
            @Schema(
                    description =
                            "cash and mobile_money always, then any other way that took or gave back money today (bank)",
                    allowableValues = {"cash", "mobile_money", "bank"})
            String method,

            @Schema(description = "sales_minor + payments_minor + fees_minor - out_minor")
            long amountMinor,

            @Schema(description = "Sales paid at the sale dated today, cancelled later or not")
            long salesMinor,

            @Schema(description = "Payments on credit sales dated today, at the branch that took them")
            long paymentsMinor,

            @Schema(description = "Delivery fees taken at the door today, or paid today on an account")
            long feesMinor,

            @Schema(
                    description = "Money given back today: sales cancelled, payments on account reversed, "
                            + "refunds on goods taken back (a return cancelled today counts back in), door "
                            + "fees of cancelled orders")
            long outMinor) {}

    @Schema(name = "RetailHomeCreditOwed", description = "What buyers owe on credit sales, as Credit control counts it")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreditOwed(
            long owedMinor,

            @Schema(description = "Buyers that owe: a customer record, or a name typed with the sale")
            int customers,

            int sales,

            @Schema(description = "The earliest due date of a sale still owing; absent when none has a due date")
            LocalDate oldestDueDate) {}

    @Schema(
            name = "RetailHomeTopItems",
            description = "The five items that sold the most by value, today and this week")
    record TopItems(List<Item> today, List<Item> week) {}

    @Schema(name = "RetailHomeTopItem")
    record Item(
            @Schema(description = "The product, or the whole item for one with sizes and colours")
            UUID itemId,

            String code,
            String label,
            String unit,

            @Schema(description = "Base units, as a decimal string")
            String qty,

            long salesMinor) {}

    @Schema(name = "RetailHomeBranch")
    record BranchLine(UUID branchId, String name, long salesMinor, long saleCount) {}
}
