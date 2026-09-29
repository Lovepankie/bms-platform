package com.rincoltech.bms.core.operations.internal;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Readiness: the database schema is at least the newest migration this build contains
 * (chapter 7 section 7.11.1, "migrations at head"). A schema ahead of the code is ready, because
 * migrations are expand and contract and a rollback runs the previous image against the newer
 * schema (chapter 6 section 6.9). A schema behind the code is not ready: the one-shot migration
 * did not run, and the deploy script rolls back.
 */
@Component("migrations")
class MigrationsHealthIndicator extends AbstractHealthIndicator {

    private static final Pattern VERSIONED = Pattern.compile("^V([0-9._]+)__.*\\.sql$");

    private final JdbcClient jdbc;
    private final MigrationVersion expected;

    MigrationsHealthIndicator(JdbcClient jdbc) throws IOException {
        this.jdbc = jdbc;
        Resource[] scripts =
                new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
        this.expected = Arrays.stream(scripts)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .map(VERSIONED::matcher)
                .filter(Matcher::matches)
                .map(m -> MigrationVersion.fromVersion(m.group(1).replace('_', '.')))
                .max(Comparator.naturalOrder())
                .orElse(MigrationVersion.EMPTY);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Optional<MigrationVersion> applied = jdbc
                .sql("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL")
                .query(String.class)
                .list()
                .stream()
                .map(MigrationVersion::fromVersion)
                .max(Comparator.naturalOrder());
        boolean atHead = applied.isPresent() && applied.get().compareTo(expected) >= 0;
        (atHead ? builder.up() : builder.down())
                .withDetail("expected", expected.getVersion())
                .withDetail("applied", applied.map(MigrationVersion::getVersion).orElse("none"));
    }
}
