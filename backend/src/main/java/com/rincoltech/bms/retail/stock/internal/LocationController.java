package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.LocationApi.InFlightReport;
import com.rincoltech.bms.retail.stock.internal.LocationApi.LocationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail/reports} location reports (chapter 7 section 7.11.20; ADR-033). */
@RestController
@RequestMapping(path = "/api/v1/retail/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class LocationController {

    private final LocationService service;

    LocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping("/stock-by-location")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Every product's balance in each store and shop the caller may read, subtotals by role,"
                    + " stock in transit and the combined total (ADR-033)",
            operationId = "getRetailStockByLocation")
    LocationReport stockByLocation(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "negative_only", required = false, defaultValue = "false") boolean negativeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.stockByLocation(query, categoryId, negativeOnly, limit, cursor);
    }

    @GetMapping("/transfers-in-flight")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Transfers dispatched and not yet received, with age and value; needs the transfer_receipt"
                    + " capability (ADR-033)",
            operationId = "getRetailTransfersInFlight")
    InFlightReport inFlight() {
        return service.inFlight();
    }
}
