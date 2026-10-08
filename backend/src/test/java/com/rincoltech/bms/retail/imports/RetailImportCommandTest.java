package com.rincoltech.bms.retail.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RetailImportCommandTest {

    @Test
    void firstLiveDateIsOptionalAndParsedAsADate() {
        String[] base = {"import-retail", "--tenant", "test-shop", "--dir", "/tmp/test-export"};

        assertThat(RetailImportCommand.Options.parse(base).firstLiveDate()).isNull();
        assertThat(RetailImportCommand.Options.parse(new String[] {
                    "import-retail",
                    "--tenant",
                    "test-shop",
                    "--dir",
                    "/tmp/test-export",
                    "--dry-run",
                    "--first-live-date",
                    "2026-10-01"
                }))
                .satisfies(o -> {
                    assertThat(o.firstLiveDate()).isEqualTo(LocalDate.parse("2026-10-01"));
                    assertThat(o.dryRun()).isTrue();
                });
        assertThat(RetailImportCommand.USAGE).contains("--first-live-date yyyy-mm-dd");
    }

    @Test
    void aMissingOrMalformedFirstLiveDateIsAUsageError() {
        assertThatThrownBy(() -> RetailImportCommand.Options.parse(new String[] {
                    "import-retail", "--tenant", "t", "--dir", "/tmp/x", "--first-live-date", "01/10/2026"
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--first-live-date");
        assertThatThrownBy(() -> RetailImportCommand.Options.parse(
                        new String[] {"import-retail", "--tenant", "t", "--dir", "/tmp/x", "--first-live-date"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
