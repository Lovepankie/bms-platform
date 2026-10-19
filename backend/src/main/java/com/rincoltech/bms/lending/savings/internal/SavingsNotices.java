package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.savings.internal.SavingsRepository.AccountRow;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The savings receipt SMS of chapter 11 section 11.3.1 ({@code savings.deposit}, {@code savings.withdrawal}), queued in the
 * notification outbox in the movement's transaction through {@link SmsEvents} (issue #221, ADR-036 third amendment), exactly
 * as the loan and investment events are: the number is read without guessing, the key is the transaction and the member, a
 * rolled-back movement sends nothing, a failure to queue (the whole preparation runs in a savepoint) never fails the deposit
 * or withdrawal, a tenant without SMS costs two small reads, and an event whose value date is more than the cutoff days old
 * is not texted. Only staff movements send one, and only if the request did not say {@code notify_member: false}: the seed
 * and the import port never message members. A reversal sends nothing new and withdraws what the reversed movement queued if
 * the sender has not claimed it. A receipt is transactional (never held by the sending window), about the member (so the
 * member's opt-out applies), expires an hour after it is queued, and carries no balance unless the tenant setting
 * {@code include_balance} is on (FR-NTF-17). The phone number is never logged.
 */
@Component
class SavingsNotices {

    private final SmsEvents sms;
    private final MemberLookup members;

    SavingsNotices(SmsEvents sms, MemberLookup members) {
        this.sms = sms;
        this.members = members;
    }

    /**
     * Whether a movement with this {@code source} messages the member: a staff movement does, and so does a gateway
     * booking (the member paid and is told the receipt), unless the request said not to; the seed and the import never.
     */
    static boolean sends(String source, boolean notifyMember) {
        return ("staff".equals(source) || "gateway".equals(source)) && notifyMember;
    }

    /** What the audit entry of a money event records about the text: whether the member was to be told, and the cutoff. */
    Map<String, Object> audit(String source, boolean notifyMember, LocalDate valueDate) {
        return sms.auditNote(sends(source, notifyMember), valueDate);
    }

    void receipt(
            String templateKey,
            AccountRow a,
            UUID txnId,
            long amountMinor,
            long balanceMinor,
            String receiptNo,
            LocalDate valueDate) {
        sms.queueWhenActive(
                templateKey,
                a.memberId(),
                txnId.toString(),
                Outbox.TRANSACTIONAL,
                valueDate,
                () -> members.smsPhone(a.memberId()).orElse(null),
                () -> {
                    Map<String, String> params = new HashMap<>();
                    params.put("currency", a.currency());
                    params.put("amount", sms.amount(amountMinor, a.currency()));
                    params.put("account_no", a.accountNo());
                    params.put("receipt_no", receiptNo == null ? "-" : receiptNo);
                    if (sms.includeBalance()) {
                        params.put("balance", sms.amount(balanceMinor, a.currency()));
                    }
                    return params;
                });
    }

    /** A reversal withdraws the message of the reversed movement, if the sender has not claimed it yet. */
    void reversed(UUID reversedTxnId) {
        sms.cancel(reversedTxnId.toString());
    }
}
