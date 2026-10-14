package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The savings receipt SMS of chapter 11 section 11.3.1 ({@code savings.deposit},
 * {@code savings.withdrawal}), queued in the notification outbox in the movement's transaction, so a
 * message exists exactly when the movement commits, once per transaction (idempotency key). Only
 * staff movements send one: the seed and the import never message members. The SMS channel
 * (ADR-036) sends the row according to the environment's mode and the tenant's operator-set switch;
 * with the switch off no row is written at all. A receipt is transactional (never held by the
 * sending window), about the member (so the member's opt-out applies), expires an hour after it is
 * queued, and carries no balance unless the tenant setting {@code sms_include_balance} is on
 * (FR-NTF-17). The phone number is never logged.
 */
@Component
class SavingsNotices {

    static final Duration LIFETIME = Duration.ofHours(1);

    private final Outbox outbox;
    private final MemberLookup members;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final TenantSettings settings;

    SavingsNotices(
            Outbox outbox,
            MemberLookup members,
            CurrentTenant currentTenant,
            BusinessClock clock,
            JdbcClient jdbc,
            TenantSettings settings) {
        this.outbox = outbox;
        this.members = members;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    void receipt(String templateKey, AccountRow a, UUID txnId, long amountMinor, long balanceMinor, String receiptNo) {
        String phone = members.smsPhone(a.memberId()).orElse(null);
        if (phone == null || phone.isBlank()) {
            return;
        }
        CurrentTenant.Profile tenant = currentTenant.profile();
        int exponent = jdbc.sql("SELECT exponent FROM currencies WHERE code = ?")
                .param(a.currency())
                .query(Integer.class)
                .single();
        Map<String, String> params = new HashMap<>(Map.of(
                "tenant_name", tenant.name(),
                "currency", a.currency(),
                "amount", format(amountMinor, exponent),
                "account_no", a.accountNo(),
                "receipt_no", receiptNo == null ? "-" : receiptNo));
        if (settings.smsIncludeBalance()) {
            params.put("balance", format(balanceMinor, exponent));
        }
        outbox.enqueue(new Outbox.Message(
                Outbox.SMS,
                phone,
                templateKey,
                params,
                "lending.savings.sms:" + tenant.id() + ":" + txnId,
                null,
                clock.now().plus(LIFETIME),
                Outbox.TRANSACTIONAL,
                Outbox.Subject.member(a.memberId()),
                null));
    }

    /** {@code 1234567} with exponent 0 is {@code 1,234,567}; with exponent 2, {@code 12,345.67}. */
    static String format(long minor, int exponent) {
        return String.format(Locale.ROOT, "%,." + exponent + "f", BigDecimal.valueOf(minor, exponent));
    }
}
