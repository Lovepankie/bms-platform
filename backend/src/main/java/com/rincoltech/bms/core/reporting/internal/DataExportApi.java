package com.rincoltech.bms.core.reporting.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** The request and response shapes of the data export routes (chapter 7 section 7.11.8, ADR-074). */
final class DataExportApi {

    private DataExportApi() {}

    @Schema(name = "DataExportStatus")
    record Status(
            @Schema(description = "Whether the Owner is emailed a copy every week (on until switched off)")
            boolean weeklyEmail,

            @Schema(description = "When a workbook of the business's data was last made, by anyone; null when never")
            Instant lastExportAt,

            @Schema(description = "When the weekly email last went to the Owner; null when never")
            Instant lastEmailedAt,

            @Schema(description = "The caller's newest workbook, in any state; null when they never asked for one")
            ReportsApi.RunView latest) {}

    @Schema(name = "DataExportSettingsRequest")
    record SettingsRequest(
            @NotNull @Schema(description = "Email the Owner a copy every week")
            Boolean weeklyEmail) {}
}
