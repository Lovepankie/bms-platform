package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The one clock (#147; chapter 5 section 5.4.5) at the edge of the day: 00:30 in Africa/Kampala is still the
 * previous day in UTC. A void must stamp the record, post its reversal and report the Kampala date, never the UTC
 * one. Fabricated names and amounts.
 */
@Import(RetailCashbookClockIT.FixedClock.class)
class RetailCashbookClockIT extends IntegrationTest {

    /** 2026-10-08 00:30 in Kampala, 2026-10-07 21:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T21:30:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    JdbcClient owner;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-clock", false, true);
        cb = new CashbookTestSupport(http, t);
        owner = TestDatabase.owner();
    }

    @Test
    void aVoidJustAfterMidnightInKampalaIsDatedWithTheKampalaDayEverywhere() {
        LocalDate kampala = LocalDate.of(2026, 10, 8);
        LocalDate utc = LocalDate.of(2026, 10, 7);
        assertThat(clock.today(BusinessClock.DEFAULT_ZONE)).isEqualTo(kampala);

        UUID party = cb.party("Test Owner 01", "owner");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("business_date", utc.toString());
        body.put("party_id", party);
        body.put("principal_minor", 50_000);
        body.put("purpose", "Test purpose");
        ResponseEntity<JsonNode> created = cb.post("/advances", body, ALL);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = RetailTestSupport.id(created);

        ResponseEntity<JsonNode> voided = cb.voidIt("/advances/" + id, "Test void", ALL);
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);

        LocalDate voidedOn = owner.sql("SELECT (voided_at AT TIME ZONE 'Africa/Kampala')::date FROM retail_advances"
                        + " WHERE tenant_id = ? AND id = ?")
                .params(t.tenantId(), id)
                .query(LocalDate.class)
                .single();
        LocalDate reversalOn =
                owner.sql("""
                        SELECT e.entry_date FROM journal_entries e
                         WHERE e.tenant_id = ? AND e.reverses_entry_id IS NOT NULL
                        """).param(t.tenantId()).query(LocalDate.class).single();
        assertThat(voidedOn).isEqualTo(kampala).isNotEqualTo(utc);
        assertThat(reversalOn).isEqualTo(kampala).isNotEqualTo(utc);
        assertThat(voided.getBody().get("voided_at").asString()).startsWith("2026-10-07T21:30");
    }
}
