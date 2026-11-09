package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The rehearse-import command of issue #138 without a database: its arguments, and its exit status for a committed, a
 * stored but not committed and a refused rehearsal. The ids and the backup name are made up.
 */
class RehearseImportCommandTest {

    static final UUID TENANT = UUID.fromString("0b5e7a2c-0000-4000-8000-00000000a001");
    static final UUID RUN = UUID.fromString("0b5e7a2c-0000-4000-8000-00000000c001");
    static final String BACKUP = "bms-staging-20261024T011500Z.dump.enc";

    static String[] args(String... rest) {
        String[] head = {"rehearse-import", "--tenant", TENANT.toString(), "--run", RUN.toString(), "--backup", BACKUP};
        String[] all = new String[head.length + rest.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(rest, 0, all, head.length, rest.length);
        return all;
    }

    @Test
    void theArgumentsAreTheBusinessTheRunTheBackupAndTheHostTimings() {
        RehearseImportCommand.Options options =
                RehearseImportCommand.Options.parse(args("--timings", "download_ms=12,restore_ms=3400"));
        assertThat(options.tenant()).isEqualTo(TENANT);
        assertThat(options.run()).isEqualTo(RUN);
        assertThat(options.backup()).isEqualTo(BACKUP);
        assertThat(options.timings()).containsExactly(Map.entry("download_ms", 12L), Map.entry("restore_ms", 3400L));
        assertThat(RehearseImportCommand.Options.parse(args()).timings()).isEmpty();
    }

    @Test
    void aMissingOrMalformedArgumentIsAUsageError() {
        for (String[] bad : List.of(
                new String[] {"rehearse-import", "--run", RUN.toString(), "--backup", BACKUP},
                new String[] {"rehearse-import", "--tenant", "not-a-uuid", "--run", RUN.toString(), "--backup", BACKUP},
                new String[] {"rehearse-import", "--tenant", TENANT.toString(), "--run", RUN.toString()},
                args("--timings", "download=twelve"),
                args("--timings", "Download_ms=1"),
                args("--force"))) {
            assertThatThrownBy(() -> RehearseImportCommand.Options.parse(bad))
                    .as(String.join(" ", bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static int run(ImportRehearsals rehearsals, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return RehearseImportCommand.run(
                rehearsals,
                (tenant, work) -> work.get(),
                RehearseImportCommand.Options.parse(args()),
                "f00dfeed".repeat(6),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    static ImportRehearsals answering(String outcome, String failure, Boolean matches) {
        return (runId, token, backup, timings) -> new ImportRehearsals.Rehearsal(
                UUID.randomUUID(), outcome, failure, null, matches, Map.of("commit_ms", 5L));
    }

    @Test
    void committedIsZeroStoredButNotCommittedIsOneAndRefusedIsThree() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(run(answering("committed", null, true), out, err)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains(": committed")
                .contains("the report says what the run's dry run said: yes")
                .contains("commit_ms=5");

        out.reset();
        assertThat(run(answering("failed", "TENANT_TRADING", null), out, err)).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("failed (TENANT_TRADING)")
                .contains("not compared");

        out.reset();
        assertThat(run(answering("halted", "halted", false), out, err)).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("said: NO");

        ImportRehearsals refusing = (runId, token, backup, timings) -> {
            throw new ApiException(HttpStatus.CONFLICT, "not_a_rehearsal_database", "Not a rehearsal database", "x");
        };
        assertThat(run(refusing, out, err)).isEqualTo(3);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("rehearse-import refused: not_a_rehearsal_database");
    }
}
