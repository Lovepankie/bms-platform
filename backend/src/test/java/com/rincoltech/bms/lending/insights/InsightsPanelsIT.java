package com.rincoltech.bms.lending.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * FR-INS-09: the savings and investments panels. A tenant with savings accounts and investments
 * (the fabricated seed) gets both panels with exactly the keys of the metrics dictionary and values
 * from the modules' own rows; a tenant with neither gets none; an officer-narrowed request gets none.
 */
class InsightsPanelsIT extends IntegrationTest {

    @Autowired
    LendingSeeder seeder;

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    @Test
    void savingsAndInvestmentsPanelsShowOnlyForATenantWithTheirData() throws IOException {
        TestDatabase.Fixture empty = TestDatabase.tenant("insights-panels-empty", true);
        assertThat(panels(empty, true).get("panels").size()).isZero();

        TestDatabase.Fixture t = TestDatabase.tenant("insights-panels", true);
        seeder.seed(t.slug());

        JsonNode body = panels(t, true);
        assertThat(body.get("panels").size()).isEqualTo(2);
        Map<String, Long> values = new HashMap<>();
        Set<String> keys = new TreeSet<>();
        for (JsonNode panel : body.get("panels")) {
            for (JsonNode m : panel.get("metrics")) {
                assertThat(m.get("key").asString()).startsWith(panel.get("key").asString() + ".");
                keys.add(m.get("key").asString());
                values.put(m.get("key").asString(), m.get("value").asLong());
            }
        }
        assertThat(body.get("panels").get(0).get("key").asString()).isEqualTo("savings");
        assertThat(body.get("panels").get(1).get("key").asString()).isEqualTo("investments");
        assertThat(keys).containsExactlyInAnyOrderElementsOf(documentedPanelKeys());

        assertThat(values.get("savings.balances"))
                .isPositive()
                .isEqualTo(sum(
                        t, "SELECT coalesce(sum(balance_minor), 0) FROM lending_savings_accounts WHERE tenant_id = ?"));
        assertThat(values.get("investments.open_count")).isPositive().isEqualTo(sum(t, """
                        SELECT count(*) FROM lending_investments
                         WHERE tenant_id = ? AND status IN ('active', 'matured') AND principal_held_minor > 0
                        """));
        assertThat(values.get("investments.balance")).isEqualTo(sum(t, """
                        SELECT coalesce(sum(principal_held_minor), 0) FROM lending_investments
                         WHERE tenant_id = ? AND status IN ('active', 'matured')
                        """));

        // Neither module records a responsible officer: a one-officer request shows no panel.
        assertThat(panels(t, false).get("panels").size()).isZero();
    }

    private JsonNode panels(TestDatabase.Fixture t, boolean allOfficers) {
        LocalDate today = clock.today(BusinessClock.DEFAULT_ZONE);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add(
                "X-Dev-Permissions",
                allOfficers ? "lending.insights.read,lending.insights.all_officers" : "lending.insights.read");
        h.add("X-Dev-Branch-Ids", "*");
        ResponseEntity<JsonNode> res = http.exchange(
                "/api/v1/lending/insights/panels?from=" + today.minusDays(364) + "&to=" + today,
                HttpMethod.GET,
                new HttpEntity<>(h),
                JsonNode.class);
        assertThat(res.getStatusCode()).as("%s", res.getBody()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private static long sum(TestDatabase.Fixture t, String sql) {
        return TestDatabase.owner()
                .sql(sql)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    private static Set<String> documentedPanelKeys() throws IOException {
        String doc = Files.readString(Path.of("../docs/specs/lending-insights-metrics.md"));
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("`((?:savings|investments)\\.[a-z0-9_]+)`").matcher(doc);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
