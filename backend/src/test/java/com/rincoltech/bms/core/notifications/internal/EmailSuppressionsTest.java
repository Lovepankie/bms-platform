package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
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
        return new EmailSuppressions(
                null, null, dataKey, environment, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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

    // JUSTIFICATION-A3: the new test issue #777 item 1 asks for; no existing test reads the main application.yml.
    @Test
    void theDataKeyIsBmsDataKeyThroughTheRealApplicationYml() throws Exception {
        // Issue #777: the test profile has a key of its own, so this binds the constructor's own @Value placeholder
        // against the main application.yml alone, with BMS_DATA_KEY set as a host's environment would set it.
        String placeholder = Arrays.stream(EmailSuppressions.class.getDeclaredConstructors()[0].getParameters())
                .map(p -> p.getAnnotation(Value.class))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow()
                .value();
        String key = "ZmFicmljYXRlZC1ob3N0LWRhdGEta2V5LTAwMDAwMDE=";

        assertThat(applicationYml(Map.of("BMS_DATA_KEY", key)).resolvePlaceholders(placeholder))
                .isEqualTo(key);
        assertThat(applicationYml(Map.of()).resolvePlaceholders(placeholder))
                .as("unset on the host: blank, which stops the start")
                .isBlank();
        String bound = applicationYml(Map.of("BMS_DATA_KEY", key)).resolvePlaceholders(placeholder);
        assertThat(build(bound, "server").hash(ADDRESS))
                .isEqualTo(build(key, "server").hash(ADDRESS));
    }

    /** The main application.yml under a host environment of {@code variables} only, as a server would read it. */
    static StandardEnvironment applicationYml(Map<String, Object> variables) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource("host", variables));
        for (PropertySource<?> document :
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))) {
            sources.addLast(document);
        }
        return environment;
    }
}
