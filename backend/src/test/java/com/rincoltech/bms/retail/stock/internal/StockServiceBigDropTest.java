package com.rincoltech.bms.retail.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Issue #636: the server's big drop rule at its edges. The client's {@code bigDrop} (frontend maths.ts) reads these same rows
 * in maths.test.ts and must agree with each, so the screen never misses a tick the server asks for. Amounts fabricated.
 */
class StockServiceBigDropTest {

    @ParameterizedTest(name = "expected {0}, counted {1}: {2}")
    @CsvSource({
        "8.000, 0.000, true",
        "1.000, 0.000, true",
        "10.000, 5.000, false",
        "10.000, 4.999, true",
        "10.000, 4.500, true",
        "0.002, 0.001, false",
        "0.003, 0.001, true",
        "0.000, 0.000, false",
        "-2.000, 0.000, false",
        "3.000, 7.000, false",
    })
    void aBigDropTakesAnItemHoldingStockToBelowHalf(String expected, String counted, boolean big) {
        StocktakeLine line = new StocktakeLine(null, "SKT-1", "Test Item", counted, expected, null, null, null);
        assertThat(StockService.bigDrop(line)).isEqualTo(big);
    }

    /** Issue #656: the refusal names at most five items and counts the rest. */
    @Test
    void theRefusalNamesFiveItemsAndCountsTheRest() {
        assertThat(StockService.firstNames(List.of("Test A"))).isEqualTo("Test A");
        assertThat(StockService.firstNames(List.of("Test A", "Test B", "Test C", "Test D", "Test E")))
                .isEqualTo("Test A, Test B, Test C, Test D, Test E");
        assertThat(StockService.firstNames(
                        List.of("Test A", "Test B", "Test C", "Test D", "Test E", "Test F", "Test G")))
                .isEqualTo("Test A, Test B, Test C, Test D, Test E and 2 more");
    }
}
