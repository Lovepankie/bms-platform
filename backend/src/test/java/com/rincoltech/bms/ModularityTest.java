package com.rincoltech.bms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleDependency;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module boundary test of ADR-002 and chapter 15 section 15.5 (NFR-MNT-01). The allowed
 * dependencies of each module are data: the {@code allowedDependencies} of its
 * {@code package-info.java}, so an architecture change shows up in review as a change to that
 * line. {@link ApplicationModules#verify()} fails on a cycle, on use of another module's
 * internals, and on any dependency not in the allowed list.
 */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(BmsApplication.class);

    @Test
    void modulesRespectTheirDeclaredBoundaries() {
        MODULES.verify();
    }

    @Test
    void theExpectedModulesExist() {
        assertThat(identifiers())
                .containsExactlyInAnyOrder(
                        "kernel",
                        "core.tenancy",
                        "core.identity",
                        "core.audit",
                        "core.documents",
                        "core.ledger",
                        "core.jobs",
                        "core.operations",
                        "core.notifications",
                        "core.approvals",
                        "core.platform",
                        "core.onboarding",
                        "lending.collateral",
                        "lending.loans",
                        "lending.manifest",
                        "lending.members",
                        "lending.products",
                        "lending.savings",
                        "lending.seed",
                        "retail.catalogue",
                        "retail.imports",
                        "retail.manifest",
                        "retail.purchasing",
                        "retail.reports",
                        "retail.sales",
                        "retail.stock");
    }

    /** ADR-001: the core never depends on a vertical, whatever a package-info might allow. */
    @Test
    void coreNeverDependsOnAVertical() {
        for (ApplicationModule module : MODULES) {
            if (!module.getIdentifier().toString().startsWith("core.")) {
                continue;
            }
            List<String> targets = module.getDirectDependencies(MODULES).stream()
                    .map(ApplicationModuleDependency::getTargetModule)
                    .map(m -> m.getIdentifier().toString())
                    .toList();
            assertThat(targets)
                    .as("dependencies of %s", module.getIdentifier())
                    .noneMatch(t -> t.startsWith("lending.") || t.startsWith("retail."));
        }
    }

    /** ADR-020: retail uses core modules only and never depends on a lending package. */
    @Test
    void retailNeverDependsOnLending() {
        List<String> retail =
                identifiers().stream().filter(id -> id.startsWith("retail.")).toList();
        assertThat(retail).isNotEmpty();
        for (ApplicationModule module : MODULES) {
            if (!module.getIdentifier().toString().startsWith("retail.")) {
                continue;
            }
            List<String> targets = module.getDirectDependencies(MODULES).stream()
                    .map(ApplicationModuleDependency::getTargetModule)
                    .map(m -> m.getIdentifier().toString())
                    .toList();
            assertThat(targets)
                    .as("dependencies of %s", module.getIdentifier())
                    .noneMatch(t -> t.startsWith("lending."));
        }
    }

    /** ADR-002 rule 4: the shared kernel depends on nothing else in the system. */
    @Test
    void kernelDependsOnNothing() {
        ApplicationModule kernel = MODULES.getModuleByName("kernel").orElseThrow();
        assertThat(kernel.getDirectDependencies(MODULES).isEmpty()).isTrue();
    }

    private static List<String> identifiers() {
        return StreamSupport.stream(MODULES.spliterator(), false)
                .map(m -> m.getIdentifier().toString())
                .toList();
    }
}
