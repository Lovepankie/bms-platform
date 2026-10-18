package com.rincoltech.bms.lending.advisor.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The bodies of {@code /api/v1/lending/advisor} (chapter 7 section 7.11.24). */
final class AdvisorApi {

    private AdvisorApi() {}

    @Schema(
            name = "AdvisorPoint",
            description = "One point of the brief, written by our code from the reviewed catalogue")
    record PointView(String text, List<String> facts) {}

    @Schema(
            name = "AdvisorFact",
            description =
                    "A figure the brief rests on; metric is the insights metric that opens its drill-down, or null")
    record ChipView(String key, String label, String display, String metric) {}

    @Schema(name = "AdvisorBrief")
    record BriefView(
            @Schema(description = "Always true: every text here is advisory")
            boolean advisory,

            @Schema(
                    description =
                            "model: rendered from an accepted structured model answer. fallback: the automatic summary")
            String source,

            @Schema(
                    description =
                            "Why the automatic summary is shown: off, scope, validation, provider_down, cap, not_ready or unavailable; null for a model brief")
            String reason,

            @Schema(description = "The label the card carries: AI assistant (advisory) or Automatic summary")
            String label,

            @Schema(description = "When the brief was made (a stored brief) or computed (the caller's own summary)")
            Instant asOf,

            @Schema(description = "The business date the figures stand at")
            LocalDate factsAsOf,

            String summary,
            List<PointView> points,
            List<ChipView> facts,

            @Schema(description = "The line shown under the text")
            String note) {}

    @Schema(name = "AdvisorAcknowledgement", description = "The text to show before switching on, and its version")
    record Acknowledgement(String textVersion, String text) {}

    @Schema(name = "AdvisorSettings")
    record SettingsView(
            boolean enabled,

            @Schema(description = "The tenant is named in BMS_AI_ENABLED_TENANTS; switching on is refused otherwise")
            boolean allowed,

            @Schema(
                    description =
                            "A provider is configured on the platform; false means every brief is the automatic summary")
            boolean providerConfigured,

            long dailyTokenCap,
            long dailyCostCapMicro,
            String costCurrency,
            long tokensUsedToday,
            long costUsedTodayMicro,
            UUID acknowledgedBy,
            Instant acknowledgedAt,
            String acknowledgedTextVersion,
            Acknowledgement acknowledgement,
            int version) {}
}
