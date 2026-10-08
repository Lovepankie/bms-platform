package com.rincoltech.bms.lending.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.insights.GoldenModel.Loan;
import com.rincoltech.bms.lending.insights.GoldenModel.Pos;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
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
 * Golden tests of the insights metrics (#153, chapter 15 section 15.7): every number the API
 * returns is recomputed by {@link GoldenModel} from the raw rows of a fabricated year (the insights
 * demo seed, plus a reversal with re-allocation made through the staff routes), and must match to
 * the minor unit. The edge cases are all in the data: overpayment credits, write-offs and
 * recoveries, a reversed repayment, partial months at both ends of a range, a past range read from
 * the snapshots, branch and officer filters, and a second tenant that must never show through.
 */
class InsightsGoldenIT extends IntegrationTest {

    static final String BASE = "/api/v1/lending/insights";
    static final String ALL =
            "lending.insights.read,lending.insights.all_officers,lending.insights.export," + "lending.members.read";

    static TestDatabase.Fixture a;
    static TestDatabase.Fixture b;
    static GoldenModel ma;
    static GoldenModel mb;
    static UUID reversedTxn;
    static LocalDate today;

    @Autowired
    LendingSeeder seeder;

    @Autowired
    TestRestTemplate http;

    @Autowired
    BusinessClock clock;

    @BeforeEach
    void once() {
        if (a != null) {
            return;
        }
        today = clock.today(BusinessClock.DEFAULT_ZONE);
        a = TestDatabase.tenant("golden-a", true);
        b = TestDatabase.tenant("golden-b", true);
        seeder.seed(a.slug(), new LendingSeeder.Options(true, 1));
        seeder.seed(b.slug());
        reversedTxn = reverseARepaymentWithALaterOne(a);
        ma = GoldenModel.load(a.tenantId());
        mb = GoldenModel.load(b.tenantId());
    }

    /** Reverses an earlier repayment of an active loan that has a later one, so the later one is re-allocated. */
    UUID reverseARepaymentWithALaterOne(TestDatabase.Fixture t) {
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT r.id, r.loan_id FROM lending_loan_transactions r
                          JOIN lending_loans l ON l.id = r.loan_id AND l.status = 'active'
                         WHERE r.tenant_id = ? AND r.txn_type = 'repayment'
                           AND EXISTS (SELECT 1 FROM lending_loan_transactions n
                                        WHERE n.loan_id = r.loan_id AND n.txn_type = 'repayment' AND n.value_date > r.value_date)
                         ORDER BY r.value_date DESC, r.id LIMIT 1
                        """).param(t.tenantId()).query().singleRow();
        UUID txn = (UUID) row.get("id");
        UUID loan = (UUID) row.get("loan_id");
        HttpHeaders cashier = headers(t, UUID.randomUUID(), permissionsOf("cashier"), "*");
        cashier.add("Idempotency-Key", "golden-" + UUID.randomUUID());
        ResponseEntity<JsonNode> requested = http.exchange(
                "/api/v1/lending/loans/" + loan + "/transactions/" + txn + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Test keying error"), cashier),
                JsonNode.class);
        assertThat(requested.getStatusCode()).as("%s", requested.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> approved = http.exchange(
                "/api/v1/approvals/"
                        + requested.getBody().get("approval_request_id").asString() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("note", "Checked"), headers(t, UUID.randomUUID(), permissionsOf("branch_manager"), "*")),
                JsonNode.class);
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        return txn;
    }

    static String permissionsOf(String role) {
        return TestDatabase.owner()
                .sql("SELECT string_agg(permission_key, ',') FROM role_permissions WHERE role_key = ?")
                .param(role)
                .query(String.class)
                .single();
    }

    static HttpHeaders headers(TestDatabase.Fixture t, UUID user, String permissions, String branches) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branches);
        return h;
    }

    JsonNode get(TestDatabase.Fixture t, String path, HttpHeaders h) {
        ResponseEntity<JsonNode> r = http.exchange(BASE + path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode owner(TestDatabase.Fixture t, String path) {
        return get(t, path, headers(t, UUID.randomUUID(), ALL, "*"));
    }

    static Long metric(JsonNode body, String key) {
        for (JsonNode m : body.get("metrics")) {
            if (m.get("key").asString().equals(key)) {
                return m.get("value").isNull() ? null : m.get("value").asLong();
            }
        }
        throw new AssertionError("no metric " + key);
    }

    static Long bpOrNull(long part, long whole) {
        return whole == 0 ? null : GoldenModel.bp(part, whole);
    }

    /** Compares every portfolio metric with the model for the range and the loan filter. */
    void portfolioMatches(JsonNode p, GoldenModel m, LocalDate from, LocalDate to, Predicate<Loan> f, List<Pos> stock) {
        long po = GoldenModel.po(stock);
        assertThat(metric(p, "portfolio.principal_outstanding")).isEqualTo(po);
        assertThat(metric(p, "portfolio.interest_receivable"))
                .isEqualTo(stock.stream().mapToLong(Pos::io).sum());
        assertThat(metric(p, "portfolio.active_loans")).isEqualTo((long) stock.size());
        assertThat(metric(p, "portfolio.arrears"))
                .isEqualTo(stock.stream().mapToLong(Pos::arrears).sum());
        assertThat(metric(p, "portfolio.par1")).isEqualTo(bpOrNull(GoldenModel.parAmount(stock, 0), po));
        assertThat(metric(p, "portfolio.par30")).isEqualTo(bpOrNull(GoldenModel.parAmount(stock, 30), po));
        assertThat(metric(p, "portfolio.par60")).isEqualTo(bpOrNull(GoldenModel.parAmount(stock, 60), po));
        assertThat(metric(p, "portfolio.par90")).isEqualTo(bpOrNull(GoldenModel.parAmount(stock, 90), po));
        long disbursed = m.sumTxns(Set.of("disbursement"), from, to, f, false);
        assertThat(metric(p, "portfolio.disbursed")).isEqualTo(disbursed);
        assertThat(metric(p, "portfolio.disbursed_count")).isEqualTo(m.countTxns("disbursement", from, to, f));
        assertThat(metric(p, "portfolio.collected"))
                .isEqualTo(m.sumTxns(Set.of("repayment", "recovery"), from, to, f, true));
        long expected = m.expected(from, to, f);
        long onDue = m.collectedOnDue(from, to, f);
        assertThat(metric(p, "portfolio.expected")).isEqualTo(expected);
        assertThat(metric(p, "portfolio.collected_on_due")).isEqualTo(onDue);
        assertThat(metric(p, "portfolio.collection_rate")).isEqualTo(bpOrNull(onDue, expected));
        assertThat(metric(p, "portfolio.written_off")).isEqualTo(m.sumTxns(Set.of("write_off"), from, to, f, false));
        assertThat(metric(p, "portfolio.written_off_count")).isEqualTo(m.countTxns("write_off", from, to, f));
        assertThat(metric(p, "portfolio.recovered")).isEqualTo(m.sumTxns(Set.of("recovery"), from, to, f, true));
        assertThat(metric(p, "portfolio.forecast_7")).isEqualTo(m.forecast(today, 7, f));
        assertThat(metric(p, "portfolio.forecast_30")).isEqualTo(m.forecast(today, 30, f));
        long[] repeat = m.repeat(from, to, f);
        assertThat(metric(p, "portfolio.repeat_borrowers")).isEqualTo(repeat[1]);
        assertThat(metric(p, "portfolio.repeat_share")).isEqualTo(bpOrNull(repeat[1], repeat[0]));
        // The series and the ageing add up to the totals.
        long seriesDisbursed = 0;
        long seriesCollected = 0;
        long seriesExpected = 0;
        for (JsonNode s : p.get("series")) {
            seriesDisbursed += s.get("disbursed_minor").asLong();
            seriesCollected += s.get("collected_minor").asLong();
            seriesExpected += s.get("expected_minor").asLong();
        }
        assertThat(seriesDisbursed).isEqualTo(disbursed);
        assertThat(seriesCollected).isEqualTo(metric(p, "portfolio.collected"));
        assertThat(seriesExpected).isEqualTo(expected);
        long ageing = 0;
        for (JsonNode bucket : p.get("ageing")) {
            ageing += bucket.get("principal_minor").asLong();
        }
        assertThat(ageing).isEqualTo(po);
    }

    @Test
    void portfolioOverARangeWithPartialMonthsMatchesTheRawRows() {
        LocalDate from = today.minusDays(89);
        JsonNode p = owner(a, "/portfolio?from=" + from + "&to=" + today);
        assertThat(p.get("stock_source").asString()).isEqualTo("live");
        portfolioMatches(p, ma, from, today, l -> true, ma.live(today, l -> true));
        // The data holds every edge case the definitions name.
        assertThat(ma.reversed).contains(reversedTxn);
        assertThat(ma.allocs.stream().anyMatch(x -> x.component().equals("overpayment")))
                .isTrue();
        assertThat(ma.txns.values().stream().anyMatch(t -> t.type().equals("write_off")))
                .isTrue();
        assertThat(metric(p, "portfolio.par1")).isPositive();
    }

    @Test
    void aPastMonthReadsTheSnapshotAndMatchesTheReplayedAllocations() {
        LocalDate monthEnd = today.withDayOfMonth(1).minusDays(1);
        LocalDate from = monthEnd.withDayOfMonth(1);
        JsonNode p = owner(a, "/portfolio?from=" + from + "&to=" + monthEnd + "&grain=week");
        assertThat(p.get("stock_source").asString()).isEqualTo("snapshot");
        assertThat(p.get("grain").asString()).isEqualTo("week");
        portfolioMatches(
                p, ma, from, monthEnd, l -> true, List.copyOf(ma.asOf(monthEnd).values()));
    }

    @Test
    void theSnapshotsEqualTheModelOnPastDates() {
        for (LocalDate d : List.of(today.minusDays(1), today.minusDays(45), today.minusDays(200))) {
            Map<UUID, Pos> expected = ma.asOf(d);
            List<Map<String, Object>> rows = TestDatabase.owner()
                    .sql("""
                            SELECT loan_id, principal_outstanding_minor, interest_outstanding_minor, arrears_minor,
                                   days_past_due
                              FROM lending_loan_daily_snapshots WHERE tenant_id = ? AND business_date = ?
                            """)
                    .params(a.tenantId(), java.sql.Date.valueOf(d))
                    .query()
                    .listOfRows();
            assertThat(rows).as("rows on %s", d).hasSize(expected.size());
            for (Map<String, Object> r : rows) {
                Pos e = expected.get((UUID) r.get("loan_id"));
                assertThat(e).isNotNull();
                assertThat(((Number) r.get("principal_outstanding_minor")).longValue())
                        .isEqualTo(e.po());
                assertThat(((Number) r.get("interest_outstanding_minor")).longValue())
                        .isEqualTo(e.io());
                assertThat(((Number) r.get("arrears_minor")).longValue()).isEqualTo(e.arrears());
                assertThat(((Number) r.get("days_past_due")).intValue()).isEqualTo(e.dpd());
            }
        }
        // Today's replay equals the live reading, loan by loan.
        Map<UUID, Pos> replayed = ma.asOf(today);
        for (Pos live : ma.live(today, l -> true)) {
            assertThat(replayed.get(live.loan())).isEqualTo(live);
        }
    }

    @Test
    void aBranchFilterKeepsOnlyThatBranch() {
        UUID north = TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? AND code = 'FABN'")
                .param(a.tenantId())
                .query(UUID.class)
                .single();
        LocalDate from = today.minusDays(59);
        JsonNode p = owner(a, "/portfolio?from=" + from + "&to=" + today + "&branch_id=" + north);
        Predicate<Loan> f = l -> l.branch().equals(north);
        portfolioMatches(p, ma, from, today, f, ma.live(today, f));
        // A user scoped to the head office cannot ask for the north branch.
        JsonNode scoped = get(
                a,
                "/portfolio?from=" + from + "&to=" + today + "&branch_id=" + north,
                headers(a, UUID.randomUUID(), ALL, a.headOffice().toString()));
        assertThat(metric(scoped, "portfolio.principal_outstanding")).isZero();
        assertThat(metric(scoped, "portfolio.disbursed")).isZero();
    }

    @Test
    void anOfficerWithoutAllOfficersSeesOnlyTheirOwnLoans() {
        UUID officer = ma.loans.values().stream()
                .filter(l -> l.status().equals("active"))
                .map(Loan::officer)
                .findFirst()
                .orElseThrow();
        UUID other = ma.loans.values().stream()
                .map(Loan::officer)
                .filter(o -> !o.equals(officer))
                .findFirst()
                .orElseThrow();
        LocalDate from = today.minusDays(29);
        JsonNode p = get(
                a,
                "/portfolio?from=" + from + "&to=" + today + "&officer_id=" + other,
                headers(a, officer, permissionsOf("loan_officer"), "*"));
        Predicate<Loan> f = l -> l.officer().equals(officer);
        portfolioMatches(p, ma, from, today, f, ma.live(today, f));
    }

    @Test
    void revenueComesFromThePostedLinesByProductAndMonth() {
        LocalDate from = today.minusDays(120);
        JsonNode r = owner(a, "/revenue?from=" + from + "&to=" + today);
        long[] c = ma.revenue(from, today, l -> true);
        assertThat(metric(r, "revenue.interest")).isEqualTo(c[0]);
        assertThat(metric(r, "revenue.fees")).isEqualTo(c[1]);
        assertThat(metric(r, "revenue.penalties")).isEqualTo(c[2]);
        assertThat(metric(r, "revenue.recovered")).isEqualTo(c[3]);
        assertThat(metric(r, "revenue.write_off_expense")).isEqualTo(c[4]);
        assertThat(metric(r, "revenue.contribution")).isEqualTo(c[5]);
        assertThat(c[0]).isPositive();
        long byProduct = 0;
        for (JsonNode row : r.get("by_product")) {
            UUID product = UUID.fromString(row.get("key").asString());
            long[] pc = ma.revenue(from, today, l -> l.product().equals(product));
            assertThat(row.get("contribution_minor").asLong()).isEqualTo(pc[5]);
            assertThat(row.get("interest_minor").asLong()).isEqualTo(pc[0]);
            byProduct += row.get("contribution_minor").asLong();
        }
        assertThat(byProduct).isEqualTo(c[5]);
        JsonNode months = r.get("months");
        assertThat(months.size()).isEqualTo(12);
        for (JsonNode month : months) {
            LocalDate start = LocalDate.parse(month.get("month").asString());
            LocalDate end = start.with(TemporalAdjusters.lastDayOfMonth());
            long[] mc = ma.revenue(start, end.isAfter(today) ? today : end, l -> true);
            assertThat(month.get("interest_minor").asLong()).as("%s", start).isEqualTo(mc[0]);
            assertThat(month.get("contribution_minor").asLong()).as("%s", start).isEqualTo(mc[5]);
        }
    }

    @Test
    void theBriefCountsTodaysMovements() {
        JsonNode brief = owner(a, "/brief");
        List<Pos> live = ma.live(today, l -> true);
        assertThat(metric(brief, "brief.disbursed_today"))
                .isEqualTo(ma.sumTxns(Set.of("disbursement"), today, today, l -> true, false));
        assertThat(metric(brief, "brief.collected_today"))
                .isEqualTo(ma.sumTxns(Set.of("repayment", "recovery"), today, today, l -> true, true));
        assertThat(metric(brief, "brief.expected_today")).isEqualTo(ma.expected(today, today, l -> true));
        assertThat(metric(brief, "brief.new_arrears"))
                .isEqualTo(live.stream().filter(p -> p.dpd() == 1).count());
        assertThat(metric(brief, "brief.going_bad"))
                .isEqualTo(live.stream()
                        .filter(p -> p.dpd() >= 24 && p.dpd() <= 30)
                        .count());
        assertThat(brief.get("sentences").size()).isEqualTo(5);
        for (JsonNode s : brief.get("sentences")) {
            assertThat(s.asString()).endsWith(".").doesNotContain("{").doesNotContainPattern("[\\u2013\\u2014]");
        }
    }

    @Test
    void anotherTenantNeverShowsThrough() {
        LocalDate from = today.minusDays(120);
        JsonNode pb = owner(b, "/portfolio?from=" + from + "&to=" + today);
        portfolioMatches(pb, mb, from, today, l -> true, mb.live(today, l -> true));
        JsonNode pa = owner(a, "/portfolio?from=" + from + "&to=" + today);
        assertThat(metric(pb, "portfolio.disbursed")).isNotEqualTo(metric(pa, "portfolio.disbursed"));
        JsonNode table = owner(b, "/tables/disbursements?from=" + from + "&to=" + today);
        assertThat(table.get("rows").size()).isEqualTo((int) mb.countTxns("disbursement", from, today, l -> true));
        JsonNode revenue = owner(b, "/revenue?from=" + from + "&to=" + today);
        assertThat(metric(revenue, "revenue.interest")).isEqualTo(mb.revenue(from, today, l -> true)[0]);
    }

    @Test
    void drillDownRowsAddUpToTheirNumber() {
        LocalDate from = today.minusDays(30);
        JsonNode p = owner(a, "/portfolio?from=" + from + "&to=" + today);
        JsonNode collections = owner(a, "/tables/collections?from=" + from + "&to=" + today);
        long sum = 0;
        int amount = column(collections, "amount_minor");
        for (JsonNode row : collections.get("rows")) {
            sum += row.get(amount).asLong();
        }
        assertThat(sum).isEqualTo(metric(p, "portfolio.collected"));
        JsonNode arrears = owner(a, "/tables/arrears?min_dpd=31");
        long atRisk = 0;
        int po = column(arrears, "principal_outstanding_minor");
        for (JsonNode row : arrears.get("rows")) {
            atRisk += row.get(po).asLong();
        }
        assertThat(atRisk).isEqualTo(GoldenModel.parAmount(ma.live(today, l -> true), 30));
    }

    static int column(JsonNode table, String key) {
        JsonNode columns = table.get("columns");
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).get("key").asString().equals(key)) {
                return i;
            }
        }
        throw new AssertionError("no column " + key);
    }

    @Test
    void anExportMasksNamesWithoutMemberAccessAndIsAudited() {
        HttpHeaders noMembers = headers(
                a,
                UUID.randomUUID(),
                "lending.insights.read,lending.insights.all_officers,lending.insights.export",
                "*");
        ResponseEntity<String> csv = http.exchange(
                BASE + "/tables/arrears/export", HttpMethod.GET, new HttpEntity<>(noMembers), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getBody()).doesNotContain("Demo Borrower").contains("D. B. ");
        assertThat(csv.getBody()).doesNotContain("+256");
        ResponseEntity<String> full = http.exchange(
                BASE + "/tables/arrears/export",
                HttpMethod.GET,
                new HttpEntity<>(headers(a, UUID.randomUUID(), ALL, "*")),
                String.class);
        assertThat(full.getBody()).contains("Demo Borrower");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lending.insights.exported'")
                        .param(a.tenantId())
                        .query(Long.class)
                        .single())
                .isGreaterThanOrEqualTo(2);
        ResponseEntity<String> refused = http.exchange(
                BASE + "/tables/arrears/export",
                HttpMethod.GET,
                new HttpEntity<>(headers(a, UUID.randomUUID(), "lending.insights.read", "*")),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void membersFunnelAndPanels() {
        LocalDate from = today.minusDays(89);
        JsonNode m = owner(a, "/members?from=" + from + "&to=" + today);
        long applied = metric(m, "members.applied");
        long approved = metric(m, "members.approved");
        long disbursed = metric(m, "members.disbursed");
        assertThat(applied).isGreaterThanOrEqualTo(approved);
        assertThat(approved).isGreaterThanOrEqualTo(disbursed);
        long expectedApplied = TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM lending_loans WHERE tenant_id = ?
                           AND (submitted_at AT TIME ZONE 'Africa/Kampala')::date BETWEEN ? AND ?
                        """)
                .params(a.tenantId(), java.sql.Date.valueOf(from), java.sql.Date.valueOf(today))
                .query(Long.class)
                .single();
        assertThat(applied).isEqualTo(expectedApplied);
        assertThat(metric(m, "members.median_decision_hours")).isNotNull();
        JsonNode panels = owner(a, "/panels");
        // The seed holds savings accounts and investments, so both panels show (FR-INS-09).
        java.util.List<String> keys = new java.util.ArrayList<>();
        panels.get("panels").forEach(p -> keys.add(p.get("key").asString()));
        assertThat(keys).containsExactly("savings", "investments");
    }
}
