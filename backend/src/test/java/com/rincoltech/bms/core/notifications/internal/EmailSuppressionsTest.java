package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The key of the suppression hash (ADR-117 decision 9, fourth #759 review): a blank {@code BMS_DATA_KEY} stops the start
 * outside the dev and test profiles, and the key is never random, so a restart never silently forgets a suppression.
 * The data key and the address are fabricated.
 */
class EmailSuppressionsTest {

    static final String ADDRESS = "staff-01@suppress.example.test";

    static EmailSuppressions build(String dataKey, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new EmailSuppressions(null, null, dataKey, environment);
    }

    @Test
    void aBlankDataKeyStopsTheStartOutsideDevAndTest() {
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> build(blank, "server"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BMS_DATA_KEY");
            assertThatThrownBy(() -> build(blank)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theHashKeyIsNeverRandom() {
        // Without a data key in dev and test: the same fixed key on every start, never a random one.
        assertThat(build("", "test").hash(ADDRESS)).isEqualTo(build(null, "dev").hash(ADDRESS));
        // With a data key, in any profile: the same hash on every start, and another than without one.
        String key = "ZmFicmljYXRlZC10ZXN0LWRhdGEta2V5LTAwMDAwMDA=";
        assertThat(build(key, "server").hash(ADDRESS))
                .isEqualTo(build(key, "server").hash(ADDRESS))
                .isNotEqualTo(build("", "test").hash(ADDRESS))
                .hasSize(64);
    }
}
