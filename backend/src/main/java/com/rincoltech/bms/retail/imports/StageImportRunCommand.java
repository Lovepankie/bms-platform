package com.rincoltech.bms.retail.imports;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.imports.internal.FabricatedRunStager;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar bms-api.jar stage-import-run --tenant <slug> --dir <path>} (issue #138, review of #882): stages, on
 * staging, a run of the import screens marked fabricated from an {@code import-retail} export directory of fabricated data,
 * reviewed, dry run and ready, and prints its id, so the rehearsal ({@code deploy/restore-drill.sh --rehearse-import}) has
 * a run to rehearse before the operator screens (#141) can upload one. Only on staging ({@code BMS_ENVIRONMENT=staging})
 * and only with the repository's fabricated {@code fixtures/retail/import-sample/}. Starts the application
 * without the web server and the job scheduler, connected as {@code bms_app} like the API.
 *
 * <p>Exit status: 0 staged, 1 refused or failed (the reason's code only), 2 for a usage error.
 */
public final class StageImportRunCommand {

    public static final String NAME = "stage-import-run";

    static final String USAGE = "usage: stage-import-run --tenant <slug> --dir <path>";

    private StageImportRunCommand() {}

    /** The parsed arguments, after the command name. */
    record Options(String tenant, Path dir) {

        static Options parse(String[] args) {
            String tenant = null;
            Path dir = null;
            for (int i = 1; i < args.length; i++) {
                String value = i + 1 < args.length ? args[i + 1] : null;
                switch (args[i]) {
                    case "--tenant" -> tenant = value;
                    case "--dir" -> dir = value == null ? null : Path.of(value);
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
                i++;
            }
            if (tenant == null || tenant.isBlank() || dir == null) {
                throw new IllegalArgumentException("--tenant and --dir are required");
            }
            return new Options(tenant, dir);
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
        // An allowlist (review of #882): an unset, blank or misspelt environment is refused like production.
        if (!"staging".equals(System.getenv("BMS_ENVIRONMENT"))) {
            System.err.println("stage-import-run refused: only on staging (BMS_ENVIRONMENT=staging)");
            return 1;
        }
        SpringApplication app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "db-scheduler.enabled", "false",
                "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            return run(context.getBean(FabricatedRunStager.class), options, System.out, System.err);
        }
    }

    static int run(FabricatedRunStager stager, Options options, PrintStream out, PrintStream err) {
        UUID run;
        try {
            run = stager.stage(options.tenant(), options.dir());
        } catch (ApiException e) {
            err.println("stage-import-run refused: " + e.code());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("stage-import-run refused: " + e.getMessage());
            return 1;
        }
        out.println("staged fabricated import run " + run + " (ready) for business " + options.tenant());
        return 0;
    }
}
