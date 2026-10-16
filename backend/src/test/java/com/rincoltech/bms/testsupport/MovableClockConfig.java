package com.rincoltech.bms.testsupport;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's {@link Clock} with a {@link MovableClock} that starts at {@link #BEFORE_MIDNIGHT}: 20:59 UTC on
 * 15 October 2026, one minute before Africa/Kampala (UTC+3) turns from the 15th to the 16th. A test class imports it with
 * {@code @Import(MovableClockConfig.class)}, autowires the {@code MovableClock} and sets the time it needs in its
 * {@code @BeforeEach}; the business date is the Kampala date of this clock (chapter 5 section 5.4.5), never the wall clock.
 *
 * <p>The clock is a bean of the Spring context, not a static: the classes that import this configuration share one cached
 * context and so one clock, and a test class that uses it must hold the JUnit resource lock {@link #LOCK}
 * ({@code @ResourceLock(MovableClockConfig.LOCK)}), so that two such classes can never move it under each other if the
 * suite is ever run in parallel.
 */
@TestConfiguration
public class MovableClockConfig {

    /** The JUnit resource lock every class that moves the clock holds. */
    public static final String LOCK = "movable-clock";

    /** 20:59 UTC, 23:59 on 2026-10-15 in Kampala. */
    public static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-10-15T20:59:00Z");

    /** 21:01 UTC, 00:01 on 2026-10-16 in Kampala. */
    public static final Instant AFTER_MIDNIGHT = Instant.parse("2026-10-15T21:01:00Z");

    @Bean
    @Primary
    MovableClock movableClock() {
        return new MovableClock(BEFORE_MIDNIGHT);
    }
}
