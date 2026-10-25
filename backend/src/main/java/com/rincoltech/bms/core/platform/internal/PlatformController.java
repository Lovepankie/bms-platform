package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.identity.OperatorSecurity;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform} tenant management (chapter 7 section 7.11.3). Platform operators only. The plans and their
 * prices are {@link PriceBookController}.
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class PlatformController {

    private final PlatformService service;
    private final BusinessService business;
    private final OperatorSecurity security;

    PlatformController(PlatformService service, BusinessService business, OperatorSecurity security) {
        this.service = service;
        this.business = business;
        this.security = security;
    }

    @Schema(name = "PlatformTenant")
    record TenantResponse(
            UUID id,
            String slug,
            String name,

            @Schema(description = "active or suspended (read only, FR-TEN-06)")
            String status,

            String planCode,
            String planName,
            String currency,
            String timezone,

            @Schema(description = "trial, active, past_due, suspended or cancelled")
            String subscriptionStatus,

            LocalDate nextStatusChangeOn,

            @Schema(description = "The last day of the free month; set at the first staff sign-in, null before it")
            LocalDate trialEndsOn,

            @Schema(description = "The first day of the paid period, or null")
            LocalDate paidFrom,

            @Schema(description = "The last day paid for, or null when nothing was paid")
            LocalDate paidUntil,

            List<String> modules,
            Instant createdAt,

            @Schema(description = "The Owner of the business (ADR-062), or null while it has none")
            String ownerName,

            String ownerEmail,

            @Schema(description = "The latest sign-in of any staff user, or null")
            Instant lastSignInAt,

            @Schema(description = "The business's own address, where its staff sign in")
            String address,

            @Schema(
                    description =
                            "While suspended or cancelled: the status a resume brings back (trial, active or past_due)")
            String suspendedFrom,

            @Schema(description = "A pilot or complimentary business: free, no free month end (ADR-024 decision 6)")
            boolean pilot) {}

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

    @Schema(name = "PlatformStepUpRequest")
    record StepUpRequest(
            @NotBlank
            @Size(max = 20)
            @Schema(description = "A current 6 digit code from the operator's own authenticator app")
            String totpCode,

            @NotBlank
            @Size(min = 5, max = 300)
            @Schema(description = "Why; kept in the platform audit log (issue #407)")
            String reason) {}

    @Schema(
            name = "PlatformSignInResetLink",
            description = "Shown once to the operator, who hands it to the tenant admin")
    record SignInResetLink(String url, Instant expiresAt) {}

    @Schema(name = "FirstAdminInvitation", description = "Shown once to the operator, and sent to the admin")
    record FirstAdminInvitation(UUID userId, String url, Instant expiresAt) {}

    @Schema(name = "CreatedTenant")
    record CreatedTenantResponse(
            TenantResponse tenant, UUID headOfficeBranchId, FirstAdminInvitation adminInvitation) {}

    @Schema(name = "TenantModulesRequest")
    record ModulesRequest(
            @NotNull List<@Pattern(regexp = "^[a-z]{2,30}$") String> modules,

            @NotBlank
            @Size(min = 5, max = 300)
            @Schema(description = "Why; kept in the platform audit log (issue #407)")
            String reason) {}

    @Schema(name = "TenantPilotRequest")
    record PilotRequest(
            @NotNull
            @Schema(description = "True: a pilot or complimentary business, free with no end (ADR-024 decision 6)")
            Boolean pilot,

            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "SubscriptionStatusRequest")
    record SubscriptionRequest(
            @NotBlank @Pattern(regexp = "trial|active|past_due|suspended|cancelled")
            String status,

            LocalDate nextStatusChangeOn,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the platform audit log")
            String reason) {}

    @Schema(name = "TenantGoLiveRequest")
    record GoLiveRequest(
            @NotNull @Schema(description = "The day the app became the business's register, today or earlier (ADR-071)")
            LocalDate goLiveDate,

            @NotBlank
            @Size(min = 5, max = 300)
            @Schema(description = "Why; kept in the tenant's audit log and the platform's")
            String reason) {}

    @Schema(name = "PlatformReasonRequest")
    record ReasonRequest(
            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the platform audit log")
            String reason) {}

    @Schema(name = "TenantPlanRequest")
    record PlanRequest(
            @NotBlank @Pattern(regexp = PriceBookService.PLAN_CODE)
            String planCode,

            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "SubscriptionDatesRequest", description = "Send one or both dates")
    record DatesRequest(
            @Schema(description = "The new last day of the free month")
            LocalDate trialEndsOn,

            @Schema(description = "The new last day paid for")
            LocalDate paidUntil,

            @Schema(
                    description =
                            "True clears the last day of the free month (the clock starts again at the next sign-in)")
            Boolean clearTrialEndsOn,

            @Schema(description = "True clears what is paid (the last day paid for and the start of the paid period)")
            Boolean clearPaidUntil,

            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "SubscriptionPaymentRequest", description = "A payment received by mobile money or bank transfer")
    record PaymentRequest(
            @NotNull
            @Min(1)
            @Max(PriceBookService.MAX_MINOR)
            @Schema(description = "Minor units; whole shillings for UGX; at most 50,000,000")
            Long amountMinor,

            @NotBlank @Pattern(regexp = "mtn_momo|airtel_money|bank_transfer|other")
            String method,

            @Size(max = 100) @Schema(description = "The sender's transaction reference")
            String reference,

            @NotNull LocalDate paidOn,

            @NotNull @Min(1) @Max(24) @Schema(description = "Months the payment covers: 1 for a month, 12 for a year")
            Integer months,

            @Size(max = 500) String note,

            @NotBlank
            @Size(min = 5, max = 300)
            @Schema(
                    description =
                            "Why it is recorded (who confirmed it, how it was checked); kept in the platform audit log")
            String reason,

            @Schema(
                    description =
                            "True when the operator checked an amount more than 25% away from the plan's price for these months, or a plan with no price (422 payment_amount_unusual otherwise)")
            Boolean confirmUnusualAmount) {}

    @Schema(name = "SubscriptionPayment")
    record PaymentResponse(
            UUID id,

            @Schema(
                    description =
                            "confirmed (entered by the operator); claimed and rejected arrive with the tenant's billing")
            String status,

            long amountMinor,
            String currency,
            String method,
            String reference,
            LocalDate paidOn,
            int months,

            @Schema(description = "The first day the payment covers")
            LocalDate periodStart,

            @Schema(description = "The last day the payment covers")
            LocalDate periodEnd,

            String note,
            String recordedByName,
            Instant createdAt) {}

    @Schema(name = "TenantAdmin")
    record AdminResponse(
            UUID id,
            String fullName,
            String email,
            String phone,

            @Schema(description = "invited, active or deactivated")
            String status,

            boolean mfaEnabled,
            Instant lastLoginAt,
            Instant createdAt,

            @Schema(description = "True for the Owner of the business, listed first (ADR-062)")
            boolean isOwner) {}

    @Schema(name = "TenantMessage", description = "A message the outbox holds for the business; never its text or link")
    record MessageResponse(
            UUID id,
            String channel,

            @Schema(description = "Masked to its last 4 characters")
            String recipient,

            String templateKey,

            @Schema(description = "pending, sent or failed") String status,

            int attempts,
            String lastError,
            Instant createdAt,
            Instant sentAt,
            Instant failedAt) {}

    @Schema(
            name = "TenantHistoryEntry",
            description = "An operator action on the business, from the platform audit log")
    record HistoryResponse(
            String action, String operatorName, Instant occurredAt, String reason, String before, String after) {}

    @Schema(name = "BusinessDetail")
    record BusinessDetail(
            TenantResponse tenant,

            @Schema(description = "The price in force today for the business's plan, or null when none was entered")
            PriceBookController.PlanPriceResponse planPrice,

            List<AdminResponse> admins,

            @Schema(description = "Newest first") List<PaymentResponse> payments,

            @Schema(description = "Newest first, at most 50; sent messages are purged after a while")
            List<MessageResponse> messages,

            @Schema(description = "Newest first, at most 50")
            List<HistoryResponse> history) {}

    @GetMapping("/tenants")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Every tenant with plan, subscription, owner and last sign-in; search and filter (FR-TEN-05)",
            operationId = "platformListTenants")
    TenantList tenants(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "module", required = false) String module) {
        return new TenantList(service.tenants(q, status, module));
    }

    @Schema(name = "OwnerCandidate", description = "An active administrator of a business with no Owner")
    record OwnerCandidate(UUID id, String fullName, String email, Instant lastLoginAt) {}

    @Schema(name = "TenantWithoutOwner")
    record TenantWithoutOwner(
            UUID tenantId,
            String slug,
            String name,

            @Schema(description = "active or suspended") String status,

            Instant createdAt,

            @Schema(description = "Its active administrators, oldest first; any one of them can be made the Owner")
            List<OwnerCandidate> admins) {}

    @GetMapping("/tenants/without-owner")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary =
                    "Every active or suspended business with no Owner, with its active administrators, so the operator sets the Owner (ADR-062, ADR-090)",
            operationId = "platformTenantsWithoutOwner")
    List<TenantWithoutOwner> tenantsWithoutOwner() {
        return service.tenantsWithoutOwner();
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

    @GetMapping("/tenants/{tenant_id}/overview")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary =
                    "The business page: details, owner and admins, price, payments, recent messages and operator history",
            operationId = "platformTenantOverview")
    BusinessDetail overview(@PathVariable("tenant_id") UUID tenantId) {
        return business.overview(tenantId);
    }

    @PutMapping("/tenants/{tenant_id}/plan")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Move the business to another active plan, with a reason; the same plan again changes nothing (FR-TEN-04)",
            operationId = "platformSetTenantPlan")
    TenantResponse plan(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody PlanRequest request) {
        return business.setPlan(tenantId, request.planCode(), request.reason());
    }

    @PutMapping("/tenants/{tenant_id}/subscription-dates")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Set the last day of the free month or the last day paid for, with a reason (FR-TEN-05)",
            operationId = "platformSetSubscriptionDates")
    TenantResponse dates(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody DatesRequest request) {
        return business.setDates(tenantId, request);
    }

    @PostMapping("/tenants/{tenant_id}/payments")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Record a payment received; it extends what is paid (ADR-024 decision 4). Needs an Idempotency-Key; a repeat records nothing",
            operationId = "platformRecordPayment")
    ResponseEntity<PaymentResponse> payment(
            @PathVariable("tenant_id") UUID tenantId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        BusinessService.Recorded recorded = business.recordPayment(tenantId, idempotencyKey, request);
        return ResponseEntity.status(recorded.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(recorded.payment());
    }

    @PutMapping("/tenants/{tenant_id}/modules")
    @RequiresPermission("platform.tenants.manage")
    @Operation(summary = "Switch modules on or off; data is kept (FR-TEN-03)", operationId = "platformSetModules")
    TenantResponse modules(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody ModulesRequest request) {
        return service.setModules(tenantId, request.modules(), request.reason());
    }

    @PutMapping("/tenants/{tenant_id}/go-live")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Set the business's go-live date, with a reason: an existing loan is brought in only with a disbursement date before it (ADR-071)",
            operationId = "platformSetGoLiveDate")
    ResponseEntity<Void> goLive(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody GoLiveRequest request) {
        service.setGoLive(tenantId, request.goLiveDate(), request.reason().trim());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/tenants/{tenant_id}/pilot")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Mark or unmark a pilot (complimentary) business, with a reason: free, no free month end; the same mark again changes nothing (ADR-024 decision 6)",
            operationId = "platformSetTenantPilot")
    TenantResponse pilot(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody PilotRequest request) {
        return business.setPilot(tenantId, request.pilot(), request.reason());
    }

    @PostMapping("/tenants/{tenant_id}/subscription")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Move the subscription status, with a reason; the same status again changes nothing (FR-TEN-05)",
            operationId = "platformSetSubscription")
    TenantResponse subscription(
            @PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody SubscriptionRequest request) {
        return service.setSubscription(tenantId, request.status(), request.nextStatusChangeOn(), request.reason());
    }

    @PostMapping("/tenants/{tenant_id}/suspend")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Suspend with a reason: the tenant becomes read only, nothing is deleted; already suspended changes nothing (FR-TEN-06)",
            operationId = "platformSuspendTenant")
    TenantResponse suspend(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        return service.setSubscription(tenantId, "suspended", null, request.reason());
    }

    @PostMapping("/tenants/{tenant_id}/resume")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Resume a suspended tenant with a reason, back to the status it had before (a free month stays a free month); a tenant that is not suspended changes nothing (FR-TEN-06)",
            operationId = "platformResumeTenant")
    TenantResponse resume(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        return service.resume(tenantId, request.reason());
    }

    /**
     * Both reset routes below ask the signed-in operator for a current code from their own authenticator, used once, before
     * anything is done (ADR-042): a stolen operator token alone cannot take a tenant's admin over. The target is checked first
     * (read-only) on both, so a code is not spent on a call that would be refused.
     */
    @PostMapping("/tenants/{tenant_id}/users/{user_id}/reset-sign-in")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Issue a one-time password reset link for a tenant admin, with a reason; shown once, valid one hour; needs a current code from the operator's own authenticator (FR-IAM-15)",
            operationId = "platformResetAdminSignIn")
    SignInResetLink resetAdminSignIn(
            @PathVariable("tenant_id") UUID tenantId,
            @PathVariable("user_id") UUID userId,
            @Valid @RequestBody StepUpRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        service.requireResettableAdmin(tenantId, userId);
        security.requireTotp(request.totpCode());
        TenantAdmins.ResetLink link = service.resetAdminSignIn(tenantId, userId, request.reason());
        return new SignInResetLink(link.url(), link.expiresAt());
    }

    /**
     * No step-up: lifting a lock gives nobody a credential, it only lets the owner try their own password again (ADR-059).
     * The reason is kept in both audit logs.
     */
    @PostMapping("/tenants/{tenant_id}/users/{user_id}/unlock")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Lift the sign-in lock of a tenant's staff user after too many wrong passwords, with a reason; audited, changes no password (ADR-059)",
            operationId = "platformUnlockUserSignIn")
    ResponseEntity<Void> unlockSignIn(
            @PathVariable("tenant_id") UUID tenantId,
            @PathVariable("user_id") UUID userId,
            @Valid @RequestBody ReasonRequest request) {
        service.unlockSignIn(tenantId, userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * ADR-062: for a business whose Owner lost access (or that has none), the operator makes one of its active administrators the
     * Owner, with a reason, audited in both logs. Never a non-administrator in one step: the person is made an administrator of
     * the business first. The target is checked before the operator's code is spent.
     */
    @PostMapping("/tenants/{tenant_id}/users/{user_id}/make-owner")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Make an active administrator of the business its Owner, with a reason; the previous Owner stays an administrator; needs a current code from the operator's own authenticator (FR-IAM-18)",
            operationId = "platformTransferOwnership")
    ResponseEntity<Void> makeOwner(
            @PathVariable("tenant_id") UUID tenantId,
            @PathVariable("user_id") UUID userId,
            @Valid @RequestBody StepUpRequest request) {
        service.requireOwnerCandidate(tenantId, userId);
        security.requireTotp(request.totpCode());
        service.transferOwnership(tenantId, userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * ADR-062: a new business whose first administrator (its Owner) never joined, and whose invitation expired, is not stuck:
     * the operator sends a new link, with a reason and the operator's own code, shown once. Only for that invited first
     * administrator; the target is checked before the code is spent.
     */
    @PostMapping("/tenants/{tenant_id}/users/{user_id}/invitation")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Issue a new invitation link for the first administrator (the Owner) of a business who never joined, with a reason; shown once; needs a current code from the operator's own authenticator (FR-IAM-18)",
            operationId = "platformReissueOwnerInvitation")
    FirstAdminInvitation reissueOwnerInvitation(
            @PathVariable("tenant_id") UUID tenantId,
            @PathVariable("user_id") UUID userId,
            @Valid @RequestBody StepUpRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        service.requireInvitedFirstAdmin(tenantId, userId);
        security.requireTotp(request.totpCode());
        TenantAdmins.Invitation invitation = service.reissueFirstAdminInvitation(tenantId, userId, request.reason());
        return new FirstAdminInvitation(invitation.userId(), invitation.url(), invitation.expiresAt());
    }

    @PostMapping("/tenants/{tenant_id}/users/{user_id}/mfa/reset")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Reset the lost second factor of a tenant admin, with a reason; needs a current code from the operator's own authenticator (FR-IAM-12)",
            operationId = "platformResetAdminMfa")
    ResponseEntity<Void> resetAdminMfa(
            @PathVariable("tenant_id") UUID tenantId,
            @PathVariable("user_id") UUID userId,
            @Valid @RequestBody StepUpRequest request) {
        service.requireTenantAdmin(tenantId, userId);
        security.requireTotp(request.totpCode());
        service.resetAdminMfa(tenantId, userId, request.reason());
        return ResponseEntity.noContent().build();
    }
}
