package com.rincoltech.bms.retail.catalogue.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

    @Schema(name = "RetailCategoryUpdate", description = "Omitted fields are unchanged; a category is never deleted")
    record UpdateCategoryRequest(@Size(min = 1, max = 100) String name, Boolean active) {}

    @Schema(name = "RetailUnitUpdate", description = "Omitted fields are unchanged; a unit is never deleted")
    record UpdateUnitRequest(@Size(min = 1, max = 30) String name, Boolean active) {}

    @Schema(name = "RetailCategory")
    record Category(
            UUID id,
            String name,
            boolean active,

            @Schema(description = "Products (active or not) that use this category")
            int productCount,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

    @Schema(name = "RetailUnit")
    record Unit(
            UUID id,
            String name,
            boolean active,

            @Schema(description = "Products (active or not) that use this unit")
            int productCount,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

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

            @NotNull @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long costMinor,

            @NotNull @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long sellMinor,

            @Size(max = 300) @Schema(description = DUPLICATE_REASON)
            String duplicateReason) {}

    @Schema(
            name = "UpdateRetailProductRequest",
            description = "Omitted fields are unchanged; prices change only through /prices")
    record UpdateProductRequest(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 300) String description,
            UUID categoryId,
            UUID unitId,
            Boolean active,

            @Size(max = 300) @Schema(description = DUPLICATE_REASON)
            String duplicateReason) {}

    static final String DUPLICATE_REASON =
            "Issue #352: only an administrator (core.settings.manage) may add or rename a product to a"
                    + " name another product of the same unit has, and then with a reason of at least 5 characters, stored on the product."
                    + " Without it that is refused with 422 duplicate_product_name.";

    @Schema(name = "RetailSimilarProduct")
    record SimilarProduct(
            UUID id,
            String code,
            String description,
            String unit,
            boolean active,

            @Schema(
                    description =
                            "Letters changed, added or removed to turn one name into the other (0 is the same name in another unit)")
            int distance,

            @Schema(description = "True when the unit is the one asked about, so the same name is a duplicate")
            boolean sameUnit) {}

    @Schema(
            name = "RetailSimilarProducts",
            description =
                    "Issue #352: products whose name is the same (exact, same unit: a save is refused) or close to the"
                            + " description (similar: a warning only)")
    record SimilarProducts(List<SimilarProduct> exact, List<SimilarProduct> similar) {}

    @Schema(name = "RetailPriceEditRequest", description = "At least one price; FR-RET-02")
    record PriceEditRequest(
            @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long costMinor,

            @PositiveOrZero @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            Long sellMinor,

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
            Boolean negative,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The pack sizes of the product; present only while the units capability is on")
            List<Pack> packs) {

        /** A product without packs, the shape every read had before units of measure (ADR-033). */
        Product(
                UUID id,
                String code,
                String description,
                UUID categoryId,
                String category,
                UUID unitId,
                String unit,
                long sellMinor,
                Long costMinor,
                String currency,
                boolean active,
                Instant createdAt,
                Instant updatedAt,
                int version,
                String qty,
                Boolean negative) {
            this(
                    id,
                    code,
                    description,
                    categoryId,
                    category,
                    unitId,
                    unit,
                    sellMinor,
                    costMinor,
                    currency,
                    active,
                    createdAt,
                    updatedAt,
                    version,
                    qty,
                    negative,
                    null);
        }

        Product withPacks(List<Pack> packs) {
            return new Product(
                    id,
                    code,
                    description,
                    categoryId,
                    category,
                    unitId,
                    unit,
                    sellMinor,
                    costMinor,
                    currency,
                    active,
                    createdAt,
                    updatedAt,
                    version,
                    qty,
                    negative,
                    packs);
        }

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
                    negative,
                    packs);
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

    @Schema(name = "RetailProductImportRequest")
    record ImportRequest(
            @NotBlank
            @Size(max = ProductImportService.MAX_CHARS)
            @Schema(
                    description =
                            "The CSV text: a header row, then one item per row. Columns code, description, category, unit,"
                                    + " sell_price and optionally cost_price (only with retail.profit.read). Comma, semicolon or tab separated.")
            String csv) {}

    @Schema(name = "RetailProductImportRow")
    record ImportRow(
            int line,
            String code,
            String description,

            @Schema(description = "added, skipped (the code exists already or repeats in the file) or error")
            String outcome,

            String message) {}

    @Schema(name = "RetailProductImportResult")
    record ImportResult(
            boolean dryRun,
            int rowsRead,
            int added,
            int skipped,
            int errors,

            @Schema(description = "Categories that are new (would be created on a dry run, were created on an apply)")
            List<String> categoriesCreated,

            List<String> unitsCreated,
            List<ImportRow> rows) {}

    // ---- Pack sizes (ADR-033, issue #194) ------------------------------------------------------

    @Schema(name = "RetailPackRequest", description = "A pack size: factor base units in one pack of the unit")
    record PackRequest(
            @NotNull UUID unitId,

            @NotNull
            @DecimalMin("1")
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    description = "Base units in one pack, at least 1, up to three places, for example 12 or 12.5")
            BigDecimal factor) {}

    @Schema(
            name = "RetailPackUpdate",
            description = "Omitted fields are unchanged; a pack is never deleted, only switched off")
    record UpdatePackRequest(
            @DecimalMin("1")
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    description = "Base units in one pack, at least 1; required to switch a pack on again")
            BigDecimal factor,

            Boolean active) {}

    @Schema(name = "RetailPack")
    record Pack(
            UUID id,
            UUID productId,
            UUID unitId,
            String unit,

            @Schema(description = "Base units in one pack, three places, for example 12.000")
            String factor,

            boolean active,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

    @Schema(name = "RetailPackList")
    record PackList(List<Pack> items) {}
}
