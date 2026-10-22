package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.platform.internal.PlanController.ChangeResponse;
import com.rincoltech.bms.core.platform.internal.PlanController.ModuleRequestResponse;
import com.rincoltech.bms.core.platform.internal.PlanController.PlanResponse;
import com.rincoltech.bms.core.platform.internal.PlanController.PlanStatusResponse;
import com.rincoltech.bms.core.platform.internal.PlatformController.TenantResponse;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Owner's own plan (issue #472, ADR-073): ask for a module, stop using one, cancel the subscription and undo a change
 * still to come; the operator's decision on a module request; and the nightly apply of the changes that are due. A change
 * takes effect the day after the last day paid for, or at once when nothing is paid ahead (a free month, a pilot, a late
 * business). Modules are switched through {@link PlatformService#setModules} (data kept), the subscription is ended through
 * {@code platform_end_subscription}; every act is audited in the business's log and in {@code platform_audit_log}.
 */
@Service
class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    static final String DECIDED = "platform.plan_request_decided";
    static final String OPERATOR_NOTICE = "platform.plan_change_for_operator";

    private final JdbcClient jdbc;
    private final PlatformService platform;
    private final BusinessService business;
    private final TenantAdmins admins;
    private final PolicyChangeGuard proof;
    private final AuditLog tenantAudit;
    private final PlatformAuditLog audit;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final PlatformHost hosts;
    private final BusinessClock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate savepoint;
    private final Set<String> moduleKeys;

    PlanService(
            JdbcClient jdbc,
            PlatformService platform,
            BusinessService business,
            TenantAdmins admins,
            PolicyChangeGuard proof,
            AuditLog tenantAudit,
            PlatformAuditLog audit,
            Outbox outbox,
            CurrentTenant tenant,
            PlatformHost hosts,
            BusinessClock clock,
            PlatformTransactionManager transactionManager,
            List<ModuleManifest> manifests) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.business = business;
        this.admins = admins;
        this.proof = proof;
        this.tenantAudit = tenantAudit;
        this.audit = audit;
        this.outbox = outbox;
        this.tenant = tenant;
        this.hosts = hosts;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.moduleKeys = manifests.stream().map(ModuleManifest::key).collect(Collectors.toUnmodifiableSet());
    }

    /** What the plan card shows: the plan, the modules on, the dates, the requests and the changes still to come. */
    PlanResponse plan() {
        return transactions.execute(status -> {
            Sub sub = subscription();
            LocalDate today = today();
            boolean owner = isOwner();
            return new PlanResponse(
                    sub.planCode(),
                    sub.planName(),
                    sub.status(),
                    sub.trialEndsOn(),
                    sub.paidUntil(),
                    sub.pilot(),
                    sub.modules(),
                    moduleKeys.stream()
                            .filter(m -> !sub.modules().contains(m))
                            .sorted()
                            .toList(),
                    sub.allowed(),
                    effectiveOn(sub, today),
                    !effectiveOn(sub, today).isAfter(today),
                    today,
                    cancellationOn(),
                    sub.endedOn(),
                    owner,
                    requests(),
                    changes());
        });
    }

    /** The banner and the read-only notice every member of staff sees: when the subscription ends, or ended. */
    PlanStatusResponse status() {
        return transactions.execute(status ->
                new PlanStatusResponse(cancellationOn(), subscription().endedOn()));
    }

    /**
     * The Owner asks for a module. A module the plan already includes is switched on at once (approved by the system, the
     * operator told); any other waits for the operator. One pending request per module.
     */
    ModuleRequestResponse requestModule(String module) {
        requireKnown(module);
        UUID actor = requireOwner();
        return transactions.execute(status -> {
            Sub sub = subscription();
            if (sub.modules().contains(module)) {
                throw ApiException.rule("module_already_on", "The business already uses " + label(module) + ".");
            }
            UUID id = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO subscription_module_requests (id, tenant_id, module_key, requested_by, plan_before)
                                VALUES (?, ?, ?, ?, ?)
                                """)
                        .params(id, tenant.profile().id(), module, actor, sub.planCode())
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "module_request_pending",
                        "Already asked",
                        "You already asked for " + label(module) + ". The platform operator will answer soon.");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("module", module);
            data.put("plan", sub.planCode());
            data.put("owner_user_id", actor);
            tenantAudit.record(AuditLog.Entry.created("core.plan.module_requested", "core.plan", id, null, data));
            audit.record(
                    "platform.tenant.module_requested", null, tenant.profile().id(), data);
            if (sub.allowed().contains(module)) {
                List<String> modules = new ArrayList<>(sub.modules());
                modules.add(module);
                platform.switchModules(
                        tenant.profile().id(), modules, null, "Asked for by the Owner; the plan includes it");
                decide(id, "approved", true, null, sub.planCode(), null);
                tellOperators(
                        "asked for " + label(module) + ", which the plan includes, so it was switched on at once");
            } else {
                tellOperators("asked for " + label(module) + ". The plan does not include it: approve or decline it");
            }
            return request(id);
        });
    }

    /**
     * The Owner stops using a module, after the step-up. In force the day after the last day paid for, or at once when
     * nothing is paid ahead; the records are kept and come back when it is switched on again. A business keeps at least one
     * module, counting the stops already scheduled.
     */
    ChangeResponse stopModule(String module, String totpCode, String password) {
        requireKnown(module);
        requireOwner();
        transactions.execute(status -> {
            checkStop(subscription(), module);
            return null;
        });
        proof.requireProof(totpCode, password);
        return schedule("stop_module", module);
    }

    /** The Owner cancels the subscription, after the step-up: in force the day after the last day paid for. */
    ChangeResponse cancel(String totpCode, String password) {
        requireOwner();
        transactions.execute(status -> {
            checkCancel();
            return null;
        });
        proof.requireProof(totpCode, password);
        return schedule("cancel", null);
    }

    /**
     * A module stop is refused when the module is off, already stopping, or the last one staying: the modules on less the
     * stops already scheduled of modules still on.
     */
    private void checkStop(Sub sub, String module) {
        if (!sub.modules().contains(module)) {
            throw ApiException.rule("module_not_on", "The business does not use " + label(module) + ".");
        }
        List<String> stopping =
                scheduledStops().stream().filter(sub.modules()::contains).toList();
        if (stopping.contains(module)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "change_already_scheduled",
                    "Already scheduled",
                    "Stopping " + label(module) + " is already scheduled.");
        }
        if (sub.modules().size() - stopping.size() - 1 < 1) {
            throw ApiException.rule(
                    "last_module",
                    "A business keeps at least one module. To stop using everything, cancel the subscription"
                            + " instead.");
        }
    }

    private void checkCancel() {
        if (cancellationOn() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "change_already_scheduled",
                    "Already scheduled",
                    "The cancellation is already scheduled.");
        }
    }

    /** The Owner undoes a change still to come, before its day: the business goes on as before. */
    ChangeResponse undo(UUID changeId) {
        UUID actor = requireOwner();
        return transactions.execute(status -> {
            Change change = jdbc.sql(CHANGE_SELECT + " WHERE id = ? FOR UPDATE")
                    .param(changeId)
                    .query(PlanService::change)
                    .optional()
                    .orElseThrow(ApiException::notFound);
            if (!"scheduled".equals(change.status())) {
                throw ApiException.rule(
                        "change_not_scheduled", "That change is no longer waiting, so it cannot be undone.");
            }
            if (!change.effectiveOn().isAfter(today())) {
                throw ApiException.rule(
                        "change_already_due", "That change is due today and is being applied, so it cannot be undone.");
            }
            jdbc.sql(
                            "UPDATE subscription_scheduled_changes SET status = 'undone', undone_at = now(), undone_by = ? WHERE id = ?")
                    .params(actor, changeId)
                    .update();
            Map<String, Object> data = changeData(change);
            data.put("owner_user_id", actor);
            tenantAudit.record(AuditLog.Entry.created("core.plan.change_undone", "core.plan", changeId, null, data));
            audit.record(
                    "platform.tenant.plan_change_undone", null, tenant.profile().id(), data);
            tellOperators("undid " + what(change.kind(), change.moduleKey()) + ", which was due on "
                    + DAY.format(change.effectiveOn()));
            return changeResponse(changeId);
        });
    }

    private ChangeResponse schedule(String kind, String module) {
        UUID actor = CurrentPrincipal.require().userId();
        return transactions.execute(status -> {
            // Under the subscription's row lock the checks are made again, so two stops or two cancellations sent at
            // once cannot both pass: the second waits, then sees the first.
            jdbc.sql("SELECT app_lock_subscription(?)")
                    .param(tenant.profile().id())
                    .query(String.class)
                    .optional();
            Sub sub = subscription();
            if ("cancel".equals(kind)) {
                checkCancel();
            } else {
                checkStop(sub, module);
            }
            LocalDate today = today();
            LocalDate effective = effectiveOn(sub, today);
            UUID id = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO subscription_scheduled_changes
                                    (id, tenant_id, kind, module_key, effective_on, requested_by)
                                VALUES (?, ?, ?, ?, ?, ?)
                                """)
                        .params(id, tenant.profile().id(), kind, module, effective, actor)
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "change_already_scheduled",
                        "Already scheduled",
                        "That is already scheduled.");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", kind);
            data.put("module", module);
            data.put("effective_on", effective.toString());
            data.put(
                    "paid_until",
                    sub.paidUntil() == null ? null : sub.paidUntil().toString());
            data.put("owner_user_id", actor);
            tenantAudit.record(AuditLog.Entry.created("core.plan.change_scheduled", "core.plan", id, null, data));
            audit.record(
                    "platform.tenant.plan_change_scheduled",
                    null,
                    tenant.profile().id(),
                    data);
            if (effective.isAfter(today)) {
                tellOperators(("cancel".equals(kind)
                                ? "cancelled the subscription; the business becomes read only on "
                                : "stopped using " + label(module) + "; it is switched off on ")
                        + DAY.format(effective));
            } else {
                apply(id);
                tellOperators(("cancel".equals(kind) ? "cancelled the subscription" : "stopped using " + label(module))
                        + ", at once, as nothing was paid ahead");
            }
            return changeResponse(id);
        });
    }

    /**
     * The nightly job's work for one business, in a transaction bound to it: every scheduled change whose day has come is
     * applied once (the row is locked and moved to applied with the change), so a second run, or a run that overlaps,
     * changes nothing. Returns how many were applied.
     */
    int applyDue() {
        return transactions.execute(status -> applyDue(today()));
    }

    /** As {@link #applyDue()}, as of a given business date, in the caller's transaction or its own. */
    int applyDue(LocalDate today) {
        return transactions.execute(status -> {
            List<UUID> due = jdbc.sql("""
                            SELECT id FROM subscription_scheduled_changes
                             WHERE status = 'scheduled' AND effective_on <= ?
                             ORDER BY kind DESC, effective_on, created_at, id
                            """).param(today).query(UUID.class).list();
            int applied = 0;
            for (UUID id : due) {
                if (apply(id)) {
                    applied++;
                }
            }
            return applied;
        });
    }

    /** Today in the bound business's own time zone. */
    LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    /** Applies one scheduled change in the caller's transaction, if it is still scheduled. */
    private boolean apply(UUID id) {
        Change change = jdbc.sql(CHANGE_SELECT + " WHERE id = ? FOR UPDATE")
                .param(id)
                .query(PlanService::change)
                .optional()
                .orElse(null);
        if (change == null || !"scheduled".equals(change.status())) {
            return false;
        }
        UUID tenantId = tenant.profile().id();
        if ("stop_module".equals(change.kind())) {
            List<String> modules = new ArrayList<>(subscription().modules());
            if (!modules.remove(change.moduleKey()) || modules.isEmpty()) {
                // Nothing to switch off (the module is already off), or it is the last one: the row is closed as
                // undone, not applied, so nothing claims a change that did not happen.
                log.warn("a scheduled stop of module {} of tenant {} changed nothing", change.moduleKey(), tenantId);
                jdbc.sql("UPDATE subscription_scheduled_changes SET status = 'undone', undone_at = now() WHERE id = ?")
                        .param(id)
                        .update();
                return false;
            }
            platform.switchModules(tenantId, modules, null, "Stopped by the Owner from " + change.effectiveOn());
        } else {
            String before = jdbc.sql("SELECT platform_end_subscription(?, ?)")
                    .params(tenantId, change.effectiveOn())
                    .query(String.class)
                    .single();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("before", before);
            data.put("after", "cancelled");
            data.put("ended_on", change.effectiveOn().toString());
            audit.record("platform.tenant.subscription_ended", null, tenantId, data);
        }
        jdbc.sql("UPDATE subscription_scheduled_changes SET status = 'applied', applied_at = now() WHERE id = ?")
                .param(id)
                .update();
        Map<String, Object> data = changeData(change);
        tenantAudit.record(
                AuditLog.Entry.created("core.plan.change_applied", "core.plan", id, null, data), null, "system");
        audit.record("platform.tenant.plan_change_applied", null, tenantId, data);
        return true;
    }

    /**
     * The operator's decision on a module request, in a transaction bound to that business. Approving switches the module
     * on, moving the plan first when one is given (the plan or the price may change); declining keeps a reason the Owner
     * sees. The Owner is emailed either way. A request already decided is refused.
     */
    ModuleRequestResponse decideRequest(UUID requestId, boolean approve, String planCode, String reason) {
        UUID operator = CurrentPrincipal.require().userId();
        UUID tenantId = transactions.execute(status -> jdbc.sql("SELECT platform_module_request_tenant(?)")
                .param(requestId)
                .query(UUID.class)
                .optional()
                .orElse(null));
        if (tenantId == null) {
            throw ApiException.notFound();
        }
        return TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    ModuleRequestResponse request = jdbc.sql(REQUEST_SELECT + " WHERE r.id = ? FOR UPDATE OF r")
                            .param(requestId)
                            .query(PlanService::request)
                            .single();
                    if (!"pending".equals(request.status())) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "request_already_decided",
                                "Already decided",
                                "This request was already decided.");
                    }
                    TenantResponse before = platform.tenant(tenantId);
                    String planAfter = before.planCode();
                    if (approve) {
                        if (planCode != null && !planCode.isBlank() && !planCode.equals(before.planCode())) {
                            business.setPlan(tenantId, planCode, reason);
                            planAfter = planCode;
                        }
                        List<String> modules =
                                new ArrayList<>(platform.tenant(tenantId).modules());
                        if (!modules.contains(request.moduleKey())) {
                            modules.add(request.moduleKey());
                            platform.switchModules(tenantId, modules, operator, reason);
                        }
                    }
                    decide(requestId, approve ? "approved" : "declined", false, operator, planAfter, reason.trim());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("request_id", requestId);
                    data.put("module", request.moduleKey());
                    data.put("decision", approve ? "approved" : "declined");
                    data.put("plan_before", before.planCode());
                    data.put("plan_after", planAfter);
                    data.put("reason", reason.trim());
                    audit.record("platform.tenant.module_request_decided", operator, tenantId, data);
                    tellOwner(requestId, request.moduleKey(), approve, reason.trim());
                    return request(requestId);
                }));
    }

    private void decide(UUID id, String status, boolean automatic, UUID operator, String planAfter, String reason) {
        jdbc.sql("""
                        UPDATE subscription_module_requests
                           SET status = ?, decided_at = now(), decided_automatically = ?, decided_by_platform_user_id = ?,
                               plan_after = ?, decision_reason = ?
                         WHERE id = ?
                        """).params(status, automatic, operator, planAfter, reason, id).update();
    }

    /** The operator's lists across businesses. */
    List<PlanController.QueuedRequest> moduleRequests(String status) {
        return transactions.execute(tx -> jdbc.sql("SELECT * FROM platform_module_requests(?)")
                .param(status)
                .query((rs, n) -> new PlanController.QueuedRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("tenant_name"),
                        rs.getString("plan_code"),
                        rs.getBoolean("plan_includes"),
                        rs.getString("module_key"),
                        rs.getString("status"),
                        rs.getString("requested_by_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        PlatformService.instant(rs.getTimestamp("decided_at")),
                        rs.getBoolean("decided_automatically"),
                        rs.getString("decision_reason")))
                .list());
    }

    List<PlanController.QueuedChange> scheduledChanges() {
        return transactions.execute(tx -> jdbc.sql("SELECT * FROM platform_scheduled_changes()")
                .query((rs, n) -> new PlanController.QueuedChange(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("tenant_name"),
                        rs.getString("kind"),
                        rs.getString("module_key"),
                        rs.getObject("effective_on", LocalDate.class),
                        rs.getString("requested_by_name"),
                        rs.getTimestamp("created_at").toInstant()))
                .list());
    }

    /** The day a change asked for now takes effect: the day after the last day paid for, or today. */
    static LocalDate effectiveOn(Sub sub, LocalDate today) {
        if (sub.paidUntil() != null && !sub.paidUntil().isBefore(today)) {
            return sub.paidUntil().plusDays(1);
        }
        return today;
    }

    private UUID requireOwner() {
        UUID actor = CurrentPrincipal.require().userId();
        Optional<TenantAdmins.Owner> owner = transactions.execute(status -> admins.owner());
        if (owner.isEmpty() || !owner.get().userId().equals(actor)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "owner_only",
                    "Owner only",
                    "Only the Owner of the business can change the plan and the subscription.");
        }
        return actor;
    }

    private boolean isOwner() {
        UUID actor = CurrentPrincipal.require().userId();
        return admins.owner().map(o -> o.userId().equals(actor)).orElse(false);
    }

    private void requireKnown(String module) {
        if (module == null || !moduleKeys.contains(module)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("module", "invalid", "Unknown module " + module + ".")));
        }
    }

    record Sub(
            String planCode,
            String planName,
            String status,
            LocalDate trialEndsOn,
            LocalDate paidUntil,
            boolean pilot,
            LocalDate endedOn,
            List<String> modules,
            List<String> allowed) {}

    /** The bound business's plan and subscription, read under its row-level security. */
    private Sub subscription() {
        return jdbc.sql("""
                        SELECT p.code, p.name, p.allowed_modules, s.status, s.trial_ends_on, s.current_period_end,
                               coalesce(s.is_pilot, false) AS is_pilot, s.ended_on,
                               coalesce((SELECT array_agg(m.module_key ORDER BY m.module_key) FROM tenant_modules m), '{}')
                                   AS modules
                          FROM tenants t
                          JOIN plans p ON p.id = t.plan_id
                          LEFT JOIN subscriptions s ON s.tenant_id = t.id
                        """)
                .query((rs, n) -> new Sub(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getObject("trial_ends_on", LocalDate.class),
                        rs.getObject("current_period_end", LocalDate.class),
                        rs.getBoolean("is_pilot"),
                        rs.getObject("ended_on", LocalDate.class),
                        strings(rs.getArray("modules")),
                        strings(rs.getArray("allowed_modules"))))
                .single();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private List<String> scheduledStops() {
        return jdbc.sql("""
                        SELECT module_key FROM subscription_scheduled_changes
                         WHERE status = 'scheduled' AND kind = 'stop_module'
                        """).query(String.class).list();
    }

    private LocalDate cancellationOn() {
        return jdbc.sql("""
                        SELECT effective_on FROM subscription_scheduled_changes WHERE status = 'scheduled' AND kind = 'cancel'
                        """).query(LocalDate.class).optional().orElse(null);
    }

    private static final String REQUEST_SELECT = """
            SELECT r.id, r.module_key, r.status, r.created_at, r.decided_at, r.decided_automatically, r.decision_reason,
                   u.full_name AS requested_by_name
              FROM subscription_module_requests r
              JOIN users u ON u.id = r.requested_by
            """;

    private static ModuleRequestResponse request(ResultSet rs, int n) throws SQLException {
        return new ModuleRequestResponse(
                rs.getObject("id", UUID.class),
                rs.getString("module_key"),
                rs.getString("status"),
                rs.getString("requested_by_name"),
                rs.getTimestamp("created_at").toInstant(),
                PlatformService.instant(rs.getTimestamp("decided_at")),
                rs.getBoolean("decided_automatically"),
                rs.getString("decision_reason"));
    }

    private ModuleRequestResponse request(UUID id) {
        return jdbc.sql(REQUEST_SELECT + " WHERE r.id = ?")
                .param(id)
                .query(PlanService::request)
                .single();
    }

    /** The latest 20 requests, newest first. */
    private List<ModuleRequestResponse> requests() {
        return jdbc.sql(REQUEST_SELECT + " ORDER BY r.created_at DESC, r.id LIMIT 20")
                .query(PlanService::request)
                .list();
    }

    record Change(UUID id, String kind, String moduleKey, LocalDate effectiveOn, String status, UUID requestedBy) {}

    private static final String CHANGE_SELECT =
            "SELECT id, kind, module_key, effective_on, status, requested_by FROM subscription_scheduled_changes";

    private static Change change(ResultSet rs, int n) throws SQLException {
        return new Change(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("module_key"),
                rs.getObject("effective_on", LocalDate.class),
                rs.getString("status"),
                rs.getObject("requested_by", UUID.class));
    }

    private static final String CHANGE_RESPONSE_SELECT = """
            SELECT c.id, c.kind, c.module_key, c.effective_on, c.status, c.created_at, c.applied_at,
                   u.full_name AS requested_by_name
              FROM subscription_scheduled_changes c
              JOIN users u ON u.id = c.requested_by
            """;

    private static ChangeResponse changeResponse(ResultSet rs, int n) throws SQLException {
        return new ChangeResponse(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("module_key"),
                rs.getObject("effective_on", LocalDate.class),
                rs.getString("status"),
                rs.getString("requested_by_name"),
                rs.getTimestamp("created_at").toInstant(),
                PlatformService.instant(rs.getTimestamp("applied_at")));
    }

    private ChangeResponse changeResponse(UUID id) {
        return jdbc.sql(CHANGE_RESPONSE_SELECT + " WHERE c.id = ?")
                .param(id)
                .query(PlanService::changeResponse)
                .single();
    }

    /** The changes still to come, soonest first, and the latest ten applied or undone. */
    private List<ChangeResponse> changes() {
        return jdbc.sql(CHANGE_RESPONSE_SELECT + """
                         WHERE c.status = 'scheduled'
                            OR c.id IN (SELECT id FROM subscription_scheduled_changes WHERE status <> 'scheduled'
                                         ORDER BY created_at DESC LIMIT 10)
                         ORDER BY c.status = 'scheduled' DESC, c.effective_on, c.created_at DESC
                        """)
                .query(PlanService::changeResponse)
                .list();
    }

    private static Map<String, Object> changeData(Change change) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("change_id", change.id());
        data.put("kind", change.kind());
        data.put("module", change.moduleKey());
        data.put("effective_on", change.effectiveOn().toString());
        data.put("owner_user_id", change.requestedBy());
        return data;
    }

    /** A module's name as people say it. */
    static String label(String module) {
        return switch (module) {
            case "retail" -> "Shop";
            case "lending" -> "Lending";
            default -> module.substring(0, 1).toUpperCase(Locale.ROOT) + module.substring(1);
        };
    }

    private static String what(String kind, String module) {
        return "stop_module".equals(kind) ? "stopping " + label(module) : "cancelling the subscription";
    }

    /**
     * One email per active platform operator, in a savepoint inside the caller's transaction: a failure is logged and the
     * change is kept (the operator also sees it in the console lists).
     */
    private void tellOperators(String change) {
        try {
            savepoint.executeWithoutResult(tx -> {
                record Operator(UUID id, String email) {}
                List<Operator> operators = jdbc.sql("SELECT id, email FROM platform_users WHERE is_active ORDER BY id")
                        .query((rs, n) -> new Operator(rs.getObject("id", UUID.class), rs.getString("email")))
                        .list();
                String key = UUID.randomUUID().toString();
                for (Operator operator : operators) {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("business_name", tenant.profile().name());
                    params.put("change", change);
                    params.put("portal_url", hosts.platformOrigin() + "/platform");
                    outbox.enqueue(new Outbox.Message(
                            Outbox.EMAIL,
                            operator.email(),
                            OPERATOR_NOTICE,
                            params,
                            OPERATOR_NOTICE + ":" + key + ":" + operator.id()));
                }
            });
        } catch (RuntimeException e) {
            log.warn(
                    "the operator email for a plan change was not queued: {}",
                    e.getClass().getSimpleName());
        }
    }

    /** The Owner's notice of the operator's decision, by email; the plan card shows it too. */
    private void tellOwner(UUID requestId, String module, boolean approved, String reason) {
        Optional<TenantAdmins.Owner> owner = admins.owner();
        if (owner.isEmpty() || owner.get().email() == null) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("business_name", tenant.profile().name());
        params.put("module", label(module));
        params.put(
                "decision",
                approved
                        ? "approved: " + label(module) + " is switched on now"
                        : "declined. The platform operator wrote: " + reason);
        params.put("link", hosts.tenantOrigin(tenant.profile().slug()) + "/staff/setup");
        outbox.enqueue(new Outbox.Message(
                Outbox.EMAIL,
                owner.get().email(),
                DECIDED,
                params,
                DECIDED + ":" + requestId + ":" + owner.get().userId()));
    }
}
