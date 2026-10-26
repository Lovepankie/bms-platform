package com.rincoltech.bms;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The PostgreSQL driver is on the compile classpath only so {@code UserService.duplicateContact} can read which unique
 * index refused a staff contact (issue #323, ADR-085). Everything else goes through Spring's JDBC exceptions, so no other
 * class imports {@code org.postgresql} (issue #573).
 */
class DriverArchitectureTest {

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rincoltech.bms");

    @Test
    void onlyTheStaffUserServiceReadsTheDriversErrors() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("com.rincoltech.bms.core.identity.internal.UserService")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.postgresql..")
                .check(CLASSES);
    }
}
