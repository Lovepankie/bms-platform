package com.rincoltech.bms.retail.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLineRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import com.rincoltech.bms.retail.stock.EntryLocations;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Issue #769, ADR-067 decision 5: the sale screen's online request and its outbox copy, which adds occurred_at,
 * recorded_offline and prices_as_of, are one request both to the client sale id's stored hash and to the key's hash; a
 * changed line is not. Fabricated ids and amounts.
 */
class SaleRequestKeyTest {

    private static final UUID BRANCH = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID CLIENT = UUID.fromString("00000000-0000-4000-8000-000000000769");

    private static SaleRequest sale(String qty, Instant occurredAt, Boolean offline, Instant pricesAsOf) {
        return new SaleRequest(
                BRANCH,
                null,
                "cash",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new SaleLineRequest(PRODUCT, new BigDecimal(qty), 2_000L, null, null)),
                CLIENT,
                occurredAt,
                offline,
                pricesAsOf,
                null);
    }

    @Test
    void theOutboxCopyIsTheSameRequestAndAChangedLineIsNot() {
        Instant at = Instant.parse("2026-11-02T09:10:00Z");
        SaleRequest online = sale("1", null, null, null);
        SaleRequest copy = sale("1", at, true, Instant.parse("2026-11-02T09:00:00Z"));
        SaleRequest changed = sale("2", at, true, Instant.parse("2026-11-02T09:00:00Z"));

        assertThat(SalesService.requestHash(copy)).isEqualTo(SalesService.requestHash(online));
        assertThat(SalesService.keyed(copy)).isEqualTo(SalesService.keyed(online));
        assertThat(SalesService.requestHash(changed)).isNotEqualTo(SalesService.requestHash(online));
        assertThat(SalesService.keyed(changed)).isNotEqualTo(SalesService.keyed(online));
    }

    // JUSTIFICATION-A3: the review of #834 asks for this one case; a test, not a mechanism.
    private static SaleRequest credit(
            String name, String contact, int dueInDays, Instant at, EntryLocations.Fix where) {
        LocalDate day = LocalDate.parse("2026-11-02");
        return new SaleRequest(
                BRANCH,
                day,
                "credit",
                null,
                name,
                contact,
                day.plusDays(dueInDays),
                null,
                "Test reason",
                List.of(new SaleLineRequest(PRODUCT, BigDecimal.ONE, 2_000L, null, null)),
                null,
                at,
                false,
                at,
                where);
    }

    /** Issue #826, ADR-122: a request with no client sale id keeps every field under its key but where the phone was. */
    @Test
    void anOnlineSaleWithoutAClientIdKeepsEveryFieldButTheLocation() {
        Instant at = Instant.parse("2026-11-02T09:10:00Z");
        String name = "Test Buyer 01";
        String phone = "+256700000001";
        SaleRequest located = credit(name, phone, 30, at, new EntryLocations.Fix(0.3136, 32.5811, 12.0));
        SaleRequest key = SalesService.keyed(located);
        assertThat(key).isEqualTo(credit(name, phone, 30, at, null));
        // Each buyer field stays under the key: a change to any of them is a different request.
        assertThat(SalesService.keyed(credit("Test Buyer 02", phone, 30, at, null)))
                .isNotEqualTo(key);
        assertThat(SalesService.keyed(credit(name, "+256700000002", 30, at, null)))
                .isNotEqualTo(key);
        assertThat(SalesService.keyed(credit(name, phone, 14, at, null))).isNotEqualTo(key);
    }
}
