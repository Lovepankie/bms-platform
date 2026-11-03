package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The run state diagram of spec section 3.11 (issue #127). */
class RunStatesTest {

    static final List<String> ALL = List.of(
            "draft", "uploaded", "in_review", "ready", "committing", "committed", "signed_off", "purged", "cancelled");

    @Test
    void theHappyPathIsOneStepAtATime() {
        for (int i = 0; i + 1 < ALL.indexOf("cancelled"); i++) {
            assertThat(RunStates.allowed(ALL.get(i), ALL.get(i + 1)))
                    .as("%s -> %s", ALL.get(i), ALL.get(i + 1))
                    .isTrue();
        }
        assertThat(RunStates.allowed("draft", "ready")).isFalse();
        assertThat(RunStates.allowed("ready", "committed")).isFalse();
        assertThat(RunStates.allowed("committed", "ready")).isFalse();
    }

    @Test
    void aRunIsCancelledOnlyBeforeItCommits() {
        for (String from : ALL) {
            boolean expected =
                    List.of("draft", "uploaded", "in_review", "ready").contains(from);
            assertThat(RunStates.allowed(from, "cancelled"))
                    .as("%s -> cancelled", from)
                    .isEqualTo(expected);
        }
    }

    @Test
    void purgedAndCancelledAreFinal() {
        for (String to : ALL) {
            assertThat(RunStates.allowed("purged", to)).isFalse();
            assertThat(RunStates.allowed("cancelled", to)).isFalse();
        }
    }

    @Test
    void everyStateIsKnownAndStampsItsOwnColumn() {
        assertThat(ALL).allMatch(RunStates::known);
        assertThat(RunStates.known("parsing")).isFalse();
        assertThat(RunStates.stampColumn("in_review")).isEqualTo("in_review_at");
    }
}
