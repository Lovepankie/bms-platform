package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos.Kind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The receipt photo of a delivery, a restock made with Buy stock (issue #819, ADR-124, FR-RET-89): added and read by whoever
 * reads the restock through {@code GET /retail/purchases/{purchase_id}} (its branch scope, and ADR-055: someone else's only
 * with {@code retail.profit.read}). A restock may add stock to several shops, so its photo names no branch.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-purchasing")
class DeliveryReceiptPhotoController {

    private final ReceiptPhotos photos;
    private final PurchasingService purchases;

    DeliveryReceiptPhotoController(ReceiptPhotos photos, PurchasingService purchases) {
        this.photos = photos;
        this.purchases = purchases;
    }

    @PostMapping(path = "/purchases/{purchase_id}/receipt-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PurchasingService.PERMISSION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Add the photo of the paper receipt to a delivery: PNG, JPEG or WebP, 5 MB, re-encoded without"
                    + " metadata (FR-RET-89)",
            operationId = "addRetailPurchaseReceiptPhoto")
    ReceiptPhotos.Added add(@PathVariable("purchase_id") UUID id, @RequestPart("file") MultipartFile file) {
        return new ReceiptPhotos.Added(photos.attach(Kind.DELIVERY, id, ReceiptPhotos.bytes(file), () -> {
            String status =
                    purchases.visible(id).orElseThrow(ApiException::notFound).status();
            if ("voided".equals(status)) {
                throw ReceiptPhotos.voided();
            }
            return null;
        }));
    }

    @GetMapping(path = "/purchases/{purchase_id}/receipt-photo", produces = MediaType.ALL_VALUE)
    @RequiresPermission(PurchasingService.PERMISSION)
    @Operation(
            summary = "The receipt photo of a delivery, from the same origin (FR-RET-89)",
            operationId = "getRetailPurchaseReceiptPhoto")
    ResponseEntity<byte[]> photo(@PathVariable("purchase_id") UUID id) {
        return ReceiptPhotos.serve(photos.image(Kind.DELIVERY, id, null));
    }

    /** Whoever reads the restock sees its photo: the check of {@code GET /retail/purchases/{purchase_id}}. */
    @Component
    static class DeliveryRule implements ReceiptPhotos.Rule {

        private final PurchasingService purchases;

        DeliveryRule(PurchasingService purchases) {
            this.purchases = purchases;
        }

        @Override
        public Kind kind() {
            return Kind.DELIVERY;
        }

        @Override
        public boolean canSee(Principal principal, UUID recordId, UUID photoId) {
            return principal.hasPermission(PurchasingService.PERMISSION)
                    && purchases.visible(recordId).isPresent();
        }
    }
}
