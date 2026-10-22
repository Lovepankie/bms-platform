package com.rincoltech.bms.core.feedback.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.feedback.internal.FeedbackApi.FeedbackItem;
import com.rincoltech.bms.core.feedback.internal.FeedbackApi.FeedbackList;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/v1/feedback} (chapter 7 section 7.11.25, issue #447, ADR-065): any signed-in staff user sends feedback and
 * reads their own. No matrix permission: sending feedback is open to every member of staff, like {@code /me}.
 */
@RestController
@RequestMapping(path = "/api/v1/feedback", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "feedback")
class FeedbackController {

    private final FeedbackService service;

    FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Send an idea, a problem or a question to the platform operator, with an optional PNG or JPEG"
                    + " screenshot of at most 2 MB; at most 10 an hour per person (429 feedback_rate_limited)",
            operationId = "sendFeedback")
    ResponseEntity<FeedbackItem> send(
            @RequestParam("kind") String kind,
            @RequestParam("message") String message,
            @RequestParam(name = "page_path", required = false) String pagePath,
            @RequestPart(name = "screenshot", required = false) MultipartFile screenshot,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        FeedbackItem sent = service.send(new FeedbackService.Send(
                kind, message, pagePath, userAgent, screenshot == null ? null : bytes(screenshot)));
        return ResponseEntity.created(URI.create("/api/v1/feedback/" + sent.id()))
                .body(sent);
    }

    @GetMapping
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "The caller's own feedback, newest first, with its status and the reply",
            operationId = "listMyFeedback")
    FeedbackList mine() {
        return service.mine();
    }

    @GetMapping(path = "/{feedback_id}/screenshot", produces = MediaType.ALL_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "The screenshot of one of the caller's own feedback items",
            operationId = "getMyFeedbackScreenshot")
    ResponseEntity<byte[]> screenshot(@PathVariable("feedback_id") UUID id) {
        Documents.Image image = service.ownScreenshot(id);
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

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
    }
}
