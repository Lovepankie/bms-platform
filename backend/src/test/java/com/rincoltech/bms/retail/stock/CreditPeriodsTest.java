package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The credit sales counted when the buyer pays (ADR-043): one start day, for ever, by the later of a sale's date and entry day. */
class CreditPeriodsTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 21);
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    static Instant at(String local) {
        return java.time.LocalDateTime.parse(local).atZone(KAMPALA).toInstant();
    }

    @Test
    void aTenantThatNeverChoseTheRuleHasNoStartAndNoSqlCondition() {
        assertThat(CreditPeriods.NONE.isEmpty()).isTrue();
        assertThat(CreditPeriods.NONE.contains(D1)).isFalse();
        assertThat(CreditPeriods.NONE.counts(D1, at("2026-10-21T10:00:00"))).isFalse();
        assertThat(CreditPeriods.NONE.sql("s.sale_date", "s.created_at", new LinkedHashMap<>(), "p"))
                .isEqualTo("false");
    }

    @Test
    void aDayIsInsideFromTheStartOn() {
        CreditPeriods p = new CreditPeriods(D1, KAMPALA);
        assertThat(p.isEmpty()).isFalse();
        assertThat(p.contains(D1.minusDays(1))).isFalse();
        assertThat(p.contains(D1)).isTrue();
        assertThat(p.contains(D1.plusYears(5))).isTrue();
    }

    @Test
    void aSaleCountsFromTheLaterOfItsDateAndTheDayItWasEntered() {
        CreditPeriods p = new CreditPeriods(D1, KAMPALA);
        assertThat(p.counts(D1.minusDays(1), at("2026-10-20T10:00:00")))
                .as("dated and entered before the start")
                .isFalse();
        assertThat(p.counts(D1, at("2026-10-21T10:00:00")))
                .as("dated and entered on the start")
                .isTrue();
        assertThat(p.counts(D1.minusDays(1), at("2026-10-25T10:00:00")))
                .as("dated before the start but entered after it: a backdated sale")
                .isTrue();
        assertThat(p.counts(D1.plusDays(2), at("2026-10-20T10:00:00")))
                .as("dated after the start (cannot be, but the later date wins)")
                .isTrue();
    }

    @Test
    void theDayItWasEnteredIsTheTenantsDayNotTheUtcDay() {
        CreditPeriods p = new CreditPeriods(D1, KAMPALA);
        // 22:30 UTC on the 20th is 01:30 on the 21st in Kampala.
        assertThat(p.counts(D1.minusDays(1), Instant.parse("2026-10-20T22:30:00Z")))
                .isTrue();
        assertThat(p.counts(D1.minusDays(1), Instant.parse("2026-10-20T20:30:00Z")))
                .isFalse();
    }

    @Test
    void theSqlConditionUsesTheLaterOfTheDateAndTheEntryDayAndBindsBothValues() {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = new CreditPeriods(D1, KAMPALA).sql("s.sale_date", "s.created_at", params, "x");
        assertThat(sql)
                .isEqualTo("(GREATEST(s.sale_date, CAST((s.created_at AT TIME ZONE :xZone) AS date)) >= :xFrom)");
        assertThat(params).containsOnlyKeys("xFrom", "xZone");
        assertThat(params.get("xZone")).isEqualTo("Africa/Kampala");
    }
}
