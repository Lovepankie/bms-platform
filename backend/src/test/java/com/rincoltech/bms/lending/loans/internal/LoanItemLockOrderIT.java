package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #176 (review of #202): pledging and a reopening reversal lock collateral items in one order. It
 * must be PostgreSQL's order for uuid, never {@code UUID.compareTo}, which compares signed longs and
 * disagrees for about half of random pairs.
 */
class LoanItemLockOrderIT {

    @Test
    void theItemLockOrderIsPostgresqlsOrderNotTheSignedOne() {
        UUID high = UUID.fromString("ffffffff-0000-4000-8000-000000000000");
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000000");

        assertThat(high.compareTo(low))
                .as("UUID.compareTo puts the high bit first")
                .isNegative();
        assertThat(LoanRepository.ITEM_LOCK_ORDER.compare(high, low)).isPositive();
        assertThat(TestDatabase.owner()
                        .sql("SELECT ?::uuid > ?::uuid")
                        .params(high, low)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
