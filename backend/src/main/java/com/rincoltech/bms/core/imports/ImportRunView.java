package com.rincoltech.bms.core.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * An import run as the screens show it (issue #141): the business's own routes and the operator portal's answer this one shape,
 * so both say the same about a run. It holds no value of a row and no person's id.
 */
@Schema(name = "ImportRun", description = "An import run and where it stands")
public record ImportRunView(
        UUID id,

        @Schema(
                description = "draft, uploaded, in_review, ready, committing, committed, signed_off, purged or"
                        + " cancelled")
        String status,

        int version,
        Instant cutoverAt,

        @Schema(description = "The stop button was pressed; the commit stops before the next batch")
        boolean haltRequested,

        @Schema(description = "A platform operator allowed this run into a business that already trades")
        boolean tradingOverrideSet,

        @Schema(description = "A second platform operator approved that override")
        boolean tradingOverrideApproved,

        @Schema(description = "The file name of the pre-import dump recorded, or null")
        String preImportDumpFile,

        boolean preImportDumpVerified,

        @Schema(description = "import_commit is approved for the run as it is now")
        boolean commitApproved,

        Instant committingAt,
        Instant committedAt,
        boolean fabricated) {

    public static ImportRunView of(ImportRuns.Run r) {
        return new ImportRunView(
                r.id(),
                r.status(),
                r.version(),
                r.cutoverAt(),
                r.haltRequested(),
                r.allowTradingTenant(),
                r.tradingOverrideApproved(),
                r.preImportDumpFile(),
                r.preImportDumpVerified(),
                r.commitApproved(),
                r.committingAt(),
                r.committedAt(),
                r.fabricated());
    }
}
