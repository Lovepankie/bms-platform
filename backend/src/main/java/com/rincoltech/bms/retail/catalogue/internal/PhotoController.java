package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Family;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/v1/retail} photo routes of items (issue #384 slice 2, ADR-053; chapter 7 section 7.11.20). The photo is read
 * from the same origin through {@code GET /retail/photos/{photo_id}} with the id the product or item carries as
 * {@code photo_id} (the content security policy allows images from the app's own origin only).
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-catalogue")
class PhotoController {

    private final PhotoService service;

    PhotoController(PhotoService service) {
        this.service = service;
    }

    @GetMapping(path = "/photos/{photo_id}", produces = MediaType.ALL_VALUE)
    @RequiresPermission(PhotoAccess.READ)
    @Operation(
            summary =
                    "The image of an item photo, from the same origin; size=small is the 240 px copy for the item cards and"
                            + " pickers (issue #384)",
            operationId = "getRetailPhoto")
    ResponseEntity<byte[]> photo(
            @PathVariable("photo_id") UUID id, @RequestParam(name = "size", required = false) String size) {
        if (size != null && !size.equals("small")) {
            throw ApiException.validation(List.of(new ApiException.FieldProblem(
                    "size", "invalid", "size is small, or left out for the full photo.")));
        }
        Documents.Image image = service.photo(id, size != null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                // A photo never changes under its id (a new photo is a new document), so the browser may keep it a day.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .eTag(image.sha256())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    @PostMapping(path = "/products/{product_id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Set an item's photo: PNG, JPEG or WebP, 5 MB, re-encoded without metadata (issue #384)",
            operationId = "setRetailProductPhoto")
    ResponseEntity<Product> setProductPhoto(
            @PathVariable("product_id") UUID id, @RequestPart("file") MultipartFile file) {
        Product p = service.setProductPhoto(id, bytes(file));
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }

    @DeleteMapping("/products/{product_id}/photo")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Take an item's own photo off; a size then shows its item's photo (issue #384)",
            operationId = "clearRetailProductPhoto")
    ResponseEntity<Product> clearProductPhoto(@PathVariable("product_id") UUID id) {
        Product p = service.clearProductPhoto(id);
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }

    @PostMapping(path = "/product-families/{family_id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary =
                    "Set the photo of an item with sizes and colours, shown for every size without its own (issue #384)",
            operationId = "setRetailProductFamilyPhoto")
    ResponseEntity<Family> setFamilyPhoto(@PathVariable("family_id") UUID id, @RequestPart("file") MultipartFile file) {
        Family f = service.setFamilyPhoto(id, bytes(file));
        return ResponseEntity.ok().eTag(String.valueOf(f.version())).body(f);
    }

    @DeleteMapping("/product-families/{family_id}/photo")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Take the photo of an item with sizes and colours off (issue #384)",
            operationId = "clearRetailProductFamilyPhoto")
    ResponseEntity<Family> clearFamilyPhoto(@PathVariable("family_id") UUID id) {
        Family f = service.clearFamilyPhoto(id);
        return ResponseEntity.ok().eTag(String.valueOf(f.version())).body(f);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
    }
}
