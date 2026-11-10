package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

/** The batch's failure reason and the parse's timeout check, without a database (review of 339d6a2 on #790). */
class UploadServiceTest {

    @Test
    void aReasonIsCutWithoutSplittingASurrogatePair() {
        String pair = new String(Character.toChars(0x1F600));
        assertThat(UploadService.truncate("x".repeat(299) + pair + "tail", 300)).isEqualTo("x".repeat(299));
        assertThat(UploadService.truncate("x".repeat(298) + pair + "tail", 300)).isEqualTo("x".repeat(298) + pair);
        assertThat(UploadService.truncate("short", 300)).isEqualTo("short");
    }

    /** Issues #137, #880 and #900: production holds the row limit the big file test measured safe on the staging heap. */
    @Test
    void theRowLimitIsTheMeasuredTwentyFiveThousandUntil900() throws java.io.IOException {
        assertThat(new UploadSettings.Properties(null, null, null, null, null, null).maxRows())
                .isEqualTo(25_000);
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml")))
                .contains("max-rows: ${BMS_IMPORTS_MAX_ROWS:25000}");
    }

    @Test
    void aStatementTimeoutIsFoundHoweverSpringWrapsIt() {
        assertThat(UploadService.queryTimedOut(new QueryTimeoutException("timed out")))
                .isTrue();
        SQLException cancelled = new SQLException("canceling statement due to statement timeout", "57014");
        assertThat(UploadService.queryTimedOut(new TransientDataAccessResourceException("insert", cancelled)))
                .isTrue();
        SQLException lost = new SQLException("connection lost", "08006");
        assertThat(UploadService.queryTimedOut(new TransientDataAccessResourceException("insert", lost)))
                .isFalse();
        assertThat(UploadService.queryTimedOut(new DataIntegrityViolationException("too long")))
                .isFalse();
    }
}
