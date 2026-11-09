package com.rincoltech.bms.core.imports;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar bms-api.jar purge-import --tenant <business id> --run <run id> --operator <email> --witness <email>
 * [--dump-file <name> --dump-sha256 <hex>]} (issue #139, spec sections 2 stage 9 and 6.4): the platform operator's purge of
 * a signed off run, witnessed by a second platform operator, with the pre-import dump they deleted on the host, through
 * {@link ImportPurges#purge}. Run on the host as the other host commands are ({@code docs/runbooks/customer-data-cutover.md}
 * section 6.2): the application starts without the web server and the job scheduler, connected as {@code bms_app}, purges
 * the run and prints its certificate of deletion, which holds file names, sizes, hashes and counts, never a value of a
 * row. On a run the schedule already purged, the dump given issues the certificate's next version.
 *
 * <p>Exit status: 0 purged (or already purged, the certificate printed), 2 for a usage error, 3 refused or failed, with no
 * certificate. A refusal of the run, the operator, the witness or the dump comes before anything is deleted; a storage
 * failure, a run that changed meanwhile or any other failure comes after the files began to be deleted, and running the
 * command again finishes the purge.
 */
public final class PurgeImportCommand {

    public static final String NAME = "purge-import";

    static final String USAGE = "usage: purge-import --tenant <business id> --run <run id> --operator <email>"
            + " --witness <email> [--dump-file <name> --dump-sha256 <hex>]";

    private PurgeImportCommand() {}

    /** The parsed arguments, after the command name. */
    record Options(UUID tenant, UUID run, ImportPurges.Purge purge) {

        static Options parse(String[] args) {
            UUID tenant = null;
            UUID run = null;
            String operator = null;
            String witness = null;
            String dumpFile = null;
            String dumpSha256 = null;
            for (int i = 1; i < args.length; i++) {
                String value = i + 1 < args.length ? args[i + 1] : null;
                switch (args[i]) {
                    case "--tenant" -> tenant = uuid("--tenant", value);
                    case "--run" -> run = uuid("--run", value);
                    case "--operator" -> operator = value;
                    case "--witness" -> witness = value;
                    case "--dump-file" -> dumpFile = value;
                    case "--dump-sha256" -> dumpSha256 = value;
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
                i++;
            }
            if (tenant == null || run == null || blank(operator) || blank(witness)) {
                throw new IllegalArgumentException("--tenant, --run, --operator and --witness are required");
            }
            if (blank(dumpFile) != blank(dumpSha256)) {
                throw new IllegalArgumentException("--dump-file and --dump-sha256 go together");
            }
            return new Options(tenant, run, new ImportPurges.Purge(operator, witness, dumpFile, dumpSha256, origin()));
        }

        /** Where the command runs: the host's name and the user, for the audit row (review of #886, S5). */
        static String origin() {
            String host;
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.io.IOException e) {
                host = "unknown host";
            }
            return "purge-import on " + host + " as " + System.getProperty("user.name", "unknown user");
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        private static UUID uuid(String name, String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException(name + " must be a UUID");
            }
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
        SpringApplication app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "db-scheduler.enabled", "false",
                "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            TenantJobs tenants = context.getBean(TenantJobs.class);
            return run(context.getBean(ImportPurges.class), tenants::callAs, options, System.out, System.err);
        }
    }

    /** {@code asTenant} binds the business as a one-off command does ({@link TenantJobs#callAs}), outside a transaction. */
    static int run(
            ImportPurges purges,
            BiFunction<UUID, Supplier<ImportPurges.Certificate>, ImportPurges.Certificate> asTenant,
            Options options,
            PrintStream out,
            PrintStream err) {
        ImportPurges.Certificate certificate;
        try {
            certificate = asTenant.apply(options.tenant(), () -> purges.purge(options.run(), options.purge()));
        } catch (ApiException e) {
            err.println("purge-import refused: " + e.code());
            return 3;
        } catch (RuntimeException e) {
            // The class only: a message could carry a value (AGENTS.md rule 9). Running it again finishes a purge.
            String failure = e.getClass().getSimpleName();
            err.println(
                    "purge-import failed: " + failure + "; some files may already be deleted, run it again to finish");
            return 3;
        }
        out.print(certificate.document());
        out.println("sha256: " + certificate.sha256());
        return 0;
    }
}
