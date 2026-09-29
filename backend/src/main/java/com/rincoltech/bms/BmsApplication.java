package com.rincoltech.bms;

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
 * before the application containers switch (ADR-006).
 */
@SpringBootApplication
@Modulithic(systemName = "BMS Platform")
@OpenAPIDefinition(info = @Info(title = "BMS Platform API", version = "v1"), servers = @Server(url = "/"))
public class BmsApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("migrate")) {
            System.exit(DatabaseMigrator.runFromEnvironment());
        }
        SpringApplication.run(BmsApplication.class, args);
    }
}
