package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bodies of the retail reports (chapter 7 section 7.11.20). Cost fields are absent without retail.profit.read. */
final class ReportsApi {

    static final String COST_ONLY = "Present only with retail.profit.read";
    static final String PERCENT =
            "; expected profit over value at cost in basis points (2500 is 25 percent), rounded half up; absent when the value at cost is not above zero";

    static final String DIFFERENCE =
            "Committed stock-take differences at cost: negative for a loss, positive for a gain; included in profit_minor";

    static final String BAD_DEBT =
            "The loss of credit sales written off on the day (ADR-043); included in profit_minor as a minus. With on_payment the"
                    + " unpaid share of the cost of goods, with on_sale the whole unpaid amount";

    private ReportsApi() {}

    @Schema(name = "RetailValuationRow")
    record ValuationRow(
            UUID branchId,
            UUID productId,
            String code,
            String description,
            UUID categoryId,
            String category,
            String unit,
            String qty,
            boolean negative,
            long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "qty times the current sell price; absent when amount_out_of_range")
            Long expectedSalesMinor,

            @Schema(
                    description =
                            "True when qty times a price is too large to hold; the row's values are absent and left out of the totals")
            boolean amountOutOfRange,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + "; qty times the current cost")
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; expected sales less value at cost; absent when amount_out_of_range")
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

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
            Long revaluationDifferenceMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; expected sales less value at cost")
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailValuationCategory", description = "Totals of every reported branch for one category")
    record CategoryTotal(
            UUID categoryId,
            String category,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailValuation")
    record Valuation(
            LocalDate asOf,
            String currency,
            List<ValuationRow> rows,
            List<BranchTotal> branches,
            List<CategoryTotal> categories,
            long expectedSalesMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long valueAtCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + "; expected sales less value at cost")
            Long expectedProfitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + PERCENT)
            Long expectedProfitBp) {}

    @Schema(name = "RetailDailyProfitRow")
    record ProfitRow(
            UUID branchId,
            LocalDate date,
            long salesMinor,
            long costOfSalesMinor,
            long grossProfitMinor,
            long usageCostMinor,

            @Schema(description = BAD_DEBT) long badDebtMinor,
            @Schema(description = DIFFERENCE) long stocktakeDifferenceMinor,
            long profitMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only when credit_sale_recognition is on_payment: everything sold on the day, credit"
                                    + " sales included (Sold). Then sales_minor is the sales counted for profit (sales paid at the sale,"
                                    + " the profit share of each payment on a credit sale counted when the buyer pays, and credit counted at"
                                    + " the sale before the rule started) and the profit columns follow it; received_minor is the cash"
                                    + " (ADR-043)")
            Long soldMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with on_payment: the money recorded as collected on the day it was entered, by any method"
                                    + " (cash, mobile money, bank) (Received): sales paid at the sale and every payment recorded on a credit"
                                    + " sale, whatever the sale's date and whether or not it counts when the buyer pays, imported credit sales"
                                    + " included; less payments reversed (on the day of the reversal) and money paid back on returns (on the"
                                    + " day of the return, at the refund branch; given back on the day a return is voided). A payment dated"
                                    + " earlier than it was entered counts on the day it was entered. Sales paid at the sale count on the day they"
                                    + " were entered; cancelling a cash sale removes it from its own day. Not a profit basis")
            Long receivedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with on_payment: the profit counted at the sale for credit sales (not imported ones)"
                                    + " entered and dated before the rule started, net of their returns (informational; part of the profit,"
                                    + " never subtracted from Received)")
            Long countedAtSaleMinor) {}

    @Schema(name = "RetailDailyProfit")
    record DailyProfit(
            LocalDate from,
            LocalDate to,
            String currency,
            List<ProfitRow> rows,
            long salesMinor,
            long costOfSalesMinor,
            long usageCostMinor,
            @Schema(description = BAD_DEBT) long badDebtMinor,
            @Schema(description = DIFFERENCE) long stocktakeDifferenceMinor,
            long profitMinor,

            @Schema(
                    allowableValues = {"on_sale", "on_payment"},
                    description =
                            "How credit sales were counted (ADR-043): on_sale when they are made, on_payment when the buyer pays")
            String creditSaleRecognition,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with on_payment: everything sold in the period, credit sales included (Sold)")
            Long soldMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with on_payment: what buyers still owe now on the credit sales counted when the buyer"
                                    + " pays, all dates (On credit); not part of the period. Credit sales from before the rule started"
                                    + " were counted at the sale and are not in it")
            Long onCreditMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The first business day of the rule on_payment, when it was chosen; it can lie in the future. credit_sale_recognition"
                                    + " is on_payment once that day has come, whatever days the period spans")
            LocalDate creditRecognitionStartsOn,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with on_payment: the money recorded as collected on the day it was entered, by any method"
                                    + " (cash, mobile money, bank) (Received): sales paid at the sale and every payment recorded on a credit"
                                    + " sale, whatever the sale's date and whether or not it counts when the buyer pays, imported credit sales"
                                    + " included; less payments reversed (on the day of the reversal) and money paid back on returns (on the"
                                    + " day of the return, at the refund branch; given back on the day a return is voided). A payment dated"
                                    + " earlier than it was entered counts on the day it was entered. Sales paid at the sale count on the day they"
                                    + " were entered; cancelling a cash sale removes it from its own day. Not a profit basis")
            Long receivedMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with on_payment: the profit counted at the sale for credit sales (not imported ones)"
                                    + " entered and dated before the rule started, net of their returns (informational; part of the profit,"
                                    + " never subtracted from Received)")
            Long countedAtSaleMinor) {}
}
