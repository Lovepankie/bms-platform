package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.AdminSecurity;
import com.rincoltech.bms.core.identity.PasswordStepUp;
import com.rincoltech.bms.core.identity.internal.UserApi.InvitationLink;
import com.rincoltech.bms.core.identity.internal.UserApi.InviteUserRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.InvitedUserResponse;
import com.rincoltech.bms.core.identity.internal.UserApi.ResetSignInRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.RoleAssignmentRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.SignInResetLink;
import com.rincoltech.bms.core.identity.internal.UserApi.TransferOwnershipRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UnlockSignInRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UpdateUserRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UserPage;
import com.rincoltech.bms.core.identity.internal.UserApi.UserResponse;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/users} (chapter 7 section 7.11.4). */
@RestController
@Validated
@RequestMapping(path = "/api/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "users")
class UserController {

    private final UserService service;
    private final PasswordResets resets;
    private final AdminSecurity security;
    private final PasswordStepUp passwords;

    UserController(UserService service, PasswordResets resets, AdminSecurity security, PasswordStepUp passwords) {
        this.service = service;
        this.resets = resets;
        this.security = security;
        this.passwords = passwords;
    }

    @GetMapping
    @RequiresPermission("core.users.read")
    @Operation(summary = "Staff users with their roles", operationId = "listUsers")
    UserPage list(
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(statuses, limit, cursor);
    }

    @PostMapping
    @RequiresPermission("core.users.manage")
    @Operation(
            summary = "Invite a staff user; the one-time link is returned once to the inviting admin (FR-IAM-01)",
            operationId = "inviteUser")
    ResponseEntity<InvitedUserResponse> invite(@Valid @RequestBody InviteUserRequest request) {
        InvitedUserResponse invited = service.invite(request);
        return ResponseEntity.created(
                        URI.create("/api/v1/users/" + invited.user().id()))
                .eTag(String.valueOf(invited.user().version()))
                .body(invited);
    }

    @GetMapping("/{user_id}")
    @RequiresPermission("core.users.read")
    @Operation(summary = "Get one staff user", operationId = "getUser")
    ResponseEntity<UserResponse> get(@PathVariable("user_id") UUID userId) {
        UserResponse user = service.get(userId);
        return ResponseEntity.ok().eTag(String.valueOf(user.version())).body(user);
    }

    @PatchMapping("/{user_id}")
    @RequiresPermission("core.users.manage")
    @Operation(
            summary =
                    "Change a staff user's name or contact; the Owner changing their own email or phone also sends totp_code or password (ADR-062)",
            operationId = "updateUser")
    ResponseEntity<UserResponse> update(
            @PathVariable("user_id") UUID userId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateUserRequest request) {
        // ADR-062: the Owner's own email or phone is where a reset link goes, so changing it needs the step-up first.
        boolean steppedUp = false;
        if (service.contactChangeNeedsStepUp(userId, request)) {
            stepUp(request.totpCode(), request.password());
            steppedUp = true;
        }
        UserResponse user = service.update(userId, ifMatch, request, steppedUp);
        return ResponseEntity.ok().eTag(String.valueOf(user.version())).body(user);
    }

    @PutMapping("/{user_id}/roles")
    @RequiresPermission("core.users.manage")
    @Operation(summary = "Replace a user's roles and branch scopes (FR-IAM-01)", operationId = "setUserRoles")
    UserResponse setRoles(
            @PathVariable("user_id") UUID userId,
            @RequestBody @Size(min = 1, max = 20) List<@Valid RoleAssignmentRequest> roles) {
        return service.setRoles(userId, roles);
    }

    @PostMapping("/{user_id}/deactivate")
    @RequiresPermission("core.users.manage")
    @Operation(summary = "Deactivate a user and revoke every session (FR-IAM-08)", operationId = "deactivateUser")
    UserResponse deactivate(@PathVariable("user_id") UUID userId) {
        return service.deactivate(userId);
    }

    @PostMapping("/{user_id}/invitation")
    @RequiresPermission("core.users.manage")
    @Operation(
            summary = "Issue a new invitation link to a user still invited (FR-IAM-01)",
            operationId = "reissueInvitation")
    InvitationLink reissueInvitation(@PathVariable("user_id") UUID userId) {
        return service.reissueInvitation(userId);
    }

    @PostMapping("/{user_id}/mfa/reset")
    @RequiresPermission("core.users.manage")
    @Operation(summary = "Reset another user's lost second factor (FR-IAM-12)", operationId = "resetUserMfa")
    UserResponse resetMfa(@PathVariable("user_id") UUID userId) {
        return service.resetMfa(userId);
    }

    @PostMapping("/{user_id}/unlock")
    @RequiresPermission("core.users.manage")
    @Operation(
            summary =
                    "Lift the sign-in lock of a staff user after too many wrong passwords, with a reason; audited, changes no password (ADR-059)",
            operationId = "unlockUserSignIn")
    UserResponse unlock(@PathVariable("user_id") UUID userId, @Valid @RequestBody UnlockSignInRequest request) {
        return service.unlockSignIn(userId, request.reason());
    }

    /**
     * The order matters: the target is checked first (read-only), then the acting admin proves they are still themselves, then
     * the link is made, which checks the target again under a lock. The proof is a fresh code from their own authenticator when
     * they have one enrolled, otherwise their own current password (counted on the sign-in lock like a wrong password at sign-in):
     * two-step sign-in is optional, so an admin without it must not be locked out of this route, and a stolen session or token
     * alone gives neither (ADR-042). The step-up runs before the transaction of the action, as {@link AdminSecurity#requireTotp}
     * requires. Neither credential is ever logged.
     */
    @PostMapping("/{user_id}/reset-sign-in")
    @RequiresPermission("core.users.manage")
    @Operation(
            summary =
                    "Issue a one-time link to set a new password for an active user who is not a tenant admin; shown once, valid for one hour; needs the admin's authenticator code, or their own password when they have no two-step sign-in (FR-IAM-15)",
            operationId = "resetUserSignIn")
    SignInResetLink resetSignIn(
            @PathVariable("user_id") UUID userId,
            @Valid @RequestBody ResetSignInRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        resets.precheckForAdmin(userId);
        stepUp(request.totpCode(), request.password());
        return resets.issueForAdmin(userId);
    }

    /**
     * ADR-062: the Owner hands ownership to another active administrator, who becomes the one Owner; the old Owner stays an
     * administrator. As for the reset of a sign-in, the target is checked first (read-only), then the Owner proves they are still
     * themselves, then the transfer runs under a lock and checks again.
     */
    @PostMapping("/{user_id}/transfer-ownership")
    @RequiresPermission("core.users.manage")
    @Operation(
            summary =
                    "Hand ownership of the business to another active administrator; the Owner only; needs the Owner's authenticator code, or their own password when they have no two-step sign-in (FR-IAM-18)",
            operationId = "transferOwnership")
    UserResponse transferOwnership(
            @PathVariable("user_id") UUID userId, @Valid @RequestBody TransferOwnershipRequest request) {
        service.precheckTransfer(userId);
        stepUp(request.totpCode(), request.password());
        return service.transferOwnership(userId, request.reason());
    }

    /**
     * The proof that the signed-in admin is still themselves, before the transaction of the action (ADR-042): a fresh code from
     * their own authenticator when they have one enrolled, otherwise their own current password. Neither is ever logged.
     */
    private void stepUp(String totpCode, String password) {
        if (resets.actorHasSecondFactor()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw ApiException.rule("totp_required", "Enter the current code from your authenticator app.");
            }
            security.requireTotp(totpCode);
        } else {
            if (password == null || password.isEmpty()) {
                throw ApiException.rule("password_required", "Enter your own password to confirm.");
            }
            passwords.requirePassword(password);
        }
    }
}
