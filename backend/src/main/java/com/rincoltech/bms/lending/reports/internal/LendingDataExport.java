package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.DataExportSection;
import com.rincoltech.bms.core.reporting.DataExportSheets;
import com.rincoltech.bms.core.reporting.DataExportSheets.DatedRows;
import com.rincoltech.bms.core.reporting.DataExportSheets.Rows;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The lending sheets of "Download my data" (issue #334 step 1, ADR-074): Members, Loans with what each still owes,
 * Repayments (with recoveries and the reversals of either) in date order, and Arrears, the loans past due as the nightly
 * arrears run left them. Every branch and every officer: the file is the business's own copy, read by an administrator.
 */
@Component
class LendingDataExport implements DataExportSection {

    private static final Column BRANCH = new Column("branch", "Branch", Kind.TEXT);
    private static final Column LOAN_NO = new Column("loan_no", "Loan no", Kind.TEXT);
    private static final Column MEMBER_NO = new Column("member_no", "Member no", Kind.TEXT);
    private static final Column MEMBER = new Column("member", "Member", Kind.TEXT);
    private static final Column OFFICER = new Column("officer", "Officer", Kind.TEXT);

    /** Rows fetched from the server at a time: a table of any length is never held whole (ADR-038). */
    static final int FETCH = 2000;

    private final JdbcClient jdbc;
    private final JdbcTemplate streaming;

    LendingDataExport(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        // Joins the run's snapshot transaction like any read; the fetch size makes the driver hand rows over in blocks.
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(FETCH);
    }

    @Override
    public String module() {
        return "lending";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void write(DataExportSheets sheets) {
        members(sheets);
        sheets.checkpoint();
        loans(sheets);
        sheets.checkpoint();
        repayments(sheets);
        arrears(sheets);
    }

    private interface Row {
        void write(ResultSet rs) throws SQLException;
    }

    /** Writes each row as the server returns it, {@link #FETCH} at a time; nothing is collected. */
    private void each(String sql, Row row) {
        streaming.query(sql, (RowCallbackHandler) row::write);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static Long money(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Long.class);
    }

    private static LocalDate day(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private void members(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "members",
                "Members",
                List.of(
                        MEMBER_NO,
                        new Column("name", "Name", Kind.TEXT),
                        new Column("phone", "Phone", Kind.TEXT),
                        new Column("other_phone", "Other phone", Kind.TEXT),
                        new Column("id_type", "ID type", Kind.TEXT),
                        new Column("id_number", "ID number", Kind.TEXT),
                        new Column("gender", "Gender", Kind.TEXT),
                        new Column("date_of_birth", "Date of birth", Kind.DATE),
                        new Column("district", "District", Kind.TEXT),
                        new Column("village", "Village", Kind.TEXT),
                        new Column("occupation", "Occupation", Kind.TEXT),
                        BRANCH,
                        OFFICER,
                        new Column("kyc", "KYC", Kind.TEXT),
                        new Column("status", "Status", Kind.TEXT),
                        new Column("joined_on", "Joined on", Kind.DATE)),
                count("SELECT count(*) FROM lending_members"));
        each("""
                SELECT m.member_no, m.full_name, m.phone_e164, m.alt_phone_e164, m.id_type,
                       coalesce(m.national_id, m.other_id_number) AS id_number, m.gender, m.date_of_birth, m.district,
                       m.village, m.occupation, b.name AS branch, u.full_name AS officer, m.kyc_status, m.status,
                       m.created_at
                  FROM lending_members m
                  JOIN branches b ON b.tenant_id = m.tenant_id AND b.id = m.branch_id
                  LEFT JOIN users u ON u.tenant_id = m.tenant_id AND u.id = m.officer_user_id
                 ORDER BY m.created_at, m.id
                """, rs -> {
            LocalDate joined = sheets.date(rs.getTimestamp("created_at"));
            rows.row(
                    joined,
                    rs.getString("member_no"),
                    rs.getString("full_name"),
                    rs.getString("phone_e164"),
                    rs.getString("alt_phone_e164"),
                    rs.getString("id_type"),
                    rs.getString("id_number"),
                    rs.getString("gender"),
                    day(rs, "date_of_birth"),
                    rs.getString("district"),
                    rs.getString("village"),
                    rs.getString("occupation"),
                    rs.getString("branch"),
                    rs.getString("officer"),
                    rs.getString("kyc_status"),
                    rs.getString("status"),
                    joined);
        });
    }

    private void loans(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "loans",
                "Loans",
                List.of(
                        LOAN_NO,
                        MEMBER_NO,
                        MEMBER,
                        BRANCH,
                        new Column("product", "Product", Kind.TEXT),
                        OFFICER,
                        new Column("status", "Status", Kind.TEXT),
                        new Column("applied_on", "Applied on", Kind.DATE),
                        new Column("disbursed_on", "Disbursed on", Kind.DATE),
                        new Column("maturity_date", "Maturity date", Kind.DATE),
                        new Column("principal", "Principal disbursed", Kind.MONEY),
                        new Column("rate", "Interest rate", Kind.BASIS_POINTS),
                        new Column("rate_unit", "Rate per", Kind.TEXT),
                        new Column("term", "Term", Kind.COUNT),
                        new Column("term_unit", "Term in", Kind.TEXT),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("interest_outstanding", "Interest outstanding", Kind.MONEY),
                        new Column("fees_outstanding", "Fees outstanding", Kind.MONEY),
                        new Column("penalties_outstanding", "Penalties outstanding", Kind.MONEY),
                        new Column("total_paid", "Total paid", Kind.MONEY),
                        new Column("arrears", "Arrears", Kind.MONEY),
                        new Column("days_past_due", "Days past due", Kind.COUNT),
                        new Column("closed_on", "Closed on", Kind.DATE)),
                count("SELECT count(*) FROM lending_loans"));
        each("""
                SELECT l.loan_no, m.member_no, m.full_name AS member, b.name AS branch, p.name AS product,
                       u.full_name AS officer, l.status, l.created_at, l.disbursed_on, l.maturity_date,
                       l.principal_disbursed_minor, l.interest_rate_bp, l.rate_unit,
                       coalesce(l.approved_term_count, l.requested_term_count) AS term, l.term_unit,
                       l.principal_outstanding_minor, l.interest_outstanding_minor, l.fees_outstanding_minor,
                       l.penalties_outstanding_minor, l.total_paid_minor, l.arrears_minor, l.days_past_due, l.closed_on
                  FROM lending_loans l
                  JOIN lending_members m ON m.tenant_id = l.tenant_id AND m.id = l.member_id
                  JOIN branches b ON b.tenant_id = l.tenant_id AND b.id = l.branch_id
                  LEFT JOIN lending_loan_product_versions pv ON pv.tenant_id = l.tenant_id AND pv.id = l.product_version_id
                  LEFT JOIN lending_loan_products p ON p.tenant_id = pv.tenant_id AND p.id = pv.product_id
                  LEFT JOIN users u ON u.tenant_id = l.tenant_id AND u.id = l.officer_user_id
                 ORDER BY l.created_at, l.id
                """, rs -> {
            LocalDate applied = sheets.date(rs.getTimestamp("created_at"));
            rows.row(
                    applied,
                    rs.getString("loan_no"),
                    rs.getString("member_no"),
                    rs.getString("member"),
                    rs.getString("branch"),
                    rs.getString("product"),
                    rs.getString("officer"),
                    rs.getString("status"),
                    applied,
                    day(rs, "disbursed_on"),
                    day(rs, "maturity_date"),
                    money(rs, "principal_disbursed_minor"),
                    rs.getObject("interest_rate_bp", Integer.class),
                    rs.getString("rate_unit"),
                    rs.getObject("term", Integer.class),
                    rs.getString("term_unit"),
                    money(rs, "principal_outstanding_minor"),
                    money(rs, "interest_outstanding_minor"),
                    money(rs, "fees_outstanding_minor"),
                    money(rs, "penalties_outstanding_minor"),
                    money(rs, "total_paid_minor"),
                    money(rs, "arrears_minor"),
                    rs.getObject("days_past_due", Integer.class),
                    day(rs, "closed_on"));
        });
    }

    /** Repayments and recoveries, and the reversals of either. */
    private static final String REPAYMENTS = """
              FROM lending_loan_transactions t
              LEFT JOIN lending_loan_transactions o ON o.tenant_id = t.tenant_id AND o.id = t.reverses_txn_id
             WHERE t.txn_type IN ('repayment', 'recovery')
                OR (t.txn_type = 'reversal' AND o.txn_type IN ('repayment', 'recovery'))
            """;

    private void repayments(DataExportSheets sheets) {
        DatedRows rows = sheets.dated(
                "repayments",
                "Repayments",
                List.of(
                        new Column("date", "Date", Kind.DATE),
                        new Column("receipt_no", "Receipt no", Kind.TEXT),
                        LOAN_NO,
                        MEMBER_NO,
                        MEMBER,
                        BRANCH,
                        new Column("type", "Type", Kind.TEXT),
                        new Column("amount", "Amount", Kind.MONEY),
                        new Column("method", "Method", Kind.TEXT),
                        new Column("reference", "Reference", Kind.TEXT),
                        new Column("recorded_by", "Recorded by", Kind.TEXT)),
                count("SELECT count(*)" + REPAYMENTS));
        each(
                "SELECT t.value_date, t.receipt_no, l.loan_no, m.member_no, m.full_name AS member, b.name AS branch,"
                        + " t.txn_type, t.amount_minor, t.payment_method_key, t.external_reference, u.full_name AS recorded_by"
                        + REPAYMENTS.replace(
                                " WHERE ",
                                " JOIN lending_loans l ON l.tenant_id = t.tenant_id AND l.id = t.loan_id"
                                        + " JOIN lending_members m ON m.tenant_id = l.tenant_id AND m.id = l.member_id"
                                        + " JOIN branches b ON b.tenant_id = t.tenant_id AND b.id = t.branch_id"
                                        + " LEFT JOIN users u ON u.tenant_id = t.tenant_id AND u.id = t.recorded_by"
                                        + " WHERE ")
                        + " ORDER BY t.value_date, t.created_at, t.id",
                rs -> rows.row(
                        day(rs, "value_date"),
                        day(rs, "value_date"),
                        rs.getString("receipt_no"),
                        rs.getString("loan_no"),
                        rs.getString("member_no"),
                        rs.getString("member"),
                        rs.getString("branch"),
                        rs.getString("txn_type"),
                        money(rs, "amount_minor"),
                        rs.getString("payment_method_key"),
                        rs.getString("external_reference"),
                        rs.getString("recorded_by")));
    }

    private void arrears(DataExportSheets sheets) {
        Rows rows = sheets.table(
                "arrears",
                "Arrears",
                List.of(
                        LOAN_NO,
                        MEMBER_NO,
                        MEMBER,
                        new Column("phone", "Phone", Kind.TEXT),
                        BRANCH,
                        OFFICER,
                        new Column("days_past_due", "Days past due", Kind.COUNT),
                        new Column("arrears", "Arrears", Kind.MONEY),
                        new Column("principal_outstanding", "Principal outstanding", Kind.MONEY),
                        new Column("next_due", "Next due date", Kind.DATE),
                        new Column("last_repayment", "Last repayment", Kind.DATE)));
        each(
                """
                SELECT l.loan_no, m.member_no, m.full_name AS member, m.phone_e164, b.name AS branch,
                       u.full_name AS officer, l.days_past_due, l.arrears_minor, l.principal_outstanding_minor,
                       l.next_due_date, l.last_repayment_on
                  FROM lending_loans l
                  JOIN lending_members m ON m.tenant_id = l.tenant_id AND m.id = l.member_id
                  JOIN branches b ON b.tenant_id = l.tenant_id AND b.id = l.branch_id
                  LEFT JOIN users u ON u.tenant_id = l.tenant_id AND u.id = l.officer_user_id
                 WHERE l.arrears_minor > 0 OR l.days_past_due > 0
                 ORDER BY l.days_past_due DESC, l.arrears_minor DESC, l.loan_no, l.id
                """,
                rs -> rows.row(
                        rs.getString("loan_no"),
                        rs.getString("member_no"),
                        rs.getString("member"),
                        rs.getString("phone_e164"),
                        rs.getString("branch"),
                        rs.getString("officer"),
                        rs.getObject("days_past_due", Integer.class),
                        money(rs, "arrears_minor"),
                        money(rs, "principal_outstanding_minor"),
                        day(rs, "next_due_date"),
                        day(rs, "last_repayment_on")));
    }
}
