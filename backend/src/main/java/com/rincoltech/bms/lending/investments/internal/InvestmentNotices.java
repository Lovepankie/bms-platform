package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import com.rincoltech.bms.lending.members.MemberLookup;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The investment SMS events of FR-NTF-02 (issue #221, ADR-036): {@code investment.funded},
 * {@code investment.maturing} and {@code investment.paid_out}. Each is queued in the notification outbox through
 * {@link SmsEvents}, in the transaction of the event (or of the nightly job run) that causes it: a message exists exactly
 * when the event commits, the same event never queues twice, and the message goes to the investor's own primary phone
 * only. It names the business and the account number and no name of the member.
 *
 * <p>Funding and payouts message the member only when staff made them (a source other than {@code staff}, the seed and the
 * import port, never does), when the request did not say {@code notify_member: false} (a register import does) and when the
 * value date is not more than the cutoff days old; a reversal sends nothing new and withdraws what the reversed transaction
 * queued if the sender has not claimed it. A payout is any money paid to the investor: a return payout, the
 * maturity payout and an early withdrawal, each with its voucher. The maturing notice is a reminder, so the tenant's sending
 * window holds it, and it is sent once per investment and maturity date: by the job's own record that the reminder was made
 * (the date is stamped on the investment) and by the idempotency key, so a job run twice queues nothing new.
 */
@Component
class InvestmentNotices {

    static final String FUNDED = "investment.funded";
    static final String MATURING = "investment.maturing";
    static final String PAID_OUT = "investment.paid_out";

    private final SmsEvents sms;
    private final MemberLookup members;

    InvestmentNotices(SmsEvents sms, MemberLookup members) {
        this.sms = sms;
        this.members = members;
    }

    /**
     * Whether an event with this {@code source} messages the member: a staff event does, the seed and the import port do not,
     * and a staff request that said {@code notify_member: false} does not.
     */
    static boolean sends(String source, boolean notifyMember) {
        return "staff".equals(source) && notifyMember;
    }

    /** What the audit entry of a money event records about the text: whether the member was to be told, and the cutoff. */
    Map<String, Object> audit(String source, boolean notifyMember, LocalDate valueDate) {
        return sms.auditNote(sends(source, notifyMember), valueDate);
    }

    void funded(Inv inv, UUID txnId, LocalDate valueDate, LocalDate maturity, String receiptNo) {
        queue(FUNDED, inv, txnId.toString(), Outbox.TRANSACTIONAL, valueDate, () -> {
            Map<String, String> params = base(inv, inv.principalMinor());
            params.put("date", sms.date(valueDate));
            params.put("maturity_date", sms.date(maturity));
            params.put("receipt_no", receiptNo);
            return params;
        });
    }

    /** The reminder that the investment matures soon; one per investment and maturity date (a rollover has a new date). */
    void maturing(Inv inv) {
        queue(MATURING, inv, inv.id() + ":" + inv.maturityDate(), Outbox.REMINDER, null, () -> {
            Map<String, String> params = base(inv, inv.principalMinor());
            params.put("maturity_date", sms.date(inv.maturityDate()));
            return params;
        });
    }

    void paidOut(Inv inv, UUID txnId, long paidMinor, LocalDate valueDate, String voucherNo) {
        queue(PAID_OUT, inv, txnId.toString(), Outbox.TRANSACTIONAL, valueDate, () -> {
            Map<String, String> params = base(inv, paidMinor);
            params.put("date", sms.date(valueDate));
            params.put("voucher_no", voucherNo);
            return params;
        });
    }

    /**
     * A reversal withdraws what the reversed transaction queued and the sender has not yet claimed (a receipt for an entry
     * made on the wrong account must not tell the wrong person). What has gone stays gone; nothing new is sent.
     */
    void reversed(UUID reversedTxnId) {
        sms.cancel(reversedTxnId.toString());
    }

    private Map<String, String> base(Inv inv, long amountMinor) {
        Map<String, String> params = new HashMap<>();
        params.put("account_no", inv.accountNo());
        params.put("currency", inv.currency());
        params.put("amount", sms.amount(amountMinor, inv.currency()));
        return params;
    }

    private void queue(
            String templateKey,
            Inv inv,
            String eventRef,
            String category,
            LocalDate valueDate,
            Supplier<Map<String, String>> params) {
        sms.queueWhenActive(
                templateKey,
                inv.memberId(),
                eventRef,
                category,
                valueDate,
                () -> members.smsPhone(inv.memberId()).orElse(null),
                params);
    }
}
