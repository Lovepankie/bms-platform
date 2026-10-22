package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.platform.internal.PlatformController.AdminResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.BusinessDetail;
import com.rincoltech.bms.core.platform.internal.PlatformController.HistoryResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.MessageResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.PaymentRequest;
import com.rincoltech.bms.core.platform.internal.PlatformController.PaymentResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Business page of the operator portal (issue #407, ADR-056): one tenant's overview, its plan, its trial end and paid
 * until dates, and the payments the operator records (ADR-024 decision 4). Every change carries the operator's id and a
 * reason in {@code platform_audit_log}; a request that would change nothing writes nothing. A payment is written inside a
 * transaction bound to that tenant ({@code TenantContext.callAs}), so its row, its Idempotency-Key and the subscription move
 * commit together.
 */
@Service
class BusinessService {

    private final JdbcClient jdbc;
    private final PlatformService platform;
    private final PriceBookService priceBook;
    private final PlatformAuditLog audit;
    private final Idempotency idempotency;
    private final BusinessClock clock;
    private final TransactionTemplate transactions;

    BusinessService(
            JdbcClient jdbc,
            PlatformService platform,
            PriceBookService priceBook,
            PlatformAuditLog audit,
            Idempotency idempotency,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.priceBook = priceBook;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    record Recorded(PaymentResponse payment, boolean replayed) {}

    BusinessDetail overview(UUID tenantId) {
        TenantResponse tenant = transactions.execute(status -> platform.tenant(tenantId));
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        PriceBookController.PlanPriceResponse price =
                priceBook.priceOn(tenant.planCode(), tenant.currency(), today).orElse(null);
        List<AdminResponse> admins = transactions.execute(status -> jdbc.sql("SELECT * FROM platform_tenant_admins(?)")
                .param(tenantId)
                .query((rs, n) -> new AdminResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("status"),
                        rs.getBoolean("mfa_enabled"),
                        PlatformService.instant(rs.getTimestamp("last_login_at")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_owner")))
                .list());
        List<MessageResponse> messages =
                transactions.execute(status -> jdbc.sql("SELECT * FROM platform_tenant_outbox(?, 50)")
                        .param(tenantId)
                        .query((rs, n) -> new MessageResponse(
                                rs.getObject("id", UUID.class),
                                rs.getString("channel"),
                                Masking.lastFour(rs.getString("recipient")),
                                rs.getString("template_key"),
                                rs.getString("status"),
                                rs.getInt("attempts"),
                                maskAddresses(rs.getString("last_error")),
                                rs.getTimestamp("created_at").toInstant(),
                                PlatformService.instant(rs.getTimestamp("sent_at")),
                                PlatformService.instant(rs.getTimestamp("failed_at"))))
                        .list());
        List<HistoryResponse> history = transactions.execute(status -> jdbc.sql("""
                        SELECT a.action, u.full_name AS operator_name, a.occurred_at, a.data ->> 'reason' AS reason,
                               a.data ->> 'before' AS before, a.data ->> 'after' AS after
                          FROM platform_audit_log a
                          LEFT JOIN platform_users u ON u.id = a.platform_user_id
                         WHERE a.tenant_id = ?
                         ORDER BY a.occurred_at DESC, a.id
                         LIMIT 50
                        """)
                .param(tenantId)
                .query((rs, n) -> new HistoryResponse(
                        rs.getString("action"),
                        rs.getString("operator_name"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("reason"),
                        rs.getString("before"),
                        rs.getString("after")))
                .list());
        List<PaymentResponse> payments = TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> jdbc.sql(PAYMENT_SELECT + " ORDER BY sp.created_at DESC, sp.id")
                        .query(BusinessService::payment)
                        .list()));
        return new BusinessDetail(tenant, price, admins, payments, messages, history);
    }

    /** FR-TEN-04: moves the tenant to another active plan; refused in plain words when the tenant would not fit it. */
    @Transactional
    TenantResponse setPlan(UUID tenantId, String planCode, String reason) {
        TenantResponse before = platform.tenant(tenantId);
        if (planCode.equals(before.planCode())) {
            return before;
        }
        String previous;
        try {
            previous = jdbc.sql("SELECT platform_set_tenant_plan(?, ?, ?)")
                    .params(tenantId, planCode, CurrentPrincipal.require().userId())
                    .query(String.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("plan_modules")) {
                throw ApiException.rule(
                        "plan_modules_not_allowed",
                        "That plan does not include every module this business has switched on. Switch the module off"
                                + " first, or choose a plan that includes it.");
            }
            if (message.contains("plan_branches")) {
                throw ApiException.rule(
                        "plan_too_small_branches",
                        "This business has more open shops and stores than that plan allows. Close some first, or choose"
                                + " a bigger plan.");
            }
            if (message.contains("plan_staff")) {
                throw ApiException.rule(
                        "plan_too_small_staff",
                        "This business has more staff than that plan allows. Deactivate some first, or choose a bigger plan.");
            }
            if (message.contains("plan_members")) {
                throw ApiException.rule(
                        "plan_too_small_members",
                        "This business has more active lending members than that plan allows. Close some memberships"
                                + " first, or choose a bigger plan.");
            }
            throw ApiException.rule("plan_unknown", "There is no active plan with that code.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", previous);
        data.put("after", planCode);
        data.put("reason", reason.trim());
        audit.record("platform.tenant.plan_changed", CurrentPrincipal.require().userId(), tenantId, data);
        return platform.tenant(tenantId);
    }

    /** The earliest day a subscription date may be set to: nothing in this product is older. */
    static final LocalDate EARLIEST_DATE = LocalDate.of(2026, 1, 1);

    /**
     * FR-TEN-05: the last day of the free month, or the last day paid for, set by hand with a reason, or cleared. A date is
     * from 2026-01-01 to five years from today; the last day paid for is not before the paid period starts. Unchanged dates
     * change nothing.
     */
    @Transactional
    TenantResponse setDates(UUID tenantId, PlatformController.DatesRequest request) {
        TenantResponse before = platform.tenant(tenantId);
        boolean clearTrial = Boolean.TRUE.equals(request.clearTrialEndsOn());
        boolean clearPaid = Boolean.TRUE.equals(request.clearPaidUntil());
        LocalDate trialEndsOn = clearTrial ? null : request.trialEndsOn();
        LocalDate paidUntil = clearPaid ? null : request.paidUntil();
        if (trialEndsOn == null && paidUntil == null && !clearTrial && !clearPaid) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "trial_ends_on",
                    "required",
                    "Give the new last day of the free month, or the last day paid for, or clear one.")));
        }
        if (before.pilot() && trialEndsOn != null) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "trial_ends_on",
                    "pilot",
                    "A pilot business has no last day of the free month. Unmark it as a pilot first if its free month"
                            + " should end.")));
        }
        LocalDate latest = clock.today(ZoneId.of(before.timezone())).plusYears(5);
        for (Map.Entry<String, LocalDate> date : dates(trialEndsOn, paidUntil).entrySet()) {
            if (date.getValue().isBefore(EARLIEST_DATE) || date.getValue().isAfter(latest)) {
                throw ApiException.validation(List.of(new FieldProblem(
                        date.getKey(), "out_of_range", "Choose a day from 1 Jan 2026 to five years from today.")));
            }
        }
        if (before.paidFrom() != null && paidUntil != null && paidUntil.isBefore(before.paidFrom())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "paid_until",
                    "before_paid_from",
                    "The last day paid for cannot be before the paid period starts.")));
        }
        boolean trialSame = clearTrial
                ? before.trialEndsOn() == null
                : trialEndsOn == null || trialEndsOn.equals(before.trialEndsOn());
        boolean paidSame =
                clearPaid ? before.paidUntil() == null : paidUntil == null || paidUntil.equals(before.paidUntil());
        if (trialSame && paidSame) {
            return before;
        }
        jdbc.sql("SELECT * FROM platform_set_subscription_dates(?, ?, ?, ?, ?, ?)")
                .params(
                        tenantId,
                        trialEndsOn,
                        clearTrial,
                        paidUntil,
                        clearPaid,
                        CurrentPrincipal.require().userId())
                .query()
                .singleRow();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (!trialSame) {
            was.put("trial_ends_on", text(before.trialEndsOn()));
            now.put("trial_ends_on", text(trialEndsOn));
        }
        if (!paidSame) {
            was.put("paid_until", text(before.paidUntil()));
            now.put("paid_until", text(paidUntil));
        }
        data.put("before", was);
        data.put("after", now);
        data.put("reason", request.reason().trim());
        audit.record(
                "platform.tenant.subscription_dates_changed",
                CurrentPrincipal.require().userId(),
                tenantId,
                data);
        return platform.tenant(tenantId);
    }

    private static Map<String, LocalDate> dates(LocalDate trialEndsOn, LocalDate paidUntil) {
        Map<String, LocalDate> out = new LinkedHashMap<>();
        if (trialEndsOn != null) {
            out.put("trial_ends_on", trialEndsOn);
        }
        if (paidUntil != null) {
            out.put("paid_until", paidUntil);
        }
        return out;
    }

    /**
     * ADR-024 decision 6: a pilot (complimentary) business is free with no free month end; marking clears the last day of
     * the free month and any scheduled status change, and the clock never starts. The same mark again changes nothing.
     */
    @Transactional
    TenantResponse setPilot(UUID tenantId, boolean pilot, String reason) {
        TenantResponse before = platform.tenant(tenantId);
        if (before.pilot() == pilot) {
            return before;
        }
        jdbc.sql("SELECT platform_set_pilot(?, ?, ?)")
                .params(tenantId, pilot, CurrentPrincipal.require().userId())
                .query(Boolean.class)
                .single();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", before.pilot());
        data.put("after", pilot);
        data.put("trial_ends_on_before", text(before.trialEndsOn()));
        data.put("next_status_change_on_before", text(before.nextStatusChangeOn()));
        data.put("reason", reason.trim());
        audit.record("platform.tenant.pilot_changed", CurrentPrincipal.require().userId(), tenantId, data);
        return platform.tenant(tenantId);
    }

    /** Masks what looks like an email address or a phone number in a sender's error text (the outbox keeps it raw). */
    static String maskAddresses(String text) {
        if (text == null) {
            return null;
        }
        String masked = java.util.regex.Pattern.compile("[^\\s@<>\"'(),;:]+@[^\\s@<>\"'(),;:]+")
                .matcher(text)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement(Masking.lastFour(m.group())));
        return java.util.regex.Pattern.compile("\\+?\\d[\\d -]{6,}\\d")
                .matcher(masked)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement(Masking.lastFour(m.group())));
    }

    /**
     * ADR-024 decision 4: a payment received, entered by the operator and confirmed at once. It covers {@code months} from
     * the day after what is already covered (the trial, an earlier payment, or today) and makes a trial or past due
     * subscription active. Behind the Idempotency-Key protocol, so a repeated submit records nothing and answers the first.
     */
    Recorded recordPayment(UUID tenantId, String idempotencyKey, PaymentRequest request) {
        TenantResponse tenant = transactions.execute(status -> platform.tenant(tenantId));
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        if (request.paidOn().isAfter(today)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("paid_on", "in_the_future", "The payment date cannot be after today.")));
        }
        if (request.paidOn().isBefore(today.minusYears(1))) {
            throw ApiException.validation(List.of(
                    new FieldProblem("paid_on", "too_old", "The payment date is more than a year ago; check it.")));
        }
        if (!Boolean.TRUE.equals(request.confirmUnusualAmount())) {
            checkUsualAmount(tenant, request, today);
        }
        UUID operator = CurrentPrincipal.require().userId();
        String path = "/api/v1/platform/tenants/" + tenantId + "/payments";
        Idempotency.Outcome<PaymentResponse> outcome = TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status ->
                        idempotency.once(idempotencyKey, "POST", path, request, PaymentResponse.class, () -> {
                            UUID id = UUID.randomUUID();
                            jdbc.sql("SELECT * FROM platform_record_subscription_payment(?, ?, ?, ?, ?, ?, ?, ?, ?)")
                                    .params(
                                            id,
                                            request.amountMinor(),
                                            request.method(),
                                            PriceBookService.blankToNull(request.reference()),
                                            request.paidOn(),
                                            request.months(),
                                            PriceBookService.blankToNull(request.note()),
                                            today,
                                            operator)
                                    .query()
                                    .singleRow();
                            PaymentResponse payment = jdbc.sql(PAYMENT_SELECT + " WHERE sp.id = ?")
                                    .param(id)
                                    .query(BusinessService::payment)
                                    .single();
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("payment_id", id);
                            data.put("amount_minor", payment.amountMinor());
                            data.put("currency", payment.currency());
                            data.put("method", payment.method());
                            data.put("months", payment.months());
                            data.put("period_start", payment.periodStart().toString());
                            data.put("period_end", payment.periodEnd().toString());
                            data.put("before", tenant.subscriptionStatus());
                            data.put("reason", request.reason().trim());
                            audit.record("platform.tenant.payment_recorded", operator, tenantId, data);
                            return payment;
                        })));
        return new Recorded(outcome.body(), outcome.replayed());
    }

    // JUSTIFICATION-A3: the review of #413 asked for this new check (point 3); no existing code compares a payment with
    // the price.
    /** The price of {@code months} on a plan: each whole year at the yearly price, the months left at the monthly one. */
    static long expectedMinor(PriceBookController.PlanPriceResponse price, int months) {
        return (months / 12) * price.annualMinor() + (months % 12) * price.monthlyMinor();
    }

    /**
     * An amount more than 25% away from the plan's price for these months, or a plan with no price entered, is refused
     * until the operator confirms it (a slip of a zero, or the wrong business): 422 {@code payment_amount_unusual}.
     */
    private void checkUsualAmount(TenantResponse tenant, PaymentRequest request, LocalDate today) {
        String currency = tenant.currency();
        PriceBookController.PlanPriceResponse price =
                priceBook.priceOn(tenant.planCode(), currency, today).orElse(null);
        if (price == null) {
            throw ApiException.rule(
                    "payment_amount_unusual",
                    "No price is entered for the plan of this business, so the amount cannot be checked. Check it, then"
                            + " confirm it.");
        }
        long expected = expectedMinor(price, request.months());
        long amount = request.amountMinor();
        if (Math.abs(amount - expected) * 4 > expected) {
            throw ApiException.rule(
                    "payment_amount_unusual",
                    "The plan's price for " + request.months() + (request.months() == 1 ? " month" : " months") + " is "
                            + money(currency, expected) + ", and this payment is " + money(currency, amount)
                            + ". That is more than a quarter away. Check it, then confirm it.");
        }
    }

    private static final String PAYMENT_SELECT = """
            SELECT sp.id, sp.status, sp.amount_minor, sp.currency, sp.method, sp.reference, sp.paid_on, sp.months,
                   sp.period_start, sp.period_end, sp.note, sp.created_at, u.full_name AS recorded_by_name
              FROM subscription_payments sp
              LEFT JOIN platform_users u ON u.id = sp.recorded_by_platform_user_id
            """;

    private static PaymentResponse payment(ResultSet rs, int n) throws SQLException {
        return new PaymentResponse(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getString("reference"),
                rs.getObject("paid_on", LocalDate.class),
                rs.getInt("months"),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getString("note"),
                rs.getString("recorded_by_name"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * An amount as people write it: "UGX 30,000". UGX has no minor unit, so its minor units are whole shillings; any other
     * currency is shown with two decimals.
     */
    static String money(String currency, long minor) {
        if ("UGX".equals(currency)) {
            return String.format(Locale.ROOT, "UGX %,d", minor);
        }
        return String.format(Locale.ROOT, "%s %,.2f", currency, minor / 100.0);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
