package com.rincoltech.bms.retail.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rincoltech.bms.retail.stock.EntryLocations.Fix;
import org.junit.jupiter.api.Test;

/** Issue #826 (ADR-122; FR-RET-87): the haversine distance, rounded as people say it, and the checks on a location. */
class EntryDistanceTest {

    static String said(double metres) {
        return EntryLocationService.words(EntryLocationService.rounded(metres));
    }

    @Test
    void haversineAgreesWithKnownDistances() {
        assertThat(EntryLocationService.haversine(0, 0, 1, 0)).isCloseTo(111_195.08, within(0.1));
        assertThat(EntryLocationService.haversine(0, 0, 0, 180))
                .isCloseTo(Math.PI * EntryLocationService.EARTH_RADIUS_M, within(0.5));
        assertThat(EntryLocationService.haversine(0.3136, 32.5811, 0.3136, 32.5811))
                .isZero();
        // Symmetric, and across the date line the short way round.
        assertThat(EntryLocationService.haversine(1, 179.9, 1, -179.9))
                .isCloseTo(EntryLocationService.haversine(1, -179.9, 1, 179.9), within(0.001))
                .isLessThan(23_000);
    }

    @Test
    void metresUnderAKilometreToTheNearestTen() {
        assertThat(said(0)).isEqualTo("at the shop");
        assertThat(said(9.9)).isEqualTo("at the shop");
        assertThat(said(10)).isEqualTo("10 m from the shop");
        assertThat(said(44)).isEqualTo("40 m from the shop");
        assertThat(said(40.03)).isEqualTo("40 m from the shop");
        assertThat(said(994)).isEqualTo("990 m from the shop");
    }

    @Test
    void kilometresWithOneDecimalThenWhole() {
        assertThat(said(995)).isEqualTo("1 km from the shop");
        assertThat(said(3_249)).isEqualTo("3.2 km from the shop");
        assertThat(said(3_251)).isEqualTo("3.3 km from the shop");
        assertThat(said(12_000)).isEqualTo("12 km from the shop");
        assertThat(said(99_900)).isEqualTo("99.9 km from the shop");
        assertThat(said(99_960)).isEqualTo("100 km from the shop");
        assertThat(said(250_400)).isEqualTo("250 km from the shop");
    }

    @Test
    void aLocationIsCheckedForRangeAndAccuracy() {
        assertThat(new Fix(0.3136, 32.5811, 15.0).valid()).isTrue();
        assertThat(new Fix(-90.0, -180.0, 0.0).valid()).isTrue();
        assertThat(new Fix(90.1, 0.0, 1.0).valid()).isFalse();
        assertThat(new Fix(0.0, -180.5, 1.0).valid()).isFalse();
        assertThat(new Fix(0.0, 0.0, -0.5).valid()).isFalse();
        assertThat(new Fix(Double.NaN, 0.0, 1.0).valid()).isFalse();
        assertThat(new Fix(0.0, 0.0, Double.POSITIVE_INFINITY).valid()).isFalse();
        assertThat(new Fix(null, 0.0, 1.0).valid()).isFalse();
        assertThat(new Fix(null, 0.0, 1.0).complete()).isFalse();
        assertThat(new Fix(0.0, 0.0, null).complete()).isFalse();
        assertThat(new Fix(91.0, 0.0, 1.0).complete()).isTrue();
    }
}
