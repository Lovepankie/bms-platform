package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The grouped restocks summary (issue #350; chapter 7 section 7.11.20): one row per shop, year, month, supplier, day,
 * category or item with the figures of the restocks list, so the browser never sums pages.
 */
final class PurchasesSummaryApi {

    static final String COST_ONLY = "Present only with retail.profit.read in every branch the figures cover";

    private PurchasesSummaryApi() {}

    @Schema(name = "RetailPurchasesSummaryGroup")
    record Group(
            @Schema(
                    description =
                            "What the group is: a branch id, a supplier id (the nil UUID for restocks with no supplier),"
                                    + " a category id, a product id, or the period (2026, 2026-10 or 2026-10-19); pass it"
                                    + " back as the matching filter of the next level")
            String key,

            @Schema(description = "What to show: the branch, supplier, category or item name, or the period key")
            String label,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "2026, 2026-10 or 2026-10-19; year, month and day only")
            String period,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "First calendar day of the period; year, month and day only")
            LocalDate from,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Last calendar day of the period; year, month and day only")
            LocalDate to,

            @Schema(
                    description = "Restocks that were not voided. Under a category or an item: the restocks that have"
                            + " a line in it")
            long purchasesCount,

            @Schema(description = "Voided restocks in the group, kept out of every other figure")
            long voidedCount,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY + ": what was bought, at cost")
            Long totalMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Units bought, in base units; item level only")
            String quantity,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The item's base unit; item level only")
            String unit) {}

    @Schema(name = "RetailPurchasesSummary")
    record Summary(
            @Schema(description = "The level these groups are: one of available_levels")
            String groupBy,

            @Schema(description = "The chain the request asked for, in order")
            List<String> levels,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The level under this one in the chain; absent at the end of the chain")
            String nextLevel,

            @Schema(description = "Every level group_by accepts")
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
            @Schema(description = "Branch, supplier, category and item: ask again with offset = next_offset")
            Integer nextOffset) {}
}
