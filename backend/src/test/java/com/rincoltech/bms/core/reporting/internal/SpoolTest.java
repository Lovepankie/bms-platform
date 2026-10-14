package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The spool of a report run (ADR-038 decision 2) on its own: its disk guard counts bytes as written, not characters
 * (extras slice D1 review), it enforces the row cap per table, it types every cell, and it asks its guard before each row.
 */
class SpoolTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private Spool.Writer writer(int rowCap, long maxBytes, Spool.Guard guard) {
        return new Spool(dir.resolve("run"), mapper).writer(rowCap, maxBytes, guard);
    }

    @Test
    void theDiskGuardCountsEncodedBytesNotCharacters() {
        // Guard limit: twice 100 bytes. A row of 30 two-byte characters is about 66 bytes as written but 36 characters.
        String name = "é".repeat(30);
        Spool.Writer out = writer(1000, 100, () -> {});
        var t = out.table("names", "Names", List.of(new Column("name", "Name", Kind.TEXT)));
        t.row(name);
        t.row(name);
        t.row(name);
        assertThatThrownBy(() -> t.row(name))
                .isInstanceOfSatisfying(
                        ReportFailure.class, e -> assertThat(e.code()).isEqualTo("report_too_large"));
        // The same rows in plain ASCII (36 bytes a row) are still under the same limit after four rows.
        Spool.Writer ascii = new Spool(dir.resolve("ascii"), mapper).writer(1000, 100, () -> {});
        var u = ascii.table("names", "Names", List.of(new Column("name", "Name", Kind.TEXT)));
        for (int i = 0; i < 4; i++) {
            u.row("e".repeat(30));
        }
        assertThat(ascii.rows()).isEqualTo(4);
    }

    @Test
    void aTableOverTheRowCapEndsTheRunButNotBeforeTheCap() {
        Spool.Writer out = writer(3, 1_000_000, () -> {});
        var t = out.table("rows", "Rows", List.of(new Column("id", "Id", Kind.COUNT)));
        t.row(1);
        t.row(2);
        t.row(3);
        assertThatThrownBy(() -> t.row(4))
                .isInstanceOfSatisfying(
                        ReportFailure.class, e -> assertThat(e.code()).isEqualTo("report_too_large"));
        // The cap is per table: a second table starts again.
        var u = out.table("more", "More", List.of(new Column("id", "Id", Kind.COUNT)));
        u.row(1);
        assertThat(out.rows()).isEqualTo(4);
    }

    @Test
    void everyRowAsksTheGuardAndACellOfTheWrongKindIsRefused() throws Exception {
        int[] asked = {0};
        Spool.Writer out = writer(10, 1_000_000, () -> asked[0]++);
        var t = out.table(
                "rows", "Rows", List.of(new Column("id", "Id", Kind.COUNT), new Column("on", "On", Kind.DATE)));
        t.row(1, java.time.LocalDate.of(2026, 10, 8));
        t.row(2, null);
        assertThat(asked[0]).isEqualTo(2);
        assertThatThrownBy(() -> t.row("one", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.row(1)).isInstanceOf(IllegalArgumentException.class);
        out.checkpoint();
        assertThat(asked[0])
                .as("the refused cell was asked about first, and a report may ask the guard itself while it reads")
                .isEqualTo(4);
        var written = out.finish();
        assertThat(Files.readAllLines(written.get(0).file())).containsExactly("[1,\"2026-10-08\"]", "[2,null]");
    }
}
