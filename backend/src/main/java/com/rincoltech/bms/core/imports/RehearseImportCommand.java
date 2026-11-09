package com.rincoltech.bms.core.imports;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar bms-api.jar rehearse-import --tenant <business id> --run <run id> --backup <name> [--timings
 * name_ms=n,...]} (issue #138, ADR-027 decision 9): the rehearsal of {@link ImportRehearsals}, started only by
 * {@code deploy/restore-drill.sh --rehearse-import} in its short-lived API container, against its throwaway database, with the
 * one-time token in {@code BMS_REHEARSAL_TOKEN}. Starts the application without the web server and the job scheduler,
 * connected as {@code bms_app} like the API, rehearses the run and prints the outcome, the timings and the report, which
 * holds codes, counts and amounts only.
 *
 * <p>Exit status: 0 committed, 1 stored but not committed (halted or failed), 2 for a usage error, 3 refused (nothing
 * stored: not the rehearsal's database, on staging a run not marked fabricated, or a run neither in review nor ready).
 */
public final class RehearseImportCommand {

    public static final String NAME = "rehearse-import";

    static final String USAGE =
            "usage: rehearse-import --tenant <business id> --run <run id> --backup <name> [--timings name_ms=n,...]";

    private static final Pattern TIMING = Pattern.compile("[a-z_]{1,40}=[0-9]{1,15}");

    private RehearseImportCommand() {}

    /** The parsed arguments, after the command name. */
    record Options(UUID tenant, UUID run, String backup, Map<String, Long> timings) {

        static Options parse(String[] args) {
            UUID tenant = null;
            UUID run = null;
            String backup = null;
            Map<String, Long> timings = new LinkedHashMap<>();
            for (int i = 1; i < args.length; i++) {
                String value = i + 1 < args.length ? args[i + 1] : null;
                switch (args[i]) {
                    case "--tenant" -> tenant = uuid("--tenant", value);
                    case "--run" -> run = uuid("--run", value);
                    case "--backup" -> backup = value;
                    case "--timings" -> timings = timings(value);
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
                i++;
            }
            if (tenant == null || run == null || backup == null || backup.isBlank() || backup.length() > 200) {
                throw new IllegalArgumentException("--tenant, --run and --backup are required");
            }
            return new Options(tenant, run, backup, timings);
        }

        private static UUID uuid(String name, String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException(name + " must be a UUID");
            }
        }

        private static Map<String, Long> timings(String value) {
            Map<String, Long> timings = new LinkedHashMap<>();
            if (value == null || value.isEmpty()) {
                return timings;
            }
            for (String part : value.split(",")) {
                if (!TIMING.matcher(part).matches()) {
                    throw new IllegalArgumentException("--timings takes name_ms=n pairs separated by commas");
                }
                int eq = part.indexOf('=');
                timings.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
            }
            return timings;
        }
    }

    /** Runs the command from {@code main}; {@code args[0]} is the command name. */
    public static int run(Class<?> application, String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 2;
        }
        String token = System.getenv("BMS_REHEARSAL_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("BMS_REHEARSAL_TOKEN is required: the rehearsal runs only from deploy/restore-drill.sh");
            return 2;
        }
        SpringApplication app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "db-scheduler.enabled", "false",
                "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            TenantJobs tenants = context.getBean(TenantJobs.class);
            return run(
                    context.getBean(ImportRehearsals.class), tenants::callAs, options, token, System.out, System.err);
        }
    }

    /** {@code asTenant} binds the business as a one-off command does ({@link TenantJobs#callAs}), outside a transaction. */
    static int run(
            ImportRehearsals rehearsals,
            BiFunction<UUID, Supplier<ImportRehearsals.Rehearsal>, ImportRehearsals.Rehearsal> asTenant,
            Options options,
            String token,
            PrintStream out,
            PrintStream err) {
        ImportRehearsals.Rehearsal rehearsal;
        try {
            rehearsal = asTenant.apply(
                    options.tenant(),
                    () -> rehearsals.rehearse(options.run(), token, options.backup(), options.timings()));
        } catch (ApiException e) {
            err.println("rehearse-import refused: " + e.code());
            return 3;
        }
        out.println("rehearsal " + rehearsal.id() + " of import run " + options.run() + " on " + options.backup() + ": "
                + rehearsal.outcome() + (rehearsal.failure() == null ? "" : " (" + rehearsal.failure() + ")"));
        out.println("the report says what the run's dry run said: "
                + (rehearsal.matchesDryRun() == null
                        ? "not compared (no report or no stored dry run)"
                        : rehearsal.matchesDryRun() ? "yes" : "NO"));
        out.println("timings in milliseconds: " + rehearsal.timings());
        if (rehearsal.report() != null) {
            out.print(rehearsal.report().render());
        }
        return "committed".equals(rehearsal.outcome()) ? 0 : 1;
    }
}
