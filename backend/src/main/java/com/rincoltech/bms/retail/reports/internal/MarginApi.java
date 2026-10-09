package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of the margin report (issue #149). The whole report needs retail.profit.read, so every figure is cost-bearing. */
final class MarginApi {

    private MarginApi() {}

    @Schema(name = "RetailMarginTotals")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Totals(
            long salesMinor,
            long costMinor,
            long profitMinor,

            @Schema(description = "Profit over sales in basis points, half up; absent when sales are not above zero")
            Long marginBp) {}

    @Schema(name = "RetailMarginRow", description = "One item or category; code and unit are set for an item only")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Row(
            UUID id,
            String code,
            String label,
            String unit,
            String qty,
            long salesMinor,
            long costMinor,
            long profitMinor,

            @Schema(description = "Absent when the item sold for nothing")
            Long marginBp) {}

    @Schema(name = "RetailMarginBelowTarget")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BelowTarget(
            List<Row> items,

            @Schema(description = "How many items are below the target, before the row limit")
            int total) {}

    @Schema(
            name = "RetailPriceChangeImpact",
            description =
                    "An item whose sell price changed in the range: the last change decides the split, the figures"
                            + " before it are from the start of the range, those from it are to the end of the range."
                            + " Cost is the snapshot on each sale line, never the current cost.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PriceChange(
            UUID productId,
            String code,
            String description,
            String unit,
            LocalDate changedOn,

            @Schema(description = "The sell price before the first change in the range")
            long oldSellMinor,

            @Schema(description = "The sell price after the last change in the range")
            long newSellMinor,

            int daysBefore,
            int daysAfter,
            String unitsBefore,
            String unitsAfter,
            long salesBeforeMinor,
            long salesAfterMinor,

            @Schema(description = "Absent when nothing sold before the change")
            Long marginBeforeBp,

            @Schema(description = "Absent when nothing sold from the change")
            Long marginAfterBp) {}

    @Schema(name = "RetailPriceChanges")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PriceChanges(List<PriceChange> items, int total) {}

    @Schema(name = "RetailMargins")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Report(
            LocalDate from,
            LocalDate to,
            String currency,
            int top,

            @Schema(description = "The margin target in basis points; 2000 is 20 percent")
            int targetBp,

            Totals totals,
            @Schema(description = "The top items by profit") List<Row> byProduct,
            List<Row> byCategory,
            BelowTarget belowTarget,
            PriceChanges priceChanges) {}
}
