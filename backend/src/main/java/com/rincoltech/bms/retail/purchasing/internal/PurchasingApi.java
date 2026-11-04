package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.OfflineEntry;
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

    @Schema(name = "RetailSupplierRequest", description = "Every field but the name is optional (issue #820)")
    record SupplierRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String contact,
            @Valid SupplierDetails details) {

        // JUSTIFICATION-A3: the existing callers and tests send a name and a contact only.
        SupplierRequest(String name, String contact) {
            this(name, contact, null);
        }
    }

    /**
     * The optional details of a supplier (issue #820, ADR-125, FR-RET-90), as the owner's old app kept them. In a request an
     * omitted field is unchanged and an empty one clears it; the email and the website are checked by the service.
     */
    @Schema(name = "RetailSupplierDetails")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SupplierDetails(
            @Size(max = 254) String email,
            @Size(max = 300) String address,
            @Size(max = 100) String country,

            @Size(max = 300) @Schema(description = "A web address, with or without https://")
            String website,

            @Size(max = 100) @Schema(description = "As typed, for example 30 days")
            String paymentTerms,

            @Size(max = 60)
            @Schema(description = "Shown only to those who may edit suppliers (retail.catalogue.manage)")
            String bankAccount,

            @Size(max = 60) @Schema(description = "A short free label, for example Wholesaler")
            String supplierType,

            @Size(max = 1000) String notes) {

        static final SupplierDetails NONE = new SupplierDetails(null, null, null, null, null, null, null, null);

        /** These details with the bank account left out, for a reader who may not edit suppliers. */
        SupplierDetails withoutBankAccount() {
            return new SupplierDetails(email, address, country, website, paymentTerms, null, supplierType, notes);
        }
    }

    @Schema(
            name = "RetailSupplierUpdate",
            description = "Omitted fields are unchanged; an empty contact or detail clears it")
    record UpdateSupplierRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 100) String contact,
            Boolean active,
            @Valid SupplierDetails details) {

        // JUSTIFICATION-A3: the existing callers and tests send these three only.
        UpdateSupplierRequest(String name, String contact, Boolean active) {
            this(name, contact, active, null);
        }
    }

    @Schema(name = "RetailSupplier")
    record Supplier(
            UUID id,
            String name,
            String contact,
            boolean active,

            @Schema(description = "The date added, set by the server")
            Instant createdAt,

            @Schema(description = "Send as If-Match when updating")
            int version,

            // Issue #820: the optional details; the bank account only for those who may edit suppliers.
            SupplierDetails details,

            @Schema(
                    description =
                            "Issue #820: the day of the newest restock from this supplier that is not voided, among"
                                    + " those the caller may list; never typed")
            LocalDate lastDeliveryOn) {

        Supplier withDetails(SupplierDetails shown) {
            return new Supplier(id, name, contact, active, createdAt, version, shown, lastDeliveryOn);
        }
    }

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

            @Schema(
                    description = "Defaults to today; not in the future, and before today only from a holder of"
                            + " retail.entry.backdate at every branch of the lines, with date_reason (ADR-120)")
            LocalDate purchasedOn,

            @NotNull
            @Pattern(regexp = "cash|mobile_money|bank|credit")
            @Schema(
                    description =
                            "cash, mobile_money, bank, or credit: owed to the supplier, who is then required (issue #557)")
            String paymentMethod,

            @Size(max = 300) String note,
            @NotEmpty @Size(max = 500) List<@Valid @NotNull PurchaseLineRequest> lines,

            @Size(max = 300) @Schema(description = "Why the purchase has a date before today; needed then (ADR-120)")
            String dateReason,

            @Valid OfflineEntry offline) {

        /** An earlier day the owner chose, with its reason, is judged as sent online (ADR-067 decision 13). */
        PurchaseRequest {
            offline = OfflineEntry.unlessChosen(offline, dateReason);
        }

        /** What the Idempotency-Key covers: the request without its offline block (ADR-067 decision 13). */
        PurchaseRequest forKey() {
            return new PurchaseRequest(supplierId, purchasedOn, paymentMethod, note, lines, dateReason, null);
        }

        /** The same purchase dated {@code on}: an entry kept offline takes the day it was made (ADR-067 decision 13). */
        PurchaseRequest dated(LocalDate on) {
            return java.util.Objects.equals(on, purchasedOn)
                    ? this
                    : new PurchaseRequest(supplierId, on, paymentMethod, note, lines, dateReason, offline);
        }
    }

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
            String baseUnit,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "On the response that made the line only: true when use_from_now_on was left out because"
                                    + " the pack has its own price and the caller may not change prices (issue #624)")
            Boolean sizeKept,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Why the usual size was kept, in one plain sentence, with size_kept")
            String sizeKeptNote) {

        PurchaseLine withSizeKept(String note) {
            return new PurchaseLine(
                    id,
                    lineNo,
                    productId,
                    code,
                    description,
                    costMinor,
                    sellMinor,
                    qtyTotal,
                    lineTotalMinor,
                    qtyByBranch,
                    unitId,
                    unit,
                    unitFactor,
                    unitQtyTotal,
                    unitCostMinor,
                    baseUnit,
                    true,
                    note);
        }
    }

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
            Boolean partial,

            @Schema(description = "Issue #819, ADR-124: a photo of the paper receipt is on the delivery")
            boolean hasReceiptPhoto) {

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
                    null,
                    false);
        }

        // JUSTIFICATION-A3: the lead's decision on #846 reads the photo through a port; the restock is marked after.
        /** Issue #819: the same restock, marked as having a receipt photo or not. */
        Purchase withReceiptPhoto(boolean has) {
            return new Purchase(
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
                    status,
                    voidedAt,
                    voidedBy,
                    voidedOn,
                    voidReason,
                    partial,
                    has);
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
                    hidden ? Boolean.TRUE : null,
                    hasReceiptPhoto);
        }
    }

    @Schema(name = "RetailPurchaseVoidRequest")
    record VoidPurchaseRequest(
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "RetailPurchasePage")
    record PurchasePage(List<Purchase> items, String nextCursor) {}
}
