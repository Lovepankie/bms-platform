package com.rincoltech.bms.retail.cashbook.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One clock reading for a void and its reversal, and the suggestion token (ADR-022 decisions 9 and 11). */
class CashbookSupportTest {

    static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID BRANCH = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    static CashbookSupport support(Instant at, String dataKey) {
        CurrentTenant tenant =
                () -> new CurrentTenant.Profile(TENANT, "test", "Test Shop", "UGX", ZoneId.of("Africa/Kampala"));
        return new CashbookSupport(tenant, new BusinessClock(Clock.fixed(at, ZoneOffset.UTC)), null, dataKey);
    }

    @Test
    void aVoidJustAfterMidnightInTheTenantsZoneFallsOnTheNewDayForBothTheStampAndTheReversal() {
        // 21:30 UTC is 00:30 the next day in Kampala (UTC+3).
        Instant at = Instant.parse("2026-10-06T21:30:00Z");
        CashbookSupport s = support(at, "test-key");

        assertThat(s.dayOf(s.now())).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(s.dayOf(s.now())).isEqualTo(s.today());
    }

    @Test
    void theTokenIsComparedInConstantTimeAndKeyedByAPurposeKeyNotTheDataKey() {
        Instant at = Instant.parse("2026-10-07T08:00:00Z");
        CashbookSupport s = support(at, "test-key-one");
        LocalDate day = LocalDate.parse("2026-10-06");
        String token = s.suggestionToken(BRANCH, day, 2_500);

        assertThat(s.tokenMatches(BRANCH, day, 2_500, token)).isTrue();
        assertThat(s.tokenMatches(BRANCH, day, 2_501, token)).isFalse();
        assertThat(s.tokenMatches(BRANCH, day, 2_500, null)).isFalse();
        assertThat(s.tokenMatches(BRANCH, day, 2_500, token.substring(1))).isFalse();
        assertThat(support(at, "test-key-two").suggestionToken(BRANCH, day, 2_500))
                .isNotEqualTo(token);
        assertThat(support(at, "test-key-one").suggestionToken(BRANCH, day, 2_500))
                .isEqualTo(token);
    }
}
