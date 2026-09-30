package com.rincoltech.bms.lending.members.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of {@code /api/v1/lending/members} (chapter 7 section 7.11.11).
 * Field names are snake_case on the wire (global Jackson naming strategy).
 */
final class MemberApi {

    private MemberApi() {}

    @Schema(name = "CreateMemberRequest")
    record CreateMemberRequest(
            @NotNull UUID branchId,
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,

            @NotBlank @Schema(description = "Any common Uganda form; stored as E.164 (FR-MEM-02)")
            String phone,

            String altPhone,

            @NotNull @Pattern(regexp = "nin|passport|refugee_id|other|none")
            String idType,

            @Schema(description = "Required when id_type is nin (FR-MEM-03)")
            String nationalId,

            @Size(max = 40) String otherIdNumber,
            @Past LocalDate dateOfBirth,

            @Pattern(regexp = "female|male|other|unspecified")
            String gender,

            @Pattern(regexp = "single|married|divorced|widowed|separated|unknown")
            String maritalStatus,

            @Size(max = 100) String district,
            @Size(max = 100) String subCounty,
            @Size(max = 100) String village,
            @Size(max = 200) String location,
            @Size(max = 120) String occupation,
            @Size(max = 200) String otherIncomeSource,
            @PositiveOrZero Long monthlyIncomeMinor,

            @Pattern(regexp = "^[A-Z]{3}$") @Schema(description = "Defaults to the tenant currency")
            String currency,

            UUID officerUserId,

            @Schema(
                    description =
                            "True after the user confirmed that a member with the same phone is a different person (FR-MEM-04)")
            Boolean confirmedNotDuplicate) {}

    /** The branch is not editable here: moving a member is a transfer (FR-BR-06). */
    @Schema(name = "UpdateMemberRequest", description = "Omitted fields are unchanged")
    record UpdateMemberRequest(
            @Size(min = 1, max = 200) String fullName,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            String phone,
            String altPhone,

            @Pattern(regexp = "nin|passport|refugee_id|other|none")
            String idType,

            String nationalId,
            @Size(max = 40) String otherIdNumber,
            @Past LocalDate dateOfBirth,

            @Pattern(regexp = "female|male|other|unspecified")
            String gender,

            @Pattern(regexp = "single|married|divorced|widowed|separated|unknown")
            String maritalStatus,

            @Size(max = 100) String district,
            @Size(max = 100) String subCounty,
            @Size(max = 100) String village,
            @Size(max = 200) String location,
            @Size(max = 120) String occupation,
            @Size(max = 200) String otherIncomeSource,
            @PositiveOrZero Long monthlyIncomeMinor,
            UUID officerUserId,

            @Pattern(regexp = "active|inactive|exited") @Schema(description = "FR-MEM-10")
            String status,

            @Schema(description = "As on create, when the new phone belongs to another member (FR-MEM-04)")
            Boolean confirmedNotDuplicate) {}

    @Schema(name = "DuplicateCheckRequest", description = "At least one field is required (FR-MEM-04)")
    record DuplicateCheckRequest(@Size(max = 200) String fullName, String phone, String nationalId) {}

    /**
     * A likely duplicate. One outside the caller's branch scope shows only its member number and
     * why it matched, the same disclosure as {@code duplicate_nin}.
     */
    @Schema(name = "DuplicateCandidate")
    record DuplicateCandidate(
            UUID id,
            String memberNo,
            String fullName,
            UUID branchId,
            String phoneE164Masked,
            String nationalIdMasked,
            @Schema(description = "Any of nin, phone, name") List<String> matchReasons,
            boolean inScope) {}

    @Schema(name = "DuplicateCheckResponse")
    record DuplicateCheckResponse(List<DuplicateCandidate> candidates) {}

    @Schema(name = "KycDecisionRequest")
    record KycDecisionRequest(
            @NotNull @Pattern(regexp = "verified|rejected") String decision,

            @Size(max = 500) @Schema(description = "Required when rejected")
            String note) {}

    @Schema(name = "BlacklistRequest")
    record BlacklistRequest(
            @NotNull Boolean isBlacklisted,

            @Size(max = 500) @Schema(description = "Required when blacklisting (FR-MEM-13)")
            String reason) {}

    @Schema(name = "Member")
    record MemberResponse(
            UUID id,
            UUID branchId,
            String memberNo,
            String fullName,
            String firstName,
            String lastName,
            String phoneE164,
            String altPhoneE164,
            String idType,
            String nationalId,
            String otherIdNumber,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String district,
            String subCounty,
            String village,
            String location,
            String occupation,
            String otherIncomeSource,
            Long monthlyIncomeMinor,
            String currency,
            String kycStatus,
            UUID kycVerifiedBy,
            Instant kycVerifiedAt,
            String status,
            boolean isBlacklisted,
            String blacklistReason,
            UUID officerUserId,
            String source,
            Instant createdAt,
            Instant updatedAt,
            int version) {}

    /** List rows mask phone and NIN to the last 4 characters (chapter 7 section 7.5). */
    @Schema(name = "MemberListItem")
    record MemberListItem(
            UUID id,
            UUID branchId,
            String memberNo,
            String fullName,
            String phoneE164Masked,
            String nationalIdMasked,
            String kycStatus,
            String status,
            boolean isBlacklisted) {}

    @Schema(name = "MemberPage")
    record MemberPage(List<MemberListItem> items, String nextCursor) {}
}
