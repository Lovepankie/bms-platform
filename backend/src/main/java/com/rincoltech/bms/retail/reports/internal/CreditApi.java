package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of the credit control report (issue #149). No cost or profit figure appears here. */
final class CreditApi {

    private CreditApi() {}

    @Schema(
            name = "RetailAgeing",
            description =
                    "What is owed on completed credit sales, by days past the due date as of today; a sale with no"
                            + " due date counts as not yet due")
    record Ageing(
            long owedMinor,
            long notDueMinor,
            long days1To30Minor,
            long days31To60Minor,
            long days61To90Minor,
            long over90Minor) {}

    @Schema(name = "RetailCreditBuyer")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Buyer(
            @Schema(description = "The credit buyer's id; absent for a buyer who was typed in by name")
            UUID customerId,

            String name,
            int saleCount,
            Ageing ageing,

            @Schema(description = "Days past the due date of the oldest overdue sale; absent when none is overdue")
            Integer oldestOverdueDays,

            @Schema(
                    description =
                            "The buyer's credit limit; present only while the customer_accounts capability is on and"
                                    + " the buyer has a record with a limit")
            Long creditLimitMinor,

            @Schema(
                    description =
                            "Owed is above the limit. Present only with a limit and only when the report covers every"
                                    + " branch, because the limit is measured across all of them")
            Boolean overLimit) {}

    @Schema(name = "RetailOverdueSale")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Overdue(
            UUID saleId,
            String saleNo,
            UUID branchId,
            UUID customerId,
            String buyerName,
            LocalDate saleDate,
            LocalDate dueDate,
            int daysOverdue,
            long totalMinor,
            long outstandingMinor) {}

    @Schema(
            name = "RetailCustomerAgeingRow",
            description = "A credit buyer with a record: what is owed, by days past the due date, with limit and terms")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AgeingCustomer(
            UUID customerId,
            String name,
            Long creditLimitMinor,
            Integer termsDays,
            int saleCount,
            Ageing ageing,

            @Schema(description = "Days past the due date of the oldest overdue sale; absent when none is overdue")
            Integer oldestOverdueDays,

            @Schema(
                    description =
                            "Owed is above the limit. Present only with a limit and only when the caller's scope covers"
                                    + " every branch, because the limit is measured across all of them")
            Boolean overLimit) {}

    @Schema(
            name = "RetailCustomerAgeing",
            description =
                    "Ageing per credit buyer with a record, as of today in the business time zone; a sale with no due"
                            + " date counts as not yet due. No cost or profit appears")
    record CustomerReport(
            LocalDate asOf,
            String currency,
            int top,

            @Schema(description = "Every buyer with a record in scope, whatever the row limit")
            Ageing totals,

            int customerCount,

            @Schema(description = "Buyers owing the most first")
            List<AgeingCustomer> customers) {}

    @Schema(
            name = "RetailPaymentDay",
            description =
                    "Payments received on a day by method. A reversed payment on account stays on the day it was received;"
                            + " its reversal is a row of its own on the day it was undone, with a negative count and amount")
    record PaymentDay(LocalDate date, String method, int count, long amountMinor) {}

    @Schema(name = "RetailPaymentMethodTotal")
    record PaymentMethod(String method, int count, long amountMinor) {}

    @Schema(name = "RetailCreditControl")
    record Report(
            LocalDate from,
            LocalDate to,
            LocalDate asOf,
            String currency,
            int top,

            @Schema(description = "Every buyer in scope, whatever the row limit")
            Ageing totals,

            int buyerCount,

            @Schema(description = "Buyers owing the most first")
            List<Buyer> buyers,

            @Schema(description = "amount or age") String sort,
            int overdueCount,
            List<Overdue> overdue,

            @Schema(description = "Payments on credit sales received in the range, by day and method")
            List<PaymentDay> payments,

            List<PaymentMethod> paymentsByMethod,
            long paymentsMinor) {}
}
