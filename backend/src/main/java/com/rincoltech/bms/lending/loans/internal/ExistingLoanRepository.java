package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.tenancy.GoLiveDate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL of the loans brought in from a paper register (ADR-071, migration V91). Row-level security supplies the tenant. */
@Repository
class ExistingLoanRepository implements GoLiveDate.Dependent {

    private final JdbcClient jdbc;

    ExistingLoanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** ADR-071: the business has a lending journal entry of its own or an approved bring-in. */
    @Override
    public boolean dependsOnGoLive() {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM journal_entries WHERE source_module = 'lending')
                            OR EXISTS (SELECT 1 FROM lending_loans WHERE brought_in_on IS NOT NULL)
                        """).query(Boolean.class).single();
    }

    /** What a loan keeps of its bring-in; every field is null on a loan that was not brought in. */
    record Facts(
            LocalDate goLiveOn,
            LocalDate broughtInOn,
            String registerRef,
            Long agreedTotalMinor,
            UUID openingEntryId,
            LocalDate disbursementDate) {}

    /** The loan row in {@code brought_in}, terms already copied by the caller; the maker is the submitter. */
    void insert(
            LoanRepository.Loan l,
            LocalDate disbursementDate,
            LocalDate goLiveOn,
            String registerRef,
            Long agreedTotalMinor) {
        jdbc.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, currency, requested_principal_minor,
                            requested_term_count, approved_principal_minor, approved_term_count, term_unit,
                            interest_method, interest_rate_bp, rate_unit, repayment_pattern, instalment_frequency,
                            proposed_disbursement_date, submitted_by, submitted_at, created_by, go_live_on, register_ref,
                            agreed_total_minor)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, 'brought_in', ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?)
                        """)
                .params(
                        l.id(),
                        l.branchId(),
                        l.loanNo(),
                        l.memberId(),
                        l.productVersionId(),
                        l.officerUserId(),
                        l.channel(),
                        l.purposeCategory(),
                        l.currency(),
                        l.requestedPrincipalMinor(),
                        l.requestedTermCount(),
                        l.requestedPrincipalMinor(),
                        l.requestedTermCount(),
                        l.termUnit(),
                        l.interestMethod(),
                        l.interestRateBp(),
                        l.rateUnit(),
                        l.repaymentPattern(),
                        l.instalmentFrequency(),
                        disbursementDate,
                        l.createdBy(),
                        l.createdBy(),
                        goLiveOn,
                        registerRef,
                        agreedTotalMinor)
                .update();
    }

    void insertLines(UUID loanId, List<BringIn.Line> lines) {
        for (BringIn.Line line : lines) {
            jdbc.sql("""
                            INSERT INTO lending_brought_in_repayments (id, tenant_id, loan_id, line_no, value_date,
                                amount_minor, payment_method_key, note)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            loanId,
                            line.lineNo(),
                            line.valueDate(),
                            line.amountMinor(),
                            line.paymentMethodKey(),
                            line.note())
                    .update();
        }
    }

    /** The repayments as entered, in entry order. */
    List<BringIn.Line> lines(UUID loanId) {
        return jdbc.sql("""
                        SELECT line_no, value_date, amount_minor, payment_method_key, note
                          FROM lending_brought_in_repayments WHERE loan_id = ? ORDER BY line_no
                        """)
                .param(loanId)
                .query((rs, n) -> new BringIn.Line(
                        rs.getInt(1),
                        rs.getObject(2, LocalDate.class),
                        rs.getLong(3),
                        rs.getString(4),
                        rs.getString(5)))
                .list();
    }

    Optional<Facts> facts(UUID loanId) {
        return jdbc.sql("""
                        SELECT go_live_on, brought_in_on, register_ref, agreed_total_minor, opening_entry_id,
                               coalesce(disbursed_on, proposed_disbursement_date) AS disbursement_date
                          FROM lending_loans WHERE id = ?
                        """)
                .param(loanId)
                .query((rs, n) -> new Facts(
                        rs.getObject(1, LocalDate.class),
                        rs.getObject(2, LocalDate.class),
                        rs.getString(3),
                        rs.getObject(4, Long.class),
                        rs.getObject(5, UUID.class),
                        rs.getObject(6, LocalDate.class)))
                .optional();
    }

    /** The checker's approval: the bring-in date, the opening entry (if principal was owed) and the checker. */
    void approved(UUID loanId, LocalDate broughtInOn, UUID openingEntryId, UUID checkerId) {
        jdbc.sql("""
                        UPDATE lending_loans SET brought_in_on = ?, opening_entry_id = ?, approved_by = ?, approved_at = now(),
                            updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).params(broughtInOn, openingEntryId, checkerId, loanId).update();
    }

    /**
     * What stops the reversal of a bring-in (ADR-071 decision 9): a transaction made in the app (not historic) or a penalty
     * charge on the loan. Null when there is none.
     */
    String activitySince(UUID loanId) {
        return jdbc.sql("""
                        SELECT CASE
                                 WHEN EXISTS (SELECT 1 FROM lending_loan_transactions
                                               WHERE loan_id = :id AND NOT is_historic) THEN 'transaction'
                                 WHEN EXISTS (SELECT 1 FROM lending_loan_charges WHERE loan_id = :id) THEN 'penalty'
                               END
                        """).param("id", loanId).query(String.class).optional().orElse(null);
    }

    /** A reversed bring-in owes nothing: the loan's figures go to zero with its status cancelled (ADR-071 decision 9). */
    void cleared(UUID loanId) {
        jdbc.sql("""
                        UPDATE lending_loans SET principal_outstanding_minor = 0, interest_outstanding_minor = 0,
                            fees_outstanding_minor = 0, penalties_outstanding_minor = 0, arrears_minor = 0,
                            days_past_due = 0, next_due_date = NULL, total_paid_minor = 0, last_repayment_on = NULL,
                            updated_at = now(), version = version + 1
                         WHERE id = ?
                        """).param(loanId).update();
    }

    /** A request asked again checks against today's go-live date, which the loan then keeps. */
    void goLiveOn(UUID loanId, LocalDate goLiveOn) {
        jdbc.sql("UPDATE lending_loans SET go_live_on = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                .params(goLiveOn, loanId)
                .update();
    }

    /** True when a live loan (not cancelled or rejected) already carries this register number, in any case. */
    boolean registerRefTaken(String registerRef) {
        return jdbc.sql("""
                        SELECT count(*) FROM lending_loans
                         WHERE lower(register_ref) = lower(?) AND status NOT IN ('cancelled', 'rejected')
                        """).param(registerRef).query(Long.class).single() > 0;
    }

    /** The pending request of the loan of this type ({@code loan_bring_in} or its reversal), if one waits. */
    Optional<UUID> pendingApproval(UUID loanId, String actionType) {
        return jdbc.sql("""
                        SELECT id FROM approval_requests
                         WHERE subject_id = ? AND action_type = ? AND status = 'pending' AND expires_at > now()
                        """).params(loanId, actionType).query(UUID.class).optional();
    }

    /** The loans brought in from the register in the given branches (null: all), newest first, at most {@code limit}. */
    List<ExistingLoanApi.ExistingLoanSummary> list(List<UUID> branchIds, List<String> statuses, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.loan_no, l.status, l.member_id, l.approved_principal_minor,
                       coalesce(l.disbursed_on, l.proposed_disbursement_date) AS disbursement_date, l.register_ref,
                       (SELECT r.id FROM approval_requests r
                         WHERE r.subject_id = l.id AND r.action_type = :type AND r.status = 'pending'
                           AND r.expires_at > now()) AS approval_id
                  FROM lending_loans l
                 WHERE l.go_live_on IS NOT NULL
                """);
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("type", BringInAction.TYPE);
        if (branchIds != null) {
            sql.append(" AND l.branch_id = ANY (:branches)");
            params.put("branches", branchIds.toArray(UUID[]::new));
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND l.status = ANY (:statuses)");
            params.put("statuses", statuses.toArray(String[]::new));
        }
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new ExistingLoanApi.ExistingLoanSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("loan_no"),
                        rs.getString("status"),
                        rs.getObject("member_id", UUID.class),
                        null,
                        rs.getLong("approved_principal_minor"),
                        rs.getObject("disbursement_date", LocalDate.class),
                        rs.getString("register_ref"),
                        rs.getObject("approval_id", UUID.class)))
                .list();
    }

    /** Decimals of a currency, for amounts typed in major units in the import. */
    int currencyExponent(String currency) {
        return jdbc.sql("SELECT exponent FROM currencies WHERE code = ?")
                .param(currency)
                .query(Integer.class)
                .single();
    }
}
