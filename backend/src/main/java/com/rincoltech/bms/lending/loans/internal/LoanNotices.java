package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The loan SMS events of FR-NTF-02 (issue #221, ADR-036): {@code loan.disbursed}, {@code loan.repayment_received}
 * and {@code loan.closed}. Each is queued in the notification outbox in the transaction of the event that causes it,
 * through {@link SmsEvents}, so a message exists exactly when the event commits and a rolled-back repayment sends
 * nothing (FR-NTF-01); the key is the transaction id, so the same event is never queued twice (FR-NTF-06's rule for
 * events). The message goes to the borrower's own primary phone and to nobody else: never a next of kin, never a
 * guarantor. It names the business and the loan number and carries no name of the member and no loan purpose.
 *
 * <p>Only a staff event or a gateway booking sends. A caller without a request principal (the fabricated seed, the
 * import port) never messages a member (a source other than {@code staff} or {@code gateway}); a staff request can also say {@code notify_member: false}, as a
 * register import through the public routes does; and an event whose value date is more than the cutoff days old
 * ({@code BMS_SMS_EVENT_MAX_AGE_DAYS}, 3) is never texted. A reversal sends nothing new and withdraws what the reversed
 * transaction queued if the sender has not claimed it yet; a correction is the cashier's word. The balance
 * is the outstanding principal, interest, fees and penalties after the repayment, and only for a tenant that asked for
 * it; it is computed only then.
 */
@Component
class LoanNotices {

    static final String DISBURSED = "loan.disbursed";
    static final String REPAYMENT = "loan.repayment_received";
    static final String CLOSED = "loan.closed";

    private final SmsEvents sms;
    private final MemberLookup members;

    LoanNotices(SmsEvents sms, MemberLookup members) {
        this.sms = sms;
        this.members = members;
    }

    /**
     * Whether an event with this {@code source} messages the member: a staff event and a gateway booking do (the member
     * paid, and is told the receipt), the seed and the import port do not, and a request that said
     * {@code notify_member: false} does not.
     */
    static boolean sends(String source, boolean notifyMember) {
        return ("staff".equals(source) || "gateway".equals(source)) && notifyMember;
    }

    /** What the audit entry of a money event records about the text: whether the member was to be told, and the cutoff. */
    java.util.Map<String, Object> audit(String source, boolean notifyMember, LocalDate valueDate) {
        return sms.auditNote(sends(source, notifyMember), valueDate);
    }

    /** @param paidOutMinor the principal less the fees deducted at disbursement: what the member actually receives */
    void disbursed(Loan loan, UUID txnId, LocalDate date, long paidOutMinor, ScheduleCalculator.Item first) {
        queue(DISBURSED, loan, txnId, date, () -> {
            Map<String, String> params = new HashMap<>();
            params.put("loan_no", loan.loanNo());
            params.put("currency", loan.currency());
            params.put("amount", sms.amount(paidOutMinor, loan.currency()));
            params.put("date", sms.date(date));
            params.put("next_amount", sms.amount(first.totalMinor(), loan.currency()));
            params.put("next_due_date", sms.date(first.dueDate()));
            return params;
        });
    }

    /** @param outstandingMinor what the loan still owes after this repayment, or null for a recovery on a written-off loan */
    void repaymentReceived(
            Loan loan, UUID txnId, long amountMinor, LocalDate valueDate, String receiptNo, Long outstandingMinor) {
        queue(REPAYMENT, loan, txnId, valueDate, () -> {
            Map<String, String> params = new HashMap<>();
            params.put("loan_no", loan.loanNo());
            params.put("currency", loan.currency());
            params.put("amount", sms.amount(amountMinor, loan.currency()));
            params.put("date", sms.date(valueDate));
            params.put("receipt_no", receiptNo);
            if (outstandingMinor != null && sms.includeBalance()) {
                params.put("balance", sms.amount(outstandingMinor, loan.currency()));
            }
            return params;
        });
    }

    /** @param closingTxnId the repayment that settled the loan; a loan can close, reopen and close again, once each time */
    void closed(Loan loan, UUID closingTxnId, LocalDate valueDate) {
        queue(CLOSED, loan, closingTxnId, valueDate, () -> Map.of("loan_no", loan.loanNo()));
    }

    /**
     * A reversal withdraws what the reversed transaction queued and the sender has not yet claimed: the receipt of an
     * entry made on the wrong loan must not tell the wrong person. What has gone stays gone; nothing new is sent.
     */
    void reversed(UUID reversedTxnId) {
        sms.cancel(reversedTxnId.toString());
    }

    private void queue(
            String templateKey, Loan loan, UUID eventId, LocalDate valueDate, Supplier<Map<String, String>> params) {
        sms.queueWhenActive(
                templateKey,
                loan.memberId(),
                eventId.toString(),
                Outbox.TRANSACTIONAL,
                valueDate,
                () -> members.smsPhone(loan.memberId()).orElse(null),
                params);
    }
}
