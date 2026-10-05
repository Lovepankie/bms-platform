package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KernelTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** FR-MEM-02: common local forms normalise to E.164 (fabricated numbers). */
    @ParameterizedTest
    @CsvSource({
        "0700000001, +256700000001",
        "700000002, +256700000002",
        "256700000003, +256700000003",
        "+256 700 000 004, +256700000004",
        "0700-000-005, +256700000005"
    })
    void phoneNumbersNormaliseToE164(String raw, String expected) {
        assertThat(PhoneNumbers.normaliseUganda(raw)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "0800000001", "+254700000001", "07000000011", "abc"})
    void invalidPhoneNumbersAreRejected(String raw) {
        assertThat(PhoneNumbers.normaliseUganda(raw)).isEmpty();
    }

    /** FR-MEM-03: upper case, no spaces, C[MF] plus 12. */
    @Test
    void ninIsNormalisedAndValidated() {
        assertThat(NationalIds.normaliseNin(" cmtest0000001a ")).contains("CMTEST0000001A");
        assertThat(NationalIds.normaliseNin("CFTEST 0000002B")).contains("CFTEST0000002B");
        assertThat(NationalIds.normaliseNin("CXTEST0000001A")).isEmpty();
        assertThat(NationalIds.normaliseNin("CMTEST01")).isEmpty();
    }

    @Test
    void maskingKeepsTheLastFour() {
        assertThat(Masking.lastFour("+256700000042")).isEqualTo("*********0042");
        assertThat(Masking.lastFour("abc")).isEqualTo("***");
        assertThat(Masking.lastFour(null)).isNull();
    }

    @Test
    void moneyIsExactAndRefusesMixedCurrencies() {
        assertThat(Money.of(150_000, "UGX").plus(Money.of(50_000, "UGX"))).isEqualTo(Money.of(200_000, "UGX"));
        assertThatThrownBy(() -> Money.of(1, "UGX").plus(Money.of(1, "KES")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "UGX").plus(Money.of(1, "UGX")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void aBoundTenantCannotBeSwitchedWithinARequest() {
        UUID a = UUID.randomUUID();
        TenantContext.bind(a);
        TenantContext.bind(a);
        assertThatThrownBy(() -> TenantContext.bind(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.require()).isEqualTo(a);
    }

    @Test
    void requireFailsClosedWithNoTenant() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cursorsRoundTrip() {
        assertThat(Cursor.decode(Cursor.encode("M000042"))).contains("M000042");
        assertThat(Cursor.decode(null)).isEmpty();
    }

    /** Review F8: the shared cursor parser refuses anything but a sort key, a bar and a UUID. */
    @ParameterizedTest
    @ValueSource(strings = {"foo", "foo|bar", "|", "2026-01-01T00:00:00Z|not-a-uuid"})
    void aMalformedCursorIsRefusedWith400(String raw) {
        assertThatThrownBy(() -> Cursor.decodeKey(Cursor.encode(raw)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "malformed_request");
    }

    @Test
    void aCursorKeyRoundTripsAndChecksItsTimestamp() {
        UUID id = UUID.randomUUID();
        Cursor.Key key =
                Cursor.decodeKey(Cursor.encode("2026-01-01T00:00:00Z|" + id)).orElseThrow();
        assertThat(key.id()).isEqualTo(id);
        assertThat(key.at()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThatThrownBy(() -> Cursor.decodeKey(Cursor.encode("NB-1|" + id))
                        .orElseThrow()
                        .at())
                .isInstanceOf(ApiException.class);
        assertThat(Cursor.decodeKey(null)).isEmpty();
    }
}
