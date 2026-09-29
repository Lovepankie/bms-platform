package com.rincoltech.bms;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies {@code classpath:db/migration} with Flyway, connected as the owner role
 * ({@code bms_owner}), never as the application role. Used by the one-shot migrate container in
 * every environment and by the integration tests, so tests run exactly the migrations production
 * runs.
 */
public final class DatabaseMigrator {

    private DatabaseMigrator() {}

    public static MigrateResult migrate(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                // Each migration runs in its own transaction; a failure leaves the schema at the
                // last good version and the deploy stops before switching containers.
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    static int runFromEnvironment() {
        String url = require("BMS_DB_URL");
        String user = require("BMS_OWNER_USER");
        String password = require("BMS_OWNER_PASSWORD");
        try {
            MigrateResult result = migrate(url, user, password);
            System.out.printf(
                    "migrate: %d migration(s) applied%s%n",
                    result.migrationsExecuted,
                    result.targetSchemaVersion == null
                            ? "; schema already up to date"
                            : ", schema now at " + result.targetSchemaVersion);
            return 0;
        } catch (RuntimeException e) {
            System.err.println("migrate: FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("migrate: environment variable " + name + " is required");
            System.exit(2);
        }
        return value;
    }
}
