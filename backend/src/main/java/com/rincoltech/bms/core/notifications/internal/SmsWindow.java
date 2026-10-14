package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The tenant's sending window (FR-NTF-04, ADR-036 decision 5): reminders and arrears notices go
 * out between {@code start} (inclusive) and {@code end} (exclusive) on the business clock of
 * Africa/Kampala, 08:00 to 20:00 unless the tenant set its own (the {@code sms_window_start} and
 * {@code sms_window_end} settings). Receipts are never held by it. Pure: the channel and the
 * enqueue both ask it, later slices (reminders, #227) too.
 */
record SmsWindow(LocalTime start, LocalTime end, ZoneId zone) {

    static final LocalTime DEFAULT_START = LocalTime.of(8, 0);
    static final LocalTime DEFAULT_END = LocalTime.of(20, 0);

    SmsWindow {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("the window must end after it starts");
        }
    }

    static SmsWindow standard() {
        return new SmsWindow(DEFAULT_START, DEFAULT_END, BusinessClock.DEFAULT_ZONE);
    }

    static SmsWindow of(LocalTime start, LocalTime end) {
        return new SmsWindow(start, end, BusinessClock.DEFAULT_ZONE);
    }

    boolean isOpen(Instant at) {
        LocalTime time = at.atZone(zone).toLocalTime();
        return !time.isBefore(start) && time.isBefore(end);
    }

    /** The moment the window next allows a send: {@code at} itself when it is open now. */
    Instant nextOpen(Instant at) {
        if (isOpen(at)) {
            return at;
        }
        LocalDate day = at.atZone(zone).toLocalDate();
        LocalTime time = at.atZone(zone).toLocalTime();
        return time.isBefore(start) ? startOn(day) : startOn(day.plusDays(1));
    }

    /** The opening of the window on the day after {@code at}'s day: where an over-cap reminder waits. */
    Instant nextDayStart(Instant at) {
        return startOn(at.atZone(zone).toLocalDate().plusDays(1));
    }

    /** The closing of the window on {@code at}'s day: a reminder expires then (ADR-036 decision 14). */
    Instant endOfDay(Instant at) {
        return end.atDate(at.atZone(zone).toLocalDate()).atZone(zone).toInstant();
    }

    private Instant startOn(LocalDate day) {
        return start.atDate(day).atZone(zone).toInstant();
    }
}
