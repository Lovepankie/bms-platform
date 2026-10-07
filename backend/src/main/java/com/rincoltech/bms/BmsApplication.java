package com.rincoltech.bms;

import com.rincoltech.bms.core.identity.ProvisioningKeys;
import com.rincoltech.bms.lending.seed.LendingSeedCommand;
import com.rincoltech.bms.retail.imports.RetailImportCommand;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * BMS Platform API: one deployable, modules by package (ADR-002, ADR-010).
 *
 * <p>{@code java -jar bms-api.jar migrate} runs the database migrations as the owner role and
 * exits, without starting the web application. The deploy script runs it as a one-shot container
 * before the application containers switch (ADR-006). {@code java -jar bms-api.jar keys} prints new
 * sign-in key material for a host env file and exits (chapter 8 section 8.7). {@code java -jar
 * bms-api.jar import-retail --tenant <slug> --dir <path> [--dry-run]} imports a retail export into
 * one tenant as {@code bms_app}, prints its report and exits ({@code docs/runbooks/import-retail.md}).
 * {@code java -jar bms-api.jar seed-lending --tenant <slug>} fills one empty lending tenant with
 * fabricated data on staging and exits; it is refused in production ({@code docs/runbooks/seed-lending.md}).
 * Neither command runs unless named: the image's default startup is the web application.
 */
@SpringBootApplication
@Modulithic(systemName = "BMS Platform")
@OpenAPIDefinition(info = @Info(title = "BMS Platform API", version = "v1"), servers = @Server(url = "/"))
public class BmsApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("migrate")) {
            System.exit(DatabaseMigrator.runFromEnvironment());
        }
        if (args.length > 0 && args[0].equals("keys")) {
            System.out.print(ProvisioningKeys.envLines());
            return;
        }
        if (args.length > 0 && args[0].equals(RetailImportCommand.NAME)) {
            System.exit(RetailImportCommand.run(BmsApplication.class, args));
        }
        if (args.length > 0 && args[0].equals(LendingSeedCommand.NAME)) {
            System.exit(LendingSeedCommand.run(BmsApplication.class, args));
        }
        SpringApplication.run(BmsApplication.class, args);
    }
}
