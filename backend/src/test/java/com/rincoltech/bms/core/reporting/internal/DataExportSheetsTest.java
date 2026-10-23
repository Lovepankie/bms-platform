package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.reporting.DataExportSheets;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sheets of "Download my data" (issue #334 step 1, ADR-074) without a database: a long dated table is cut into a sheet
 * per year, a year past the split size continues in numbered parts, a short table stays one sheet, and a moment is written
 * as the business's own date and time. The split size is 3 here so every cut is visible with a handful of rows.
 */
class DataExportSheetsTest {

    private static final List<Column> COLUMNS =
            List.of(new Column("date", "Date", Kind.DATE), new Column("amount", "Amount", Kind.MONEY));

    /** Records the tables opened and the rows written to each, in order. */
    static final class Recording implements ReportOutput {

        final Map<String, String> titles = new LinkedHashMap<>();
        final Map<String, List<Column>> columns = new LinkedHashMap<>();
        final Map<String, List<Object[]>> rows = new LinkedHashMap<>();

        @Override
        public Table table(String key, String title, List<Column> columns) {
            titles.put(key, title);
            this.columns.put(key, columns);
            List<Object[]> list = new ArrayList<>();
            rows.put(key, list);
            return new Table() {
                @Override
                public void row(Object... cells) {
                    list.add(cells);
                }

                @Override
                public void total(Object... cells) {
                    throw new AssertionError("the data export writes no totals");
                }
            };
        }

        @Override
        public void note(String text) {}

        @Override
        public long rows() {
            return rows.values().stream().mapToLong(List::size).sum();
        }

        @Override
        public void checkpoint() {}
    }

    private final Recording out = new Recording();
    private final DataExportReport.Sheets sheets = new DataExportReport.Sheets(out, ZoneId.of("Africa/Kampala"), 3);

    @Test
    void aDatedTableLongerThanTheSplitGetsASheetPerYearAndAFullYearContinuesInParts() {
        DataExportSheets.DatedRows sales = sheets.dated("sales", "Sales", COLUMNS, 7);
        LocalDate[] dates = {
            LocalDate.of(2024, 12, 30),
            LocalDate.of(2025, 1, 2),
            LocalDate.of(2025, 3, 4),
            LocalDate.of(2025, 5, 6),
            LocalDate.of(2025, 7, 8),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 10, 8)
        };
        for (LocalDate d : dates) {
            sales.row(d, d, 1000L);
        }
        assertThat(out.titles)
                .containsExactly(
                        Map.entry("sales_2024", "Sales 2024"),
                        Map.entry("sales_2025", "Sales 2025"),
                        Map.entry("sales_2025_2", "Sales 2025 (2)"),
                        Map.entry("sales_2026", "Sales 2026"));
        assertThat(out.rows.get("sales_2024")).hasSize(1);
        assertThat(out.rows.get("sales_2025")).hasSize(3);
        assertThat(out.rows.get("sales_2025_2")).hasSize(1);
        assertThat(out.rows.get("sales_2026")).hasSize(2);
    }

    @Test
    void aDatedTableWithinTheSplitIsOneSheetWhateverItsYears() {
        DataExportSheets.DatedRows sales = sheets.dated("sales", "Sales", COLUMNS, 3);
        sales.row(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), 1L);
        sales.row(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 2L);
        assertThat(out.titles).containsExactly(Map.entry("sales", "Sales"));
        assertThat(out.rows.get("sales")).hasSize(2);
    }

    @Test
    void anEmptyTableStillHasItsSheetAndAnUndatedOneIsCutIntoNumberedParts() {
        sheets.dated("repayments", "Repayments", COLUMNS, 0);
        DataExportSheets.Rows products = sheets.table("products", "Products", COLUMNS);
        for (int i = 0; i < 4; i++) {
            products.row(null, (long) i);
        }
        assertThat(out.titles)
                .containsExactly(
                        Map.entry("repayments", "Repayments"),
                        Map.entry("products", "Products"),
                        Map.entry("products_2", "Products (2)"));
        assertThat(out.rows.get("repayments")).isEmpty();
        assertThat(out.rows.get("products")).hasSize(3);
        assertThat(out.rows.get("products_2")).hasSize(1);
    }

    @Test
    void aMomentIsTheBusinessDateAndTimeNotUtc() {
        // 22:30 UTC on 7 October is 01:30 on 8 October in Kampala (UTC+3).
        Timestamp late = Timestamp.from(Instant.parse("2026-10-07T22:30:00Z"));
        assertThat(sheets.date(late)).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(sheets.time(late)).isEqualTo("01:30");
        assertThat(sheets.date(null)).isNull();
        assertThat(sheets.time(null)).isNull();
    }

    @Test
    void theEmailedCopyLeavesOutThePersonalColumnsHeaderAndCellsAndTheFullCopyKeepsThem() {
        List<Column> members = List.of(
                new Column("member_no", "Member no", Kind.TEXT),
                new Column("phone", "Phone", Kind.TEXT),
                new Column("name", "Name", Kind.TEXT),
                new Column("id_number", "ID number", Kind.TEXT));
        java.util.Set<String> personal = java.util.Set.of("phone", "id_number");

        Recording emailed = new Recording();
        DataExportReport.Sheets weekly = new DataExportReport.Sheets(emailed, ZoneId.of("Africa/Kampala"), 3, false);
        weekly.dated("members", "Members", members, 1, personal)
                .row(LocalDate.of(2026, 10, 8), "M0001", "+256700000001", "Test Member", "CM00000000TEST");
        weekly.table("plain", "Plain", COLUMNS, personal).row(LocalDate.of(2026, 10, 8), 5L);
        assertThat(emailed.columns.get("members")).extracting(Column::key).containsExactly("member_no", "name");
        assertThat(emailed.rows.get("members").get(0)).containsExactly("M0001", "Test Member");
        assertThat(emailed.columns.get("plain")).isEqualTo(COLUMNS);

        Recording full = new Recording();
        new DataExportReport.Sheets(full, ZoneId.of("Africa/Kampala"), 3, true)
                .dated("members", "Members", members, 1, personal)
                .row(LocalDate.of(2026, 10, 8), "M0001", "+256700000001", "Test Member", "CM00000000TEST");
        assertThat(full.columns.get("members")).isEqualTo(members);
        assertThat(full.rows.get("members").get(0)).hasSize(4);
    }
}
