package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.internal.TourProgress.TourStateRequest;
import com.rincoltech.bms.core.identity.internal.UserApi.MeBranch;
import com.rincoltech.bms.core.identity.internal.UserApi.MePermissionScope;
import com.rincoltech.bms.core.identity.internal.UserApi.MeResponse;
import com.rincoltech.bms.core.identity.internal.UserApi.RoleCatalogue;
import com.rincoltech.bms.core.identity.internal.UserApi.RoleResponse;
import com.rincoltech.bms.core.identity.internal.UserApi.UserResponse;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me}, the user's own tour progress and {@code GET /api/v1/roles} (chapter 7
 * sections 7.11.2 and 7.11.4).
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "users")
class MeController {

    private final UserService users;
    private final StaffAccounts accounts;
    private final Branches branches;
    private final JdbcClient jdbc;
    private final TourProgress tours;

    MeController(UserService users, StaffAccounts accounts, Branches branches, JdbcClient jdbc, TourProgress tours) {
        this.users = users;
        this.accounts = accounts;
        this.branches = branches;
        this.jdbc = jdbc;
        this.tours = tours;
    }

    @GetMapping("/me")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "The signed-in user, permissions and branches (FR-BR-03)", operationId = "getMe")
    @Transactional(readOnly = true)
    public MeResponse me() {
        Principal principal = CurrentPrincipal.require();
        UserResponse user = users.find(principal.userId()).orElseThrow(AuthFlow::unauthenticated);
        List<MeBranch> workable = branches.all().stream()
                .filter(b -> b.active() && principal.canSeeBranch(b.id()))
                .map(b -> new MeBranch(b.id(), b.code(), b.name(), b.headOffice()))
                .toList();
        UUID defaultBranch = workable.stream()
                .filter(MeBranch::isHeadOffice)
                .findFirst()
                .or(() -> workable.stream().findFirst())
                .map(MeBranch::id)
                .orElse(null);
        return new MeResponse(
                user.id(),
                principal.kind(),
                user.fullName(),
                user.email(),
                user.phoneE164(),
                user.roles(),
                principal.permissions().stream().sorted().toList(),
                permissionScopes(principal),
                principal.allBranches(),
                workable,
                defaultBranch,
                user.mfaEnabled(),
                accounts.mfaRequired(user.id()),
                accounts.unusedRecoveryCodes(user.id()),
                tours.of(user.id()));
    }

    /**
     * Remembers that the signed-in user finished a guided tour or chose not to see it again
     * (issue #19). Any staff user may write their own progress and nobody else's: the user is the
     * principal, never a parameter.
     */
    @PutMapping(path = "/me/tours/{tour_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "Record how the user left a guided tour (issue #19)", operationId = "recordTourState")
    public ResponseEntity<Void> recordTour(
            @PathVariable("tour_id") String tourId, @Valid @RequestBody TourStateRequest request) {
        tours.record(CurrentPrincipal.require().userId(), tourId, request);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, MePermissionScope> permissionScopes(Principal principal) {
        Map<String, MePermissionScope> out = new TreeMap<>();
        principal
                .scopes()
                .forEach((permission, scope) -> out.put(
                        permission,
                        new MePermissionScope(
                                scope.all(),
                                scope.all()
                                        ? List.of()
                                        : scope.branchIds().stream().sorted().toList())));
        return out;
    }

    @GetMapping("/roles")
    @RequiresPermission("core.users.read")
    @Operation(summary = "The fixed role catalogue with permissions (FR-IAM-02)", operationId = "listRoles")
    @Transactional(readOnly = true)
    public RoleCatalogue roles() {
        return new RoleCatalogue(jdbc.sql("""
                        SELECT r.key, r.name, r.mfa_required,
                               coalesce(array_agg(rp.permission_key ORDER BY rp.permission_key)
                                        FILTER (WHERE rp.permission_key IS NOT NULL), '{}') AS permissions
                          FROM roles r LEFT JOIN role_permissions rp ON rp.role_key = r.key
                         WHERE r.kind = 'staff'
                         GROUP BY r.key, r.name, r.mfa_required
                         ORDER BY r.key
                        """)
                .query((rs, n) -> new RoleResponse(
                        rs.getString("key"), rs.getString("name"), rs.getBoolean("mfa_required"), Arrays.asList((String
                                        [])
                                rs.getArray("permissions").getArray())))
                .list());
    }
}
