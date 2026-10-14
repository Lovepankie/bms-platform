package com.rincoltech.bms.core.reporting.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The request and response shapes of the report routes (chapter 7 section 7.11.8). */
final class ReportsApi {

    private ReportsApi() {}

    @Schema(name = "ReportCatalogueEntry")
    record CatalogueEntry(
            String key,
            String title,
            String description,

            @Schema(description = "The permission that lets a user run it")
            String permission,

            @Schema(description = "json or csv until the PDF and Excel slices")
            List<String> formats,

            List<CatalogueParam> params) {}

    @Schema(name = "ReportCatalogueParam")
    record CatalogueParam(
            String name,

            @Schema(description = "date, uuid_list, enum, integer or text")
            String type,

            boolean required,
            String description,

            @Schema(description = "The allowed values of an enum")
            List<String> values) {}

    @Schema(name = "ReportRunRequest")
    record RunRequest(
            @Schema(description = "A key from the catalogue, for example lending.par")
            String reportKey,

            @Schema(
                    description =
                            "The report's parameters; omitted ones take their defaults and are echoed on the output")
            Map<String, Object> params,

            @Schema(description = "json or csv") String format) {}

    /**
     * A run. Errors are codes ({@code report_too_large}, {@code snapshot_unavailable}, {@code timeout},
     * {@code interrupted}, {@code reports_disabled}, {@code requester_deactivated}, {@code run_failed}); never a
     * message that could carry data. A file is fetched with {@code POST /documents/{document_id}/download-url}.
     */
    @Schema(name = "ReportRun")
    record RunView(
            UUID id,
            String reportKey,
            String title,
            String format,

            @Schema(description = "queued, running, ready, failed or cancelled")
            String status,

            @Schema(description = "The normalised parameters, as printed on the output")
            Map<String, Object> params,

            LocalDate asOf,

            @Schema(description = "When the data was read (the snapshot of the query phase)")
            Instant snapshotAt,

            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Integer rowCount,
            Long sizeBytes,
            UUID documentId,

            @Schema(description = "When the file is deleted")
            Instant expiresAt,

            @Schema(description = "True once the file is gone; a download then answers 410 report_expired")
            boolean expired,

            @Schema(description = "A running run that has been asked to stop")
            boolean cancelRequested,

            String error) {}
}
