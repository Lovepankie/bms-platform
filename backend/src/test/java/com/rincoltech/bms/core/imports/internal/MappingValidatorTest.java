package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportMappings.Problem;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.FieldType;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.Normaliser;
import com.rincoltech.bms.core.imports.ImportTemplate.Severity;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.kernel.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canonical template contract, the registry, the JSON Schema per template version and the mapping validator (issue
 * #129), without Spring or a database, over the fabricated mappings of {@code fixtures/imports/} and the fabricated
 * {@link FixtureShopTemplate}.
 */
class MappingValidatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path FIXTURES = Path.of("../fixtures/imports");

    final TemplateRegistry registry = new TemplateRegistry(List.of(new FixtureShopTemplate()));
    final ImportTemplate shop = registry.require(FixtureShopTemplate.KEY, 1);

    static Map<String, Object> fixture(String name) {
        return JSON.readValue(FIXTURES.resolve(name).toFile(), new TypeReference<Map<String, Object>>() {});
    }

    static List<String> headers() {
        return JSON.readValue(
                FIXTURES.resolve("fixture_shop_v1.item_list.headers.json").toFile(),
                new TypeReference<List<String>>() {});
    }

    List<Problem> check(Map<String, Object> document) {
        return MappingValidator.check(document, registry::find);
    }

    static List<String> codes(List<Problem> problems) {
        return problems.stream().map(Problem::code).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> source(Map<String, Object> document, String key) {
        return (Map<String, Object>) ((Map<String, Object>) document.get("sources")).get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> columns(Map<String, Object> document, String key) {
        return (Map<String, Object>) source(document, key).get("columns");
    }

    // ---------------------------------------------------------------------------------------------
    // The acceptance criterion: a column neither mapped nor ignored makes the mapping invalid, once per column.
    // ---------------------------------------------------------------------------------------------

    @Test
    void everyColumnNeitherMappedNorIgnoredIsReportedOnceAsColumnUnmapped() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        assertThat(check(mapping)).isEmpty();

        List<String> file = new ArrayList<>(headers());
        file.add("Fab Aisle");
        file.add(" fab colour ");
        List<Problem> problems = MappingValidator.headers(shop, mapping, "item_list", file);

        assertThat(codes(problems)).containsExactly("COLUMN_UNMAPPED", "COLUMN_UNMAPPED");
        assertThat(problems).extracting(Problem::column).containsExactly("Fab Colour", "Fab Aisle");
        assertThat(problems.getFirst().message())
                .isEqualTo("Column Fab Colour is not in the mapping; map it or mark it ignored.");
        assertThat(problems.getFirst().severity()).isEqualTo("warning");
        assertThat(problems.getFirst().source()).isEqualTo("item_list");
    }

    @Test
    void anIgnoredColumnIsNotUnmappedAndHeadersCompareTrimmedIgnoringCase() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        List<String> file = List.of(
                "FAB ITEM CODE",
                "fab item label",
                "Fab Pack ",
                "Fab Asking Price",
                "Fab Line Type",
                "Fab In Use",
                "Fab Shelf Note");

        assertThat(MappingValidator.headers(shop, mapping, "item_list", file)).isEmpty();
    }

    @Test
    void aRequiredFieldWithNoColumnIsReportedAndSoIsItsColumnMissingFromTheFile() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        columns(mapping, "till_roll").remove("Fab When");

        List<Problem> problems = check(mapping);
        assertThat(codes(problems)).containsExactly("REQUIRED_FIELD_UNMAPPED");
        assertThat(problems.getFirst().field()).isEqualTo("sold_at");
        assertThat(problems.getFirst().message())
                .isEqualTo("sold_at of sales is required and no column of source till_roll feeds it.");

        Map<String, Object> valid = fixture("fixture_shop_v1.mapping.json");
        List<Problem> missing = MappingValidator.headers(
                shop, valid, "item_list", List.of("Fab Item Code", "Fab Pack", "Fab Shelf Note"));
        assertThat(codes(missing)).containsExactly("REQUIRED_COLUMN_MISSING");
        assertThat(missing.getFirst().column()).isEqualTo("Fab Item Label");
        assertThat(missing.getFirst().field()).isEqualTo("name");
    }

    @Test
    void theInvalidFixtureHasEachProblemTheSchemaCannotSay() {
        List<Problem> problems = check(fixture("fixture_shop_v1.invalid.mapping.json"));

        assertThat(codes(problems))
                .containsExactly(
                        "FIELD_MAPPED_TWICE",
                        "REQUIRED_FIELD_UNMAPPED",
                        "MAP_FIELD_UNMAPPED",
                        "SOURCE_REF_COLUMN_UNKNOWN");
        assertThat(problems).allSatisfy(p -> assertThat(p.severity()).isEqualTo("blocking"));
    }

    @Test
    void aColumnWrittenTwiceIgnoringCaseIsReported() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        columns(mapping, "till_roll").put(" fab count", Map.of("ignore", true, "reason", "written twice on purpose"));

        assertThat(codes(check(mapping))).containsExactly("COLUMN_DUPLICATE");
    }

    @Test
    void anUnknownZoneAndAnUnknownSourceAreReported() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        mapping.put("time_zone", "Fabricated/Nowhere");
        assertThat(codes(check(mapping))).containsExactly("ZONE_UNKNOWN");

        Map<String, Object> valid = fixture("fixture_shop_v1.mapping.json");
        assertThat(codes(MappingValidator.headers(shop, valid, "no_such_source", headers())))
                .containsExactly("SOURCE_UNKNOWN");
    }

    // ---------------------------------------------------------------------------------------------
    // The schema.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theSchemaRefusesWhatTheTemplateDoesNotHaveWithAPointerAndNoValue() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        columns(mapping, "item_list").put("Fab Weight", Map.of("field", "weight"));
        columns(mapping, "item_list").put("Fab Bin", Map.of("ignore", true));
        Map<String, Object> enumMaps = new LinkedHashMap<>(Map.of("kind", Map.of("Fabricated label", "rental")));
        source(mapping, "item_list").put("enum_maps", enumMaps);
        source(mapping, "item_list").put("row_rules", Map.of("totals_row", "last"));
        source(mapping, "item_list").put("header_row", 21);
        source(mapping, "buyer_book").put("entity_set", "suppliers");
        mapping.put("currency", "ugx");
        mapping.put("extra", true);

        List<Problem> problems = check(mapping);

        assertThat(problems).allSatisfy(p -> assertThat(p.code()).isEqualTo("MAPPING_INVALID"));
        assertThat(problems)
                .extracting(Problem::pointer)
                .contains(
                        "/currency",
                        "/extra",
                        "/sources/item_list/columns/Fab Weight",
                        "/sources/item_list/columns/Fab Bin",
                        "/sources/item_list/enum_maps/kind/Fabricated label",
                        "/sources/item_list/row_rules/totals_row",
                        "/sources/item_list/header_row",
                        "/sources/buyer_book/entity_set")
                .doesNotHaveDuplicates();
        assertThat(problems).noneSatisfy(p -> assertThat(p.message()).contains("rental"));
    }

    @Test
    void aSchemaProblemDoesNotHideTheProblemsOfTheSourcesTheSchemaAccepted() {
        // JUSTIFICATION-A3: a new test case for the review of #866 (nice-to-have 4 and 5), no new mechanism.
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        mapping.put("currency", "UGX\n");
        columns(mapping, "till_roll").remove("Fab When");
        source(mapping, "item_list").put("header_row", "first");
        columns(mapping, "item_list").remove("Fab Item Label");

        List<Problem> problems = check(mapping);

        assertThat(problems)
                .extracting(Problem::code, Problem::pointer, Problem::field)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("MAPPING_INVALID", "/currency", null),
                        org.assertj.core.groups.Tuple.tuple("MAPPING_INVALID", "/sources/item_list/header_row", null),
                        org.assertj.core.groups.Tuple.tuple("REQUIRED_FIELD_UNMAPPED", null, "sold_at"));
        assertThat(SchemaCheck.strictEnd("^[A-Z]{3}$")).isEqualTo("^[A-Z]{3}\\z");
        assertThat(SchemaCheck.strictEnd("^a\\$")).isEqualTo("^a\\$");
    }

    @Test
    void aDocumentForATemplateNotRegisteredHasOneProblem() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");
        mapping.put("template_version", 2);

        assertThat(codes(check(mapping))).containsExactly("TEMPLATE_UNKNOWN");
        assertThat(codes(check(Map.of("currency", "UGX")))).containsExactly("MAPPING_INVALID");
        assertThat(codes(check(null))).containsExactly("MAPPING_INVALID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaIsDraft202012AndNarrowsEachEntitySetToItsOwnFields() {
        Map<String, Object> schema = registry.schema(FixtureShopTemplate.KEY, 1);

        assertThat(schema.get("$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("$id")).isEqualTo("urn:bms:imports:mapping:fixture_shop_v1");
        Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
        assertThat(defs)
                .containsKeys("source", "ignored", "source_ref", "entity_items", "entity_customers", "entity_sales");
        Map<String, Object> sales =
                (Map<String, Object>) ((Map<String, Object>) defs.get("entity_sales")).get("properties");
        Map<String, Object> enumMaps = (Map<String, Object>) sales.get("enum_maps");
        assertThat((Map<String, Object>) enumMaps.get("propertyNames")).containsEntry("enum", List.of("kind"));
        assertThatThrownBy(() -> registry.schema("no_such_template", 1))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("TEMPLATE_UNKNOWN"));
    }

    @Test
    void theSchemaCheckRefusesAKeywordItDoesNotInterpret() {
        assertThatThrownBy(() -> SchemaCheck.check(Map.of("oneOf", List.of()), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oneOf");
    }

    @Test
    void theRequiredAndIgnoredColumnsOfASourceFeedTheParse() {
        Map<String, Object> mapping = fixture("fixture_shop_v1.mapping.json");

        assertThat(MappingValidator.requiredColumns(shop, source(mapping, "till_roll")))
                .containsExactly("Fab Item Code", "Fab When", "Fab Count", "Fab Paid", "Fab Entry Type");
        assertThat(MappingValidator.ignoredColumns(source(mapping, "till_roll")))
                .containsExactly("Fab Cashier");
    }

    // ---------------------------------------------------------------------------------------------
    // The registry and the contract.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theCatalogueIsTheSharedCodesThenTheTemplatesOwnAndEveryCodeIsUnique() {
        List<IssueCode> catalogue = registry.catalogue(FixtureShopTemplate.KEY, 1);

        assertThat(catalogue).startsWith(ImportTemplates.SHARED_CODES.toArray(IssueCode[]::new));
        assertThat(catalogue.getLast().code()).isEqualTo("FIXTURE_ITEM_UNNAMED");
        Set<String> codes = new HashSet<>();
        assertThat(catalogue).allSatisfy(c -> assertThat(codes.add(c.code())).isTrue());
        assertThat(catalogue)
                .extracting(IssueCode::code)
                .contains(
                        "COLUMN_UNMAPPED",
                        "REQUIRED_FIELD_UNMAPPED",
                        "MAPPING_INVALID",
                        "HEADER_NOT_FOUND",
                        "REQUIRED_MISSING",
                        "TYPE_INVALID");
        assertThat(registry.list()).extracting(ImportTemplate::id).containsExactly("fixture_shop_v1");
    }

    @Test
    void theRegistryRefusesATemplateItCannotTrust() {
        assertRefused(List.of(new FixtureShopTemplate(), new FixtureShopTemplate()), "registered twice");
        assertRefused(
                List.of(template(
                        "later_ref",
                        List.of(
                                new EntitySet("sales", List.of(Field.required("item", FieldType.ref("items")))),
                                new EntitySet("items", List.of(Field.required("code", FieldType.text(10))))))),
                "a ref must name this entity set or one committed before it");
        assertRefused(
                List.of(template(
                        "empty_enum",
                        List.of(new EntitySet("items", List.of(Field.required("kind", FieldType.enumOf())))))),
                "an enum needs distinct values");
        assertRefused(
                List.of(template(
                        "no_length",
                        List.of(new EntitySet("items", List.of(Field.required("code", FieldType.text(0))))))),
                "a text field needs a length above 0");
        assertRefused(
                List.of(template(
                        "twice",
                        List.of(new EntitySet(
                                "items",
                                List.of(
                                        Field.required("code", FieldType.text(5)),
                                        Field.optional("code", FieldType.money())))))),
                "the field is declared twice");
        assertRefused(
                List.of(template(
                        "Bad-Key",
                        List.of(new EntitySet("items", List.of(Field.required("code", FieldType.text(5))))))),
                "the key must be");
        ImportTemplate clash = new FixtureShopTemplate() {
            @Override
            public String key() {
                return "clash";
            }

            @Override
            public List<IssueCode> issueCodes() {
                return List.of(new IssueCode("COLUMN_UNMAPPED", Severity.WARNING, "Taken."));
            }
        };
        assertRefused(List.of(clash), "is a shared code");
    }

    @Test
    void theFieldTypesReadAsTheSpecWritesThemAndTheNormalisersAreNamed() {
        assertThat(FieldType.text(120).spec()).isEqualTo("text(120)");
        assertThat(FieldType.enumOf("sale", "adjustment").spec()).isEqualTo("enum(sale|adjustment)");
        assertThat(FieldType.ref("items").spec()).isEqualTo("ref(items)");
        assertThat(List.of(
                        FieldType.money(),
                        FieldType.qty(),
                        FieldType.date(),
                        FieldType.datetime(),
                        FieldType.phone(),
                        FieldType.nin(),
                        FieldType.bool()))
                .extracting(FieldType::spec)
                .containsExactly("money", "qty", "date", "datetime", "phone", "nin", "bool");
        assertThat(Normaliser.UPPER.apply("  fab  item-1 ")).isEqualTo("FAB ITEM-1");
        assertThat(Normaliser.COLLAPSE_SPACES.apply(" Fab   Name ")).isEqualTo("Fab Name");
        assertThat(Field.optional("unit", FieldType.text(5)).normaliser()).isEqualTo(Normaliser.TRIM);
    }

    // ---------------------------------------------------------------------------------------------
    // The mapping's fixed header row reaches the parser.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aFixedHeaderRowIsTakenAsItIsAndABlankOrIncompleteOneIsHeaderNotFound() {
        List<String> header = new ArrayList<>();
        Parsing.Sink sink = new Parsing.Sink() {
            @Override
            public void header(int headerRowNo, List<String> names) {
                header.addAll(names);
            }

            @Override
            public void row(int sourceRowNo, Map<String, Parsing.Cell> raw) {}
        };
        Parsing.Limits fixedAt2 = new Parsing.Limits(100, 200, Set.of("Fab Buyer No"), Set.of(), 1_000_000, 2);
        Parsing.Sheet sheet = new Parsing.Sheet(fixedAt2, sink);
        // Row 1 would hold every required header, but the mapping says row 2.
        sheet.row(1, List.of(Parsing.Cell.text("Fab Buyer No"), Parsing.Cell.text("Fab Buyer")));
        sheet.row(2, List.of(Parsing.Cell.text("Fab Buyer No"), Parsing.Cell.text("Fab Mobile")));
        sheet.row(3, List.of(Parsing.Cell.text("FAB-B1"), Parsing.Cell.text("0700000001")));
        sheet.end();
        assertThat(sheet.headerRowNo()).isEqualTo(2);
        assertThat(header).containsExactly("Fab Buyer No", "Fab Mobile");

        Parsing.Sheet blank = new Parsing.Sheet(fixedAt2, sink);
        blank.row(1, List.of(Parsing.Cell.text("Fab Title")));
        assertThatThrownBy(() -> blank.row(3, List.of(Parsing.Cell.text("Fab Buyer No"))))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo("HEADER_NOT_FOUND"));

        Parsing.Sheet incomplete = new Parsing.Sheet(fixedAt2, sink);
        incomplete.row(1, List.of(Parsing.Cell.text("Fab Title")));
        assertThatThrownBy(() -> incomplete.row(2, List.of(Parsing.Cell.text("Fab Buyer"))))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo("HEADER_NOT_FOUND"));
    }

    private static void assertRefused(List<ImportTemplate> templates, String reason) {
        assertThatThrownBy(() -> new TemplateRegistry(templates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    private static ImportTemplate template(String key, List<EntitySet> sets) {
        return new ImportTemplate() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public List<EntitySet> entitySets() {
                return sets;
            }

            @Override
            public List<IssueCode> issueCodes() {
                return List.of();
            }

            @Override
            public BatchWriter loader(String entitySet) {
                return (runId, batchId, seq, checkpoint) -> Map.of();
            }
        };
    }
}
