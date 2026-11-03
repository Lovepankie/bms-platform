package com.rincoltech.bms.retail.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Issue #807 (ADR-119 decision 3), review of #809: the every-shop quantities of My stock cost one read of the stock balances
 * per page, whatever the number of rows or shops, and none on an empty page. Figures fabricated.
 */
class RetailAllShopsStockReadsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @MockitoSpyBean
    StockRepository repo;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("all-shops-reads", false, true);
        api = new RetailTestSupport(http, t);
        for (int i = 1; i <= 3; i++) {
            UUID product = api.product("ASR-" + i, 400, 700);
            api.stockUp(t.secondBranch(), product, String.valueOf(i));
        }
        clearInvocations(repo);
    }

    JsonNode read(String query) {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/stock?branch_id=" + t.headOffice() + query,
                null,
                RetailTestSupport.SALES,
                t.headOffice().toString(),
                Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void anEmptyPageReadsNoBalances() {
        assertThat(read("&query=NOTHING-MATCHES-THIS").get("items")).isEmpty();
        verify(repo, times(0)).balances(any(), any());
    }

    @Test
    void onePageOfManyRowsReadsTheBalancesOnce() {
        JsonNode page = read("&query=ASR");
        assertThat(page.get("items")).hasSize(3);
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("shops")).hasSize(2);
        }
        verify(repo, times(1)).balances(any(), any());
    }

    @Test
    void eachPageReadsTheBalancesOnce() {
        JsonNode first = read("&query=ASR&limit=2");
        assertThat(first.get("items")).hasSize(2);
        read("&query=ASR&limit=2&cursor=" + first.get("next_cursor").asString());
        verify(repo, times(2)).balances(any(), any());
    }
}
