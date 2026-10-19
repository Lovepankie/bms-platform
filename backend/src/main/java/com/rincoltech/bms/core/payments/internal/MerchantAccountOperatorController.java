package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/platform/tenants/{tenant_id}/payment-account} (ADR-035 amendment of 2026-10-18): the platform
 * console's view of whether a lender has connected its own payment account. Status and times only; the operator can
 * neither see nor change a credential (the lender does that itself). Read through a definer function that returns no
 * credential column at all (migration V56). Platform operators only ({@code platform.tenants.read}).
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class MerchantAccountOperatorController {

    @Schema(name = "TenantPaymentAccountStatus")
    record Status(
            @Schema(description = "not_connected, verified or failed")
            String status,

            Instant lastCheckedAt,

            @Schema(description = "A closed word: invalid_credentials or registration_refused")
            String failureReason) {}

    /** Why the operator releases the account: required, and kept in the platform audit log. */
    @Schema(name = "ReleasePaymentAccountRequest")
    record ReleaseRequest(
            @Schema(
                    description =
                            "Why the account is released (10 to 300 characters); written to the platform audit log")
            @NotBlank
            @Size(min = 10, max = 300)
            String reason) {}

    private final JdbcClient jdbc;
    private final PlatformAuditLog audit;
    private final MerchantAccountService accounts;
    private final TransactionTemplate tx;

    MerchantAccountOperatorController(
            JdbcClient jdbc,
            PlatformAuditLog audit,
            MerchantAccountService accounts,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.accounts = accounts;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * The operator's way out when a lender's account must go while a payment still waits on it (a compromised merchant
     * account, a stuck order): removes the saved account whatever is in flight, with a reason, in one transaction with its audit
     * row (the removal and the audit are both done or neither), and tells the tenant's administrators by email. Orders still
     * waiting are left as they are (they were made under that merchant account and are never checked with another's
     * credentials; the poller skips them and never closes them). The operator never sees or enters a credential.
     */
    @PostMapping("/tenants/{tenant_id}/payment-account/release")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Remove a tenant's saved payment account although payments are in flight (audited override)",
            operationId = "platformReleaseTenantPaymentAccount")
    Status release(
            @PathVariable("tenant_id") UUID tenantId,
            @Valid @RequestBody ReleaseRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        UUID operator = CurrentPrincipal.require().userId();
        tx.executeWithoutResult(status -> {
            String was = jdbc.sql("SELECT app_release_payment_account(?)")
                    .param(tenantId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (was == null) {
                throw ApiException.notFound();
            }
            audit.record(
                    "platform.payment_account.released",
                    operator,
                    tenantId,
                    java.util.Map.of("previous_status", was, "reason", request.reason()));
        });
        try {
            accounts.notifyReleased(tenantId, UUID.randomUUID());
        } catch (RuntimeException e) {
            // The release stands; a notice that could not be queued is not a reason to undo it, but it is not silent.
            LoggerFactory.getLogger(MerchantAccountOperatorController.class)
                    .error(
                            "the release notice for tenant {} could not be queued: {}",
                            tenantId,
                            e.getClass().getSimpleName());
        }
        return new Status("not_connected", null, null);
    }

    @GetMapping("/tenants/{tenant_id}/payment-account")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Whether a tenant has connected its own payment account (status only)",
            operationId = "platformGetTenantPaymentAccount")
    Status get(@PathVariable("tenant_id") UUID tenantId, HttpServletResponse response) {
        NoStore.apply(response);
        Integer known = jdbc.sql("SELECT count(*)::int FROM platform_list_tenants() WHERE id = ?")
                .param(tenantId)
                .query(Integer.class)
                .single();
        if (known == 0) {
            throw ApiException.notFound();
        }
        return jdbc.sql("SELECT status, last_checked_at, failure_reason FROM app_payment_account_status(?)")
                .param(tenantId)
                .query((rs, n) -> new Status(
                        "verified".equals(rs.getString("status")) || "failed".equals(rs.getString("status"))
                                ? rs.getString("status")
                                : "not_connected",
                        rs.getTimestamp("last_checked_at") == null
                                ? null
                                : rs.getTimestamp("last_checked_at").toInstant(),
                        rs.getString("failure_reason")))
                .optional()
                .orElse(new Status("not_connected", null, null));
    }
}
