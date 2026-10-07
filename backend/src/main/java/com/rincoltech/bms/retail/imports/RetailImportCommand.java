package com.rincoltech.bms.retail.imports;

import com.rincoltech.bms.retail.imports.internal.ImportReport;
import com.rincoltech.bms.retail.imports.internal.RetailImporter;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar bms-api.jar import-retail --tenant <slug> --dir <path> [--dry-run] [--first-live-date yyyy-mm-dd]} (FR-RET-12;
 * {@code docs/runbooks/import-retail.md}). Starts the application without the web server and the
 * job scheduler, connected as {@code bms_app} like the API (the database role guard refuses
 * anything else), imports the export directory into that tenant and prints the report.
 *
 * <p>Exit status: 0 when every file was imported (anomalies are reported, not fatal), 1 when a
 * file failed or the tenant or directory was refused, 2 for a usage error.
 */
public final class RetailImportCommand {

    public static final String NAME = "import-retail";

    static final String USAGE =
            "usage: import-retail --tenant <slug> --dir <path> [--dry-run] [--first-live-date yyyy-mm-dd]";

    private RetailImportCommand() {}

    /** The parsed arguments, after the command name. */
    record Options(String tenant, Path dir, boolean dryRun, LocalDate firstLiveDate) {

        static Options parse(String[] args) {
            String tenant = null;
            Path dir = null;
            boolean dryRun = false;
            LocalDate firstLive = null;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--tenant" -> tenant = ++i < args.length ? args[i] : null;
                    case "--dir" -> dir = ++i < args.length ? Path.of(args[i]) : null;
                    case "--dry-run" -> dryRun = true;
                    case "--first-live-date" -> firstLive = date(++i < args.length ? args[i] : null);
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
            }
            if (tenant == null || tenant.isBlank() || dir == null) {
                throw new IllegalArgumentException("--tenant and --dir are required");
            }
            return new Options(tenant, dir, dryRun, firstLive);
        }

        private static LocalDate date(String text) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException | NullPointerException e) {
                throw new IllegalArgumentException("--first-live-date must be a date as yyyy-mm-dd");
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
            return run(context.getBean(RetailImporter.class), options, System.out, System.err);
        }
    }

    static int run(RetailImporter importer, Options options, PrintStream out, PrintStream err) {
        ImportReport report;
        try {
            report = importer.run(options.tenant(), options.dir(), options.dryRun(), options.firstLiveDate());
        } catch (IllegalArgumentException e) {
            err.println("import-retail refused: " + e.getMessage());
            return 1;
        }
        out.print(report.render());
        return report.failed() ? 1 : 0;
    }
}
