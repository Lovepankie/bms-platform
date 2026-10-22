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
        return guarded("queue " + message.templateKey(), Result.FAILED, () -> write(message));
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
                    templateKey, memberId, phone.get(), params.get(), eventRef, category, valueDate, expiresAt));
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

    private Result write(MemberSms message) {
        Optional<String> to = PhoneNumbers.forSms(message.phone());
        if (to.isEmpty()) {
            return Result.NO_PHONE;
        }
        CurrentTenant.Profile profile = tenant.profile();
        Map<String, String> params = new LinkedHashMap<>(message.params());
        params.putIfAbsent("tenant_name", profile.name());
        if (!settings.smsIncludeBalance()) {
            params.remove("balance");
        }
        String key = key(message.templateKey(), profile.id(), message.eventRef(), message.memberId());
        boolean receipt = Outbox.TRANSACTIONAL.equals(message.category());
        boolean queued = outbox.enqueue(new Outbox.Message(
                Outbox.SMS,
                to.get(),
                message.templateKey(),
                params,
                key,
                null,
                receipt ? clock.now().plus(RECEIPT_LIFETIME) : message.expiresAt(),
                message.category(),
                Outbox.Subject.member(message.memberId()),
                null));
        return queued ? Result.QUEUED : Result.NOT_QUEUED;
    }

    /** The idempotency key: the same event for the same member in the same tenant is one row, ever. */
    static String key(String templateKey, UUID tenantId, String eventRef, UUID memberId) {
        return "sms:" + templateKey + ":" + tenantId + ":" + eventRef + ":" + memberId;
    }
}
