package com.rincoltech.bms.core.notifications;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * The bound business's SMS use, read from its delivery log (FR-NTF-07, FR-NTF-15, issue #215): the figures of the report
 * {@code core.sms_usage}, which the reporting module renders. Read inside the caller's transaction with the business bound
 * (row-level security keeps every other business out); never reads the clock.
 */
public interface SmsUsage {

    /**
     * Whether the report is offered to the business: its own SMS switch is on, or it has any message in the delivery log
     * (kept 13 months), so a business that switched SMS off still sees what it used. Off for a business that never used
     * SMS.
     */
    boolean offered();

    /**
     * One line per calendar month and event, in month then event order, over the months that hold {@code from} to
     * {@code to} (inclusive), months taken in {@code zone}. A message counts when it was handed to the provider
     * ({@code sent}, {@code delivered}, {@code undelivered}, or {@code unknown}, which may have gone): not one the sink
     * took, one refused or blocked, or one still queued. Segments are the provider's count when it gave one, else ours.
     * The cost is the provider's recorded cost of the messages recorded in {@code currency}; any other message is counted
     * in {@link Line#withoutCost}.
     */
    List<Line> months(LocalDate from, LocalDate to, ZoneId zone, String currency);

    /**
     * @param eventKey the template key, for example {@code loan.repayment_received}
     * @param event the event in words, for example "Loan repayment receipt"
     * @param costMinor the recorded cost in integer minor units of the currency asked for
     */
    record Line(
            YearMonth month,
            String eventKey,
            String event,
            long messages,
            long delivered,
            long undelivered,
            long segments,
            long costMinor,
            long withoutCost) {}
}
