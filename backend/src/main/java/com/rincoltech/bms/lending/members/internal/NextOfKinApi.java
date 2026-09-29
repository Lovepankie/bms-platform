package com.rincoltech.bms.lending.members.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the next of kin and relationship routes (chapter 7 section
 * 7.11.11; FR-MEM-06 to FR-MEM-08). Field names are snake_case on the wire.
 */
final class NextOfKinApi {

    static final String RELATIONSHIPS = "spouse|parent|child|sibling|relative|friend|employer|other";

    private NextOfKinApi() {}

    @Schema(name = "CreateNextOfKinRequest")
    record CreateNextOfKinRequest(
            @NotBlank @Size(max = 200) String fullName,

            @Schema(description = "Any common Uganda form; stored as E.164")
            String phone,

            String nationalId,
            @NotNull @Pattern(regexp = RELATIONSHIPS) String relationship,

            @Size(max = 60) @Schema(description = "The original wording, for example from the import")
            String relationshipText,

            @Size(max = 200) String location,
            Boolean isPrimary) {}

    @Schema(name = "UpdateNextOfKinRequest", description = "Omitted fields are unchanged")
    record UpdateNextOfKinRequest(
            @Size(min = 1, max = 200) String fullName,
            String phone,
            String nationalId,
            @Pattern(regexp = RELATIONSHIPS) String relationship,
            @Size(max = 60) String relationshipText,
            @Size(max = 200) String location,
            Boolean isPrimary) {}

    @Schema(name = "NextOfKinLinkDecision")
    record LinkDecisionRequest(
            @NotNull @Pattern(regexp = "confirm|reject") String decision) {}

    @Schema(name = "NextOfKin")
    record NextOfKinResponse(
            UUID id,
            UUID memberId,
            String fullName,
            String phoneE164,
            String nationalId,
            String relationship,
            String relationshipText,
            String location,
            boolean isPrimary,
            UUID linkedMemberId,
            String linkedMemberNo,

            @Schema(description = "nin, phone or manual; null when not linked")
            String linkMethod,

            @Schema(description = "none, suggested, confirmed or rejected (FR-MEM-07)")
            String linkStatus,

            Instant createdAt,
            Instant updatedAt,
            int version) {}

    @Schema(name = "NextOfKinList")
    record NextOfKinList(List<NextOfKinResponse> items) {}

    /**
     * A member who names this member as next of kin. One outside the caller's read scope shows
     * only the member number.
     */
    @Schema(name = "RelatedMember")
    record RelatedMember(UUID id, String memberNo, String fullName, String relationship, boolean inScope) {}

    /**
     * FR-MEM-08. Guarantee edges and each party's outstanding balance and days past due join
     * when loans exist (increment 4).
     */
    @Schema(name = "MemberRelationships")
    record Relationships(UUID memberId, List<NextOfKinResponse> namesAsKin, List<RelatedMember> namedAsKinBy) {}
}
