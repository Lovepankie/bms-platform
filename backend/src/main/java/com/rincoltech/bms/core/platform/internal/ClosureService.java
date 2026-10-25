package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.identity.TenantAdmins;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.platform.internal.ClosureAdminController.QueuedClosure;
import com.rincoltech.bms.core.platform.internal.ClosureAdminController.Removed;
import com.rincoltech.bms.core.platform.internal.ClosureController.ClosureView;
import com.rincoltech.bms.core.reporting.DataExports;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.PersonalDataEraser;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Closing a business (issue #571, ADR-090). The Owner asks, after the step-up: the business is read only from that moment
 * (the resolver answers {@code closing}), a full data export is queued for the Owner, the Owner and every platform operator are
 * emailed, and the request may be cancelled by the Owner until the business is removed. After the cooling-off period the
 * operator removes it, with their own authenticator code and a reason: in one transaction bound to the business, the core's
 * eraser and every module's {@link PersonalDataEraser} delete or anonymise its personal data, the money records stay without
 * it until {@link #keepUntil}, when the daily job deletes them ({@link #purgeDue}), and the business is closed. Every act is audited in the business's log and the platform's.
 */
@Service
class ClosureService {

    private static final Logger log = LoggerFactory.getLogger(ClosureService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    static final String REQUESTED = "platform.closure_requested";
    static final String CANCELLED = "platform.closure_cancelled";

    private final JdbcClient jdbc;
    private final TenantAdmins admins;
    private final PolicyChangeGuard proof;
    private final AuditLog tenantAudit;
    private final PlatformAuditLog audit;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final PlatformHost hosts;
    private final BusinessClock clock;
    private final DataExports exports;
    private final Documents documents;
    private final PlanService plans;
    private final List<PersonalDataEraser> erasers;
    private final TransactionTemplate transactions;
    private final TransactionTemplate savepoint;
    private final int coolingOffDays;
    private final int retentionYears;
    private final ObjectMapper json;

    ClosureService(
            JdbcClient jdbc,
            TenantAdmins admins,
            PolicyChangeGuard proof,
            AuditLog tenantAudit,
            PlatformAuditLog audit,
            Outbox outbox,
            CurrentTenant tenant,
            PlatformHost hosts,
            BusinessClock clock,
            DataExports exports,
            Documents documents,
            PlanService plans,
            List<PersonalDataEraser> erasers,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            @Value("${bms.platform.closure-cooling-off-days:30}") int coolingOffDays,
            @Value("${bms.platform.closed-retention-years:5}") int retentionYears) {
        this.jdbc = jdbc;
        this.admins = admins;
        this.proof = proof;
        this.tenantAudit = tenantAudit;
        this.audit = audit;
        this.outbox = outbox;
        this.tenant = tenant;
        this.hosts = hosts;
        this.clock = clock;
        this.exports = exports;
        this.documents = documents;
        this.plans = plans;
        this.erasers = erasers;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.coolingOffDays = coolingOffDays;
        if (retentionYears < 1) {
            throw new IllegalStateException("BMS_CLOSED_RETENTION_YEARS must be at least 1");
        }
        this.retentionYears = retentionYears;
    }

    record Open(
            UUID id, java.time.Instant requestedAt, String requestedByName, LocalDate removeAfter, UUID exportRunId) {}

    private Optional<Open> open() {
        return jdbc.sql("""
                        SELECT k.id, k.requested_at, k.remove_after, k.export_run_id, u.full_name
                          FROM tenant_closures k JOIN users u ON u.id = k.requested_by
                         WHERE k.status IN ('requested', 'removing')
                        """)
                .query((rs, n) -> new Open(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getString("full_name"),
                        rs.getObject("remove_after", LocalDate.class),
                        rs.getObject("export_run_id", UUID.class)))
                .optional();
    }

    /** The card: the open request, if any, and whether the caller is the Owner. */
    ClosureView view() {
        return transactions.execute(status -> {
            UUID actor = CurrentPrincipal.require().userId();
            boolean owner = admins.owner().map(o -> o.userId().equals(actor)).orElse(false);
            return open().map(o -> new ClosureView(
                            "requested",
                            o.id(),
                            o.requestedAt(),
                            o.requestedByName(),
                            o.removeAfter(),
                            o.exportRunId(),
                            coolingOffDays,
                            owner))
                    .orElse(new ClosureView("none", null, null, null, null, null, coolingOffDays, owner));
        });
    }

    /** For the read-only notice every member of staff sees: the first day of removal while a request is open. */
    LocalDate removeAfter() {
        return open().map(Open::removeAfter).orElse(null);
    }

    /**
     * The Owner asks to close the business, after the step-up. Checked before the step-up (so a code is not spent on a call
     * that would be refused) and again after it, under the unique index of one open request.
     */
    ClosureView request(String totpCode, String password) {
        UUID actor = requireOwner();
        transactions.execute(status -> {
            requireNoOpenRequest();
            return null;
        });
        proof.requireProof(totpCode, password);
        return transactions.execute(status -> {
            requireNoOpenRequest();
            LocalDate today = clock.today(tenant.profile().timezone());
            LocalDate removeAfter = today.plusDays(coolingOffDays);
            UUID id = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO tenant_closures (id, tenant_id, requested_by, remove_after)
                                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                                """).params(id, actor, removeAfter).update();
            } catch (DuplicateKeyException e) {
                throw alreadyRequested();
            }
            UUID exportRun = queueExport(actor);
            if (exportRun != null) {
                jdbc.sql("UPDATE tenant_closures SET export_run_id = ? WHERE id = ?")
                        .params(exportRun, id)
                        .update();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("closure_id", id);
            data.put("remove_after", removeAfter.toString());
            data.put("export_run_id", exportRun);
            data.put("owner_user_id", actor);
            tenantAudit.record(
                    AuditLog.Entry.created("core.business.closure_requested", "core.business_closure", id, null, data));
            audit.record(
                    "platform.tenant.closure_requested", null, tenant.profile().id(), data);
            plans.tellOperators("asked to close the business. It is read only now; it may be removed from "
                    + DAY.format(removeAfter));
            tellOwner(REQUESTED, id, removeAfter);
            return view();
        });
    }

    /** The Owner cancels the open request; the business works again at once. */
    ClosureView cancel() {
        UUID actor = requireOwner();
        return transactions.execute(status -> {
            UUID id = jdbc.sql("SELECT id FROM tenant_closures WHERE status = 'requested' FOR UPDATE")
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> ApiException.rule(
                            "no_closure_request", "There is no request to close this business to cancel."));
            jdbc.sql("""
                            UPDATE tenant_closures SET status = 'cancelled', cancelled_by = ?, cancelled_at = now()
                             WHERE id = ?
                            """).params(actor, id).update();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("closure_id", id);
            data.put("owner_user_id", actor);
            tenantAudit.record(
                    AuditLog.Entry.created("core.business.closure_cancelled", "core.business_closure", id, null, data));
            audit.record(
                    "platform.tenant.closure_cancelled", null, tenant.profile().id(), data);
            plans.tellOperators("cancelled the request to close the business. It works again as before");
            tellOwner(CANCELLED, id, null);
            return view();
        });
    }

    /** The operator's list across businesses, with whether each open request may be removed today. */
    List<QueuedClosure> list() {
        return transactions.execute(status -> jdbc.sql("SELECT * FROM platform_closures()")
                .query((rs, n) -> {
                    LocalDate removeAfter = rs.getObject("remove_after", LocalDate.class);
                    String state = rs.getString("status");
                    LocalDate today = clock.today(ZoneId.of(rs.getString("timezone")));
                    return new QueuedClosure(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("tenant_slug"),
                            rs.getString("tenant_name"),
                            state,
                            rs.getTimestamp("requested_at").toInstant(),
                            rs.getString("requested_by_name"),
                            removeAfter,
                            ("requested".equals(state) || "removing".equals(state)) && !removeAfter.isAfter(today),
                            PlatformService.instant(rs.getTimestamp("cancelled_at")),
                            PlatformService.instant(rs.getTimestamp("removed_at")),
                            rs.getObject("keep_until", LocalDate.class),
                            rs.getString("removed_by_name"));
                })
                .list());
    }

    private UUID tenantOf(UUID closureId) {
        UUID tenantId = transactions.execute(status -> jdbc.sql("SELECT platform_closure_tenant(?)")
                .param(closureId)
                .query(UUID.class)
                .optional()
                .orElse(null));
        if (tenantId == null) {
            throw ApiException.notFound();
        }
        return tenantId;
    }

    /**
     * Everything {@link #remove} would refuse, read-only, so the operator's code is not spent on it: an unknown request (404),
     * one no longer open (409 {@code closure_not_open}) and one still in its cooling-off period (422 {@code cooling_off}).
     */
    void requireRemovable(UUID closureId) {
        UUID tenantId = tenantOf(closureId);
        TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    checkRemovable(closureId);
                    return null;
                }));
    }

    private void checkRemovable(UUID closureId) {
        record Row(String status, LocalDate removeAfter) {}
        Row row = jdbc.sql("SELECT status, remove_after FROM tenant_closures WHERE id = ?")
                .param(closureId)
                .query((rs, n) -> new Row(rs.getString("status"), rs.getObject("remove_after", LocalDate.class)))
                .optional()
                .orElseThrow(ApiException::notFound);
        // A request the operator began whose erasure failed is still removing: removing it again resumes it.
        if (!"requested".equals(row.status()) && !"removing".equals(row.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "closure_not_open",
                    "Not open",
                    "This request is no longer open: it was cancelled or the business was removed already.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        if (row.removeAfter().isAfter(today)) {
            throw ApiException.rule(
                    "cooling_off",
                    "The business can be removed from " + DAY.format(row.removeAfter())
                            + ". Until then its owner can still cancel the closing.");
        }
    }

    /**
     * The operator removes the business, in two steps. First, bound to no business, {@code platform_begin_tenant_removal}
     * authenticates the operator (their live platform session and the authenticator code they just used), checks the
     * cooling-off against the business's own date, and moves the request to removing. Then, in one transaction bound to the
     * business, the request is marked for the erasers, the core's eraser and every module's run, and the removal ends: the
     * business closed, the request removed with the day its money records may be deleted, a row in the platform's log with the
     * reason. A failure there rolls the erasure back and leaves the request removing; removing it again resumes.
     */
    Removed remove(UUID closureId, String reason) {
        Principal principal = CurrentPrincipal.require();
        UUID tenantId;
        try {
            tenantId = transactions.execute(status -> jdbc.sql("SELECT platform_begin_tenant_removal(?, ?, ?, ?)")
                    .params(closureId, principal.userId(), principal.sessionId(), reason.trim())
                    .query(UUID.class)
                    .optional()
                    .orElse(null));
        } catch (DataAccessException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("cooling_off")) {
                throw ApiException.rule(
                        "cooling_off",
                        "The business cannot be removed yet. Until then its owner can still cancel the closing.");
            }
            if (message.contains("operator_not_verified")) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "operator_not_verified",
                        "Code needed",
                        "Type a current code from your own authenticator app and remove again.");
            }
            throw e;
        }
        if (tenantId == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "closure_not_open", "Not open", "This request is no longer open.");
        }
        return TenantContext.callAs(
                tenantId,
                () -> transactions.execute(status -> {
                    jdbc.sql("SELECT app_mark_tenant_removal(?)")
                            .param(closureId)
                            .query(Boolean.class)
                            .single();
                    // The business's own log keeps the removal as the operator's act; its reason is in the platform's
                    // log, which keeps it.
                    tenantAudit.record(
                            AuditLog.Entry.created(
                                    "core.business.removed",
                                    "core.business_closure",
                                    closureId,
                                    null,
                                    Map.of("closure_id", closureId)),
                            null,
                            "platform");
                    Map<String, Integer> erased = new LinkedHashMap<>();
                    erased.put(
                            "core",
                            jdbc.sql("SELECT app_erase_core_personal_data()")
                                    .query(Integer.class)
                                    .single());
                    for (PersonalDataEraser eraser : erasers) {
                        erased.merge(eraser.area(), eraser.erase(), Integer::sum);
                    }
                    LocalDate keepUntil = keepUntil(closedOn(closureId), financialYearEndMonth(), retentionYears);
                    jdbc.sql("SELECT platform_finish_tenant_removal(?, ?, ?::jsonb)")
                            .params(closureId, keepUntil, json.writeValueAsString(erased))
                            .query(String.class)
                            .single();
                    log.info("tenant {} removed after its Owner's request {}", tenantId, closureId);
                    return new Removed(closureId, tenantId, keepUntil, erased);
                }));
    }

    /** "The closing" of D46: the business's own date of the request, the day it went read only (ADR-090). */
    private LocalDate closedOn(UUID closureId) {
        java.time.Instant requested = jdbc.sql("SELECT requested_at FROM tenant_closures WHERE id = ?")
                .param(closureId)
                .query((rs, n) -> rs.getTimestamp("requested_at").toInstant())
                .single();
        return requested.atZone(tenant.profile().timezone()).toLocalDate();
    }

    /** The month the bound business's financial year ends in, from its settings; June (the Uganda tax year) when unset. */
    private int financialYearEndMonth() {
        return jdbc.sql("SELECT (settings ->> 'financial_year_end_month')::int FROM tenant_settings")
                .query(Integer.class)
                .optional()
                .filter(m -> m != null && m >= 1 && m <= 12)
                .orElse(Month.JUNE.getValue());
    }

    /**
     * The last day the kept money records are kept (owner decision D46, ADR-090): {@code years} ({@code
     * BMS_CLOSED_RETENTION_YEARS}, 5 by default) after the end of the financial year, ending in month {@code yearEndMonth}, in
     * which the business closed on {@code closedOn}. The daily job deletes them the day after.
     */
    static LocalDate keepUntil(LocalDate closedOn, int yearEndMonth, int years) {
        int endYear = closedOn.getMonthValue() <= yearEndMonth ? closedOn.getYear() : closedOn.getYear() + 1;
        return YearMonth.of(endYear + years, yearEndMonth).atEndOfMonth();
    }

    /**
     * The daily job's work (owner decision D46): every removed business past its {@code keep_until} in its own time zone has
     * every row it still holds deleted, money records included, each in its own transaction; one failure is logged and does
     * not stop the others. Returns how many businesses were purged.
     */
    int purgeDue() {
        List<UUID> due = transactions.execute(status -> jdbc.sql("SELECT platform_closures_due_for_purge()")
                .query(UUID.class)
                .list());
        int purged = 0;
        for (UUID closureId : due) {
            try {
                // The files first, while the rows still name them; a purge that then fails runs again the next night.
                UUID tenantId = tenantOf(closureId);
                TenantContext.callAs(tenantId, documents::deleteAllStoredFiles);
                Integer rows = transactions.execute(status -> jdbc.sql("SELECT platform_purge_closed_tenant(?)")
                        .param(closureId)
                        .query(Integer.class)
                        .single());
                log.info(
                        "closure {}: the kept records of the removed business were deleted ({} rows)", closureId, rows);
                purged++;
            } catch (RuntimeException e) {
                log.error("closure {}: the kept records could not be deleted", closureId, e);
            }
        }
        return purged;
    }

    private UUID queueExport(UUID actor) {
        try {
            return savepoint.execute(status -> exports.requestFullCopy(actor));
        } catch (RuntimeException e) {
            // The request stands without it: the Owner can still make the workbook from Download my data.
            log.warn(
                    "the data export for a closure request was not queued: {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private void requireNoOpenRequest() {
        if (open().isPresent()) {
            throw alreadyRequested();
        }
    }

    private static ApiException alreadyRequested() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "closure_already_requested",
                "Already asked",
                "Closing this business is already asked for. You can cancel it in Business set-up.");
    }

    private UUID requireOwner() {
        UUID actor = CurrentPrincipal.require().userId();
        Optional<TenantAdmins.Owner> owner = transactions.execute(status -> admins.owner());
        if (owner.isEmpty() || !owner.get().userId().equals(actor)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "owner_only",
                    "Owner only",
                    "Only the Owner of the business can ask to close it or cancel the closing.");
        }
        return actor;
    }

    /** The Owner's email of the request or its cancel, in a savepoint: a mail failure never refuses the Owner's act. */
    private void tellOwner(String template, UUID closureId, LocalDate removeAfter) {
        try {
            savepoint.executeWithoutResult(tx -> {
                Optional<TenantAdmins.Owner> owner = admins.owner();
                if (owner.isEmpty() || owner.get().email() == null) {
                    return;
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("owner_name", owner.get().fullName());
                params.put("business_name", tenant.profile().name());
                params.put("link", hosts.tenantOrigin(tenant.profile().slug()) + "/staff/setup");
                if (removeAfter != null) {
                    params.put("remove_after", DAY.format(removeAfter));
                }
                outbox.enqueue(new Outbox.Message(
                        Outbox.EMAIL,
                        owner.get().email(),
                        template,
                        params,
                        template + ":" + closureId + ":" + owner.get().userId()));
            });
        } catch (RuntimeException e) {
            log.warn(
                    "the Owner's email about closing was not queued: {}",
                    e.getClass().getSimpleName());
        }
    }
}
