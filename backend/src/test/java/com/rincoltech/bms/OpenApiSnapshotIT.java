package com.rincoltech.bms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract snapshot (chapter 7 section 7.3, chapter 15 section 15.7): the generated OpenAPI
 * document must equal {@code docs/api/openapi.json}, so every contract change is visible in
 * review. After an intended change, regenerate with {@code make openapi} and commit the file;
 * the frontend's typed client is generated from it.
 */
class OpenApiSnapshotIT extends IntegrationTest {

    static final Path SNAPSHOT = Path.of("../docs/api/openapi.json");

    @Autowired
    TestRestTemplate http;

    @Test
    void theContractMatchesTheCommittedSnapshot() throws Exception {
        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        JsonNode generated = mapper.readTree(http.getForObject("/api/v1/openapi.json", String.class));
        String rendered = mapper.writeValueAsString(mapper.treeToValue(generated, Object.class)) + "\n";

        if ("true".equals(System.getenv("UPDATE_OPENAPI_SNAPSHOT"))) {
            Files.writeString(SNAPSHOT, rendered);
        }
        assertThat(Files.exists(SNAPSHOT))
                .as("docs/api/openapi.json is missing; run `make openapi`")
                .isTrue();
        assertThat(rendered)
                .as("the API contract changed; run `make openapi` and commit docs/api/openapi.json")
                .isEqualTo(Files.readString(SNAPSHOT));
    }
}
