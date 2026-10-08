package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Column;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.TableResponse;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The drill-down tables: the rows that make up each number on the insights page, and the CSV
 * export of any of them. Each table carries only the columns its metric needs: a member is
 * identified by member number; a name appears only in the action lists (arrears, the due
 * forecast, dormant members), and never a phone number or an ID number (chapter 8, ADR-030).
 */
@Component
class InsightsTables {

    static final List<String> KEYS = List.of(
            "disbursements",
            "collections",
            "expected",
            "arrears",
            "active_loans",
            "loans_by_status",
            "forecast",
            "write_offs",
            "recoveries",
            "revenue_lines",
            "new_members",
            "applications",
            "repeat_borrowers",
            "dormant_members",
            "staff_activity");

    /** Columns that hold a person's name: masked in an export for a caller without member read access. */
    static final Set<String> NAME_COLUMNS = Set.of("member_name");

    private final JdbcClient jdbc;
    private final Positions positions;
    private final InsightsQueries queries;

    InsightsTables(JdbcClient jdbc, Positions positions, InsightsQueries queries) {
        this.jdbc = jdbc;
        this.positions = positions;
        this.queries = queries;
    }

    private record Spec(String title, List<Column> columns) {}

    private static Column col(String key, String label, String kind) {
        return new Column(key, label, kind);
    }

    private static final Column LOAN = col("loan_no", "Loan", "text");
    private static final Column MEMBER = col("member_no", "Member", "text");
    private static final Column NAME = col("member_name", "Name", "text");

    TableResponse table(String key, Scope s, Map<String, String> params, ZoneId zone, int limit) {
        if (!KEYS.contains(key)) {
            throw ApiException.notFound();
        }
        LocalDate date = dateParam(params, "date");
        Scope r = date == null ? s : s.withRange(date, date);
        return switch (key) {
            case "disbursements" ->
                sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                "Disbursements",
                                List.of(
                                        col("value_date", "Date", "date"),
                                        LOAN,
                                        MEMBER,
                                        col("product", "Product", "text"),
                                        col("branch", "Branch", "text"),
                                        col("officer", "Officer", "text"),
                                        col("principal_minor", "Principal", "money"))),
                        """
                    SELECT t.value_date, l.loan_no, m.member_no, pr.name AS product, b.name AS branch,
                           u.full_name AS officer, t.amount_minor AS principal_minor
                      FROM lending_loan_transactions t
                      JOIN lending_loans l ON l.id = t.loan_id
                      JOIN lending_members m ON m.id = l.member_id
                      JOIN lending_loan_product_versions pv ON pv.id = l.product_version_id
                      JOIN lending_loan_products pr ON pr.id = pv.product_id
                      JOIN branches b ON b.id = l.branch_id
                      LEFT JOIN users u ON u.id = l.officer_user_id
                     WHERE t.txn_type = 'disbursement' AND t.value_date BETWEEN :from AND :to""",
                        "ORDER BY t.value_date, l.loan_no",
                        Map.of());
            case "collections", "recoveries" ->
                sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                key.equals("collections") ? "Collections" : "Recoveries",
                                List.of(
                                        col("value_date", "Date", "date"),
                                        col("receipt_no", "Receipt", "text"),
                                        LOAN,
                                        MEMBER,
                                        col("txn_type", "Type", "text"),
                                        col("method", "Method", "text"),
                                        col("amount_minor", "Amount", "money"))),
                        """
                    SELECT t.value_date, t.receipt_no, l.loan_no, m.member_no, t.txn_type,
                           t.payment_method_key AS method, t.amount_minor
                      FROM lending_loan_transactions t
                      JOIN lending_loans l ON l.id = t.loan_id
                      JOIN lending_members m ON m.id = l.member_id
                     WHERE t.txn_type IN """ + (key.equals("collections") ? InsightsQueries.COLLECTED_TYPES : "('recovery')") + " "
                                + """
                        AND t.value_date BETWEEN :from AND :to
                       AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions x WHERE x.reverses_txn_id = t.id)""",
                        "ORDER BY t.value_date, t.receipt_no",
                        Map.of());
            case "expected" ->
                sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                "Due in the range",
                                List.of(
                                        col("due_date", "Due", "date"),
                                        LOAN,
                                        MEMBER,
                                        col("item_no", "Item", "count"),
                                        col("due_minor", "Due", "money"),
                                        col("collected_minor", "Collected on due", "money"),
                                        col("unpaid_minor", "Unpaid now", "money"))),
                        """
                    SELECT si.due_date, l.loan_no, m.member_no, si.item_no,
                           si.principal_due_minor + si.interest_due_minor + si.fees_due_minor AS due_minor,
                           coalesce((SELECT sum(a.amount_minor) FROM lending_repayment_allocations a
                                       JOIN lending_loan_transactions t ON t.id = a.applies_to_txn_id
                                      WHERE a.schedule_item_id = si.id AND a.component IN ('principal', 'interest', 'fee')
                                        AND t.value_date <= :to), 0) AS collected_minor,
                           (si.principal_due_minor - si.principal_paid_minor)
                               + (si.interest_due_minor - si.interest_paid_minor - si.interest_waived_minor)
                               + (si.fees_due_minor - si.fees_paid_minor - si.fees_waived_minor) AS unpaid_minor
                      FROM lending_schedule_items si
                      JOIN lending_loans l ON l.id = si.loan_id
                      JOIN lending_members m ON m.id = l.member_id
                     WHERE si.due_date BETWEEN :from AND :to
                       AND l.disbursed_on IS NOT NULL
                       AND (l.written_off_on IS NULL OR l.written_off_on >= :from)
                       AND (l.closed_on IS NULL OR l.closed_on >= :from)""",
                        "ORDER BY si.due_date, l.loan_no, si.item_no",
                        Map.of());
            case "arrears", "active_loans" -> positionsTable(key, s, params, limit);
            case "loans_by_status" -> {
                String status = params.getOrDefault("status", "active");
                yield sql(
                        key,
                        s,
                        limit,
                        new Spec(
                                "Loans by status",
                                List.of(
                                        LOAN,
                                        MEMBER,
                                        col("status", "Status", "text"),
                                        col("product", "Product", "text"),
                                        col("branch", "Branch", "text"),
                                        col("principal_minor", "Principal", "money"))),
                        """
                        SELECT l.loan_no, m.member_no, l.status, pr.name AS product, b.name AS branch,
                               coalesce(l.approved_principal_minor, l.requested_principal_minor) AS principal_minor
                          FROM lending_loans l
                          JOIN lending_members m ON m.id = l.member_id
                          JOIN lending_loan_product_versions pv ON pv.id = l.product_version_id
                          JOIN lending_loan_products pr ON pr.id = pv.product_id
                          JOIN branches b ON b.id = l.branch_id
                         WHERE l.status = :status""",
                        "ORDER BY l.loan_no",
                        Map.of("status", status));
            }
            case "forecast" -> {
                int days = Math.clamp(intParam(params, "days", 30), 1, 92);
                yield sql(
                        key,
                        s,
                        limit,
                        new Spec(
                                "Expected collections (estimate)",
                                List.of(
                                        col("due_date", "Due", "date"),
                                        LOAN,
                                        MEMBER,
                                        NAME,
                                        col("unpaid_minor", "Expected", "money"))),
                        """
                        SELECT si.due_date, l.loan_no, m.member_no, m.full_name AS member_name,
                               (si.principal_due_minor - si.principal_paid_minor)
                                   + (si.interest_due_minor - si.interest_paid_minor - si.interest_waived_minor)
                                   + (si.fees_due_minor - si.fees_paid_minor - si.fees_waived_minor) AS unpaid_minor
                          FROM lending_schedule_items si
                          JOIN lending_loans l ON l.id = si.loan_id
                          JOIN lending_members m ON m.id = l.member_id
                         WHERE l.status = 'active' AND si.due_date > :today AND si.due_date <= :until
                           AND si.status NOT IN ('paid', 'waived', 'written_off')""",
                        "ORDER BY si.due_date, l.loan_no",
                        Map.of(
                                "today",
                                Date.valueOf(s.today()),
                                "until",
                                Date.valueOf(s.today().plusDays(days))));
            }
            case "write_offs" ->
                sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                "Write-offs",
                                List.of(
                                        col("value_date", "Date", "date"),
                                        LOAN,
                                        MEMBER,
                                        col("principal_minor", "Principal", "money"))),
                        """
                    SELECT t.value_date, l.loan_no, m.member_no, t.amount_minor AS principal_minor
                      FROM lending_loan_transactions t
                      JOIN lending_loans l ON l.id = t.loan_id
                      JOIN lending_members m ON m.id = l.member_id
                     WHERE t.txn_type = 'write_off' AND t.value_date BETWEEN :from AND :to""",
                        "ORDER BY t.value_date, l.loan_no",
                        Map.of());
            case "revenue_lines" -> {
                String account = params.get("account");
                List<String> keys = account == null ? InsightsQueries.REVENUE_ACCOUNTS : List.of(account);
                if (!InsightsQueries.REVENUE_ACCOUNTS.containsAll(keys)) {
                    throw ApiException.notFound();
                }
                yield sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                "Posted revenue lines",
                                List.of(
                                        col("entry_date", "Date", "date"),
                                        col("entry_no", "Entry", "text"),
                                        LOAN,
                                        col("account", "Account", "text"),
                                        col("amount_minor", "Amount", "money"))),
                        """
                        SELECT je.entry_date, je.entry_no, l.loan_no, ga.name AS account,
                               CASE WHEN ga.account_type = 'expense' THEN jl.debit - jl.credit
                                    ELSE jl.credit - jl.debit END AS amount_minor
                          FROM journal_entries je
                          JOIN journal_lines jl ON jl.entry_id = je.id
                          JOIN gl_accounts ga ON ga.id = jl.account_id
                          JOIN lending_loan_transactions t ON t.id = je.source_id
                          JOIN lending_loans l ON l.id = t.loan_id
                         WHERE je.entry_date BETWEEN :from AND :to
                           AND je.source_module = 'lending' AND je.source_type = 'loan_transaction'
                           AND ga.system_key IN (:keys)""",
                        "ORDER BY je.entry_date, je.entry_no",
                        Map.of("keys", keys));
            }
            case "new_members" ->
                memberSql(
                        key,
                        r,
                        zone,
                        limit,
                        new Spec(
                                "New members",
                                List.of(
                                        MEMBER,
                                        col("registered_on", "Registered", "date"),
                                        col("branch", "Branch", "text"),
                                        col("kyc_status", "KYC", "text"))),
                        """
                    SELECT m.member_no, (m.created_at AT TIME ZONE :zone)::date AS registered_on, b.name AS branch,
                           m.kyc_status
                      FROM lending_members m
                      JOIN branches b ON b.id = m.branch_id
                     WHERE m.created_at >= :fromTs AND m.created_at < :toTs""",
                        "ORDER BY m.created_at, m.member_no",
                        Map.of());
            case "applications" -> {
                String stage = params.getOrDefault("stage", "applied");
                String condition = switch (stage) {
                    case "applied" -> "";
                    case "appraised" -> " AND EXISTS (SELECT 1 FROM lending_loan_appraisals a WHERE a.loan_id = l.id)";
                    case "approved" -> " AND l.approved_at IS NOT NULL";
                    case "disbursed" -> " AND l.disbursed_on IS NOT NULL";
                    default -> throw ApiException.notFound();
                };
                Map<String, Object> p = instants(r, zone);
                yield sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                "Applications: " + stage,
                                List.of(
                                        LOAN,
                                        MEMBER,
                                        col("submitted_on", "Submitted", "date"),
                                        col("status", "Status", "text"),
                                        col("principal_minor", "Requested", "money"))),
                        """
                        SELECT l.loan_no, m.member_no, (l.submitted_at AT TIME ZONE :zone)::date AS submitted_on, l.status,
                               l.requested_principal_minor AS principal_minor
                          FROM lending_loans l
                          JOIN lending_members m ON m.id = l.member_id
                         WHERE l.submitted_at >= :fromTs AND l.submitted_at < :toTs""" + condition,
                        "ORDER BY l.submitted_at, l.loan_no",
                        p);
            }
            case "repeat_borrowers" ->
                sql(
                        key,
                        r,
                        limit,
                        new Spec(
                                "Repeat borrowers",
                                List.of(
                                        MEMBER,
                                        col("first_in_range", "Disbursed in range", "date"),
                                        col("earlier_loans", "Earlier loans", "count"))),
                        """
                    SELECT m.member_no, min(t.value_date) AS first_in_range,
                           (SELECT count(*) FROM lending_loans e
                             WHERE e.member_id = l.member_id AND e.disbursed_on < min(t.value_date)) AS earlier_loans
                      FROM lending_loan_transactions t
                      JOIN lending_loans l ON l.id = t.loan_id
                      JOIN lending_members m ON m.id = l.member_id
                     WHERE t.txn_type = 'disbursement' AND t.value_date BETWEEN :from AND :to""",
                        "GROUP BY m.member_no, l.member_id HAVING (SELECT count(*) FROM lending_loans e WHERE e.member_id = l.member_id"
                                + " AND e.disbursed_on < min(t.value_date)) > 0 ORDER BY m.member_no",
                        Map.of());
            case "dormant_members" ->
                memberSql(
                        key,
                        s,
                        zone,
                        limit,
                        new Spec(
                                "Dormant members",
                                List.of(
                                        MEMBER,
                                        NAME,
                                        col("branch", "Branch", "text"),
                                        col("last_activity", "Last loan activity", "date"))),
                        """
                    SELECT m.member_no, m.full_name AS member_name, b.name AS branch,
                           (SELECT max(dt.value_date) FROM lending_loan_transactions dt
                              JOIN lending_loans dl ON dl.id = dt.loan_id WHERE dl.member_id = m.id) AS last_activity
                      FROM lending_members m
                      JOIN branches b ON b.id = m.branch_id
                     WHERE true""" + InsightsQueries.DORMANT,
                        "ORDER BY m.member_no",
                        Map.of("since", Date.valueOf(s.today().minusDays(90))));
            case "staff_activity" -> staffTable(s, zone, limit);
            default -> throw ApiException.notFound();
        };
    }

    // ---- Builders ---------------------------------------------------------------------------

    /** A loan-based table: {@code where} gets the scope's loan filter on alias {@code l}, then {@code tail}. */
    private TableResponse sql(
            String key, Scope s, int limit, Spec spec, String where, String tail, Map<String, ?> extra) {
        if (s.empty()) {
            return new TableResponse(key, spec.title(), s.currency(), spec.columns(), List.of(), false);
        }
        Map<String, Object> p = new LinkedHashMap<>(extra);
        p.put("from", Date.valueOf(s.from()));
        p.put("to", Date.valueOf(s.to()));
        String sql = where + s.loanFilter("l", p) + " " + tail + " LIMIT :limit";
        return run(key, s, limit, spec, sql, p);
    }

    /** A member-based table: branch and officer predicates on alias {@code m}. */
    private TableResponse memberSql(
            String key, Scope s, ZoneId zone, int limit, Spec spec, String where, String tail, Map<String, ?> extra) {
        if (s.empty()) {
            return new TableResponse(key, spec.title(), s.currency(), spec.columns(), List.of(), false);
        }
        Map<String, Object> p = instants(s, zone);
        p.putAll(extra);
        StringBuilder filter = new StringBuilder();
        if (s.branchIds() != null) {
            filter.append(" AND m.branch_id IN (:branches)");
            p.put("branches", s.branchIds());
        }
        if (s.officerId() != null) {
            filter.append(" AND m.officer_user_id = :officer");
            p.put("officer", s.officerId());
        }
        return run(key, s, limit, spec, where + filter + " " + tail + " LIMIT :limit", p);
    }

    private TableResponse run(String key, Scope s, int limit, Spec spec, String sql, Map<String, Object> p) {
        p.put("limit", limit + 1);
        List<String> keys = spec.columns().stream().map(Column::key).toList();
        List<List<String>> rows =
                jdbc.sql(sql).params(p).query((rs, n) -> row(rs, keys)).list();
        boolean truncated = rows.size() > limit;
        return new TableResponse(
                key, spec.title(), s.currency(), spec.columns(), truncated ? rows.subList(0, limit) : rows, truncated);
    }

    private static List<String> row(ResultSet rs, List<String> keys) throws SQLException {
        List<String> cells = new ArrayList<>(keys.size());
        for (String k : keys) {
            Object v = rs.getObject(k);
            cells.add(v == null ? "" : String.valueOf(v));
        }
        return cells;
    }

    private static Map<String, Object> instants(Scope s, ZoneId zone) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fromTs", Timestamp.from(s.from().atStartOfDay(zone).toInstant()));
        p.put("toTs", Timestamp.from(s.to().plusDays(1).atStartOfDay(zone).toInstant()));
        p.put("zone", zone.getId());
        return p;
    }

    /** Active loans from the live positions: every one, or those in arrears with an optional DPD band. */
    private TableResponse positionsTable(String key, Scope s, Map<String, String> params, int limit) {
        boolean arrears = key.equals("arrears");
        int minDpd = intParam(params, "min_dpd", arrears ? 1 : 0);
        int maxDpd = intParam(params, "max_dpd", Integer.MAX_VALUE);
        String bucket = params.get("bucket");
        String group = params.get("group");
        String groupKey = params.get("key");
        List<Positions.Position> rows = positions.live(s).stream()
                .filter(p -> p.daysPastDue() >= minDpd && p.daysPastDue() <= maxDpd)
                .filter(p -> bucket == null || bucket.equals(p.bucket()))
                .filter(p -> group == null || groupKey == null || groupKey.equals(groupValue(p, group)))
                .sorted(
                        arrears
                                ? Comparator.comparingLong(Positions.Position::arrearsMinor)
                                        .reversed()
                                        .thenComparing(Positions.Position::loanNo)
                                : Comparator.comparing(Positions.Position::loanNo))
                .toList();
        boolean truncated = rows.size() > limit;
        List<Positions.Position> shown = truncated ? rows.subList(0, limit) : rows;
        Set<UUID> memberIds = new HashSet<>();
        Set<UUID> officerIds = new HashSet<>();
        shown.forEach(p -> {
            memberIds.add(p.memberId());
            officerIds.add(p.officerUserId());
        });
        Map<UUID, String[]> members = queries.members(memberIds);
        Map<UUID, String> officers = queries.userNames(officerIds);
        Map<UUID, String> products = queries.productNames();
        Map<UUID, String> branches = queries.branchNames();
        List<Column> columns = arrears
                ? List.of(
                        LOAN,
                        MEMBER,
                        NAME,
                        col("days_past_due", "Days past due", "days"),
                        col("bucket", "Bucket", "text"),
                        col("arrears_minor", "Arrears", "money"),
                        col("principal_outstanding_minor", "Principal outstanding", "money"),
                        col("officer", "Officer", "text"))
                : List.of(
                        LOAN,
                        MEMBER,
                        col("product", "Product", "text"),
                        col("branch", "Branch", "text"),
                        col("officer", "Officer", "text"),
                        col("principal_outstanding_minor", "Principal outstanding", "money"),
                        col("interest_outstanding_minor", "Interest receivable", "money"),
                        col("days_past_due", "Days past due", "days"));
        Function<Positions.Position, List<String>> render = p -> {
            String[] m = members.getOrDefault(p.memberId(), new String[] {"", ""});
            String officer = officers.getOrDefault(p.officerUserId(), InsightsService.staffLabel(p.officerUserId()));
            return arrears
                    ? List.of(
                            p.loanNo(),
                            m[0],
                            m[1],
                            String.valueOf(p.daysPastDue()),
                            p.bucket(),
                            String.valueOf(p.arrearsMinor()),
                            String.valueOf(p.principalOutstandingMinor()),
                            officer)
                    : List.of(
                            p.loanNo(),
                            m[0],
                            products.getOrDefault(p.productId(), ""),
                            branches.getOrDefault(p.branchId(), ""),
                            officer,
                            String.valueOf(p.principalOutstandingMinor()),
                            String.valueOf(p.interestOutstandingMinor()),
                            String.valueOf(p.daysPastDue()));
        };
        return new TableResponse(
                key,
                arrears ? "Loans in arrears" : "Active loans",
                s.currency(),
                columns,
                shown.stream().map(render).toList(),
                truncated);
    }

    static String groupValue(Positions.Position p, String group) {
        return switch (group) {
            case "product" -> String.valueOf(p.productId());
            case "branch" -> String.valueOf(p.branchId());
            case "officer" -> String.valueOf(p.officerUserId());
            case "bucket" -> p.bucket();
            default -> throw ApiException.notFound();
        };
    }

    private TableResponse staffTable(Scope s, ZoneId zone, int limit) {
        List<Column> columns = List.of(
                col("name", "Staff", "text"),
                col("members_registered", "Members registered", "count"),
                col("applications_submitted", "Applications submitted", "count"),
                col("appraisals", "Appraisals", "count"),
                col("disbursements", "Disbursements", "count"),
                col("repayments_recorded", "Repayments recorded", "count"));
        List<List<String>> rows = queries.staffActivity(s, zone).stream()
                .limit(limit)
                .map(r -> List.of(
                        r[1] == null ? InsightsService.staffLabel((UUID) r[0]) : (String) r[1],
                        String.valueOf(r[2]),
                        String.valueOf(r[3]),
                        String.valueOf(r[4]),
                        String.valueOf(r[5]),
                        String.valueOf(r[6])))
                .toList();
        return new TableResponse("staff_activity", "Staff activity", s.currency(), columns, rows, false);
    }

    private static LocalDate dateParam(Map<String, String> params, String name) {
        String v = params.get(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.rule("invalid_parameter", "The " + name + " parameter is a date (yyyy-mm-dd).");
        }
    }

    private static int intParam(Map<String, String> params, String name, int fallback) {
        String v = params.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.rule("invalid_parameter", "The " + name + " parameter is a whole number.");
        }
    }
}
