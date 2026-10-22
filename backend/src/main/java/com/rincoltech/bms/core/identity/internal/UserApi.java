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
            @Size(max = 20) String phone,

            @Size(max = 20)
            @Schema(
                    description =
                            "Only for the Owner changing their own email or phone (ADR-062): the current code from their authenticator app")
            String totpCode,

            @Size(max = 200)
            @Schema(
                    description =
                            "Only for the Owner changing their own email or phone (ADR-062), when they have no two-step sign-in: their own password")
            String password) {}

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
            int version,

            @Schema(
                    description =
                            "True for the Owner of the business: one per business, an administrator whom nobody inside the business can remove, deactivate or demote, and who alone adds, removes or changes administrators (ADR-062)")
            boolean isOwner) {}

    @Schema(name = "UserPage")
    record UserPage(List<UserResponse> items, String nextCursor) {}

    /**
     * The one-time link, shown to the inviting admin once per issue, in addition to the message
     * sent through the notification port (FR-IAM-01 as amended, review item 1 of PR #8).
     */
    @Schema(name = "InvitationLink")
    record InvitationLink(String url, Instant expiresAt) {}

    /**
     * The one-time link a tenant admin hands to a user who forgot the password (FR-IAM-15): shown once, valid for one hour, and
     * never sent by email on this path.
     */
    @Schema(name = "SignInResetLink")
    record SignInResetLink(String url, Instant expiresAt) {}

    @Schema(
            name = "ResetSignInRequest",
            description =
                    "Proof that the signed-in admin is still themselves: the code from their authenticator app when they have two-step sign-in, otherwise their own password. Exactly the one that applies is required; never logged")
    record ResetSignInRequest(
            @Size(max = 20) @Schema(description = "The current 6 digit code from the admin's own authenticator app")
            String totpCode,

            @Size(max = 200)
            @Schema(description = "The admin's own current password, for an admin with no two-step sign-in")
            String password) {}

    @Schema(name = "UnlockSignInRequest")
    record UnlockSignInRequest(
            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the tenant's audit log")
            String reason) {}

    @Schema(
            name = "TransferOwnershipRequest",
            description =
                    "Proof that the signed-in Owner is still themselves, as for the reset of a sign-in: the code from their authenticator app when they have two-step sign-in, otherwise their own password; never logged")
    record TransferOwnershipRequest(
            @Size(max = 20) @Schema(description = "The current 6 digit code from the Owner's own authenticator app")
            String totpCode,

            @Size(max = 200)
            @Schema(description = "The Owner's own current password, for an Owner with no two-step sign-in")
            String password,

            @Size(max = 300) @Schema(description = "Optional: why, kept in the audit row")
            String reason) {}

    @Schema(name = "InvitedUser")
    record InvitedUserResponse(UserResponse user, InvitationLink invitation) {}

    @Schema(name = "Role")
    record RoleResponse(String key, String name, boolean mfaRequired, List<String> permissions) {}

    @Schema(name = "RoleCatalogue")
    record RoleCatalogue(List<RoleResponse> items) {}

    @Schema(name = "MeBranch")
    record MeBranch(
            UUID id,
            String code,
            String name,
            boolean isHeadOffice,

            @Schema(
                    description =
                            "false for a branch that keeps no goods: sale, restock and transfer forms skip it (#329)")
            boolean stocksGoods,

            @Schema(
                    description = "false for a Store, a branch that holds stock but does not sell: the sale form, the "
                            + "default selling branch and the till skip it; restock, goods receipt, transfer and stock-take "
                            + "forms include it (#366)")
            boolean sellsGoods) {}

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
            int unusedRecoveryCodes,

            @Schema(description = "Guided tours the user finished or dismissed, by tour id (issue #19)")
            Map<String, TourProgress.TourState> tours,

            @Schema(description = "Keys of the modules the tenant has switched on, sorted (issue #185)")
            List<String> modules,

            @Schema(
                    description =
                            "Per enabled module that has a settings group, the capabilities switched on (ADR-033); the PWA offers a capability's screens only when listed")
            Map<String, List<String>> moduleCapabilities,

            @Schema(
                    description =
                            "True when the tenant has switched report runs on (setting reports_enabled, ADR-038); the PWA shows the Reports tab only then")
            boolean reportsEnabled,

            @Schema(
                    description =
                            "When the tenant's two-step policy will require this user to set up a second factor: only for a user it covers who has none yet and whose grace period is still running, otherwise null (ADR-046)")
            Instant mfaDueBy,

            @Schema(
                    description =
                            "The tenant's two-step sign-in policy: off, roles or everyone; the PWA recommends turning it on to administrators while it is off (ADR-046)")
            String twoStepPolicy,

            @Schema(description = "The business's time zone, in which mfa_due_by is a day's end (ADR-046)")
            String tenantTimezone,

            @Schema(
                    description =
                            "True when the signed-in user is the Owner of the business (ADR-062): the PWA offers the owner-only actions and the Transfer ownership action only then")
            boolean isOwner) {}

    @Schema(name = "PlatformMe")
    record PlatformMeResponse(UUID userId, String kind, String fullName, String email, List<String> permissions) {}
}
