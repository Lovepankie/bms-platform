package com.rincoltech.bms.retail.catalogue.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the retail catalogue (chapter 7 section 7.11.20). Fields marked
 * as cost are omitted from the body, not sent as null, for a caller without
 * {@code retail.profit.read} (ADR-020 decision 10).
 */
final class CatalogueApi {

    static final String COST_ONLY = "Present only with retail.profit.read";

    private CatalogueApi() {}

    @Schema(name = "RetailCategoryRequest")
    record CategoryRequest(@NotBlank @Size(max = 100) String name) {}

    @Schema(name = "RetailUnitRequest")
    record UnitRequest(@NotBlank @Size(max = 30) String name) {}

    @Schema(name = "RetailCategory")
    record Category(UUID id, String name) {}

    @Schema(name = "RetailUnit")
    record Unit(UUID id, String name) {}

    @Schema(name = "RetailCategoryList")
    record CategoryList(List<Category> items) {}

    @Schema(name = "RetailUnitList")
    record UnitList(List<Unit> items) {}

    @Schema(name = "CreateRetailProductRequest")
    record CreateProductRequest(
            @NotBlank @Size(max = 40) @Schema(description = "Unique ignoring case and surrounding spaces")
            String code,

            @NotBlank @Size(max = 300) String description,
            @NotNull UUID categoryId,
            @NotNull UUID unitId,
            @NotNull @PositiveOrZero Long costMinor,
            @NotNull @PositiveOrZero Long sellMinor) {}

    @Schema(
            name = "UpdateRetailProductRequest",
            description = "Omitted fields are unchanged; prices change only through /prices")
    record UpdateProductRequest(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 300) String description,
            UUID categoryId,
            UUID unitId,
            Boolean active) {}

    @Schema(name = "RetailPriceEditRequest", description = "At least one price; FR-RET-02")
    record PriceEditRequest(
            @PositiveOrZero Long costMinor,
            @PositiveOrZero Long sellMinor,
            @NotBlank @Size(max = 300) String reason) {}

    @Schema(name = "RetailProduct")
    record Product(
            UUID id,
            String code,
            String description,
            UUID categoryId,
            String category,
            UUID unitId,
            String unit,
            long sellMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long costMinor,

            String currency,
            boolean active,
            Instant createdAt,
            Instant updatedAt,
            int version,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The branch balance, when branch_id was given")
            String qty,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "True when the branch balance is below zero")
            Boolean negative) {

        Product withoutCost() {
            return new Product(
                    id,
                    code,
                    description,
                    categoryId,
                    category,
                    unitId,
                    unit,
                    sellMinor,
                    null,
                    currency,
                    active,
                    createdAt,
                    updatedAt,
                    version,
                    qty,
                    negative);
        }
    }

    @Schema(name = "RetailProductPage")
    record ProductPage(List<Product> items, String nextCursor) {}

    @Schema(name = "RetailPriceChange")
    record PriceChange(
            UUID id,
            Instant at,
            UUID by,

            @Schema(description = "initial, manual, purchase or import")
            String source,

            UUID sourceId,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long oldCostMinor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = COST_ONLY)
            Long newCostMinor,

            Long oldSellMinor,
            long newSellMinor,
            String currency,
            String reason) {

        PriceChange withoutCost() {
            return new PriceChange(
                    id, at, by, source, sourceId, null, null, oldSellMinor, newSellMinor, currency, reason);
        }
    }

    @Schema(name = "RetailPriceHistory")
    record PriceHistory(List<PriceChange> items) {}
}
