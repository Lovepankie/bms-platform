package com.rincoltech.bms.core.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of {@code /api/v1/users}, {@code /api/v1/roles} and {@code /api/v1/me} (section 7.11.4). */
final class UserApi {

    private UserApi() {}

    @Schema(name = "RoleAssignmentRequest", description = "branch_id null means every branch")
    record RoleAssignmentRequest(@NotBlank String roleKey, UUID branchId) {}

    @Schema(name = "InviteUserRequest", description = "Email or phone (or both) is required (FR-IAM-01)")
    record InviteUserRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Email @Size(max = 320) String email,
            @Size(max = 20) String phone,
            @NotEmpty @Size(max = 20) List<@Valid RoleAssignmentRequest> roles) {}

    @Schema(name = "UpdateUserRequest", description = "Omitted fields are unchanged")
    record UpdateUserRequest(
            @Size(min = 1, max = 200) String fullName,
            @Email @Size(max = 320) String email,
            @Size(max = 20) String phone) {}

    @Schema(name = "RoleAssignment")
    record RoleAssignment(String roleKey, String roleName, UUID branchId) {}

    @Schema(name = "User")
    record UserResponse(
            UUID id,
            String fullName,
            String email,
            String phoneE164,
            String status,
            boolean mfaEnabled,
            Instant lastLoginAt,
            List<RoleAssignment> roles,
            Instant createdAt,
            int version) {}

    @Schema(name = "UserPage")
    record UserPage(List<UserResponse> items, String nextCursor) {}

    /**
     * The one-time link, shown to the inviting admin once per issue, in addition to the message
     * sent through the notification port (FR-IAM-01 as amended, review item 1 of PR #8).
     */
    @Schema(name = "InvitationLink")
    record InvitationLink(String url, Instant expiresAt) {}

    @Schema(name = "InvitedUser")
    record InvitedUserResponse(UserResponse user, InvitationLink invitation) {}

    @Schema(name = "Role")
    record RoleResponse(String key, String name, boolean mfaRequired, List<String> permissions) {}

    @Schema(name = "RoleCatalogue")
    record RoleCatalogue(List<RoleResponse> items) {}

    @Schema(name = "MeBranch")
    record MeBranch(UUID id, String code, String name, boolean isHeadOffice) {}

    /** Where one permission applies: every branch, or the listed ones (ADR-017). */
    @Schema(name = "MePermissionScope")
    record MePermissionScope(boolean allBranches, List<UUID> branchIds) {}

    /**
     * What the frontend shows and hides by (chapter 8 section 8.3.3), and the branches the user can
     * switch between (FR-BR-03). {@code all_branches} lets the user pick "All branches" (FR-BR-04).
     */
    @Schema(name = "Me")
    record MeResponse(
            UUID userId,
            String kind,
            String fullName,
            String email,
            String phoneE164,
            List<RoleAssignment> roles,
            List<String> permissions,

            @Schema(description = "Per permission key, the branches it applies in")
            Map<String, MePermissionScope> permissionScopes,

            boolean allBranches,
            List<MeBranch> branches,
            UUID defaultBranchId,
            boolean mfaEnabled,
            boolean mfaRequired,
            int unusedRecoveryCodes) {}

    @Schema(name = "PlatformMe")
    record PlatformMeResponse(UUID userId, String kind, String fullName, String email, List<String> permissions) {}
}
