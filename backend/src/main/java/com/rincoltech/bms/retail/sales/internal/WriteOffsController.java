package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffOutcome;
import com.rincoltech.bms.retail.sales.internal.WriteOffsApi.WriteOffRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/retail/sales/{sale_id}/write-off} (ADR-043, issue #340; chapter 7 section 7.11.20). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-sales")
class WriteOffsController {

    private final WriteOffsService service;

    WriteOffsController(WriteOffsService service) {
        this.service = service;
    }

    @PostMapping("/sales/{sale_id}/write-off")
    @RequiresPermission("retail.credit.write_off")
    @Operation(
            summary =
                    "Write off the unpaid part of a credit sale as a loss: clears the debt, never deletes (issue #340); M."
                            + " 201 when done, 202 when it waits for a second administrator",
            operationId = "writeOffRetailSale")
    ResponseEntity<WriteOffOutcome> create(
            @PathVariable("sale_id") UUID saleId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WriteOffRequest request) {
        Outcome<WriteOffOutcome> outcome = service.create(idempotencyKey, saleId, request);
        ResponseEntity.BodyBuilder response;
        if (outcome.body().executed()) {
            response = ResponseEntity.created(URI.create("/api/v1/retail/sales/" + saleId));
        } else {
            response = ResponseEntity.status(HttpStatus.ACCEPTED);
        }
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
