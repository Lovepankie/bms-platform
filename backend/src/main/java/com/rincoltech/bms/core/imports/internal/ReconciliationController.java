package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportReconciliation.Report;
import com.rincoltech.bms.core.imports.ImportReconciliation.SignOff;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/imports} (chapter 7 section 7.11.9, issue #134, FR-IMP-08): the reconciliation of a committed import and
 * the customer's sign-off in the app. Reading and signing need {@code core.imports.sign_off}, the tenant administrator's;
 * making the reconciliation again needs {@code core.imports.manage}. Every route works on the business of the host only
 * (row-level security): a run of another business is 404. No answer holds a value of a row: codes, counts and amounts.
 */
// Named: core.payments has a ReconciliationController of its own.
@RestController("importReconciliationController")
@RequestMapping(path = "/api/v1/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "imports")
class ReconciliationController {

    private final ImportReconciliation reconciliation;

    ReconciliationController(ImportReconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Schema(name = "ImportSignOffRun", description = "An imported run on the sign-off screen")
    record RunResponse(
            UUID runId,
            @Schema(description = "committed or signed_off") String status,
            Instant committedAt,

            @Schema(description = "The newest reconciliation's version, or null while none was made")
            Integer version,

            @Schema(description = "Whether every difference is explained, or null while none was made")
            Boolean signable,

            Instant signedAt) {}

    @Schema(name = "ImportSignOffRunList")
    record RunList(List<RunResponse> items) {}

    @Schema(name = "ImportReconciliationFact", description = "A figure shown and not compared")
    record FactResponse(String label, String value) {}

    @Schema(
            name = "ImportReconciliationCheck",
            description = "One check: values are exact decimal strings, money in minor units, never rounded")
    record CheckResponse(
            String key,
            String label,

            @Schema(description = "rows, qty, minor, loans or entries")
            String unit,

            String source,
            String system,
            @Schema(description = "Source less system") String difference,

            @Schema(description = "The part of the difference the items explain")
            String itemised,

            @Schema(description = "The part no item explains; anything but 0 blocks the sign-off")
            String unitemised,

            boolean ties) {}

    @Schema(name = "ImportReconciliationItem", description = "One explained difference, without any value of a row")
    record ItemResponse(
            String check,
            String file,

            @Schema(description = "The line of the file, or 0 for the run as a whole")
            int line,

            @Schema(description = "excluded, overridden or policy")
            String reason,

            String detail,
            String amount) {}

    @Schema(name = "ImportReconciliationSection")
    record SectionResponse(
            @Schema(
                    description =
                            "counts, stock_quantity, stock_value, receivables, loan_principal, arrears, ledger or review")
            String key,

            String title,
            List<FactResponse> facts,
            List<CheckResponse> checks,
            List<ItemResponse> items) {}

    @Schema(name = "ImportSignOff", description = "The sign-off: the typed name and date, the sections ticked")
    record SignedResponse(
            Instant signedAt, UUID signedBy, String signedName, LocalDate signedOn, List<String> sections) {}

    @Schema(name = "ImportReconciliation", description = "The reconciliation of an imported run (FR-IMP-08)")
    record ReconciliationResponse(
            UUID id,
            UUID runId,
            int version,
            Instant generatedAt,

            @Schema(description = "The day arrears are aged to: the cutover day")
            LocalDate asAt,

            @Schema(description = "The SHA-256 of the report's text; name it when signing")
            String sha256,

            @Schema(description = "False while any difference is not explained")
            boolean signable,

            List<SectionResponse> sections,

            @Schema(description = "The sign-off, or null before it")
            SignedResponse signed) {

        static ReconciliationResponse of(Report r) {
            return new ReconciliationResponse(
                    r.id(),
                    r.runId(),
                    r.version(),
                    r.generatedAt(),
                    r.asAt(),
                    r.sha256(),
                    r.signable(),
                    r.sections().stream()
                            .map(s -> new SectionResponse(
                                    s.key(),
                                    s.title(),
                                    s.facts().stream()
                                            .map(f -> new FactResponse(f.label(), f.value()))
                                            .toList(),
                                    s.checks().stream()
                                            .map(c -> new CheckResponse(
                                                    c.key(),
                                                    c.label(),
                                                    c.unit(),
                                                    c.source(),
                                                    c.system(),
                                                    c.difference(),
                                                    c.itemised(),
                                                    c.unitemised(),
                                                    c.ties()))
                                            .toList(),
                                    s.items().stream()
                                            .map(i -> new ItemResponse(
                                                    i.check(), i.file(), i.line(), i.reason(), i.detail(), i.amount()))
                                            .toList()))
                            .toList(),
                    r.signed() == null
                            ? null
                            : new SignedResponse(
                                    r.signed().signedAt(),
                                    r.signed().signedBy(),
                                    r.signed().signedName(),
                                    r.signed().signedOn(),
                                    r.signed().sections()));
        }
    }

    @Schema(name = "ImportSignOffRequest")
    record SignOffRequest(
            @NotNull @Schema(description = "The reconciliation read")
            UUID reconciliationId,

            @NotNull @Pattern(regexp = "[0-9a-f]{64}") @Schema(description = "Its sha256 as read")
            String sha256,

            @NotNull @Size(min = 1, max = 10) @Schema(description = "Every section key, ticked")
            List<@NotNull @Size(max = 40) String> sections,

            @NotNull @Size(max = 120) @Schema(description = "The signer's name as typed")
            String name,

            @NotNull @Schema(description = "Today's date as typed")
            LocalDate date) {}

    @GetMapping("/sign-off")
    @RequiresPermission("core.imports.sign_off")
    @Operation(
            summary = "The business's imported runs to sign off, newest first, at most 20",
            operationId = "listImportsForSignOff")
    RunList forSignOff() {
        return new RunList(reconciliation.forSignOff().stream()
                .map(r -> new RunResponse(
                        r.runId(), r.status(), r.committedAt(), r.version(), r.signable(), r.signedAt()))
                .toList());
    }

    @GetMapping("/runs/{run_id}/reconciliation")
    @RequiresPermission("core.imports.sign_off")
    @Operation(
            summary = "The newest reconciliation of an imported run, with its sign-off once signed (FR-IMP-08)",
            operationId = "getImportReconciliation")
    ReconciliationResponse latest(@PathVariable("run_id") UUID runId) {
        return ReconciliationResponse.of(reconciliation.latest(runId).orElseThrow(ApiException::notFound));
    }

    @GetMapping(path = "/runs/{run_id}/reconciliation.txt", produces = "text/plain")
    @RequiresPermission("core.imports.sign_off")
    @Operation(
            summary = "The newest reconciliation as the plain text that is signed, with the sign-off once signed",
            operationId = "downloadImportReconciliation")
    ResponseEntity<String> text(@PathVariable("run_id") UUID runId) {
        Report r = reconciliation.latest(runId).orElseThrow(ApiException::notFound);
        StringBuilder out = new StringBuilder(r.render());
        out.append("sha256: ").append(r.sha256()).append('\n');
        if (r.signed() != null) {
            // The downloaded text is the signed document, so it carries the name the signer typed (Lead on #873). The
            // name is never logged, and the audit row of the sign-off does not hold it.
            out.append("signed by: ")
                    .append(r.signed().signedName())
                    .append(" on ")
                    .append(r.signed().signedOn())
                    .append(", sections ticked: ")
                    .append(String.join(", ", r.signed().sections()))
                    .append('\n');
        }
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"import-reconciliation-" + r.version() + ".txt\"")
                .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                .body(out.toString());
    }

    @PostMapping("/runs/{run_id}/reconciliation")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Make the reconciliation of an imported run again from the source and the books; audited",
            operationId = "reconcileImport")
    ReconciliationResponse reconcile(@PathVariable("run_id") UUID runId) {
        return ReconciliationResponse.of(
                reconciliation.reconcile(runId, CurrentPrincipal.require().userId()));
    }

    @PostMapping("/runs/{run_id}/sign-off")
    @RequiresPermission("core.imports.sign_off")
    @Operation(
            summary = "Sign the reconciliation: every section ticked, the name and today's date typed; audited",
            operationId = "signOffImport")
    ReconciliationResponse signOff(@PathVariable("run_id") UUID runId, @Valid @RequestBody SignOffRequest request) {
        return ReconciliationResponse.of(reconciliation.signOff(
                runId,
                new SignOff(
                        request.reconciliationId(),
                        request.sha256(),
                        request.sections(),
                        request.name(),
                        request.date(),
                        CurrentPrincipal.require().userId())));
    }
}
