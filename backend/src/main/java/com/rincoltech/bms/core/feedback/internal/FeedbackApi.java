package com.rincoltech.bms.core.feedback.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of {@code /api/v1/feedback} (chapter 7 section 7.11.25). Field names are snake_case on the wire (global
 * Jackson naming strategy). The request is a multipart form (the screenshot is a file), so its fields are parameters of
 * {@link FeedbackController#send}.
 */
final class FeedbackApi {

    private FeedbackApi() {}

    @Schema(name = "FeedbackItem")
    record FeedbackItem(
            UUID id,

            @Schema(description = "idea, problem or question")
            String kind,

            String message,

            @Schema(description = "The path of the page it was sent from, or null")
            String pagePath,

            @Schema(description = "The version of BMS that was running (version and git SHA)")
            String appVersion,

            boolean hasScreenshot,

            @Schema(description = "new, seen or done") String status,

            @Schema(description = "The platform operator's reply, or null")
            String replyNote,

            Instant statusChangedAt,
            Instant createdAt) {}

    @Schema(name = "FeedbackList")
    record FeedbackList(List<FeedbackItem> items) {}
}
