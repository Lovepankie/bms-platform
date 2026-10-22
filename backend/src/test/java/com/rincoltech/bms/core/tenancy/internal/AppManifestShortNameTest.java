package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The manifest's short name (ADR-066); the frontend's branding.test.ts holds the same cases for the iPhone's title. */
class AppManifestShortNameTest {

    /** The cases shared with the frontend test: one file, so the two cuts cannot drift. */
    static final JsonNode CASES = JsonMapper.builder()
            .build()
            .readTree(Path.of("../fixtures/app-short-names.json").toFile());

    @Test
    void theSharedCasesHold() {
        for (JsonNode c : CASES.get("cases")) {
            assertThat(AppManifestController.shortName(c.get("name").asString()))
                    .as(c.get("name").asString())
                    .isEqualTo(c.get("short").asString());
        }
    }
}
