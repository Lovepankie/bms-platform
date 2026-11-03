package com.rincoltech.bms.retail.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLineRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import java.math.BigDecimal;
import java.time.Instant;
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
                pricesAsOf);
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
}
