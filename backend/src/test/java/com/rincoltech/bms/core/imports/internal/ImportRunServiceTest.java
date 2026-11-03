package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The audit of a failed batch never replaces the batch's own error (review of #736, issue #779). */
class ImportRunServiceTest {

    @Test
    void aFailingAuditIsAddedAsSuppressedAndTheBatchErrorIsTheOneThrown() {
        IllegalStateException batch = new IllegalStateException("fabricated row refused");
        IllegalStateException lost = new IllegalStateException("fabricated connection lost");

        RuntimeException thrown = ImportRunService.audited(batch, () -> {
            throw lost;
        });

        assertThat(thrown).isSameAs(batch);
        assertThat(thrown.getSuppressed()).containsExactly(lost);
    }

    @Test
    void anAuditThatSucceedsLeavesTheBatchErrorAsItWas() {
        IllegalStateException batch = new IllegalStateException("fabricated row refused");
        AtomicBoolean audited = new AtomicBoolean();

        RuntimeException thrown = ImportRunService.audited(batch, () -> audited.set(true));

        assertThat(audited).isTrue();
        assertThat(thrown).isSameAs(batch);
        assertThat(thrown.getSuppressed()).isEmpty();
    }
}
