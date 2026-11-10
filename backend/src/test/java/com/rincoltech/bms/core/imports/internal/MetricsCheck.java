package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportMetrics.BatchRows;
import com.rincoltech.bms.core.imports.ImportMetrics.Metrics;
import com.rincoltech.bms.core.imports.ImportMetrics.Stage;
import com.rincoltech.bms.core.imports.ImportMetrics.Step;
import com.rincoltech.bms.core.imports.ImportMetrics.TemplateRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The metrics of a committed golden run (issue #140) against the run's tables read here with queries of their own, as the
 * owner, and against the commit's report: so each figure is shown to come from what the run stored, never from a log. Shared
 * by the golden runs of {@code retail_v1} and {@code lending_loans_v1}.
 */
public final class MetricsCheck {

    private MetricsCheck() {}

    /** Every figure of the metrics of a committed run, checked against its stored data and the commit's report. */
    public static void assertFromStoredData(Metrics m, UUID run, CommitReport real) {
        assertThat(m.runId()).isEqualTo(run);
        assertThat(m.status()).isEqualTo(text("SELECT status FROM import_runs WHERE id = ?", run));
        assertThat(m.stamps().createdAt()).isEqualTo(at("SELECT created_at FROM import_runs WHERE id = ?", run));
        assertThat(m.stamps().committedAt())
                .isNotNull()
                .isEqualTo(at("SELECT committed_at FROM import_runs WHERE id = ?", run));
        assertThat(m.stamps().dryRunAt()).isEqualTo(at("SELECT dry_run_at FROM import_runs WHERE id = ?", run));
        assertThat(m.stamps().reconciledAt())
                .isEqualTo(at("SELECT min(created_at) FROM import_reconciliations WHERE run_id = ?", run));

        // The ten stages of spec section 2 in order; the cutover from the committing to the committed stamp.
        assertThat(m.stages()).extracting(Stage::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        Stage cutover = m.stages().get(5);
        assertThat(cutover.key()).isEqualTo("cutover");
        assertThat(cutover.startedAt()).isEqualTo(at("SELECT committing_at FROM import_runs WHERE id = ?", run));
        assertThat(cutover.endedAt()).isEqualTo(m.stamps().committedAt());
        assertThat(cutover.seconds()).isNotNull().isNotNegative();

        // The steps, as stored where each ended: every batch uploaded, read and committed; the run validated and its
        // dry run.
        long batches = count("SELECT count(*) FROM import_batches WHERE run_id = ?", run);
        long committed = count("SELECT count(*) FROM import_batches WHERE run_id = ? AND status = 'committed'", run);
        assertThat(steps(m, "upload")).isEqualTo(batches);
        assertThat(steps(m, "parse")).isEqualTo(batches);
        assertThat(steps(m, "commit")).isEqualTo(committed).isPositive();
        assertThat(steps(m, "validate")).isEqualTo(1);
        assertThat(steps(m, "dry_run")).isEqualTo(1);
        assertThat(m.steps()).allSatisfy(s -> assertThat(s.millis()).isNotNegative());

        // Rows by outcome per batch: the file's rows and those left out from the tables, the commit's counts from the
        // report of the commit.
        assertThat(m.batches()).hasSize(Math.toIntExact(batches));
        for (BatchRows b : m.batches()) {
            UUID id = TestDatabase.owner()
                    .sql("SELECT id FROM import_batches WHERE run_id = ? AND seq = ?")
                    .params(run, b.seq())
                    .query(UUID.class)
                    .single();
            assertThat(b.rows()).isEqualTo(count("SELECT row_count FROM import_batches WHERE id = ?", id));
            assertThat(b.excluded())
                    .isEqualTo(count(
                            "SELECT count(*) FROM import_rows WHERE batch_id = ? AND excluded_by IS NOT NULL", id));
        }
        assertThat(m.templates()).hasSize(1);
        TemplateRows template = m.templates().getFirst();
        assertThat(template.templateKey())
                .isEqualTo(text("SELECT min(template_key) FROM import_batches WHERE run_id = ?", run));
        assertThat(template.read()).isEqualTo(sum(real, CommitReport.Source::read));
        assertThat(template.written()).isEqualTo(sum(real, CommitReport.Source::written));
        assertThat(template.existing()).isEqualTo(sum(real, CommitReport.Source::existing));
        assertThat(template.skipped()).isEqualTo(sum(real, CommitReport.Source::skipped));
        assertThat(template.excluded())
                .isEqualTo(count(
                        "SELECT count(*) FROM import_rows w JOIN import_batches b ON b.id = w.batch_id"
                                + " WHERE b.run_id = ? AND w.excluded_by IS NOT NULL",
                        run));

        // Issues by code and severity, and resolutions by kind, as the tables hold them.
        Map<String, String> issues = m.issues().stream()
                .collect(Collectors.toMap(
                        i -> i.code() + "|" + i.severity(),
                        i -> i.total() + "|" + i.open(),
                        (a, b) -> a,
                        TreeMap::new));
        String issuesSql = "SELECT i.code || '|' || i.severity, count(*) || '|' || count(*) FILTER (WHERE i.status ="
                + " 'open') FROM import_issues i JOIN import_batches b ON b.id = i.batch_id WHERE b.run_id = ?"
                + " GROUP BY i.code, i.severity";
        assertThat(issues).isEqualTo(pairs(issuesSql, run));
        Map<String, String> resolutions = m.resolutions().stream()
                .collect(Collectors.toMap(r -> r.kind(), r -> String.valueOf(r.count()), (a, b) -> a, TreeMap::new));
        assertThat(resolutions)
                .isEqualTo(pairs(
                        "SELECT resolution, CAST(count(*) AS text) FROM import_issue_resolutions WHERE run_id = ?"
                                + " GROUP BY resolution",
                        run));

        // The mapping versions the batches were read with.
        assertThat(m.mappings().stream().map(v -> v.name() + "|" + v.version()).toList())
                .containsExactlyInAnyOrderElementsOf(TestDatabase.owner()
                        .sql("SELECT DISTINCT m.name || '|' || m.version FROM import_mappings m"
                                + " JOIN import_batches b ON b.mapping_id = m.id WHERE b.run_id = ?")
                        .param(run)
                        .query(String.class)
                        .list());
        assertThat(m.defects().open()).isZero();
        assertThat(m.defects().closed()).isZero();
    }

    private static long steps(Metrics m, String step) {
        return m.steps().stream()
                .filter(s -> step.equals(s.step()))
                .map(Step::millis)
                .count();
    }

    private static long sum(CommitReport real, java.util.function.ToIntFunction<CommitReport.Source> of) {
        return real.sources().stream().mapToInt(of).sum();
    }

    private static Map<String, String> pairs(String sql, UUID run) {
        Map<String, String> out = new TreeMap<>();
        TestDatabase.owner()
                .sql(sql)
                .param(run)
                .query((rs, n) -> out.put(rs.getString(1), rs.getString(2)))
                .list();
        return out;
    }

    private static long count(String sql, Object... params) {
        Long n = TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
        return n == null ? 0 : n;
    }

    private static String text(String sql, UUID run) {
        return TestDatabase.owner().sql(sql).param(run).query(String.class).single();
    }

    private static Instant at(String sql, UUID run) {
        Timestamp t =
                TestDatabase.owner().sql(sql).param(run).query(Timestamp.class).single();
        return t == null ? null : t.toInstant();
    }
}
