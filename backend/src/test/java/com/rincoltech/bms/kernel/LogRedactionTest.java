package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** NFR-SEC-08, ADR-059, issue #426: personal data never reaches a log line. All values are fabricated. */
class LogRedactionTest {

    @Test
    void emailsPhonesAndNinsAreReplaced() {
        assertThat(LogRedaction.redact("Reset asked for test.borrower+01@example.test by +256700000001"))
                .isEqualTo("Reset asked for [email] by [phone]");
        assertThat(LogRedaction.redact("phones 0700000002, 256700000003, +256 700 000 004 and 0700-000-005"))
                .isEqualTo("phones [phone], [phone], [phone] and [phone]");
        assertThat(LogRedaction.redact("member CMTEST0000001A has no photo")).isEqualTo("member [nin] has no photo");
    }

    /** The PostgreSQL duplicate key detail of the readiness audit (M8): it prints the phone it refused. */
    @Test
    void aDuplicateKeyDetailLosesTheValue() {
        String detail =
                "Key (tenant_id, phone_e164)=(1b4e28ba-2fa1-11d2-883f-0016d3cca427, +256700000009) already exists.";
        assertThat(LogRedaction.redact(detail))
                .isEqualTo(
                        "Key (tenant_id, phone_e164)=(1b4e28ba-2fa1-11d2-883f-0016d3cca427, [phone]) already exists.");
    }

    @Test
    void tokensSecretsAndNamedFieldsAreReplaced() {
        assertThat(LogRedaction.redact("GET /sign-in#token=AbCdEf0123456789xyz&next=/home"))
                .isEqualTo("GET /sign-in#token=[redacted]&next=/home");
        assertThat(LogRedaction.redact("{\"password\": \"Wrong-Password-1\", \"login\": \"x\"}"))
                .isEqualTo("{\"password\": \"[redacted]\", \"login\": \"x\"}");
        assertThat(LogRedaction.redact("Authorization: Bearer abc.def-ghi"))
                .isEqualTo("Authorization: Bearer [redacted]");
        assertThat(LogRedaction.redact("sent eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln to the client"))
                .isEqualTo("sent [redacted] to the client");
        assertThat(LogRedaction.redact("Member[fullName=Test Borrower 01, branch=head]"))
                .isEqualTo("Member[fullName=[name], branch=head]");
        assertThat(LogRedaction.redact("{\"full_name\":\"Test Borrower 02\"}")).isEqualTo("{\"full_name\":[name]}");
    }

    /** Review of #433, finding 4: a secret key at the end of a longer name, the Basic scheme, and a whole quoted value. */
    @Test
    void secretKeysInsideLongerNamesAndWholeQuotedValuesAreReplaced() {
        assertThat(LogRedaction.redact("{\"newPassword\":\"Fabricated-Pass-2026\"}"))
                .isEqualTo("{\"newPassword\":\"[redacted]\"}");
        assertThat(LogRedaction.redact("client_secret=fabricated-secret-01&grant=x"))
                .isEqualTo("client_secret=[redacted]&grant=x");
        assertThat(LogRedaction.redact("BMS_SMTP_PASSWORD=fabricated-01")).isEqualTo("BMS_SMTP_PASSWORD=[redacted]");
        assertThat(LogRedaction.redact("resetToken=AbCdEf012345")).isEqualTo("resetToken=[redacted]");
        assertThat(LogRedaction.redact("invitation_token=AbCdEf012345 sent"))
                .isEqualTo("invitation_token=[redacted] sent");
        assertThat(LogRedaction.redact("x-api-key: fabricated-key-01")).isEqualTo("x-api-key: [redacted]");
        assertThat(LogRedaction.redact("Authorization: Basic dXNlcjpwYXNz"))
                .isEqualTo("Authorization: Basic [redacted]");
        assertThat(LogRedaction.redact("header Basic dXNlcjpwYXNz seen")).isEqualTo("header Basic [redacted] seen");
        assertThat(LogRedaction.redact("basic information stays")).isEqualTo("basic information stays");
        assertThat(LogRedaction.redact("{\"password\": \"correct horse battery\"}"))
                .isEqualTo("{\"password\": \"[redacted]\"}");
        assertThat(LogRedaction.redact("{\"password\": \"has \\\"quote\\\" inside\", \"x\": 1}"))
                .isEqualTo("{\"password\": \"[redacted]\", \"x\": 1}");
        assertThat(LogRedaction.redact("password='a b c' next")).isEqualTo("password='[redacted]' next");
        // A line cut inside the value loses the rest of it.
        assertThat(LogRedaction.redact("cut {\"password\": \"correct horse"))
                .isEqualTo("cut {\"password\": \"[redacted]\"");
    }

    /**
     * Deliberate, pinned by the delta review of #433: a secret key matches at the end of a longer name with no word boundary
     * (that is how {@code resetToken=} and {@code BMS_SMTP_PASSWORD=} are caught), so a harmless key that happens to end in
     * one, such as {@code spin=} or {@code shipping_pin=}, loses its value too. A redacted harmless value is the safe
     * direction; a key that merely contains one inside it ({@code shipping=}, {@code pinned=}) is left alone.
     */
    @Test
    void aKeyEndingInASecretWordLosesItsValueOnPurpose() {
        assertThat(LogRedaction.redact("spin=3 shipping_pin=4411"))
                .isEqualTo("spin=[redacted] shipping_pin=[redacted]");
        assertThat(LogRedaction.redact("shipping=courier pinned=true tokens=4"))
                .isEqualTo("shipping=courier pinned=true tokens=4");
    }

    /** Review of #433, finding 5: a URL-encoded plus, spaced landlines and NINs in lower case. Fabricated numbers only. */
    @Test
    void ugandaPhoneAndNinFormsAreReplaced() {
        assertThat(LogRedaction.redact("to=%2B256772123456&x=1")).isEqualTo("to=[phone]&x=1");
        assertThat(LogRedaction.redact("to=x%3D%2B256772123456")).isEqualTo("to=x%3D[phone]");
        assertThat(LogRedaction.redact("office 0414 123 456 or +256 414 123 456 or 0392-123-456"))
                .isEqualTo("office [phone] or [phone] or [phone]");
        assertThat(LogRedaction.redact("nin cm900123456abc on file")).isEqualTo("nin [nin] on file");
        assertThat(LogRedaction.redact("nin Cf900123456ABC on file")).isEqualTo("nin [nin] on file");
        assertThat(LogRedaction.redact("call +447700900123 or 447700900123 now"))
                .isEqualTo("call [phone] or [phone] now");
    }

    /** Review of #433: a lending ledger needs its amounts, times and ids; a labelled or time-shaped digit run stays. */
    @Test
    void labelledAmountsTimesAndIdsStay() {
        for (String line : List.of(
                "amount=1500000000 posted",
                "{\"amount_minor\": 150000000000}",
                "principal_minor=250000000000 balance=120000000000",
                "loan id=123456789012 ok",
                "at 1729500000123 and 1729500000",
                "stamp 20261021120005",
                "ts=1729500000")) {
            assertThat(LogRedaction.redact(line)).as(line).isEqualTo(line);
        }
    }

    /** Review of #433: a plain name key and a Java record's toString, the most likely way a name reaches a log. */
    @Test
    void aPlainNameKeyAndARecordToStringAreReplaced() {
        assertThat(LogRedaction.redact("Customer[name=Test Person 01]")).isEqualTo("Customer[name=[name]]");
        assertThat(LogRedaction.redact("Customer[id=7, name=Test Person 02, phone=x]"))
                .isEqualTo("Customer[id=7, name=[name], phone=x]");
        assertThat(LogRedaction.redact("{\"name\":\"Test Person 03\"}")).isEqualTo("{\"name\":[name]}");
        assertThat(LogRedaction.redact("file_name=report.pdf hostname=api"))
                .isEqualTo("file_name=report.pdf hostname=api");
        assertThat(LogRedaction.redact("Sent to test.user@example.test.")).isEqualTo("Sent to [email].");
    }

    /**
     * Review of #433, finding 6: every pattern is linear. A 50,000 character adversarial line (the old email pattern
     * rescanned a run of letters and dots from every position, 0.77 s at 20,000) redacts in well under 50 ms, and a long
     * quoted value cannot overflow the regex engine's stack.
     */
    @Test
    void longAdversarialLinesRedactInLinearTime() {
        for (String line : List.of(
                "a.".repeat(25_000),
                "a.".repeat(25_000) + "@",
                "a".repeat(50_000) + "@example.test",
                "x@" + "b.".repeat(25_000) + "1",
                "1".repeat(50_000),
                "%2B".repeat(16_000),
                "eyJ".repeat(16_000),
                "a@".repeat(25_000),
                "name=" + "x".repeat(50_000),
                "{\"password\":\"" + "\\\"".repeat(16_000) + "\"}",
                "{\"password\":\"" + "y".repeat(50_000))) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long started = System.nanoTime();
                LogRedaction.redact(line);
                best = Math.min(best, System.nanoTime() - started);
            }
            assertThat(best / 1_000_000)
                    .as("%s... (%s characters)", line.substring(0, 12), line.length())
                    .isLessThan(50);
        }
    }

    /** What operators need to read a log stays: ids, codes, dates, small numbers, class names and stack frames. */
    @Test
    void ordinaryLogTextIsLeftAlone() {
        String line = "Report run 1b4e28ba-2fa1-11d2-883f-0016d3cca427 failed on 2026-10-21 12:00:05 after 1500 ms"
                + " (code=rate_limited, rows=200000) at com.rincoltech.bms.core.reporting.internal.ReportRunner.tick"
                + "(ReportRunner.java:120) request_id=0123456789abcdef0123456789abcdef task=core.reporting.report-runner";
        assertThat(LogRedaction.redact(line)).isEqualTo(line);
        assertThat(LogRedaction.redact(null)).isNull();
    }

    /** The real path: Spring Boot's ECS encoder with the customizer the server profile names, message and exception. */
    @Test
    void theServerProfileEncoderRedactsMessageExceptionAndMdc() {
        LoggerContext context = new LoggerContext();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.structured.json.customizer", LogRedaction.class.getName())
                .withProperty("spring.application.name", "bms-api");
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        Logger logger = context.getLogger("com.rincoltech.bms.test");
        LoggingEvent event = new LoggingEvent(
                Logger.class.getName(),
                logger,
                Level.ERROR,
                "Sign-in failed for {}",
                new IllegalStateException("duplicate phone +256700000010 for test.staff@example.test"),
                new Object[] {"test.cashier@example.test"});
        event.setMDCPropertyMap(Map.of("request_id", "0123456789abcdef", "login", "0700000011"));

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
        encoder.stop();

        assertThat(json)
                .contains("Sign-in failed for [email]")
                .contains("duplicate phone [phone] for [email]")
                .contains("0123456789abcdef")
                .doesNotContain("example.test")
                .doesNotContain("700000010")
                .doesNotContain("0700000011");
    }
}
