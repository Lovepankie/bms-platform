package com.rincoltech.bms.core.referrals;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Referrals and their commissions (issue #464, ADR-069), for {@code core.onboarding} (the sign-up and Activate) and
 * {@code core.platform} (the payment the operator records, and the operator's routes). Every operator method runs with no
 * tenant bound and writes a {@code platform_audit_log} row with the operator and the reason; {@link #commissionForPayment}
 * runs inside the paying business's transaction.
 */
public interface Referrals {

    /** The business a typed code belongs to, the code in upper case; 422 {@code referral_code_unknown} for none. */
    Code resolve(String code);

    /** Records the referrer on an application just created. */
    void attachToApplication(UUID applicationId, Code code);

    /** The referrer an application carries, for the operator's application page. */
    Optional<ApplicationReferrer> applicationReferrer(UUID applicationId);

    /** Activate: the application's referrer becomes the new business's referral, audited. Empty when it had none. */
    Optional<UUID> recordFromApplication(UUID applicationId, UUID tenantId, UUID operatorId);

    /**
     * The commission of a payment just recorded for the bound business, from the rule in force now; a line is added and
     * audited when the rule gives an amount. Never throws for a business with no referrer or no rule.
     */
    Commission commissionForPayment(UUID paymentId, UUID operatorId);

    /** Referrals and open applications with a code, newest first; filters may be null. */
    List<Referral> list(Filter filter, int limit, int offset);

    /** The same rows as CSV, at most 10,000, audited. */
    String exportCsv(Filter filter, UUID operatorId);

    /** One referral, its rule in force and its lines; 404 when there is none. */
    ReferralDetail detail(UUID referralId);

    /**
     * Sets, corrects or clears (a null code) the referrer of a business, with a reason; 422 {@code referral_self} for the
     * business itself, 422 {@code referral_code_unknown} for an unknown code. The same referrer again changes nothing.
     */
    Optional<Referral> setReferrer(UUID tenantId, String referrerCode, String reason, UUID operatorId);

    /** The platform default rule, or empty while none is set. */
    Optional<Rule> defaultRule();

    /** Sets (or clears, with null) the platform default rule, with a reason. */
    Optional<Rule> setDefaultRule(RuleInput rule, String reason, UUID operatorId);

    /** Sets (or clears, with null: the default applies) the rule of one current referral, with a reason. */
    ReferralDetail setRule(UUID referralId, RuleInput rule, String reason, UUID operatorId);

    /** A line with an amount the operator typed, with a reason. */
    ReferralDetail addLine(UUID referralId, long amountMinor, String currency, String reason, UUID operatorId);

    /** The exact opposite of one line, with a reason; a line is reversed once and a reversal never. */
    ReferralDetail reverseLine(UUID lineId, String reason, UUID operatorId);

    /** Marks one line paid; 409 {@code commission_already_paid} for a line already marked. */
    ReferralDetail markPaid(UUID lineId, Payout payout, UUID operatorId);

    record Code(String code, UUID tenantId) {}

    @Schema(name = "ApplicationReferrer", description = "The business whose referral code the applicant brought")
    record ApplicationReferrer(String code, UUID tenantId, String tenantSlug, String tenantName) {}

    @Schema(name = "ReferralCommissionOutcome")
    record Commission(
            @Schema(
                    description =
                            "added, no_referral, no_rule, not_first_payment, months_used, currency_differs, cap_reached or zero")
            String outcome,

            UUID lineId,
            UUID referralId,
            Long amountMinor,
            String currency) {}

    record Filter(UUID referrerTenantId, String status, String commission) {}

    @Schema(name = "ReferralTotal", description = "One currency: everything earned, what was marked paid, and the rest")
    record Total(String currency, long earnedMinor, long paidMinor, long dueMinor) {}

    @Schema(name = "PlatformReferral")
    record Referral(
            @Schema(description = "Null for an application not activated yet")
            UUID referralId,

            @Schema(description = "The sign-up application, while it is open or when the referral came from one")
            UUID applicationId,

            UUID referrerTenantId,
            String referrerSlug,
            String referrerName,
            String code,

            @Schema(description = "Null while the referred business is an application")
            UUID referredTenantId,

            String referredSlug,
            String referredName,

            @Schema(description = "applied, active, paying or ended")
            String status,

            @Schema(description = "sign_up or operator") String source,

            @Schema(description = "When the business was created, or the application sent")
            Instant joinedAt,

            Instant startedAt,
            Instant endedAt,

            @Schema(description = "True when the operator set a rule for this referral")
            boolean ruleOverride,

            List<Total> totals) {}

    @Schema(name = "ReferralCommissionRule")
    record Rule(
            UUID id,

            @Schema(description = "percent_of_payment, fixed_first_payment or fixed_per_month")
            String kind,

            @Schema(description = "Basis points of each payment: 1000 is 10%")
            Integer percentBp,

            @Schema(description = "Minor units: once (fixed_first_payment) or per paid month (fixed_per_month)")
            Long amountMinor,

            String currency,

            @Schema(description = "The first paid months of the referred business it applies to; null: no end")
            Integer months,

            @Schema(description = "The most a referral earns under a percent rule, in currency; null: no cap")
            Long capMinor,

            String createdByName,
            Instant createdAt) {}

    @Schema(
            name = "ReferralCommissionRuleInput",
            description = "No rate or amount is built in: the operator types them")
    record RuleInput(
            String kind, Integer percentBp, Long amountMinor, String currency, Integer months, Long capMinor) {}

    @Schema(name = "ReferralCommissionLine")
    record Line(
            UUID id,

            @Schema(description = "payment (worked out from a rule), manual (typed) or reversal")
            String kind,

            UUID paymentId,
            UUID reversesLineId,
            UUID reversedByLineId,
            long amountMinor,
            String currency,
            Integer monthsCounted,

            @Schema(description = "The rule as it was when the line was worked out, and the payment it was applied to")
            Map<String, Object> ruleSnapshot,

            String note,
            LocalDate paidOn,
            String paidMethod,
            String paidReference,
            String paidNote,
            Instant paidAt,
            String paidByName,
            String createdByName,
            Instant createdAt) {}

    @Schema(name = "PlatformReferralDetail")
    record ReferralDetail(
            Referral referral,

            @Schema(description = "The rule a payment recorded now would use, or null")
            Rule ruleInForce,

            @Schema(description = "override, default or none")
            String ruleSource,

            @Schema(description = "Oldest first") List<Line> lines) {}

    record Payout(LocalDate paidOn, String method, String reference, String note, String reason) {}
}
