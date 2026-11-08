package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.internal.RowValidator.BatchInput;
import com.rincoltech.bms.core.imports.internal.RowValidator.Finding;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.core.imports.internal.RowValidator.RowResult;
import com.rincoltech.bms.core.imports.internal.RowValidator.SourceRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The validation engine run without a database for a template outside this package (issue #136): the lending template's
 * golden test feeds its fabricated registers through {@link RowValidator} exactly as the service does (both passes, the
 * mapping's ignored columns dropped, empty cells left out, the review's edits read in place of their cells), and reads the
 * issues and typed values. The template's own reads of the business go through whatever the test gives it.
 */
public final class EngineRun {

    private EngineRun() {}

    /**
     * One file of a run: its name, the mapping source that reads it, its header and the rows below it (cell texts, an empty
     * text no cell), and the review's edits in force by line and field.
     */
    public record File(
            String name,
            String source,
            List<String> header,
            List<List<String>> rows,
            Map<Integer, Map<String, String>> edits) {

        public File(String name, String source, List<String> header, List<List<String>> rows) {
            this(name, source, header, rows, Map.of());
        }
    }

    /** One issue: where, its code, severity and message, and the engine's or the template's suggestion. */
    public record Issue(
            String file, int line, String field, String code, String severity, String message, String suggestion) {}

    /** One row's typed values as the engine stores them. */
    public record Row(String file, int line, Map<String, Object> normalised) {}

    public record Result(List<Issue> issues, List<Row> rows) {}

    public static Result run(ImportTemplate template, Map<String, Object> mapping, Instant cutover, List<File> files) {
        RowValidator engine = new RowValidator(
                template,
                new TemplateRegistry(List.of(template)).catalogue(template.key(), template.version()),
                cutover);
        Map<BatchInput, List<SourceRow>> batches = new LinkedHashMap<>();
        for (File f : files) {
            BatchInput batch = new BatchInput(
                    UUID.nameUUIDFromBytes(f.name().getBytes(StandardCharsets.UTF_8)), f.name(), mapping, f.source());
            Set<String> ignored = MappingValidator.ignoredColumns(
                    MappingValidator.sources(mapping).get(f.source()));
            List<SourceRow> rows = new ArrayList<>();
            for (int i = 0; i < f.rows().size(); i++) {
                int line = i + 2;
                List<String> cells = f.rows().get(i);
                Map<String, RawCell> raw = new LinkedHashMap<>();
                for (int c = 0; c < f.header().size() && c < cells.size(); c++) {
                    if (!ignored.contains(f.header().get(c)) && !cells.get(c).isEmpty()) {
                        raw.put(f.header().get(c), new RawCell(cells.get(c), "text"));
                    }
                }
                rows.add(new SourceRow(
                        UUID.nameUUIDFromBytes((f.name() + ":" + line).getBytes(StandardCharsets.UTF_8)),
                        line,
                        raw,
                        f.edits().getOrDefault(line, Map.of())));
            }
            batches.put(batch, rows);
        }
        batches.forEach((batch, rows) -> rows.forEach(row -> engine.index(batch, row)));
        engine.missingReferences().forEach((set, keys) -> engine.addExisting(set, template.existingKeys(set, keys)));
        List<Issue> issues = new ArrayList<>();
        List<Row> typed = new ArrayList<>();
        batches.forEach((batch, rows) -> {
            for (SourceRow row : rows) {
                RowResult r = engine.validate(batch, row);
                typed.add(new Row(batch.file(), row.line(), r.normalised()));
                for (Finding f : r.findings()) {
                    String suggestion = f.suggestion() != null ? f.suggestion() : engine.suggestion(batch, f);
                    issues.add(new Issue(
                            batch.file(), row.line(), f.field(), f.code(), f.severity(), f.message(), suggestion));
                }
            }
        });
        return new Result(issues, typed);
    }

    /** One CSV record (RFC 4180 quotes, no line break inside a cell), as the golden files are written. */
    public static List<String> cells(String line) {
        return GoldenRun.cells(line);
    }
}
