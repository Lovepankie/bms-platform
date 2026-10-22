package com.rincoltech.bms.core.feedback;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The platform operator's side of in-app feedback (issue #447, ADR-065), for {@code core.platform}. The list and the
 * status change run with no tenant bound, through the SECURITY DEFINER functions of migration V80; the screenshot is read
 * with the item's tenant bound ({@link #tenantOf} first, then {@link #screenshot} inside that tenant).
 */
public interface FeedbackInbox {

    /**
     * Newest first across every business, optionally one status ({@code new}, {@code seen}, {@code done}) and only what was
     * sent after {@code since} (null for all).
     */
    List<Item> list(String status, Instant since, int limit, int offset);

    /**
     * Moves one item forward and sets its reply (null keeps the reply there, blank clears it), audited in the platform log
     * with the operator. 404 when there is no such item, 422 {@code feedback_status_backwards} for a move back.
     */
    Item setStatus(UUID id, String status, String replyNote, UUID operatorId);

    /**
     * Sets the number of the issue the operator opened for the item, or clears it with null, audited in the platform log
     * with the operator. 404 when there is no such item.
     */
    Item setLinkedIssue(UUID id, Integer issueNumber, UUID operatorId);

    /** The business the item belongs to, read with no tenant bound. */
    Optional<UUID> tenantOf(UUID id);

    /** The item's screenshot, read with its tenant bound; 404 when it has none. */
    Screenshot screenshot(UUID id);

    @Schema(name = "PlatformFeedbackItem")
    record Item(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantName,
            String senderName,
            String kind,
            String message,
            String pagePath,
            String appVersion,
            String userAgent,
            boolean hasScreenshot,
            String status,
            String replyNote,
            Instant statusChangedAt,
            Integer linkedIssueNumber,
            Instant createdAt) {}

    record Screenshot(byte[] bytes, String contentType, String sha256) {}
}
