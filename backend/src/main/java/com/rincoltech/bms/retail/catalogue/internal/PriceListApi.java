package com.rincoltech.bms.retail.catalogue.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the price list routes (ADR-033, issue #195; chapter 7 section 7.11.20). A list item
 * states a price in minor units per unit; the cost of an item is never sent, only {@code below_cost} and only with
 * {@code retail.profit.read}.
 */
final class PriceListApi {

    static final String COST_ONLY = "Present only with retail.profit.read";

    private PriceListApi() {}

    @Schema(name = "RetailPriceListRequest")
    record PriceListRequest(
            @NotBlank @Size(max = 100) String name,

            @Schema(description = "Refuse a product that is not in the list instead of using the retail price")
            Boolean strict) {}

    @Schema(name = "RetailPriceListUpdate", description = "Omitted fields are unchanged; a list is never deleted")
    record UpdatePriceListRequest(@Size(min = 1, max = 100) String name, Boolean strict, Boolean active) {}

    @Schema(name = "RetailPriceList")
    record PriceList(
            UUID id,
            String name,
            String currency,
            boolean strict,
            boolean active,

            @Schema(description = "Items that apply today or later")
            int currentItems,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

    @Schema(name = "RetailPriceListList")
    record PriceListList(List<PriceList> items) {}

    @Schema(name = "RetailPriceListItemRequest")
    record ItemRequest(
            @NotNull UUID productId,

            @Schema(description = "The product's base unit or one of its active packs") @NotNull
            UUID unitId,

            @PositiveOrZero
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    description = "The quantity break, in unit_id; absent or 0 means from any quantity")
            BigDecimal minQty,

            @NotNull
            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "Per unit_id, in minor units")
            Long priceMinor,

            @Schema(description = "The first day it applies; defaults to today and is never in the past")
            LocalDate validFrom,

            @Schema(description = "The last day it applies; absent means open ended")
            LocalDate validTo) {}

    @Schema(name = "RetailPriceListItemEnd", description = "Ends an open item: it applies up to and including valid_to")
    record EndItemRequest(@NotNull LocalDate validTo) {}

    @Schema(name = "RetailPriceListItem")
    record Item(
            UUID id,
            UUID priceListId,
            UUID productId,
            String code,
            String description,
            UUID unitId,
            String unit,

            @Schema(description = "Three places, for example 10.000")
            String minQty,

            long priceMinor,
            LocalDate validFrom,
            LocalDate validTo,

            @Schema(description = "scheduled (starts later), current, or ended")
            String status,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Boolean belowCost) {}

    @Schema(name = "RetailPriceListItemPage")
    record ItemPage(List<Item> items, String nextCursor) {}

    @Schema(name = "RetailCustomerGroupRequest")
    record GroupRequest(
            @NotBlank @Size(max = 100) String name,

            @Schema(
                    description =
                            "The list every customer of the group is priced from, unless the customer has their own")
            UUID priceListId) {}

    @Schema(
            name = "RetailCustomerGroupUpdate",
            description = "Replaces the group's fields; an absent price_list_id clears the list. Never deleted")
    record UpdateGroupRequest(
            @NotBlank @Size(max = 100) String name,
            UUID priceListId,
            @NotNull Boolean active) {}

    @Schema(name = "RetailCustomerGroup")
    record Group(
            UUID id,
            String name,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID priceListId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,
            boolean active,
            @Schema(description = "Customers in the group") int customerCount,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

    @Schema(name = "RetailCustomerGroupList")
    record GroupList(List<Group> items) {}
}
