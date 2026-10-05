package com.rincoltech.bms.retail.purchasing.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
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

    @Schema(name = "RetailSupplier")
    record Supplier(UUID id, String name, String contact, boolean active, Instant createdAt) {}

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

            @NotNull @PositiveOrZero @Schema(description = "Unit cost; becomes the product's cost")
            Long costMinor,

            @PositiveOrZero @Schema(description = "When given, becomes the product's sell price")
            Long sellMinor,

            @NotEmpty @Size(max = 50) List<@Valid @NotNull BranchQty> qtyByBranch) {}

    @Schema(name = "RetailPurchaseRequest")
    record PurchaseRequest(
            @Schema(description = "Required for a credit purchase")
            UUID supplierId,

            @NotNull @Schema(description = "Not in the future")
            LocalDate purchasedOn,

            @NotNull @Pattern(regexp = "cash|bank|credit") String paymentMethod,
            @Size(max = 300) String note,
            @NotEmpty @Size(max = 500) List<@Valid @NotNull PurchaseLineRequest> lines) {}

    @Schema(name = "RetailPurchaseLineBranch")
    record LineBranch(UUID branchId, String qty) {}

    @Schema(name = "RetailPurchaseLine")
    record PurchaseLine(
            UUID id,
            int lineNo,
            UUID productId,
            String code,
            String description,
            long costMinor,
            Long sellMinor,
            String qtyTotal,
            long lineTotalMinor,
            List<LineBranch> qtyByBranch) {}

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
            UUID createdBy) {}

    @Schema(name = "RetailPurchasePage")
    record PurchasePage(List<Purchase> items, String nextCursor) {}
}
