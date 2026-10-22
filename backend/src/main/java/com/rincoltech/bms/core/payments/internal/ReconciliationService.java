package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.PurposeHandler.ManualReceipt;
import com.rincoltech.bms.core.payments.internal.ReconciliationApi.FindingView;
import com.rincoltech.bms.core.payments.internal.ReconciliationApi.Report;
import com.rincoltech.bms.core.payments.internal.ReconciliationApi.RunView;
import com.rincoltech.bms.core.payments.internal.StatementMatcher.Match;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily reconciliation of a business's gateway payments (spec 3.9, FR-PAY-05). Once a night per tenant, and on request, it
 * compares what this system holds with what the provider and the people entering money say, and lists the differences in a report
 * kept 90 days. It posts nothing to a ledger and changes no order's status: its only writes besides its own rows are to schedule an
 * immediate look ({@code next_check_at} and {@code updated_at} of {@code payment_intents}) at an order that is paid and unbooked or
 * unpaid after a day and has nothing scheduled (the re-query of spec 3.9; the poller does the asking), and to remove runs older
 * than 90 days.
 *
 * <p>Window: the orders of the last 120 days (the card reversal window) and the statements uploaded in that time, read in full:
 * a business with more than the ceilings holds is told so ({@code truncated} on the run), and then nothing that needs the whole
 * (a booked payment missing from every statement) is listed, since a part would flag payments falsely. What it lists:
 * a completed order not booked an hour after it was paid; an order unpaid a day after it was made; a payment that differs from
 * the order; a booked payment the provider reversed and no person has decided; a reversal handed over to a person; a reversal the
 * savings balance rule refused (a shortfall); a reversal of money the lender already sent back by hand (a loss); a receipt keyed by
 * hand that is probably the same payment as one the gateway booked (its reference equals the confirmation code, found through
 * {@code payment_intents.confirmation_code} and the booked transaction, never by comparing the loan transaction's own external
 * reference, which is the order tracking id; and it is for the same loan or account, or the same amount within three days); and,
 * from the uploaded statements, lines this system does not hold as paid (unless a person marked them as outside this system),
 * orders booked on a day an export is complete for that no statement lists, and amounts or currencies that differ. The statement
 * lines are matched again on every run, so a difference closes by itself when its cause is put right. The alert counts only
 * differences the previous run did not already list.
 */
@Service
class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    static final String READ = "core.payments.read";
    static final String MANAGE = "core.payments.manage";
    static final Duration WINDOW = Duration.ofDays(120);
    static final Duration KEEP = Duration.ofDays(90);
    private static final Duration UNBOOKED_AFTER = Duration.ofHours(1);
    private static final Duration PENDING_AFTER = Duration.ofHours(24);
    /** How much is read at a time, and how much at most; package-private so a test can make them small. */
    static int page = 5_000;

    static int intentCeiling = 200_000;
    static int lineCeiling = 100_000;
    private static final long SAME_PAYMENT_DAYS = 3;
    private static final int REPORT_LIMIT = 500;
    private static final int CODE_BATCH = 1000;
    private static final Set<String> BOOKED = Set.of("booked", "booked_unallocated", "reversed_pending_review");

    /** One difference before it is written. */
    private record Finding(
            String kind,
            UUID intentId,
            UUID statementRowId,
            UUID otherTxnId,
            String trackingId,
            String code,
            Long bmsAmount,
            Long providerAmount,
            String currency,
            UUID branchId) {

        /** What makes a difference the same one on the next run. */
        String key() {
            return kind + "|" + intentId + "|" + statementRowId + "|" + otherTxnId + "|" + trackingId + "|" + code;
        }
    }

    private record IntentRow(
            UUID id,
            UUID branchId,
            String status,
            String merchantReference,
            String providerReference,
            String confirmationCode,
            long amountMinor,
            String currency,
            Long paidAmountMinor,
            String paidCurrency,
            Instant paidAt,
            Instant createdAt,
            Instant nextCheckAt,
            UUID bookedTxnId,
            UUID subjectId,
            UUID allocatedSubjectId,
            String refusalCode,
            String receiptStatus,
            String blocked) {}

    private record StatementLine(
            UUID id, String trackingId, String code, long amountMinor, String currency, boolean reviewed) {}

    /** The days a statement is complete for. */
    private record Period(LocalDate from, LocalDate to, String currency) {}

    /** What was read, and whether it was all there was within the ceiling. */
    private record Load<T>(List<T> items, boolean truncated) {}

    private final JdbcClient jdbc;
    private final StatementMatcher matcher;
    private final PurposeRegistry registry;
    private final AuditLog audit;
    private final PaymentCounters counters;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final Duration lockWait;

    ReconciliationService(
            JdbcClient jdbc,
            StatementMatcher matcher,
            PurposeRegistry registry,
            AuditLog audit,
            PaymentCounters counters,
            CurrentTenant tenant,
            BusinessClock clock,
            @org.springframework.beans.factory.annotation.Value("${bms.payments.reconcile-lock-wait:30s}")
                    Duration lockWait) {
        this.lockWait = lockWait;
        this.jdbc = jdbc;
        this.matcher = matcher;
        this.registry = registry;
        this.audit = audit;
        this.counters = counters;
        this.tenant = tenant;
        this.clock = clock;
    }

    private boolean jobRanOn(LocalDate day) {
        return jdbc.sql("SELECT count(*) FROM payment_reconciliation_runs WHERE trigger = 'job' AND ran_on = ?")
                        .param(Date.valueOf(day))
                        .query(Long.class)
                        .single()
                > 0;
    }

    // ---- running -------------------------------------------------------------------------------

    /**
     * One run for the bound tenant, in the caller's transaction. A tenant with no orders and no statement gets no run from the
     * nightly job ({@code always} false), so a business that never collected has nothing written; a request always gets one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<RunView> run(String trigger, UUID startedBy, boolean always) {
        UUID tenantId = tenant.profile().id();
        // One run at a time per business: the job and a person asking at the same moment make two runs, not a tangle.
        // The
        // lock is the transaction's, so a crashed run leaves nothing held; only the wait for it is bounded
        // (BusinessLock).
        BusinessLock.take(jdbc, "payments:reconcile:" + tenantId, lockWait);
        Instant now = clock.now();
        ZoneId zone = tenant.profile().timezone();
        LocalDate today = clock.today(zone);
        // The nightly job runs once a business day: a job that is run again (a second node, a retry after a crash, a
        // resume)
        // finds the day's run, written in the same transaction as its findings, and writes nothing. A unique index
        // backs it.
        if ("job".equals(trigger) && jobRanOn(today)) {
            return Optional.empty();
        }
        LocalDate windowFrom = today.minusDays(WINDOW.toDays());
        Instant from = windowFrom.atStartOfDay(zone).toInstant();

        Load<IntentRow> intents = intents(from);
        Load<StatementLine> lines = statementLines(from);
        List<Period> periods = periods(from);
        if (!always && intents.items().isEmpty() && lines.items().isEmpty()) {
            return Optional.empty();
        }
        boolean truncated = intents.truncated() || lines.truncated();

        List<Finding> findings = new ArrayList<>();
        Set<UUID> requeue = new HashSet<>();
        intentFindings(intents.items(), now, findings, requeue);
        manualDuplicates(intents.items(), zone, findings);
        statementFindings(
                intents.items(), lines.items(), periods, zone, lines.truncated() || intents.truncated(), findings);

        Set<String> before = previousKeys();
        long fresh = findings.stream().filter(f -> !before.contains(f.key())).count();

        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payment_reconciliation_runs (id, tenant_id, ran_on, trigger, started_by, window_from,
                            intents_examined, statements_used, finding_count, truncated)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        runId,
                        Date.valueOf(today),
                        trigger,
                        startedBy,
                        Date.valueOf(windowFrom),
                        intents.items().size(),
                        periods.size(),
                        findings.size(),
                        truncated)
                .update();
        for (Finding f : findings) {
            jdbc.sql("""
                            INSERT INTO payment_reconciliation_findings (id, tenant_id, run_id, kind, intent_id, statement_row_id,
                                other_txn_id, order_tracking_id, confirmation_code, bms_amount_minor, provider_amount_minor,
                                currency, branch_id)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            runId,
                            f.kind(),
                            f.intentId(),
                            f.statementRowId(),
                            f.otherTxnId(),
                            f.trackingId(),
                            f.code(),
                            f.bmsAmount(),
                            f.providerAmount(),
                            f.currency(),
                            f.branchId())
                    .update();
        }
        if (!requeue.isEmpty()) {
            // The re-query: the poller looks at these now. Only an order with nothing scheduled is touched, and only
            // next_check_at and updated_at are written: no status, amount or reference ever changes here.
            jdbc.sql("""
                            UPDATE payment_intents SET next_check_at = :now, updated_at = :now
                             WHERE id IN (:ids) AND next_check_at IS NULL AND status IN ('paid', 'pending', 'expired')
                            """)
                    .param("now", Timestamp.from(now))
                    .param("ids", List.copyOf(requeue))
                    .update();
        }
        purgeOld(now);
        Map<String, Integer> byKind = new LinkedHashMap<>();
        findings.forEach(f -> byKind.merge(f.kind(), 1, Integer::sum));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("trigger", trigger);
        after.put("intents_examined", intents.items().size());
        after.put("statements_used", periods.size());
        after.put("findings", findings.size());
        after.put("new_findings", fresh);
        after.put("truncated", truncated);
        after.put("by_kind", byKind);
        audit.record(AuditLog.Entry.created(
                "core.payments.reconciliation.run", "payment_reconciliation_run", runId, null, after));
        if (fresh > 0) {
            // Only a difference the previous run did not already list is news: the same one every night is not an
            // alert.
            counters.count("reconciliation_differences");
            log.warn("payment reconciliation {}: {} new difference(s) of {} {}", runId, fresh, findings.size(), byKind);
        }
        if (truncated) {
            log.warn("payment reconciliation {} could not read everything: results are partial", runId);
        }
        return Optional.of(new RunView(
                runId,
                now,
                today,
                trigger,
                windowFrom,
                intents.items().size(),
                periods.size(),
                findings.size(),
                truncated));
    }

    /** The keys of the differences the latest run listed, to tell a new one from one already known. */
    private Set<String> previousKeys() {
        return new HashSet<>(jdbc.sql("""
                        SELECT f.kind, f.intent_id, f.statement_row_id, f.other_txn_id, f.order_tracking_id, f.confirmation_code
                          FROM payment_reconciliation_findings f
                         WHERE f.run_id = (SELECT id FROM payment_reconciliation_runs ORDER BY created_at DESC, id LIMIT 1)
                        """)
                .query((rs, n) -> new Finding(
                                rs.getString(1),
                                rs.getObject(2, UUID.class),
                                rs.getObject(3, UUID.class),
                                rs.getObject(4, UUID.class),
                                rs.getString(5),
                                rs.getString(6),
                                null,
                                null,
                                null,
                                null)
                        .key())
                .list());
    }

    private void purgeOld(Instant now) {
        jdbc.sql("DELETE FROM payment_reconciliation_runs WHERE created_at < ?")
                .param(Timestamp.from(now.minus(KEEP)))
                .update();
    }

    // ---- the orders --------------------------------------------------------------------------------

    /** Every order of the window, newest first, a page at a time, up to the ceiling. */
    private Load<IntentRow> intents(Instant from) {
        List<IntentRow> all = new ArrayList<>();
        Timestamp afterAt = null;
        UUID afterId = null;
        while (true) {
            List<IntentRow> rows = jdbc.sql("""
                            SELECT i.id, i.branch_id, i.status, i.merchant_reference, i.provider_reference, i.confirmation_code,
                                   i.amount_minor, i.currency, i.paid_amount_minor, i.paid_currency, i.paid_at, i.created_at,
                                   i.next_check_at, i.booked_subject_txn_id, i.subject_id, r.allocated_subject_id,
                                   i.refusal_code, r.status AS receipt_status,
                                   (SELECT a.execution_error FROM approval_requests a
                                     WHERE a.action_type = 'gateway_payment_reversal' AND a.subject_id = i.id
                                       AND a.status = 'pending'
                                     ORDER BY a.requested_at DESC LIMIT 1) AS blocked
                              FROM payment_intents i
                              LEFT JOIN unallocated_receipts r ON r.tenant_id = i.tenant_id AND r.intent_id = i.id
                             WHERE i.scope = 'tenant' AND i.created_at >= :from
                               AND (CAST(:afterAt AS timestamptz) IS NULL OR (i.created_at, i.id) < (:afterAt, CAST(:afterId AS uuid)))
                             ORDER BY i.created_at DESC, i.id DESC LIMIT :limit
                            """)
                    .param("from", Timestamp.from(from))
                    .param("afterAt", afterAt)
                    .param("afterId", afterId)
                    .param("limit", page)
                    .query((rs, n) -> new IntentRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("status"),
                            rs.getString("merchant_reference"),
                            rs.getString("provider_reference"),
                            rs.getString("confirmation_code"),
                            rs.getLong("amount_minor"),
                            rs.getString("currency"),
                            (Long) rs.getObject("paid_amount_minor"),
                            rs.getString("paid_currency"),
                            instant(rs.getTimestamp("paid_at")),
                            instant(rs.getTimestamp("created_at")),
                            instant(rs.getTimestamp("next_check_at")),
                            rs.getObject("booked_subject_txn_id", UUID.class),
                            rs.getObject("subject_id", UUID.class),
                            rs.getObject("allocated_subject_id", UUID.class),
                            rs.getString("refusal_code"),
                            rs.getString("receipt_status"),
                            rs.getString("blocked")))
                    .list();
            all.addAll(rows);
            if (rows.size() < page) {
                return new Load<>(all, false);
            }
            if (all.size() >= intentCeiling) {
                return new Load<>(all, true);
            }
            IntentRow last = rows.getLast();
            afterAt = Timestamp.from(last.createdAt());
            afterId = last.id();
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private void intentFindings(List<IntentRow> intents, Instant now, List<Finding> out, Set<UUID> requeue) {
        for (IntentRow i : intents) {
            boolean paid = i.paidAt() != null && i.paidAmountMinor() != null;
            if (("paid".equals(i.status()) || "needs_attention".equals(i.status()))
                    && paid
                    && i.paidAt().isBefore(now.minus(UNBOOKED_AFTER))) {
                out.add(intentFinding("completed_not_booked", i, i.amountMinor(), i.paidAmountMinor()));
                requeue.add(i.id());
            }
            if (Set.of("created", "pending", "expired").contains(i.status())
                    && i.createdAt().isBefore(now.minus(PENDING_AFTER))) {
                out.add(intentFinding("pending_over_24h", i, i.amountMinor(), null));
                requeue.add(i.id());
            }
            if (paid
                    && i.paidAmountMinor() != i.amountMinor()
                    && Set.of("paid", "booked", "booked_unallocated", "reversed_pending_review", "needs_attention")
                            .contains(i.status())) {
                out.add(intentFinding("paid_differs", i, i.amountMinor(), i.paidAmountMinor()));
            }
            if ("reversed_pending_review".equals(i.status())) {
                String kind = "refunded".equals(i.receiptStatus())
                        ? "reversed_after_refund"
                        : i.blocked() != null
                                ? "reversal_blocked"
                                : i.refusalCode() != null && ReversalHandOver.CODES.contains(i.refusalCode())
                                        ? "reversal_handed_over"
                                        : "reversed_awaiting_review";
                out.add(intentFinding(kind, i, i.paidAmountMinor(), i.paidAmountMinor()));
            }
        }
    }

    private Finding intentFinding(String kind, IntentRow i, Long bms, Long provider) {
        return new Finding(
                kind,
                i.id(),
                null,
                null,
                i.providerReference(),
                i.confirmationCode(),
                bms,
                provider,
                i.currency(),
                i.branchId());
    }

    /**
     * A receipt keyed by hand that is probably the same payment as one the gateway took: its reference is the order's
     * confirmation code (a short code two genuine payments can share), and it is for the same loan or account, or for the same
     * amount within three days of the payment. A receipt that only shares a short code with an unrelated payment is not listed.
     */
    private void manualDuplicates(List<IntentRow> intents, ZoneId zone, List<Finding> out) {
        Map<String, List<IntentRow>> byCode = new HashMap<>();
        Set<UUID> gatewayTxns = new HashSet<>();
        for (IntentRow i : intents) {
            if (i.confirmationCode() != null && i.paidAt() != null) {
                byCode.computeIfAbsent(i.confirmationCode().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(i);
            }
            if (i.bookedTxnId() != null) {
                gatewayTxns.add(i.bookedTxnId());
            }
        }
        if (byCode.isEmpty()) {
            return;
        }
        List<String> codes = List.copyOf(byCode.keySet());
        for (PurposeHandler handler : registry.all()) {
            for (int from = 0; from < codes.size(); from += CODE_BATCH) {
                List<String> batch = codes.subList(from, Math.min(codes.size(), from + CODE_BATCH));
                for (ManualReceipt m : handler.manualReceiptsWithReference(batch, gatewayTxns)) {
                    for (IntentRow i : byCode.getOrDefault(m.reference().trim().toUpperCase(Locale.ROOT), List.of())) {
                        if (!samePayment(m, i, zone)) {
                            continue;
                        }
                        out.add(new Finding(
                                "manual_receipt_duplicate",
                                i.id(),
                                null,
                                m.transactionId(),
                                i.providerReference(),
                                i.confirmationCode(),
                                m.amountMinor(),
                                i.paidAmountMinor(),
                                m.currency(),
                                m.branchId()));
                    }
                }
            }
        }
    }

    private static boolean samePayment(ManualReceipt m, IntentRow i, ZoneId zone) {
        if (m.subjectId() != null
                && (m.subjectId().equals(i.subjectId()) || m.subjectId().equals(i.allocatedSubjectId()))) {
            return true;
        }
        if (m.valueDate() == null || i.paidAt() == null || i.paidAmountMinor() == null) {
            return false;
        }
        long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                m.valueDate(), i.paidAt().atZone(zone).toLocalDate()));
        return m.amountMinor() == i.paidAmountMinor() && days <= SAME_PAYMENT_DAYS;
    }

    // ---- the statements ----------------------------------------------------------------------------

    /** Every statement line of the window, a page at a time, up to the ceiling. */
    private Load<StatementLine> statementLines(Instant from) {
        List<StatementLine> all = new ArrayList<>();
        UUID after = null;
        while (true) {
            List<StatementLine> rows = jdbc.sql("""
                            SELECT r.id, r.order_tracking_id, r.confirmation_code, r.amount_minor, r.currency,
                                   EXISTS (SELECT 1 FROM payment_statement_reviews v WHERE v.row_id = r.id) AS reviewed
                              FROM payment_statement_rows r
                              JOIN payment_statements s ON s.tenant_id = r.tenant_id AND s.id = r.statement_id
                             WHERE s.created_at >= :from AND (CAST(:after AS uuid) IS NULL OR r.id > CAST(:after AS uuid))
                             ORDER BY r.id LIMIT :limit
                            """)
                    .param("from", Timestamp.from(from))
                    .param("after", after)
                    .param("limit", page)
                    .query((rs, n) -> new StatementLine(
                            rs.getObject("id", UUID.class),
                            rs.getString("order_tracking_id"),
                            rs.getString("confirmation_code"),
                            rs.getLong("amount_minor"),
                            rs.getString("currency"),
                            rs.getBoolean("reviewed")))
                    .list();
            all.addAll(rows);
            if (rows.size() < page) {
                return new Load<>(all, false);
            }
            if (all.size() >= lineCeiling) {
                return new Load<>(all, true);
            }
            after = rows.getLast().id();
        }
    }

    /** The days exports are complete for. An export that states none, or too short to cover a whole day, covers nothing. */
    private List<Period> periods(Instant from) {
        return jdbc.sql("""
                        SELECT covered_from, covered_to, currency FROM payment_statements
                         WHERE created_at >= ? AND covered_from IS NOT NULL
                        """)
                .param(Timestamp.from(from))
                .query((rs, n) -> new Period(
                        rs.getObject("covered_from", LocalDate.class),
                        rs.getObject("covered_to", LocalDate.class),
                        rs.getString("currency")))
                .list();
    }

    private void statementFindings(
            List<IntentRow> intents,
            List<StatementLine> lines,
            List<Period> periods,
            ZoneId zone,
            boolean partial,
            List<Finding> out) {
        if (lines.isEmpty()) {
            return;
        }
        List<Match> matches = matcher.match(lines.stream()
                .map(l -> new StatementMatcher.Line(l.trackingId(), l.code(), l.amountMinor(), l.currency()))
                .toList());
        Set<UUID> listed = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int n = 0; n < lines.size(); n++) {
            StatementLine l = lines.get(n);
            Match m = matches.get(n);
            if (m.intent() != null) {
                listed.add(m.intent().id());
            }
            if ("matched".equals(m.status()) || ("unmatched".equals(m.status()) && l.reviewed())) {
                // Clean, or a person has marked it as money for something outside this system.
                continue;
            }
            String key = m.status() + ":"
                    + (l.trackingId() != null ? l.trackingId().toLowerCase(Locale.ROOT) : "c:" + l.code());
            if (!seen.add(key)) {
                continue;
            }
            Long bms = m.intent() == null ? null : m.intent().paidAmountMinor();
            UUID intent = m.intent() == null ? null : m.intent().id();
            UUID branch = m.intent() == null ? null : m.intent().branchId();
            String kind = switch (m.status()) {
                case "unmatched", "not_paid_here" -> "in_provider_not_in_bms";
                case "amount_mismatch" -> "amount_mismatch";
                case "currency_mismatch" -> "currency_mismatch";
                default -> "ambiguous_statement_row";
            };
            out.add(new Finding(
                    kind, intent, l.id(), null, l.trackingId(), l.code(), bms, l.amountMinor(), l.currency(), branch));
        }
        if (partial) {
            // A part of the orders or lines would flag payments that are on a statement we did not read.
            return;
        }
        // Booked here on a day an export is complete for, and on none of them.
        for (IntentRow i : intents) {
            if (i.paidAt() == null
                    || i.paidAmountMinor() == null
                    || !BOOKED.contains(i.status())
                    || listed.contains(i.id())) {
                continue;
            }
            LocalDate day = i.paidAt().atZone(zone).toLocalDate();
            boolean covered = periods.stream()
                    .anyMatch(p ->
                            p.currency().equals(i.paidCurrency()) && !day.isBefore(p.from()) && !day.isAfter(p.to()));
            if (covered) {
                out.add(new Finding(
                        "in_bms_not_in_provider",
                        i.id(),
                        null,
                        null,
                        i.providerReference(),
                        i.confirmationCode(),
                        i.paidAmountMinor(),
                        null,
                        i.paidCurrency(),
                        i.branchId()));
            }
        }
    }

    // ---- the report ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Report latest() {
        Principal principal = CurrentPrincipal.require();
        Optional<RunView> run = jdbc.sql("""
                        SELECT id, created_at, ran_on, trigger, window_from, intents_examined, statements_used, finding_count,
                               truncated
                          FROM payment_reconciliation_runs ORDER BY created_at DESC, id LIMIT 1
                        """)
                .query((rs, n) -> new RunView(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("ran_on", LocalDate.class),
                        rs.getString("trigger"),
                        rs.getObject("window_from", LocalDate.class),
                        rs.getInt("intents_examined"),
                        rs.getInt("statements_used"),
                        rs.getInt("finding_count"),
                        rs.getBoolean("truncated")))
                .optional();
        if (run.isEmpty()) {
            return new Report(null, Map.of(), List.of(), false);
        }
        List<UUID> branches = principal.branchFilter(READ, null);
        boolean everywhere = branches == null;
        if (!everywhere && branches.isEmpty()) {
            return new Report(restricted(run.get(), 0), Map.of(), List.of(), false);
        }
        String where = everywhere ? "" : " AND branch_id IN (:branches)";
        var counts = new LinkedHashMap<String, Integer>();
        var countQuery = jdbc.sql("SELECT kind, count(*) FROM payment_reconciliation_findings WHERE run_id = :run"
                        + where + " GROUP BY kind ORDER BY kind")
                .param("run", run.get().id());
        if (!everywhere) {
            countQuery = countQuery.param("branches", branches);
        }
        countQuery.query((rs, n) -> counts.put(rs.getString(1), rs.getInt(2))).list();
        var query = jdbc.sql("""
                        SELECT id, kind, intent_id, branch_id, order_tracking_id, confirmation_code, bms_amount_minor,
                               provider_amount_minor, currency, other_txn_id, statement_row_id
                          FROM payment_reconciliation_findings WHERE run_id = :run""" + where + " ORDER BY kind, id LIMIT :limit")
                .param("run", run.get().id())
                .param("limit", REPORT_LIMIT + 1);
        if (!everywhere) {
            query = query.param("branches", branches);
        }
        List<FindingView> items = query.query((rs, n) -> new FindingView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getObject("intent_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("order_tracking_id"),
                        rs.getString("confirmation_code"),
                        (Long) rs.getObject("bms_amount_minor"),
                        (Long) rs.getObject("provider_amount_minor"),
                        rs.getString("currency"),
                        rs.getObject("other_txn_id", UUID.class),
                        rs.getObject("statement_row_id", UUID.class)))
                .list();
        boolean more = items.size() > REPORT_LIMIT;
        RunView shown = everywhere
                ? run.get()
                : restricted(
                        run.get(),
                        counts.values().stream().mapToInt(Integer::intValue).sum());
        return new Report(shown, counts, more ? items.subList(0, REPORT_LIMIT) : items, more);
    }

    /**
     * What a reader of some branches may see of a run: its own time and kind, the differences in their branches counted from the
     * rows shown, and not the business-wide totals (how many orders and statements the run looked at).
     */
    private static RunView restricted(RunView run, int visible) {
        return new RunView(
                run.id(),
                run.createdAt(),
                run.ranOn(),
                run.trigger(),
                run.windowFrom(),
                null,
                null,
                visible,
                run.truncated());
    }
}
