package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.internal.UserApi.InvitationLink;
import com.rincoltech.bms.core.identity.internal.UserApi.InviteUserRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.InvitedUserResponse;
import com.rincoltech.bms.core.identity.internal.UserApi.RoleAssignmentRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UpdateUserRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.UserPage;
import com.rincoltech.bms.core.identity.internal.UserApi.UserResponse;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    UserController(UserService service) {
        this.service = service;
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
    @Operation(summary = "Change a staff user's name or contact", operationId = "updateUser")
    ResponseEntity<UserResponse> update(
            @PathVariable("user_id") UUID userId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateUserRequest request) {
        UserResponse user = service.update(userId, ifMatch, request);
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
}
