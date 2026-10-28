package com.rincoltech.bms.retail.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeLine;
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
}
