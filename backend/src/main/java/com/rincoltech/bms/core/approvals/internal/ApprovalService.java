package com.rincoltech.bms.core.approvals.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.internal.ApprovalController.ApprovalPage;
import com.rincoltech.bms.core.approvals.internal.ApprovalController.ApprovalResponse;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The approval mechanism (FR-APR-01 to FR-APR-08). Requests carry a payload snapshot; the checker
 * is never the maker (the service refuses, and the database CHECK
 * {@code approval_checker_is_not_maker} would refuse too); approval executes the snapshot exactly
 * once, in the transaction that records the decision.
 */
@Service
class ApprovalService implements Approvals {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    static final Duration VALIDITY = Duration.ofDays(7);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String COLUMNS = """
            r.id, r.action_type, r.subject_type, r.subject_id, r.branch_id, r.amount_minor, r.currency,
            r.payload::text AS payload, r.subject_version, r.status, r.requested_by, u.full_name AS requested_by_name,
            r.requested_at, r.expires_at, r.decided_by, r.decided_at, r.decision_note, r.execution_error
            """;

    private final Map<String, ApprovalAction> actions;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TenantSettings settings;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TransactionTemplate transactions;

    ApprovalService(
            List<ApprovalAction> actions,
            JdbcClient jdbc,
            ObjectMapper mapper,
            TenantSettings settings,
            AuditLog audit,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.actions =
                actions.stream().collect(Collectors.toUnmodifiableMap(ApprovalAction::actionType, Function.identity()));
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---- Maker ----------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome request(ActionRequest request) {
        Principal maker = CurrentPrincipal.require();
        ApprovalAction action = action(request.actionType());
        if (!maker.may(action.makerPermission(), request.branchId())) {
            throw denied();
        }
        if ((request.amountMinor() == null) != (request.currency() == null)) {
            throw new IllegalArgumentException("amount and currency go together");
        }
        if (action.thresholdApplies()
                && request.amountMinor() != null
                && request.amountMinor() < settings.approvalThresholdMinor(action.actionType())) {
            action.execute(new ApprovalAction.Execution(
                    null, request.branchId(), request.subjectId(), request.payload(), maker.userId(), null));
            audit.record(new AuditLog.Entry(
                    "core.approval.executed_below_threshold",
                    action.subjectType(),
                    request.subjectId(),
                    request.branchId(),
                    Map.of(),
                    summary(request)));
            return new Outcome(true, null);
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        try {
            jdbc.sql("""
                            INSERT INTO approval_requests (id, tenant_id, branch_id, action_type, subject_type, subject_id,
                                                           amount_minor, currency, payload, subject_version, status,
                                                           requested_by, requested_at, expires_at)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?,
                                    'pending', ?, ?, ?)
                            """)
                    .params(
                            id,
                            request.branchId(),
                            action.actionType(),
                            action.subjectType(),
                            request.subjectId(),
                            request.amountMinor(),
                            request.currency(),
                            mapper.writeValueAsString(request.payload()),
                            request.subjectVersion(),
                            maker.userId(),
                            Timestamp.from(now),
                            Timestamp.from(now.plus(VALIDITY)))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "approval_already_pending",
                    "Approval already pending",
                    "A request for this action on this subject is already waiting for a checker.");
        }
        audit.record(AuditLog.Entry.created(
                "core.approval.requested", "core.approval_request", id, request.branchId(), summary(request)));
        return new Outcome(false, id);
    }

    @Transactional
    ApprovalResponse cancel(UUID approvalId) {
        Principal principal = CurrentPrincipal.require();
        Row row = lockVisible(principal, approvalId);
        if (!row.requestedBy().equals(principal.userId())) {
            throw denied();
        }
        requirePending(row);
        jdbc.sql("""
                        UPDATE approval_requests SET status = 'cancelled', decided_at = ?, updated_at = now(),
                               version = version + 1 WHERE id = ?
                        """).params(Timestamp.from(clock.now()), approvalId).update();
        audit.record(transition(row, "core.approval.cancelled", "cancelled"));
        return get(approvalId);
    }

    // ---- Checker --------------------------------------------------------------------------

    private enum Decided {
        APPROVED,
        EXPIRED,
        STALE
    }

    /** Thrown inside the approval transaction when the action itself fails; rolls it back. */
    private static final class ExecutionFailed extends RuntimeException {
        ExecutionFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * FR-APR-06: executes in the decision's transaction. An expired request becomes
     * {@code expired}, a changed subject makes it {@code stale}, and both states are committed
     * before the error is returned. A failed execution rolls the decision back, records the error
     * on the still pending request, and returns it.
     */
    ApprovalResponse approve(UUID approvalId, String note) {
        Principal checker = CurrentPrincipal.require();
        Decided decided;
        try {
            decided = transactions.execute(status -> decide(checker, approvalId, note));
        } catch (ExecutionFailed e) {
            transactions.executeWithoutResult(status -> {
                jdbc.sql("UPDATE approval_requests SET execution_error = ?, updated_at = now() WHERE id = ?")
                        .params(e.getMessage(), approvalId)
                        .update();
                audit.record(new AuditLog.Entry(
                        "core.approval.execution_failed",
                        "core.approval_request",
                        approvalId,
                        null,
                        Map.of(),
                        Map.of("error", e.getMessage())));
            });
            throw ApiException.rule(
                    "approval_execution_failed", "The action failed and stays pending: " + e.getMessage());
        }
        return switch (decided) {
            case APPROVED -> transactions.execute(status -> get(approvalId));
            case EXPIRED ->
                throw ApiException.rule("approval_expired", "The request expired and can no longer be approved.");
            case STALE ->
                throw ApiException.rule(
                        "subject_changed", "The subject changed since the request; the request is now stale.");
        };
    }

    private Decided decide(Principal checker, UUID approvalId, String note) {
        Row row = lockVisible(checker, approvalId);
        ApprovalAction action = action(row.actionType());
        if (!checker.may(action.checkerPermission(), row.branchId())) {
            throw denied();
        }
        if (!"pending".equals(row.status())) {
            throw notPending(row.status());
        }
        Instant now = clock.now();
        if (!now.isBefore(row.expiresAt())) {
            setStatus(row, "expired", null, null, now);
            audit.record(transition(row, "core.approval.expired", "expired"));
            return Decided.EXPIRED;
        }
        refuseMakerAndConflicts(checker, row, action);
        Optional<Integer> current = action.subjectVersion(row.subjectId());
        if (current.isEmpty() || (row.subjectVersion() != null && !current.get().equals(row.subjectVersion()))) {
            setStatus(row, "stale", null, null, now);
            audit.record(transition(row, "core.approval.stale", "stale"));
            return Decided.STALE;
        }
        try {
            action.execute(new ApprovalAction.Execution(
                    row.id(), row.branchId(), row.subjectId(), row.payload(), row.requestedBy(), checker.userId()));
        } catch (ApiException e) {
            throw new ExecutionFailed(e.code() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("approval {} execution failed", approvalId, e);
            throw new ExecutionFailed("internal_error", e);
        }
        setStatus(row, "approved", checker.userId(), note, now);
        audit.record(transition(row, "core.approval.approved", "approved"));
        return Decided.APPROVED;
    }

    @Transactional
    ApprovalResponse reject(UUID approvalId, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("note", "required", "A note is required to reject.")));
        }
        Principal checker = CurrentPrincipal.require();
        Row row = lockVisible(checker, approvalId);
        ApprovalAction action = action(row.actionType());
        if (!checker.may(action.checkerPermission(), row.branchId())) {
            throw denied();
        }
        requirePending(row);
        refuseMakerAndConflicts(checker, row, action);
        setStatus(row, "rejected", checker.userId(), note.trim(), clock.now());
        audit.record(transition(row, "core.approval.rejected", "rejected"));
        return get(approvalId);
    }

    /** Marks every pending request past its expiry as expired (nightly, FR-APR-07). */
    @Transactional(propagation = Propagation.MANDATORY)
    int expireOverdue() {
        List<Row> overdue = jdbc.sql(
                        "SELECT " + COLUMNS + " FROM approval_requests r LEFT JOIN users u ON u.id = r.requested_by"
                                + " WHERE r.status = 'pending' AND r.expires_at <= ? FOR UPDATE OF r")
                .param(Timestamp.from(clock.now()))
                .query(this::map)
                .list();
        for (Row row : overdue) {
            setStatus(row, "expired", null, null, clock.now());
            audit.record(transition(row, "core.approval.expired", "expired"));
        }
        return overdue.size();
    }

    // ---- Queue (FR-APR-05) ----------------------------------------------------------------

    /**
     * Requests the principal may decide (the action's checker permission in the request's branch)
     * or made, newest first. {@code branchIds} narrows to the branches asked for (FR-BR-04).
     */
    @Transactional(readOnly = true)
    ApprovalPage list(
            List<String> statuses, List<String> actionTypes, List<UUID> branchIds, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM approval_requests r LEFT JOIN users u ON u.id = r.requested_by WHERE (r.requested_by = :me");
        params.put("me", principal.userId());
        int i = 0;
        for (ApprovalAction action : actions.values()) {
            Optional<BranchScope> scope = principal.scopeOf(action.checkerPermission());
            if (scope.isEmpty()
                    || (!scope.get().all() && scope.get().branchIds().isEmpty())) {
                continue;
            }
            sql.append(" OR (r.action_type = :type").append(i);
            params.put("type" + i, action.actionType());
            if (!scope.get().all()) {
                sql.append(" AND r.branch_id IN (:branches").append(i).append(")");
                params.put("branches" + i, List.copyOf(scope.get().branchIds()));
            }
            sql.append(")");
            i++;
        }
        sql.append(")");
        List<UUID> seeable = principal.branchFilter("core.approvals.read", branchIds);
        if (seeable != null) {
            if (seeable.isEmpty()) {
                return new ApprovalPage(List.of(), null);
            }
            sql.append(" AND r.branch_id IN (:seeable)");
            params.put("seeable", seeable);
        }
        Timestamp now = Timestamp.from(clock.now());
        params.put("now", now);
        if (statuses != null && !statuses.isEmpty()) {
            // A pending request past its expiry is shown as expired even before the nightly job.
            sql.append(" AND (CASE WHEN r.status = 'pending' AND r.expires_at <= :now THEN 'expired' ELSE r.status END)"
                    + " IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (actionTypes != null && !actionTypes.isEmpty()) {
            sql.append(" AND r.action_type IN (:actionTypes)");
            params.put("actionTypes", actionTypes);
        }
        Optional<String> after = Cursor.decode(cursor);
        if (after.isPresent()) {
            String[] parts = after.get().split("\\|");
            sql.append(" AND (r.requested_at, r.id) < (:afterAt, :afterId)");
            params.put("afterAt", Timestamp.from(Instant.parse(parts[0])));
            params.put("afterId", UUID.fromString(parts[1]));
        }
        sql.append(" ORDER BY r.requested_at DESC, r.id DESC LIMIT :limit");
        params.put("limit", size + 1);
        List<Row> rows =
                jdbc.sql(sql.toString()).params(params).query(this::map).list();
        boolean more = rows.size() > size;
        List<Row> page = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        page.getLast().requestedAt() + "|" + page.getLast().id())
                : null;
        return new ApprovalPage(
                page.stream().map(r -> response(principal, r, false)).toList(), next);
    }

    @Transactional(readOnly = true)
    ApprovalResponse get(UUID approvalId) {
        Principal principal = CurrentPrincipal.require();
        return find(approvalId)
                .filter(r -> visible(principal, r))
                .map(r -> response(principal, r, true))
                .orElseThrow(ApiException::notFound);
    }

    // ---- Helpers --------------------------------------------------------------------------

    record Row(
            UUID id,
            String actionType,
            String subjectType,
            UUID subjectId,
            UUID branchId,
            Long amountMinor,
            String currency,
            Map<String, Object> payload,
            Integer subjectVersion,
            String status,
            UUID requestedBy,
            String requestedByName,
            Instant requestedAt,
            Instant expiresAt,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            String executionError) {}

    private ApprovalResponse response(Principal principal, Row r, boolean withPayload) {
        Instant now = clock.now();
        boolean pending = "pending".equals(r.status()) && now.isBefore(r.expiresAt());
        String status = "pending".equals(r.status()) && !pending ? "expired" : r.status();
        ApprovalAction action = actions.get(r.actionType());
        boolean canDecide = pending
                && action != null
                && !r.requestedBy().equals(principal.userId())
                && principal.may(action.checkerPermission(), r.branchId());
        boolean canCancel = pending && r.requestedBy().equals(principal.userId());
        return new ApprovalResponse(
                r.id(),
                r.actionType(),
                r.subjectType(),
                r.subjectId(),
                r.branchId(),
                r.amountMinor(),
                r.currency(),
                status,
                r.requestedBy(),
                r.requestedByName(),
                r.requestedAt(),
                r.expiresAt(),
                r.decidedBy(),
                r.decidedAt(),
                r.decisionNote(),
                r.executionError(),
                withPayload ? r.payload() : null,
                canDecide,
                canCancel);
    }

    private boolean visible(Principal principal, Row r) {
        if (r.requestedBy().equals(principal.userId())) {
            return true;
        }
        ApprovalAction action = actions.get(r.actionType());
        return action != null && principal.may(action.checkerPermission(), r.branchId());
    }

    /** A request the principal may not see is indistinguishable from a missing one. */
    private Row lockVisible(Principal principal, UUID approvalId) {
        return jdbc.sql(
                        "SELECT " + COLUMNS
                                + " FROM approval_requests r LEFT JOIN users u ON u.id = r.requested_by WHERE r.id = ? FOR UPDATE OF r")
                .param(approvalId)
                .query(this::map)
                .optional()
                .filter(r -> visible(principal, r))
                .orElseThrow(ApiException::notFound);
    }

    private Optional<Row> find(UUID approvalId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM approval_requests r LEFT JOIN users u ON u.id = r.requested_by WHERE r.id = ?")
                .param(approvalId)
                .query(this::map)
                .optional();
    }

    private void refuseMakerAndConflicts(Principal checker, Row row, ApprovalAction action) {
        if (row.requestedBy().equals(checker.userId())) {
            throw ApiException.rule("self_approval_forbidden", "The checker cannot be the maker of the request.");
        }
        if (action.conflictedDeciders(row.subjectId(), row.payload()).contains(checker.userId())) {
            throw ApiException.rule("approver_conflict", "You took part in this subject and cannot decide it.");
        }
    }

    private void requirePending(Row row) {
        if (!"pending".equals(row.status())) {
            throw notPending(row.status());
        }
        if (!clock.now().isBefore(row.expiresAt())) {
            throw ApiException.rule("approval_expired", "The request expired.");
        }
    }

    private void setStatus(Row row, String status, UUID decidedBy, String note, Instant at) {
        jdbc.sql("""
                        UPDATE approval_requests SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?,
                               updated_at = now(), version = version + 1 WHERE id = ?
                        """)
                .params(status, decidedBy, Timestamp.from(at), note, row.id())
                .update();
    }

    private AuditLog.Entry transition(Row row, String action, String status) {
        return new AuditLog.Entry(
                action,
                "core.approval_request",
                row.id(),
                row.branchId(),
                Map.of("status", row.status()),
                Map.of("status", status, "action_type", row.actionType(), "subject_id", row.subjectId()));
    }

    private static Map<String, Object> summary(ActionRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action_type", request.actionType());
        m.put("subject_id", request.subjectId());
        m.put("amount_minor", request.amountMinor());
        m.put("currency", request.currency());
        return m;
    }

    private ApprovalAction action(String actionType) {
        ApprovalAction action = actions.get(actionType);
        if (action == null) {
            throw ApiException.rule("unknown_action_type", "No module handles action type " + actionType + ".");
        }
        return action;
    }

    private static ApiException notPending(String status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "invalid_status_transition",
                "Invalid status transition",
                "The request is " + status + ", not pending.");
    }

    private static ApiException denied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                "Permission denied",
                "You do not have permission to perform this action.");
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("action_type"),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("amount_minor", Long.class),
                rs.getString("currency"),
                mapper.readValue(rs.getString("payload"), MAP),
                rs.getObject("subject_version", Integer.class),
                rs.getString("status"),
                rs.getObject("requested_by", UUID.class),
                rs.getString("requested_by_name"),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("expires_at")),
                rs.getObject("decided_by", UUID.class),
                instant(rs.getTimestamp("decided_at")),
                rs.getString("decision_note"),
                rs.getString("execution_error"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
