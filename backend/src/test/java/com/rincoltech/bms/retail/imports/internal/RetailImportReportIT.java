package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The report text of {@code import-retail} on the fabricated {@code fixtures/retail/import-sample/} (issue #135, acceptance
 * criterion 1): the golden file {@code fixtures/retail/import-sample.report.txt} is the report the command printed before
 * it ran on the import framework, captured from CI on the commit before the wrapper, and the wrapper's report is that text
 * exactly. Only the tenant's slug, which each test makes up, is masked.
 */
class RetailImportReportIT extends IntegrationTest {

    static final Path GOLDEN = Path.of("../fixtures/retail/import-sample.report.txt");

    @Autowired
    RetailImporter importer;

    @Test
    void theReportOnTheSampleIsTheGoldenText() throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-gold", false, true);

        String real =
                masked(importer.run(t.slug(), RetailImportIT.SAMPLE, false).render(), t.slug());

        String expected = Files.isRegularFile(GOLDEN) ? Files.readString(GOLDEN, StandardCharsets.UTF_8) : "";
        if (!real.equals(expected)) {
            // The text in the log, between markers, so a changed report can be read and checked by a person.
            System.out.println(
                    "=== import-retail sample report begin ===\n" + real + "=== import-retail sample report end ===");
        }
        assertThat(real).isEqualTo(expected);
    }

    static String masked(String report, String slug) {
        return report.replace("tenant: " + slug + "\n", "tenant: <tenant>\n");
    }
}
