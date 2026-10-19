package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The grouped sales summary (issue #328; chapter 7 section 7.11.20): one row per year, month or day with the
 * figures of the sales list, so the browser never sums pages.
 */
final class SalesSummaryApi {

    static final String PROFIT_ONLY = "Present only with retail.profit.read in every branch the figures cover";

    private SalesSummaryApi() {}

    /** The sales of one payment method inside a period: how many and for how much. */
    @Schema(name = "RetailSalesSummaryMethod")
    record MethodFigures(long count, long totalMinor) {}

    @Schema(name = "RetailSalesSummaryMethods")
    record Methods(MethodFigures cash, MethodFigures mobileMoney, MethodFigures bank, MethodFigures credit) {}

    @Schema(name = "RetailSalesSummaryGroup")
    record Group(
            @Schema(description = "2026, 2026-10 or 2026-10-19, by group_by")
            String period,

            @Schema(description = "First calendar day of the period; not narrowed by the from filter of the request")
            LocalDate from,

            @Schema(description = "Last calendar day of the period; not narrowed by the to filter of the request")
            LocalDate to,

            @Schema(description = "Completed sales; voided sales are not counted")
            long salesCount,

            @Schema(description = "Total of the completed sales")
            long totalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long profitMinor,

            @Schema(description = "Voided sales in the period, kept out of every other figure")
            long voidedCount,

            Methods byMethod) {}

    @Schema(name = "RetailSalesSummary")
    record Summary(
            String groupBy,

            @Schema(description = "The tenant's currency; a retail tenant keeps its books in one currency")
            String currency,

            List<Group> items,

            @Schema(description = "True when older periods remain: ask again with to = next_to")
            boolean hasMore,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The day before the oldest period shown")
            LocalDate nextTo) {}
}
