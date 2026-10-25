package com.rincoltech.bms.retail.catalogue.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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

    static final String CODE_PREFIX =
            "Issue #493, ADR-081: 2 to 4 letters, unique in the business, that start the item codes the app makes in this category, such as BLB for BLB-0001.";

    private CatalogueApi() {}

    @Schema(name = "RetailCategoryRequest")
    record CategoryRequest(
            @NotBlank @Size(max = 100) String name,

            @Size(max = 4)
            @Schema(description = CODE_PREFIX + " Left out or blank, the app suggests one from the name.")
            String codePrefix) {}

    @Schema(name = "RetailUnitRequest")
    record UnitRequest(@NotBlank @Size(max = 30) String name) {}

    @Schema(name = "RetailCategoryUpdate", description = "Omitted fields are unchanged; a category is never deleted")
    record UpdateCategoryRequest(
            @Size(min = 1, max = 100) String name,
            Boolean active,

            @Size(min = 2, max = 4) @Schema(description = CODE_PREFIX + " Items made before keep their codes.")
            String codePrefix) {}

    @Schema(name = "RetailUnitUpdate", description = "Omitted fields are unchanged; a unit is never deleted")
    record UpdateUnitRequest(@Size(min = 1, max = 30) String name, Boolean active) {}

    @Schema(name = "RetailCategory")
    record Category(
            UUID id,
            String name,
            boolean active,

            @Schema(description = CODE_PREFIX) String codePrefix,

            @Schema(
                    description =
                            "The code the next item added here without a typed code would get now, such as BLB-0004; a hint only (issue #493)")
            String nextCode,

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

    @Schema(name = "RetailCategoryPrefixSuggestion")
    record PrefixSuggestion(String codePrefix) {}

    @Schema(name = "RetailCategoryList")
    record CategoryList(List<Category> items) {}

    @Schema(name = "RetailUnitList")
    record UnitList(List<Unit> items) {}

    @Schema(name = "CreateRetailProductRequest")
    record CreateProductRequest(
            @Schema(
                    deprecated = true,
                    description =
                            "Leave it out: the app always gives the category's next code, such as BLB-0001 (issue #562, ADR-081"
                                    + " as amended). A code that is not blank is refused with 400 item_code_made_by_app")
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
            @Schema(
                    deprecated = true,
                    description =
                            "Leave it out: an item keeps the code the app gave it (issue #562). A blank code, or the item's own"
                                    + " code in any case, is no change; any other is refused with 400 item_code_made_by_app")
            String code,

            @Size(min = 1, max = 300) String description,
            UUID categoryId,
            UUID unitId,
            Boolean active,

            @Size(max = 300) @Schema(description = DUPLICATE_REASON)
            String duplicateReason,

            @Size(max = 30)
            @Schema(
                    description =
                            "Issue #384, a variant only: its new size; an empty string removes it (a colour must remain)."
                                    + " The description follows: the item's name, then the size and colour")
            String variantSize,

            @Size(max = 30)
            @Schema(description = "Issue #384, a variant only: its new colour or print; an empty string removes it")
            String variantColour) {}

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

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = RetailCatalogue.PRICE_ONLY)
            Long sellMinor,

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
            List<Pack> packs,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #342: the stock of this product summed over the branches the caller may read; sent only"
                                    + " by GET /retail/products/{id}, so the edit screen can warn before an item with stock is switched off")
            String stockOnHand,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #342: in how many of the branches the caller may read the balance is not zero (a positive and a"
                                    + " negative balance do not cancel); sent only by GET /retail/products/{id}")
            Integer stockBranches,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #384: the item with sizes and colours this product is one variant of; absent for an item"
                                    + " without variants")
            VariantOf variant,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #385: of the branch balance, what open customer orders keep aside (so not free to"
                                    + " sell); sent only with branch_id, and only when something is kept")
            String reservedQty,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #384: the id of this product's own photo; show it with GET /retail/photos/{photo_id}."
                                    + " Absent when it has none (a size then shows item_photo_id)")
            UUID photoId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #384: the id of the photo of the item this size belongs to, which it shows while it has"
                                    + " none of its own; absent when the item has none")
            UUID itemPhotoId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #345: the barcodes of this product and of its pack sizes, sent by the list, the get and"
                                    + " the barcode lookup; scan any of them to find the product")
            List<Barcode> barcodes) {

        /** Every field but the barcodes, the shape every read had before barcodes (issue #345). */
        Product(
                UUID id,
                String code,
                String description,
                UUID categoryId,
                String category,
                UUID unitId,
                String unit,
                Long sellMinor,
                Long costMinor,
                String currency,
                boolean active,
                Instant createdAt,
                Instant updatedAt,
                int version,
                String qty,
                Boolean negative,
                List<Pack> packs,
                String stockOnHand,
                Integer stockBranches,
                VariantOf variant,
                String reservedQty,
                UUID photoId,
                UUID itemPhotoId) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    null);
        }

        /** Every field but the photo, the shape every read had before item photos (issue #384). */
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
                Boolean negative,
                List<Pack> packs,
                String stockOnHand,
                Integer stockBranches,
                VariantOf variant) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    null);
        }

        /** Every field but the photos, the shape every read had before item photos (issue #384). */
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
                Boolean negative,
                List<Pack> packs,
                String stockOnHand,
                Integer stockBranches,
                VariantOf variant,
                String reservedQty) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    null,
                    null);
        }

        /** Every field but the variant, the shape every read had before sizes and colours (issue #384). */
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
                Boolean negative,
                List<Pack> packs,
                String stockOnHand,
                Integer stockBranches) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    null,
                    null);
        }

        /** A product with packs but no stock total, the shape every list row has. */
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
                Boolean negative,
                List<Pack> packs) {
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
                    packs,
                    null,
                    null);
        }

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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    barcodes);
        }

        Product withStock(String total, int branches) {
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
                    packs,
                    total,
                    branches,
                    variant,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    barcodes);
        }

        /** ADR-055: no selling price and no cost, for a caller who may see neither (a storekeeper). */
        Product withoutPrice() {
            return withoutCost().withSell(null);
        }

        private Product withSell(Long sell) {
            return new Product(
                    id,
                    code,
                    description,
                    categoryId,
                    category,
                    unitId,
                    unit,
                    sell,
                    costMinor,
                    currency,
                    active,
                    createdAt,
                    updatedAt,
                    version,
                    qty,
                    negative,
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    barcodes);
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    barcodes);
        }

        /** The same product with what customer orders keep aside of the branch balance (issue #385). */
        Product withReserved(String reserved) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reserved,
                    photoId,
                    itemPhotoId,
                    barcodes);
        }

        Product withVariant(VariantOf v) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    v,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    barcodes);
        }

        Product withPhoto(UUID photo, UUID itemPhoto) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    photo,
                    itemPhoto,
                    barcodes);
        }

        Product withBarcodes(List<Barcode> codes) {
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
                    packs,
                    stockOnHand,
                    stockBranches,
                    variant,
                    reservedQty,
                    photoId,
                    itemPhotoId,
                    codes);
        }
    }

    @Schema(
            name = "RetailProductVariantOf",
            description = "Issue #384: the item a variant belongs to, and the variant's size and colour (at least one)")
    record VariantOf(
            UUID familyId,
            String familyName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String size,
            @JsonInclude(JsonInclude.Include.NON_NULL) String colour,

            @Schema(
                    description =
                            "The variant's place in the item's list, in the order the sizes and colours were given")
            int position) {}

    // ---- Items with sizes and colours (issue #384, ADR-051) --------------------------------------------

    @Schema(
            name = "RetailVariantLine",
            description =
                    "One size and colour to make: a size, a colour or both; the code and prices default from the item")
    record VariantLine(
            @Size(max = 30) String size,

            @Size(max = 30) @Schema(description = "A colour or a print")
            String colour,

            @Schema(
                    deprecated = true,
                    description =
                            "Leave it out: the app makes the code from the item's base code, the size and the colour in"
                                    + " capitals, as BLB-0007-03M-BLUE (issue #562). One that is not blank is refused with 400"
                                    + " item_code_made_by_app")
            String code,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "This variant's own cost; by default the item's")
            Long costMinor,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "This variant's own selling price; by default the item's")
            Long sellMinor) {}

    static final String GRID =
            "Without variants every combination of sizes and colours is made, sizes in the order given; with variants exactly"
                    + " those are made";

    @Schema(name = "CreateRetailFamilyRequest", description = "Issue #384: an item with sizes and colours. " + GRID)
    record CreateFamilyRequest(
            @NotBlank @Size(max = 200) @Schema(description = "The item's name, as Baby romper")
            String name,

            @Schema(
                    deprecated = true,
                    description =
                            "Leave it out: every variant's code starts with the category's next code, as BLB-0007 (issue"
                                    + " #562). One that is not blank is refused with 400 item_code_made_by_app")
            String baseCode,

            @NotNull UUID categoryId,
            @NotNull UUID unitId,

            @NotNull
            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "Each variant's cost unless it has its own; 0 without retail.profit.read")
            Long costMinor,

            @NotNull
            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "Each variant's selling price unless it has its own")
            Long sellMinor,

            @Size(max = 30) List<@Size(max = 30) String> sizes,
            @Size(max = 30) List<@Size(max = 30) String> colours,
            @Size(max = VariantGrid.MAX_VARIANTS) List<@Valid VariantLine> variants,

            @Size(max = 300) @Schema(description = ITEM_DUPLICATE_REASON)
            String duplicateReason) {}

    @Schema(
            name = "AddRetailVariantsRequest",
            description =
                    "Issue #384: more sizes or colours for an item; a combination it has already is skipped. " + GRID)
    record AddVariantsRequest(
            @Size(max = 30) List<@Size(max = 30) String> sizes,
            @Size(max = 30) List<@Size(max = 30) String> colours,
            @Size(max = VariantGrid.MAX_VARIANTS) List<@Valid VariantLine> variants,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "The new variants' cost; by default the cost of the item's first variant")
            Long costMinor,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "The new variants' selling price; by default the price of the item's first variant")
            Long sellMinor) {}

    @Schema(
            name = "RetailMakeFamilyRequest",
            description =
                    "Issue #384: give an item without variants sizes and colours. The item becomes the variant named by"
                            + " size and colour, keeping its code, prices, stock and history; the other combinations are made at its"
                            + " prices. " + GRID)
    record MakeFamilyRequest(
            @Size(max = 30) @Schema(description = "The size of the stock the item holds now")
            String size,

            @Size(max = 30) @Schema(description = "The colour or print of the stock the item holds now")
            String colour,

            @Size(max = 30) List<@Size(max = 30) String> sizes,
            @Size(max = 30) List<@Size(max = 30) String> colours,
            @Size(max = VariantGrid.MAX_VARIANTS) List<@Valid VariantLine> variants,

            @Size(max = 300) @Schema(description = ITEM_DUPLICATE_REASON)
            String duplicateReason) {}

    @Schema(
            name = "UpdateRetailFamilyRequest",
            description =
                    "Omitted fields are unchanged. A new name renames every variant; a new category moves every variant")
    record UpdateFamilyRequest(
            @Size(min = 1, max = 200) String name,
            UUID categoryId,

            @Size(max = 300) @Schema(description = ITEM_DUPLICATE_REASON)
            String duplicateReason) {}

    static final String ITEM_DUPLICATE_REASON =
            "Issue #384, ADR-044 one level up: an item's name is kept once per unit against other items with sizes and items"
                    + " without; only an administrator (core.settings.manage) may use a name that exists, with a reason of at"
                    + " least 5 characters. Without it that is refused with 422 duplicate_product_name";

    @Schema(name = "RetailJoinItem", description = "An item without sizes that becomes one size or colour of the item")
    record JoinItem(
            @NotNull UUID productId,
            @Size(max = 30) String size,

            @Size(max = 30) @Schema(description = "A colour or a print")
            String colour) {}

    @Schema(
            name = "RetailJoinRequest",
            description =
                    "Issue #384: items kept apart until now (one per size) become the sizes and colours of one item. Each keeps"
                            + " its id, code, prices, stock and history; its name becomes the item's name with its size and colour,"
                            + " its category the item's. They must share one unit and have no sizes yet. Joining an item that exists"
                            + " (POST /product-families/{id}/join) takes only items: name, base_code, category_id and"
                            + " duplicate_reason are refused with 422 there, and the item may not pass 100 sizes and colours."
                            + " A product's reason for a name in use is cleared by a join and kept in the audit trail")
    record JoinRequest(
            @Size(min = 1, max = 200) @Schema(description = "The item's name; required for a new item")
            String name,

            @Schema(
                    deprecated = true,
                    description =
                            "Leave it out: later variant codes start with the first item's code (issue #562). One that is not"
                                    + " blank is refused with 400 item_code_made_by_app")
            String baseCode,

            @Schema(description = "The item's category; by default the first item's")
            UUID categoryId,

            @NotNull @Size(min = 1, max = VariantGrid.MAX_VARIANTS)
            List<@Valid @NotNull JoinItem> items,

            @Size(max = 300) @Schema(description = ITEM_DUPLICATE_REASON)
            String duplicateReason) {}

    @Schema(name = "RetailBranchStock", description = "What the item holds in one branch, all its variants together")
    record BranchStock(UUID branchId, String qty) {}

    @Schema(name = "RetailFamily", description = "Issue #384: an item with sizes and colours and its variants")
    record Family(
            UUID id,
            String name,
            String baseCode,
            UUID categoryId,
            String category,
            UUID unitId,
            String unit,

            @Schema(description = "The sizes in the item's order")
            List<String> sizes,

            @Schema(description = "The colours or prints in the item's order")
            List<String> colours,

            @Schema(
                    description =
                            "Every variant, active or not, in the item's order, each with stock_on_hand and stock_branches over the"
                                    + " branches the caller may read")
            List<Product> variants,

            @Schema(description = "The item's stock over the branches the caller may read, every variant together")
            String stockOnHand,

            @Schema(description = "The item's stock per branch the caller may read, where it is not zero")
            List<BranchStock> stockByBranch,

            Instant createdAt,

            @Schema(description = "Send as If-Match when updating")
            int version,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #384: the document id of the item's photo, which a size without its own shows; absent when none")
            UUID photoId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Issue #395: on adding sizes (and giving an item sizes) only, what was copied to the new sizes from"
                                    + " the size they were made from, and what was not; absent otherwise")
            TermsCopied termsCopied) {

        Family withTermsCopied(TermsCopied copied) {
            return new Family(
                    id,
                    name,
                    baseCode,
                    categoryId,
                    category,
                    unitId,
                    unit,
                    sizes,
                    colours,
                    variants,
                    stockOnHand,
                    stockByBranch,
                    createdAt,
                    version,
                    photoId,
                    copied);
        }
    }

    @Schema(
            name = "RetailTermsCopied",
            description =
                    "Issue #395: the pack sizes and price list prices copied to new sizes, and the prices that were not")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TermsCopied(
            int packsCopied,
            int priceListItemsCopied,

            @Schema(
                    description = "Why no price list price was looked at, absent when they were looked at one by one:"
                            + " price_list_permission (the caller does not hold retail.pricelist.manage; given whether"
                            + " or not the size has list prices and whatever the capability, so nothing about the"
                            + " lists is shown) or price_lists_off (the price lists capability is off)",
                    allowableValues = {"price_list_permission", "price_lists_off"})
            String priceListItemsSkipped,

            @Schema(
                    description =
                            "Each price list price not copied to a new size, and why; set it on the list if it is wanted")
            List<PriceNotCopied> priceListItemsNotCopied) {}

    @Schema(
            name = "RetailPriceNotCopied",
            description = "Issue #395: a price list price that was not copied to a new size")
    record PriceNotCopied(
            @Schema(description = "The new size") UUID productId,
            UUID priceListId,
            UUID unitId,
            String minQty,
            long priceMinor,

            @Schema(
                    description =
                            "price_differs: the new size does not keep the cost and selling price it was made from;"
                                    + " below_cost: the price is below the new size's cost (only to a caller with"
                                    + " retail.profit.read); not_copied: the same for a caller without it, who is not told"
                                    + " about cost; unit_not_sold: the new size is not sold in that pack",
                    allowableValues = {"price_differs", "below_cost", "not_copied", "unit_not_sold"})
            String reason) {}

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

            @Schema(
                    description =
                            "Empty on a dry run for a row whose code the category makes; the made code once applied (issue #493)")
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

    // ---- Barcodes (ADR-070, issue #345; FR-RET-68) ----------------------------------------------------

    @Schema(
            name = "RetailBarcode",
            description = "Issue #345: one barcode of a product, for its base unit or one pack size")
    record Barcode(
            UUID id,
            UUID productId,
            String code,

            @Schema(description = "ean13, ean8, upca or code128")
            String format,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The pack size this barcode is on; absent for the product's own unit")
            UUID unitId,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The name of that pack's unit")
            String unit,

            @Schema(
                    description = "True for a code the system made for the product's labels: a shop EAN-13 from 210"
                            + " (#524), or a Code 128 IN code made before")
            boolean generated) {}

    @Schema(name = "RetailBarcodeRequest", description = "Issue #345: add a barcode to a product")
    record BarcodeRequest(
            @NotBlank
            @Size(max = Barcodes.MAX_LENGTH)
            @Schema(description = "As printed or scanned; EAN-13, EAN-8 and UPC-A check digits are checked")
            String code,

            @Size(max = 10)
            @Schema(
                    description =
                            "ean13, ean8, upca or code128; by default read from the code (13, 12 or 8 digits, else Code 128)")
            String format,

            @Schema(
                    description =
                            "A pack size of the product (the units capability); absent for the product's own unit")
            UUID unitId) {}

    @Schema(name = "RetailBarcodeList")
    record BarcodeList(List<Barcode> items) {}

    @Schema(name = "RetailBarcodeMatch", description = "Issue #345: the active product a scanned barcode belongs to")
    record BarcodeMatch(
            Product product,
            Barcode barcode,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The pack size the barcode is on, to add the line in that unit; absent for the base unit")
            UUID unitId) {}
}
