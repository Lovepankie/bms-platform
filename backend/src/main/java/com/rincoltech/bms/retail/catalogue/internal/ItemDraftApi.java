package com.rincoltech.bms.retail.catalogue.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The body of {@code POST /api/v1/retail/products/import/draft} (issue #626, ADR-102). Field names are snake_case. */
final class ItemDraftApi {

    private ItemDraftApi() {}

    @Schema(name = "RetailItemDraft")
    record ItemDraft(
            @Schema(
                    description = "Null when rows were drafted; else why there are none: no_provider, all_failed,"
                            + " daily_cap or unreadable. The screen then offers to type the list or import a file",
                    nullable = true)
            String reason,

            @Schema(description = "The drafted rows, for the import's check; nothing is saved")
            List<ItemDraftRow> rows,

            @Schema(description = "Decimal places of the shop's currency, to write the prices in the import file")
            int currencyExponent) {}

    @Schema(name = "RetailItemDraftRow")
    record ItemDraftRow(
            String description,
            String category,
            String unit,

            @Schema(
                    description = "Selling price of one unit in minor units; null when the list gives none",
                    nullable = true)
            Long sellPriceMinor,

            @Schema(
                    description =
                            "Buying price of one unit in minor units; null when none, and always for a caller without"
                                    + " retail.profit.read",
                    nullable = true)
            Long costPriceMinor,

            @Schema(description = "A bigger pack it comes in, or null", nullable = true)
            String packUnit,

            @Schema(description = "How many units are in that pack, up to three decimals, or null", nullable = true)
            String packFactor) {}
}
