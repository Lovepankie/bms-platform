package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.internal.RowValidator.BatchInput;
import com.rincoltech.bms.core.imports.internal.RowValidator.Finding;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.core.imports.internal.RowValidator.RowResult;
import com.rincoltech.bms.core.imports.internal.RowValidator.SourceRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The golden run of the validation engine (issue #130, spec section 5.1): the fabricated files of
 * {@code fixtures/imports/golden/} through {@link RowValidator} with the fixture mapping, exactly as the service feeds it
 * (the mapping's ignored columns dropped, empty cells left out, the header where the source says). Plain Java apart from
 * the mapping the caller reads, so the unit test and the integration test share it. No real name, phone, NIN or figure:
 * every value in the files is made up.
 */
final class GoldenRun {

    static final Path DIR = Path.of("../fixtures/imports/golden");

    /** The agreed cutover moment of the golden run: midnight of 1 October 2026 in Kampala. */
    static final Instant CUTOVER = Instant.parse("2026-09-30T21:00:00Z");

    /** One file of the run and the mapping source that describes it, in commit order. */
    record GoldenFile(String file, String source) {}

    static final List<GoldenFile> FILES = List.of(
            new GoldenFile("fab-item-list.csv", "item_list"),
            new GoldenFile("fab-buyers.csv", "buyer_book"),
            new GoldenFile("fab-till-roll.csv", "till_roll"));

    /** The fixture's personal values: no message and no log line of the run may hold one, in any of these forms. */
    static final List<String> PERSONAL = List.of(
            "Fabricated Buyer",
            "Buyer Beta",
            "0700000101",
            "700000101",
            "+256700000102",
            "700000102",
            "0700000104",
            "700000104",
            "700000105",
            "0700000106",
            "700000106",
            "CM00000000000A",
            "00000000000A",
            "0000 0000 000B",
            "CM00000000000B",
            "XX123",
            "Fabricated Cashier",
            "fabricated remark");

    private GoldenRun() {}

    /** One issue as the golden report writes it. */
    static String line(String file, int line, Finding f) {
        return file + "|" + line + "|" + (f.field() == null ? "-" : f.field()) + "|" + f.code() + "|" + f.severity()
                + "|" + f.message();
    }

    /** The report of one run: a line per issue, in the engine's order, and each row's typed values and reference. */
    record Report(List<String> issues, List<String> originals, List<String> rows) {}

    static Report run(ImportTemplate template, Map<String, Object> mapping) {
        RowValidator engine = new RowValidator(
                template,
                new TemplateRegistry(List.of(template)).catalogue(template.key(), template.version()),
                CUTOVER);
        Map<BatchInput, List<SourceRow>> batches = new LinkedHashMap<>();
        for (GoldenFile g : FILES) {
            BatchInput batch = new BatchInput(
                    UUID.nameUUIDFromBytes(g.file().getBytes(StandardCharsets.UTF_8)), g.file(), mapping, g.source());
            batches.put(batch, rows(mapping, g));
        }
        batches.forEach((batch, rows) -> rows.forEach(row -> engine.index(batch, row)));
        engine.missingReferences().forEach((set, keys) -> engine.addExisting(set, template.existingKeys(set, keys)));
        List<String> issues = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        List<String> typed = new ArrayList<>();
        batches.forEach((batch, rows) -> {
            for (SourceRow row : rows) {
                RowResult r = engine.validate(batch, row);
                typed.add(batch.file() + "|" + row.line() + "|" + r.sourceRef() + "|" + r.normalised());
                for (Finding f : r.findings()) {
                    issues.add(line(batch.file(), row.line(), f));
                    originals.add(String.valueOf(f.original()));
                }
            }
        });
        return new Report(issues, originals, typed);
    }

    /** The rows of one golden file below its header, as the parser stores them. */
    static List<SourceRow> rows(Map<String, Object> mapping, GoldenFile g) {
        Map<String, Object> source = MappingValidator.sources(mapping).get(g.source());
        int headerRow = source.get("header_row") instanceof Number n ? n.intValue() : 1;
        Set<String> ignored = MappingValidator.ignoredColumns(source);
        List<String> lines;
        try {
            lines = Files.readAllLines(DIR.resolve(g.file()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> header = cells(lines.get(headerRow - 1));
        List<SourceRow> out = new ArrayList<>();
        for (int i = headerRow; i < lines.size(); i++) {
            List<String> cells = cells(lines.get(i));
            Map<String, RawCell> raw = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                if (!ignored.contains(header.get(c)) && !cells.get(c).isEmpty()) {
                    raw.put(header.get(c), new RawCell(cells.get(c), "text"));
                }
            }
            out.add(new SourceRow(
                    UUID.nameUUIDFromBytes((g.file() + ":" + (i + 1)).getBytes(StandardCharsets.UTF_8)), i + 1, raw));
        }
        return out;
    }

    /** One CSV record (RFC 4180 quotes, no line break inside a cell: the golden files have none). */
    static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out;
    }
}
