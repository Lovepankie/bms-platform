package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.referrals.Referrals;
import com.rincoltech.bms.core.referrals.Referrals.Payout;
import com.rincoltech.bms.core.referrals.Referrals.Referral;
import com.rincoltech.bms.core.referrals.Referrals.ReferralDetail;
import com.rincoltech.bms.core.referrals.Referrals.Rule;
import com.rincoltech.bms.core.referrals.Referrals.RuleInput;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
 * {@code /api/v1/platform/referrals} and the referrer of a tenant (chapter 7 section 7.11.3, issue #464, ADR-069): who referred
 * whom, the commission rule (the platform default and an override per referral) and the commission lines, for platform
 * operators only, through the public port of {@code core.referrals}. Every change takes a reason and is audited; the app
 * records commissions and never pays them.
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class ReferralsAdminController {

    private final Referrals referrals;

    ReferralsAdminController(Referrals referrals) {
        this.referrals = referrals;
    }

    @Schema(name = "PlatformReferralList")
    record ReferralList(List<Referral> items) {}

    @Schema(name = "TenantReferrerRequest")
    record ReferrerRequest(
            @Size(max = 20)
            @Schema(description = "The referral code of the business that referred this one; null or empty clears it")
            String referrerCode,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the platform audit log")
            String reason) {}

    @Schema(name = "TenantReferrer")
    record ReferrerResponse(
            UUID tenantId,

            @Schema(description = "The current referral of the business, or null when it has no referrer")
            Referral referral) {}

    @Schema(name = "ReferralDefaultRule")
    record DefaultRuleResponse(
            @Schema(description = "Null while no rule is set: nothing is worked out")
            Rule rule) {}

    @Schema(name = "ReferralRuleRequest")
    record RuleRequest(
            @Schema(
                    description =
                            "The rule; null clears it (the default: nothing is worked out; a referral: the default)")
            @Valid
            RuleBody rule,

            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "ReferralRuleBody")
    record RuleBody(
            @NotBlank @Pattern(regexp = "percent_of_payment|fixed_first_payment|fixed_per_month")
            String kind,

            @Schema(description = "percent_of_payment: basis points, 1000 is 10%")
            Integer percentBp,

            @Schema(description = "The fixed amount in minor units")
            Long amountMinor,

            @Pattern(regexp = "^[A-Za-z]{3}$") String currency,

            @Schema(description = "The first paid months it applies to; null: no end")
            Integer months,

            @Schema(description = "percent_of_payment: the most one referral earns; null: no cap")
            Long capMinor) {}

    @Schema(name = "ReferralLineRequest", description = "A commission the operator works out and types")
    record LineRequest(
            @NotNull @Min(1) @Max(1_000_000_000L) Long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept on the line and in the audit log")
            String reason) {}

    @Schema(name = "ReferralLineReverseRequest")
    record ReverseRequest(
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @Schema(name = "ReferralLinePaidRequest", description = "The commission was paid outside the app; this records it")
    record PaidRequest(
            @NotNull LocalDate paidOn,

            @NotBlank @Pattern(regexp = "mtn_momo|airtel_money|bank_transfer|cash|other")
            String method,

            @Size(max = 100) String reference,
            @Size(max = 500) String note,
            @NotBlank @Size(min = 5, max = 300) String reason) {}

    @GetMapping("/referrals")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Referrals across the platform and open applications with a code, newest first; filters:"
                    + " referrer_tenant_id, status (applied, active, paying, ended), commission (due, paid)",
            operationId = "platformListReferrals")
    ReferralList list(
            @RequestParam(name = "referrer_tenant_id", required = false) UUID referrer,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "commission", required = false) String commission,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return new ReferralList(referrals.list(
                filter(referrer, status, commission),
                limit == null ? 100 : Math.clamp(limit, 1, 500),
                offset == null ? 0 : Math.max(offset, 0)));
    }

    @GetMapping(path = "/referrals/export", produces = "text/csv")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "The same referrals as CSV, one row per referral and currency, at most 10,000; audited",
            operationId = "platformExportReferrals")
    ResponseEntity<String> export(
            @RequestParam(name = "referrer_tenant_id", required = false) UUID referrer,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "commission", required = false) String commission) {
        String csv = referrals.exportCsv(
                filter(referrer, status, commission), CurrentPrincipal.require().userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"referrals.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    @GetMapping("/referrals/{referral_id}")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "One referral, the rule a payment recorded now would use, and its commission lines",
            operationId = "platformGetReferral")
    ReferralDetail get(@PathVariable("referral_id") UUID referralId) {
        return referrals.detail(referralId);
    }

    @PutMapping("/tenants/{tenant_id}/referrer")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Set, correct or clear the business that referred this one, with a reason; 422 referral_self and"
                    + " referral_code_unknown; lines already earned stay with their referrer; the same referrer again"
                    + " changes nothing",
            operationId = "platformSetTenantReferrer")
    ReferrerResponse setReferrer(
            @PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody ReferrerRequest request) {
        return new ReferrerResponse(
                tenantId,
                referrals
                        .setReferrer(
                                tenantId,
                                request.referrerCode(),
                                request.reason(),
                                CurrentPrincipal.require().userId())
                        .orElse(null));
    }

    @GetMapping("/referrals/default-rule")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "The platform default commission rule, or null",
            operationId = "platformGetReferralDefaultRule")
    DefaultRuleResponse defaultRule() {
        return new DefaultRuleResponse(referrals.defaultRule().orElse(null));
    }

    @PutMapping("/referrals/default-rule")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Set or clear the platform default commission rule, with a reason; it applies to payments recorded"
                            + " from now on",
            operationId = "platformSetReferralDefaultRule")
    DefaultRuleResponse setDefaultRule(@Valid @RequestBody RuleRequest request) {
        return new DefaultRuleResponse(referrals
                .setDefaultRule(
                        input(request.rule()),
                        request.reason(),
                        CurrentPrincipal.require().userId())
                .orElse(null));
    }

    @PutMapping("/referrals/{referral_id}/rule")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Set this referral's own commission rule, or clear it so the default applies, with a reason;"
                    + " 422 referral_ended",
            operationId = "platformSetReferralRule")
    ReferralDetail setRule(@PathVariable("referral_id") UUID referralId, @Valid @RequestBody RuleRequest request) {
        return referrals.setRule(
                referralId,
                input(request.rule()),
                request.reason(),
                CurrentPrincipal.require().userId());
    }

    @PostMapping("/referrals/{referral_id}/commissions")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Add a commission line with an amount the operator typed, with a reason. Needs an Idempotency-Key; a"
                            + " repeat adds nothing and answers 200",
            operationId = "platformAddReferralCommission")
    ResponseEntity<ReferralDetail> addLine(
            @PathVariable("referral_id") UUID referralId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LineRequest request) {
        Referrals.LineAdded added = referrals.addLine(
                referralId,
                idempotencyKey,
                request.amountMinor(),
                request.currency(),
                request.reason(),
                CurrentPrincipal.require().userId());
        return ResponseEntity.status(added.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(added.detail());
    }

    @PostMapping("/referrals/commissions/{line_id}/reverse")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Reverse one commission line with a reason (lines are never deleted); 409"
                    + " commission_line_already_reversed, 422 commission_line_is_reversal",
            operationId = "platformReverseReferralCommission")
    ReferralDetail reverse(@PathVariable("line_id") UUID lineId, @Valid @RequestBody ReverseRequest request) {
        return referrals.reverseLine(
                lineId, request.reason(), CurrentPrincipal.require().userId());
    }

    @PostMapping("/referrals/commissions/{line_id}/paid")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Record that one commission line was paid (date, method, reference, note), with a reason; the app"
                    + " never pays; 409 commission_already_paid",
            operationId = "platformMarkReferralCommissionPaid")
    ReferralDetail paid(@PathVariable("line_id") UUID lineId, @Valid @RequestBody PaidRequest request) {
        return referrals.markPaid(
                lineId,
                new Payout(request.paidOn(), request.method(), request.reference(), request.note(), request.reason()),
                CurrentPrincipal.require().userId());
    }

    private static Referrals.Filter filter(UUID referrer, String status, String commission) {
        return new Referrals.Filter(
                referrer,
                status == null || status.isBlank() ? null : status,
                commission == null || commission.isBlank() ? null : commission);
    }

    private static RuleInput input(RuleBody body) {
        return body == null
                ? null
                : new RuleInput(
                        body.kind(),
                        body.percentBp(),
                        body.amountMinor(),
                        body.currency(),
                        body.months(),
                        body.capMinor());
    }
}
