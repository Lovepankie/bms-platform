package com.rincoltech.bms.retail.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rincoltech.bms.retail.imports.internal.FabricatedRunStager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** What {@code stage-import-run} prints and exits with when the stager refuses a failed dry run (issue #885). */
class StageImportRunCommandTest {

    @Test
    void aFailedDryRunIsPrintedWithItsFailureAndExitsOne() {
        Path dir = Path.of("/tmp/test-import-sample");
        String failure = "dry_run_failed: batch 1 failed (plan_limit_reached: the plan's limit refused this batch;"
                + " move the business to a plan with room)";
        FabricatedRunStager stager = mock(FabricatedRunStager.class);
        when(stager.stage("test-shop", dir)).thenThrow(new IllegalStateException(failure));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = StageImportRunCommand.run(
                stager,
                new StageImportRunCommand.Options("test-shop", dir),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        String printed = err.toString(StandardCharsets.UTF_8);
        assertThat(printed).isEqualTo("stage-import-run refused: " + failure + System.lineSeparator());
    }
}
