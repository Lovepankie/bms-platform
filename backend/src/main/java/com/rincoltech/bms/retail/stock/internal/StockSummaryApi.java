package com.rincoltech.bms.retail.stock.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The grouped stock summary (issue #350; chapter 7 section 7.11.20): one row per shop or per category with how many
 * items are held and what they are worth, so the browser never sums pages. The items themselves are the stock list.
 */
final class StockSummaryApi {

    static final String COST_ONLY = "Present only with retail.profit.read in every branch the figures cover";

    private StockSummaryApi() {}

    @Schema(name = "RetailStockSummaryGroup")
    record Group(
            @Schema(description = "A branch id or a category id; pass it back as branch_id or category_id")
            String key,

            @Schema(description = "The branch or category name")
            String label,

            @Schema(description = "Items listed: active ones, and inactive ones only with include_inactive")
            long itemsCount,

            @Schema(description = "Items with a balance below zero")
            long negativeCount,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Present only with retail.profit.read in every branch the figures cover (ADR-055): what the stock holds at the selling price")
            Long valueMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = COST_ONLY + ": what the stock holds at cost")
            Long costMinor) {}

    @Schema(name = "RetailStockSummary")
    record Summary(
            @Schema(description = "The level these groups are: one of available_levels")
            String groupBy,

            @Schema(description = "The chain the request asked for, in order")
            List<String> levels,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The level under this one in the chain; absent at the end of the chain")
            String nextLevel,

            @Schema(description = "Every level group_by accepts: branch and category")
            List<String> availableLevels,

            @Schema(description = "The tenant's currency; a retail tenant keeps its books in one currency")
            String currency,

            List<Group> items,

            @Schema(description = "True when more groups remain: ask again with offset = next_offset")
            boolean hasMore,

            @JsonInclude(JsonInclude.Include.NON_NULL) Integer nextOffset) {}
}
