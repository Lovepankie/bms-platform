package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.imports.ImportRunView;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.Run;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.kernel.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/imports} (chapter 7 section 7.11.3, issue #141, spec section 11, ADR-024 decision 8): the actions
 * of the operator portal's run queue, for platform operators only, on the platform host, through the public port
 * {@link ImportRuns} of {@code core.imports}; it lives in the platform console because only the console acts inside one tenant
 * ({@code SecurityArchitectureTest}). The queue itself is the platform report of {@code core.imports}, which names each
 * business by its slug; an action names the business by
 * that slug and the run by its id, and runs for that business alone ({@code TenantContext.callAs}), so row-level security
 * holds as on the business's own host. Only a platform principal reaches a platform path (the authentication filter), so
 * the operator id the service records is always an operator's (comment on #141); the service checks again that it is an
 * active one. The commit and its resume take one transaction per batch and can run for minutes, so they start on their own
 * thread and answer 202 at once; the run's state on the queue shows how they go.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class PlatformImportRunsController {

    private static final Logger log = LoggerFactory.getLogger(PlatformImportRunsController.class);

    private final ImportRuns runs;
    private final JdbcClient jdbc;

    PlatformImportRunsController(ImportRuns runs, JdbcClient jdbc) {
        this.runs = runs;
        this.jdbc = jdbc;
    }

    @Schema(
            name = "PlatformImportRunState",
            description = "One run as the operator portal shows it, with the kill switch")
    record RunState(
            String tenantSlug,
            ImportRunView run,

            @Schema(description = "The platform kill switch: false stops every commit before its next batch")
            boolean commitEnabled) {}

    @Schema(name = "PlatformImportOverrideRequest")
    record OverrideRequest(
            @NotBlank
            @Size(min = 5, max = 500)
            @Schema(description = "Why this business may be imported into while it trades")
            String reason) {}

    @Schema(name = "PlatformImportDumpRequest", description = "The pre-import dump as taken on the host")
    record DumpRequest(
            @NotBlank @Size(max = 255) String file,
            @NotNull Instant takenAt,
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String sha256) {}

    @Schema(
            name = "PlatformImportDumpCheckRequest",
            description = "The check of the dump: pg_restore --list and the hash")
    record DumpCheckRequest(
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") @Schema(description = "The SHA-256 measured again")
            String sha256,

            @NotNull @Min(1) @Schema(description = "The entries pg_restore --list listed")
            Integer listedEntries) {}

    @Schema(name = "PlatformImportKillSwitch")
    record KillSwitch(boolean commitEnabled) {}

    @Schema(name = "PlatformImportKillSwitchRequest")
    record KillSwitchRequest(
            @NotNull Boolean commitEnabled,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why, kept in the platform audit log")
            String reason) {}

    @GetMapping("/businesses/{slug}/runs/{run_id}")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "One import run of a business, by its slug, with the kill switch",
            operationId = "platformGetImportRun")
    RunState get(@PathVariable("slug") String slug, @PathVariable("run_id") UUID runId) {
        return state(slug, as(slug, () -> runs.get(runId)));
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/stop")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "The stop button: the commit stops before the next batch, or within 1,000 rows; audited",
            operationId = "platformStopImportRun")
    RunState stop(@PathVariable("slug") String slug, @PathVariable("run_id") UUID runId) {
        return state(slug, as(slug, () -> runs.requestHalt(runId)));
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Start the commit of a ready run whose import_commit is approved and whose dump is checked; it runs"
                            + " on, batch by batch",
            operationId = "platformStartImportRun")
    RunState start(@PathVariable("slug") String slug, @PathVariable("run_id") UUID runId) {
        UUID tenant = tenant(slug);
        UUID operator = CurrentPrincipal.require().userId();
        requireSwitchOn();
        // The guards, the move to committing and its audit row (with this operator as actor) run here, so every refusal
        // is this request's answer; only the batches go on in the background (review of 1c9568b on #895).
        Run run = TenantContext.callAs(tenant, () -> {
            runs.startCommit(runId, operator, work -> background(tenant, runId, "commit", work));
            return runs.get(runId);
        });
        return state(slug, run);
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Resume a stopped or interrupted commit from its first batch not committed; it runs on",
            operationId = "platformResumeImportRun")
    RunState resume(@PathVariable("slug") String slug, @PathVariable("run_id") UUID runId) {
        UUID tenant = tenant(slug);
        UUID operator = CurrentPrincipal.require().userId();
        requireSwitchOn();
        // Refused here while any commit loop of the business is alive (IMPORT_IN_PROGRESS), so a stop is never undone
        // under a commit still finishing its batch, and a second resume never starts a second writer.
        Run run = TenantContext.callAs(tenant, () -> {
            runs.startResume(runId, operator, work -> background(tenant, runId, "resume", work));
            return runs.get(runId);
        });
        return state(slug, run);
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/trading-override")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Ask to import into a business that already trades, with a reason; a second operator approves it;"
                    + " audited",
            operationId = "platformRequestImportTradingOverride")
    RunState override(
            @PathVariable("slug") String slug,
            @PathVariable("run_id") UUID runId,
            @Valid @RequestBody OverrideRequest request) {
        UUID operator = CurrentPrincipal.require().userId();
        return state(slug, as(slug, () -> runs.allowTradingTenant(runId, operator, request.reason())));
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/trading-override/approve")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Approve the trading override; the operator who asked for it cannot; audited",
            operationId = "platformApproveImportTradingOverride")
    RunState approveOverride(@PathVariable("slug") String slug, @PathVariable("run_id") UUID runId) {
        UUID operator = CurrentPrincipal.require().userId();
        return state(slug, as(slug, () -> runs.approveTradingTenant(runId, operator)));
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/dump")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Record the pre-import dump taken on the host: file name, time and SHA-256; audited",
            operationId = "platformRecordImportDump")
    RunState dump(
            @PathVariable("slug") String slug,
            @PathVariable("run_id") UUID runId,
            @Valid @RequestBody DumpRequest request) {
        return state(
                slug,
                as(slug, () -> runs.recordPreImportDump(runId, request.file(), request.takenAt(), request.sha256())));
    }

    @PostMapping("/businesses/{slug}/runs/{run_id}/dump/check")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Record the check of the dump: its SHA-256 again and the entries pg_restore --list listed; audited",
            operationId = "platformCheckImportDump")
    RunState checkDump(
            @PathVariable("slug") String slug,
            @PathVariable("run_id") UUID runId,
            @Valid @RequestBody DumpCheckRequest request) {
        return state(slug, as(slug, () -> runs.verifyPreImportDump(runId, request.sha256(), request.listedEntries())));
    }

    @GetMapping("/kill-switch")
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "Whether imports may commit on this platform", operationId = "platformGetImportKillSwitch")
    KillSwitch killSwitch() {
        return new KillSwitch(runs.commitEnabled());
    }

    @PutMapping("/kill-switch")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Stop every import commit before its next batch, or allow them again, with a reason; audited",
            operationId = "platformSetImportKillSwitch")
    KillSwitch setKillSwitch(@Valid @RequestBody KillSwitchRequest request) {
        runs.setCommitEnabled(
                request.commitEnabled(), CurrentPrincipal.require().userId(), request.reason());
        return new KillSwitch(runs.commitEnabled());
    }

    private RunState state(String slug, Run run) {
        return new RunState(slug, ImportRunView.of(run), runs.commitEnabled());
    }

    private <T> T as(String slug, Supplier<T> work) {
        return TenantContext.callAs(tenant(slug), work);
    }

    /** The business of a slug, as the platform report names it; an unknown slug is 404. */
    private UUID tenant(String slug) {
        return jdbc.sql("SELECT id FROM app_resolve_tenant_status(?)")
                .param(slug)
                .query(UUID.class)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private void requireSwitchOn() {
        if (!runs.commitEnabled()) {
            throw conflict("imports_disabled", "Imports are stopped on this platform. Allow them again first.");
        }
    }

    /** Runs a commit or a resume for one business on its own thread; a failure is logged by run id and kind only. */
    private static void background(UUID tenant, UUID runId, String what, Supplier<ImportRuns.CommitOutcome> work) {
        Thread.ofVirtual().name("import-" + what + "-" + runId).start(() -> {
            try {
                ImportRuns.CommitOutcome outcome = TenantContext.callAs(tenant, work);
                log.info("import {} of run {} ended {}", what, runId, outcome);
            } catch (RuntimeException e) {
                log.warn(
                        "import {} of run {} stopped: {}",
                        what,
                        runId,
                        e.getClass().getSimpleName());
            }
        });
    }

    private static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Import cannot do that now", detail);
    }
}
