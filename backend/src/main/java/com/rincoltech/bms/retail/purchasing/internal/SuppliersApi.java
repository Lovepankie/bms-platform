package com.rincoltech.bms.retail.purchasing.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The bodies of a supplier's page (issue #820, ADR-125, chapter 7 section 7.11.20). */
final class SuppliersApi {

    private SuppliersApi() {}

    @Schema(
            name = "RetailSupplierDelivery",
            description = "A restock from the supplier; it opens as the restock itself")
    record Delivery(
            UUID id,
            String purchaseNo,
            LocalDate purchasedOn,
            boolean voided,

            @Schema(description = "How many lines added stock at the branches the caller may read")
            int lines) {}

    @Schema(name = "RetailSupplierDeliveryPage")
    record DeliveryPage(List<Delivery> items, String nextCursor) {}

    @Schema(name = "RetailSupplierTypes", description = "The supplier types already used, the most used first")
    record SupplierTypes(List<String> items) {}
}
