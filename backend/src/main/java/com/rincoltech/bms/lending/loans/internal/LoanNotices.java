package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Position;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 *
 * <p>The reminders and arrears notices (issue #227, FR-NTF-02, FR-NTF-06) are queued by the nightly arrears run after
 * each active loan's recompute: {@code loan.due_in_3_days} once an unpaid instalment is three days away or less, and
 * {@code loan.due_today} on its due date (category reminder); {@code loan.overdue_1}, {@code loan.overdue_7} and
 * {@code loan.overdue_30} once the loan's DPD reaches the step (category arrears), keyed on the oldest unpaid
 * instalment and carrying the loan's arrears and days past due, in the wording of chapter 11 section 11.3.1. The event reference is the schedule item, so the outbox key is (item, reminder type): a rerun,
 * or a night when the step is still the same, queues nothing new. A night the job missed is caught up by the next one,
 * with the step the loan has reached by then. The channel holds them for the tenant's window, expires them at its end,
 * and applies the opt-out and the caps.
 */
@Component
class LoanNotices {

    static final String DISBURSED = "loan.disbursed";
    static final String REPAYMENT = "loan.repayment_received";
    static final String CLOSED = "loan.closed";
    static final String DUE_IN_3_DAYS = "loan.due_in_3_days";
    static final String DUE_TODAY = "loan.due_today";
    static final String OVERDUE_1 = "loan.overdue_1";
    static final String OVERDUE_7 = "loan.overdue_7";
    static final String OVERDUE_30 = "loan.overdue_30";

    /**
     * One reminder or arrears notice the nightly run owes the borrower: about this item, for this amount (what is due
     * on it, or the loan's arrears), with the loan's days past due for an arrears notice.
     */
    record Reminder(String templateKey, String category, UUID itemId, long amountMinor, LocalDate dueDate, int dpd) {

        boolean arrears() {
            return Outbox.ARREARS.equals(category);
        }
    }

    private final SmsEvents sms;
    private final MemberLookup members;
    private final JdbcClient jdbc;

    LoanNotices(SmsEvents sms, MemberLookup members, JdbcClient jdbc) {
        this.sms = sms;
        this.members = members;
        this.jdbc = jdbc;
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

    /** An arrears step is sent on the night the loan reaches it, or the night after when that night was missed. */
    static final int STEP_CATCH_UP_DAYS = 1;

    /** At most one arrears notice a loan in this many days, across its instalments (an always-late borrower). */
    static final int ARREARS_GAP_DAYS = 7;

    /** The arrears step reached on {@code dpd}, or null: steps 1, 7 and 30, each on its night or the night after. */
    static String arrearsStep(int dpd) {
        for (int step : new int[] {30, 7, 1}) {
            if (dpd >= step && dpd <= step + STEP_CATCH_UP_DAYS) {
                return step == 30 ? OVERDUE_30 : step == 7 ? OVERDUE_7 : OVERDUE_1;
            }
        }
        return null;
    }

    /**
     * The reminders the loan's position calls for on {@code today}, oldest first; pure. An instalment with nothing
     * left to pay (paid, waived) is never reminded. An arrears notice goes only on the night the loan reaches a step
     * (DPD 1, 7 or 30) or the night after, never as a backlog: a loan already 400 days late when the run or SMS is
     * switched on is not texted (the second review of #290).
     */
    static List<Reminder> reminders(List<Item> items, Position position, LocalDate today) {
        List<Item> byDue = items.stream()
                .sorted(Comparator.comparing((Item i) -> i.dueDate).thenComparingInt(i -> i.no))
                .toList();
        List<Reminder> out = new ArrayList<>();
        for (Item i : byDue) {
            long unpaid = i.unpaidTotal();
            long days = ChronoUnit.DAYS.between(today, i.dueDate);
            if (unpaid <= 0 || days < 0 || days > 3) {
                continue;
            }
            out.add(new Reminder(days == 0 ? DUE_TODAY : DUE_IN_3_DAYS, Outbox.REMINDER, i.id, unpaid, i.dueDate, 0));
        }
        int dpd = position.daysPastDue();
        String step = arrearsStep(dpd);
        if (step != null) {
            byDue.stream()
                    .filter(i -> i.dueDate.isBefore(today) && i.unpaidContract() > 0)
                    .findFirst()
                    .ifPresent(oldest -> out.add(new Reminder(
                            step, Outbox.ARREARS, oldest.id, position.arrearsMinor(), oldest.dueDate, dpd)));
        }
        return out;
    }

    /**
     * Queues what {@link #reminders} calls for; each is a no-op when it was queued before or SMS is off. A due reminder
     * expires at the end of its due date and an arrears notice at the end of today, in {@code zone}, so neither reaches
     * the member a day late. An arrears notice is skipped when the loan had one about another instalment in the last
     * {@link #ARREARS_GAP_DAYS} days; the steps of one instalment (1, 7, 30) always go. Returns how many were queued.
     */
    int remind(Loan loan, List<Item> items, Position position, LocalDate today, ZoneId zone) {
        int queued = 0;
        for (Reminder r : reminders(items, position, today)) {
            if (r.arrears() && recentArrearsNotice(loan.id(), r.itemId(), today)) {
                continue;
            }
            LocalDate lastDay = r.arrears() ? today : r.dueDate();
            Instant expiresAt = lastDay.plusDays(1).atStartOfDay(zone).toInstant();
            SmsEvents.Result result = sms.queueWhenActive(
                    r.templateKey(),
                    loan.memberId(),
                    r.itemId().toString(),
                    r.category(),
                    null,
                    expiresAt,
                    () -> members.smsPhone(loan.memberId()).orElse(null),
                    () -> r.arrears()
                            ? Map.of(
                                    "loan_no", loan.loanNo(),
                                    "currency", loan.currency(),
                                    "arrears", sms.amount(r.amountMinor(), loan.currency()),
                                    "dpd", String.valueOf(r.dpd()))
                            : Map.of(
                                    "loan_no", loan.loanNo(),
                                    "currency", loan.currency(),
                                    "amount", sms.amount(r.amountMinor(), loan.currency()),
                                    "due_date", sms.date(r.dueDate())));
            if (result == SmsEvents.Result.QUEUED) {
                queued++;
                if (r.arrears()) {
                    jdbc.sql("""
                                    INSERT INTO lending_loan_notices (id, tenant_id, loan_id, schedule_item_id, template_key,
                                        business_date)
                                    VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                                    """)
                            .params(UUID.randomUUID(), loan.id(), r.itemId(), r.templateKey(), today)
                            .update();
                }
            }
        }
        return queued;
    }

    private boolean recentArrearsNotice(UUID loanId, UUID itemId, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM lending_loan_notices
                                        WHERE loan_id = ? AND schedule_item_id <> ? AND business_date > ?)
                        """)
                .params(loanId, itemId, today.minusDays(ARREARS_GAP_DAYS))
                .query(Boolean.class)
                .single());
    }

    /**
     * Withdraws the not-yet-sent reminders and arrears notices of these schedule items (the event reference is the
     * item): a member who paid at 07:30 is not told at 08:00 that the instalment is due or overdue, nor one whose loan
     * was closed or written off (the second review of #290). What has gone stays gone.
     */
    void withdrawReminders(java.util.Collection<UUID> itemIds) {
        for (UUID itemId : itemIds) {
            sms.cancelSettled(itemId.toString());
        }
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
