package com.rincoltech.bms;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * One clock (chapter 5 section 5.4.5, chapter 15 section 15.5): outside the kernel, nothing
 * reads the system clock directly. Use {@code BusinessClock}, which tests can fix.
 */
class ClockArchitectureTest {

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rincoltech.bms");

    @Test
    void noSystemClockReadsOutsideTheKernel() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.rincoltech.bms.kernel..")
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .check(CLASSES);
    }
}
