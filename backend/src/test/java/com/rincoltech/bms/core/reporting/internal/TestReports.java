package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Synthetic reports for the framework tests (ADR-038): they stand in for a vertical's reports so that the queue, the
 * snapshot, the caps, the files and the failures are proved without any lending data. Every name and value is
 * fabricated. They are registered through the same {@link ReportDefinition} bean contract as {@code lending.par}.
 */
@TestConfiguration
@org.springframework.context.annotation.Import(com.rincoltech.bms.core.documents.internal.WatchedStorage.Config.class)
public class TestReports {

    /** 2026-10-08 10:00 UTC, 13:00 in Kampala. */
    public static final Instant START = Instant.parse("2026-10-08T10:00:00Z");

    /** A clock a test can move, so expiry is proved without waiting 30 days. */
    public static final class MovableClock extends Clock {
        private volatile Instant now = START;

        public void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Held by a gated run until the test lets it go. */
    public static volatile CountDownLatch gate = new CountDownLatch(0);

    /** Counts down when a gated run has started its query. */
    public static volatile CountDownLatch gateEntered = new CountDownLatch(1);

    /** What the settings report saw inside the run's transaction. */
    public static final AtomicReference<Map<String, String>> SEEN = new AtomicReference<>(Map.of());

    @Bean
    @Primary
    MovableClock testClock() {
        return new MovableClock();
    }

    abstract static class Base implements ReportDefinition {
        private final String key;
        private final String module;
        private final BusinessClock clock;

        Base(String key, String module, BusinessClock clock) {
            this.key = key;
            this.module = module;
            this.clock = clock;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String title() {
            return "Test report " + key;
        }

        @Override
        public String description() {
            return "A synthetic report for the framework tests.";
        }

        @Override
        public String module() {
            return module;
        }

        @Override
        public String permission() {
            return module.equals("lending") ? "lending.reports.portfolio" : "retail.reports.read";
        }

        @Override
        public List<Param> params() {
            return List.of(new Param("n", "integer", false, "Rows to write."));
        }

        @Override
        public Set<String> formats() {
            return Set.of("json", "csv", "pdf", "xlsx");
        }

        @Override
        public Prepared prepare(Map<String, Object> params) {
            int n = ReportParams.integer(params, "n", 0, 1_000_000, 3);
            LocalDate today = clock.today(ZoneId.of("Africa/Kampala"));
            return new Prepared(Map.of("n", n), Map.of("who", "fabricated"), today);
        }

        int n(Prepared prepared) {
            return ((Number) prepared.params().get("n")).intValue();
        }
    }

    /** {@code n} rows of an id, a label and an amount. */
    @Bean
    ReportDefinition rowsReport(BusinessClock clock) {
        return new Base("test.rows", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table(
                        "rows",
                        "Rows",
                        List.of(
                                new Column("id", "Number", Kind.COUNT),
                                new Column("label", "Label", Kind.TEXT),
                                new Column("amount", "Amount", Kind.MONEY)));
                for (int i = 1; i <= n(prepared); i++) {
                    t.row(i, "Test Row " + i, i * 1_000L);
                }
            }
        };
    }

    /** Holds its run open until {@link #gate} opens. */
    @Bean
    ReportDefinition gatedReport(BusinessClock clock) {
        return new Base("test.gate", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                gateEntered.countDown();
                try {
                    if (!gate.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never opened the gate");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                out.table("rows", "Rows", List.of(new Column("id", "Number", Kind.COUNT)))
                        .row(1);
            }
        };
    }

    /** Never finishes on its own: writes a row every 20 ms until the framework stops it. */
    @Bean
    ReportDefinition slowReport(BusinessClock clock) {
        return new Base("test.slow", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table("rows", "Rows", List.of(new Column("id", "Number", Kind.COUNT)));
                for (int i = 0; ; i++) {
                    t.row(i);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
    }

    /** Fails with a message that carries a phone number and a name: neither may reach a log, a row or the audit. */
    @Bean
    ReportDefinition boomReport(BusinessClock clock) {
        return new Base("test.boom", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                throw new IllegalStateException("could not read +256700000041 for Test Borrower 41");
            }
        };
    }

    /** Text that a spreadsheet would run, commas, quotes and a line break, and a negative amount. */
    @Bean
    ReportDefinition evilReport(BusinessClock clock) {
        return new Base("test.evil", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table(
                        "names",
                        "Names",
                        List.of(new Column("name", "Name", Kind.TEXT), new Column("amount", "Amount", Kind.MONEY)));
                t.row("=HYPERLINK(\"http://example.invalid\")", -500L);
                t.row("+1+1", 0L);
                t.row("-2", 7L);
                t.row("@SUM(A1)", 8L);
                t.row("Test, Borrower \"Quoted\"\nSecond line", 9L);
                t.row(null, null);
            }
        };
    }

    /** Three rows of 600 kB of text: few rows, but a file over the 1 MiB size cap of the tests. */
    @Bean
    ReportDefinition wideReport(BusinessClock clock) {
        return new Base("test.wide", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table("wide", "Wide", List.of(new Column("text", "Text", Kind.TEXT)));
                String filler = "x".repeat(600_000);
                for (int i = 0; i < 3; i++) {
                    t.row(filler);
                }
            }
        };
    }

    // JUSTIFICATION-A3: new synthetic reports and a watched storage; the existing ones cannot show a snapshot or an
    // upload.

    /** Rows of 360 characters: {@code n} of them make a file near the staging size cap of 8 MiB (20,000 make about 7.6 MB). */
    @Bean
    ReportDefinition fatReport(BusinessClock clock) {
        return new Base("test.fat", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table(
                        "fat",
                        "Fat",
                        List.of(new Column("id", "Number", Kind.COUNT), new Column("text", "Text", Kind.TEXT)));
                String filler = "y".repeat(360);
                for (int i = 1; i <= n(prepared); i++) {
                    t.row(i, filler);
                }
            }
        };
    }

    /** Released by the test to let {@code test.snapshot} read its second count. */
    public static volatile CountDownLatch snapshotGo = new CountDownLatch(0);

    /** Counts down when {@code test.snapshot} has read its first count. */
    public static volatile CountDownLatch snapshotMid = new CountDownLatch(1);

    /** Reads the tenant's branch count twice with a committed write by the test in between: one snapshot sees equal counts. */
    @Bean
    ReportDefinition snapshotReport(BusinessClock clock, JdbcClient jdbc) {
        return new Base("test.snapshot", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                long first = jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single();
                snapshotMid.countDown();
                try {
                    if (!snapshotGo.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never let the run go on");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                long second = jdbc.sql("SELECT count(*) FROM branches")
                        .query(Long.class)
                        .single();
                out.table(
                                "counts",
                                "Counts",
                                List.of(
                                        new Column("first", "First", Kind.COUNT),
                                        new Column("second", "Second", Kind.COUNT)))
                        .row(first, second);
            }
        };
    }

    /** Records the transaction settings the query phase runs under. */
    @Bean
    ReportDefinition settingsReport(BusinessClock clock, JdbcClient jdbc) {
        return new Base("test.settings", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                SEEN.set(Map.of(
                        "isolation",
                                jdbc.sql("SHOW transaction_isolation")
                                        .query(String.class)
                                        .single(),
                        "read_only",
                                jdbc.sql("SHOW transaction_read_only")
                                        .query(String.class)
                                        .single(),
                        "statement_timeout",
                                jdbc.sql("SHOW statement_timeout")
                                        .query(String.class)
                                        .single(),
                        "idle",
                                jdbc.sql("SHOW idle_in_transaction_session_timeout")
                                        .query(String.class)
                                        .single()));
                out.table("rows", "Rows", List.of(new Column("id", "Number", Kind.COUNT)))
                        .row(1);
            }
        };
    }

    // JUSTIFICATION-A3: the PDF and workbook renderers (issue #217) need a report with every column kind, a total and
    // eight columns.
    /**
     * {@code n} rows of every column kind (eight columns, so the PDF turns landscape) and a total row: row {@code i} is
     * "Test Group i", {@code i} loans, {@code i} x 1,000,000 principal, {@code i} x 7 basis points, and a date {@code i}
     * days after 1 October 2026. The total is the sum, written by hand-checkable arithmetic (n x (n + 1) / 2).
     */
    @Bean
    ReportDefinition kindsReport(BusinessClock clock) {
        return new Base("test.kinds", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table(
                        "kinds",
                        "Every kind",
                        List.of(
                                new Column("group", "Group", Kind.TEXT),
                                new Column("loans", "Loans", Kind.COUNT),
                                new Column("principal", "Principal", Kind.MONEY),
                                new Column("interest", "Interest", Kind.MONEY),
                                new Column("par", "PAR", Kind.BASIS_POINTS),
                                new Column("share", "Share", Kind.BASIS_POINTS),
                                new Column("since", "Since", Kind.DATE),
                                new Column("note", "Note", Kind.TEXT)));
                long n = n(prepared);
                for (long i = 1; i <= n; i++) {
                    t.row(
                            "Test Group " + i,
                            i,
                            i * 1_000_000L,
                            i * 10_000L,
                            i * 7L,
                            null,
                            LocalDate.of(2026, 10, 1).plusDays(i - 1),
                            i % 2 == 0 ? "even" : null);
                }
                long sum = n * (n + 1) / 2;
                t.total("Total", sum, sum * 1_000_000L, sum * 10_000L, null, 10_000L, null, null);
            }
        };
    }

    // JUSTIFICATION-A3: new case (issue #217); no existing synthetic report has markup, quotes and scripts the PDF font
    // lacks.
    /** Fabricated names with markup, quotes, accents and scripts the PDF font lacks, for the escaping tests of every format. */
    public static final List<String> MARKUP_NAMES = List.of(
            "Test <Branch> & \"Co\" 'North' >=< end",
            "Ünï Çödé Ñandú",
            "Тестовый Банк 日本語 العربية 😀",
            "</td></tr><script>alert(1)</script>",
            "Tab\there and\nnew line",
            "Very long unbroken name " + "W".repeat(120));

    @Bean
    ReportDefinition markupReport(BusinessClock clock) {
        return new Base("test.markup", "lending", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                ReportOutput.Table t = out.table(
                        "names",
                        "Names",
                        List.of(new Column("name", "Name", Kind.TEXT), new Column("amount", "Amount", Kind.MONEY)));
                long i = 0;
                for (String name : MARKUP_NAMES) {
                    t.row(name, ++i * 1_000L);
                }
            }
        };
    }

    /** A report of a module the lending tenants do not have (retail), for the catalogue filter. */
    @Bean
    ReportDefinition retailReport(BusinessClock clock) {
        return new Base("test.retail", "retail", clock) {
            @Override
            public void query(Prepared prepared, ReportOutput out) {
                out.table("rows", "Rows", List.of(new Column("id", "Number", Kind.COUNT)))
                        .row(1);
            }
        };
    }
}
