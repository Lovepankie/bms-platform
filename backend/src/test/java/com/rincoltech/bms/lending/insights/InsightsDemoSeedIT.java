package com.rincoltech.bms.lending.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
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
 * The insights demo history (#153): {@code seed-lending --insights-demo} writes a year of
 * fabricated loans through the servicing port with balanced books, a snapshot for every past day,
 * and an insights page with twelve months of trend.
 */
class InsightsDemoSeedIT extends IntegrationTest {

    @Autowired
    LendingSeeder seeder;

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    static long count(UUID tenant, String sql) {
        return TestDatabase.owner().sql(sql).param(tenant).query(Long.class).single();
    }

    @Test
    void writesAYearOfHistoryWithBalancedBooksAndSnapshots() {
        TestDatabase.Fixture t = TestDatabase.tenant("demo-insights", true);
        LocalDate today = clock.today(BusinessClock.DEFAULT_ZONE);

        LendingSeeder.Report report = seeder.seed(t.slug(), new LendingSeeder.Options(true, 1));

        assertThat(report.demo()).isNotNull();
        assertThat(report.demo().disbursed()).isGreaterThan(100);
        assertThat(report.demo().repayments()).isGreaterThan(300);
        assertThat(report.demo().writeOffs()).isPositive();
        assertThat(count(t.tenantId(), "SELECT count(*) FROM branches WHERE tenant_id = ?"))
                .isGreaterThanOrEqualTo(3);
        // Books: the trial balance balances and loans receivable equals each loan's principal outstanding.
        assertThat(count(t.tenantId(), "SELECT sum(debit) - sum(credit) FROM journal_lines WHERE tenant_id = ?"))
                .isZero();
        assertThat(count(t.tenantId(), """
                        SELECT count(*) FROM lending_loans l WHERE l.tenant_id = ? AND l.principal_outstanding_minor <>
                            (SELECT coalesce(sum(j.debit - j.credit), 0) FROM journal_lines j
                               JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'loans_receivable'
                              WHERE j.subledger_id = l.id)
                        """)).isZero();
        // Disbursements spread over the year, not bunched on one day.
        assertThat(count(t.tenantId(), """
                        SELECT count(DISTINCT date_trunc('month', value_date)) FROM lending_loan_transactions
                         WHERE tenant_id = ? AND txn_type = 'disbursement'
                        """)).isGreaterThanOrEqualTo(12);
        // A snapshot for every past day since the year began, the last one yesterday.
        assertThat(TestDatabase.owner()
                        .sql("SELECT max(business_date) FROM lending_loan_daily_snapshots WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Date.class)
                        .single()
                        .toLocalDate())
                .isEqualTo(today.minusDays(1));
        assertThat(count(t.tenantId(), "SELECT count(*) FROM users WHERE tenant_id = ? AND status <> 'deactivated'"))
                .isZero();

        ResponseEntity<JsonNode> portfolio = http.exchange(
                "/api/v1/lending/insights/portfolio?from=" + today.minusDays(364) + "&to=" + today,
                HttpMethod.GET,
                new HttpEntity<>(headers(t)),
                JsonNode.class);
        assertThat(portfolio.getStatusCode()).as("%s", portfolio.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode trend = portfolio.getBody().get("trend");
        assertThat(trend.size()).isEqualTo(12);
        for (int i = 0; i < 11; i++) {
            assertThat(trend.get(i).get("source").asString()).isEqualTo("snapshot");
        }
        assertThat(trend.get(11).get("source").asString()).isEqualTo("live");
        assertThat(portfolio.getBody().get("series").size()).isEqualTo(13);
    }

    static HttpHeaders headers(TestDatabase.Fixture t) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", "lending.insights.read,lending.insights.all_officers");
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }
}
