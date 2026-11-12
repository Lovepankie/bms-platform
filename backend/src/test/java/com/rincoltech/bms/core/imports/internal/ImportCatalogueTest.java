package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.FieldType;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.RowCheck;
import com.rincoltech.bms.core.imports.ImportTemplate.RowFinding;
import com.rincoltech.bms.core.imports.ImportTemplate.Severity;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.internal.RowValidator.BatchInput;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.core.imports.internal.RowValidator.SourceRow;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The issue catalogue and the validation engine of issue #130 without a database (spec sections 3.4, 3.4.1, 5.1 and 6.5):
 * the golden run gives exactly the expected issues; the same rows, template and mapping version give the same issues every
 * time; every code of the catalogue is produced by a fixture row, by a named test of the stage that owns it, or is pending a
 * named later step or retired; no code is removed, renamed or given another severity; and no message may interpolate a
 * personal field or carry a personal value. Fabricated data only ({@code fixtures/imports/}).
 */
class ImportCatalogueTest {

    static final Path FIXTURES = Path.of("../fixtures/imports");
    static final Path TESTS = Path.of("src/test/java/com/rincoltech/bms/core/imports");

    /** The lending template's golden run (#136), which classes the sample's sub-header and placeholder rows. */
    static final String LENDING_GOLDEN =
            "../../lending/loans/internal/LendingLoansV1TemplateTest.java#thePilotRegisterGivesTheIssuesOfSection1312";

    /**
     * Codes another stage of the pipeline reports, each with the test method that produces it from a fabricated file,
     * mapping or run and asserts it: the upload and parse (#128), the mapping validator (#129) and the commit guards (#127).
     * The completeness test reads that method's body and requires the code inside one of its assertions, so a mention
     * elsewhere in the file (a comment, an import) does not count (review of #867).
     */
    static final Map<String, String> PRODUCED_ELSEWHERE = Map.ofEntries(
            Map.entry(
                    "FILE_TYPE_UNSUPPORTED",
                    "internal/SourceParserTest.java#theWriterRefusesAWrongTypeAndAFileOverTheLimit"),
            Map.entry(
                    "FILE_TOO_LARGE", "internal/SourceParserTest.java#theWriterRefusesAWrongTypeAndAFileOverTheLimit"),
            Map.entry("TOO_MANY_ROWS", "internal/SourceParserTest.java#theRowLimitIsExactAndOneMoreIsRefused"),
            Map.entry("HEADER_NOT_FOUND", "internal/SourceParserTest.java#theHeaderMustBeInTheFirstTwentyRows"),
            Map.entry("TOO_MANY_COLUMNS", "internal/SourceParserTest.java#tooManyColumnsInTheHeaderOrInARowIsRefused"),
            Map.entry("FILE_UNREADABLE", "internal/SourceParserTest.java#anUnclosedQuoteOrAHugeCellIsUnreadable"),
            Map.entry("FILE_ALREADY_UPLOADED", "internal/ImportUploadsIT.java#everyRefusalAtUploadStoresNothing"),
            Map.entry("UPLOAD_DISK_LOW", "internal/ImportUploadsIT.java#everyRefusalAtUploadStoresNothing"),
            Map.entry(
                    "COLUMN_UNMAPPED",
                    "internal/MappingValidatorTest.java#everyColumnNeitherMappedNorIgnoredIsReportedOnceAsColumnUnmapped"),
            Map.entry("ENCODING_CONVERTED", "internal/SourceParserTest.java#windows1252IsReadAndWarned"),
            Map.entry(
                    "TENANT_TRADING",
                    "ImportRunsIT.java#aTradingBusinessIsRefusedWithoutTheOverrideAndAllowedWithItBothAudited"),
            Map.entry("IMPORT_IN_PROGRESS", "ImportRunsIT.java#twoConcurrentCommitsOfOneRunExactlyOneProceeds"),
            Map.entry(
                    "TEMPLATE_UNKNOWN",
                    "internal/MappingValidatorTest.java#aDocumentForATemplateNotRegisteredHasOneProblem"),
            Map.entry(
                    "MAPPING_INVALID",
                    "internal/MappingValidatorTest.java#theSchemaRefusesWhatTheTemplateDoesNotHaveWithAPointerAndNoValue"),
            Map.entry(
                    "REQUIRED_FIELD_UNMAPPED",
                    "internal/MappingValidatorTest.java#aRequiredFieldWithNoColumnIsReportedAndSoIsItsColumnMissingFromTheFile"),
            Map.entry(
                    "FIELD_MAPPED_TWICE",
                    "internal/MappingValidatorTest.java#theInvalidFixtureHasEachProblemTheSchemaCannotSay"),
            Map.entry(
                    "COLUMN_DUPLICATE", "internal/MappingValidatorTest.java#aColumnWrittenTwiceIgnoringCaseIsReported"),
            Map.entry(
                    "MAP_FIELD_UNMAPPED",
                    "internal/MappingValidatorTest.java#theInvalidFixtureHasEachProblemTheSchemaCannotSay"),
            Map.entry(
                    "SOURCE_REF_COLUMN_UNKNOWN",
                    "internal/MappingValidatorTest.java#theInvalidFixtureHasEachProblemTheSchemaCannotSay"),
            Map.entry(
                    "SOURCE_REF_COLUMN_PERSONAL",
                    "internal/MappingValidatorTest.java#aReferenceColumnThatFeedsAPersonalFieldIsRefused"),
            Map.entry("ZONE_UNKNOWN", "internal/MappingValidatorTest.java#anUnknownZoneAndAnUnknownSourceAreReported"),
            Map.entry(
                    "SOURCE_UNKNOWN", "internal/MappingValidatorTest.java#anUnknownZoneAndAnUnknownSourceAreReported"),
            Map.entry(
                    "BATCH_HAS_NO_MAPPING",
                    "internal/ImportMappingsIT.java#aBatchRecordsItsMappingVersionAndEveryUnmappedColumnIsReported"),
            Map.entry(
                    "REQUIRED_COLUMN_MISSING",
                    "internal/MappingValidatorTest.java#aRequiredFieldWithNoColumnIsReportedAndSoIsItsColumnMissingFromTheFile"),
            // The review queue (#131): a row whose cells changed since it was resolved, after a new upload.
            Map.entry(
                    "ROW_CHANGED_SINCE_REVIEW",
                    "internal/ImportReviewIT.java#aChangedRowComesBackToTheQueueAndAnUnchangedRowKeepsItsResolution"),
            // The commit (#133): a row already imported with other content, found against import_refs by the
            // validation; and a run refused while the receivable decision it needs is missing.
            Map.entry(
                    "CHANGED_SINCE_IMPORT",
                    "internal/ImportCommitIT.java#aRowImportedBeforeWithOtherContentIsChangedSinceImportAndNotWrittenTwice"),
            Map.entry(
                    "CREDIT_DECISION_MISSING",
                    "internal/ImportCommitIT.java#theCommitIsRefusedWithoutTheDumpTheApprovalOrADecisionAndByTheMakerAsChecker"),
            // The shared row rules (#136): the lending register's sub-headers and placeholder rows, through the
            // lending template's golden run.
            Map.entry("SUBHEADER_ROW", LENDING_GOLDEN),
            Map.entry("PLACEHOLDER_ROW", LENDING_GOLDEN));

    /**
     * Codes whose producer is a later build step of spec section 9. Each step adds its fixture row or its named test and
     * moves its code out of this list: {@code ROW_CHANGED_SINCE_REVIEW} left it with the review queue of #131, and
     * {@code CHANGED_SINCE_IMPORT} with the commit of #133, which holds the {@code import_refs} it compares a row with.
     */
    static final Map<String, String> PENDING = Map.of();

    static Map<String, Object> mapping() {
        return JsonMapper.builder()
                .build()
                .readValue(FIXTURES.resolve("fixture_shop_v1.mapping.json").toFile(), new TypeReference<>() {});
    }

    static List<IssueCode> catalogue() {
        return new TemplateRegistry(List.of(new FixtureShopTemplate())).catalogue(FixtureShopTemplate.KEY, 1);
    }

    // ---------------------------------------------------------------------------------------------
    // The golden run and determinism.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theGoldenRunGivesExactlyTheExpectedIssues() throws IOException {
        List<String> expected =
                Files.readAllLines(FIXTURES.resolve("golden/expected-issues.txt"), StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.isBlank())
                        .toList();

        GoldenRun.Report report = GoldenRun.run(new FixtureShopTemplate(), mapping());

        assertThat(report.issues()).containsExactlyElementsOf(expected);
    }

    /** The list every left-out query names (preview, commit, reconciliation; issue #878): exactly these two codes. */
    @Test
    void theLeftOutCodesAreTheBlankAndSubheaderRows() {
        assertThat(ImportTemplates.leftOutSql()).isEqualTo("('BLANK_ROW', 'SUBHEADER_ROW')");
    }

    @Test
    void theSameRowsTemplateAndMappingVersionGiveIdenticalIssues() {
        GoldenRun.Report first = GoldenRun.run(new FixtureShopTemplate(), mapping());
        GoldenRun.Report second = GoldenRun.run(new FixtureShopTemplate(), mapping());

        assertThat(second.issues()).isEqualTo(first.issues());
        assertThat(second.originals()).isEqualTo(first.originals());
        assertThat(second.rows()).isEqualTo(first.rows());
    }

    @Test
    void everyChangeANormaliserMakesIsAnInfoIssueWithTheOriginal() {
        GoldenRun.Report report = GoldenRun.run(new FixtureShopTemplate(), mapping());
        Map<String, String> originals = new LinkedHashMap<>();
        for (int i = 0; i < report.issues().size(); i++) {
            String issue = report.issues().get(i);
            if (issue.contains("|VALUE_NORMALISED|")) {
                originals.put(
                        issue.substring(0, issue.indexOf("|VALUE_NORMALISED|")),
                        report.originals().get(i));
            }
        }

        assertThat(originals)
                .containsEntry("fab-item-list.csv|3|code", " fab-002 ")
                .containsEntry("fab-item-list.csv|3|price", "12,500")
                .containsEntry("fab-buyers.csv|3|phone", "0700000101")
                .containsEntry("fab-buyers.csv|4|nin", "cm 0000 0000 000B")
                .containsEntry("fab-till-roll.csv|6|item", "fab-002");
        assertThat(report.issues())
                .filteredOn(l -> l.contains("|VALUE_NORMALISED|"))
                .allMatch(l -> l.contains("|info|"));
        // The normalised values are the shared form functions' (chapter 13 section 13.6).
        assertThat(report.rows())
                .anyMatch(r -> r.startsWith("fab-buyers.csv|3|") && r.contains("phone=+256700000101"))
                .anyMatch(r -> r.startsWith("fab-buyers.csv|4|") && r.contains("nin=CM00000000000B"))
                .anyMatch(r -> r.startsWith("fab-item-list.csv|3|") && r.contains("code=FAB-002"));
    }

    @Test
    void noMessageOfTheGoldenRunHoldsAPersonalValueOrAnUnfilledPlaceholder() {
        GoldenRun.Report report = GoldenRun.run(new FixtureShopTemplate(), mapping());

        for (String issue : report.issues()) {
            String message = issue.substring(issue.lastIndexOf('|') + 1);
            assertThat(message).doesNotContain("{").doesNotContain("}");
            // A rule giving a placeholder an unsafe or personal value is a programming error this golden run catches;
            // at run time it is shown as hidden, never a 500 (Lead ruling 3, second review of #867).
            assertThat(message).doesNotContain(RowValidator.HIDDEN);
            for (String personal : GoldenRun.PERSONAL) {
                assertThat(message).as("a personal value in a message").doesNotContain(personal);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The catalogue.
    // ---------------------------------------------------------------------------------------------

    @Test
    void everyCodeOfTheCatalogueHasAFixtureThatProducesIt() throws IOException {
        Set<String> produced = new LinkedHashSet<>();
        for (String issue : GoldenRun.run(new FixtureShopTemplate(), mapping()).issues()) {
            produced.add(issue.split("\\|")[3]);
        }
        List<String> unproduced = new ArrayList<>();
        for (IssueCode c : catalogue()) {
            if (c.retired() || produced.contains(c.code()) || PENDING.containsKey(c.code())) {
                continue;
            }
            String test = PRODUCED_ELSEWHERE.get(c.code());
            if (test == null || !assertedIn(test, c.code())) {
                unproduced.add(c.code());
            }
        }

        assertThat(unproduced).as("codes no fixture row or named test produces").isEmpty();
        // The engine's own codes come from its fixture rows, never from the list of other stages.
        assertThat(produced).doesNotContainAnyElementsOf(PRODUCED_ELSEWHERE.keySet());
        assertThat(PENDING.keySet()).doesNotContainAnyElementsOf(produced);
        // A retired code is never produced.
        assertThat(produced).doesNotContain("FIXTURE_ITEM_UNNAMED");
    }

    @Test
    void noCodeIsEverRemovedRenamedOrGivenAnotherSeverity() throws IOException {
        Map<String, String> pinned = new LinkedHashMap<>();
        for (String line : Files.readAllLines(FIXTURES.resolve("issue-codes.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            assertThat(parts[0]).isIn("shared", "fixture_shop_v1", "retail_v1", "lending_loans_v1");
            if ("retail_v1".equals(parts[0]) || "lending_loans_v1".equals(parts[0])) {
                // The production templates' own codes (#135, #136), pinned against each by its own template test.
                continue;
            }
            pinned.put(
                    parts[1],
                    parts[0] + " " + parts[2] + (parts.length > 3 && "retired".equals(parts[3]) ? " retired" : ""));
        }
        // The catalogue a code is in counts too: a shared code moved into a template's own list fails (review of #867).
        Set<String> shared = new LinkedHashSet<>();
        ImportTemplates.SHARED_CODES.forEach(c -> shared.add(c.code()));
        Map<String, String> current = new LinkedHashMap<>();
        for (IssueCode c : catalogue()) {
            current.put(
                    c.code(),
                    (shared.contains(c.code()) ? "shared" : "fixture_shop_v1") + " "
                            + c.severity().key() + (c.retired() ? " retired" : ""));
        }

        assertThat(current)
                .as("the catalogue against fixtures/imports/issue-codes.txt")
                .isEqualTo(pinned);
        assertThat(ImportTemplates.SHARED_CODES).extracting(IssueCode::code).doesNotHaveDuplicates();
    }

    @Test
    void noMessageTemplateInterpolatesAPersonalFieldOrAValue() {
        ImportTemplate template = new FixtureShopTemplate();
        Set<String> personal = new LinkedHashSet<>();
        template.entitySets()
                .forEach(s -> s.fields().stream().filter(Field::isPersonal).forEach(f -> personal.add(f.name())));
        assertThat(personal).contains("name", "phone", "nin");

        for (IssueCode c : catalogue()) {
            for (String placeholder : TemplateRegistry.placeholders(c.message())) {
                assertThat(ImportTemplates.MESSAGE_PLACEHOLDERS)
                        .as("%s interpolates {%s}", c.code(), placeholder)
                        .contains(placeholder);
                assertThat(personal)
                        .as("%s interpolates the personal field {%s}", c.code(), placeholder)
                        .doesNotContain(placeholder);
            }
        }
    }

    @Test
    void theRegistryRefusesAMessageThatInterpolatesAPersonalFieldOrAValue() {
        assertRefused(withCode("Line {line} belongs to {name}."), "interpolates the personal field {name}");
        assertRefused(withCode("Line {line} reads {value}."), "interpolates {value}");
        assertRefused(withCode("Phone {phone} on line {line}."), "interpolates the personal field {phone}");
        assertRefused(withCode("Line {line} " + "is wrong ".repeat(30)), "has a message over 200 characters");
        assertThat(catalogue())
                .allSatisfy(
                        c -> assertThat(c.message()).hasSizeLessThanOrEqualTo(TemplateRegistry.MAX_MESSAGE_TEMPLATE));
    }

    @Test
    void theRegistryRefusesAKeyOrRangeItCannotUse() {
        assertRefused(
                template(new EntitySet(
                        "items",
                        List.of(Field.required("code", FieldType.money()).asKey()))),
                "a key field must be a text field");
        assertRefused(
                template(new EntitySet(
                        "items",
                        List.of(Field.required("code", FieldType.text(5)).range(BigDecimal.ONE, null)))),
                "only a money or qty field has a range");
        assertRefused(
                template(
                        new EntitySet("items", List.of(Field.required("code", FieldType.text(5)))),
                        new EntitySet("sales", List.of(Field.required("item", FieldType.ref("items"))))),
                "a ref must name an entity set with a key field");
    }

    @Test
    void aRetiredCodeOrACodeOutsideTheCatalogueIsNeverReported() {
        assertThatThrownBy(
                        () -> runWithCheck(values -> List.of(new RowFinding("FIXTURE_ITEM_UNNAMED", null, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
        assertThatThrownBy(() -> runWithCheck(values -> List.of(new RowFinding("NO_SUCH_CODE", null, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in the template's catalogue");
        assertThatThrownBy(() -> runWithCheck(values ->
                        List.of(new RowFinding("FIXTURE_SALE_QTY_NOT_POSITIVE", "qty", Map.of("name", "qty")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a message placeholder");
    }

    /**
     * Two long file names in one message still fit {@code import_issues.message} (500) (review of #867).
     * JUSTIFICATION-A3: new test cases for the reviewer's must-fix 2 and item 6.
     */
    @Test
    void aMessageWithTwoLongFileNamesStaysInsideItsColumn() {
        String longName = "fab-" + "x".repeat(240) + ".csv";
        assertThat(RowValidator.shortName(longName))
                .hasSize(RowValidator.MAX_FILE_NAME)
                .contains("...");
        assertThat(RowValidator.shortName("fab-item-list.csv")).isEqualTo("fab-item-list.csv");
        Map<String, Object> mapping = mapping();
        RowValidator engine = new RowValidator(new FixtureShopTemplate(), catalogue(), null);
        BatchInput first = new BatchInput(UUID.randomUUID(), "a-" + longName, mapping, "item_list");
        BatchInput second = new BatchInput(UUID.randomUUID(), "b-" + longName, mapping, "item_list");
        SourceRow row = new SourceRow(
                UUID.randomUUID(),
                2,
                Map.of("Fab Item Code", new RawCell("FAB-001", "text"), "Fab Item Label", new RawCell("Fab", "text")));
        engine.index(first, row);
        engine.index(second, row);

        List<RowValidator.Finding> findings = engine.validate(second, row).findings();

        assertThat(findings).extracting(RowValidator.Finding::code).contains("DUPLICATE_SOURCE_REF", "CODE_DUPLICATE");
        assertThat(findings).allSatisfy(f -> assertThat(f.message().length()).isLessThanOrEqualTo(500));

        // A file named with placeholders is shown as named: one pass over the template fills nothing inside it.
        String braces = "{other}".repeat(14);
        BatchInput third = new BatchInput(UUID.randomUUID(), braces, mapping, "item_list");
        engine.index(third, row);
        List<RowValidator.Finding> named = engine.validate(third, row).findings();
        assertThat(named)
                .filteredOn(f -> f.code().equals("CODE_DUPLICATE"))
                .singleElement()
                .satisfies(f -> assertThat(f.message())
                        .startsWith("code on " + braces + " line 2 is the same as line 2 of a-")
                        .hasSizeLessThanOrEqualTo(500));
        assertThat(RowValidator.cap("x".repeat(600))).hasSize(500).endsWith("...");
    }

    /**
     * A cross-field rule may give a placeholder only a number, a code in capitals or a name of the template, and never a
     * value of a personal field, whatever key it uses (Lead ruling on #867).
     */
    @Test
    void aRowCheckThatPutsAPersonalValueIntoAMessageIsRefused() {
        // Not a number, code or name: shown as hidden, never as written.
        assertThat(customerWithCheck("Fabricated Buyer Alpha", "0700000101", values -> (String) values.get("name")))
                .extracting(RowValidator.Finding::message)
                .containsExactly("(hidden) on fab-buyers.csv line 3 must be above zero for a sale.");
        // A name written in capitals looks like a code; it is still the row's personal value.
        assertThat(customerWithCheck("FABRICATED", "0700000101", values -> (String) values.get("name")))
                .extracting(RowValidator.Finding::message)
                .containsExactly("(hidden) on fab-buyers.csv line 3 must be above zero for a sale.");
        // A phone written in another form, by its digits.
        assertThat(customerWithCheck("Fabricated Buyer Alpha", "0700000101", values -> "700000101"))
                .extracting(RowValidator.Finding::message)
                .containsExactly("(hidden) on fab-buyers.csv line 3 must be above zero for a sale.");
        // Ordinary data never stops the run: a placeholder phone of one character is not looked for (second review).
        assertThat(customerWithCheck("Fabricated Buyer Alpha", "0", values -> "100"))
                .extracting(RowValidator.Finding::message)
                .containsExactly("100 on fab-buyers.csv line 3 must be above zero for a sale.");
        assertThat(customerWithCheck("Fabricated Buyer Alpha", "0700000101", values -> "joined_on"))
                .extracting(RowValidator.Finding::message)
                .containsExactly("joined_on on fab-buyers.csv line 3 must be above zero for a sale.");
        RowValidator engine = new RowValidator(new FixtureShopTemplate(), catalogue(), null);
        assertThat(engine.safeParam("12500")).isTrue();
        assertThat(engine.safeParam("SALE_QTY")).isTrue();
        assertThat(engine.safeParam("joined_on")).isTrue();
        assertThat(engine.safeParam("0700000101x")).isFalse();
        assertThat(engine.safeParam("Fabricated")).isFalse();
        assertThat(RowValidator.holds("CM00000000000A", List.of("cm 0000 0000 000A")))
                .isTrue();
        assertThat(RowValidator.holds("12500", List.of("0700000101"))).isFalse();
        assertThat(RowValidator.holds("256700000101", List.of("0700000101"))).isTrue();
    }

    /**
     * One customer row through a template whose customers rule names, as its field, the value the function gives: the
     * placeholder {@code field} is the engine's, so the rule's value reaches the message only through the check under test.
     */
    private static List<RowValidator.Finding> customerWithCheck(
            String name, String phone, Function<Map<String, Object>, String> param) {
        ImportTemplate leaky = new FixtureShopTemplate() {
            @Override
            public List<RowCheck> rowChecks(String entitySet) {
                return "customers".equals(entitySet)
                        ? List.of(values -> List.of(new RowFinding(
                                "FIXTURE_SALE_QTY_NOT_POSITIVE", null, Map.of("field", param.apply(values)))))
                        : List.of();
            }
        };
        RowValidator engine = new RowValidator(leaky, catalogue(), null);
        BatchInput batch = new BatchInput(UUID.randomUUID(), "fab-buyers.csv", mapping(), "buyer_book");
        SourceRow row = new SourceRow(
                UUID.randomUUID(),
                3,
                Map.of(
                        "Fab Buyer No", new RawCell("B-001", "text"),
                        "Fab Buyer", new RawCell(name, "text"),
                        "Fab Mobile", new RawCell(phone, "text")));
        engine.index(batch, row);
        return engine.validate(batch, row).findings().stream()
                .filter(f -> f.code().equals("FIXTURE_SALE_QTY_NOT_POSITIVE"))
                .toList();
    }

    @Test
    void codesAreComparedIgnoringCaseAndSpacesAndAHiddenCharacterIsFound() {
        assertThat(RowValidator.compare(" fab 001 ")).isEqualTo(RowValidator.compare("FAB001"));
        assertThat(RowValidator.compare("Fab 001")).isEqualTo("FAB001");
        assertThat(RowValidator.hidden("FAB​001")).isTrue();
        assertThat(RowValidator.hidden("FAB\t001")).isTrue();
        assertThat(RowValidator.hidden("FAB 001")).isFalse();
        assertThat(RowValidator.decimal("12,500")).isEqualByComparingTo("12500");
        assertThat(RowValidator.decimal("1 234 567.5")).isEqualByComparingTo("1234567.5");
        assertThat(RowValidator.decimal("1.5e3")).isNull();
        // Commas only between groups of three: never 1250 from 12,50 nor 15 from 1,5 (review of #867).
        assertThat(RowValidator.decimal("12,50")).isNull();
        assertThat(RowValidator.decimal("1,5")).isNull();
        assertThat(RowValidator.decimal("1,2345")).isNull();
        // A comma as a decimal point after a zero: never 250 from a quarter (second review of #867).
        assertThat(RowValidator.decimal("0,250")).isNull();
        assertThat(RowValidator.decimal("00,500")).isNull();
        assertThat(RowValidator.decimal("-0,5")).isNull();
        assertThat(RowValidator.grouped("12,500")).isTrue();
        assertThat(RowValidator.grouped(" 12500 ")).isFalse();
        assertThat(RowValidator.dateTime("day_first", "30/06/2026", false)).hasToString("2026-06-30T00:00");
        assertThat(RowValidator.dateTime("month_first", "06/30/2026 14:05", true))
                .hasToString("2026-06-30T14:05");
        assertThat(RowValidator.dateTime("iso", "30/06/2026", false)).isNull();
        assertThat(RowValidator.dateTime("day_first", "2026-06-30 14:05", false))
                .isNull();
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the named test method ({@code file#method}) asserts the code: one statement of its body, from the previous
     * {@code ;} or brace to the next {@code ;}, holds both an assertion and the code as a text literal or a constant.
     * JUSTIFICATION-A3: the reviewer's item 3 asks for method-level evidence instead of a grep of the file.
     */
    static boolean assertedIn(String test, String code) throws IOException {
        String file = Files.readString(TESTS.resolve(test.substring(0, test.indexOf('#'))));
        int start = file.indexOf(" void " + test.substring(test.indexOf('#') + 1) + "(");
        if (start < 0) {
            return false;
        }
        int end = file.indexOf("\n    }\n", start);
        String body = file.substring(start, end < 0 ? file.length() : end);
        for (String statement : body.split("[;{]")) {
            boolean names = statement.contains("\"" + code + "\"")
                    || Pattern.compile("\\." + code + "\\b").matcher(statement).find();
            boolean asserts = statement.contains("assert") || statement.contains("refused(");
            // A negative assertion (doesNotContain, isNotEqualTo, noneMatch) is no evidence (second review of #867).
            boolean negative =
                    statement.contains("doesNot") || statement.contains("isNot") || statement.contains("none");
            if (names && asserts && !negative) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pending code leaves {@link #PENDING} with the step that produces it (Lead ruling on #867): this fails as soon as
     * any main class other than the catalogue itself names the code, so the step that adds its producer must add the
     * fixture row and move the code out of the list in the same change. JUSTIFICATION-A3: the Lead's ruling 3.
     */
    @Test
    void aPendingCodeHasNoProducerYet() throws IOException {
        assertThat(PENDING).isEmpty();
        // The review queue produces ROW_CHANGED_SINCE_REVIEW (#131) and the commit CHANGED_SINCE_IMPORT (#133), each
        // proven by its named test.
        assertThat(PRODUCED_ELSEWHERE)
                .containsEntry(
                        "ROW_CHANGED_SINCE_REVIEW",
                        "internal/ImportReviewIT.java#aChangedRowComesBackToTheQueueAndAnUnchangedRowKeepsItsResolution")
                .containsEntry(
                        "CHANGED_SINCE_IMPORT",
                        "internal/ImportCommitIT.java#aRowImportedBeforeWithOtherContentIsChangedSinceImportAndNotWrittenTwice");
        // Every main file, Java, SQL or other, by the code as a word; the catalogue itself may name it once only, so a
        // constant or a second use there counts too (second review of #867).
        Path catalogue = Path.of("src/main/java/com/rincoltech/bms/core/imports/ImportTemplates.java");
        List<String> producers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file, StandardCharsets.ISO_8859_1);
                PENDING.forEach((code, issue) -> {
                    long uses = Pattern.compile("\\b" + code + "\\b")
                            .matcher(source)
                            .results()
                            .count();
                    if (uses > (file.equals(catalogue) ? 1 : 0)) {
                        producers.add(code + " (" + issue + ") in " + file);
                    }
                });
            }
        }

        assertThat(producers)
                .as("a pending code now has a producer: add its fixture row and remove it from PENDING")
                .isEmpty();
    }

    @Test
    void theEvidenceOfAnotherStageIsAnAssertionInTheNamedMethod() throws IOException {
        // A method that does not exist, or that does not assert the code, is no evidence.
        assertThat(assertedIn("internal/MappingValidatorTest.java#noSuchMethod", "ZONE_UNKNOWN"))
                .isFalse();
        assertThat(assertedIn(
                        "internal/MappingValidatorTest.java#anUnknownZoneAndAnUnknownSourceAreReported",
                        "TOO_MANY_ROWS"))
                .isFalse();
        assertThat(assertedIn(
                        "internal/MappingValidatorTest.java#anUnknownZoneAndAnUnknownSourceAreReported",
                        "ZONE_UNKNOWN"))
                .isTrue();
    }

    private static void runWithCheck(RowCheck check) {
        ImportTemplate template = new FixtureShopTemplate() {
            @Override
            public List<RowCheck> rowChecks(String entitySet) {
                return "sales".equals(entitySet) ? List.of(check) : List.of();
            }
        };
        RowValidator engine = new RowValidator(template, catalogue(), null);
        BatchInput batch = new BatchInput(UUID.randomUUID(), "fab-till-roll.csv", mapping(), "till_roll");
        Map<String, RawCell> cells = new LinkedHashMap<>();
        cells.put("Fab Item Code", new RawCell("FAB-900", "text"));
        cells.put("Fab When", new RawCell("2026-09-15T10:30:00", "datetime"));
        cells.put("Fab Count", new RawCell(new BigDecimal("1"), "number"));
        cells.put("Fab Paid", new RawCell(new BigDecimal("1000"), "number"));
        cells.put("Fab Entry Type", new RawCell("Sold", "text"));
        SourceRow row = new SourceRow(UUID.randomUUID(), 2, cells);
        engine.index(batch, row);
        engine.validate(batch, row);
    }

    private static ImportTemplate withCode(String message) {
        return new FixtureShopTemplate() {
            @Override
            public String key() {
                return "leaky";
            }

            @Override
            public List<IssueCode> issueCodes() {
                return List.of(new IssueCode("LEAKY_MESSAGE", Severity.WARNING, message));
            }
        };
    }

    private static ImportTemplate template(EntitySet... sets) {
        return new FixtureShopTemplate() {
            @Override
            public String key() {
                return "contract";
            }

            @Override
            public List<EntitySet> entitySets() {
                return List.of(sets);
            }

            @Override
            public List<IssueCode> issueCodes() {
                return List.of();
            }
        };
    }

    private static void assertRefused(ImportTemplate template, String reason) {
        assertThatThrownBy(() -> new TemplateRegistry(List.of(template)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }
}
