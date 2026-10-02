package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.LoanApi.LoanListItem;
import com.rincoltech.bms.lending.loans.internal.LoanApi.StatusChange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for loans and their guarantors, pledges and history. Row-level security supplies the tenant predicate. */
@Repository
class LoanRepository {

    /**
     * Statuses that end a loan's hold on its collateral (FR-COL-04): the pledges are released when
     * the loan reaches one. Written off is not among them (the collateral is being recovered), and
     * a restructured loan hands its pledges to the loan that replaces it when restructure is built.
     */
    static final Set<String> RELEASES_PLEDGES = Set.of("cancelled", "rejected", "closed");

    private final JdbcClient jdbc;

    LoanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The loan row as stored, before the response adds names and the schedule. */
    record Loan(
            UUID id,
            String loanNo,
            UUID branchId,
            UUID memberId,
            UUID productVersionId,
            UUID officerUserId,
            String status,
            String channel,
            String purposeCategory,
            String purposeText,
            String currency,
            long requestedPrincipalMinor,
            int requestedTermCount,
            Long approvedPrincipalMinor,
            Integer approvedTermCount,
            String termUnit,
            String interestMethod,
            int interestRateBp,
            String rateUnit,
            String repaymentPattern,
            String instalmentFrequency,
            LocalDate proposedDisbursementDate,
            UUID submittedBy,
            Instant submittedAt,
            UUID appraisedBy,
            UUID approvedBy,
            Instant approvedAt,
            String rejectedReason,
            String cancelledReason,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            int version) {}

    Optional<Loan> find(UUID id) {
        return jdbc.sql("SELECT * FROM lending_loans WHERE id = ?")
                .param(id)
                .query(LoanRepository::map)
                .optional();
    }

    Optional<Loan> lock(UUID id) {
        return jdbc.sql("SELECT * FROM lending_loans WHERE id = ? FOR UPDATE")
                .param(id)
                .query(LoanRepository::map)
                .optional();
    }

    void insert(Loan l) {
        jdbc.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, purpose_text, currency,
                            requested_principal_minor, requested_term_count, term_unit, interest_method,
                            interest_rate_bp, rate_unit, repayment_pattern, instalment_frequency,
                            proposed_disbursement_date, created_by)
                        VALUES (:id, current_setting('app.tenant_id')::uuid, :branchId, :loanNo, :memberId,
                            :productVersionId, :officerUserId, :status, :channel, :purposeCategory, :purposeText,
                            :currency, :requestedPrincipalMinor, :requestedTermCount, :termUnit, :interestMethod,
                            :interestRateBp, :rateUnit, :repaymentPattern, :instalmentFrequency,
                            :proposedDisbursementDate, :createdBy)
                        """).paramSource(l).update();
    }

    void updateDraft(
            UUID id, long principal, int term, String purposeCategory, String purposeText, LocalDate proposed) {
        jdbc.sql("""
                        UPDATE lending_loans SET requested_principal_minor = ?, requested_term_count = ?,
                            purpose_category = ?, purpose_text = ?, proposed_disbursement_date = ?,
                            updated_at = now(), version = version + 1
                        WHERE id = ?
                        """)
                .params(principal, term, purposeCategory, purposeText, proposed, id)
                .update();
    }

    /** Bumps the version when a child list (guarantors, pledges) changes, so the ETag moves too. */
    void touch(UUID id) {
        jdbc.sql("UPDATE lending_loans SET updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id)
                .update();
    }

    /** One status move with its timestamps and actor columns; the history row is written alongside. */
    void move(UUID id, String from, String to, UUID by, String reason, Map<String, Object> columns) {
        StringBuilder sql =
                new StringBuilder("UPDATE lending_loans SET status = :to, updated_at = now(), version = version + 1");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("to", to);
        p.put("id", id);
        columns.forEach((column, value) -> {
            if (value == NOW) {
                sql.append(", ").append(column).append(" = now()");
            } else {
                sql.append(", ").append(column).append(" = :").append(column);
                p.put(column, value);
            }
        });
        sql.append(" WHERE id = :id");
        jdbc.sql(sql.toString()).params(p).update();
        if (RELEASES_PLEDGES.contains(to)) {
            jdbc.sql("UPDATE lending_loan_collateral SET released_at = now(), updated_at = now(), version = version + 1"
                            + " WHERE loan_id = ? AND released_at IS NULL")
                    .param(id)
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO lending_loan_status_history (id, tenant_id, loan_id, from_status, to_status, changed_by, reason)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        """).params(UUID.randomUUID(), id, from, to, by, reason).update();
    }

    /** Marker for {@link #move}: set the column to the database's now(). */
    static final Object NOW = new Object();

    void history(UUID loanId, String to, UUID by) {
        jdbc.sql("""
                        INSERT INTO lending_loan_status_history (id, tenant_id, loan_id, from_status, to_status, changed_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, NULL, ?, ?)
                        """).params(UUID.randomUUID(), loanId, to, by).update();
    }

    List<StatusChange> historyOf(UUID loanId) {
        return jdbc.sql("""
                        SELECT from_status, to_status, changed_by, reason, created_at FROM lending_loan_status_history
                         WHERE loan_id = ? ORDER BY created_at, id
                        """)
                .param(loanId)
                .query((rs, n) -> new StatusChange(
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getObject("changed_by", UUID.class),
                        rs.getString("reason"),
                        instant(rs.getTimestamp("created_at"))))
                .list();
    }

    record GuarantorRow(UUID memberId, long amountMinor, String relationship, String status) {}

    List<GuarantorRow> guarantors(UUID loanId) {
        return jdbc.sql("""
                        SELECT guarantor_member_id, guaranteed_amount_minor, relationship, status
                          FROM lending_loan_guarantors WHERE loan_id = ? ORDER BY created_at, id
                        """)
                .param(loanId)
                .query((rs, n) -> new GuarantorRow(
                        rs.getObject("guarantor_member_id", UUID.class),
                        rs.getLong("guaranteed_amount_minor"),
                        rs.getString("relationship"),
                        rs.getString("status")))
                .list();
    }

    void replaceGuarantors(UUID loanId, List<GuarantorRow> rows) {
        jdbc.sql("DELETE FROM lending_loan_guarantors WHERE loan_id = ?")
                .param(loanId)
                .update();
        for (GuarantorRow g : rows) {
            jdbc.sql("""
                            INSERT INTO lending_loan_guarantors (id, tenant_id, loan_id, guarantor_member_id,
                                guaranteed_amount_minor, relationship, status)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 'active')
                            """)
                    .params(UUID.randomUUID(), loanId, g.memberId(), g.amountMinor(), g.relationship())
                    .update();
        }
    }

    record PledgeRow(UUID collateralId, long pledgedValueMinor) {}

    List<PledgeRow> pledges(UUID loanId) {
        return jdbc.sql("""
                        SELECT collateral_id, pledged_value_minor FROM lending_loan_collateral
                         WHERE loan_id = ? AND released_at IS NULL ORDER BY created_at, id
                        """)
                .param(loanId)
                .query((rs, n) ->
                        new PledgeRow(rs.getObject("collateral_id", UUID.class), rs.getLong("pledged_value_minor")))
                .list();
    }

    void replacePledges(UUID loanId, List<PledgeRow> rows) {
        jdbc.sql("DELETE FROM lending_loan_collateral WHERE loan_id = ?")
                .param(loanId)
                .update();
        for (PledgeRow p : rows) {
            jdbc.sql("""
                            INSERT INTO lending_loan_collateral (id, tenant_id, loan_id, collateral_id, pledged_value_minor)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                            """)
                    .params(UUID.randomUUID(), loanId, p.collateralId(), p.pledgedValueMinor())
                    .update();
        }
    }

    /**
     * True when another loan holds an unreleased pledge on this item (FR-COL-01, FR-COL-04). The
     * caller holds the item's row lock, so the answer cannot change under it.
     */
    boolean pledgedElsewhere(UUID collateralId, UUID exceptLoanId) {
        return jdbc.sql("""
                        SELECT count(*) FROM lending_loan_collateral lc
                         WHERE lc.collateral_id = :collateral AND lc.released_at IS NULL
                           AND lc.loan_id IS DISTINCT FROM CAST(:except AS uuid)
                        """)
                        .param("collateral", collateralId)
                        .param("except", exceptLoanId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** A loan as it counts towards someone's exposure (FR-ORG-05). */
    record ExposureLoan(
            UUID loanId, UUID memberId, String loanNo, String status, long outstandingMinor, int daysPastDue) {}

    private static final String EXPOSURE = """
            SELECT l.id, l.member_id, l.loan_no, l.status, l.days_past_due,
                   l.principal_outstanding_minor + l.interest_outstanding_minor + l.fees_outstanding_minor
                       + l.penalties_outstanding_minor AS outstanding
              FROM lending_loans l
            """;

    /** Statuses that count as exposure: applied for and not yet finished (drafts do not count). */
    static final List<String> EXPOSED = List.of("submitted", "appraised", "approved", "active");

    List<ExposureLoan> exposureOfMembers(List<UUID> memberIds, UUID exceptLoanId) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(EXPOSURE + " WHERE l.member_id IN (:members) AND l.status IN (:statuses)"
                        + " AND l.id IS DISTINCT FROM CAST(:except AS uuid) ORDER BY l.created_at")
                .param("members", memberIds)
                .param("statuses", EXPOSED)
                .param("except", exceptLoanId)
                .query(LoanRepository::exposure)
                .list();
    }

    /** Loans this member guarantees (active guarantees on exposed loans). */
    List<ExposureLoan> guaranteedBy(UUID memberId) {
        return jdbc.sql(EXPOSURE + " JOIN lending_loan_guarantors g ON g.loan_id = l.id"
                        + " WHERE g.guarantor_member_id = :member AND g.status = 'active' AND l.status IN (:statuses)"
                        + " ORDER BY l.created_at")
                .param("member", memberId)
                .param("statuses", EXPOSED)
                .query(LoanRepository::exposure)
                .list();
    }

    /** Closed and written-off loans of a member, for the repayment history component. */
    record History(int closed, int writtenOff) {}

    History historyCounts(UUID memberId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'closed') AS closed,
                               count(*) FILTER (WHERE status = 'written_off') AS written_off
                          FROM lending_loans WHERE member_id = ?
                        """)
                .param(memberId)
                .query((rs, n) -> new History(rs.getInt("closed"), rs.getInt("written_off")))
                .single();
    }

    void insertAppraisal(
            UUID id,
            UUID loanId,
            UUID by,
            Long income,
            Long obligations,
            String notes,
            CreditScore.Result r,
            String componentsJson,
            String exposureJson,
            String weightsJson) {
        jdbc.sql("""
                        INSERT INTO lending_loan_appraisals (id, tenant_id, loan_id, appraised_by,
                            declared_monthly_income_minor, monthly_obligations_minor, visit_notes, score, band,
                            components, flags, exposure, weights, recommendation)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                                CAST(? AS text[]), CAST(? AS jsonb), CAST(? AS jsonb), ?)
                        """)
                .params(
                        id,
                        loanId,
                        by,
                        income,
                        obligations,
                        notes,
                        r.score(),
                        r.band(),
                        componentsJson,
                        "{" + String.join(",", r.flags()) + "}",
                        exposureJson,
                        weightsJson,
                        r.recommendation())
                .update();
    }

    record AppraisalRow(
            UUID id,
            UUID appraisedBy,
            Long declaredMonthlyIncomeMinor,
            Long monthlyObligationsMinor,
            String visitNotes,
            int score,
            String band,
            String componentsJson,
            List<String> flags,
            String exposureJson,
            String weightsJson,
            String recommendation,
            Instant createdAt) {}

    List<AppraisalRow> appraisals(UUID loanId) {
        return jdbc.sql("""
                        SELECT id, appraised_by, declared_monthly_income_minor, monthly_obligations_minor, visit_notes,
                               score, band, components::text AS components, flags, exposure::text AS exposure,
                               weights::text AS weights, recommendation, created_at
                          FROM lending_loan_appraisals WHERE loan_id = ? ORDER BY created_at DESC, id
                        """)
                .param(loanId)
                .query((rs, n) -> new AppraisalRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("appraised_by", UUID.class),
                        rs.getObject("declared_monthly_income_minor", Long.class),
                        rs.getObject("monthly_obligations_minor", Long.class),
                        rs.getString("visit_notes"),
                        rs.getInt("score"),
                        rs.getString("band"),
                        rs.getString("components"),
                        List.of((String[]) rs.getArray("flags").getArray()),
                        rs.getString("exposure"),
                        rs.getString("weights"),
                        rs.getString("recommendation"),
                        instant(rs.getTimestamp("created_at"))))
                .list();
    }

    /** FR-ORG-07: the member's active loans. */
    int activeLoans(UUID memberId) {
        return jdbc.sql("SELECT count(*) FROM lending_loans WHERE member_id = ? AND status = 'active'")
                .param(memberId)
                .query(Integer.class)
                .single();
    }

    /** FR-ORG-08: approved loans still waiting for disbursement after the validity period, locked. */
    List<Loan> overdueApprovals(int validityDays) {
        return jdbc.sql("SELECT * FROM lending_loans WHERE status = 'approved'"
                        + " AND approved_at < now() - make_interval(days => ?) FOR UPDATE")
                .param(validityDays)
                .query(LoanRepository::map)
                .list();
    }

    /** A re-appraisal of an appraised loan: the latest appraiser, no status change. */
    void reappraised(UUID loanId, UUID by) {
        jdbc.sql("UPDATE lending_loans SET appraised_by = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                .params(by, loanId)
                .update();
    }

    private static ExposureLoan exposure(ResultSet rs, int n) throws SQLException {
        return new ExposureLoan(
                rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("loan_no"),
                rs.getString("status"),
                rs.getLong("outstanding"),
                rs.getInt("days_past_due"));
    }

    /** One page ordered by creation; {@code branchIds} already intersected with the scope, null for every branch. */
    List<LoanListItem> page(
            List<UUID> branchIds,
            List<String> statuses,
            UUID memberId,
            UUID officerId,
            UUID productId,
            Instant afterCreated,
            UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("SELECT l.* FROM lending_loans l WHERE true");
        Map<String, Object> p = new LinkedHashMap<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND l.branch_id IN (:branchIds)");
            p.put("branchIds", branchIds);
        }
        if (!statuses.isEmpty()) {
            sql.append(" AND l.status IN (:statuses)");
            p.put("statuses", statuses);
        }
        if (memberId != null) {
            sql.append(" AND l.member_id = :memberId");
            p.put("memberId", memberId);
        }
        if (officerId != null) {
            sql.append(" AND l.officer_user_id = :officerId");
            p.put("officerId", officerId);
        }
        if (productId != null) {
            sql.append(
                    " AND l.product_version_id IN (SELECT id FROM lending_loan_product_versions WHERE product_id = :productId)");
            p.put("productId", productId);
        }
        if (afterCreated != null) {
            sql.append(" AND (l.created_at, l.id) > (:afterCreated, :afterId)");
            p.put("afterCreated", Timestamp.from(afterCreated));
            p.put("afterId", afterId);
        }
        sql.append(" ORDER BY l.created_at, l.id LIMIT :limit");
        p.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> new LoanListItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("loan_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("member_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("purpose_category"),
                        rs.getLong("requested_principal_minor"),
                        rs.getInt("requested_term_count"),
                        rs.getString("currency"),
                        rs.getObject("officer_user_id", UUID.class),
                        instant(rs.getTimestamp("created_at"))))
                .list();
    }

    private static Loan map(ResultSet rs, int n) throws SQLException {
        return new Loan(
                rs.getObject("id", UUID.class),
                rs.getString("loan_no"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getObject("product_version_id", UUID.class),
                rs.getObject("officer_user_id", UUID.class),
                rs.getString("status"),
                rs.getString("channel"),
                rs.getString("purpose_category"),
                rs.getString("purpose_text"),
                rs.getString("currency"),
                rs.getLong("requested_principal_minor"),
                rs.getInt("requested_term_count"),
                rs.getObject("approved_principal_minor", Long.class),
                rs.getObject("approved_term_count", Integer.class),
                rs.getString("term_unit"),
                rs.getString("interest_method"),
                rs.getInt("interest_rate_bp"),
                rs.getString("rate_unit"),
                rs.getString("repayment_pattern"),
                rs.getString("instalment_frequency"),
                rs.getObject("proposed_disbursement_date", LocalDate.class),
                rs.getObject("submitted_by", UUID.class),
                instant(rs.getTimestamp("submitted_at")),
                rs.getObject("appraised_by", UUID.class),
                rs.getObject("approved_by", UUID.class),
                instant(rs.getTimestamp("approved_at")),
                rs.getString("rejected_reason"),
                rs.getString("cancelled_reason"),
                rs.getObject("created_by", UUID.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
