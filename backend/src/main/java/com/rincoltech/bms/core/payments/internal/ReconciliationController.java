package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.internal.ReconciliationApi.Report;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payments/reconciliation} (chapter 7 section 7.11.10, FR-PAY-05): the latest run's differences, and a run on
 * request. Reading needs {@code core.payments.read} and shows only the branches the caller reads (a difference with no branch, a
 * statement line no order of ours has, only to a reader of every branch); running needs {@code core.payments.manage} in every
 * branch. A run posts nothing and changes no order's status (it only schedules an immediate look at a stuck one); see
 * {@link ReconciliationService}.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class ReconciliationController {

    private final ReconciliationService service;
    private final TransactionTemplate tx;

    ReconciliationController(ReconciliationService service, PlatformTransactionManager transactions) {
        this.service = service;
        this.tx = new TransactionTemplate(transactions);
    }

    @GetMapping
    @RequiresPermission("core.payments.read")
    @Operation(summary = "The latest reconciliation run and its differences", operationId = "getPaymentReconciliation")
    Report latest(HttpServletResponse response) {
        NoStore.apply(response);
        return service.latest();
    }

    @PostMapping("/run")
    @RequiresPermission("core.payments.manage")
    @Operation(summary = "Run the reconciliation now and return its report", operationId = "runPaymentReconciliation")
    ResponseEntity<Report> run(HttpServletResponse response) {
        NoStore.apply(response);
        var userId =
                PaymentsScope.requireEverywhere(ReconciliationService.MANAGE).userId();
        // Another run of the business holding the lock past the bounded wait is 409 try_again (BusinessLock).
        tx.executeWithoutResult(status -> service.run("manual", userId, true));
        return ResponseEntity.status(HttpStatus.OK).body(service.latest());
    }
}
