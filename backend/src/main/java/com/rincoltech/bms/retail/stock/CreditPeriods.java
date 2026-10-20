package com.rincoltech.bms.retail.stock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * The credit sales to which the tenant's {@code on_payment} rule applies (ADR-043): from one business day on, for ever. The rule is
 * one-way (switched on once, the business day after it was asked for), so there is a single start day. A credit sale is counted the
 * way the rule stood on the day it COUNTS FROM: the later of the date written on the sale and the tenant's day on which it was
 * entered. So a sale entered after the start but dated before it (a backdated sale) is counted when the buyer pays, exactly like a
 * sale made after the start; otherwise its profit would be added to a day that was already reported and whose savings were already
 * set aside. A sale entered and dated before the start keeps counting at the sale, so switching never counts the profit of an older
 * sale a second time. A tenant that never chose {@code on_payment} has no start, and every report reads exactly as it always did.
 *
 * @param from the first counting day of a sale counted when the buyer pays, or null when the rule was never chosen
 * @param zone the tenant's time zone, which turns the moment a sale was entered into the day it was entered
 */
public record CreditPeriods(LocalDate from, ZoneId zone) {

    public static final CreditPeriods NONE = new CreditPeriods(null, null);

    public boolean isEmpty() {
        return from == null;
    }

    /** Whether the rule is in force on this business day (a report or a card of that day). */
    public boolean contains(LocalDate day) {
        return from != null && !day.isBefore(from);
    }

    /** Whether a credit sale dated {@code saleDate} and entered at {@code enteredAt} is counted when the buyer pays. */
    public boolean counts(LocalDate saleDate, Instant enteredAt) {
        if (from == null) {
            return false;
        }
        LocalDate entered = enteredAt.atZone(zone).toLocalDate();
        LocalDate day = saleDate.isAfter(entered) ? saleDate : entered;
        return !day.isBefore(from);
    }

    /**
     * A SQL condition that is true for a sale inside the rule: the later of its date column and the tenant day of its entry column
     * is on or after the start day. Both bind values go into {@code params} under names that start with {@code prefix}.
     * {@code false} when there is no start.
     */
    public String sql(String dateColumn, String enteredColumn, Map<String, Object> params, String prefix) {
        if (from == null) {
            return "false";
        }
        params.put(prefix + "From", java.sql.Date.valueOf(from));
        params.put(prefix + "Zone", zone.getId());
        return "(GREATEST(" + dateColumn + ", CAST((" + enteredColumn + " AT TIME ZONE :" + prefix
                + "Zone) AS date)) >= :" + prefix + "From)";
    }
}
