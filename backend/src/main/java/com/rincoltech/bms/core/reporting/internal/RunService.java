package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportDefinition.Param;
import com.rincoltech.bms.core.reporting.ReportDefinition.Prepared;
import com.rincoltech.bms.core.reporting.internal.RunStore.Run;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The request side of a report run (FR-RPT-04, ADR-038 decision 1): queue, read, list and cancel. Everything
 * here runs in a request, with the signed-in user; the job that executes a run is {@link ReportRunner}.
 *
 * <p>Queuing checks, in order: the tenant has switched reports on; the report exists for this tenant (its module
 * is on); the caller holds its permission; the format is one it offers; no parameter is unknown; the caller is
 * under the rate limit (10 new runs a minute, chapter 7 section 7.10); then the report's own
 * {@link ReportDefinition#prepare}, which freezes the caller's scope into the run. An identical request of the
 * same user while one is queued or running returns that run.
 */
@Service
class RunService {

    static final String AUDIT_RUN = "core.reports.run";
    static final String AUDIT_CANCEL = "core.reports.run_cancelled";
    static final String ENTITY = "core.report_run";
    static final String AUDIT_READ = "core.audit.read";

    /** New runs per user per minute (chapter 7 section 7.10). */
    static final int RATE_PER_MINUTE = 10;

    private final RunStore store;
    private final ReportRegistry registry;
    private final TenantSettings settings;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final Idempotency idempotency;
    private final ReportsProperties limits;

    RunService(
            RunStore store,
            ReportRegistry registry,
            TenantSettings settings,
            BusinessClock clock,
            AuditLog audit,
            Idempotency idempotency,
            ReportsProperties limits) {
        this.store = store;
        this.registry = registry;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
        this.idempotency = idempotency;
        this.limits = limits;
    }

    /** A run as the API returns it, and whether this call queued it (false: an identical open run was returned). */
    record Requested(ReportsApi.RunView run, boolean created) {}

    /** The tenant switch (extras principle 1): nothing is queued, read or cancelled until it is on. */
    void requireEnabled() {
        if (!settings.reportsEnabled()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "reports_not_enabled",
                    "Reports are not enabled",
                    "Report runs are switched off for this business.");
        }
    }

    boolean enabled() {
        return settings.reportsEnabled();
    }

    @Transactional(readOnly = true)
    List<ReportsApi.CatalogueEntry> catalogue() {
        requireEnabled();
        return registry.available(CurrentPrincipal.require()).stream()
                .map(d -> new ReportsApi.CatalogueEntry(
                        d.key(),
                        d.title(),
                        d.description(),
                        d.permission(),
                        d.formats().stream().sorted().toList(),
                        d.params().stream()
                                .map(p -> new ReportsApi.CatalogueParam(
                                        p.name(), p.type(), p.required(), p.description(), p.values()))
                                .toList()))
                .toList();
    }

    // ---- queue --------------------------------------------------------------------------------

    @Transactional
    Requested request(String idempotencyKey, ReportsApi.RunRequest request) {
        requireEnabled();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return queue(request);
        }
        AtomicBoolean created = new AtomicBoolean();
        Idempotency.Outcome<ReportsApi.RunView> outcome = idempotency.once(
                idempotencyKey, "POST", "/api/v1/report-runs", request, ReportsApi.RunView.class, () -> {
                    Requested made = queue(request);
                    created.set(made.created());
                    return made.run();
                });
        return new Requested(outcome.body(), created.get() && !outcome.replayed());
    }

    private Requested queue(ReportsApi.RunRequest request) {
        Principal principal = CurrentPrincipal.require();
        String key = request.reportKey() == null ? "" : request.reportKey();
        ReportDefinition def = registry.findInTenant(key)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "report_not_found", "Report not found", "No such report is available."));
        if (!principal.hasPermission(def.permission())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You do not have permission to run this report.");
        }
        String format = request.format() == null ? "" : request.format();
        if (!def.formats().contains(format)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "format", "invalid", "This report is offered as: " + String.join(", ", def.formats()) + ".")));
        }
        Map<String, Object> asked = request.params() == null ? Map.of() : request.params();
        List<FieldProblem> unknown = new ArrayList<>();
        for (String name : asked.keySet()) {
            if (def.params().stream().noneMatch(p -> p.name().equals(name))) {
                unknown.add(new FieldProblem("params." + name, "unknown", "This report has no such parameter."));
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.validation(unknown);
        }
        Prepared prepared = def.prepare(asked);
        for (Param required : def.params()) {
            if (required.required() && !prepared.params().containsKey(required.name())) {
                throw ApiException.validation(List.of(
                        new FieldProblem("params." + required.name(), "required", "This parameter is required.")));
            }
        }
        return insert(def, format, prepared, principal.userId(), true);
    }

    /**
     * Queues a run of {@code def} for {@code requester} after the checks of a request (or of a job that acts for a named
     * user, the weekly data export of ADR-074): an identical open run of theirs is returned instead, and with
     * {@code byRequest} the rate limit applies and the audit row is the signed-in user's; without it the audit row names
     * the requester and the system. Runs in the caller's transaction.
     */
    Requested insert(ReportDefinition def, String format, Prepared prepared, UUID requester, boolean byRequest) {
        String hash = hash(def.key(), format, prepared);
        // One request at a time per requester from here: the repeat check, the rate count and the insert below see each
        // other, so ten runs a minute is ten and not "about ten" when requests arrive together.
        store.lockRequester(requester);
        Optional<Run> open = store.findOpenByHash(requester, hash);
        if (open.isPresent()) {
            // An identical request is answered with the run that exists, however many were asked this minute.
            return new Requested(view(open.get()), false);
        }
        Instant now = clock.now();
        if (byRequest && store.countCreatedSince(requester, now.minus(Duration.ofMinutes(1))) >= RATE_PER_MINUTE) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many report requests",
                    "Wait a minute before asking for more reports.");
        }
        UUID id = UUID.randomUUID();
        boolean created = store.insert(
                id,
                now,
                def.key(),
                prepared.params(),
                hash,
                prepared.scope(),
                format,
                requester,
                prepared.asOf(),
                limits.tableRowCap,
                limits.maxFileBytes);
        if (!created) {
            Run existing = store.findOpenByHash(requester, hash).orElseThrow();
            return new Requested(view(existing), false);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("report_key", def.key());
        after.put("format", format);
        after.put("as_of", prepared.asOf().toString());
        after.put("params", prepared.params());
        AuditLog.Entry entry = AuditLog.Entry.created(AUDIT_RUN, ENTITY, id, null, after);
        if (byRequest) {
            audit.record(entry);
        } else {
            audit.record(entry, requester, "system");
        }
        return new Requested(view(store.find(id).orElseThrow()), true);
    }

    // ---- read ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    ReportsApi.RunView get(UUID id) {
        requireEnabled();
        Principal principal = CurrentPrincipal.require();
        Run run = store.find(id).orElseThrow(ApiException::notFound);
        if (!run.requestedBy().equals(principal.userId()) && !principal.hasPermission(AUDIT_READ)) {
            throw ApiException.notFound();
        }
        return view(run);
    }

    @Transactional(readOnly = true)
    List<ReportsApi.RunView> list(int limit) {
        requireEnabled();
        Principal principal = CurrentPrincipal.require();
        return store.listByRequester(principal.userId(), Math.max(1, Math.min(limit, 100))).stream()
                .map(this::view)
                .toList();
    }

    // ---- cancel -------------------------------------------------------------------------------

    /**
     * A queued run is cancelled at once; a running one is asked to stop and ends {@code cancelled} at its next
     * checkpoint; a finished one cannot be cancelled (409). Only the requester may cancel.
     */
    @Transactional
    ReportsApi.RunView cancel(UUID id) {
        requireEnabled();
        Principal principal = CurrentPrincipal.require();
        Run run = store.lock(id).orElseThrow(ApiException::notFound);
        if (!run.requestedBy().equals(principal.userId())) {
            throw ApiException.notFound();
        }
        Instant now = clock.now();
        switch (run.status()) {
            case "queued" -> store.markCancelled(id, now);
            case "running" -> store.requestCancel(id, now);
            case "cancelled" -> {
                // Already cancelled: the answer is the same.
            }
            default ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "run_finished",
                        "The run has finished",
                        "A finished report run cannot be cancelled.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("report_key", run.reportKey());
        after.put("was", run.status());
        audit.record(AuditLog.Entry.created(AUDIT_CANCEL, ENTITY, id, null, after));
        return view(store.find(id).orElseThrow());
    }

    // ---- view ---------------------------------------------------------------------------------

    ReportsApi.RunView view(Run r) {
        String title = registry.find(r.reportKey()).map(ReportDefinition::title).orElse(r.reportKey());
        boolean expired = r.filesDeletedAt() != null
                || (r.expiresAt() != null && !clock.now().isBefore(r.expiresAt()));
        return new ReportsApi.RunView(
                r.id(),
                r.reportKey(),
                title,
                r.format(),
                r.status(),
                r.params(),
                r.asOf(),
                r.snapshotAt(),
                r.createdAt(),
                r.startedAt(),
                r.finishedAt(),
                r.rowCount(),
                r.sizeBytes(),
                r.documentId(),
                r.expiresAt(),
                expired,
                r.cancelRequestedAt() != null && r.status().equals("running"),
                r.error());
    }

    // ---- the dedupe key -----------------------------------------------------------------------

    /** SHA-256 of the report, format, date and normalised parameters: equal requests give equal hashes. */
    static String hash(String key, String format, Prepared prepared) {
        StringBuilder sb = new StringBuilder();
        sb.append(key)
                .append('\n')
                .append(format)
                .append('\n')
                .append(prepared.asOf())
                .append('\n');
        canonical(prepared.params(), sb);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void canonical(Object value, StringBuilder sb) {
        if (value instanceof Map<?, ?> m) {
            sb.append('{');
            new java.util.TreeMap<String, Object>((Map<String, Object>) m).forEach((k, v) -> {
                sb.append(k).append(':');
                canonical(v, sb);
                sb.append(',');
            });
            sb.append('}');
        } else if (value instanceof List<?> l) {
            sb.append('[');
            l.forEach(v -> {
                canonical(v, sb);
                sb.append(',');
            });
            sb.append(']');
        } else if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.getClass().getSimpleName().charAt(0))
                    .append('"')
                    .append(value)
                    .append('"');
        }
    }
}
