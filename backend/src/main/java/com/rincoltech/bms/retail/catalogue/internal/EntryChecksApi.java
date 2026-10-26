package com.rincoltech.bms.retail.catalogue.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the entry warnings (issue #353, ADR-093; chapter 7 section 7.11.20). Rule based only:
 * medians, the usual unit and the last selling price, read from the item's own history. Nothing here is ever a refusal.
 */
final class EntryChecksApi {

    private EntryChecksApi() {}

    /** A quantity as the screens send it: digits, at most three decimals. */
    static final String QTY = "\\d{1,11}(\\.\\d{1,3})?";

    @Schema(
            name = "RetailEntryUsage",
            description = "Issue #353: what the item's last lines of one kind say, read newest first and at most 20")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Usage(
            @Schema(description = "How many lines were read, at most 20")
            int linesRead,

            @Schema(
                    description =
                            "The median quantity of those lines in the item's own unit (base units), as plain text; absent"
                                    + " under 3 lines, when no quantity warning is given",
                    example = "50")
            String medianQty,

            @Schema(
                    description =
                            "The unit used on more than half of those lines: a pack's unit id; absent when that unit is the"
                                    + " item's own or when no unit holds more than half or under 3 lines were read")
            UUID usualUnitId,

            @Schema(
                    description =
                            "The name of that usual unit (the item's own unit when usual_unit_id is absent); absent when there"
                                    + " is no usual unit",
                    example = "roll")
            String usualUnit) {}

    @Schema(
            name = "RetailEntryNorms",
            description =
                    "Issue #353, ADR-093: the item's recent history the entry screens warn against: Buy stock, Sell, Move"
                            + " stock and Count stock call this once per item. The thresholds are sent so the screens use the"
                            + " server's")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryNorms(
            UUID productId,

            @Schema(description = "The item's own (base) unit", example = "metre")
            String unit,

            @Schema(description = "The last purchases (Buy stock), voided ones left out")
            Usage buy,

            @Schema(description = "The last sales, voided ones left out")
            Usage sell,

            @Schema(description = "The last stock moves, cancelled ones left out")
            Usage move,

            @Schema(
                    description =
                            "The price per base unit of the last sale, what was charged (line total over quantity, rounded"
                                    + " half up). "
                                    + RetailCatalogue.PRICE_ONLY)
            Long lastSellMinor,

            @Schema(description = "The day of that sale. " + RetailCatalogue.PRICE_ONLY)
            LocalDate lastSoldOn,

            @Schema(
                    description =
                            "A quantity this many times the median or more, or this many times less, is warned about")
            int quantityFactor,

            @Schema(
                    description =
                            "A selling price this many times the last price or more, or this many times less, is warned about")
            int priceFactor,

            @Schema(description = "The fewest lines a median or a usual unit is given from")
            int minLines) {}

    @Schema(name = "RetailNameHint")
    record NameHint(UUID id, String code, String description, String unit, UUID categoryId, String category) {}

    @Schema(name = "RetailCategoryHint")
    record CategoryHint(
            UUID id,
            String name,

            @Schema(description = "How many items sharing the most words with the typed name are in it")
            int itemsLike,

            @Schema(description = "One of those items, to say why", example = "Single cable 1.5 mm")
            String example) {}

    @Schema(
            name = "RetailNameHints",
            description =
                    "Issue #353: while a new item's name is typed, the existing items it may be (case, accents, spaces and"
                            + " punctuation ignored) and the category most of the items like it are in. Suggestions only")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NameHints(
            @Schema(description = "At most 5 items, those holding the typed name first, then those a few letters apart")
            List<NameHint> didYouMean,

            @Schema(description = "Absent when no item shares a word of 3 letters or more with the name")
            CategoryHint category) {}

    @Schema(
            name = "RetailEntryConfirmation",
            description =
                    "Issue #353: the person confirmed an entry warning and the save it went with succeeded. Recorded in"
                            + " the audit log as the warning's kind and its numbers (no free text), never needed to save")
    record Confirmation(
            @NotNull UUID productId,
            UUID branchId,

            @NotNull @Pattern(regexp = "buy|sell|move|count")
            String screen,

            @NotNull @Pattern(regexp = "unit|quantity|price")
            String kind,

            @Pattern(regexp = QTY) @Schema(description = "The quantity entered, in the unit entered", example = "200")
            String enteredQty,

            @Schema(description = "The pack unit entered; absent for the item's own unit")
            UUID enteredUnitId,

            @Pattern(regexp = QTY)
            @Schema(
                    description =
                            "What it was compared with, in base units: the median, or on Count stock what the records hold")
            String medianQty,

            @PositiveOrZero Long enteredPriceMinor,
            @PositiveOrZero Long lastPriceMinor) {}
}
