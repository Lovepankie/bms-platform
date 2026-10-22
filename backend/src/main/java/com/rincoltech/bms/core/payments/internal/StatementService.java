package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.PaymentMethods;
import com.rincoltech.bms.core.payments.internal.StatementApi.RowView;
import com.rincoltech.bms.core.payments.internal.StatementApi.StatementDetail;
import com.rincoltech.bms.core.payments.internal.StatementApi.StatementList;
import com.rincoltech.bms.core.payments.internal.StatementApi.StatementView;
import com.rincoltech.bms.core.payments.internal.StatementCsv.Row;
import com.rincoltech.bms.core.payments.internal.StatementMatcher.Match;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The merchant statement upload (spec 3.9, FR-PAY-06 and FR-PAY-11): the operator's CSV is read whole or not at all, every line
 * is matched to the tenant's own orders ({@link StatementMatcher}), and the fee of each line that matches cleanly is posted to the
 * gateway charges account (debit {@code gateway_charges}, credit the account the {@code gateway} method is mapped to at the
 * order's branch), once per order however many statements name it: by a row recorded against the order, by a unique index on
 * it, and by the journal's own idempotency key. The same file twice is one statement (its SHA-256 is unique per tenant), and two
 * uploads at once meet at an advisory lock. A line that does not match cleanly posts nothing and is listed for a person.
 *
 * <p>Statement lines that match no order are reported, not booked: the lender's own merchant account can receive money for things
 * that never went through this system (ADR-035 amendment, decision 52).
 */
@Service
class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);
    static final String READ = "core.payments.manage";
    private static final int ROW_LIMIT = 1000;
    private static final String GATEWAY_CHARGES = "gateway_charges";
    private static final String CLEARING = "gateway_clearing";

    /** The result of an upload: the statement and whether it was already there. */
    record Uploaded(StatementDetail detail, boolean replayed) {}

    private final JdbcClient jdbc;
    private final StatementMatcher matcher;
    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;
    private final PaymentMethods methods;
    private final AuditLog audit;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final Approvals approvals;
    private final int feeAutoMaxBps;
    private final java.time.Duration lockWait;

    StatementService(
            JdbcClient jdbc,
            StatementMatcher matcher,
            LedgerPosting ledger,
            LedgerAccounts accounts,
            PaymentMethods methods,
            AuditLog audit,
            CurrentTenant tenant,
            BusinessClock clock,
            Approvals approvals,
            @Value("${bms.payments.statement-fee-auto-max-bps:500}") int feeAutoMaxBps,
            @Value("${bms.payments.reconcile-lock-wait:30s}") java.time.Duration lockWait) {
        if (feeAutoMaxBps < 0 || feeAutoMaxBps > 10_000) {
            throw new IllegalArgumentException("bms.payments.statement-fee-auto-max-bps must be 0 to 10000");
        }
        this.approvals = approvals;
        this.feeAutoMaxBps = feeAutoMaxBps;
        this.lockWait = lockWait;
        this.jdbc = jdbc;
        this.matcher = matcher;
        this.ledger = ledger;
        this.accounts = accounts;
        this.methods = methods;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional
    Uploaded upload(String fileName, byte[] bytes, LocalDate coveredFrom, LocalDate coveredTo) {
        UUID by = PaymentsScope.requireEverywhere(READ).userId();
        String currency = tenant.profile().currency();
        LocalDate today = clock.today(tenant.profile().timezone());
        List<Row> rows = StatementCsv.parse(bytes, currency, today);
        checkCovered(coveredFrom, coveredTo, today);
        String sha = sha256(bytes);
        UUID tenantId = tenant.profile().id();
        // Uploads of one business go one after the other: two of one file replay, and two overlapping files cannot both
        // post the
        // fee of an order.
        BusinessLock.take(jdbc, "payments:statement:" + tenantId, lockWait);
        Optional<UUID> existing = jdbc.sql("SELECT id FROM payment_statements WHERE content_sha256 = ?")
                .param(sha)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return new Uploaded(detail(existing.get()), true);
        }
        List<Match> matches = matcher.match(rows.stream()
                .map(r -> new StatementMatcher.Line(
                        r.orderTrackingId(), r.confirmationCode(), r.amountMinor(), r.currency()))
                .toList());
        UUID statementId = UUID.randomUUID();
        LocalDate from =
                rows.stream().map(Row::paidOn).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = rows.stream().map(Row::paidOn).max(LocalDate::compareTo).orElseThrow();
        // An export may begin or end part way through a day, so by default only the days strictly between its first and
        // last
        // line count as covered; the uploader may state the days the export is complete for.
        LocalDate covFrom = coveredFrom != null ? coveredFrom : from.plusDays(1);
        LocalDate covTo = coveredFrom != null ? coveredTo : to.minusDays(1);
        boolean covers = !covFrom.isAfter(covTo);
        // One order is named by one line: two lines that resolve to the same order would post its fee twice, and say
        // nothing
        // true about which is right.
        Map<UUID, Row> named = new java.util.HashMap<>();
        List<FieldProblem> twice = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Match m = matches.get(i);
            if (m.intent() == null) {
                continue;
            }
            Row first = named.putIfAbsent(m.intent().id(), rows.get(i));
            if (first != null) {
                twice.add(new FieldProblem(
                        "line " + rows.get(i).line(),
                        "same_order",
                        "Line " + rows.get(i).line() + " and line " + first.line() + " name the same order."));
            }
        }
        if (!twice.isEmpty()) {
            throw ApiException.validation(twice.size() > 20 ? twice.subList(0, 20) : twice);
        }
        int matched = 0;
        int different = 0;
        int unmatched = 0;
        for (Match m : matches) {
            switch (m.status()) {
                case "matched" -> matched++;
                case "unmatched" -> unmatched++;
                default -> different++;
            }
        }
        // The statement row goes in first because its lines point at it; its totals are written once, with the fees,
        // below.
        List<UUID> feeEntries = new ArrayList<>();
        List<UUID> feeApprovals = new ArrayList<>();
        long fees = 0;
        int heldFees = 0;
        List<UUID> rowIds = new ArrayList<>();
        java.util.Set<UUID> posted = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Match m = matches.get(i);
            UUID rowId = UUID.randomUUID();
            UUID entry = null;
            UUID approval = null;
            if (r.feeMinor() > 0
                    && "matched".equals(m.status())
                    && !feeAlreadyPosted(m.intent().id())
                    && !feeAwaitingApproval(m.intent().id())
                    && posted.add(m.intent().id())) {
                // A fee at or below the automatic rate of the line's amount is posted at once; one above it is a money
                // decision for a second person (ADR-035 decision 65), so a file one person wrote cannot write a
                // clearing
                // balance off to charges alone.
                if (withinAutoRate(r.feeMinor(), r.amountMinor())) {
                    entry = postFee(
                            m.intent().branchId(),
                            m.intent().merchantReference(),
                            m.intent().id(),
                            r.paidOn(),
                            r.feeMinor(),
                            r.currency());
                    fees += r.feeMinor();
                } else {
                    approval = holdFee(statementId, rowId, r, m.intent());
                    heldFees++;
                }
            }
            feeEntries.add(entry);
            feeApprovals.add(approval);
            rowIds.add(rowId);
        }
        jdbc.sql("""
                        INSERT INTO payment_statements (id, tenant_id, uploaded_by, file_name, content_sha256, row_count,
                            period_from, period_to, currency, matched_count, different_count, unmatched_count, fees_posted_minor,
                            covered_from, covered_to)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        statementId,
                        by,
                        safeName(fileName),
                        sha,
                        rows.size(),
                        Date.valueOf(from),
                        Date.valueOf(to),
                        currency,
                        matched,
                        different,
                        unmatched,
                        fees,
                        covers ? Date.valueOf(covFrom) : null,
                        covers ? Date.valueOf(covTo) : null)
                .update();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Match m = matches.get(i);
            jdbc.sql("""
                            INSERT INTO payment_statement_rows (id, tenant_id, statement_id, row_no, order_tracking_id,
                                confirmation_code, amount_minor, fee_minor, settled_minor, currency, paid_on, payment_method,
                                match_status, intent_id, fee_entry_id, fee_approval_id)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            rowIds.get(i),
                            statementId,
                            r.rowNo(),
                            r.orderTrackingId(),
                            r.confirmationCode(),
                            r.amountMinor(),
                            r.feeMinor(),
                            r.settledMinor(),
                            r.currency(),
                            Date.valueOf(r.paidOn()),
                            r.paymentMethod(),
                            m.status(),
                            m.intent() == null ? null : m.intent().id(),
                            feeEntries.get(i),
                            feeApprovals.get(i))
                    .update();
        }
        audit.record(AuditLog.Entry.created(
                "core.payments.statement.uploaded",
                "payment_statement",
                statementId,
                null,
                Map.of(
                        "rows", rows.size(),
                        "covers", covers ? covFrom + " to " + covTo : "none",
                        "matched", matched,
                        "different", different,
                        "unmatched", unmatched,
                        "fees_posted_minor", fees,
                        "fees_held_for_approval", heldFees,
                        "period_from", from.toString(),
                        "period_to", to.toString(),
                        "content_sha256", sha)));
        log.info("payment statement {} uploaded: {} lines, {} matched", statementId, rows.size(), matched);
        return new Uploaded(detail(statementId), false);
    }

    private boolean feeAlreadyPosted(UUID intentId) {
        return jdbc.sql("SELECT count(*) FROM payment_statement_rows WHERE intent_id = ? AND fee_entry_id IS NOT NULL")
                        .param(intentId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private UUID postFee(
            UUID branchId, String merchantReference, UUID intentId, LocalDate paidOn, long feeMinor, String currency) {
        LedgerAccounts.Account charges = accounts.bySystemKey(GATEWAY_CHARGES)
                .orElseThrow(() -> ApiException.rule(
                        "lending_chart_missing",
                        "The tenant has no active account with system key " + GATEWAY_CHARGES + "."));
        LedgerAccounts.Account paidFrom = methods.accountRefIfMapped("gateway", branchId)
                .flatMap(accounts::byRef)
                .or(() -> accounts.bySystemKey(CLEARING))
                .orElseThrow(() -> ApiException.rule(
                        "lending_chart_missing", "The tenant has no active account with system key " + CLEARING + "."));
        if (!charges.currency().equals(currency) || !paidFrom.currency().equals(currency)) {
            throw ApiException.rule("currency_mismatch", "The fee's accounts are not in the statement's currency.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate date = ledger.periodOpen(paidOn) && !paidOn.isAfter(today) ? paidOn : today;
        LedgerPosting.PostedEntry entry = ledger.post(new EntryRequest(
                branchId,
                date,
                "SF-" + merchantReference,
                "Payment provider fee, statement line",
                UnallocatedReceipts.SOURCE_MODULE,
                "statement_fee",
                intentId,
                "payments.statement.fee:" + intentId,
                List.of(
                        new Line(charges.id(), feeMinor, 0, currency, null, null),
                        new Line(paidFrom.id(), 0, feeMinor, currency, null, null))));
        return entry.entryId();
    }

    /** The fee is at or below the automatic rate of the line's amount (basis points, exact in integers). */
    boolean withinAutoRate(long feeMinor, long amountMinor) {
        return java.math.BigInteger.valueOf(feeMinor)
                        .multiply(java.math.BigInteger.valueOf(10_000))
                        .compareTo(java.math.BigInteger.valueOf(amountMinor)
                                .multiply(java.math.BigInteger.valueOf(feeAutoMaxBps)))
                <= 0;
    }

    private boolean feeAwaitingApproval(UUID intentId) {
        return jdbc.sql("""
                                SELECT count(*) FROM payment_statement_rows r
                                  JOIN approval_requests a ON a.id = r.fee_approval_id
                                 WHERE r.intent_id = ? AND r.fee_entry_id IS NULL AND a.status = 'pending'
                                   AND a.expires_at > CAST(? AS timestamptz)
                                """)
                        .params(intentId, Timestamp.from(clock.now()))
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Raises the maker-checker request that holds a fee above the automatic rate; the uploader is its maker. */
    private UUID holdFee(UUID statementId, UUID rowId, Row r, StatementMatcher.IntentFacts intent) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put(StatementFeeAction.STATEMENT_ID, statementId.toString());
        payload.put("row_no", r.rowNo());
        payload.put(StatementFeeAction.INTENT_ID, intent.id().toString());
        payload.put(StatementFeeAction.FEE, r.feeMinor());
        payload.put("line_amount_minor", r.amountMinor());
        payload.put("currency", r.currency());
        payload.put("auto_max_bps", feeAutoMaxBps);
        payload.put(
                ApprovalAction.CHECKER_NOTICE,
                "A provider fee of " + r.currency() + " " + ReceiptService.whole(r.feeMinor(), r.currency())
                        + " on a payment of " + r.currency() + " " + ReceiptService.whole(r.amountMinor(), r.currency())
                        + " (order " + intent.merchantReference() + ") is above the automatic " + percent(feeAutoMaxBps)
                        + " of the amount: approving posts it to gateway charges.");
        return approvals
                .request(new ActionRequest(
                        StatementFeeAction.TYPE, intent.branchId(), rowId, 0, r.feeMinor(), r.currency(), payload))
                .approvalId();
    }

    private static String percent(int bps) {
        return java.math.BigDecimal.valueOf(bps, 2).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * The checker approved a held fee (ADR-035 decision 65): posts it once. Refused, and nothing posted, when the order's fee was
     * posted meanwhile by another line or statement.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void postHeldFee(ApprovalAction.Execution execution) {
        Map<String, Object> line = jdbc.sql("""
                        SELECT r.id, r.intent_id, r.fee_minor, r.currency, r.paid_on, r.fee_entry_id, r.fee_approval_id,
                               i.branch_id, i.merchant_reference
                          FROM payment_statement_rows r JOIN payment_intents i ON i.id = r.intent_id
                         WHERE r.id = ? FOR UPDATE OF r
                        """).param(execution.subjectId()).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(() -> ApiException.rule("fee_line_missing", "The statement line is gone."));
        if (line.get("fee_entry_id") != null || !execution.approvalId().equals(line.get("fee_approval_id"))) {
            throw ApiException.rule("fee_already_posted", "This fee was already posted.");
        }
        UUID intentId = (UUID) line.get("intent_id");
        long fee = ((Number) line.get("fee_minor")).longValue();
        if (fee != ((Number) execution.payload().get(StatementFeeAction.FEE)).longValue()) {
            throw ApiException.rule("fee_changed", "The fee on the line is not the one asked about.");
        }
        BusinessLock.take(jdbc, "payments:statement:" + tenant.profile().id(), lockWait);
        if (feeAlreadyPosted(intentId)) {
            // Nothing to decide any more: the request ends stale with this note instead of waiting, pending with an
            // error, until it expires.
            throw new RequestSupersededException(
                    "fee_already_posted",
                    "Fee already posted",
                    "The fee of this payment was already posted from another statement line, so this request was closed.",
                    Map.of());
        }
        UUID entry = postFee(
                (UUID) line.get("branch_id"),
                (String) line.get("merchant_reference"),
                intentId,
                ((java.sql.Date) line.get("paid_on")).toLocalDate(),
                fee,
                ((String) line.get("currency")).trim());
        jdbc.sql("UPDATE payment_statement_rows SET fee_entry_id = ? WHERE id = ?")
                .params(entry, execution.subjectId())
                .update();
        audit.record(AuditLog.Entry.created(
                "core.payments.statement.fee_posted_after_approval",
                "payment_statement",
                UUID.fromString((String) execution.payload().get(StatementFeeAction.STATEMENT_ID)),
                (UUID) line.get("branch_id"),
                Map.of(
                        "row_no",
                        execution.payload().get("row_no"),
                        "fee_minor",
                        fee,
                        "approval_request_id",
                        execution.approvalId(),
                        "journal_entry_id",
                        entry)));
    }

    private static void checkCovered(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "covered_from", "invalid", "State both the first and the last day the export is complete for.")));
        }
        if (to.isBefore(from) || to.isAfter(today) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.validation(
                    List.of(
                            new FieldProblem(
                                    "covered_from",
                                    "invalid",
                                    "The days the export is complete for must run from an earlier day to a later one, not after today, and not over a year.")));
        }
    }

    /**
     * A person marks a statement line that no order of ours has as money for something outside this system, so the reconciliation
     * stops listing it (audited, once per line; the line itself is never changed). Needs the permission in every branch. A line
     * that matches one of the business's orders cannot be marked: it is not outside.
     */
    @Transactional
    RowView markOutside(UUID statementId, int rowNo, String note) {
        UUID by = PaymentsScope.requireEverywhere(READ).userId();
        if (note == null || note.trim().length() < 3 || note.length() > 300) {
            throw ApiException.validation(
                    List.of(new FieldProblem("note", "invalid", "Say why in a few words (3 to 300 characters).")));
        }
        // One mark at a time per business (the unique key says the same): the second finds the first.
        BusinessLock.take(jdbc, "payments:statement:" + tenant.profile().id(), lockWait);
        Map<String, Object> row = jdbc.sql("""
                        SELECT id, order_tracking_id, confirmation_code, amount_minor, currency FROM payment_statement_rows
                         WHERE statement_id = ? AND row_no = ?
                        """).params(statementId, rowNo).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(ApiException::notFound);
        UUID rowId = (UUID) row.get("id");
        boolean already = jdbc.sql("SELECT count(*) FROM payment_statement_reviews WHERE row_id = ?")
                        .param(rowId)
                        .query(Long.class)
                        .single()
                > 0;
        boolean asked = jdbc.sql("""
                                SELECT count(*) FROM approval_requests
                                 WHERE action_type = ? AND subject_id = ? AND status = 'pending'
                                   AND expires_at > CAST(? AS timestamptz)
                                """)
                        .params(StatementLineOutsideAction.TYPE, rowId, Timestamp.from(clock.now()))
                        .query(Long.class)
                        .single()
                > 0;
        if (!already && !asked) {
            Match m = matcher.match(List.of(new StatementMatcher.Line(
                            (String) row.get("order_tracking_id"),
                            (String) row.get("confirmation_code"),
                            ((Number) row.get("amount_minor")).longValue(),
                            (String) row.get("currency"))))
                    .getFirst();
            if (!"unmatched".equals(m.status())) {
                throw ApiException.rule(
                        "not_outside",
                        "That line matches " + (m.intent() == null ? "more than one" : "one")
                                + " of your payments, so it is not outside this system.");
            }
            // Maker-checker (ADR-035 decision 66): the mark is a request; the line stays a finding until a second
            // person
            // confirms it.
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("statement_id", statementId.toString());
            payload.put("row_no", rowNo);
            payload.put("note", note.trim());
            payload.put(
                    ApprovalAction.CHECKER_NOTICE,
                    "Statement line " + rowNo + " (" + row.get("currency") + " "
                            + ReceiptService.whole(
                                    ((Number) row.get("amount_minor")).longValue(), (String) row.get("currency"))
                            + ") is said to be money for something outside this system: \"" + note.trim()
                            + "\". Confirming stops the reconciliation listing it.");
            UUID headOffice = jdbc.sql("SELECT id FROM branches WHERE is_head_office ORDER BY created_at LIMIT 1")
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> ApiException.rule(
                            "head_office_missing",
                            "The business has no head office branch, which a whole-business request is raised in. Mark one"
                                    + " branch as the head office in Business set-up, then mark the line again."));
            approvals.request(
                    new ActionRequest(StatementLineOutsideAction.TYPE, headOffice, rowId, 0, null, null, payload));
            audit.record(AuditLog.Entry.created(
                    "core.payments.statement.line_marked_outside",
                    "payment_statement",
                    statementId,
                    null,
                    Map.of("row_no", rowNo, "outside_system", "awaiting_confirmation", "note", note.trim())));
        }
        return detail(statementId).rows().stream()
                .filter(r -> r.rowNo() == rowNo)
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    /** A second person confirmed a line is outside this system (ADR-035 decision 66): the review is recorded, once. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void confirmOutside(ApprovalAction.Execution execution) {
        boolean already = jdbc.sql("SELECT count(*) FROM payment_statement_reviews WHERE row_id = ?")
                        .param(execution.subjectId())
                        .query(Long.class)
                        .single()
                > 0;
        if (already) {
            return;
        }
        String note = (String) execution.payload().get("note");
        jdbc.sql("""
                        INSERT INTO payment_statement_reviews (id, tenant_id, row_id, reviewed_by, note, confirmed_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), execution.subjectId(), execution.makerId(), note, execution.checkerId())
                .update();
        audit.record(AuditLog.Entry.created(
                "core.payments.statement.line_reviewed",
                "payment_statement",
                UUID.fromString((String) execution.payload().get("statement_id")),
                null,
                Map.of(
                        "row_no", execution.payload().get("row_no"),
                        "outside_system", true,
                        "note", note,
                        "marked_by", execution.makerId(),
                        "approval_request_id", execution.approvalId())));
    }

    @Transactional(readOnly = true)
    StatementList list() {
        PaymentsScope.requireEverywhere(READ);
        return new StatementList(jdbc.sql(SELECT_STATEMENT + " ORDER BY created_at DESC, id LIMIT 100")
                .query(StatementService::statement)
                .list());
    }

    @Transactional(readOnly = true)
    StatementDetail get(UUID id) {
        PaymentsScope.requireEverywhere(READ);
        return detail(id);
    }

    private static final String SELECT_STATEMENT = """
            SELECT id, file_name, created_at, uploaded_by, row_count, period_from, period_to, covered_from, covered_to, currency,
                   matched_count, different_count, unmatched_count, fees_posted_minor
              FROM payment_statements
            """;

    private static StatementView statement(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp at = rs.getTimestamp("created_at");
        return new StatementView(
                rs.getObject("id", UUID.class),
                rs.getString("file_name"),
                at.toInstant(),
                rs.getObject("uploaded_by", UUID.class),
                rs.getInt("row_count"),
                rs.getObject("period_from", LocalDate.class),
                rs.getObject("period_to", LocalDate.class),
                rs.getObject("covered_from", LocalDate.class),
                rs.getObject("covered_to", LocalDate.class),
                rs.getString("currency"),
                rs.getInt("matched_count"),
                rs.getInt("different_count"),
                rs.getInt("unmatched_count"),
                rs.getLong("fees_posted_minor"));
    }

    private StatementDetail detail(UUID id) {
        StatementView view = jdbc.sql(SELECT_STATEMENT + " WHERE id = ?")
                .param(id)
                .query(StatementService::statement)
                .optional()
                .orElseThrow(ApiException::notFound);
        List<RowView> rows = jdbc.sql("""
                        SELECT row_no, order_tracking_id, confirmation_code, amount_minor, fee_minor, settled_minor, paid_on,
                               payment_method, match_status, intent_id, fee_entry_id,
                               EXISTS (SELECT 1 FROM payment_statement_reviews v WHERE v.row_id = r.id) AS reviewed,
                               EXISTS (SELECT 1 FROM approval_requests q
                                        WHERE q.action_type = ? AND q.subject_id = r.id AND q.status = 'pending'
                                          AND q.expires_at > CAST(? AS timestamptz)) AS outside_asked,
                               (SELECT CASE WHEN q.status = 'pending' AND q.expires_at <= CAST(? AS timestamptz) THEN 'expired'
                                            ELSE q.status END
                                  FROM approval_requests q WHERE q.id = r.fee_approval_id) AS fee_approval
                          FROM payment_statement_rows r WHERE statement_id = ? ORDER BY row_no LIMIT ?
                        """)
                .params(
                        StatementLineOutsideAction.TYPE,
                        Timestamp.from(clock.now()),
                        Timestamp.from(clock.now()),
                        id,
                        ROW_LIMIT)
                .query((rs, n) -> {
                    long fee = rs.getLong("fee_minor");
                    String match = rs.getString("match_status");
                    boolean posted = rs.getObject("fee_entry_id") != null;
                    String feeApproval = rs.getString("fee_approval");
                    String feeStatus = posted
                            ? "posted"
                            : fee == 0
                                    ? "none"
                                    : feeApproval != null
                                            ? ("pending".equals(feeApproval) ? "awaiting_approval" : "not_approved")
                                            : "matched".equals(match) ? "already_posted" : "held";
                    return new RowView(
                            rs.getInt("row_no"),
                            rs.getString("order_tracking_id"),
                            rs.getString("confirmation_code"),
                            rs.getLong("amount_minor"),
                            fee,
                            (Long) rs.getObject("settled_minor"),
                            rs.getObject("paid_on", LocalDate.class),
                            rs.getString("payment_method"),
                            match,
                            rs.getObject("intent_id", UUID.class),
                            rs.getBoolean("reviewed"),
                            rs.getBoolean("outside_asked"),
                            feeStatus);
                })
                .list();
        return new StatementDetail(view, rows, view.rowCount() > ROW_LIMIT);
    }

    static String safeName(String name) {
        String base = name == null ? "statement.csv" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9 ._()+-]", "_");
        if (base.isBlank()) {
            base = "statement.csv";
        }
        // Cut to the last 120 characters first, then neutralise the first one: a name starting with a formula character
        // would run as a formula when a list of statements is opened in a spreadsheet, whatever the cut left at the
        // front.
        if (base.length() > 120) {
            base = base.substring(base.length() - 120);
        }
        if ("+-=@".indexOf(base.charAt(0)) >= 0) {
            base = "_" + base.substring(1);
        }
        return base;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
