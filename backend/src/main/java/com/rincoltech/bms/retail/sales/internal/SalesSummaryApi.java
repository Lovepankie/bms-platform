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
            @Schema(
                    description =
                            "What the group is: a branch id, a user id, a category id, a product id, or the"
                                    + " period (2026, 2026-10 or 2026-10-19); pass it back as the matching filter of the next level")
            String key,

            @Schema(description = "What to show: the branch, person, category or item name, or the period key")
            String label,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "2026, 2026-10 or 2026-10-19; year, month and day only")
            String period,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "First calendar day of the period; year, month and day only; not narrowed by the from filter")
            LocalDate from,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Last calendar day of the period; year, month and day only; not narrowed by the to filter")
            LocalDate to,

            @Schema(
                    description = "Completed sales; voided sales are not counted. Under a category or an item: the"
                            + " completed sales that have a line in it")
            long salesCount,

            @Schema(description = "Total of the completed sales. Under a category or an item: the total of its lines")
            long totalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = PROFIT_ONLY)
            Long profitMinor,

            @Schema(description = "Voided sales in the group, kept out of every other figure")
            long voidedCount,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Units sold, in base units; item level only")
            String quantity,

            Methods byMethod) {}

    @Schema(name = "RetailSalesSummary")
    record Summary(
            @Schema(description = "The level these groups are: one of available_levels")
            String groupBy,

            @Schema(description = "The chain the request asked for, in order")
            List<String> levels,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The level under this one in the chain; absent at the end of the chain")
            String nextLevel,

            @Schema(
                    description =
                            "Every level group_by accepts from this caller; seller needs retail.profit.read in some branch in scope")
            List<String> availableLevels,

            @Schema(description = "The tenant's currency; a retail tenant keeps its books in one currency")
            String currency,

            List<Group> items,

            @Schema(description = "True when more groups remain: next_to (year, month, day) or next_offset (the rest)")
            boolean hasMore,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Year, month and day: the day before the oldest period shown; ask again with to = next_to")
            LocalDate nextTo,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Branch, seller, category and item: ask again with offset = next_offset")
            Integer nextOffset) {}
}
