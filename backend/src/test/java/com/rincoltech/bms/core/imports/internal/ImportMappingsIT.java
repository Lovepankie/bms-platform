package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportMappings.Problem;
import com.rincoltech.bms.core.imports.ImportMappings.Validation;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.CommitOutcome;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.ParseOptions;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned mappings (issue #129) against real PostgreSQL as bms_app: the registry finds the template a test bean registers
 * without the core naming it; a mapping is stored as append-only versions validated against its template's schema; a batch
 * parsed with a version records it and is validated against the headers its parse found, one {@code COLUMN_UNMAPPED} per
 * column; a new version never changes a committed batch; and another business can neither read nor write a mapping.
 * Fabricated data only: the mapping is {@code fixtures/imports/fixture_shop_v1.mapping.json} and every cell below is made up.
 */
class ImportMappingsIT extends IntegrationTest {

    static final String SHA = "c".repeat(64);
    static final String ITEMS_CSV = "Fab Item Code,Fab Item Label,Fab Pack,Fab Shelf Note,Fab Colour,Fab Aisle\r\n"
            + "FAB-001,Fabricated Kettle,pcs,top shelf,blue,3\r\n"
            + "FAB-002,Fabricated Basin,pcs,floor,red,4\r\n";

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportTemplates templates;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportRuns runs;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("mapping", false);
        other = TestDatabase.tenant("mapping-b", false);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    static Map<String, Object> fixture(String name) {
        return JsonMapper.builder()
                .build()
                .readValue(Path.of("../fixtures/imports").resolve(name).toFile(), new TypeReference<>() {});
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    Batch upload(UUID runId, ParseOptions options) {
        byte[] bytes = ITEMS_CSV.getBytes(StandardCharsets.UTF_8);
        return as(
                t,
                () -> uploads.upload(
                        runId,
                        new Upload(
                                "fab-item-list.csv",
                                new ByteArrayInputStream(bytes),
                                bytes.length,
                                operator,
                                options)));
    }

    @Test
    void theRegistryHoldsTheTemplateATestBeanRegisters() {
        assertThat(templates.find(FixtureShopTemplate.KEY, 1)).isPresent();
        assertThat(templates.schema(FixtureShopTemplate.KEY, 1)).containsKey("$defs");
        assertCode(() -> templates.require("no_such_template", 1), "TEMPLATE_UNKNOWN");
    }

    @Test
    void eachSaveIsANewVersionAndEarlierVersionsStayAsTheyWere() {
        Map<String, Object> document = fixture("fixture_shop_v1.mapping.json");
        Mapping first = as(t, () -> mappings.save("fab-shop", document, operator, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> enumMaps = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) document.get("sources")).get("till_roll"))
                        .get("enum_maps");
        enumMaps.put("kind", Map.of("Sold", "sale", "Stock take", "adjustment", "Count", "adjustment"));
        Mapping second = as(t, () -> mappings.save("fab-shop", document, operator, "map_value from review question 3"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.templateKey()).isEqualTo(FixtureShopTemplate.KEY);
        assertThat(second.templateVersion()).isEqualTo(1);
        assertThat(second.note()).isEqualTo("map_value from review question 3");
        assertThat(as(t, () -> mappings.versions("fab-shop")))
                .extracting(Mapping::version)
                .containsExactly(1, 2);
        assertThat(as(t, () -> mappings.latest("fab-shop")).orElseThrow().id()).isEqualTo(second.id());
        assertThat(as(t, () -> mappings.get(first.id())).document())
                .isEqualTo(first.document())
                .isEqualTo(fixture("fixture_shop_v1.mapping.json"));
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_mapping.saved'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void anInvalidMappingIsRefusedWithEveryProblemAndNothingIsStored() {
        assertThatThrownBy(() -> as(
                        t,
                        () -> mappings.save(
                                "fab-bad", fixture("fixture_shop_v1.invalid.mapping.json"), operator, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("mapping_invalid");
                    assertThat(e.errors())
                            .extracting(ApiException.FieldProblem::code)
                            .containsExactly(
                                    "FIELD_MAPPED_TWICE",
                                    "REQUIRED_FIELD_UNMAPPED",
                                    "MAP_FIELD_UNMAPPED",
                                    "SOURCE_REF_COLUMN_UNKNOWN");
                });
        Map<String, Object> unknown = fixture("fixture_shop_v1.mapping.json");
        unknown.put("template", "no_such_template");
        assertCode(() -> as(t, () -> mappings.save("fab-bad", unknown, operator, null)), "TEMPLATE_UNKNOWN");
        assertCode(
                () -> as(t, () -> mappings.save("Fab Bad", fixture("fixture_shop_v1.mapping.json"), operator, null)),
                "validation_failed");
        assertThat(as(t, () -> mappings.versions("fab-bad"))).isEmpty();
    }

    @Test
    void aBatchRecordsItsMappingVersionAndEveryUnmappedColumnIsReported() {
        Mapping v1 = as(t, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));
        ParseOptions options = as(t, () -> mappings.parseOptions(v1.id(), "item_list"));
        assertThat(options.ignoredColumns()).containsExactly("Fab Shelf Note");
        assertThat(options.requiredHeaders()).containsExactlyInAnyOrder("Fab Item Code", "Fab Item Label");
        assertCode(() -> as(t, () -> mappings.parseOptions(v1.id(), "no_such_source")), "SOURCE_UNKNOWN");
        UUID runId = as(t, () -> runs.open(operator).id());

        Batch batch = upload(runId, options);

        assertThat(batch.status()).isEqualTo("parsed");
        assertThat(batch.mappingId()).isEqualTo(v1.id());
        assertThat(batch.mappingSource()).isEqualTo("item_list");
        assertThat(batch.templateKey()).isEqualTo(FixtureShopTemplate.KEY);
        assertThat(batch.templateVersion()).isEqualTo(1);
        assertThat(batch.headers())
                .containsExactly(
                        "Fab Item Code", "Fab Item Label", "Fab Pack", "Fab Shelf Note", "Fab Colour", "Fab Aisle");
        // The ignored column is never stored (spec section 6.1).
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM import_rows WHERE batch_id = ? AND raw -> 'Fab Shelf Note' IS NOT NULL")
                        .param(batch.id())
                        .query(Long.class)
                        .single())
                .isZero();

        Validation validation = as(t, () -> mappings.validateBatch(batch.id()));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.problems())
                .extracting(Problem::code)
                .containsExactly("COLUMN_UNMAPPED", "COLUMN_UNMAPPED");
        assertThat(validation.problems()).extracting(Problem::column).containsExactly("Fab Colour", "Fab Aisle");
        assertThat(validation.problems().toString()).doesNotContain("Fabricated Kettle", "blue");

        Batch plain = as(t, () -> uploads.parse(batch.id(), ParseOptions.DEFAULT));
        assertThat(plain.mappingId()).isNull();
        assertThat(as(t, () -> mappings.validateBatch(batch.id())).problems())
                .extracting(Problem::code)
                .containsExactly("BATCH_HAS_NO_MAPPING");
    }

    // JUSTIFICATION-A3: two new test cases for the review of #866 (must-fix 1, nice-to-have 2 and 3), no new mechanism.
    @Test
    void aBatchIsParsedWithItsMappingsOwnOptionsNeverTheCallers() {
        Mapping v1 = as(t, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));
        UUID runId = as(t, () -> runs.open(operator).id());

        // The caller names the mapping but none of its options (review of #866): the ignored column is not stored.
        Batch batch = upload(runId, new ParseOptions(null, Set.of(), Set.of(), 0, v1.id(), "item_list"));

        assertThat(batch.mappingId()).isEqualTo(v1.id());
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM import_rows WHERE batch_id = ? AND raw -> 'Fab Shelf Note' IS NOT NULL")
                        .param(batch.id())
                        .query(Long.class)
                        .single())
                .isZero();
        // A source the mapping lacks is refused before anything is stored.
        long before = batches(runId);
        assertCode(
                () -> upload(runId, new ParseOptions(null, Set.of(), Set.of(), 0, v1.id(), "bogus")), "SOURCE_UNKNOWN");
        assertThat(batches(runId)).isEqualTo(before);
    }

    @Test
    void aFailedReparseForgetsTheMappingAndOneUpdateCannotCommitAndMoveIt() {
        Mapping v1 = as(t, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));
        Map<String, Object> fixedRow = fixture("fixture_shop_v1.mapping.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> items =
                (Map<String, Object>) ((Map<String, Object>) fixedRow.get("sources")).get("item_list");
        // Row 2 of the file is a data row, so it lacks the required headers: HEADER_NOT_FOUND.
        items.put("header_row", 2);
        Mapping v2 = as(t, () -> mappings.save("fab-shop", fixedRow, operator, null));
        UUID runId = as(t, () -> runs.open(operator).id());
        Batch batch = upload(runId, as(t, () -> mappings.parseOptions(v1.id(), "item_list")));

        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_batches SET status = 'committed', committed_at = now(), mapping_id = NULL,"
                                + " mapping_source = NULL, template_key = NULL, template_version = NULL WHERE id = ?")
                        .param(batch.id())
                        .update())
                .hasStackTraceContaining("the mapping of a committed import batch never changes");

        Batch failed = as(t, () -> uploads.parse(batch.id(), mappings.parseOptions(v2.id(), "item_list")));

        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.failureCode()).isEqualTo("HEADER_NOT_FOUND");
        assertThat(failed.mappingId()).isNull();
        assertThat(failed.headers()).isEmpty();
        assertCode(() -> as(t, () -> mappings.validateBatch(batch.id())), "batch_not_parsed");
    }

    long batches(UUID runId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM import_batches WHERE run_id = ?")
                .param(runId)
                .query(Long.class)
                .single();
    }

    @Test
    void aNewMappingVersionNeverChangesACommittedBatch() {
        Mapping v1 = as(t, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));
        UUID runId = as(t, () -> runs.open(operator).id());
        Batch batch = upload(runId, as(t, () -> mappings.parseOptions(v1.id(), "item_list")));
        as(t, () -> {
            runs.transition(runId, "in_review");
            // Ready needs a stored dry run since #132: the commit's own writer, the template's loader, rolled back.
            runs.dryRun(runId, operator);
            runs.transition(runId, "ready");
            return runs.recordPreImportDump(runId, "pre-import-fabricated.dump", Instant.now(), SHA);
        });
        // The commit resolves the batch's writer from its template, the fixture's loader for items.
        CommitOutcome outcome = as(t, () -> runs.commit(runId));
        assertThat(outcome).isEqualTo(CommitOutcome.COMMITTED);

        Map<String, Object> changed = fixture("fixture_shop_v1.mapping.json");
        changed.put("date_convention", "month_first");
        Mapping v2 = as(t, () -> mappings.save("fab-shop", changed, operator, "the customer's dates are month first"));

        Batch after = as(t, () -> uploads.batch(batch.id()));
        assertThat(after.status()).isEqualTo("committed");
        assertThat(after.mappingId()).isEqualTo(v1.id()).isNotEqualTo(v2.id());
        assertThat(as(t, () -> mappings.get(v1.id())).document()).containsEntry("date_convention", "day_first");
        // A parse with the new version is refused: the run is past review.
        assertCode(
                () -> as(t, () -> uploads.parse(batch.id(), mappings.parseOptions(v2.id(), "item_list"))),
                "invalid_status_transition");
        // Not even the owner role moves a committed batch to another mapping.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_batches SET mapping_id = ? WHERE id = ?")
                        .params(v2.id(), batch.id())
                        .update())
                .hasStackTraceContaining("the mapping of a committed import batch never changes");
    }

    @Test
    void aStoredVersionIsNeverUpdatedOrDeleted() throws SQLException {
        Mapping v1 = as(t, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));

        assertThatThrownBy(() -> asApp(
                        t.tenantId(), c -> execute(c, "UPDATE import_mappings SET note = 'x' WHERE id = ?", v1.id())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(
                        () -> asApp(t.tenantId(), c -> execute(c, "DELETE FROM import_mappings WHERE id = ?", v1.id())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_mappings SET note = 'x' WHERE id = ?")
                        .param(v1.id())
                        .update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void anotherBusinessCanNeitherReadNorWriteAMapping() throws SQLException {
        Mapping mine = as(t, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));

        assertCode(() -> as(other, () -> mappings.get(mine.id())), "not_found");
        assertCode(() -> as(other, () -> mappings.parseOptions(mine.id(), "item_list")), "not_found");
        assertThat(as(other, () -> mappings.latest("fab-shop"))).isEmpty();
        assertThat(as(other, () -> mappings.versions("fab-shop"))).isEmpty();
        long seenByOther = asApp(
                other.tenantId(),
                c -> count(c, "SELECT count(*) FROM import_mappings WHERE tenant_id = ?", t.tenantId()));
        assertThat(seenByOther).isZero();
        // Bound to the other business, a row for this one is refused by the policy's WITH CHECK.
        assertThatThrownBy(() -> asApp(other.tenantId(), c -> execute(c, """
                        INSERT INTO import_mappings (id, tenant_id, name, version, template_key, template_version, document,
                                                     created_by)
                        VALUES (gen_random_uuid(), ?, 'fab-shop', 9, 'fixture_shop', 1,
                                '{"template": "fixture_shop", "template_version": 1}', gen_random_uuid())
                        """, t.tenantId())))
                .hasStackTraceContaining("row-level security");
        // The other business's own version 1 of the same name is its own.
        Mapping theirs =
                as(other, () -> mappings.save("fab-shop", fixture("fixture_shop_v1.mapping.json"), operator, null));
        assertThat(theirs.version()).isEqualTo(1);
        assertThat(as(t, () -> mappings.versions("fab-shop")))
                .extracting(Mapping::id)
                .containsExactly(mine.id());
        // A batch of the other business cannot record this business's mapping.
        UUID theirRun = as(other, () -> runs.open(operator).id());
        byte[] bytes = ITEMS_CSV.getBytes(StandardCharsets.UTF_8);
        assertCode(
                () -> as(
                        other,
                        () -> uploads.upload(
                                theirRun,
                                new Upload(
                                        "fab-item-list.csv",
                                        new ByteArrayInputStream(bytes),
                                        bytes.length,
                                        operator,
                                        new ParseOptions(null, Set.of(), Set.of(), 0, mine.id(), "item_list")))),
                "not_found");
    }

    interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    static <T> T asApp(UUID tenantId, SqlWork<T> work) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
            try {
                return work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
