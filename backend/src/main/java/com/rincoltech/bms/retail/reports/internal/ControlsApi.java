package com.rincoltech.bms.retail.reports.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the Controls page (issue #357, group 1; chapter 7 section 7.11.20): read-only counts of the actions that
 * deserve a second look (voids, returns, credit write-offs, changed prices, sales below cost, sales cancelled at once), every one with
 * its trend against the period before and a rate per 100 sales so people and shops can be compared. Values are sale
 * prices; nothing here is a cost, and the one card that is judged against cost is left out unless the caller may read
 * profit in every branch covered.
 */
final class ControlsApi {

    static final String COST_ONLY = "Present only with retail.profit.read in every branch the figures cover";

    private ControlsApi() {}

    @Schema(name = "RetailControlsCard")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Card(
            @Schema(
                    description =
                            "voided_sales, quick_cancels, returns, credit_writeoffs, price_overrides, discounts,"
                                    + " below_cost, restock_voids or quantity_corrections; the key to ask groups and records for")
            String metric,

            @Schema(
                    description =
                            "False while the capability the card depends on is off and nothing was ever recorded: the"
                                    + " card then reads 'not switched on', not 'all clear'")
            boolean tracked,

            long count,

            @Schema(description = "Sales with such an event; a sale with two changed lines counts once")
            long salesAffected,

            @Schema(
                    description =
                            "At sale prices; for discounts, the money given up against the price the system chose;"
                                    + " for credit_writeoffs, what the buyer still owed, never the loss")
            long valueMinor,

            long previousCount,
            long previousValueMinor,

            @Schema(
                    description =
                            "Sales with such an event per 100 sales entered in the period, one decimal; absent when no"
                                    + " sale was entered")
            @JsonProperty("rate_per_100")
            String ratePer100,

            @Schema(description = "The same for the previous period") @JsonProperty("previous_rate_per_100")
            String previousRatePer100,

            @Schema(
                    description =
                            "The value over the completed sales of the period, in basis points; absent without sales")
            Integer shareOfSalesBp) {}

    @Schema(name = "RetailControlsPeriod")
    record Period(
            LocalDate from,
            LocalDate to,

            @Schema(description = "Sales entered in the period, voided ones included, imported history left out")
            long salesCount,

            @Schema(description = "Total of the completed sales of the period")
            long salesMinor) {}

    @Schema(name = "RetailControls")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(
            String currency,
            Period period,

            Period previous,

            @Schema(description = "A sale cancelled within this many minutes of being entered is a quick cancel")
            int withinMinutes,

            @Schema(description = COST_ONLY + "; the below_cost card is sent only then")
            boolean costVisible,

            List<Card> cards) {}

    @Schema(name = "RetailControlsGroup")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Group(
            @Schema(
                    description =
                            "A branch id, a user id, a day (2026-10-19) or the reason text lower-cased; pass it back"
                                    + " as the matching filter of the next level")
            String key,

            @Schema(description = "The branch, person or reason to show, or the day")
            String label,

            @Schema(description = "User level only: active, invited or deactivated; absent when the person is unknown")
            String userStatus,

            @Schema(description = "Day level only") LocalDate day,
            long count,

            @Schema(description = "Sales with such an event; the rate is over these")
            long salesAffected,

            long valueMinor,

            @Schema(
                    description =
                            "Sales entered by the group (the shop, the person or the day) in the period; the rate is"
                                    + " over these")
            long salesCount,

            @Schema(
                    description =
                            "Sales with such an event per 100 sales the group entered, one decimal; absent when it"
                                    + " entered none")
            @JsonProperty("rate_per_100")
            String ratePer100) {}

    @Schema(name = "RetailControlsGroups")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Groups(
            String metric,
            String groupBy,

            @Schema(
                    description =
                            "The level these groups are: the one answered, the first of the chain unless level is sent")
            String level,

            List<String> levels,

            @Schema(description = "The level under this one in the chain; absent at the end of the chain")
            String nextLevel,

            String currency,
            List<Group> items,
            boolean hasMore,
            Integer nextOffset) {}

    @Schema(name = "RetailControlsRecord")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Entry(
            @Schema(description = "The sale; for restock_voids the restock's id, and for quantity_corrections absent")
            UUID saleId,

            @Schema(
                    description =
                            "The sale number; for restock_voids the restock's number, and for quantity_corrections"
                                    + " absent")
            String saleNo,

            @Schema(description = "Returns only: the number of the credit note")
            String reference,

            Instant at,
            UUID branchId,
            String branchName,
            UUID userId,

            @Schema(description = "The person whose sale it was; absent when unknown")
            String userName,

            String userStatus,
            UUID approverId,

            @Schema(
                    description =
                            "Who cancelled the sale, took the goods back, wrote it off or voided the restock; absent for"
                                    + " a price, a cost and a correction")
            String approverName,

            String approverStatus,
            String reason,

            @Schema(description = "Price changes, discounts, below cost and quantity corrections only: the item")
            String item,

            long valueMinor) {}

    @Schema(name = "RetailControlsRecords")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Records(String metric, String currency, List<Entry> items, boolean hasMore, Integer nextOffset) {}

    @Schema(name = "RetailControlsPattern")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Pattern(
            @Schema(description = "user or product") String subject,

            @Schema(description = "A user id or a product id")
            String key,

            String name,

            @Schema(description = "Users only: active, invited or deactivated")
            String userStatus,

            UUID branchId,
            String branchName,

            @Schema(description = "voided_sales, returns, price_overrides or below_cost")
            String metric,

            long count,

            @Schema(description = "Sales entered by the user (or lines sold of the item) in that shop in the period")
            long outOf,

            @JsonProperty("rate_per_100") String ratePer100,

            @Schema(description = "The shop's own rate for the same thing") @JsonProperty("shop_rate_per_100")
            String shopRatePer100,

            @Schema(description = "How many times the shop's rate, one decimal")
            String times) {}

    @Schema(name = "RetailControlsPatterns")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Patterns(
            LocalDate from,
            LocalDate to,

            @Schema(description = "A rate counts only from this many events and this many sales or lines")
            int minEvents,

            int minOutOf,
            List<Pattern> items) {}
}
