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
    private final SalesAnalysisService salesAnalysis;
    private final MarginService margins;
    private final StockHealthService stockHealth;
    private final CreditService credit;
    private final EvaluationService evaluation;
    private final DashboardService dashboard;

    ReportsController(
            ReportsService service,
            SalesAnalysisService salesAnalysis,
            MarginService margins,
            StockHealthService stockHealth,
            CreditService credit,
            EvaluationService evaluation,
            DashboardService dashboard) {
        this.service = service;
        this.salesAnalysis = salesAnalysis;
        this.margins = margins;
        this.stockHealth = stockHealth;
        this.credit = credit;
        this.evaluation = evaluation;
        this.dashboard = dashboard;
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

    @GetMapping("/sales-analysis")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "Sales by period, branch, category, product and seller, with slow movers (issue #149)",
            operationId = "getRetailSalesAnalysis")
    SalesAnalysisApi.Analysis salesAnalysis(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "group", required = false) String group,
            @RequestParam(name = "top", required = false) Integer top,
            @RequestParam(name = "slow_days", required = false) Integer slowDays) {
        return salesAnalysis.analyse(branchIds, from, to, group, top, slowDays);
    }

    @GetMapping("/margins")
    @RequiresPermission("retail.profit.read")
    @Operation(
            summary = "Profit and margin by item and category, items under a target, price change impact (issue #149)",
            operationId = "getRetailMargins")
    MarginApi.Report margins(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "top", required = false) Integer top,
            @RequestParam(name = "target_bp", required = false) Integer targetBp) {
        return margins.margins(branchIds, from, to, top, targetBp);
    }

    @GetMapping("/stock-health")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Days of cover, reorder suggestions, dead stock and shrinkage by branch (issue #149)",
            operationId = "getRetailStockHealth")
    StockHealthApi.Report stockHealth(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "top", required = false) Integer top,
            @RequestParam(name = "lead_days", required = false) Integer leadDays,
            @RequestParam(name = "cover_days", required = false) Integer coverDays) {
        return stockHealth.report(branchIds, from, to, top, leadDays, coverDays);
    }

    @GetMapping("/credit-control")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "Outstanding credit by buyer with ageing, the overdue list and payments received (issue #149)",
            operationId = "getRetailCreditControl")
    CreditApi.Report creditControl(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "top", required = false) Integer top,
            @RequestParam(name = "sort", required = false) String sort) {
        return credit.report(branchIds, from, to, top, sort);
    }

    @GetMapping("/business-evaluation")
    @RequiresPermission("retail.profit.read")
    @Operation(
            summary = "Profit to date, expected profit of the stock on hand and a 30 day run rate (issue #149)",
            operationId = "getRetailBusinessEvaluation")
    EvaluationApi.Report businessEvaluation(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "top", required = false) Integer top) {
        return evaluation.evaluate(branchIds, from, to, top);
    }

    @GetMapping("/dashboard")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "Owner dashboard: today, 7 and 30 days, stock value and counts, by shop (issue #149)",
            operationId = "getRetailDashboard")
    DashboardApi.Report dashboard(@RequestParam(name = "branch_id", required = false) List<UUID> branchIds) {
        return dashboard.dashboard(branchIds);
    }
}
