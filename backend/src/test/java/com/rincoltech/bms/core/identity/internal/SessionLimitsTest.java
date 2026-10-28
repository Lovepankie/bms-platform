package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Issue #644, ADR-014 as amended on 28 October 2026: a staff session lasts seven days idle and seven days absolute, so a
 * phone stays signed in for a week from sign-in; a platform operator's stays two hours idle and twelve hours absolute.
 */
class SessionLimitsTest {

    @Test
    void staffStaySignedInForAWeekFromSignIn() {
        StaffAccounts staff = new StaffAccounts(null, null, null, null, null, null);
        assertThat(staff.idleTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(staff.absoluteTtl()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void platformOperatorsAreUnchanged() {
        PlatformAccounts platform = new PlatformAccounts(null, null);
        assertThat(platform.idleTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(platform.absoluteTtl()).isEqualTo(Duration.ofHours(12));
    }
}
