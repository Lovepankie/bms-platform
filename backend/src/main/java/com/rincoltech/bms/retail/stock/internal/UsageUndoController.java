package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UndoRequest;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageUndone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/retail/usage/{report_id}/undo} (issue #818, FR-RET-88, ADR-123). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class UsageUndoController {

    private final UsageUndoService service;

    UsageUndoController(UsageUndoService service) {
        this.service = service;
    }

    @PostMapping("/usage/{report_id}/undo")
    @RequiresPermission("retail.sale.void")
    @Operation(
            summary = "Undo a usage or damage entry with a reason: the stock goes back, the entry is kept and marked"
                    + " undone, once (FR-RET-88)",
            operationId = "undoRetailUsage")
    UsageUndone undo(@PathVariable("report_id") UUID id, @Valid @RequestBody UndoRequest request) {
        return service.undo(id, request);
    }
}
