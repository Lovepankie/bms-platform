package com.rincoltech.bms.kernel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The one clock (chapter 5 section 5.4.5). No module reads the system clock directly; tests
 * replace the {@link Clock} bean to fix the business date. An architecture test fails the build
 * on direct {@code now()} calls outside this module.
 */
@Component
public class BusinessClock {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Kampala");

    private final Clock clock;

    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Business date in the tenant's timezone. */
    public LocalDate today(ZoneId tenantZone) {
        return LocalDate.ofInstant(clock.instant(), tenantZone);
    }
}
