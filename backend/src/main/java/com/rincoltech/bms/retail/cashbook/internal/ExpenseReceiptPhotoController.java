package com.rincoltech.bms.retail.cashbook.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos.Kind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The receipt photo of an expense (issue #819, ADR-124, FR-RET-89). The form sends the photo first and passes its id as the
 * {@code receipt_document_id} the expense API already took; a photo added later, from the expense, is a document of the
 * expense. Whoever may read the cash book at the expense's branch sees it.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-cashbook")
class ExpenseReceiptPhotoController {

    private final ReceiptPhotos photos;
    private final ExpenseRule rule;
    private final CashbookRepository repo;

    ExpenseReceiptPhotoController(ReceiptPhotos photos, ExpenseRule rule, CashbookRepository repo) {
        this.photos = photos;
        this.rule = rule;
        this.repo = repo;
    }

    @PostMapping(path = "/expenses/receipt-photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(CashbookSupport.EXPENSE_RECORD)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary =
                    "Send the photo of an expense's paper receipt before the expense: PNG, JPEG or WebP, 5 MB, re-encoded"
                            + " without metadata; pass the answer's photo_id as receipt_document_id (FR-RET-89)",
            operationId = "uploadRetailExpenseReceiptPhoto")
    ReceiptPhotos.Added upload(@RequestPart("file") MultipartFile file) {
        return new ReceiptPhotos.Added(photos.upload(Kind.EXPENSE, ReceiptPhotos.bytes(file), null));
    }

    @PostMapping(path = "/expenses/{expense_id}/receipt-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(CashbookSupport.EXPENSE_RECORD)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Add the photo of the paper receipt to an expense already recorded (FR-RET-89)",
            operationId = "addRetailExpenseReceiptPhoto")
    ReceiptPhotos.Added add(@PathVariable("expense_id") UUID id, @RequestPart("file") MultipartFile file) {
        Principal principal = CurrentPrincipal.require();
        return new ReceiptPhotos.Added(photos.attach(Kind.EXPENSE, id, ReceiptPhotos.bytes(file), () -> {
            // Under the row lock a void takes, so a void committing meanwhile is seen (review of #846).
            CashbookRepository.ExpenseRow e = repo.expense(id, true)
                    .filter(x -> principal.may(CashbookSupport.EXPENSE_RECORD, x.branchId()))
                    .orElseThrow(ApiException::notFound);
            if (e.voided().voided()) {
                throw ReceiptPhotos.voided();
            }
            return e.branchId();
        }));
    }

    @GetMapping(path = "/expenses/{expense_id}/receipt-photo", produces = MediaType.ALL_VALUE)
    @RequiresPermission(CashbookSupport.READ)
    @Operation(
            summary = "The receipt photo of an expense, from the same origin (FR-RET-89)",
            operationId = "getRetailExpenseReceiptPhoto")
    ResponseEntity<byte[]> photo(@PathVariable("expense_id") UUID id) {
        return ReceiptPhotos.serve(photos.image(Kind.EXPENSE, id, rule.sentWith(id)));
    }

    /**
     * Whoever may read the cash book at the expense's branch sees its photo. A photo sent before its expense is found
     * through the expense that names it; one that no expense names yet is seen by nobody.
     */
    @Component
    static class ExpenseRule implements ReceiptPhotos.Rule {

        /** Who decides an expense waiting for approval (ADR-091): an administrator at its branch. */
        static final String DECIDES = "core.settings.manage";

        private final JdbcClient jdbc;
        // Lazy: the approvals module runs the expense action, which calls back into this module.
        private final Approvals approvals;

        ExpenseRule(JdbcClient jdbc, @Lazy Approvals approvals) {
            this.jdbc = jdbc;
            this.approvals = approvals;
        }

        @Override
        public Kind kind() {
            return Kind.EXPENSE;
        }

        /**
         * Cash book readers at the expense's branch; and, while an expense that names the photo waits for an administrator
         * (review of #846), its readers and its deciders at the branch it is fixed to, so the approver sees the receipt.
         */
        @Override
        public boolean canSee(Principal principal, UUID recordId, UUID photoId) {
            boolean recorded = jdbc
                    .sql("SELECT branch_id FROM retail_expenses WHERE id = :record OR receipt_document_id = :photo")
                    .param("record", recordId)
                    .param("photo", photoId)
                    .query(UUID.class)
                    .list()
                    .stream()
                    .anyMatch(b -> principal.may(CashbookSupport.READ, b));
            return recorded
                    || waiting(photoId)
                            .filter(b -> principal.may(CashbookSupport.READ, b) || principal.may(DECIDES, b))
                            .isPresent();
        }

        /**
         * A photo sent before its expense is claimed once an expense names it, and while an expense that names it waits for
         * an administrator (review of #846: an approval may take days); one added to an expense always is.
         */
        @Override
        public boolean claimed(UUID recordId, UUID photoId) {
            boolean named = jdbc.sql(
                            "SELECT EXISTS (SELECT 1 FROM retail_expenses WHERE id = :record OR receipt_document_id = :photo)")
                    .param("record", recordId)
                    .param("photo", photoId)
                    .query(Boolean.class)
                    .single();
            return named || waiting(photoId).isPresent();
        }

        /** The branch of an expense still waiting for an administrator that names this photo, if one does. */
        private java.util.Optional<UUID> waiting(UUID photoId) {
            return photoId == null
                    ? java.util.Optional.empty()
                    : approvals.pendingWith(ExpenseAction.TYPE, "receipt_document_id", photoId.toString());
        }

        /** The receipt the expense was recorded with, null when it had none; the route then reads a photo added later. */
        @Transactional(readOnly = true)
        public UUID sentWith(UUID expenseId) {
            return jdbc.sql("SELECT receipt_document_id FROM retail_expenses WHERE id = ?")
                    .param(expenseId)
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
        }
    }
}
