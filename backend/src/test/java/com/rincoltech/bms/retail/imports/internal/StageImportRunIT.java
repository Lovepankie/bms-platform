package com.rincoltech.bms.retail.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRehearsals;
import com.rincoltech.bms.core.imports.ImportRehearsals.Rehearsal;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The fabricated run of the rehearsal's acceptance check (issue #138, review of #882), on real PostgreSQL as bms_app:
 * {@code stage-import-run} stages the fabricated {@code fixtures/retail/import-sample/} as a run of the import screens,
 * marked fabricated, reviewed, dry run and ready; the rehearsal then commits it in a database holding the token, and its
 * report says what the staged dry run said. Refused, with no run opened, anywhere but staging (an unset, blank or misspelt
 * environment included) and for any export that is not the fabricated sample byte for byte (review of #882).
 */
class StageImportRunIT extends IntegrationTest {

    static final String TOKEN = "beadfeed".repeat(6);

    static final String BACKUP = "bms-staging-20261024T011500Z.dump.enc";

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportRehearsals rehearsals;

    @Autowired
    TenantJobs tenants;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportReview review;

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("stage", false, true);
    }

    @AfterEach
    void reset() {
        TestDatabase.owner().sql("DROP TABLE IF EXISTS bms_rehearsal_marker").update();
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    /** The stager as it is built on a host whose BMS_ENVIRONMENT is the given value. */
    FabricatedRunStager on(String environment) {
        return new FabricatedRunStager(tenants, runs, mappings, uploads, validation, review, clock, environment);
    }

    @Test
    void aStagedFabricatedRunIsReadyAndTheRehearsalCommitsIt() {
        UUID run = on("staging").stage(t.slug(), RetailImportIT.SAMPLE);

        ImportRuns.Run staged = as(() -> runs.get(run));
        assertThat(staged.status()).isEqualTo("ready");
        assertThat(staged.fabricated()).isTrue();
        assertThat(as(() -> runs.dryRunReport(run)).orElseThrow().current()).isTrue();

        JdbcClient owner = TestDatabase.owner();
        owner.sql("CREATE TABLE bms_rehearsal_marker (token text NOT NULL)").update();
        owner.sql("INSERT INTO bms_rehearsal_marker (token) VALUES (?)")
                .param(TOKEN)
                .update();
        owner.sql("GRANT SELECT ON bms_rehearsal_marker TO bms_app").update();
        Rehearsal rehearsal = as(() -> rehearsals.rehearse(run, TOKEN, BACKUP, Map.of()));

        assertThat(rehearsal.outcome()).isEqualTo("committed");
        assertThat(rehearsal.matchesDryRun()).isTrue();
        assertThat(as(() -> runs.get(run)).status()).isEqualTo("committed");
    }

    @Test
    void anythingButStagingIsRefusedAnUnsetOrBlankEnvironmentToo() {
        for (String environment : List.of("production", "prod", "", " ", "Staging", "staging2")) {
            assertThatThrownBy(() -> on(environment).stage(t.slug(), RetailImportIT.SAMPLE))
                    .as("BMS_ENVIRONMENT=%s", environment)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only on staging");
        }
        assertThatThrownBy(() -> on(null).stage(t.slug(), RetailImportIT.SAMPLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only on staging");
        assertThat(openRuns()).isZero();
    }

    @Test
    void anExportThatIsNotTheFabricatedSampleIsRefusedEvenOnStaging(@TempDir Path copy) throws IOException {
        // An empty directory, the sample with one file missing, and the sample with one row changed.
        assertThatThrownBy(() -> on("staging").stage(t.slug(), copy)).hasMessageContaining("only the fabricated");
        for (String set : FabricatedRunStager.FIXTURE.keySet()) {
            Files.copy(RetailImportIT.SAMPLE.resolve(set + ".jsonl"), copy.resolve(set + ".jsonl"));
        }
        Files.delete(copy.resolve("usage.jsonl"));
        assertThatThrownBy(() -> on("staging").stage(t.slug(), copy)).hasMessageContaining("only the fabricated");
        Files.copy(RetailImportIT.SAMPLE.resolve("usage.jsonl"), copy.resolve("usage.jsonl"));
        Files.writeString(copy.resolve("products.jsonl"), "\n", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> on("staging").stage(t.slug(), copy)).hasMessageContaining("only the fabricated");
        assertThat(openRuns()).isZero();
    }

    /** The business's runs: none is opened by a refused staging. */
    long openRuns() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM import_runs WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }
}
