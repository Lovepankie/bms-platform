package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SmsEvents} on the outbox ({@link JdbcOutbox}). Every public operation that writes or looks anything up for a
 * message runs, whole, inside a savepoint of the caller's transaction ({@link #guarded}): a failure of any kind, in a
 * lookup that went through a transactional proxy or in a statement that aborted the PostgreSQL transaction, rolls back to
 * the savepoint (which also clears the rollback-only mark a proxy leaves), is logged without a number or a value, and the
 * money event that called goes on. Nothing here talks to the provider.
 */
@Service
class SmsEventsService implements SmsEvents {

    private static final Logger log = LoggerFactory.getLogger(SmsEventsService.class);
    private static final Duration RECEIPT_LIFETIME = Duration.ofHours(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private final Outbox outbox;
    private final JdbcClient jdbc;
    private final SmsTenantConfig config;
    private final CurrentTenant tenant;
    private final TenantSettings settings;
    private final BusinessClock clock;
    private final TransactionTemplate savepoint;
    private final int maxAgeDays;

    SmsEventsService(
            Outbox outbox,
            JdbcClient jdbc,
            SmsTenantConfig config,
            CurrentTenant tenant,
            TenantSettings settings,
            BusinessClock clock,
            PlatformTransactionManager transactionManager,
            @Value("${bms.sms.event-max-age-days:3}") int maxAgeDays) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.config = config;
        this.tenant = tenant;
        this.settings = settings;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.maxAgeDays = Math.max(0, maxAgeDays);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean active() {
        Optional<Integer> operatorCap = jdbc.sql("SELECT daily_cap FROM sms_tenant_control_current() WHERE enabled")
                .query(Integer.class)
                .optional();
        if (operatorCap.isEmpty() || operatorCap.get() == 0) {
            return false;
        }
        SmsTenantConfig.Config own = config.current();
        return own.enabled()
                && !Integer.valueOf(0).equals(own.dailyCap())
                && !Integer.valueOf(0).equals(own.monthlyCap());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean includeBalance() {
        return settings.smsIncludeBalance();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String amount(long minor, String currency) {
        int exponent = jdbc.sql("SELECT exponent FROM currencies WHERE code = ?")
                .param(currency)
                .query(Integer.class)
                .single();
        return format(minor, exponent);
    }

    /** {@code 1234567} with exponent 0 is {@code 1,234,567}; with exponent 2, {@code 12,345.67}. */
    static String format(long minor, int exponent) {
        return String.format(Locale.ROOT, "%,." + exponent + "f", BigDecimal.valueOf(minor, exponent));
    }

    @Override
    public String date(LocalDate date) {
        return DATE.format(date);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result queue(MemberSms message) {
        return queueTracked(message).result();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Queued queueTracked(MemberSms message) {
        return guarded("queue " + message.templateKey(), new Queued(Result.FAILED, null), () -> write(message));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result queueWhenActive(
            String templateKey,
            UUID memberId,
            String eventRef,
            String category,
            LocalDate valueDate,
            Instant expiresAt,
            Supplier<String> phone,
            Supplier<Map<String, String>> params) {
        return guarded("queue " + templateKey, Result.FAILED, () -> {
            if (!active()) {
                return Result.NOT_QUEUED;
            }
            if (tooOld(valueDate)) {
                return Result.TOO_OLD;
            }
            return write(new MemberSms(
                            templateKey, memberId, phone.get(), params.get(), eventRef, category, valueDate, expiresAt))
                    .result();
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancel(String eventRef) {
        return guarded("cancel", 0, () -> outbox.cancelUnclaimed(eventRef));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelSettled(String eventRef) {
        return guarded("cancel", 0, () -> outbox.cancelUnclaimed(eventRef, "cancelled_settled"));
    }

    /**
     * The row is found by the id {@link #queueTracked} answered: still pending, it has not reached the provider;
     * otherwise the delivery log (row-level security keeps it to the bound tenant) says what the provider did, and no
     * delivery row means it never got there. JUSTIFICATION-A3: a new read on the port.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TextStatus status(UUID row) {
        if (row == null) {
            return TextStatus.NOT_SENT;
        }
        return guarded("status", TextStatus.PENDING, () -> {
            if (Boolean.TRUE.equals(jdbc.sql("SELECT notification_outbox_is_pending(?)")
                    .param(row)
                    .query(Boolean.class)
                    .single())) {
                return TextStatus.PENDING;
            }
            // At most one row: UNIQUE (tenant_id, outbox_id) and the tenant binding (SmsEventsIT pins it).
            return jdbc.sql("SELECT status FROM sms_deliveries WHERE outbox_id = ?")
                    .param(row)
                    .query(String.class)
                    .optional()
                    .map(SmsEventsService::textStatus)
                    .orElse(TextStatus.NOT_SENT);
        });
    }

    /**
     * A delivery log status as the port says it: {@code queued} is a send in flight, {@code unknown} may or may not have
     * gone. One row per outbox row: {@code UNIQUE (tenant_id, outbox_id)} (V50), a retry updates it.
     */
    static TextStatus textStatus(String delivery) {
        return switch (delivery) {
            case "queued" -> TextStatus.PENDING;
            case "sent", "delivered", "sunk" -> TextStatus.SENT;
            case "unknown" -> TextStatus.UNCONFIRMED;
            default -> TextStatus.FAILED;
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String plainTenantName(int maxLength) {
        return plainName(tenant.profile().name(), maxLength);
    }

    /** See {@link SmsEvents#plainTenantName}. Pure, so a test pins it; an empty result reads {@code BMS}. */
    static String plainName(String name, int maxLength) {
        StringBuilder out = new StringBuilder();
        for (char c : (name == null ? "" : name).toCharArray()) {
            // The dashes and quotes by code point (hyphens, figure, en and em dashes, the bar, minus; curly quotes), so
            // this file carries none of them.
            char k = c == '-' || (c >= 0x2010 && c <= 0x2015) || c == 0x2212 || Character.isWhitespace(c)
                    ? ' '
                    : c == 0x2018 || c == 0x2019 || c == 0x02bc ? '\'' : c == 0x201c || c == 0x201d ? '"' : c;
            if (SmsSegments.basic(k)) {
                out.append(k);
            }
        }
        String plain = out.toString().replaceAll(" {2,}", " ").strip();
        if (plain.length() > maxLength) {
            int cut = plain.lastIndexOf(' ', maxLength);
            plain = (cut > maxLength / 2 ? plain.substring(0, cut) : plain.substring(0, maxLength)).strip();
        }
        return plain.isEmpty() ? "BMS" : plain;
    }

    /** Runs {@code body} in a savepoint; on any failure rolls back to it, logs the label and the error class, answers {@code onFailure}. */
    private <T> T guarded(String label, T onFailure, Supplier<T> body) {
        try {
            T result = savepoint.execute(tx -> body.get());
            return result == null ? onFailure : result;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError error) {
                throw error;
            }
            // The event that called is a money event; its message is not worth it. No number, no text, no values.
            log.warn(
                    "SMS event step '{}' failed and was skipped: {}",
                    label,
                    e.getClass().getSimpleName());
            return onFailure;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean withinCutoff(LocalDate valueDate) {
        return guarded("cutoff", Boolean.TRUE, () -> !tooOld(valueDate));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> auditNote(boolean notifyMember, LocalDate valueDate) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("notify_member", notifyMember);
        note.put("sms_within_cutoff", withinCutoff(valueDate));
        return note;
    }

    private boolean tooOld(LocalDate valueDate) {
        return valueDate != null
                && valueDate.isBefore(clock.today(tenant.profile().timezone()).minusDays(maxAgeDays));
    }

    private Queued write(MemberSms message) {
        Optional<String> to = PhoneNumbers.forSms(message.phone());
        if (to.isEmpty()) {
            return new Queued(Result.NO_PHONE, null);
        }
        CurrentTenant.Profile profile = tenant.profile();
        Map<String, String> params = new LinkedHashMap<>(message.params());
        params.putIfAbsent("tenant_name", profile.name());
        if (!settings.smsIncludeBalance()) {
            params.remove("balance");
        }
        String key = key(message.templateKey(), profile.id(), message.eventRef(), message.memberId());
        boolean receipt = Outbox.TRANSACTIONAL.equals(message.category());
        // A transactional text lives an hour, or less when its subject ends sooner (a payment link dies with its
        // intent).
        Instant receiptExpiry = clock.now().plus(RECEIPT_LIFETIME);
        if (message.expiresAt() != null && message.expiresAt().isBefore(receiptExpiry)) {
            receiptExpiry = message.expiresAt();
        }
        Outbox.Enqueued queued = outbox.enqueueTracked(new Outbox.Message(
                Outbox.SMS,
                to.get(),
                message.templateKey(),
                params,
                key,
                null,
                receipt ? receiptExpiry : message.expiresAt(),
                message.category(),
                Outbox.Subject.member(message.memberId()),
                null));
        return queued.written() ? new Queued(Result.QUEUED, queued.id()) : new Queued(Result.NOT_QUEUED, null);
    }

    /** The idempotency key: the same event for the same member in the same tenant is one row, ever. */
    static String key(String templateKey, UUID tenantId, String eventRef, UUID memberId) {
        return "sms:" + templateKey + ":" + tenantId + ":" + eventRef + ":" + memberId;
    }
}
