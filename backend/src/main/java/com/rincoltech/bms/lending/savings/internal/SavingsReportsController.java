package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.BalancesReport;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.MovementsReport;
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

/**
 * The two savings reports of chapter 14 section 14.5, as JSON until the report catalogue of
 * increment 8 serves them as files: {@code lending.savings_balances} and
 * {@code lending.savings_movements}, under {@code lending.reports.members}.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/savings-reports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-savings")
class SavingsReportsController {

    private final SavingsService service;

    SavingsReportsController(SavingsService service) {
        this.service = service;
    }

    @GetMapping("/balances")
    @RequiresPermission("lending.reports.members")
    @Operation(
            summary = "Savings balances as at the end of a day (today by default), per account, with totals per product"
                    + " and branch",
            operationId = "getSavingsBalancesReport")
    BalancesReport balances(
            @RequestParam(name = "as_at", required = false) LocalDate asAt,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "product_id", required = false) UUID productId) {
        return service.balances(asAt, branchIds, productId);
    }

    @GetMapping("/movements")
    @RequiresPermission("lending.reports.members")
    @Operation(
            summary =
                    "Savings movements by value date (this month by default): opening, deposits, withdrawals, interest,"
                            + " fees, reversals, closing",
            operationId = "getSavingsMovementsReport")
    MovementsReport movements(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "product_id", required = false) UUID productId) {
        return service.movements(from, to, branchIds, productId);
    }
}
