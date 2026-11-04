package com.rincoltech.bms.retail.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.UUID;

/**
 * Where an entry was made (issue #826, ADR-122): the phone's location and its accuracy, kept beside a sale or a usage or
 * damage entry so the owner can see how far from the shop it was made. Optional on every entry: an entry without one is
 * recorded exactly as before and reads "no location". The coordinates are never logged, audited or returned by the API.
 */
public interface EntryLocations {

    /** The audit entity type of a sale. */
    String SALE = "retail.sale";

    /** The audit entity type of a usage or damage entry. */
    String USAGE = "retail.usage";

    /** The largest accuracy kept, in metres; a phone that knows less than this knows nothing useful. */
    double MAX_ACCURACY_M = 1_000_000;

    @Schema(
            name = "RetailDeviceLocation",
            description =
                    "ADR-122: where the phone was when the entry was made, as the phone reported it. Optional; never"
                            + " returned, only the distance from the shop to holders of retail.entry_location.read")
    record Fix(
            @DecimalMin("-90") @DecimalMax("90") @Schema(description = "Degrees north", example = "0.3136")
            Double latitude,

            @DecimalMin("-180") @DecimalMax("180") @Schema(description = "Degrees east", example = "32.5811")
            Double longitude,

            @DecimalMin("0")
            @DecimalMax("1000000")
            @Schema(description = "Metres, as the phone reports it", example = "15")
            Double accuracyM) {

        /** All three values are there; an entry with an incomplete location is recorded as having none, never refused. */
        public boolean complete() {
            return latitude != null && longitude != null && accuracyM != null;
        }

        /** Complete and in range: the same checks as the annotations, for a caller that did not validate the body. */
        public boolean valid() {
            return complete()
                    && Double.isFinite(latitude)
                    && Double.isFinite(longitude)
                    && Double.isFinite(accuracyM)
                    && latitude >= -90
                    && latitude <= 90
                    && longitude >= -180
                    && longitude <= 180
                    && accuracyM >= 0
                    && accuracyM <= MAX_ACCURACY_M;
        }
    }

    /**
     * Keeps where an entry was made, in the caller's transaction. Does nothing when {@code fix} is null or incomplete (the
     * entry has no location). A complete fix out of range is refused with 422 {@code invalid_location}; a body validated at
     * the controller never gets here.
     */
    void record(String source, UUID sourceId, UUID branchId, Fix fix);
}
