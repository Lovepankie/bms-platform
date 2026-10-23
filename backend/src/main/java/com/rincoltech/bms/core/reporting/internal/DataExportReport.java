package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.DataExportSection;
import com.rincoltech.bms.core.reporting.DataExportSheets;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * "Download my data" (issue #334 step 1, ADR-074): the whole business as one workbook, a sheet per table, made by the report
 * run framework like any other report (queued, one snapshot, kept 30 days, tenant scoped). The core writes the Branches
 * sheet; each vertical the business has on adds its own sheets through a {@link DataExportSection}. It has no parameters and
 * no branch scope: it is read by an administrator for the whole business, so a file made for one stays theirs to fetch for
 * as long as they hold the permission. Read only: making it changes nothing.
 */
@Component
class DataExportReport implements ReportDefinition {

    static final String KEY = "core.data_export";
    static final String PERMISSION = "core.data_export.run";

    /**
     * The stored parameter that marks the weekly copy made to be emailed, which leaves out the columns of personal data
     * (ADR-074 decision 6). It is set by the weekly job only, never by a request, which takes no parameters.
     */
    static final String COPY = "copy";

    static final String WEEKLY = "weekly";

    /** Rows fetched from the server at a time by the reads of the workbook. */
    static final int FETCH = 2000;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final List<DataExportSection> sections;
    private final TenantModules modules;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final JdbcTemplate streaming;
    private final int splitRows;
    private final Duration timeout;

    DataExportReport(
            List<DataExportSection> sections,
            TenantModules modules,
            CurrentTenant tenant,
            BusinessClock clock,
            DataSource dataSource,
            ReportsProperties limits,
            @Value("${bms.data-export.split-rows:10000}") int splitRows,
            @Value("${bms.data-export.timeout:PT30M}") Duration timeout) {
        this.sections = sections.stream()
                .sorted(Comparator.comparingInt(DataExportSection::order))
                .toList();
        this.modules = modules;
        this.tenant = tenant;
        this.clock = clock;
        // Rows come from the server in blocks of FETCH in the run's transaction, so no table is held whole (ADR-038).
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(FETCH);
        // A sheet never passes the row cap of a run, whatever the split is set to.
        this.splitRows = Math.max(1, Math.min(splitRows, limits.tableRowCap));
        this.timeout = timeout;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Business data";
    }

    @Override
    public String description() {
        return "Every table of the business in one workbook, a sheet each: a copy to keep, like the old spreadsheet.";
    }

    @Override
    public String module() {
        return "core";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public boolean everyBusiness() {
        return true;
    }

    /** The whole business takes longer than one report: 30 minutes, against the runner's 10. */
    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public List<Param> params() {
        return List.of();
    }

    @Override
    public Set<String> formats() {
        return Set.of("xlsx");
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        return new Prepared(Map.of(), Map.of(), clock.today(tenant.profile().timezone()));
    }

    /** The weekly copy for the email: the same workbook without the columns of personal data. */
    Prepared weekly() {
        return new Prepared(
                Map.of(COPY, WEEKLY), Map.of(), clock.today(tenant.profile().timezone()));
    }

    /** True for a run made with {@link #weekly}. */
    static boolean isWeekly(Map<String, Object> params) {
        return WEEKLY.equals(params.get(COPY));
    }

    @Override
    public String describeScope(Prepared run) {
        return "Every branch of the business";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        out.note("A copy of the business's data, every branch. Making it changed nothing.");
        out.note("Dates and times are in the business's time zone; amounts are whole numbers in its currency.");
        boolean personal = !isWeekly(prepared.params());
        if (!personal) {
            out.note("This is the weekly copy sent by email: contact details and ID numbers are only in the copy you"
                    + " download after signing in.");
        }
        Sheets sheets = new Sheets(out, tenant.profile().timezone(), splitRows, personal);
        branches(sheets);
        for (DataExportSection section : sections) {
            if (modules.isEnabled(section.module())) {
                section.write(sheets);
            }
        }
    }

    private void branches(Sheets sheets) {
        DataExportSheets.Rows rows = sheets.table(
                "branches",
                "Branches",
                List.of(
                        new Column("code", "Code", Kind.TEXT),
                        new Column("name", "Name", Kind.TEXT),
                        new Column("location", "Location", Kind.TEXT),
                        new Column("head_office", "Head office", Kind.TEXT),
                        new Column("status", "Status", Kind.TEXT),
                        new Column("opened_on", "Opened on", Kind.DATE)));
        streaming.query("""
                SELECT code, name, location, is_head_office, status, created_at
                  FROM branches ORDER BY is_head_office DESC, code, id
                """, (RowCallbackHandler) rs -> rows.row(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("location"),
                rs.getBoolean("is_head_office") ? "Yes" : "No",
                rs.getString("status"),
                sheets.date(rs.getTimestamp("created_at"))));
    }

    /**
     * The sheets of one run: each table opened on the output as it is needed, a table past {@code split} rows cut into
     * more. A dated table longer than the split gets a sheet per year; any sheet that reaches the split continues in a
     * numbered part, so no sheet passes the run's row cap.
     */
    static final class Sheets implements DataExportSheets {

        private final ReportOutput out;
        private final ZoneId zone;
        private final int split;
        private final boolean personal;

        Sheets(ReportOutput out, ZoneId zone, int split) {
            this(out, zone, split, true);
        }

        /** @param personal false for the emailed copy: the columns named personal are left out */
        Sheets(ReportOutput out, ZoneId zone, int split, boolean personal) {
            this.out = out;
            this.zone = zone;
            this.split = split;
            this.personal = personal;
        }

        @Override
        public ZoneId zone() {
            return zone;
        }

        @Override
        public LocalDate date(Timestamp at) {
            return at == null ? null : LocalDate.ofInstant(at.toInstant(), zone);
        }

        @Override
        public String time(Timestamp at) {
            return at == null ? null : CLOCK.format(at.toInstant().atZone(zone));
        }

        @Override
        public void checkpoint() {
            out.checkpoint();
        }

        @Override
        public Rows table(String key, String title, List<Column> columns, Set<String> personalColumns) {
            Splitter s = new Splitter(key, title, columns, personalColumns, false);
            s.open(null);
            return cells -> s.write(null, cells);
        }

        @Override
        public DatedRows dated(
                String key, String title, List<Column> columns, long rowCount, Set<String> personalColumns) {
            boolean byYear = rowCount > split;
            Splitter s = new Splitter(key, title, columns, personalColumns, byYear);
            if (!byYear) {
                s.open(null);
            }
            return (date, cells) -> s.write(Objects.requireNonNull(date, "a dated row needs its date"), cells);
        }

        private final class Splitter {

            private final String key;
            private final String title;
            private final List<Column> columns;
            /** The positions of the cells written, or null for all of them. */
            private final int[] kept;

            private final boolean byYear;
            private ReportOutput.Table current;
            private Integer year;
            private int part;
            private long inPart;

            Splitter(String key, String title, List<Column> columns, Set<String> personalColumns, boolean byYear) {
                this.key = key;
                this.title = title;
                if (personal || personalColumns.isEmpty()) {
                    this.columns = columns;
                    this.kept = null;
                } else {
                    this.kept = java.util.stream.IntStream.range(0, columns.size())
                            .filter(i ->
                                    !personalColumns.contains(columns.get(i).key()))
                            .toArray();
                    this.columns =
                            java.util.Arrays.stream(kept).mapToObj(columns::get).toList();
                }
                this.byYear = byYear;
            }

            void open(Integer y) {
                if (!Objects.equals(y, year)) {
                    part = 0;
                }
                year = y;
                part++;
                inPart = 0;
                String keySuffix = (y == null ? "" : "_" + y) + (part > 1 ? "_" + part : "");
                String titleSuffix = (y == null ? "" : " " + y) + (part > 1 ? " (" + part + ")" : "");
                current = out.table(key + keySuffix, title + titleSuffix, columns);
            }

            void write(LocalDate date, Object[] cells) {
                Integer y = byYear ? date.getYear() : null;
                if (current == null || !Objects.equals(y, year) || inPart >= split) {
                    open(y);
                }
                if (kept == null) {
                    current.row(cells);
                } else {
                    Object[] left = new Object[kept.length];
                    for (int i = 0; i < kept.length; i++) {
                        left[i] = cells[kept[i]];
                    }
                    current.row(left);
                }
                inPart++;
            }
        }
    }
}
