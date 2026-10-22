package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.payments.internal.StatementCsv.Row;
import com.rincoltech.bms.kernel.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The statement reader (spec 3.9, FR-PAY-11): the usual column names, quoted cells, a byte order mark, either line ending, both
 * date styles, amounts with separators, and a file refused whole for anything it cannot read, each problem naming the physical
 * line it is on. Every identifier and code is invented.
 */
class StatementCsvTest {

    static final String TRACKING = "11111111-2222-4333-8444-555555555555";
    static final String OTHER = "99999999-2222-4333-8444-555555555555";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static List<Row> parse(String text) {
        return StatementCsv.parse(bytes(text), "UGX", TODAY);
    }

    /** The problems a refused file carries, as text. */
    static String refused(String text) {
        return refused(bytes(text));
    }

    static String refused(byte[] data) {
        try {
            StatementCsv.parse(data, "UGX", TODAY);
        } catch (ApiException e) {
            return e.errors().toString();
        }
        throw new AssertionError("the file was read but should have been refused");
    }

    @Test
    void readsTheUsualColumnNamesInAnyOrderAndIgnoresTheRest() {
        List<Row> rows = parse("Payer Account,Net,Date,Fees,Gross Amount,Tracking ID,Payment Code\n"
                + "0700***000,4800,2026-10-16,200,5000," + TRACKING + ",CONF-TEST-1\n");
        assertThat(rows).hasSize(1);
        Row r = rows.getFirst();
        assertThat(r.rowNo()).isEqualTo(1);
        assertThat(r.line()).isEqualTo(2);
        assertThat(r.orderTrackingId()).isEqualTo(TRACKING);
        assertThat(r.confirmationCode()).isEqualTo("CONF-TEST-1");
        assertThat(r.amountMinor()).isEqualTo(5000);
        assertThat(r.feeMinor()).isEqualTo(200);
        assertThat(r.settledMinor()).isEqualTo(4800);
        assertThat(r.currency()).isEqualTo("UGX");
        assertThat(r.paidOn()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void readsQuotedCellsBomCrlfSeparatorsAndBothDateStyles() {
        List<Row> rows = parse("﻿confirmation_code,amount,date,fee\r\n"
                + "\"CONF,A\",\"5,200.00\",19/10/2026,\"1,000\"\r\n"
                + "\r\n"
                + "CONF-B,300,2026-10-17T08:30:00Z,0\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).confirmationCode()).isEqualTo("CONF,A");
        assertThat(rows.get(0).amountMinor()).isEqualTo(5200);
        assertThat(rows.get(0).feeMinor()).isEqualTo(1000);
        assertThat(rows.get(0).paidOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(rows.get(1).paidOn()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(rows.get(1).orderTrackingId()).isNull();
        assertThat(rows.get(1).feeMinor()).isZero();
        assertThat(rows.get(1).rowNo()).isEqualTo(2);
        // The blank line counts: the second data line is physical line 4.
        assertThat(rows.get(1).line()).isEqualTo(4);
    }

    @Test
    void refusesAFileWholeAndNamesTheLines() {
        assertThat(refused("order_tracking_id,amount,date\n" + TRACKING + ",1000.5,2026-10-16\n" + TRACKING
                        + ",1000,2026-10-16\n"))
                .contains("line 2", "line 3", "twice");
        assertThat(refused("amount,date\n100,2026-10-16\n")).contains("header");
        assertThat(refused("order_tracking_id,amount,date\n")).contains("no lines");
        assertThat(refused(new byte[StatementCsv.MAX_BYTES + 1])).contains("1 MB");
        StringBuilder many = new StringBuilder("order_tracking_id,amount,date\n");
        for (int i = 0; i <= StatementCsv.MAX_ROWS; i++) {
            many.append(String.format("%08d-2222-4333-8444-555555555555,100,2026-10-16%n", i));
        }
        assertThat(refused(many.toString())).contains("more than");
    }

    @Test
    void refusesAZeroOrNegativeAmountAndAFeeAboveIt() {
        String header = "order_tracking_id,amount,fee,date\n";
        assertThat(refused(header + TRACKING + ",0,0,2026-10-16\n")).contains("line 2 amount");
        assertThat(refused(header + TRACKING + ",-500,0,2026-10-16\n")).contains("line 2 amount");
        assertThat(refused(header + TRACKING + ",100,200,2026-10-16\n")).contains("line 2 fee");
        assertThat(refused(header + TRACKING + ",100,-5,2026-10-16\n")).contains("line 2 fee");
    }

    @Test
    void aCommaIsReadOnlyAsAThousandsSeparatorInGroupsOfThree() {
        String header = "order_tracking_id,amount,fee,date\n";
        assertThat(parse(header + TRACKING + ",\"1,500,000\",\"2,500\",2026-10-16\n")
                        .getFirst()
                        .amountMinor())
                .isEqualTo(1_500_000L);
        assertThat(refused(header + TRACKING + ",\"1,50\",0,2026-10-16\n")).contains("line 2 amount");
        assertThat(refused(header + TRACKING + ",\"15,00,000\",0,2026-10-16\n")).contains("line 2 amount");
        assertThat(refused(header + TRACKING + ",\"1500,000\",0,2026-10-16\n")).contains("line 2 amount");
        assertThat(refused(header + TRACKING + ",5000,\"2,5\",2026-10-16\n")).contains("line 2 fee");
    }

    @Test
    void aStoredFileNameIsCutFirstAndThenNeverStartsWithAFormulaCharacter() {
        String longName = "x".repeat(10) + "+" + "y".repeat(115) + ".csv";
        assertThat(longName).hasSize(130);
        String stored = StatementService.safeName(longName);
        assertThat(stored).hasSize(120).startsWith("_").endsWith(".csv");
        assertThat(StatementService.safeName("-statement.csv")).isEqualTo("_statement.csv");
        assertThat(StatementService.safeName("@x.csv")).isEqualTo("_x.csv");
        assertThat(StatementService.safeName("statement.csv")).isEqualTo("statement.csv");
    }

    @Test
    void anOpenQuoteRefusesTheFileInsteadOfSwallowingTheLinesAfterIt() {
        // A stray quote in a column that is ignored would otherwise turn on quoted mode to the end of the file.
        String text = "order_tracking_id,amount,date,payer_name\n" + TRACKING + ",100,2026-10-16,Test \"Payer\n" + OTHER
                + ",200,2026-10-16,Another\n";
        assertThat(refused(text)).contains("line 2", "never closed");
    }

    @Test
    void aLineWithAnotherNumberOfCellsThanTheHeaderRefusesTheFile() {
        assertThat(refused("order_tracking_id,amount,date\n" + TRACKING + ",100\n" + OTHER + ",200,2026-10-16,extra\n"))
                .contains("line 2", "line 3", "cells");
    }

    @Test
    void problemsNameThePhysicalLineEvenAfterAQuotedCellThatSpansLines() {
        String text = "order_tracking_id,amount,date,note\n"
                + TRACKING + ",100,2026-10-16,\"first\nsecond\nthird\"\n"
                + OTHER + ",nonsense,2026-10-16,ok\n";
        assertThat(refused(text)).contains("line 5 amount").doesNotContain("line 3");
    }

    @Test
    void twoLinesWithTheSameCodeAndNoTrackingIdRefuseTheFileAsTwoLinesWithTheSameTrackingIdDo() {
        assertThat(refused("confirmation_code,amount,date\nCONF-A,100,2026-10-16\nconf-a,100,2026-10-16\n"))
                .contains("line 3", "same confirmation code");
        // Different codes, or the same code on a line that has a tracking id, are not this problem.
        assertThat(parse("order_tracking_id,confirmation_code,amount,date\n" + TRACKING + ",CONF-A,100,2026-10-16\n"
                        + ",CONF-A,100,2026-10-16\n"))
                .hasSize(2);
    }

    @Test
    void aDateAfterTheDayOfTheUploadOrAnAmbiguousSlashDateRefusesTheFile() {
        String header = "order_tracking_id,amount,date\n";
        assertThat(refused(header + TRACKING + ",100,2026-10-20\n")).contains("line 2 date", "future");
        assertThat(refused(header + TRACKING + ",100,20/10/2026\n")).contains("future");
        // 10/11/2026 is the 10th of November or the 11th of October: not guessed.
        assertThat(refused(header + TRACKING + ",100,10/11/2026\n")).contains("Ambiguous");
        assertThat(refused(header + TRACKING + ",100,1/13/2026\n")).contains("Ambiguous");
        assertThat(refused(header + TRACKING + ",100,16 October\n")).contains("line 2 date");
        // A day first date whose first number can only be a day, and today itself, are fine.
        assertThat(parse(header + TRACKING + ",100,16/10/2026\n").getFirst().paidOn())
                .isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(parse(header + TRACKING + ",100,2026-10-19\n").getFirst().paidOn())
                .isEqualTo(TODAY);
    }

    @Test
    void twoColumnsThatMeanTheSameThingRefuseTheHeader() {
        assertThat(refused("order_tracking_id,amount,gross,date\n" + TRACKING + ",100,100,2026-10-16\n"))
                .contains("line 1 header", "amount");
        assertThat(refused("tracking_id,order_tracking_id,amount,date\n" + TRACKING + "," + TRACKING
                        + ",100,2026-10-16\n"))
                .contains("tracking");
    }

    @Test
    void aCodeOrMethodThatStartsLikeAFormulaIsRefused() {
        for (String start : List.of("=", "+", "-", "@")) {
            assertThat(refused("confirmation_code,amount,date\n" + start + "CMD,100,2026-10-16\n"))
                    .as(start)
                    .contains("line 2 confirmation_code");
            assertThat(refused("order_tracking_id,amount,date,method\n" + TRACKING + ",100,2026-10-16," + start
                            + "SUM(A1)\n"))
                    .as(start)
                    .contains("line 2 payment_method");
        }
        // A hyphen inside a code is normal.
        assertThat(parse("confirmation_code,amount,date\nCONF-A-1,100,2026-10-16\n")
                        .getFirst()
                        .confirmationCode())
                .isEqualTo("CONF-A-1");
    }

    @Test
    void neverStoresAWordOfThePayerColumnsItIgnores() {
        List<Row> rows = parse("order_tracking_id,amount,date,payer_phone,payer_name\n" + TRACKING
                + ",100,2026-10-16,+256700000001,Test Payer\n");
        assertThat(rows.getFirst().toString()).doesNotContain("256700000001").doesNotContain("Test Payer");
    }

    @Test
    void refusesTextThatIsNotUtf8AndAnEmptyFile() {
        assertThat(refused(new byte[] {(byte) 0xff, (byte) 0xfe, 0x41})).contains("UTF-8");
        assertThatThrownBy(() -> StatementCsv.parse(new byte[0], "UGX", TODAY)).isInstanceOf(ApiException.class);
    }
}
