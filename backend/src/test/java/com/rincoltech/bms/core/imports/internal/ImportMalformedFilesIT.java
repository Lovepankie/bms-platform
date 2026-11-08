package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The malformed files of spec section 5.3 (issue #137) through the real upload, validation and preview of {@code retail_v1}
 * on PostgreSQL as {@code bms_app}, read with the fabricated shop book mapping ({@code fixtures/imports/}): files cut short,
 * in the wrong encoding, with a byte order mark in the middle, with an unbalanced quote; workbooks with merged cells,
 * formulas (with their value, with an error, saved without a value), hidden sheets, dates as serials and as text; very long
 * cells; and control and right-to-left characters in codes. Each case names what it must end in: a refusal or a failed
 * batch with its code, or the issue codes of its broken rows, and the rows it leaves clean. Every case also proves what all
 * share: every code is in the template's catalogue, a failed batch holds no rows, a refused file left no batch, the
 * preview reads what was staged without an error, and nothing outside the import tables and the audit log was written.
 * The byte-level fuzzing of the readers is {@code MalformedFilesTest}. Fabricated rows only.
 */
class ImportMalformedFilesIT extends IntegrationTest {

    static final Path MAPPING = Path.of("../fixtures/imports/retail_v1.shop_book.mapping.json");

    static final String PRODUCT_HEADER = "Product ID,Description,Category,Unit,Unit cost,Unit sell,In use,Stock value";

    /** Three valid items, lines 2 to 4. */
    static final String PRODUCTS = PRODUCT_HEADER + "\r\n"
            + "FZ-001,Test fuzz item 01,Cables,metre,700,1200,Yes,0\r\n"
            + "FZ-002,\"Test fuzz item 02, quoted\",Switches,piece,2500,3000,Yes,0\r\n"
            + "FZ-003,Test fuzz item 03,Switches,piece,4000,5000,No,0\r\n";

    static final List<String> RESTOCK_HEADER = List.of(
            "Ref",
            "User",
            "Date",
            "Product ID",
            "Supplier",
            "Entry type",
            "Unit cost",
            "Unit sell",
            "Quantity per shop");

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportPreview preview;

    @Autowired
    ImportTemplates templates;

    @Autowired
    QuarantineStorage quarantine;

    TestDatabase.Fixture t;
    UUID mapping;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        t = TestDatabase.tenant("import-fuzz", false, true);
        Map<String, Object> document =
                JsonMapper.builder().build().readValue(Files.readString(MAPPING), new TypeReference<>() {});
        mapping = as(
                () -> mappings.save("fuzz-shop-book", document, operator, null).id());
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    /**
     * One malformed file: the mapping source that reads it, its name and bytes, and what it must end in, each one of
     * {@code refused:<code>}, {@code failed:<code>}, {@code warning:<code>} (a parse warning of the batch),
     * {@code issue:<line>:<code>} and {@code clean:<line>} (no blocking issue and no warning on that line). Every code the
     * file ends in is named, once per issue; the clean lines are those the case names.
     */
    record Case(String name, String source, String file, byte[] bytes, List<String> expected) {

        Case(String name, String source, String file, byte[] bytes, String... expected) {
            this(name, source, file, bytes, List.of(expected));
        }
    }

    @TestFactory
    Stream<DynamicTest> everyMalformedFileEndsInACodedFailureOrCodedRowIssuesAndWritesNothingElse() {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> check(c)));
    }

    static List<Case> cases() {
        String cut = PRODUCTS.substring(0, PRODUCTS.indexOf("item 03,Switches") + "item 03,Switches".length());
        return List.of(
                // Truncation.
                new Case(
                        "a CSV cut inside a quoted cell",
                        "product_master",
                        "fab-cut-quote.csv",
                        utf8(PRODUCTS.substring(0, PRODUCTS.indexOf("quoted"))),
                        "failed:FILE_UNREADABLE"),
                new Case(
                        "a CSV cut inside its last row",
                        "product_master",
                        "fab-cut-row.csv",
                        utf8(cut),
                        "clean:2",
                        "clean:3",
                        // The unit, the cost and the sell price are cut off.
                        "issue:4:REQUIRED_MISSING",
                        "issue:4:REQUIRED_MISSING",
                        "issue:4:REQUIRED_MISSING"),
                new Case(
                        "a CSV cut inside its header",
                        "product_master",
                        "fab-cut-header.csv",
                        utf8("Product ID,Descr"),
                        "failed:HEADER_NOT_FOUND"),
                new Case(
                        "a workbook cut in half",
                        "product_master",
                        "fab-cut.xlsx",
                        Arrays.copyOf(products(""), products("").length / 2),
                        "failed:FILE_UNREADABLE"),
                // Encodings.
                new Case(
                        "a CSV in UTF-16",
                        "product_master",
                        "fab-utf16.csv",
                        PRODUCTS.getBytes(StandardCharsets.UTF_16LE),
                        "refused:FILE_TYPE_UNSUPPORTED"),
                new Case(
                        "a CSV in Windows-1252",
                        "product_master",
                        "fab-cp1252.csv",
                        PRODUCTS.replace("item 01", "caf" + (char) 0xE9 + " 01")
                                .getBytes(Charset.forName("windows-1252")),
                        "warning:ENCODING_CONVERTED",
                        "clean:2"),
                new Case(
                        "a UTF-8 CSV with one byte that is not UTF-8",
                        "product_master",
                        "fab-bad-byte.csv",
                        withByte(utf8(PRODUCTS), PRODUCTS.indexOf("item 03"), (byte) 0xFF),
                        "warning:ENCODING_CONVERTED"),
                new Case(
                        "two CSV exports joined, the second's byte order mark mid-file",
                        "product_master",
                        "fab-joined.csv",
                        utf8(PRODUCTS + (char) 0xFEFF + PRODUCTS.replace("FZ-00", "FZ-10")),
                        "clean:2",
                        // The second header as a row: its code holds the mark, and its cost, sell and in use are words.
                        "issue:5:CODE_CONTROL_CHAR",
                        "issue:5:TYPE_INVALID",
                        "issue:5:TYPE_INVALID",
                        "issue:5:TYPE_INVALID"),
                // Quotes and long cells.
                new Case(
                        "a CSV with a quote that never closes",
                        "product_master",
                        "fab-open-quote.csv",
                        utf8(PRODUCTS + "FZ-004,\"Test fuzz item 04,Cables,metre,700,1200,Yes,0\r\n"),
                        "failed:FILE_UNREADABLE"),
                new Case(
                        "a description over its 300 characters and a code over its 40",
                        "product_master",
                        "fab-long.csv",
                        utf8(PRODUCT_HEADER + "\r\nFZ-001," + "d".repeat(301) + ",Cables,metre,700,1200,Yes,0\r\n"
                                + "FZ-" + "9".repeat(40) + ",Test fuzz item,Cables,metre,700,1200,Yes,0\r\n"),
                        // A description left out is no name (retail_v1's own code).
                        "issue:2:TEXT_TOO_LONG",
                        "issue:2:NAME_MISSING",
                        "issue:3:TEXT_TOO_LONG"),
                new Case(
                        "a cell over the 32,767 characters a spreadsheet can hold",
                        "product_master",
                        "fab-huge-cell.csv",
                        utf8(PRODUCT_HEADER + "\r\nFZ-001," + "d".repeat(Parsing.MAX_CELL_CHARS + 1)
                                + ",Cables,metre,700,1200,Yes,0\r\n"),
                        "failed:FILE_UNREADABLE"),
                // Hidden characters in codes.
                new Case(
                        "control and right-to-left characters in codes",
                        "product_master",
                        "fab-hidden.csv",
                        utf8(PRODUCT_HEADER + "\r\n"
                                + "FZ-" + (char) 0x07 + "001,Test fuzz item 01,Cables,metre,700,1200,Yes,0\r\n"
                                + (char) 0x200F + "FZ-002,Test fuzz item 02,Cables,metre,700,1200,Yes,0\r\n"
                                + "FZ" + (char) 0x202E + "-003,Test fuzz item 03,Cables,metre,700,1200,Yes,0\r\n"
                                + "FZ-0" + (char) 0x200B + "04,Test fuzz item 04,Cables,metre,700,1200,Yes,0\r\n"
                                + "FZ-005,Test fuzz item 05,Cables,metre,700,1200,Yes,0\r\n"),
                        "issue:2:CODE_CONTROL_CHAR",
                        "issue:3:CODE_CONTROL_CHAR",
                        "issue:4:CODE_CONTROL_CHAR",
                        "issue:5:CODE_CONTROL_CHAR",
                        "clean:6"),
                new Case(
                        "a NUL character in a code",
                        "product_master",
                        "fab-nul.csv",
                        utf8(PRODUCT_HEADER + "\r\nFZ-" + (char) 0x00
                                + "001,Test fuzz item 01,Cables,metre,700,1200,Yes,0\r\n"),
                        "refused:FILE_TYPE_UNSUPPORTED"),
                // Workbooks.
                new Case(
                        "merged cells: the category of two rows held once",
                        "product_master",
                        "fab-merged.xlsx",
                        workbook(
                                false,
                                sheet(
                                        "<mergeCells count=\"1\"><mergeCell ref=\"C2:C3\"/></mergeCells>",
                                        header(PRODUCT_HEADER.split(",")),
                                        item(2, "FZ-001", "<c r=\"C2\" t=\"inlineStr\"><is><t>Cables</t></is></c>"),
                                        item(3, "FZ-002", ""))),
                        "clean:2",
                        "issue:3:REQUIRED_MISSING"),
                new Case(
                        "formulas: with a value, with an error, saved without a value, with empty text as value",
                        "product_master",
                        "fab-formulas.xlsx",
                        workbook(
                                false,
                                sheet(
                                        "",
                                        header(PRODUCT_HEADER.split(",")),
                                        "<row r=\"2\">" + text("A2", "FZ-001") + text("B2", "Test fuzz item 01")
                                                + text("C2", "Cables") + text("D2", "metre")
                                                + "<c r=\"E2\"><v>700</v></c><c r=\"F2\"><f>E2*2</f><v>1400</v></c>"
                                                + text("G2", "Yes") + "</row>",
                                        "<row r=\"3\">" + text("A3", "FZ-002")
                                                + "<c r=\"B3\" t=\"e\"><f>VLOOKUP(A3,Notes!A:B,2,FALSE)</f>"
                                                + "<v>#N/A</v></c>" + text("C3", "Cables") + text("D3", "metre")
                                                + "<c r=\"E3\"><v>700</v></c><c r=\"F3\"><v>1200</v></c>"
                                                + text("G3", "Yes") + "</row>",
                                        "<row r=\"4\">" + text("A4", "FZ-003") + text("B4", "Test fuzz item 03")
                                                + text("C4", "Cables") + text("D4", "metre")
                                                + "<c r=\"E4\"><v>700</v></c><c r=\"F4\"><v>1200</v></c>"
                                                + "<c r=\"G4\"><f>E4&gt;0</f></c></row>",
                                        // A formula whose value is empty text: an empty optional cell, as shown.
                                        "<row r=\"5\">" + text("A5", "FZ-004") + text("B5", "Test fuzz item 04")
                                                + text("C5", "Cables") + text("D5", "metre")
                                                + "<c r=\"E5\"><v>700</v></c><c r=\"F5\"><v>1200</v></c>"
                                                + "<c r=\"G5\" t=\"str\"><f>IF(A5=\"\",\"\",\"\")</f><v></v></c>"
                                                + "</row>")),
                        "clean:2",
                        "issue:3:TYPE_INVALID",
                        "issue:3:NAME_MISSING",
                        "issue:4:TYPE_INVALID",
                        "clean:5"),
                new Case(
                        "a hidden first sheet of notes before the items",
                        "product_master",
                        "fab-hidden-sheet.xlsx",
                        workbook(true, sheet("", header(PRODUCT_HEADER.split(",")), item(2, "FZ-001", cables(2)))),
                        "clean:2"),
                new Case(
                        "a workbook whose only sheet is hidden",
                        "product_master",
                        "fab-only-hidden.xlsx",
                        onlyHidden(),
                        "failed:FILE_UNREADABLE"),
                // Dates.
                new Case(
                        "dates as a serial in a General cell, in a date cell, as text and as an impossible text",
                        "restock_log",
                        "fab-restock-dates.xlsx",
                        workbook(
                                false,
                                sheet(
                                        "",
                                        header(RESTOCK_HEADER.toArray(String[]::new)),
                                        restock(2, "<c r=\"C2\"><v>46205</v></c>"),
                                        restock(3, "<c r=\"C3\" s=\"1\"><v>46205</v></c>"),
                                        restock(4, text("C4", "02/07/2026")),
                                        restock(5, text("C5", "31/02/2026")))),
                        "issue:2:TYPE_INVALID",
                        "clean:3",
                        "clean:4",
                        "issue:5:TYPE_INVALID"));
    }

    // ---------------------------------------------------------------------------------------------
    // The check every case shares.
    // ---------------------------------------------------------------------------------------------

    void check(Case c) {
        Map<String, Long> before = outsideImports();
        Set<String> catalogue =
                templates.catalogue("retail", 1).stream().map(IssueCode::code).collect(Collectors.toSet());
        UUID run = as(() -> runs.open(operator).id());
        if ("restock_log".equals(c.source())) {
            // The items the restock rows name, in the same run.
            assertThat(upload(run, "product_master", "fab-product-master.csv", utf8(PRODUCTS))
                            .status())
                    .isEqualTo("parsed");
        }
        List<String> found = new ArrayList<>();
        long objects = quarantined();
        ImportUploads.Batch batch = null;
        try {
            batch = upload(run, c.source(), c.file(), c.bytes());
        } catch (ApiException e) {
            found.add("refused:" + e.code());
            assertThat(catalogue).as("the refusal's code").contains(e.code());
            assertThat(count(
                            "SELECT count(*) FROM import_batches WHERE run_id = ? AND source_filename = ?",
                            run,
                            c.file()))
                    .as("a refused file leaves no batch")
                    .isZero();
            assertThat(quarantined())
                    .as("a refused file leaves no quarantined object")
                    .isEqualTo(objects);
        }
        ImportUploads.Batch stored = batch;
        if (stored != null && "failed".equals(stored.status())) {
            found.add("failed:" + stored.failureCode());
            assertThat(catalogue).as("the failure's code").contains(stored.failureCode());
            assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id = ?", stored.id()))
                    .as("a failed batch holds no rows")
                    .isZero();
            assertThat(quarantined())
                    .as("a failed batch keeps no quarantined object")
                    .isEqualTo(objects);
        } else if (stored != null) {
            assertThat(stored.status()).isEqualTo("parsed");
            stored.warnings().forEach(w -> found.add("warning:" + w));
            as(() -> validation.validate(run));
            Map<Integer, List<String>> byLine = new TreeMap<>();
            for (Issue i : as(() -> validation.issues(stored.id()))) {
                assertThat(catalogue).as("the code of %s", i).contains(i.code());
                found.add("issue:" + i.line() + ":" + i.code());
                if (List.of("blocking", "warning").contains(i.severity())) {
                    byLine.computeIfAbsent(i.line(), k -> new ArrayList<>()).add(i.code());
                }
            }
            for (int line = 1; line <= stored.rowCount() + 1; line++) {
                if (!byLine.containsKey(line)) {
                    found.add("clean:" + line);
                }
            }
            as(() -> preview.preview(run));
        }
        // Exactly the codes the case names, as a multiset; the clean lines it names among those found (Lead, 74bfe3e).
        List<String> codes =
                found.stream().filter(f -> !f.startsWith("clean:")).sorted().toList();
        assertThat(codes)
                .as("what %s ended in", c.name())
                .isEqualTo(c.expected().stream()
                        .filter(f -> !f.startsWith("clean:"))
                        .sorted()
                        .toList());
        assertThat(found).as("the clean lines of %s", c.name()).containsAll(c.expected());
        if (!c.expected().stream().allMatch(e -> e.startsWith("clean:"))) {
            // A malformed file ends in a code; only a well formed case (a hidden sheet skipped) is clean.
            assertThat(found.stream().filter(f -> !f.startsWith("clean:")))
                    .as("%s ends in a code", c.name())
                    .isNotEmpty();
        }
        assertThat(outsideImports()).as("nothing outside the import tables").isEqualTo(before);
    }

    ImportUploads.Batch upload(UUID run, String source, String file, byte[] bytes) {
        return as(() -> uploads.upload(
                run,
                new Upload(
                        file,
                        new ByteArrayInputStream(bytes),
                        bytes.length,
                        operator,
                        mappings.parseOptions(mapping, source))));
    }

    /** The business's quarantined objects in storage. */
    long quarantined() {
        return as(() -> {
            try (Stream<QuarantineStorage.Held> held = quarantine.list()) {
                return held.count();
            }
        });
    }

    /** The business's rows in every table but the import tables and the audit log. */
    Map<String, Long> outsideImports() {
        Map<String, Long> out = new TreeMap<>(TestDatabase.rowCounts(t.tenantId()));
        out.keySet().removeIf(k -> k.startsWith("import_") || k.equals("audit_log") || k.startsWith("sequence "));
        return out;
    }

    static long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    // ---------------------------------------------------------------------------------------------
    // The files.
    // ---------------------------------------------------------------------------------------------

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] withByte(byte[] bytes, int at, byte b) {
        byte[] out = bytes.clone();
        out[at] = b;
        return out;
    }

    static String text(String ref, String value) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }

    static String header(String... names) {
        StringBuilder row = new StringBuilder("<row r=\"1\">");
        for (int i = 0; i < names.length; i++) {
            row.append(text(TestFiles.column(i + 1) + "1", names[i]));
        }
        return row.append("</row>").toString();
    }

    static String cables(int r) {
        return text("C" + r, "Cables");
    }

    /** An item row with its category cell given, or none. */
    static String item(int r, String code, String category) {
        return "<row r=\"" + r + "\">" + text("A" + r, code) + text("B" + r, "Test fuzz item " + r) + category
                + text("D" + r, "metre") + "<c r=\"E" + r + "\"><v>700</v></c><c r=\"F" + r + "\"><v>1200</v></c>"
                + text("G" + r, "Yes") + "</row>";
    }

    /** A restock row of FZ-001 at the head office with its date cell given. */
    static String restock(int r, String date) {
        return "<row r=\"" + r + "\">" + text("A" + r, "PUR-F0" + r) + text("B" + r, "test.admin01") + date
                + text("D" + r, "FZ-001") + text("E" + r, "Test Supplier 01") + text("F" + r, "Restock")
                + "<c r=\"G" + r + "\"><v>600</v></c><c r=\"H" + r + "\"><v>1100</v></c>" + text("I" + r, "HQ=10")
                + "</row>";
    }

    /** A worksheet of the rows, with an element after the sheet data (merged cells) when given. */
    static String sheet(String after, String... rows) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + String.join("", rows) + "</sheetData>" + after + "</worksheet>";
    }

    static byte[] products(String after) {
        return workbook(
                false,
                sheet(
                        after,
                        header(PRODUCT_HEADER.split(",")),
                        item(2, "FZ-001", cables(2)),
                        item(3, "FZ-002", cables(3))));
    }

    /** A workbook as a spreadsheet program writes it: the sheet {@code Items}, after a hidden {@code Notes} when asked. */
    static byte[] workbook(boolean hiddenNotes, String items) {
        String sheets = (hiddenNotes ? "<sheet name=\"Notes\" sheetId=\"2\" state=\"hidden\" r:id=\"rId2\"/>" : "")
                + "<sheet name=\"Items\" sheetId=\"1\" r:id=\"rId1\"/>";
        return zip(sheets, items, sheet("", "<row r=\"1\">" + text("A1", "hidden notes, no header") + "</row>"));
    }

    /** A workbook of one sheet, hidden. */
    static byte[] onlyHidden() {
        return zip(
                "<sheet name=\"Items\" sheetId=\"1\" state=\"hidden\" r:id=\"rId1\"/>",
                sheet("", header(PRODUCT_HEADER.split(",")), item(2, "FZ-001", cables(2))),
                sheet("", ""));
    }

    private static byte[] zip(String sheets, String first, String second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"></Types>""");
            put(
                    zip,
                    "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                            + "<workbookPr/><sheets>" + sheets + "</sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="worksheet" Target="worksheets/sheet2.xml"/>
                    </Relationships>""");
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs>
                    </styleSheet>""");
            put(zip, "xl/worksheets/sheet1.xml", first);
            put(zip, "xl/worksheets/sheet2.xml", second);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
