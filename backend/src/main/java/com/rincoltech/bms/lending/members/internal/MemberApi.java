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

            UUID officerUserId) {}

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
            String status,
            boolean isBlacklisted,
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
