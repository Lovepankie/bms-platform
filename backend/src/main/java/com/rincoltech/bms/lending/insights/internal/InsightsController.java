package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.BriefResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DigestPreview;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DigestSettings;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DigestSettingsRequest;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.MembersResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.PanelsResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.PortfolioResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.RevenueResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.TableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/lending/insights} (chapter 7 section 7.11.21). Every route takes the same page
 * filter: {@code from} and {@code to} (inclusive, default this month to today), {@code branch_id}
 * (repeatable, narrowed to the caller's scope), {@code officer_id} (ignored without
 * {@code lending.insights.all_officers}: such a caller sees their own loans) and
 * {@code product_id}. The page polls every 60 seconds; responses are never cached.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/insights", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-insights")
class InsightsController {

    private final InsightsService service;
    private final DigestService digest;

    InsightsController(InsightsService service, DigestService digest) {
        this.service = service;
        this.digest = digest;
    }

    private Scope scope(LocalDate from, LocalDate to, List<UUID> branchIds, UUID officerId, UUID productId) {
        return service.scope(from, to, branchIds, officerId, productId);
    }

    private static <T> ResponseEntity<T> fresh(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/brief")
    @RequiresPermission("lending.insights.read")
    @Operation(
            summary = "The morning brief: today's movements with one plain sentence each",
            operationId = "getInsightsBrief")
    ResponseEntity<BriefResponse> brief(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId) {
        return fresh(service.brief(scope(null, null, branchIds, officerId, productId)));
    }

    @GetMapping("/portfolio")
    @RequiresPermission("lending.insights.read")
    @Operation(
            summary = "Loan portfolio: outstanding, PAR and ageing, flows by period, collection rate, forecast, trend",
            operationId = "getInsightsPortfolio")
    ResponseEntity<PortfolioResponse> portfolio(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @Parameter(description = "day, week, month or year; chosen from the range when absent")
                    @RequestParam(name = "grain", required = false)
                    String grain,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId) {
        return fresh(service.portfolio(scope(from, to, branchIds, officerId, productId), grain));
    }

    @GetMapping("/revenue")
    @RequiresPermission("lending.insights.read")
    @Operation(
            summary = "Revenue from posted journal lines by product, branch and month, with effective yield",
            operationId = "getInsightsRevenue")
    ResponseEntity<RevenueResponse> revenue(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId) {
        return fresh(service.revenue(scope(from, to, branchIds, officerId, productId)));
    }

    @GetMapping("/members")
    @RequiresPermission("lending.insights.read")
    @Operation(
            summary = "Member activity: new members, the applications funnel, KYC, credit scores, dormancy, staff",
            operationId = "getInsightsMembers")
    ResponseEntity<MembersResponse> members(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "grain", required = false) String grain,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId) {
        return fresh(service.members(scope(from, to, branchIds, officerId, productId), grain));
    }

    @GetMapping("/panels")
    @RequiresPermission("lending.insights.read")
    @Operation(
            summary = "Panels registered by other modules (savings, investments), for a tenant with their data",
            operationId = "getInsightsPanels")
    ResponseEntity<PanelsResponse> panels(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId) {
        return fresh(service.panels(scope(from, to, branchIds, officerId, null)));
    }

    @GetMapping("/tables/{table}")
    @RequiresPermission("lending.insights.read")
    @Operation(summary = "The rows behind a number (drill-down), at most 200", operationId = "getInsightsTable")
    ResponseEntity<TableResponse> table(
            @PathVariable("table") String table,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @Parameter(
                            description =
                                    "Table parameters: date, status, stage, account, min_dpd, max_dpd, bucket, days, group, key")
                    @RequestParam
                    Map<String, String> all) {
        return fresh(service.table(table, scope(from, to, branchIds, officerId, productId), extra(all)));
    }

    @GetMapping(path = "/tables/{table}/export", produces = "text/csv")
    @RequiresPermission("lending.insights.export")
    @Operation(
            summary = "The same table as CSV, at most 10,000 rows; names masked without member access; audited",
            operationId = "exportInsightsTable")
    ResponseEntity<String> export(
            @PathVariable("table") String table,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "officer_id", required = false) UUID officerId,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam Map<String, String> all) {
        Scope s = scope(from, to, branchIds, officerId, productId);
        String csv = service.export(table, s, extra(all));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"insights-" + table + "-" + s.from() + "-to-" + s.to() + ".csv\"")
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    /** The table parameters beyond the page filter. */
    private static Map<String, String> extra(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<>(all);
        List.of("from", "to", "branch_id", "officer_id", "product_id").forEach(out::remove);
        return out;
    }

    @GetMapping("/digest")
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "The owner's daily digest settings (off by default)",
            operationId = "getInsightsDigestSettings")
    ResponseEntity<DigestSettings> digestSettings() {
        DigestSettings settings = digest.settings();
        return ResponseEntity.ok().eTag(String.valueOf(settings.version())).body(settings);
    }

    @PutMapping("/digest")
    @RequiresPermission("core.settings.manage")
    @Operation(summary = "Change the daily digest settings", operationId = "updateInsightsDigestSettings")
    ResponseEntity<DigestSettings> updateDigest(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody DigestSettingsRequest request) {
        DigestSettings settings = digest.update(ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(settings.version())).body(settings);
    }

    @GetMapping("/digest/preview")
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "The digest text as it would be sent now, for the whole tenant",
            operationId = "previewInsightsDigest")
    ResponseEntity<DigestPreview> preview() {
        return fresh(digest.preview());
    }
}
