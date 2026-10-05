package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.Cursor;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Review F7 to F9 and F12: malformed and out-of-range input is refused with a 4xx, never a 500. */
class RetailInputGuardsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-guard", false, true);
        api = new RetailTestSupport(http, t);
    }

    /** F8: every retail list answers a malformed cursor with 400 malformed_request. */
    @ParameterizedTest
    @ValueSource(strings = {"/sales", "/purchases", "/stock/movements", "/stock", "/products"})
    void aMalformedCursorIsABadRequest(String path) {
        String separator = path.equals("/stock") ? "?branch_id=" + t.headOffice() + "&" : "?";
        boolean byCode = path.equals("/stock") || path.equals("/products");
        String badTime = byCode ? "!!" : Cursor.encode("not-a-time|" + UUID.randomUUID());
        for (String cursor : new String[] {"Zm9v", "Zm9vfGJhcg", badTime, Cursor.encode("x|y"), "!!"}) {
            ResponseEntity<JsonNode> r = api.get(path + separator + "cursor=" + cursor, ADMIN);
            assertThat(r.getStatusCode())
                    .as("%s cursor %s: %s", path, cursor, r.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code").asString()).isEqualTo("malformed_request");
        }
    }
}
