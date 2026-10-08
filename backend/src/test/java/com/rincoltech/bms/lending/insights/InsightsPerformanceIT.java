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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The insights performance measurement (#153, NFR target: the page in under a second on the
 * staging stack with the fabricated seed scaled up 20 times). Opt in, because seeding 20 times the
 * demo takes minutes: {@code mvn verify -Dit.test=InsightsPerformanceIT -Dinsights.perf=true}
 * (optionally {@code -Dinsights.perf.scale=N}). It seeds one tenant with
 * {@code --insights-demo --scale N}, warms each route once, then times five calls of each and writes
 * the medians to {@code target/insights-performance.txt}. The page loads the brief, the portfolio,
 * the revenue and the member routes in parallel, so the page time is bounded by the slowest one.
 */
@EnabledIfSystemProperty(named = "insights.perf", matches = "true")
class InsightsPerformanceIT extends IntegrationTest {

    @Autowired
    LendingSeeder seeder;

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    @Test
    void timesEveryRouteOnTheScaledSeed() throws IOException {
        int scale = Integer.getInteger("insights.perf.scale", 20);
        TestDatabase.Fixture t = TestDatabase.tenant("perf-insights", true);
        long seedStart = System.nanoTime();
        LendingSeeder.Report report = seeder.seed(t.slug(), new LendingSeeder.Options(true, scale));
        long seedMs = (System.nanoTime() - seedStart) / 1_000_000;
        TestDatabase.owner().sql("ANALYZE").update();
        LocalDate today = clock.today(BusinessClock.DEFAULT_ZONE);
        String month = "from=" + today.withDayOfMonth(1) + "&to=" + today;
        String year = "from=" + today.minusDays(364) + "&to=" + today;
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("brief", "/brief");
        routes.put("portfolio (this month)", "/portfolio?" + month);
        routes.put("portfolio (12 months)", "/portfolio?" + year);
        routes.put("revenue (12 months)", "/revenue?" + year);
        routes.put("members (this month)", "/members?" + month);
        routes.put("members (12 months)", "/members?" + year);
        routes.put("table arrears", "/tables/arrears");
        routes.put("table collections (12 months)", "/tables/collections?" + year);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", "lending.insights.read,lending.insights.all_officers,lending.members.read");
        h.add("X-Dev-Branch-Ids", "*");
        List<String> out = new ArrayList<>();
        out.add("scale " + scale + ": " + report.render());
        out.add("seed time " + seedMs + " ms");
        long slowest = 0;
        for (Map.Entry<String, String> route : routes.entrySet()) {
            String url = "/api/v1/lending/insights" + route.getValue();
            ResponseEntity<String> warm = http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertThat(warm.getStatusCode()).as("%s %s", url, warm.getBody()).isEqualTo(HttpStatus.OK);
            long[] times = new long[5];
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
                times[i] = (System.nanoTime() - start) / 1_000_000;
            }
            java.util.Arrays.sort(times);
            slowest = Math.max(slowest, times[2]);
            out.add(route.getKey() + ": median " + times[2] + " ms, max " + times[4] + " ms, "
                    + warm.getBody().length() + " bytes");
        }
        long rows = TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_loan_daily_snapshots WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        out.add("snapshot rows " + rows + "; slowest route median " + slowest + " ms");
        Files.write(Path.of("target/insights-performance.txt"), out);
        out.forEach(System.out::println);
    }
}
