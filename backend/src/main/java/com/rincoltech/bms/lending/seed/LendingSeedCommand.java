package com.rincoltech.bms.lending.seed;

import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.io.PrintStream;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar bms-api.jar seed-lending --tenant <slug> [--insights-demo [--scale <n>]]}
 * ({@code docs/runbooks/seed-lending.md}). With {@code --insights-demo} the tenant also gets twelve
 * months of fabricated history for the insights page (#153), {@code --scale} times the volume.
 * Starts the application without the web server and the job scheduler, connected as
 * {@code bms_app} like the API, and fills one empty lending tenant with fabricated data. Refused
 * when {@code BMS_ENVIRONMENT} is {@code production}.
 *
 * <p>Exit status: 0 when the tenant was seeded, 1 when it was refused, 2 for a usage error.
 */
public final class LendingSeedCommand {

    public static final String NAME = "seed-lending";

    static final String USAGE = "usage: seed-lending --tenant <slug> [--insights-demo [--scale <1-50>]]";

    /** The parsed arguments. */
    record Arguments(String tenant, LendingSeeder.Options options) {}

    private LendingSeedCommand() {}

    /** The tenant slug and options from the arguments after the command name. */
    static Arguments parse(String[] args) {
        String tenant = null;
        boolean demo = false;
        Integer scale = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--tenant") && i + 1 < args.length) {
                tenant = args[++i];
            } else if (args[i].equals("--insights-demo")) {
                demo = true;
            } else if (args[i].equals("--scale") && i + 1 < args.length) {
                try {
                    scale = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--scale is a whole number");
                }
            } else {
                throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("--tenant is required");
        }
        if (scale != null && !demo) {
            throw new IllegalArgumentException("--scale needs --insights-demo");
        }
        return new Arguments(tenant, new LendingSeeder.Options(demo, scale == null ? 1 : scale));
    }

    /** Runs the command from {@code main}; {@code args[0]} is the command name. */
    public static int run(Class<?> application, String[] args) {
        Arguments arguments;
        try {
            arguments = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 2;
        }
        if ("production".equalsIgnoreCase(System.getenv("BMS_ENVIRONMENT"))) {
            System.err.println("seed-lending refused: fabricated data is never written in production");
            return 1;
        }
        SpringApplication app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "db-scheduler.enabled", "false",
                "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            return run(context.getBean(LendingSeeder.class), arguments, System.out, System.err);
        }
    }

    static int run(LendingSeeder seeder, Arguments arguments, PrintStream out, PrintStream err) {
        try {
            out.println(seeder.seed(arguments.tenant(), arguments.options()).render());
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("seed-lending refused: " + e.getMessage());
            return 1;
        }
    }
}
