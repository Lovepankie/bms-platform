package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.DailyProfit;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.Valuation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail/reports} (chapter 7 section 7.11.20; FR-RET-09, FR-RET-10). */
@RestController
@RequestMapping(path = "/api/v1/retail/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-reports")
class ReportsController {

    private final ReportsService service;

    ReportsController(ReportsService service) {
        this.service = service;
    }

    @GetMapping("/valuation")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Stock value at cost and expected sales at price, per branch and product (FR-RET-09)",
            operationId = "getRetailValuation")
    Valuation valuation(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "as_of", required = false) LocalDate asOf) {
        return service.valuation(branchIds, asOf);
    }

    @GetMapping("/profit/daily")
    @RequiresPermission("retail.profit.read")
    @Operation(summary = "Profit per branch per day (FR-RET-10)", operationId = "getRetailDailyProfit")
    DailyProfit dailyProfit(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return service.dailyProfit(branchIds, from, to);
    }
}
