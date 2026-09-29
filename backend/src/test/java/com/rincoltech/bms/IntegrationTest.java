package com.rincoltech.bms;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests (*IT, run by Failsafe): the full application on a random port,
 * connected as bms_app to the shared {@link TestDatabase}. Every subclass shares one cached
 * application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", () -> "bms_app");
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
    }
}
