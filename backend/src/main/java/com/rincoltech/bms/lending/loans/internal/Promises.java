package com.rincoltech.bms.lending.loans.internal;

import java.time.LocalDate;

/**
 * FR-CLN-05, pure: a promise to pay is kept once the repayments from the day it was made to the
 * promised date reach the promised amount, and broken the day after the promised date if they
 * never did. Until then it stays pending.
 */
final class Promises {

    private Promises() {}

    /** The new status, or null while the promise is still open. */
    static String settle(long paidMinor, long promisedMinor, LocalDate promisedDate, LocalDate today) {
        if (paidMinor >= promisedMinor) {
            return "kept";
        }
        return today.isAfter(promisedDate) ? "broken" : null;
    }
}
