package com.rincoltech.bms.core.approvals;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The 202 body of a route whose action now waits for a second person (ADR-091 decision 5): nothing changed yet, and the request is
 * on Approvals, where its maker can see or cancel it.
 */
@Schema(name = "ApprovalWaiting")
public record ApprovalWaiting(
        @Schema(description = "The pending approval request", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID approvalId,

        @Schema(description = "The action type, as on the request", requiredMode = Schema.RequiredMode.REQUIRED)
        String actionType,

        @Schema(description = "The amount the checker sees, in minor units")
        Long amountMinor,

        String currency) {}
