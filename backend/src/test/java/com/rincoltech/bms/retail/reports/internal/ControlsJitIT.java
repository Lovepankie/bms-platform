package com.rincoltech.bms.retail.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * The Controls statements run with JIT off inside the request's own transaction (issue #357, ADR-087). The repository is spied
 * on: after each real statement, {@code current_setting('jit')} is read on the same transaction's connection, inside a real
 * HTTP request to the Controls routes. {@code SET LOCAL} outside a transaction would be a no-op with a warning and read
 * {@code on} here. After the request the pooled connection reads JIT as before, so nothing leaks to other requests.
 */
class ControlsJitIT extends IntegrationTest {

    static final String CONTROLS = "retail.controls.read,retail.sale.read,retail.profit.read";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    ControlsRepository repo;

    @Test
    void everyControlsStatementSeesJitOffInItsRequestAndNothingLeaks() {
        List<String> seen = new CopyOnWriteArrayList<>();
        org.mockito.stubbing.Answer<Object> readJit = inv -> {
            Object out = inv.callRealMethod();
            seen.add(inv.getMethod().getName() + "="
                    + jdbc.sql("SELECT current_setting('jit')")
                            .query(String.class)
                            .single());
            return out;
        };
        doAnswer(readJit).when(repo).counts(any(), any());
        doAnswer(readJit).when(repo).groups(anyString(), anyString(), any(), anyInt(), anyInt());
        doAnswer(readJit).when(repo).events(anyString(), any(), anyInt(), anyInt());
        doAnswer(readJit).when(repo).personCells(any(), any());
        doAnswer(readJit).when(repo).itemCells(org.mockito.ArgumentMatchers.anyBoolean(), any());

        TestDatabase.Fixture t = TestDatabase.tenant("retail-ctl-jit", false, true);
        RetailTestSupport api = new RetailTestSupport(http, t);
        for (String path : List.of(
                "/summary",
                "/groups?metric=credit_writeoffs&group_by=user",
                "/records?metric=voided_sales",
                "/patterns")) {
            ResponseEntity<JsonNode> r = api.call(HttpMethod.GET, "/controls" + path, null, CONTROLS, "*", Map.of());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        }
        assertThat(seen)
                .containsExactlyInAnyOrder(
                        "counts=off", "counts=off", "groups=off", "events=off", "personCells=off", "itemCells=off");
        // Outside a Controls transaction the setting is the server's own again.
        assertThat(jdbc.sql("SELECT current_setting('jit')").query(String.class).single())
                .isEqualTo("on");
    }
}
