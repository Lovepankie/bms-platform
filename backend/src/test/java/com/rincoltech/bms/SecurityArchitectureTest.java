package com.rincoltech.bms;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Who may set the security context (chapter 8 section 8.3.3, ADR-017): the principal types live
 * in the kernel so every module can read them, but only the identity module's authentication
 * filter sets or clears the current principal. Only the tenancy filter binds a request's tenant,
 * and only jobs and the platform console run work as another tenant (ADR-003, ADR-016).
 */
class SecurityArchitectureTest {

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rincoltech.bms");

    @Test
    void onlyIdentitySetsTheCurrentPrincipal() {
        noClasses()
                .that()
                .resideOutsideOfPackages("com.rincoltech.bms.core.identity..", "com.rincoltech.bms.kernel..")
                .should()
                .callMethod(CurrentPrincipal.class, "set", Principal.class)
                .orShould()
                .callMethod(CurrentPrincipal.class, "clear")
                .check(CLASSES);
    }

    @Test
    void onlyTenancyBindsARequestTenant() {
        noClasses()
                .that()
                .resideOutsideOfPackages("com.rincoltech.bms.core.tenancy..", "com.rincoltech.bms.kernel..")
                .should()
                .callMethod(TenantContext.class, "bind", UUID.class)
                .check(CLASSES);
    }

    @Test
    void onlyJobsAndThePlatformConsoleActAsATenant() {
        noClasses()
                .that()
                .resideOutsideOfPackages(
                        "com.rincoltech.bms.core.jobs..",
                        "com.rincoltech.bms.core.platform..",
                        "com.rincoltech.bms.kernel..")
                .should()
                .callMethod(TenantContext.class, "callAs", UUID.class, Supplier.class)
                .check(CLASSES);
    }
}
