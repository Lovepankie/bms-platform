package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Issue #647, ADR-014 as amended on 28 October 2026: a staff session lasts while it is used at least once every thirty days,
 * and at most ninety days from sign-in; a platform operator's stays two hours idle and twelve hours absolute.
 */
class SessionLimitsTest {

    @Test
    void staffStaySignedInWhileUsedForUpToNinetyDays() {
        StaffAccounts staff = new StaffAccounts(null, null, null, null, null, null);
        assertThat(staff.idleTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(staff.absoluteTtl()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void platformOperatorsAreUnchanged() {
        PlatformAccounts platform = new PlatformAccounts(null, null);
        assertThat(platform.idleTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(platform.absoluteTtl()).isEqualTo(Duration.ofHours(12));
    }
}
