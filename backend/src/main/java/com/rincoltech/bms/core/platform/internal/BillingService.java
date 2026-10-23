package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.platform.internal.BillingAdminController.DueForSuspension;
import com.rincoltech.bms.core.platform.internal.BillingAdminController.SettingsRequest;
import com.rincoltech.bms.core.platform.internal.BillingAdminController.SettingsResponse;
import com.rincoltech.bms.core.platform.internal.BillingController.BillingNotice;
import com.rincoltech.bms.core.platform.internal.BillingController.BillingPayment;
import com.rincoltech.bms.core.platform.internal.BillingController.BillingView;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.kernel.AmountInWords;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscription billing automation (issue #480, ADR-075). Reminders to the Owner before the last day paid for or the last day of
 * the free month, the move to {@code past_due} (overdue) the day after it in the business's own time zone, the grace period the
 * operator sets, the operator's list of businesses due for suspension, a numbered receipt for every payment recorded and the
 * Owner's Plan and billing page. Nothing here suspends a business: after the grace period it is listed, and the operator
 * suspends it by hand with the existing route. Amounts come from the price book ({@link PriceBookService}); a late payment's
 * paid period starts on the day it is recorded (V75), so what is due is the price of the next month, not the late days.
 */
@Service
class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** Reminders go at the first tick of the job from this time of day in the business's own time zone. */
    static final LocalTime REMINDERS_FROM = LocalTime.of(8, 0);

    static final String RECEIPT_KIND = "subscription_receipt";

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** The colour of the house letterhead: the platform's, as in its emails. */
    private static final String HOUSE_COLOUR = "#0D5C75";

    private static final String HOUSE_TINT = "#E2EBEE";

    private static final Map<String, String> METHODS = Map.of(
            "mtn_momo", "MTN Mobile Money",
            "airtel_money", "Airtel Money",
            "bank_transfer", "Bank transfer",
            "other", "Other");

    private final JdbcClient jdbc;
    private final PlatformService platform;
    private final PriceBookService priceBook;
    private final PlatformAuditLog audit;
    private final BusinessClock clock;
    private final Outbox outbox;
    private final Documents documents;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final boolean smsEnabled;
    private final Issuer issuer;

    /** Rincol Tech as the receipt shows it; the address and contact are environment values, never repository content. */
    record Issuer(String name, String address, String contact, String taxId) {}

    BillingService(
            JdbcClient jdbc,
            PlatformService platform,
            PriceBookService priceBook,
            PlatformAuditLog audit,
            BusinessClock clock,
            Outbox outbox,
            Documents documents,
            PlatformTransactionManager transactionManager,
            @Value("${bms.platform.billing.sms-enabled:false}") boolean smsEnabled,
            @Value("${bms.platform.billing.issuer-name:Rincol Tech}") String issuerName,
            @Value("${bms.platform.billing.issuer-address:}") String issuerAddress,
            @Value("${bms.platform.billing.issuer-contact:}") String issuerContact,
            @Value("${bms.platform.billing.issuer-tax-id:}") String issuerTaxId) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.priceBook = priceBook;
        this.audit = audit;
        this.clock = clock;
        this.outbox = outbox;
        this.documents = documents;
        this.transactions = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.smsEnabled = smsEnabled;
        this.issuer = new Issuer(issuerName, issuerAddress, issuerContact, issuerTaxId);
    }

    record Settings(int graceDays, String howToPay) {}

    @Transactional(readOnly = true)
    Settings settings() {
        return jdbc.sql("SELECT grace_days, how_to_pay FROM platform_billing_settings WHERE id = 1")
                .query((rs, n) -> new Settings(rs.getInt("grace_days"), rs.getString("how_to_pay")))
                .single();
    }

    @Transactional(readOnly = true)
    SettingsResponse settingsResponse() {
        return jdbc.sql("""
                        SELECT s.grace_days, s.how_to_pay, s.updated_at, u.full_name AS updated_by_name
                          FROM platform_billing_settings s
                          LEFT JOIN platform_users u ON u.id = s.updated_by_platform_user_id
                         WHERE s.id = 1
                        """)
                .query((rs, n) -> new SettingsResponse(
                        rs.getInt("grace_days"),
                        rs.getString("how_to_pay"),
                        PlatformService.instant(rs.getTimestamp("updated_at")),
                        rs.getString("updated_by_name")))
                .single();
    }

    /**
     * The grace period and the words on how to pay, with a reason; unchanged values change nothing. A request without
     * {@code how_to_pay} keeps the words as they are; an empty one clears them.
     */
    @Transactional
    SettingsResponse saveSettings(SettingsRequest request) {
        Settings before = settings();
        String howToPay =
                request.howToPay() == null ? before.howToPay() : PriceBookService.blankToNull(request.howToPay());
        if (before.graceDays() == request.graceDays() && Objects.equals(before.howToPay(), howToPay)) {
            return settingsResponse();
        }
        UUID operator = CurrentPrincipal.require().userId();
        jdbc.sql("SELECT * FROM platform_set_billing_settings(?, ?, ?)")
                .params(request.graceDays(), howToPay, operator)
                .query()
                .singleRow();
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (before.graceDays() != request.graceDays()) {
            was.put("grace_days", before.graceDays());
            now.put("grace_days", request.graceDays());
        }
        if (!Objects.equals(before.howToPay(), howToPay)) {
            was.put("how_to_pay", before.howToPay());
            now.put("how_to_pay", howToPay);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", was);
        data.put("after", now);
        data.put("reason", request.reason().trim());
        audit.record("platform.billing.settings_changed", operator, null, data);
        return settingsResponse();
    }

    // ---------------------------------------------------------------------------------------------
    // Dates and states, one rule for the job, the list, the page and the banner.
    // ---------------------------------------------------------------------------------------------

    /**
     * The day a subscription is paid up to: the last day of the free month while it is in its free month, otherwise the last
     * day paid for (a business that went overdue in its free month has none, so its free month's last day). Null when there
     * is none (nobody has signed in yet, nothing paid, a pilot).
     */
    static LocalDate dueOn(String status, LocalDate trialEndsOn, LocalDate paidUntil) {
        if ("trial".equals(status)) {
            return trialEndsOn;
        }
        return paidUntil != null ? paidUntil : trialEndsOn;
    }

    /**
     * What the Owner's page and the banner call the subscription: trial, active, in_grace, overdue (past the grace period, on
     * the operator's list), suspended, cancelled or pilot.
     */
    static String state(String status, boolean pilot, LocalDate due, LocalDate today, int graceDays) {
        if (status == null) {
            return "active";
        }
        if (pilot && (status.equals("trial") || status.equals("active"))) {
            return "pilot";
        }
        if (!status.equals("past_due")) {
            return status;
        }
        return due != null && !today.isAfter(due.plusDays(graceDays)) ? "in_grace" : "overdue";
    }

    /** The reminder due on {@code today}, or null: 7 days before, 1 day before and on the day. */
    static String reminderKind(boolean trial, long daysLeft) {
        String prefix = trial ? "trial_" : "due_";
        if (daysLeft == 7) {
            return prefix + "in_7";
        }
        if (daysLeft == 1) {
            return prefix + "in_1";
        }
        if (daysLeft == 0) {
            return prefix + "today";
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // The job (BillingJobs).
    // ---------------------------------------------------------------------------------------------

    record RunResult(int remindersQueued, int markedOverdue, int failures) {}

    /**
     * One pass over every business in its free month or paid: the one overdue the day after its last day becomes past_due
     * (audited by the database function), and the one whose reminder falls today gets it, at most once. A pilot, a suspended
     * or cancelled business, a business already overdue, and one with a status change the operator scheduled (an end chosen
     * for the end of its period) get nothing: no reminder, and the database function never makes it overdue or touches its
     * date. One business's failure is logged and does not stop the others.
     */
    RunResult run() {
        return run(null);
    }

    /** As {@link #run()} for these businesses only, or every one when null: the integration tests share one database. */
    RunResult run(java.util.Set<UUID> only) {
        Settings settings = settings();
        Instant now = clock.now();
        int queued = 0;
        int overdue = 0;
        int failures = 0;
        for (TenantResponse tenant : platform.tenants(null, List.of("trial", "active"), null)) {
            if (tenant.pilot()
                    || !"active".equals(tenant.status())
                    || tenant.nextStatusChangeOn() != null
                    || (only != null && !only.contains(tenant.id()))) {
                continue;
            }
            try {
                ZoneId zone = ZoneId.of(tenant.timezone());
                LocalDate today = clock.today(zone);
                boolean trial = "trial".equals(tenant.subscriptionStatus());
                LocalDate due = trial ? tenant.trialEndsOn() : tenant.paidUntil();
                if (due == null) {
                    continue;
                }
                if (today.isAfter(due)) {
                    if (markOverdue(tenant.id(), due, today)) {
                        overdue++;
                    }
                    continue;
                }
                String kind = reminderKind(trial, ChronoUnit.DAYS.between(today, due));
                if (kind == null || now.atZone(zone).toLocalTime().isBefore(REMINDERS_FROM)) {
                    continue;
                }
                if (remind(tenant, kind, due, settings)) {
                    queued++;
                }
            } catch (RuntimeException e) {
                failures++;
                log.error("billing run failed for tenant {}", tenant.id(), e);
            }
        }
        return new RunResult(queued, overdue, failures);
    }

    private boolean markOverdue(UUID tenantId, LocalDate due, LocalDate today) {
        return Boolean.TRUE.equals(
                transactions.execute(status -> jdbc.sql("SELECT platform_mark_subscription_overdue(?, ?, ?)")
                        .params(tenantId, due, today)
                        .query(Boolean.class)
                        .single()));
    }

    record Owner(String fullName, String email, String phone) {}

    /** The Owner of the bound business (ADR-062; invited or active, never deactivated), or empty while it has none. */
    private Optional<Owner> owner() {
        return jdbc.sql("""
                        SELECT full_name, email, phone_e164 FROM users
                         WHERE is_owner AND kind = 'staff' AND status <> 'deactivated'
                        """)
                .query((rs, n) ->
                        new Owner(rs.getString("full_name"), rs.getString("email"), rs.getString("phone_e164")))
                .optional();
    }

    /**
     * Queues one reminder for the business, once: the row in {@code subscription_reminders} and the outbox rows commit
     * together, and a second pass finds the row and queues nothing. Email to the Owner; SMS too only when the platform's
     * billing SMS is switched on ({@code bms.platform.billing.sms-enabled}) and the Owner has a phone. A business with no
     * Owner gets the row with no channel, so the operator sees in its outbox that nothing went.
     */
    private boolean remind(TenantResponse tenant, String kind, LocalDate due, Settings settings) {
        PriceBookController.PlanPriceResponse price = priceBook
                .priceOn(tenant.planCode(), tenant.currency(), due.plusDays(1))
                .orElse(null);
        Long amount = price == null ? null : price.monthlyMinor();
        String amountLine = price == null
                ? "We will tell you the amount."
                : "The price is " + BusinessService.money(tenant.currency(), price.monthlyMinor()) + " a month, or "
                        + BusinessService.money(tenant.currency(), price.annualMinor()) + " for a year.";
        return Boolean.TRUE.equals(TenantContext.callAs(
                tenant.id(),
                () -> transactions.execute(status -> {
                    if (!stillDue(due)) {
                        return false;
                    }
                    Optional<Owner> owner = owner();
                    List<String> channels = new ArrayList<>();
                    if (owner.isPresent() && owner.get().email() != null) {
                        channels.add(Outbox.EMAIL);
                    }
                    if (smsEnabled && owner.isPresent() && owner.get().phone() != null) {
                        channels.add(Outbox.SMS);
                    }
                    boolean inserted = jdbc.sql("""
                            INSERT INTO subscription_reminders (id, tenant_id, kind, due_on, amount_minor, currency, channels)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, kind, due_on) DO NOTHING
                            """)
                                    .params(
                                            UUID.randomUUID(),
                                            kind,
                                            due,
                                            amount,
                                            amount == null ? null : tenant.currency(),
                                            channels.toArray(String[]::new))
                                    .update()
                            == 1;
                    if (!inserted || channels.isEmpty()) {
                        return false;
                    }
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("owner_name", owner.get().fullName());
                    params.put("business_name", tenant.name());
                    params.put("tenant_name", tenant.name());
                    params.put("due_date", DATE.format(due));
                    params.put("amount_line", amountLine);
                    params.put("how_to_pay", howToPay(settings));
                    params.put("link", tenant.address() + "/staff/setup/billing");
                    String template = "billing." + kind;
                    String key = template + ":" + tenant.id() + ":" + due;
                    Instant expires = clock.now().plus(java.time.Duration.ofDays(2));
                    if (channels.contains(Outbox.EMAIL)) {
                        outbox.enqueue(new Outbox.Message(
                                Outbox.EMAIL,
                                owner.get().email(),
                                template,
                                params,
                                key,
                                "billing:" + tenant.id(),
                                expires));
                    }
                    if (channels.contains(Outbox.SMS)) {
                        outbox.enqueue(new Outbox.Message(
                                Outbox.SMS,
                                owner.get().phone(),
                                template,
                                Map.of("tenant_name", tenant.name(), "due_date", DATE.format(due)),
                                key + ":sms",
                                "billing:" + tenant.id(),
                                expires,
                                Outbox.REMINDER,
                                null,
                                null));
                    }
                    return true;
                })));
    }

    /**
     * Read again in the reminder's own transaction: the subscription is still in its free month or paid, with no change
     * scheduled, and its last day is still the one the run saw, so a payment recorded during the run sends no reminder for
     * the old date.
     */
    private boolean stillDue(LocalDate due) {
        return jdbc.sql("""
                        SELECT CASE WHEN status = 'trial' THEN trial_ends_on ELSE current_period_end END
                          FROM subscriptions
                         WHERE status IN ('trial', 'active') AND NOT is_pilot AND next_status_change_on IS NULL
                        """)
                .query(LocalDate.class)
                .optional()
                .filter(due::equals)
                .isPresent();
    }

    private static String howToPay(Settings settings) {
        return settings.howToPay() == null
                ? "Reply to this email or call Rincol Tech, and we will tell you how to pay."
                : settings.howToPay();
    }

    // ---------------------------------------------------------------------------------------------
    // Receipts (BusinessService.recordPayment, the Owner's page, the email attachment).
    // ---------------------------------------------------------------------------------------------

    /**
     * In the payment's own transaction, bound to its tenant: numbers the receipt and queues the email to the Owner with the
     * receipt attached (read when it is sent). Returns the receipt number. A business with no Owner, or an Owner with no
     * email, gets the receipt on its page only.
     */
    String issueReceipt(TenantResponse tenant, PlatformController.PaymentResponse payment, LocalDate today) {
        String receiptNo = jdbc.sql("""
                        INSERT INTO subscription_receipts (id, tenant_id, payment_id, receipt_no, business_name, plan_name,
                                                           issued_on)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?,
                                'RT-' || ? || '-' || lpad(nextval('subscription_receipt_numbers')::text, 6, '0'),
                                left(?, 200), left(?, 200), ?)
                        RETURNING receipt_no
                        """)
                .params(
                        UUID.randomUUID(),
                        payment.id(),
                        String.format(Locale.ROOT, "%04d", today.getYear()),
                        tenant.name(),
                        tenant.planName(),
                        today)
                .query(String.class)
                .single();
        Optional<Owner> owner = owner();
        if (owner.isPresent() && owner.get().email() != null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("owner_name", owner.get().fullName());
            params.put("business_name", tenant.name());
            params.put("receipt_no", receiptNo);
            params.put("amount", BusinessService.money(payment.currency(), payment.amountMinor()));
            params.put("period", DATE.format(payment.periodStart()) + " to " + DATE.format(payment.periodEnd()));
            params.put("link", tenant.address() + "/staff/setup/billing");
            params.put(EmailAttachments.PARAM, RECEIPT_KIND + ":" + payment.id());
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    owner.get().email(),
                    "billing.receipt",
                    params,
                    "billing.receipt:" + payment.id(),
                    "billing:" + tenant.id(),
                    null));
        }
        return receiptNo;
    }

    /** The receipt's PDF for a payment of the bound tenant, or empty when the payment has none. No transaction is held. */
    Optional<Receipt> receiptPdf(UUID paymentId) {
        Optional<Map<String, Object>> model = reads.execute(status -> receiptModel(paymentId));
        return model.map(m -> new Receipt(
                "receipt-" + ((Map<?, ?>) m.get("doc")).get("number") + ".pdf",
                documents.renderPdf("subscription_receipt", m)));
    }

    record Receipt(String fileName, byte[] bytes) {}

    private Optional<Map<String, Object>> receiptModel(UUID paymentId) {
        return jdbc.sql("""
                        SELECT r.receipt_no, r.business_name, r.plan_name, r.issued_on, sp.amount_minor, sp.currency,
                               sp.method, sp.reference, sp.paid_on, sp.months, sp.period_start, sp.period_end
                          FROM subscription_receipts r
                          JOIN subscription_payments sp ON sp.tenant_id = r.tenant_id AND sp.id = r.payment_id
                         WHERE r.payment_id = ?
                        """)
                .param(paymentId)
                .query((rs, n) -> {
                    String currency = rs.getString("currency");
                    long amount = rs.getLong("amount_minor");
                    int months = rs.getInt("months");
                    Map<String, Object> lh = new LinkedHashMap<>();
                    lh.put("name", issuer.name());
                    lh.put("colour", HOUSE_COLOUR);
                    lh.put("onColour", "#FFFFFF");
                    lh.put("tint", HOUSE_TINT);
                    lh.put("logo", null);
                    lh.put(
                            "footer",
                            issuer.name() + (issuer.taxId().isBlank() ? "" : ", TIN " + issuer.taxId())
                                    + ". Provider of BMS.");
                    Map<String, Object> branch = new LinkedHashMap<>();
                    branch.put("name", issuer.address());
                    branch.put("location", issuer.contact().isBlank() ? null : issuer.contact());
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("number", rs.getString("receipt_no"));
                    doc.put("date", DATE.format(rs.getObject("issued_on", LocalDate.class)));
                    doc.put("title", "RECEIPT");
                    Map<String, Object> receipt = new LinkedHashMap<>();
                    receipt.put("business", rs.getString("business_name"));
                    receipt.put("amount", BusinessService.money(currency, amount));
                    receipt.put("amountWords", AmountInWords.of(amount, currency));
                    receipt.put("forWhat", "BMS subscription, " + months + (months == 1 ? " month" : " months"));
                    receipt.put("plan", rs.getString("plan_name"));
                    receipt.put(
                            "period",
                            DATE.format(rs.getObject("period_start", LocalDate.class)) + " to "
                                    + DATE.format(rs.getObject("period_end", LocalDate.class)));
                    receipt.put("paidOn", DATE.format(rs.getObject("paid_on", LocalDate.class)));
                    receipt.put("method", METHODS.getOrDefault(rs.getString("method"), rs.getString("method")));
                    receipt.put("reference", rs.getString("reference"));
                    receipt.put("thanks", "Thank you for your payment.");
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("lh", lh);
                    model.put("branch", branch);
                    model.put("doc", doc);
                    model.put("receipt", receipt);
                    return model;
                })
                .optional();
    }

    // ---------------------------------------------------------------------------------------------
    // The Owner's page and the banner (BillingController), bound to the caller's business.
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    BillingView view() {
        TenantResponse tenant = platform.tenant(TenantContext.require());
        Settings settings = settings();
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        LocalDate due = dueOn(tenant.subscriptionStatus(), tenant.trialEndsOn(), tenant.paidUntil());
        String state = state(tenant.subscriptionStatus(), tenant.pilot(), due, today, settings.graceDays());
        boolean late = state.equals("in_grace") || state.equals("overdue");
        PriceBookController.PlanPriceResponse price =
                priceBook.priceOn(tenant.planCode(), tenant.currency(), today).orElse(null);
        List<BillingPayment> payments = jdbc.sql("""
                        SELECT sp.id, sp.amount_minor, sp.currency, sp.method, sp.reference, sp.paid_on, sp.months,
                               sp.period_start, sp.period_end, r.receipt_no
                          FROM subscription_payments sp
                          LEFT JOIN subscription_receipts r ON r.tenant_id = sp.tenant_id AND r.payment_id = sp.id
                         WHERE sp.status = 'confirmed'
                         ORDER BY sp.created_at DESC, sp.id
                        """)
                .query((rs, n) -> new BillingPayment(
                        rs.getObject("id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getString("method"),
                        rs.getString("reference"),
                        rs.getObject("paid_on", LocalDate.class),
                        rs.getInt("months"),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getString("receipt_no")))
                .list();
        return new BillingView(
                tenant.planCode(),
                tenant.planName(),
                tenant.currency(),
                price == null ? null : price.monthlyMinor(),
                price == null ? null : price.annualMonths(),
                price == null ? null : price.annualMinor(),
                state,
                tenant.trialEndsOn(),
                tenant.paidUntil(),
                late && due != null ? due.plusDays(1) : null,
                late && due != null ? due.plusDays(settings.graceDays()) : null,
                settings.graceDays(),
                settings.howToPay(),
                payments);
    }

    /** The banner every signed-in staff user of an overdue business sees; state {@code none} otherwise. */
    @Transactional(readOnly = true)
    BillingNotice notice() {
        TenantResponse tenant = platform.tenant(TenantContext.require());
        if (!"past_due".equals(tenant.subscriptionStatus()) || tenant.pilot()) {
            return new BillingNotice("none", null, null);
        }
        Settings settings = settings();
        LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
        LocalDate due = dueOn(tenant.subscriptionStatus(), tenant.trialEndsOn(), tenant.paidUntil());
        String state = state(tenant.subscriptionStatus(), false, due, today, settings.graceDays());
        return new BillingNotice(
                state, due == null ? null : due.plusDays(1), due == null ? null : due.plusDays(settings.graceDays()));
    }

    // ---------------------------------------------------------------------------------------------
    // The operator's list (BillingAdminController).
    // ---------------------------------------------------------------------------------------------

    /**
     * Every overdue business past its grace period, not a pilot, still active (a business suspended or closed at the tenant
     * level is left off) and with no status change scheduled by the operator, with how many days it is overdue and the price
     * of its next month; the most overdue first. The operator suspends from the Business page, by hand.
     */
    DueForSuspension dueForSuspension() {
        Settings settings = settings();
        List<BillingAdminController.DueItem> items = new ArrayList<>();
        for (TenantResponse tenant : platform.tenants(null, List.of("past_due"), null)) {
            if (tenant.pilot() || !"active".equals(tenant.status()) || tenant.nextStatusChangeOn() != null) {
                continue;
            }
            LocalDate today = clock.today(ZoneId.of(tenant.timezone()));
            LocalDate due = dueOn(tenant.subscriptionStatus(), tenant.trialEndsOn(), tenant.paidUntil());
            if (due == null || !today.isAfter(due.plusDays(settings.graceDays()))) {
                continue;
            }
            PriceBookController.PlanPriceResponse price = priceBook
                    .priceOn(tenant.planCode(), tenant.currency(), today)
                    .orElse(null);
            items.add(new BillingAdminController.DueItem(
                    tenant.id(),
                    tenant.name(),
                    tenant.slug(),
                    tenant.planName(),
                    due,
                    due.plusDays(1),
                    due.plusDays(settings.graceDays()),
                    ChronoUnit.DAYS.between(due, today),
                    price == null ? null : price.monthlyMinor(),
                    tenant.currency(),
                    tenant.ownerName(),
                    tenant.ownerEmail(),
                    tenant.lastSignInAt()));
        }
        items.sort((a, b) -> Long.compare(b.daysOverdue(), a.daysOverdue()));
        return new DueForSuspension(settings.graceDays(), items);
    }
}
