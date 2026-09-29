package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/platform} tenant management (chapter 7 section 7.11.3). Platform operators only. */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class PlatformController {

    private final PlatformService service;

    PlatformController(PlatformService service) {
        this.service = service;
    }

    @Schema(name = "Plan", description = "Limits only; prices are not stored in this repository")
    record PlanResponse(
            String code,
            String name,
            Integer maxBranches,
            Integer maxStaffUsers,
            Integer maxActiveMembers,
            List<String> allowedModules) {}

    @Schema(name = "PlanList")
    record PlanList(List<PlanResponse> items) {}

    @Schema(name = "PlatformTenant")
    record TenantResponse(
            UUID id,
            String slug,
            String name,

            @Schema(description = "active or suspended (read only, FR-TEN-06)")
            String status,

            String planCode,
            String currency,
            String timezone,
            String subscriptionStatus,
            LocalDate nextStatusChangeOn,
            List<String> modules,
            Instant createdAt) {}

    @Schema(name = "PlatformTenantList")
    record TenantList(List<TenantResponse> items) {}

    @Schema(name = "HeadOfficeRequest")
    record HeadOfficeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,10}$") String code,
            @NotBlank @Size(max = 200) String name) {}

    @Schema(name = "FirstAdminRequest", description = "Email or phone (or both) is required")
    record FirstAdminRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Email @Size(max = 320) String email,
            @Size(max = 20) String phone) {}

    @Schema(name = "CreateTenantRequest")
    record CreateTenantRequest(
            @NotBlank @Size(max = 200) String name,

            @NotBlank @Schema(description = "FR-TEN-02 rules; cannot change later")
            String slug,

            @NotBlank String planCode,

            @Pattern(regexp = "^[A-Z]{3}$") @Schema(description = "Default UGX")
            String currency,

            @Schema(description = "Default Africa/Kampala") String timezone,
            List<@Pattern(regexp = "^[a-z]{2,30}$") String> modules,
            @NotNull @Valid HeadOfficeRequest headOffice,
            @NotNull @Valid FirstAdminRequest admin) {}

    @Schema(name = "FirstAdminInvitation", description = "Shown once to the operator, and sent to the admin")
    record FirstAdminInvitation(UUID userId, String url, Instant expiresAt) {}

    @Schema(name = "CreatedTenant")
    record CreatedTenantResponse(
            TenantResponse tenant, UUID headOfficeBranchId, FirstAdminInvitation adminInvitation) {}

    @Schema(name = "TenantModulesRequest")
    record ModulesRequest(@NotNull List<@Pattern(regexp = "^[a-z]{2,30}$") String> modules) {}

    @Schema(name = "SubscriptionStatusRequest")
    record SubscriptionRequest(
            @NotBlank @Pattern(regexp = "trial|active|past_due|suspended|cancelled")
            String status,

            LocalDate nextStatusChangeOn) {}

    @GetMapping("/plans")
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "Plans with their limits (FR-TEN-04)", operationId = "platformListPlans")
    PlanList plans() {
        return new PlanList(service.plans());
    }

    @GetMapping("/tenants")
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "Every tenant", operationId = "platformListTenants")
    TenantList tenants() {
        return new TenantList(service.tenants());
    }

    @PostMapping("/tenants")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Create a tenant with head office, modules and an invited first admin (FR-TEN-01)",
            operationId = "platformCreateTenant")
    ResponseEntity<CreatedTenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        CreatedTenantResponse created = service.create(request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/platform/tenants/" + created.tenant().id()))
                .body(created);
    }

    @GetMapping("/tenants/{tenant_id}")
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "One tenant", operationId = "platformGetTenant")
    TenantResponse get(@PathVariable("tenant_id") UUID tenantId) {
        return service.tenant(tenantId);
    }

    @PutMapping("/tenants/{tenant_id}/modules")
    @RequiresPermission("platform.tenants.manage")
    @Operation(summary = "Switch modules on or off; data is kept (FR-TEN-03)", operationId = "platformSetModules")
    TenantResponse modules(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody ModulesRequest request) {
        return service.setModules(tenantId, request.modules());
    }

    @PostMapping("/tenants/{tenant_id}/subscription")
    @RequiresPermission("platform.tenants.manage")
    @Operation(summary = "Move the subscription status (FR-TEN-05)", operationId = "platformSetSubscription")
    TenantResponse subscription(
            @PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody SubscriptionRequest request) {
        return service.setSubscription(tenantId, request.status(), request.nextStatusChangeOn());
    }

    @PostMapping("/tenants/{tenant_id}/suspend")
    @RequiresPermission("platform.tenants.manage")
    @Operation(summary = "Suspend: the tenant becomes read only (FR-TEN-06)", operationId = "platformSuspendTenant")
    TenantResponse suspend(@PathVariable("tenant_id") UUID tenantId) {
        return service.setSubscription(tenantId, "suspended", null);
    }

    @PostMapping("/tenants/{tenant_id}/resume")
    @RequiresPermission("platform.tenants.manage")
    @Operation(summary = "Resume a suspended tenant (FR-TEN-06)", operationId = "platformResumeTenant")
    TenantResponse resume(@PathVariable("tenant_id") UUID tenantId) {
        return service.setSubscription(tenantId, "active", null);
    }

    @PostMapping("/tenants/{tenant_id}/users/{user_id}/mfa/reset")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Reset the lost second factor of a tenant admin (FR-IAM-12)",
            operationId = "platformResetAdminMfa")
    ResponseEntity<Void> resetAdminMfa(@PathVariable("tenant_id") UUID tenantId, @PathVariable("user_id") UUID userId) {
        service.resetAdminMfa(tenantId, userId);
        return ResponseEntity.noContent().build();
    }
}
