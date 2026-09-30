package com.rincoltech.bms.lending.collateral.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of {@code /api/v1/lending/collateral} (chapter 7 section 7.11.14). */
final class CollateralApi {

    static final String TYPES = "land_title|vehicle_logbook|vehicle|national_id|household_item|other";
    static final String OWNER_RELATIONSHIPS = "self|spouse|other";

    private CollateralApi() {}

    @Schema(name = "CreateCollateralRequest")
    record CreateCollateralRequest(
            @NotNull UUID memberId,
            @NotNull @Pattern(regexp = TYPES) String collateralType,
            @NotBlank @Size(max = 300) String description,

            @Size(max = 60) @Schema(description = "Title, logbook, plate or ID number as written; required for vehicle")
            String referenceNo,

            @Size(max = 200) String ownerName,
            @Pattern(regexp = OWNER_RELATIONSHIPS) String ownerRelationship,
            @PositiveOrZero Long estimatedValueMinor,

            @Pattern(regexp = "pledged|in_custody") @Schema(description = "Default pledged (the owner keeps it)")
            String custodyStatus,

            @Size(max = 200) @Schema(description = "Required when in_custody")
            String storageLocation) {}

    @Schema(name = "UpdateCollateralRequest", description = "Omitted fields are unchanged")
    record UpdateCollateralRequest(
            @Size(min = 1, max = 300) String description,
            @Size(max = 60) String referenceNo,
            @Size(max = 200) String ownerName,
            @Pattern(regexp = OWNER_RELATIONSHIPS) String ownerRelationship,
            @PositiveOrZero Long estimatedValueMinor) {}

    @Schema(name = "CollateralValuationRequest")
    record ValuationRequest(
            @NotNull @Schema(description = "Not after today (business date)")
            LocalDate valuedOn,

            @Size(max = 200) String valuerName,
            @NotNull @Positive Long marketValueMinor,

            @Positive @Schema(description = "At most the market value")
            Long forcedSaleValueMinor,

            @Size(max = 1000) String note) {}

    @Schema(name = "CollateralEventRequest")
    record EventRequest(
            @NotNull @Pattern(regexp = "received_into_custody|moved|seized|disposed|note")
            String eventType,

            @Size(max = 200) @Schema(description = "Required for received_into_custody and moved")
            String location,

            @Size(max = 200) String counterpartyName,

            @Size(max = 1000) @Schema(description = "Required for note")
            String note,

            @Schema(description = "Defaults to now; not in the future")
            Instant occurredAt) {}

    @Schema(name = "CollateralReleaseRequest")
    record ReleaseRequest(
            @NotBlank @Size(max = 200) @Schema(description = "Who collects the item or document")
            String collectedBy,

            @Size(max = 1000) String note) {}

    @Schema(name = "Collateral")
    record CollateralResponse(
            UUID id,
            UUID branchId,
            UUID memberId,
            String collateralType,
            String description,
            String referenceNo,
            String referenceNoNormalised,
            String ownerName,
            String ownerRelationship,
            Long estimatedValueMinor,
            String currency,
            String custodyStatus,
            String storageLocation,

            @Schema(description = "FR-COL-02: the latest valuation's forced sale value, else the estimate")
            Long collateralValueMinor,

            Instant createdAt,
            Instant updatedAt,
            int version) {}

    @Schema(name = "CollateralValuation")
    record Valuation(
            UUID id,
            LocalDate valuedOn,
            String valuerName,
            long marketValueMinor,
            Long forcedSaleValueMinor,
            String note,
            UUID recordedBy,
            Instant createdAt) {}

    @Schema(name = "CollateralEvent")
    record Event(
            UUID id,
            String eventType,
            String fromStatus,
            String toStatus,
            String location,
            String counterpartyName,
            String note,
            Instant occurredAt,
            UUID recordedBy,
            UUID approvalRequestId) {}

    @Schema(name = "CollateralDetail")
    record CollateralDetail(CollateralResponse item, List<Valuation> valuations, List<Event> events) {}

    @Schema(name = "CollateralPage")
    record CollateralPage(List<CollateralResponse> items, String nextCursor) {}

    @Schema(name = "CollateralReleaseOutcome")
    record ReleaseOutcome(boolean executed, UUID approvalId) {}
}
