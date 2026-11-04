package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos.Kind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * The receipt photo of a usage or damage entry (issue #819, ADR-124, FR-RET-89): added by whoever may report usage at the
 * entry's branch, read by whoever may read stock there (the readers of Past usage and damage).
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class UsageReceiptPhotoController {

    static final String ADD = "retail.usage.report";
    static final String READ = "retail.stock.read";

    private final ReceiptPhotos photos;
    private final UsageRule rule;

    UsageReceiptPhotoController(ReceiptPhotos photos, UsageRule rule) {
        this.photos = photos;
        this.rule = rule;
    }

    @PostMapping(path = "/usage/{report_id}/receipt-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(ADD)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary =
                    "Add the photo of the paper receipt to a usage or damage entry: PNG, JPEG or WebP, 5 MB, re-encoded"
                            + " without metadata (FR-RET-89)",
            operationId = "addRetailUsageReceiptPhoto")
    ReceiptPhotos.Added add(@PathVariable("report_id") UUID id, @RequestPart("file") MultipartFile file) {
        Principal principal = CurrentPrincipal.require();
        return new ReceiptPhotos.Added(photos.attach(Kind.USAGE, id, ReceiptPhotos.bytes(file), () -> {
            UUID branch = rule.branch(id).filter(b -> principal.may(ADD, b)).orElseThrow(ApiException::notFound);
            if (rule.undone(id)) {
                throw ReceiptPhotos.voided();
            }
            return branch;
        }));
    }

    @GetMapping(path = "/usage/{report_id}/receipt-photo", produces = MediaType.ALL_VALUE)
    @RequiresPermission(READ)
    @Operation(
            summary = "The receipt photo of a usage or damage entry, from the same origin (FR-RET-89)",
            operationId = "getRetailUsageReceiptPhoto")
    ResponseEntity<byte[]> photo(@PathVariable("report_id") UUID id) {
        return ReceiptPhotos.serve(photos.image(Kind.USAGE, id, null));
    }

    /** Whoever may read stock at the entry's branch sees its photo. */
    @Component
    static class UsageRule implements ReceiptPhotos.Rule {

        private final JdbcClient jdbc;

        UsageRule(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Kind kind() {
            return Kind.USAGE;
        }

        @Override
        public boolean canSee(Principal principal, UUID recordId, UUID photoId) {
            return branch(recordId).filter(b -> principal.may(READ, b)).isPresent();
        }

        /** The entry was undone (issue #818): it takes no receipt photo. */
        boolean undone(UUID reportId) {
            return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_usage_undos WHERE report_id = ?)")
                    .param(reportId)
                    .query(Boolean.class)
                    .single();
        }

        /** The entry's branch, in the caller's transaction; empty when there is no such entry in this business. */
        Optional<UUID> branch(UUID reportId) {
            return jdbc.sql("SELECT branch_id FROM retail_usage_reports WHERE id = ?")
                    .param(reportId)
                    .query(UUID.class)
                    .optional();
        }
    }
}
