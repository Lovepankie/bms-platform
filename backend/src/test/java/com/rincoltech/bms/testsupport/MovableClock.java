package com.rincoltech.bms.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} the test moves. One instance is shared by every test class that imports {@link MovableClockConfig}, so a
 * test sets the time it needs in its {@code @BeforeEach} and never reads the wall clock.
 */
public final class MovableClock extends Clock {

    private volatile Instant now;

    public MovableClock(Instant now) {
        this.now = now;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
