package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos.Kind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.Optional;
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
 * The receipt photo of a sale (issue #819, ADR-124, FR-RET-89): added by whoever may make a sale at its branch and sees the
 * sale, read by whoever sees the sale (ADR-055: another seller's sale only with {@code retail.sale.all_sellers}).
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-sales")
class SaleReceiptPhotoController {

    private final ReceiptPhotos photos;
    private final SaleRule rule;

    SaleReceiptPhotoController(ReceiptPhotos photos, SaleRule rule) {
        this.photos = photos;
        this.rule = rule;
    }

    @PostMapping(path = "/sales/{sale_id}/receipt-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("retail.sale.create")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary =
                    "Add the photo of the paper receipt to a sale: PNG, JPEG or WebP, 5 MB, re-encoded without metadata"
                            + " (FR-RET-89)",
            operationId = "addRetailSaleReceiptPhoto")
    ReceiptPhotos.Added add(@PathVariable("sale_id") UUID id, @RequestPart("file") MultipartFile file) {
        Principal principal = CurrentPrincipal.require();
        return new ReceiptPhotos.Added(photos.attach(Kind.SALE, id, ReceiptPhotos.bytes(file), () -> {
            // The lead's decision on the review of #846: the person who made the sale adds its photo, or someone
            // who may cancel sales at its branch; a seller who only sees a colleague's credit sale takes none. A
            // voided sale takes none.
            // Under the row lock a void takes, so a void committing meanwhile is seen (review of #846).
            SalesApi.Sale sale = rule.seen(principal, id, true)
                    .filter(s -> principal.may("retail.sale.create", s.branchId()))
                    .filter(s -> principal.may("retail.sale.void", s.branchId())
                            || Objects.equals(principal.userId(), s.createdBy()))
                    .orElseThrow(ApiException::notFound);
            if ("voided".equals(sale.status())) {
                throw ReceiptPhotos.voided();
            }
            return sale.branchId();
        }));
    }

    @GetMapping(path = "/sales/{sale_id}/receipt-photo", produces = MediaType.ALL_VALUE)
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "The receipt photo of a sale, from the same origin (FR-RET-89)",
            operationId = "getRetailSaleReceiptPhoto")
    ResponseEntity<byte[]> photo(@PathVariable("sale_id") UUID id) {
        return ReceiptPhotos.serve(photos.image(Kind.SALE, id, null));
    }

    /** Whoever sees the sale sees its photo: the check of {@code GET /retail/sales/{sale_id}}, in the caller's transaction. */
    @Component
    static class SaleRule implements ReceiptPhotos.Rule {

        private final SalesRepository repo;

        SaleRule(SalesRepository repo) {
            this.repo = repo;
        }

        @Override
        public Kind kind() {
            return Kind.SALE;
        }

        @Override
        public boolean canSee(Principal principal, UUID recordId, UUID photoId) {
            return seen(principal, recordId, false).isPresent();
        }

        /** The sale when the caller sees it, as {@code GET /retail/sales/{sale_id}} does, else empty; locked when asked. */
        Optional<SalesApi.Sale> seen(Principal principal, UUID saleId, boolean lock) {
            return repo.find(saleId, lock)
                    .map(SalesRepository.Header::sale)
                    .filter(s -> principal.may("retail.sale.read", s.branchId()))
                    .filter(s -> principal.may(SalesSummaryService.ALL_SELLERS, s.branchId())
                            || Objects.equals(principal.userId(), s.createdBy())
                            || SalesSummaryService.stillOwed(s));
        }
    }
}
