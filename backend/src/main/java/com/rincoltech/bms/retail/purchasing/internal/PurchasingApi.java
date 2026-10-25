package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the retail purchasing routes (chapter 7 section 7.11.20). */
final class PurchasingApi {

    private PurchasingApi() {}

    @Schema(name = "RetailSupplierRequest")
    record SupplierRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String contact) {}

    @Schema(name = "RetailSupplierUpdate", description = "Omitted fields are unchanged; an empty contact clears it")
    record UpdateSupplierRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 100) String contact,
            Boolean active) {}

    @Schema(name = "RetailSupplier")
    record Supplier(
            UUID id,
            String name,
            String contact,
            boolean active,
            Instant createdAt,

            @Schema(description = "Send as If-Match when updating")
            int version) {}

    @Schema(name = "RetailSupplierList")
    record SupplierList(List<Supplier> items) {}

    @Schema(name = "RetailBranchQty")
    record BranchQty(
            @NotNull UUID branchId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "10")
            BigDecimal qty) {}

    @Schema(name = "RetailPurchaseLineRequest")
    record PurchaseLineRequest(
            @NotNull UUID productId,

            @NotNull
            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(
                    description = "Cost per unit_id when one is given, otherwise per base unit; becomes the product's"
                            + " cost per base unit (a pack cost divided by the factor, rounded half up)")
            Long costMinor,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(description = "When given, becomes the product's sell price")
            Long sellMinor,

            @NotEmpty @Size(max = 50) List<@Valid @NotNull BranchQty> qtyByBranch,

            @Schema(
                    description = "A pack unit of the product (units capability): the quantities and cost_minor are"
                            + " then in that unit and stock moves in base units. Absent or the base unit: as before")
            UUID unitId,

            @Positive
            @Digits(integer = 11, fraction = 3)
            @Schema(
                    type = "string",
                    example = "30",
                    description = "With unit_id (D23, issue #554): how many base units each pack held in this delivery,"
                            + " at least 1. Kept on the line only; stock and the cost per base unit use it. Absent means"
                            + " the pack's usual size")
            BigDecimal packSize,

            @Schema(
                    description = "With pack_size: also make it the pack's usual size from now on (needs"
                            + " retail.catalogue.manage). Selling always uses the usual size")
            Boolean useFromNowOn) {}

    @Schema(name = "RetailPurchaseRequest")
    record PurchaseRequest(
            @Schema(description = "Required for a credit purchase")
            UUID supplierId,

            @NotNull @Schema(description = "Not in the future")
            LocalDate purchasedOn,

            @NotNull
            @Pattern(regexp = "cash|mobile_money|bank|credit")
            @Schema(
                    description =
                            "cash, mobile_money, bank, or credit: owed to the supplier, who is then required (issue #557)")
            String paymentMethod,

            @Size(max = 300) String note,
            @NotEmpty @Size(max = 500) List<@Valid @NotNull PurchaseLineRequest> lines) {}

    @Schema(name = "RetailPurchaseLineBranch")
    record LineBranch(
            UUID branchId,

            @Schema(description = "In base units, always") String qty,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The quantity in the unit the line was entered in; absent for a base unit line")
            String unitQty) {}

    @Schema(name = "RetailPurchaseLine")
    record PurchaseLine(
            UUID id,
            int lineNo,
            UUID productId,
            String code,
            String description,

            @Schema(description = "Per base unit: the movement snapshot and the product's cost")
            long costMinor,

            Long sellMinor,

            @Schema(description = "In base units") String qtyTotal,

            @Schema(description = "The amount paid or owed for the line, as entered")
            long lineTotalMinor,

            List<LineBranch> qtyByBranch,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The unit the line was entered in; absent means the base unit")
            UUID unitId,

            @JsonInclude(JsonInclude.Include.NON_NULL) String unit,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Base units in one unit when the line was made")
            String unitFactor,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Quantity in the unit")
            String unitQtyTotal,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Cost per unit as entered")
            Long unitCostMinor,

            @Schema(
                    description =
                            "The unit the item is usually kept in (issue #351), to tell a line entered in another unit")
            String baseUnit) {}

    @Schema(name = "RetailPurchase")
    record Purchase(
            UUID id,
            String purchaseNo,
            UUID supplierId,
            LocalDate purchasedOn,
            String paymentMethod,
            String currency,
            long totalMinor,
            String note,
            List<PurchaseLine> lines,
            Instant createdAt,
            UUID createdBy,

            @Schema(
                    description =
                            "posted, or voided once corrected by a void (issue #351); a voided restock is never deleted")
            String status,

            @JsonInclude(JsonInclude.Include.NON_NULL) Instant voidedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID voidedBy,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The business date of the void, the day the cash book gives the cash back")
            LocalDate voidedOn,

            @JsonInclude(JsonInclude.Include.NON_NULL) String voidReason,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "True when the caller's branch scope hid other branches this restock added stock to")
            Boolean partial) {

        // JUSTIFICATION-A3: a restock gained the void fields of issue #351; the short constructor keeps the three
        // existing callers as they are.
        /** A restock that stands: the shape every read had before voids. */
        Purchase(
                UUID id,
                String purchaseNo,
                UUID supplierId,
                LocalDate purchasedOn,
                String paymentMethod,
                String currency,
                long totalMinor,
                String note,
                List<PurchaseLine> lines,
                Instant createdAt,
                UUID createdBy) {
            this(
                    id,
                    purchaseNo,
                    supplierId,
                    purchasedOn,
                    paymentMethod,
                    currency,
                    totalMinor,
                    note,
                    lines,
                    createdAt,
                    createdBy,
                    "posted",
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        Purchase withLinesAndTotal(List<PurchaseLine> newLines, long newTotal, boolean hidden) {
            return new Purchase(
                    id,
                    purchaseNo,
                    supplierId,
                    purchasedOn,
                    paymentMethod,
                    currency,
                    newTotal,
                    note,
                    newLines,
                    createdAt,
                    createdBy,
                    status,
                    voidedAt,
                    voidedBy,
                    voidedOn,
                    voidReason,
                    hidden ? Boolean.TRUE : null);
        }
    }

    @Schema(name = "RetailPurchaseVoidRequest")
    record VoidPurchaseRequest(
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailPurchasePage")
    record PurchasePage(List<Purchase> items, String nextCursor) {}
}
