package com.rincoltech.bms.lending.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Column;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.PeriodPoint;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.TableResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.DayTotal;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.DuePaid;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.LedgerAmount;
import com.rincoltech.bms.lending.insights.internal.Positions.Position;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure rules of the insights page on hand-computed fabricated numbers (golden values written
 * out by hand, chapter 15 section 15.7): rounding, periods with partial months, the PAR worked
 * example of chapter 14, the series, the yields, the revenue components, the sentences, the CSV.
 */
class InsightsRulesTest {

    static final LocalDate D = LocalDate.of(2026, 3, 15);

    @Test
    void basisPointsRoundHalfUpAndAreEmptyOverNothing() {
        assertThat(Metrics.bp(1, 3)).isEqualTo(3333);
        assertThat(Metrics.bp(2, 3)).isEqualTo(6667);
        assertThat(Metrics.bp(1, 8)).isEqualTo(1250);
        assertThat(Metrics.bp(1, 20_000)).isEqualTo(1);
        assertThat(Metrics.bp(1, 20_001)).isZero();
        assertThat(Metrics.bp(5, 0)).isNull();
        assertThat(InsightsService.divideHalfUp(5, 2)).isEqualTo(3);
        assertThat(InsightsService.divideHalfUp(7, 3)).isEqualTo(2);
    }

    @Test
    void periodsCutPartialMonthsAtTheRangeEnds() {
        List<Periods.Period> months = Periods.split(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 10), "month");
        assertThat(months)
                .extracting(Periods.Period::start)
                .containsExactly(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
        assertThat(months)
                .extracting(Periods.Period::end)
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 10));
        assertThat(months.getFirst().label()).isEqualTo("Jan 2026");
        List<Periods.Period> weeks = Periods.split(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 16), "week");
        assertThat(weeks).hasSize(3);
        assertThat(weeks.getFirst().end()).isEqualTo(LocalDate.of(2026, 3, 8)); // a Sunday
        assertThat(Periods.indexOf(weeks, LocalDate.of(2026, 3, 9))).isEqualTo(1);
        assertThat(Periods.indexOf(weeks, LocalDate.of(2026, 3, 17))).isEqualTo(-1);
        assertThat(Periods.defaultGrain(D, D.plusDays(30))).isEqualTo("day");
        assertThat(Periods.defaultGrain(D, D.plusDays(90))).isEqualTo("week");
        assertThat(Periods.defaultGrain(D, D.plusDays(365))).isEqualTo("month");
        assertThatThrownBy(() -> Periods.split(D, D.plusDays(200), "day")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Periods.split(D, D, "fortnight")).isInstanceOf(ApiException.class);
        assertThat(Periods.monthEnds(LocalDate.of(2026, 10, 6)))
                .hasSize(12)
                .startsWith(LocalDate.of(2025, 11, 30))
                .endsWith(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 6));
    }

    static Position loan(long po, int dpd) {
        return new Position(
                UUID.randomUUID(),
                "LN",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                po,
                0,
                0,
                dpd);
    }

    /** Chapter 14 section 14.3, the PAR worked example: 10,000,000 outstanding. */
    @Test
    void parWorkedExampleOfChapter14() {
        List<Position> book = List.of(loan(8_200_000, 0), loan(1_000_000, 12), loan(500_000, 45), loan(300_000, 120));
        long po = 10_000_000;
        assertThat(Metrics.bp(
                        book.stream()
                                .filter(p -> p.daysPastDue() > 0)
                                .mapToLong(Position::principalOutstandingMinor)
                                .sum(),
                        po))
                .isEqualTo(1800);
        assertThat(Metrics.bp(
                        book.stream()
                                .filter(p -> p.daysPastDue() > 30)
                                .mapToLong(Position::principalOutstandingMinor)
                                .sum(),
                        po))
                .isEqualTo(800);
        assertThat(Metrics.bp(
                        book.stream()
                                .filter(p -> p.daysPastDue() > 90)
                                .mapToLong(Position::principalOutstandingMinor)
                                .sum(),
                        po))
                .isEqualTo(300);
        var ageing = InsightsService.ageing(book, po);
        assertThat(ageing)
                .extracting(InsightsApi.Bucket::key)
                .containsExactly("current", "1_30", "31_60", "61_90", "over_90");
        assertThat(ageing)
                .extracting(InsightsApi.Bucket::principalMinor)
                .containsExactly(8_200_000L, 1_000_000L, 500_000L, 0L, 300_000L);
        assertThat(ageing.get(1).shareBp()).isEqualTo(1000);
        assertThat(Positions.bucketOf(30)).isEqualTo("1_30");
        assertThat(Positions.bucketOf(31)).isEqualTo("31_60");
        assertThat(Positions.bucketOf(91)).isEqualTo("over_90");
    }

    @Test
    void aPeriodsCollectionRateCountsOnlyWhatWasPaidByItsEnd() {
        List<Periods.Period> months = Periods.split(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), "month");
        LocalDate jan10 = LocalDate.of(2026, 1, 10);
        List<PeriodPoint> s = InsightsService.series(
                months,
                List.of(new DayTotal(jan10, 1_000_000, 2)),
                List.of(new DayTotal(LocalDate.of(2026, 2, 3), 150_000, 1)),
                List.of(new DayTotal(jan10, 200_000, 1), new DayTotal(LocalDate.of(2026, 2, 10), 100_000, 1)),
                List.of(
                        new DuePaid(jan10, jan10, 120_000),
                        // Paid in February for a January item: late, not counted for January.
                        new DuePaid(jan10, LocalDate.of(2026, 2, 3), 80_000),
                        new DuePaid(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 9), 100_000)));
        assertThat(s.get(0).collectedOnDueMinor()).isEqualTo(120_000);
        assertThat(s.get(0).collectionRateBp()).isEqualTo(6000);
        assertThat(s.get(0).disbursedCount()).isEqualTo(2);
        assertThat(s.get(1).collectedMinor()).isEqualTo(150_000);
        assertThat(s.get(1).collectionRateBp()).isEqualTo(10_000);
    }

    @Test
    void yieldsAreAnnualisedOverTheAverageDailyBalance() {
        Scope thirtyDays = new Scope(D, D.plusDays(29), D.plusDays(29), null, null, null, "UGX");
        // 30 days averaging 10,000,000 outstanding and 300,000 earned: 365/30 * 3 percent = 36.5 percent.
        InsightsService.AverageBalance avg = new InsightsService.AverageBalance(BigInteger.valueOf(300_000_000L), 30);
        assertThat(InsightsService.annualisedBp(300_000, avg, thirtyDays)).isEqualTo(3650);
        assertThat(InsightsService.annualisedBp(
                        300_000, new InsightsService.AverageBalance(BigInteger.ZERO, 0), thirtyDays))
                .isNull();
    }

    @Test
    void revenueComponentsNetReversalsAndSubtractWriteOffs() {
        UUID p = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LocalDate m = LocalDate.of(2026, 3, 1);
        long[] c = InsightsService.components(List.of(
                new LedgerAmount(m, "loan_interest_income", p, b, 90_000),
                new LedgerAmount(m, "loan_interest_income", p, b, -10_000),
                new LedgerAmount(m, "loan_fee_income", p, b, 20_000),
                new LedgerAmount(m, "bad_debt_recovered", p, b, 5_000),
                new LedgerAmount(m, "loan_write_off_expense", p, b, -50_000)));
        assertThat(c).containsExactly(80_000, 20_000, 0, 5_000, 50_000, 55_000);
    }

    @Test
    void sentencesArePlainAndFormatMoneyWithoutFloatingPoint() {
        assertThat(Sentences.money(1_250_000, "UGX")).isEqualTo("UGX 1,250,000");
        assertThat(Sentences.money(125_050, "KES")).isEqualTo("KES 1,250.50");
        assertThat(Sentences.money(5, "USD")).isEqualTo("USD 0.05");
        assertThat(Sentences.percent(1250)).isEqualTo("12.5 percent");
        assertThat(Sentences.percent(1255)).isEqualTo("12.6 percent");
        assertThat(Sentences.disbursed(1_200_000, 3, 800_000, "UGX"))
                .isEqualTo(
                        "UGX 1,200,000 went out today on 3 loans, up 50.0 percent on the same day last week (UGX 800,000).");
        assertThat(Sentences.disbursed(0, 0, 0, "UGX")).isEqualTo("No loans were disbursed today.");
        assertThat(Sentences.collected(400_000, 500_000, "UGX"))
                .isEqualTo("UGX 400,000 came in today, down 20.0 percent on the same day last week (UGX 500,000).");
        assertThat(Sentences.dueToday(500_000, 350_000, 7000L, "UGX"))
                .isEqualTo("UGX 350,000 of the UGX 500,000 due today has been paid (70.0 percent).");
        assertThat(Sentences.newArrears(1, 45_000, "UGX"))
                .isEqualTo("1 loan fell into arrears today, owing UGX 45,000 overdue.");
        assertThat(Sentences.goingBad(2, 900_000, "UGX"))
                .isEqualTo("2 loans holding UGX 900,000 will pass 30 days late within 7 days unless paid.");
    }

    @Test
    void csvEscapesFormulasAndMasksNames() {
        TableResponse t = new TableResponse(
                "arrears",
                "Loans in arrears",
                "UGX",
                List.of(
                        new Column("loan_no", "Loan", "text"),
                        new Column("member_name", "Name", "text"),
                        new Column("arrears_minor", "Arrears", "money")),
                List.of(List.of("=HYPERLINK(1)", "Test Borrower 01", "-5000"), List.of("LN1", "A, B", "100")),
                false);
        String masked = Csv.render(t, false);
        assertThat(masked).startsWith("Loan,Name,Arrears (UGX minor units)\r\n");
        assertThat(masked).contains("'=HYPERLINK(1),T. B. 0.,-5000\r\n");
        assertThat(masked).contains("LN1,A. B.,100\r\n");
        assertThat(Csv.render(t, true)).contains("\"A, B\"").contains("Test Borrower 01");
    }

    /** {@code docs/specs/lending-insights-metrics.md} lists every metric key the code defines, and no other. */
    @Test
    void theMetricsDictionaryMatchesTheCode() throws IOException {
        String doc = Files.readString(Path.of("../docs/specs/lending-insights-metrics.md"));
        java.util.Set<String> documented = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "`((?:brief|portfolio|revenue|members)\\.[a-z0-9_]+)`")
                .matcher(doc);
        while (m.find()) {
            documented.add(m.group(1));
        }
        assertThat(documented).containsExactlyInAnyOrderElementsOf(Metrics.ALL.keySet());
        for (String table : InsightsTables.KEYS) {
            assertThat(doc).as("table %s", table).contains("`" + table + "`");
        }
    }
}
