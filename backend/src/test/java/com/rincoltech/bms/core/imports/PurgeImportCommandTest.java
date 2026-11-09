package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The purge-import command of issue #139 without a database: its arguments, and its exit status for a purge and a
 * refusal. The ids, emails and the dump are made up.
 */
class PurgeImportCommandTest {

    static final UUID TENANT = UUID.fromString("0b5e7a2c-0000-4000-8000-00000000a139");
    static final UUID RUN = UUID.fromString("0b5e7a2c-0000-4000-8000-00000000c139");

    static String[] args(String... rest) {
        String[] head = {
            "purge-import",
            "--tenant",
            TENANT.toString(),
            "--run",
            RUN.toString(),
            "--operator",
            "operator@example.test",
            "--witness",
            "witness@example.test"
        };
        String[] all = new String[head.length + rest.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(rest, 0, all, head.length, rest.length);
        return all;
    }

    @Test
    void theArgumentsAreTheBusinessTheRunTheOperatorTheWitnessAndTheDump() {
        PurgeImportCommand.Options options = PurgeImportCommand.Options.parse(
                args("--dump-file", "pre-import-fabricated.dump", "--dump-sha256", "e".repeat(64)));
        assertThat(options.tenant()).isEqualTo(TENANT);
        assertThat(options.run()).isEqualTo(RUN);
        assertThat(options.purge().operatorEmail()).isEqualTo("operator@example.test");
        assertThat(options.purge().witnessEmail()).isEqualTo("witness@example.test");
        assertThat(options.purge().dumpFile()).isEqualTo("pre-import-fabricated.dump");
        assertThat(options.purge().dumpSha256()).isEqualTo("e".repeat(64));
        assertThat(options.purge().origin()).startsWith("purge-import on ").contains(" as ");
        assertThat(PurgeImportCommand.Options.parse(args()).purge().dumpFile()).isNull();
    }

    @Test
    void aMissingOrMalformedArgumentIsAUsageError() {
        for (String[] bad : List.of(
                new String[] {"purge-import", "--run", RUN.toString(), "--operator", "a@example.test"},
                new String[] {"purge-import", "--tenant", "not-a-uuid", "--run", RUN.toString()},
                new String[] {"purge-import", "--tenant", TENANT.toString(), "--run", RUN.toString()},
                args("--dump-file", "pre-import-fabricated.dump"),
                args("--force"))) {
            assertThatThrownBy(() -> PurgeImportCommand.Options.parse(bad))
                    .as(String.join(" ", bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static ImportPurges answering(ImportPurges.Certificate certificate, ApiException refusal) {
        return new ImportPurges() {
            @Override
            public Certificate purge(UUID runId, Purge request) {
                if (refusal != null) {
                    throw refusal;
                }
                return certificate;
            }

            @Override
            public Optional<Certificate> certificate(UUID runId) {
                return Optional.ofNullable(certificate);
            }
        };
    }

    static int run(ImportPurges purges, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return PurgeImportCommand.run(
                purges,
                (tenant, work) -> work.get(),
                PurgeImportCommand.Options.parse(args()),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void aPurgePrintsTheCertificateAndARefusalIsThree() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ImportPurges.Certificate certificate = new ImportPurges.Certificate(
                UUID.randomUUID(),
                RUN,
                1,
                Instant.parse("2026-11-09T10:00:00Z"),
                "operator",
                true,
                "BMS certificate of deletion, version 1\n",
                "c".repeat(64));
        assertThat(run(answering(certificate, null), out, err)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("BMS certificate of deletion, version 1")
                .contains("sha256: " + "c".repeat(64));

        ApiException refused =
                new ApiException(HttpStatus.CONFLICT, "run_not_signed_off", "Import not signed off", "x");
        assertThat(run(answering(null, refused), out, err)).isEqualTo(3);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("purge-import refused: run_not_signed_off");

        // Any other failure is exit 3 too, with its class and never its message, which could hold a value.
        ImportPurges failing = new ImportPurges() {
            @Override
            public Certificate purge(UUID runId, Purge request) {
                throw new IllegalStateException("a message that must not be printed");
            }

            @Override
            public Optional<Certificate> certificate(UUID runId) {
                return Optional.empty();
            }
        };
        err.reset();
        assertThat(run(failing, out, err)).isEqualTo(3);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("purge-import failed: IllegalStateException")
                .doesNotContain("must not be printed");
    }
}
