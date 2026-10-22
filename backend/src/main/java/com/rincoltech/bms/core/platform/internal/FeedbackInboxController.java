package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.feedback.FeedbackInbox;
import com.rincoltech.bms.core.feedback.FeedbackInbox.Item;
import com.rincoltech.bms.core.feedback.FeedbackInbox.Screenshot;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.kernel.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/feedback} (chapter 7 section 7.11.3, issue #447, ADR-065): the feedback every business sent, for
 * platform operators only. The list and the status change read across businesses through the definer functions of V80 with
 * no tenant bound; a screenshot is read with its business bound through {@link TenantContext#callAs}, as every other act of
 * the console inside one tenant.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/feedback", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class FeedbackInboxController {

    private final FeedbackInbox inbox;

    FeedbackInboxController(FeedbackInbox inbox) {
        this.inbox = inbox;
    }

    @Schema(name = "PlatformFeedbackList")
    record FeedbackList(List<Item> items) {}

    @Schema(name = "PlatformFeedbackStatusRequest")
    record StatusRequest(
            @NotNull @Pattern(regexp = "new|seen|done") String status,

            @Size(max = 2000)
            @Schema(description = "A reply the sender sees; null keeps the one there, an empty string clears it")
            String replyNote) {}

    @Schema(name = "PlatformFeedbackLinkedIssueRequest")
    record LinkedIssueRequest(
            @Positive @Schema(description = "The number of the issue opened for this feedback; null clears it")
            Integer linkedIssueNumber) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary =
                    "Feedback from every business, newest first, optionally one status (new, seen, done) and only what"
                            + " was sent after since",
            operationId = "platformListFeedback")
    FeedbackList list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant since,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return new FeedbackList(inbox.list(
                status == null || status.isBlank() ? null : status,
                since,
                limit == null ? 50 : Math.clamp(limit, 1, 200),
                offset == null ? 0 : Math.max(offset, 0)));
    }

    @PutMapping("/{feedback_id}/status")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Move one feedback item forward (new, seen, done) with an optional reply the sender sees; 422"
                    + " feedback_status_backwards for a move back",
            operationId = "platformSetFeedbackStatus")
    Item setStatus(@PathVariable("feedback_id") UUID id, @Valid @RequestBody StatusRequest request) {
        return inbox.setStatus(
                id,
                request.status(),
                request.replyNote(),
                CurrentPrincipal.require().userId());
    }

    @PutMapping("/{feedback_id}/linked-issue")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Set or clear the number of the issue opened for one feedback item",
            operationId = "platformSetFeedbackLinkedIssue")
    Item setLinkedIssue(@PathVariable("feedback_id") UUID id, @Valid @RequestBody LinkedIssueRequest request) {
        return inbox.setLinkedIssue(
                id, request.linkedIssueNumber(), CurrentPrincipal.require().userId());
    }

    @GetMapping(path = "/{feedback_id}/screenshot", produces = MediaType.ALL_VALUE)
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "The screenshot sent with one feedback item", operationId = "platformGetFeedbackScreenshot")
    ResponseEntity<byte[]> screenshot(@PathVariable("feedback_id") UUID id) {
        UUID tenant = inbox.tenantOf(id).orElseThrow(ApiException::notFound);
        Screenshot image = TenantContext.callAs(tenant, () -> inbox.screenshot(id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))
                        .cachePrivate()
                        .mustRevalidate())
                .varyBy("Cookie", "Authorization")
                .eTag(image.sha256())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
