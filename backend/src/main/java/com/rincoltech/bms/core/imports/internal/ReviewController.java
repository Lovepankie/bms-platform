package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Bulk;
import com.rincoltech.bms.core.imports.ImportReview.BulkOutcome;
import com.rincoltech.bms.core.imports.ImportReview.Filter;
import com.rincoltech.bms.core.imports.ImportReview.Outcome;
import com.rincoltech.bms.core.imports.ImportReview.Page;
import com.rincoltech.bms.core.imports.ImportReview.Question;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/imports} (chapter 7 section 7.11.9, issue #131, FR-IMP-05): the review queue of an import run, for every
 * template. Every route needs {@code core.imports.manage} and works on the business of the host only (row-level security):
 * a run or issue of another business is 404. No answer holds a cell's value, a value before or after a resolution, or an
 * operator's note; those stay in the quarantine, for the review screen of a holder of {@code core.imports.view_raw} (#141).
 */
@RestController
@RequestMapping(path = "/api/v1/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "imports")
class ReviewController {

    private final ImportReview review;

    ReviewController(ImportReview review) {
        this.review = review;
    }

    @Schema(
            name = "ImportIssue",
            description = "One issue of the review queue; its message never holds a value of a row")
    record IssueResponse(
            UUID id,
            UUID batchId,
            UUID rowId,
            @Schema(description = "The line of the file") int line,
            String code,

            @Schema(description = "info, warning or blocking")
            String severity,

            @Schema(description = "The template's field, or null for the whole row")
            String field,

            String message,
            @Schema(description = "open or resolved") String status,

            @Schema(description = "Name it when resolving; another resolver's version is version_conflict")
            int version,

            boolean askCustomer,

            @Schema(
                    description = "The engine's suggestion, never personal: {\"value\": \"1501\"}, or"
                            + " {\"review\": \"as_uploaded\"} for a row that changed")
            Map<String, Object> suggestion,

            UUID resolutionId,

            @Schema(description = "The kind of the resolution that resolved it")
            String resolution) {

        static IssueResponse of(Issue i) {
            return new IssueResponse(
                    i.id(),
                    i.batchId(),
                    i.rowId(),
                    i.line(),
                    i.code(),
                    i.severity(),
                    i.field(),
                    i.message(),
                    i.status(),
                    i.version(),
                    i.askCustomer(),
                    i.suggestion(),
                    i.resolutionId(),
                    i.resolution());
        }
    }

    @Schema(name = "ImportIssuePage")
    record IssuePage(List<IssueResponse> items, String nextCursor) {}

    @Schema(name = "ImportResolveRequest")
    record ResolveRequest(
            @NotNull
            @Schema(
                    description = "edit_value, exclude_row, accept_suggestion, link_row, link_member, override or"
                            + " map_value")
            String resolution,

            @NotNull @Positive @Schema(description = "The issue's version as read")
            Integer version,

            @Size(max = 32767)
            @Schema(
                    description = "edit_value: the text in place of the cell (empty leaves the field empty);"
                            + " map_value: the value the cell stands for")
            String value,

            @Schema(description = "link_row: another row of the run")
            UUID linkRowId,

            @Schema(description = "link_member: a record the business holds")
            UUID linkMemberId,

            @Size(max = 500) @Schema(description = "Required for override and exclude_row")
            String note) {}

    @Schema(name = "ImportResolveOutcome")
    record OutcomeResponse(
            UUID resolutionId,
            String resolution,
            @Schema(description = "Rows validated again") int rowsValidated,

            @Schema(description = "The mapping version a map_value made")
            Integer mappingVersion,

            @Schema(description = "The issues of the row as they now are")
            List<IssueResponse> issues) {}

    @Schema(name = "ImportBulkResolveRequest")
    record BulkRequest(
            @NotNull @Size(min = 2, max = 60) String code,

            @Schema(description = "One batch of the run, or every batch")
            UUID batchId,

            @NotNull @Schema(description = "edit_value, exclude_row, accept_suggestion, override or ask_customer")
            String resolution,

            @Size(max = 32767) String value,
            @Size(max = 500) String note) {}

    @Schema(name = "ImportBulkResolveOutcome")
    record BulkResponse(UUID bulkId, int resolved, int rowsValidated) {}

    @Schema(name = "ImportAskCustomerRequest")
    record AskRequest(
            @NotNull Boolean askCustomer, @NotNull @Positive Integer version) {}

    @Schema(name = "ImportQuestion", description = "One question for the customer, without any value of a row")
    record QuestionResponse(int number, String file, int line, String field, String code, String message) {

        static QuestionResponse of(Question q) {
            return new QuestionResponse(q.number(), q.file(), q.line(), q.field(), q.code(), q.message());
        }
    }

    @Schema(name = "ImportQuestionList")
    record QuestionList(List<QuestionResponse> items) {}

    @GetMapping("/runs/{run_id}/issues")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "The review queue of an import run, in commit order, open issues by default (FR-IMP-05)",
            operationId = "listImportIssues")
    IssuePage queue(
            @PathVariable("run_id") UUID runId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "batch_id", required = false) UUID batchId,
            @RequestParam(name = "ask_customer", required = false) Boolean askCustomer,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Page page = review.queue(runId, new Filter(status, code, batchId, askCustomer), limit, cursor);
        return new IssuePage(page.items().stream().map(IssueResponse::of).toList(), page.nextCursor());
    }

    @PostMapping("/issues/{issue_id}/resolve")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary =
                    "Resolve one issue: edit, leave the row out, accept the suggestion, link, override a warning or map"
                            + " the value (FR-IMP-05)",
            operationId = "resolveImportIssue")
    OutcomeResponse resolve(@PathVariable("issue_id") UUID issueId, @Valid @RequestBody ResolveRequest request) {
        Outcome o = review.resolve(
                issueId,
                new Resolution(
                        request.resolution(),
                        request.version(),
                        request.value(),
                        request.linkRowId(),
                        request.linkMemberId(),
                        request.note()),
                CurrentPrincipal.require().userId());
        return new OutcomeResponse(
                o.resolutionId(),
                o.resolution(),
                o.rowsValidated(),
                o.mappingVersion(),
                o.issues().stream().map(IssueResponse::of).toList());
    }

    @PostMapping("/runs/{run_id}/issues/bulk-resolve")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary =
                    "Resolve every open issue of one code in the run, or in one of its files, the same way (FR-IMP-05)",
            operationId = "bulkResolveImportIssues")
    BulkResponse bulk(@PathVariable("run_id") UUID runId, @Valid @RequestBody BulkRequest request) {
        BulkOutcome o = review.resolveAll(
                runId,
                new Bulk(request.code(), request.batchId(), request.resolution(), request.value(), request.note()),
                CurrentPrincipal.require().userId());
        return new BulkResponse(o.bulkId(), o.resolved(), o.rowsValidated());
    }

    @PutMapping("/issues/{issue_id}/ask-customer")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Mark an issue as a question for the customer, or no longer one",
            operationId = "askCustomerAboutImportIssue")
    IssueResponse ask(@PathVariable("issue_id") UUID issueId, @Valid @RequestBody AskRequest request) {
        return IssueResponse.of(review.askCustomer(
                issueId,
                request.version(),
                request.askCustomer(),
                CurrentPrincipal.require().userId()));
    }

    @GetMapping("/runs/{run_id}/questions")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "The numbered questions for the customer, with file and line and no value of a row",
            operationId = "listImportQuestions")
    QuestionList questions(@PathVariable("run_id") UUID runId) {
        return new QuestionList(review.questions(runId, false).stream()
                .map(QuestionResponse::of)
                .toList());
    }

    @GetMapping(path = "/runs/{run_id}/questions.csv", produces = "text/csv")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "The same questions as a CSV file to send to the customer; audited",
            operationId = "exportImportQuestions")
    ResponseEntity<String> questionsCsv(@PathVariable("run_id") UUID runId) {
        StringBuilder csv = new StringBuilder("number,file,line,field,code,question\n");
        for (Question q : review.questions(runId, true)) {
            csv.append(q.number())
                    .append(',')
                    .append(cell(q.file()))
                    .append(',')
                    .append(q.line())
                    .append(',')
                    .append(cell(q.field()))
                    .append(',')
                    .append(cell(q.code()))
                    .append(',')
                    .append(cell(q.message()))
                    .append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-questions.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString());
    }

    /** One CSV cell, quoted, with a leading formula character made plain so a spreadsheet never runs it. */
    static String cell(String s) {
        if (s == null) {
            return "";
        }
        String v = !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
