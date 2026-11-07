package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.ImportedRecords;
import com.rincoltech.bms.kernel.LiveRecordProbe;
import com.rincoltech.bms.kernel.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportRuns} over {@code import_runs}, {@code import_batches} and {@code platform_import_settings} (migration V127).
 *
 * <p>The commit is a sequence of transactions, never one: the start (guards, then {@code ready} to {@code committing}),
 * one per batch, and the finish. A batch takes {@code pg_try_advisory_xact_lock} on the business's import key before it
 * reads its own status, so two resumes of one run, or a resume racing a commit, write each batch once; a batch that finds
 * the key taken (a dry run of the business) is answered with {@code IMPORT_IN_PROGRESS} at once, never after a wait (Lead
 * ruling 5 on #869). The writer of each batch is its template's loader, which the service resolves. The kill switch and the
 * halt are read with a fresh snapshot each time (read committed), so an operator's stop is seen within 1,000 rows.
 *
 * <p>The commit loop itself has an owner (issue #779): a commit or a resume first takes {@code pg_try_advisory_lock}, a
 * session lock, on the business's loop key, on a connection of its own that it holds outside every transaction until
 * the loop ends. While that lock is held, a second commit or resume of the business is refused with
 * {@code IMPORT_IN_PROGRESS}, also in the gap between two batches, so an operator's halt can never be cleared under a
 * live loop. The lock is the loop's heartbeat: PostgreSQL drops it when the loop's connection or process dies, so a
 * crashed loop never blocks a resume, and no column is needed.
 *
 * <p>The loop also checks its own claim before each batch (issue #789): if the claim connection died, or its session
 * no longer holds the loop key (an idle timeout, or a NAT or firewall that dropped the idle connection), a resume may
 * already own the business, so the loop writes nothing more, audits {@code core.import_run.claim_lost} and ends
 * {@code HALTED} with the run still {@code committing} and resumable.
 */
@Service
class ImportRunService implements ImportRuns {

    /** Rows between two reads of the kill switch and the halt inside a batch (spec section 5.4). */
    static final int CHECKPOINT_ROWS = 1_000;

    /** The business's import key: the advisory lock every batch takes. */
    private static final String LOCK_KEY = "hashtextextended('core.imports:' || current_setting('app.tenant_id'), 0)";

    /** The business's loop key, followed by the business's id: the session lock the whole commit loop holds. */
    private static final String LOOP_KEY = "core.imports.loop:";

    /** True while this session holds the loop key: a bigint advisory key is split over classid and objid. */
    private static final String CLAIM_HELD = """
            SELECT EXISTS (SELECT 1 FROM pg_locks
                            WHERE locktype = 'advisory' AND granted AND pid = pg_backend_pid() AND objsubid = 1
                              AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0))""";

    /**
     * Milliseconds the probe of the claim may wait on the database before the claim counts as lost (issue #792). It is
     * the claim connection's network timeout, not a query timeout: pgjdbc cancels a query on a new socket and never
     * unblocks the read on a black-holed one, while a socket read that times out ends the connection.
     */
    private static final int CLAIM_PROBE_TIMEOUT_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(ImportRunService.class);

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final String RUN_COLUMNS = """
            id, status, version, cutover_at, halt_requested, allow_trading_tenant, allow_trading_set_by,
            allow_trading_approved_by, pre_import_dump_file, committing_at, committed_at,
            pre_import_dump_verified_at, commit_requested_by, commit_approved_by, commit_approved_version""";

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final List<LiveRecordProbe> probes;
    private final ObjectMapper mapper;
    private final PreviewService preview;

    private final PlatformTransactionManager transactions;

    /** The maker's request of import_commit (issue #133); the action's approval comes back through {@link #commitApproved}. */
    private final Approvals approvals;

    private final Branches branches;

    /** import_refs, the rows a writer writes and the opening journals (issue #133). */
    private final RefService refs;

    /** The test hook after each committed batch (issue #133); a no-op without a bean. */
    private final CommitSeams seams;

    /** The reconciliation the commit makes as it finishes (issue #134). */
    private final ReconciliationService reconciliations;

    /**
     * The application's build and commit: a dry run of another build no longer counts, and a run rehearsed on one build
     * neither starts nor resumes on another (reviews of #869). Null when the server knows neither; it then refuses both.
     */
    private volatile String codeVersion;

    /** How long a dry run into a business that trades, under the approved override, may hold its rows (review of #869). */
    private volatile Duration tradingDryRunLimit;

    /** How long any other dry run may hold the business's rows (review of 1c607ea on #869). */
    private final Duration dryRunLimit;

    ImportRunService(
            JdbcClient jdbc,
            DataSource dataSource,
            PlatformTransactionManager transactions,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            List<LiveRecordProbe> probes,
            ObjectMapper mapper,
            PreviewService preview,
            ObjectProvider<BuildProperties> build,
            ObjectProvider<GitProperties> git,
            @Value("${bms.git-sha:}") String gitSha,
            @Value("${bms.imports.dry-run-limit-while-trading:60s}") Duration tradingDryRunLimit,
            @Value("${bms.imports.dry-run-limit:15m}") Duration dryRunLimit,
            Approvals approvals,
            Branches branches,
            RefService refs,
            ObjectProvider<CommitSeams> seams,
            ReconciliationService reconciliations) {
        this.reconciliations = reconciliations;
        this.approvals = approvals;
        this.branches = branches;
        this.refs = refs;
        this.seams = seams.getIfAvailable(() -> new CommitSeams() {});
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.probes = List.copyOf(probes);
        this.mapper = mapper;
        this.preview = preview;
        this.codeVersion = codeVersion(build.getIfAvailable(), git.getIfAvailable(), gitSha);
        this.tradingDryRunLimit = tradingDryRunLimit;
        this.dryRunLimit = dryRunLimit;
    }

    /**
     * The build's version and time and the commit's SHA (the image's, else the build's {@code git.properties}); null when
     * none of them is known, so a server that cannot tell its build apart never takes another build's dry run for its own.
     */
    static String codeVersion(BuildProperties build, GitProperties git, String imageGitSha) {
        String sha = imageGitSha != null && !imageGitSha.isBlank()
                ? imageGitSha.strip()
                : git == null ? null : git.getCommitId();
        if (build == null && sha == null) {
            return null;
        }
        return (build == null ? "-" : build.getVersion() + "@" + build.getTime()) + "|" + (sha == null ? "-" : sha);
    }

    /** For a test of the time limit only. */
    void tradingDryRunLimit(Duration limit) {
        this.tradingDryRunLimit = limit;
    }

    /** For a test of a server with no build identity (null) only; answers the identity it had. */
    String buildIdentity(String identity) {
        String had = codeVersion;
        codeVersion = identity;
        return had;
    }

    private void requireKnownBuild() {
        if (codeVersion == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "build_unknown",
                    "Build unknown",
                    "This server cannot tell which build of the import code it runs, so it neither dry runs nor commits"
                            + " an import.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The run and its states.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Run open(UUID createdBy) {
        return tx.execute(s -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO import_runs (id, tenant_id, created_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?)
                            """).params(id, createdBy).update();
            audit.record(AuditLog.Entry.created(
                    "core.import_run.opened", "import_run", id, null, Map.of("status", RunStates.DRAFT)));
            return find(id, false);
        });
    }

    @Override
    public Run get(UUID runId) {
        return tx.execute(s -> find(runId, false));
    }

    @Override
    public Run transition(UUID runId, String status) {
        if (!RunStates.known(status) || RunStates.COMMIT_ONLY.contains(status)) {
            throw invalidTransition(
                    RunStates.known(status)
                            ? "A run is " + RunStates.words(status) + " only through the import itself."
                            : "That is not a state an import can be in.");
        }
        if (RunStates.SIGN_OFF_ONLY.contains(status)) {
            throw invalidTransition("A run is signed off only by the customer's sign-off of its reconciliation.");
        }
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!RunStates.allowed(run.status(), status)) {
                throw invalidTransition("The import is " + RunStates.words(run.status()) + ", so it cannot be "
                        + RunStates.words(status) + " now.");
            }
            // Ready means every file is read (spec section 3.11): a file not yet parsed, or refused, is parsed again or
            // cancelled first (#128).
            if (RunStates.READY.equals(status)
                    && jdbc.sql("""
                                    SELECT EXISTS (SELECT 1 FROM import_batches WHERE run_id = ? AND status IN ('uploaded', 'failed'))
                                    """).param(runId).query(Boolean.class).single()) {
                throw invalidTransition(
                        "A file of this import is not read yet or was refused; read it again or cancel it.");
            }
            // Ready means a stored dry run of the run as it is now, which did not fail and matched the preview (#132).
            if (RunStates.READY.equals(status)) {
                requireDryRun(runId, false);
                // And every policy decision the run needs, recorded (spec section 3.8, issue #133).
                requirePolicies(runId);
            }
            setStatus(runId, status);
            audit(runId, "core.import_run.status_changed", Map.of("status", run.status()), Map.of("status", status));
            return find(runId, false);
        });
    }

    @Override
    public UUID addBatch(UUID runId, int seq) {
        return tx.execute(s -> {
            Run run = find(runId, true);
            requireBeforeCommit(run);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO import_batches (id, tenant_id, run_id, seq)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                            """).params(id, runId, seq).update();
            return id;
        });
    }

    @Override
    public Run recordPreImportDump(UUID runId, String file, Instant takenAt, String sha256) {
        if (file == null
                || file.isBlank()
                || file.length() > 255
                || takenAt == null
                || sha256 == null
                || !SHA256.matcher(sha256).matches()) {
            throw ApiException.rule(
                    "invalid_pre_import_dump", "The dump needs a file name, the time it was taken and its SHA-256.");
        }
        return tx.execute(s -> {
            requireBeforeCommit(find(runId, true));
            // Another dump is not yet verified (issue #133).
            jdbc.sql("""
                            UPDATE import_runs SET pre_import_dump_file = ?, pre_import_dump_at = ?,
                                   pre_import_dump_sha256 = ?, pre_import_dump_verified_at = NULL,
                                   pre_import_dump_entries = NULL, version = version + 1, updated_at = now()
                             WHERE id = ?
                            """)
                    .params(file.strip(), Timestamp.from(takenAt), sha256, runId)
                    .update();
            audit(runId, "core.import_run.dump_recorded", Map.of(), Map.of("file", file.strip(), "sha256", sha256));
            return find(runId, false);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The verified dump, the policy decisions and the import_commit approval (issue #133).
    // JUSTIFICATION-A3: issue #133's new operations on the run, each its own method beside the dump's record.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Run verifyPreImportDump(UUID runId, String sha256, int listedEntries) {
        if (sha256 == null || !SHA256.matcher(sha256).matches() || listedEntries <= 0) {
            throw ApiException.rule(
                    "invalid_pre_import_dump",
                    "The check needs the dump's SHA-256 and the number of entries pg_restore listed, above zero.");
        }
        return tx.execute(s -> {
            requireBeforeCommit(find(runId, true));
            String recorded = jdbc.sql("SELECT pre_import_dump_sha256 FROM import_runs WHERE id = ?")
                    .param(runId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (recorded == null) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "pre_import_dump_missing",
                        "No pre-import dump",
                        "Record the dump taken before the import first, then its check.");
            }
            if (!recorded.equals(sha256)) {
                throw ApiException.rule(
                        "pre_import_dump_differs",
                        "The SHA-256 checked is not the one of the dump recorded on the run.");
            }
            jdbc.sql("""
                            UPDATE import_runs SET pre_import_dump_verified_at = now(), pre_import_dump_entries = ?,
                                   version = version + 1, updated_at = now()
                             WHERE id = ?
                            """).params(listedEntries, runId).update();
            audit(runId, "core.import_run.dump_verified", Map.of(), Map.of("entries", listedEntries));
            return find(runId, false);
        });
    }

    @Override
    public Run recordPolicy(UUID runId, String key, PolicyDecision decision) {
        if (key == null || decision == null || decision.option() == null) {
            throw ApiException.rule("policy_option_unknown", "Say which decision was taken.");
        }
        String confirmation =
                decision.confirmation() == null ? "" : decision.confirmation().strip();
        if (decision.confirmedOn() == null
                || confirmation.length() < 3
                || confirmation.length() > 200
                || decision.recordedBy() == null) {
            throw ApiException.rule(
                    "policy_confirmation_required",
                    "Give the day the customer confirmed the decision and the reference of their confirmation.");
        }
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!List.of(RunStates.DRAFT, RunStates.UPLOADED, RunStates.IN_REVIEW)
                    .contains(run.status())) {
                throw invalidTransition("The import is " + RunStates.words(run.status())
                        + ", so its decisions can no longer be changed.");
            }
            ImportTemplate.Policy policy = preview.policies(runId).stream()
                    .filter(p -> p.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> ApiException.rule(
                            "policy_unknown", "The template of this import takes no decision " + key + "."));
            if (!policy.options().contains(decision.option())) {
                throw ApiException.rule(
                        "policy_option_unknown",
                        "The decision " + key + " is one of " + String.join(", ", policy.options()) + ".");
            }
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("option", decision.option());
            stored.put("confirmed_on", decision.confirmedOn().toString());
            stored.put("confirmation", confirmation);
            stored.put("recorded_by", decision.recordedBy().toString());
            if (policy.exceptionOnly().contains(decision.option())) {
                // The dev lead's exception (spec section 3.8): a platform operator, and the day of the follow-up.
                if (decision.followUpOn() == null) {
                    throw ApiException.rule(
                            "policy_exception_required",
                            "This decision needs the dev lead's exception and the day of its follow-up.");
                }
                requireOperator(decision.exceptionBy(), "policy_exception_required");
                stored.put("exception_by", decision.exceptionBy().toString());
                stored.put("follow_up_on", decision.followUpOn().toString());
            }
            jdbc.sql("""
                            UPDATE import_runs SET policy = policy || jsonb_build_object(CAST(? AS text), CAST(? AS jsonb)),
                                   version = version + 1, updated_at = now()
                             WHERE id = ?
                            """).params(key, mapper.writeValueAsString(stored), runId).update();
            audit(runId, "core.import_run.policy_recorded", Map.of(), Map.of("policy", key, "decision", stored));
            return find(runId, false);
        });
    }

    /**
     * Refuses a run that needs a policy decision it has not recorded (spec section 3.8): 409 with the policy's own code,
     * {@code CREDIT_DECISION_MISSING} for the receivable decision. Inside the caller's transaction.
     */
    private void requirePolicies(UUID runId) {
        Set<String> recorded = new HashSet<>(jdbc.sql("SELECT jsonb_object_keys(policy) FROM import_runs WHERE id = ?")
                .param(runId)
                .query(String.class)
                .list());
        List<ImportTemplate.Policy> missing = preview.missingPolicies(runId, recorded);
        if (!missing.isEmpty()) {
            ImportTemplate.Policy first = missing.getFirst();
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    first.missingCode(),
                    "Decision missing",
                    "Rows of this import need the " + first.key().replace('_', ' ')
                            + " decision; record the option the customer confirmed first.");
        }
    }

    @Override
    public UUID requestCommit(UUID runId) {
        requireNoTransaction();
        UUID maker = CurrentPrincipal.require().userId();
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!RunStates.READY.equals(run.status())) {
                throw invalidTransition("Only an import that is ready can be asked to commit; this one is "
                        + RunStates.words(run.status()) + ".");
            }
            requireCommitPossible(run);
            requirePolicies(runId);
            UUID branch = branches.all().stream()
                    .filter(Branches.Branch::headOffice)
                    .findFirst()
                    .or(() -> branches.all().stream().findFirst())
                    .map(Branches.Branch::id)
                    .orElseThrow(() -> new IllegalStateException("a business without a branch"));
            long files = jdbc.sql(
                            "SELECT count(*) FROM import_batches WHERE run_id = ? AND status IN ('pending', 'parsed')")
                    .param(runId)
                    .query(Long.class)
                    .single();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("run_id", runId.toString());
            payload.put("batches", files);
            payload.put(
                    ApprovalAction.CHECKER_NOTICE,
                    "This writes " + files + " checked files of customer data into the business's records and opening"
                            + " balances. It cannot be undone except by restoring the dump taken before it.");
            // The request is recorded on the run without a new version, so the request's subject version is the run's.
            jdbc.sql("""
                            UPDATE import_runs SET commit_requested_by = ?, commit_requested_at = now(),
                                   commit_approval_id = NULL, commit_approved_by = NULL, commit_approved_at = NULL,
                                   commit_approved_version = NULL
                             WHERE id = ?
                            """).params(maker, runId).update();
            // The rule is read once and passed (ADR-091); import_commit is not switchable, so it always waits.
            Approvals.Rule rule = approvals.rule(ImportCommitAction.TYPE);
            Approvals.Outcome outcome = approvals.request(
                    new Approvals.ActionRequest(
                            ImportCommitAction.TYPE, branch, runId, run.version(), null, null, payload),
                    rule);
            if (outcome.executed()) {
                throw new IllegalStateException("import_commit ran without a checker");
            }
            audit(
                    runId,
                    "core.import_run.commit_requested",
                    Map.of(),
                    Map.of("approval_id", String.valueOf(outcome.approvalId())));
            return outcome.approvalId();
        });
    }

    /** The run's version, for the subject of its import_commit request; empty when the business holds no such run. */
    Optional<Integer> version(UUID runId) {
        return jdbc.sql("SELECT version FROM import_runs WHERE id = ?")
                .param(runId)
                .query(Integer.class)
                .optional();
    }

    /**
     * The approval of import_commit, inside the approval's transaction (ADR-015): marks the run approved at its next
     * version, which the commit's start requires. The database refuses a checker who is the maker
     * ({@code import_runs_commit_checker_not_maker}). A run no longer ready fails the approval, which stays pending with the
     * error (FR-APR-06).
     */
    void commitApproved(UUID runId, UUID approvalId, UUID maker, UUID checker) {
        Run run = find(runId, true);
        if (!RunStates.READY.equals(run.status())) {
            throw invalidTransition(
                    "The import is " + RunStates.words(run.status()) + ", so it cannot be approved now.");
        }
        jdbc.sql("""
                        UPDATE import_runs SET commit_requested_by = ?, commit_approval_id = ?, commit_approved_by = ?,
                               commit_approved_at = now(), commit_approved_version = version + 1, version = version + 1,
                               updated_at = now()
                         WHERE id = ?
                        """).params(maker, approvalId, checker, runId).update();
        audit(runId, "core.import_run.commit_approved", Map.of(), Map.of("approval_id", approvalId.toString()));
    }

    /** Refuses an id that is not an active platform operator's (review of #736, comment on #133): 403. */
    private void requireOperator(UUID platformUserId, String code) {
        boolean active = platformUserId != null
                && jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_users WHERE id = ? AND is_active)")
                        .param(platformUserId)
                        .query(Boolean.class)
                        .single();
        if (!active) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, code, "Platform operator required", "Only an active platform operator may.");
        }
    }

    @Override
    public Run requestHalt(UUID runId) {
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!RunStates.COMMITTING.equals(run.status())) {
                throw invalidTransition("Only an import being imported can be stopped; this one is "
                        + RunStates.words(run.status()) + ".");
            }
            jdbc.sql(
                            "UPDATE import_runs SET halt_requested = true, version = version + 1, updated_at = now() WHERE id = ?")
                    .param(runId)
                    .update();
            audit(runId, "core.import_run.halt_requested", Map.of(), Map.of("halt_requested", true));
            return find(runId, false);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The trading tenant override: set by one operator, approved by another.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Run allowTradingTenant(UUID runId, UUID platformUserId, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5 || why.length() > 500) {
            throw ApiException.rule("reason_required", "Say why this business may be imported into while it trades.");
        }
        return tx.execute(s -> {
            requireBeforeCommit(find(runId, true));
            requireOperator(platformUserId, "operator_required");
            // Setting it again clears an earlier approval: the checker approves the reason now on the run.
            jdbc.sql("""
                            UPDATE import_runs SET allow_trading_tenant = true, allow_trading_reason = ?,
                                   allow_trading_set_by = ?, allow_trading_approved_by = NULL,
                                   version = version + 1, updated_at = now()
                             WHERE id = ?
                            """).params(why, platformUserId, runId).update();
            audit.record(
                    new AuditLog.Entry(
                            "core.import_run.trading_override_set",
                            "import_run",
                            runId,
                            null,
                            Map.of(),
                            Map.of("allow_trading_tenant", true, "reason", why)),
                    platformUserId,
                    "platform");
            return find(runId, false);
        });
    }

    @Override
    public Run approveTradingTenant(UUID runId, UUID platformUserId) {
        return tx.execute(s -> {
            Run run = find(runId, true);
            requireBeforeCommit(run);
            requireOperator(platformUserId, "operator_required");
            if (!run.allowTradingTenant()) {
                throw invalidTransition("No trading override is set on this run.");
            }
            if (platformUserId.equals(run.allowTradingSetBy())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "self_approval_forbidden",
                        "Self approval forbidden",
                        "The operator who set the override cannot approve it.");
            }
            jdbc.sql("""
                            UPDATE import_runs SET allow_trading_approved_by = ?, version = version + 1, updated_at = now()
                             WHERE id = ?
                            """).params(platformUserId, runId).update();
            audit.record(
                    new AuditLog.Entry(
                            "core.import_run.trading_override_approved",
                            "import_run",
                            runId,
                            null,
                            Map.of(),
                            Map.of("allow_trading_approved", true)),
                    platformUserId,
                    "platform");
            return find(runId, false);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The commit.
    // ---------------------------------------------------------------------------------------------

    @Override
    public CommitOutcome commit(UUID runId) {
        requireNoTransaction();
        requireKnownBuild();
        try (LoopClaim claim = claimLoop()) {
            List<String> trading;
            try {
                trading = tx.execute(s -> start(runId));
            } catch (DuplicateKeyException e) {
                // import_runs_one_committing: another run of this business is committing.
                throw importInProgress();
            }
            if (!trading.isEmpty()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "TENANT_TRADING",
                        "Business already trades",
                        "The tenant already has live transactions (" + String.join(", ", trading)
                                + "); an import needs the trading override.");
            }
            return writeBatches(runId, claim);
        }
    }

    /**
     * The guards and the move to {@code committing}, in one transaction. Returns the areas that trade when the guard
     * refuses, so the refusal's audit row commits before the caller throws; empty when the run moved on.
     */
    private List<String> start(UUID runId) {
        Run run = find(runId, true);
        if (RunStates.COMMITTING.equals(run.status())) {
            throw importInProgress();
        }
        if (!RunStates.READY.equals(run.status())) {
            throw invalidTransition(
                    "Only an import that is ready can start; this one is " + RunStates.words(run.status()) + ".");
        }
        requireCommitPossible(run);
        // The run cannot change once ready, so this holds; checked again so no path commits without its dry run (#132),
        // and only with the writers the dry run rehearsed (Lead ruling 4 on #869).
        requireDryRun(runId, true);
        // Every policy decision the run needs (spec section 3.8, issue #133), as ready checked it.
        requirePolicies(runId);
        List<String> trading = tradingAreas();
        if (!trading.isEmpty() && !run.tradingOverrideApproved()) {
            audit(
                    runId,
                    "core.import_run.commit_refused",
                    Map.of(),
                    Map.of("code", "TENANT_TRADING", "areas", trading));
            return trading;
        }
        // The maker's import_commit, approved by a different user, for the run as it is now (issue #133, ADR-015).
        if (!run.commitApproved()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "commit_not_approved",
                    "Commit not approved",
                    run.commitApprovedBy() == null
                            ? "The import's commit needs a request and a second person's approval first."
                            : "The import changed after its commit was approved; ask for the approval again.");
        }
        setStatus(runId, RunStates.COMMITTING);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RunStates.COMMITTING);
        if (!trading.isEmpty()) {
            after.put("trading_override_used", true);
            after.put("areas", trading);
        }
        audit(runId, "core.import_run.status_changed", Map.of("status", RunStates.READY), after);
        return List.of();
    }

    /** The areas in which the business already trades, by every {@link LiveRecordProbe}; empty when it does not. */
    private List<String> tradingAreas() {
        return probes.stream()
                .filter(LiveRecordProbe::hasLiveRecords)
                .map(LiveRecordProbe::area)
                .sorted()
                .toList();
    }

    @Override
    public CommitOutcome resume(UUID runId) {
        requireNoTransaction();
        requireKnownBuild();
        // A live commit loop owns the business's loop lock, inside a batch and between two: going on now would clear
        // the operator's halt under it, and the halt would be lost (reviews of #736, issue #779).
        try (LoopClaim claim = claimLoop()) {
            tx.executeWithoutResult(s -> {
                Run run = find(runId, true);
                if (!RunStates.COMMITTING.equals(run.status())) {
                    throw invalidTransition("Only an import being imported can go on; this one is "
                            + RunStates.words(run.status()) + ".");
                }
                requireCommitPossible(run);
                // A resume goes on with a commit that was approved (Lead ruling 8 on #872); the run's version
                // moves with its own commit, so the approval is required, not its version.
                if (run.commitApprovedBy() == null) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "commit_not_approved",
                            "Commit not approved",
                            "This import's commit has no second person's approval, so it does not go on.");
                }
                // The run's own commits make its dry run's stamp differ now, so the build and the writers are checked
                // again: a resume goes on only on the build, and with the writers, the dry run rehearsed for the
                // batches left (Lead ruling 4 and the review of d8d1e5b on #869).
                requireSameWriters(runId);
                // A dry run of the batches left, taken for a new build, must not have failed and must have matched the
                // preview, as ready requires (review of 1c607ea on #869); on the normal path ready already did.
                Optional<DryRun> dry = storedDryRun(runId);
                if (dry.map(d -> d.report().failure() != null).orElse(false)) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "dry_run_failed",
                            "Dry run failed",
                            "The last dry run of this import stopped at a batch; fix the cause and run it again.");
                }
                if (dry.map(d -> !d.previewMatches()).orElse(false)) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "preview_differs",
                            "Preview differs",
                            "The balances of the last dry run differ from the preview's; the import does not go on.");
                }
                // The trading guard is not asked again: it was passed when the run started, and the batches this run
                // already committed are the business's records now.
                jdbc.sql(
                                "UPDATE import_runs SET halt_requested = false, version = version + 1, updated_at = now() WHERE id = ?")
                        .param(runId)
                        .update();
                audit(runId, "core.import_run.resumed", Map.of(), Map.of("halt_requested", false));
            });
            return writeBatches(runId, claim);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The dry run and the stored reports (issue #132).
    // ---------------------------------------------------------------------------------------------

    // JUSTIFICATION-A3: issue #132's dry run, the commit's batch code inside one outer transaction rolled back.
    /**
     * The stamp of a run's inputs: an MD5 over the application's version and commit, the cutover moment and policy
     * decisions, every batch (status, file, mapping version, parse, rows), every issue (status, version, resolution),
     * every resolution, every staged row's review columns and typed values, and the last batch any run of the business
     * committed. Anything a commit reads from the staging area, another build of the templates' code, and another run's
     * commit into the business change it; the dry run's own writes are rolled back and never do. Every part is written with
     * a marker for NULL, so no two different inputs give the same text.
     */
    private static final String STAMP = """
            SELECT md5(concat_ws('#', ?,
                     (SELECT concat_ws('|', coalesce(cutover_at::text, '-'), policy::text) FROM import_runs WHERE id = ?),
                     coalesce((SELECT string_agg(concat_ws('|', b.id, b.seq, b.status, coalesce(b.source_sha256, '-'),
                                                  coalesce(b.mapping_id::text, '-'), coalesce(b.mapping_source, '-'),
                                                  coalesce(b.template_key, '-'), coalesce(b.template_version::text, '-'),
                                                  coalesce(b.parsed_at::text, '-'), coalesce(b.row_count::text, '-')),
                                        ',' ORDER BY b.seq, b.id)
                                 FROM import_batches b WHERE b.run_id = ?), '-'),
                     coalesce((SELECT string_agg(concat_ws('|', i.id, i.status, i.version,
                                                  coalesce(i.resolution_id::text, '-'), i.ask_customer), ',' ORDER BY i.id)
                                 FROM import_issues i JOIN import_batches b ON b.id = i.batch_id WHERE b.run_id = ?), '-'),
                     coalesce((SELECT string_agg(r.id::text, ',' ORDER BY r.id)
                                 FROM import_issue_resolutions r WHERE r.run_id = ?), '-'),
                     coalesce((SELECT string_agg(concat_ws('|', w.id, coalesce(w.excluded_by::text, '-'),
                                                  coalesce(w.linked_row_id::text, '-'),
                                                  coalesce(w.linked_member_id::text, '-'), coalesce(w.source_ref, '-'),
                                                  coalesce(md5(w.normalised::text), '-')), ',' ORDER BY w.id)
                                 FROM import_rows w JOIN import_batches b ON b.id = w.batch_id WHERE b.run_id = ?), '-'),
                     coalesce((SELECT max(c.committed_at)::text FROM import_batches c WHERE c.status = 'committed'), '-')))
            """;

    private String stamp(UUID runId) {
        return jdbc.sql(STAMP)
                .params(rehearsal(writersIdentity(runId)), runId, runId, runId, runId, runId)
                .query(String.class)
                .single();
    }

    /**
     * The identity of the writers the commit would resolve for the run's batches (Lead ruling 4 on #869), part of the stamp:
     * a template or loader changed since the dry run makes it stop counting. A run with a batch read without a mapping has
     * none; its dry run is refused, so it never has a stamp to compare.
     */
    /** What a dry run rehearses: a first line naming the build, then the writers' lines, one per batch. */
    private String rehearsal(String writers) {
        return "build " + codeVersion + "\n" + writers;
    }

    private String writersIdentity(UUID runId) {
        try {
            return preview.writers(runId).identity();
        } catch (ApiException e) {
            return "no writers: " + e.code();
        }
    }

    @Override
    public DryRun dryRun(UUID runId, UUID by) {
        requireNoTransaction();
        if (by == null) {
            throw new IllegalArgumentException("a dry run needs who runs it");
        }
        requireKnownBuild();
        String[] inputs = new String[2];
        List<CommitReport> parts = new ArrayList<>();
        String[] failure = new String[1];
        // The business's commit loop is held for the whole dry run, as a commit holds it, and each batch takes the
        // business's import key with pg_try_advisory_xact_lock (Lead ruling 5 on #869). A commit or resume of the
        // business meanwhile is answered at once with IMPORT_IN_PROGRESS, and so is this dry run while one runs:
        // neither ever waits on the other until a statement timeout.
        try (LoopClaim claim = claimLoop()) {
            // The trading guard of the commit, asked before the dry run holds any row (review of d8d1e5b on #869): the
            // writers' postings lock the business's ledger and stock rows until the outer rollback, so a business that
            // trades is dry run only under the approved override, and then within a time limit, past which every
            // statement of the dry run is refused and its staff's postings wait no longer.
            boolean trading = Boolean.TRUE.equals(tx.execute(s -> {
                Run run = find(runId, false);
                requireDryRunPossible(run);
                List<String> areas = tradingAreas();
                // A run being imported passed the guard when it started, and its own batches are the business's
                // records now; it is dry run again only for a resume on a new build, within the limit all the same.
                if (!areas.isEmpty() && !run.tradingOverrideApproved() && !RunStates.COMMITTING.equals(run.status())) {
                    // As the commit's start refuses it, but with nothing audited: the dry run writes nothing.
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "TENANT_TRADING",
                            "Business already trades",
                            "The tenant already has live transactions (" + String.join(", ", areas)
                                    + "); a dry run of an import into it needs the approved trading override.");
                }
                return !areas.isEmpty();
            }));
            // Every dry run is bounded, so one into a business whose staff begin to post while it runs still ends
            // (review of 1c607ea); one into a business already trading, more tightly.
            TransactionTemplate outerTx = new TransactionTemplate(transactions);
            long limitSeconds = Math.max(1, (trading ? tradingDryRunLimit : dryRunLimit).toSeconds());
            outerTx.setTimeout(Math.toIntExact(limitSeconds));
            long deadline = System.nanoTime() + Duration.ofSeconds(limitSeconds).toNanos();
            outerTx.executeWithoutResult(outer -> {
                // Rolled back whatever happens inside: the dry run writes nothing (spec section 3.6).
                outer.setRollbackOnly();
                requireDryRunPossible(find(runId, false));
                // The writers the commit resolves, never a caller's (Lead ruling 4 on #869). They are read before any
                // batch, with the run's personal values, so a failure here is the dry run's, never a batch's.
                PreviewService.Writers writers = preview.writers(runId);
                inputs[0] = stamp(runId);
                inputs[1] = rehearsal(writers.identity());
                PreviewService.RunPrivacy privacy = preview.privacy(runId);
                for (PendingBatch batch : ordered(pending(runId), writers)) {
                    try {
                        CommitReport part = writeBatch(runId, batch, writers, limitSeconds, privacy);
                        if (part != null) {
                            parts.add(part);
                        }
                    } catch (ApiException e) {
                        if ("IMPORT_IN_PROGRESS".equals(e.code())) {
                            // The business's import key is taken: answered at once, nothing stored (Lead ruling 5).
                            throw e;
                        }
                        failure[0] = "batch " + batch.seq() + " failed ("
                                + e.getClass().getSimpleName() + ")";
                        break;
                    } catch (RuntimeException e) {
                        // As a real run, the first batch that fails stops it; the report names the batch and the kind
                        // of failure, never the message, which may quote a row. The outer transaction is rolled back.
                        failure[0] = System.nanoTime() - deadline >= 0
                                ? "batch " + batch.seq() + " stopped at the time limit of a dry run"
                                        + (trading ? " into a trading business" : "") + " (" + limitSeconds + "s)"
                                : "batch " + batch.seq() + " failed ("
                                        + e.getClass().getSimpleName() + ")";
                        break;
                    }
                }
            });
        }
        CommitReport.Builder whole = new CommitReport.Builder(null);
        parts.forEach(whole::add);
        if (failure[0] != null) {
            whole.fail(failure[0]);
        }
        CommitReport report = whole.build(CommitReport.DRY_RUN);
        boolean matches = sameBalances(preview.preview(runId).balances(), report.balances());
        DryRun stored = tx.execute(s -> {
            requireDryRunPossible(find(runId, true));
            if (!inputs[0].equals(stamp(runId))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "run_changed",
                        "Import changed",
                        "A file, mapping or review decision of this import changed while the dry run ran; run it again.");
            }
            jdbc.sql("""
                            UPDATE import_runs SET dry_run_report = CAST(? AS jsonb), dry_run_at = now(), dry_run_by = ?,
                                   dry_run_stamp = ?, dry_run_preview_matches = ?, dry_run_writer = ?,
                                   version = version + 1, updated_at = now()
                             WHERE id = ?
                            """)
                    .params(mapper.writeValueAsString(report), by, inputs[0], matches, inputs[1], runId)
                    .update();
            return storedDryRun(runId).orElseThrow();
        });
        // The run, the counts and the outcome only: never a file name, an anomaly or a value.
        log.info(
                "import run {} dry run: {} batches, {} anomalies, {} journals, failed {}, preview matches {}",
                runId,
                parts.size(),
                report.anomalies().size(),
                report.journals().size(),
                failure[0] != null,
                matches);
        return stored;
    }

    @Override
    public Optional<DryRun> dryRunReport(UUID runId) {
        TenantContext.require();
        return tx.execute(s -> {
            find(runId, false);
            return storedDryRun(runId);
        });
    }

    @Override
    public CommitReport commitReport(UUID runId) {
        TenantContext.require();
        return tx.execute(s -> {
            find(runId, false);
            CommitReport.Builder whole = new CommitReport.Builder(null);
            // A batch committed before V142 stored no part; no run was committed then (no writer existed).
            jdbc.sql("""
                            SELECT report::text FROM import_batches
                             WHERE run_id = ? AND status = 'committed' AND report IS NOT NULL ORDER BY dependency_rank, seq
                            """)
                    .param(runId)
                    .query(String.class)
                    .list()
                    .forEach(part -> whole.add(mapper.readValue(part, CommitReport.class)));
            return whole.build(CommitReport.COMMIT);
        });
    }

    /** The stored dry run of a run, inside the caller's transaction, with whether it still counts. */
    private Optional<DryRun> storedDryRun(UUID runId) {
        String now = stamp(runId);
        return jdbc.sql("""
                        SELECT dry_run_report::text AS report, dry_run_at, dry_run_by, dry_run_stamp, dry_run_preview_matches,
                               dry_run_writer
                          FROM import_runs WHERE id = ? AND dry_run_report IS NOT NULL
                        """)
                .param(runId)
                .query((rs, n) -> new DryRun(
                        mapper.readValue(rs.getString("report"), CommitReport.class),
                        instant(rs, "dry_run_at"),
                        rs.getObject("dry_run_by", UUID.class),
                        rs.getBoolean("dry_run_preview_matches"),
                        rs.getString("dry_run_writer"),
                        rs.getString("dry_run_stamp").equals(now)))
                .optional();
    }

    /**
     * Refuses a run whose writers are not those its dry run rehearsed (Lead ruling 4 on #869): the build must be the one
     * the dry run ran on, and every batch left to write must be written by the template, version, class, entity set and
     * loader the dry run wrote it with. Only the batches left are compared, so a resume after some batches committed is
     * checked too (review of d8d1e5b: a loader's class name alone does not tell two builds apart).
     */
    private void requireSameWriters(UUID runId) {
        String rehearsed = jdbc.sql("SELECT dry_run_writer FROM import_runs WHERE id = ?")
                .param(runId)
                .query(String.class)
                .optional()
                .orElse(null);
        List<String> lines = rehearsed == null ? List.of() : List.of(rehearsed.split("\n"));
        List<String> now = List.of(rehearsal(preview.writers(runId).identity()).split("\n"));
        boolean same = !lines.isEmpty()
                && lines.getFirst().equals(now.getFirst())
                && lines.containsAll(now.subList(1, now.size()));
        if (!same) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "dry_run_writer_differs",
                    "Not the rehearsed writer",
                    "The build, a template or a loader of this import changed since its dry run; run the dry run again"
                            + " on this build, or, once it commits, resume it only on the build it was rehearsed on.");
        }
    }

    /**
     * Refuses a run that may not be made ready or committed: no dry run that counts, a failed one, one the preview did not
     * match, or, at the commit, one whose writers are not those the dry run rehearsed.
     */
    private void requireDryRun(UUID runId, boolean writers) {
        if (writers) {
            requireSameWriters(runId);
        }
        DryRun dry = storedDryRun(runId).orElse(null);
        if (dry == null || !dry.current()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "dry_run_required",
                    "Dry run required",
                    "Run the dry run of this import first: there is none, or a file, mapping or review decision changed"
                            + " after it.");
        }
        if (dry.report().failure() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "dry_run_failed",
                    "Dry run failed",
                    "The last dry run of this import stopped at a batch; fix the cause and run it again.");
        }
        if (!dry.previewMatches()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "preview_differs",
                    "Preview differs",
                    "The balances of the dry run differ from the preview's; the import is not ready.");
        }
    }

    private void requireDryRunPossible(Run run) {
        // In review; or being imported, for the batches it has left, so a resume after a new deploy has a dry run on
        // the build that resumes it (Lead on #869). The loop claim keeps a live commit out meanwhile.
        if (!RunStates.IN_REVIEW.equals(run.status()) && !RunStates.COMMITTING.equals(run.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_not_open",
                    "Import not open",
                    "Only an import in review, or one being imported for the files it has left, can be dry run; this"
                            + " one is " + RunStates.words(run.status()) + ".");
        }
        if (jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM import_batches WHERE run_id = ? AND status IN ('uploaded', 'failed'))")
                .param(run.id())
                .query(Boolean.class)
                .single()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "batch_not_parsed",
                    "File not read",
                    "A file of this import is not read yet or was refused; read it again or cancel it first.");
        }
    }

    /**
     * Whether the preview's predicted balances are the dry run's (review of #869): stock quantity and value, receivables,
     * loan principal, loans and principal by arrears bucket, all per branch and summed per key, and each journal's branch,
     * kind, debit and credit, journal by journal; in any order, a zero the same as none.
     */
    static boolean sameBalances(ImportForecast.Balances predicted, ImportForecast.Balances written) {
        return figures(predicted).equals(figures(written));
    }

    private static Map<String, String> figures(ImportForecast.Balances b) {
        Map<String, BigDecimal> sums = new TreeMap<>();
        b.stock().forEach(s -> {
            sum(sums, "stock|" + s.branch() + "|qty", s.qty());
            sum(sums, "stock|" + s.branch() + "|value", BigDecimal.valueOf(s.valueMinor()));
        });
        b.receivables().forEach(r -> sum(sums, "receivable|" + r.branch(), BigDecimal.valueOf(r.amountMinor())));
        b.loanPrincipal().forEach(l -> sum(sums, "loan|" + l.branch(), BigDecimal.valueOf(l.amountMinor())));
        b.arrears().forEach(a -> {
            String key = "arrears|" + a.branch() + "|" + a.bucket();
            sum(sums, key + "|loans", BigDecimal.valueOf(a.loans()));
            sum(sums, key + "|principal", BigDecimal.valueOf(a.principalMinor()));
        });
        // Each journal on its own, never summed: two of 500 are not one of 1000 (review of d8d1e5b). Equal journals are
        // counted, so the comparison is of the two lists in any order; a journal of zeros is the same as none.
        b.journals().stream()
                .filter(j -> j.debitMinor() != 0 || j.creditMinor() != 0)
                .forEach(j -> sum(
                        sums,
                        "journal|" + j.branch() + "|" + j.kind() + "|" + j.debitMinor() + "|" + j.creditMinor(),
                        BigDecimal.ONE));
        Map<String, String> out = new TreeMap<>();
        sums.forEach((key, value) -> {
            if (value.signum() != 0) {
                out.put(key, value.stripTrailingZeros().toPlainString());
            }
        });
        return out;
    }

    private static void sum(Map<String, BigDecimal> sums, String key, BigDecimal value) {
        if (value != null) {
            sums.merge(key, value, BigDecimal::add);
        }
    }

    private List<PendingBatch> pending(UUID runId) {
        return jdbc.sql("""
                        SELECT id, seq FROM import_batches WHERE run_id = ? AND status IN ('pending', 'parsed') ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new PendingBatch(rs.getObject("id", UUID.class), rs.getInt("seq")))
                .list();
    }

    /** The batches in the template's dependency order (spec section 3.7, issue #133): its entity sets, then their number. */
    private static List<PendingBatch> ordered(List<PendingBatch> batches, PreviewService.Writers writers) {
        return batches.stream()
                .sorted(Comparator.comparingInt((PendingBatch b) -> writers.rank(b.id()))
                        .thenComparingInt(PendingBatch::seq))
                .toList();
    }

    /**
     * Claims the business's commit loop: {@code pg_try_advisory_lock} on the loop key, on a connection of its own, in
     * autocommit so it never sits idle in a transaction. Refused with {@code IMPORT_IN_PROGRESS} when another loop of the
     * business holds it, on this instance or another.
     */
    private LoopClaim claimLoop() {
        String key = LOOP_KEY + TenantContext.require();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean claimed;
        try {
            connection.setAutoCommit(true);
            try (PreparedStatement claim =
                    connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                claim.setString(1, key);
                try (ResultSet rs = claim.executeQuery()) {
                    claimed = rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new DataAccessResourceFailureException("The import's commit loop could not be claimed", e);
        }
        if (!claimed) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            // A commit, a resume or a dry run of the business holds the loop: said plainly (Lead on #869).
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IMPORT_IN_PROGRESS",
                    "Import in progress",
                    "Another import of this business is being committed or dry run; try again when it ends.");
        }
        return new LoopClaim(connection, key);
    }

    /** The claimed loop; closing it unlocks the loop key and gives the connection back. */
    private final class LoopClaim implements AutoCloseable {

        private final Connection connection;
        private final String key;

        LoopClaim(Connection connection, String key) {
            this.connection = connection;
            this.key = key;
        }

        /**
         * Asks the claim's own session whether it still holds the loop key. Null while it does; otherwise why the
         * claim is lost: {@code claim_connection_lost} when the probe fails, {@code claim_not_held} when it answers no.
         */
        String lostReason() {
            return probeClaim(connection, key, CLAIM_PROBE_TIMEOUT_MILLIS);
        }

        @Override
        public void close() {
            try (PreparedStatement unlock =
                    connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
                unlock.setString(1, key);
                unlock.execute();
            } catch (SQLException e) {
                // A session that could not unlock must not go back to the pool still holding the loop: end it.
                log.warn(
                        "import commit loop not unlocked, closing its connection: {}",
                        e.getClass().getSimpleName());
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException ignored) {
                    // The connection is being thrown away either way.
                }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    // JUSTIFICATION-A3: the probe body moved out of LoopClaim, static so issue #792's stalled read test can reach it.
    /**
     * The probe of {@link LoopClaim#lostReason()}: every read on the connection is bounded by {@code timeoutMillis}
     * through its network timeout, and the previous network timeout is put back afterwards, on every path. A timeout
     * that cannot be put back means the connection is closed or broken (a read that times out ends it), so the claim
     * counts as {@code claim_connection_lost} whatever the probe read.
     */
    static String probeClaim(Connection connection, String key, int timeoutMillis) {
        int previous;
        try {
            previous = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, timeoutMillis);
        } catch (SQLException e) {
            return "claim_connection_lost";
        }
        String reason;
        boolean restored = false;
        try (PreparedStatement probe = connection.prepareStatement(CLAIM_HELD)) {
            probe.setString(1, key);
            try (ResultSet rs = probe.executeQuery()) {
                reason = rs.next() && rs.getBoolean(1) ? null : "claim_not_held";
            }
        } catch (SQLException e) {
            reason = "claim_connection_lost";
        } finally {
            try {
                connection.setNetworkTimeout(Runnable::run, previous);
                restored = true;
            } catch (SQLException e) {
                // Closed or broken: the session and its loop key may be gone; the close of the claim discards it.
            }
        }
        return restored ? reason : "claim_connection_lost";
    }

    private CommitOutcome writeBatches(UUID runId, LoopClaim claim) {
        // The writers the service resolves from the batches' templates (Lead ruling 4 on #869), and the run's personal
        // values, read once for every batch's report (review of #869). The batches go in the template's dependency
        // order (issue #133), so a resume continues from the first batch of that order not committed.
        PreviewService.Writers writers = tx.execute(s -> preview.writers(runId));
        List<PendingBatch> pending = ordered(tx.execute(s -> pending(runId)), writers);
        PreviewService.RunPrivacy privacy = tx.execute(s -> preview.privacy(runId));
        for (PendingBatch batch : pending) {
            // Without the claim a resume may already own the business: write nothing more, leave the run resumable.
            String lost = claim.lostReason();
            if (lost != null) {
                log.warn("import commit loop lost its claim before batch {}: {}", batch.seq(), lost);
                tx.executeWithoutResult(s -> audit(
                        runId,
                        "core.import_run.claim_lost",
                        Map.of(),
                        Map.of("batch_seq", batch.seq(), "reason", lost)));
                return CommitOutcome.HALTED;
            }
            try {
                tx.executeWithoutResult(s -> writeBatch(runId, batch, writers, 0, privacy));
            } catch (Halted e) {
                tx.executeWithoutResult(s -> audit(
                        runId,
                        "core.import_run.halted",
                        Map.of(),
                        Map.of("batch_seq", batch.seq(), "reason", e.reason)));
                return CommitOutcome.HALTED;
            } catch (RuntimeException e) {
                // The batch rolled back as a whole and the run stays committing, so it can go on once fixed. The audit
                // says which batch failed and the kind of failure, never the message, which may quote the row's data.
                throw audited(
                        e,
                        () -> tx.executeWithoutResult(s -> audit(
                                runId,
                                "core.import_run.batch_failed",
                                Map.of(),
                                Map.of(
                                        "batch_seq",
                                        batch.seq(),
                                        "error",
                                        e.getClass().getSimpleName()))));
            }
            // The batch is committed: a process killed here resumes from the next batch (test hook, issue #133).
            seams.afterBatch(runId, batch.seq());
        }
        boolean finished = Boolean.TRUE.equals(tx.execute(s -> {
            Run run = find(runId, true);
            long left = jdbc.sql(
                            "SELECT count(*) FROM import_batches WHERE run_id = ? AND status IN ('pending', 'parsed')")
                    .param(runId)
                    .query(Long.class)
                    .single();
            // A concurrent resume may have finished the run first.
            if (!RunStates.COMMITTING.equals(run.status()) || left != 0) {
                return false;
            }
            setStatus(runId, RunStates.COMMITTED);
            // A halt pressed during the last batch, which ended before its next checkpoint, means nothing now.
            jdbc.sql("UPDATE import_runs SET halt_requested = false WHERE id = ? AND halt_requested")
                    .param(runId)
                    .update();
            audit(
                    runId,
                    "core.import_run.status_changed",
                    Map.of("status", RunStates.COMMITTING),
                    Map.of("status", RunStates.COMMITTED));
            return true;
        }));
        // The reconciliation is generated after the commit and stored (issue #134, spec section 3.9), in its own
        // transaction: the commit is done whatever it finds.
        if (finished) {
            reconciliations.afterCommit(runId);
        }
        return CommitOutcome.COMMITTED;
    }

    // JUSTIFICATION-A3: issue #132 makes the batch's commit report its part and serve the dry run, one code for both.
    /**
     * Writes one batch: the commit's code, and the dry run's inside its outer transaction ({@code dryRun}: the kill switch
     * and the halt, which guard writes, do not stop it). Returns the batch's part of the run's report, also stored on the
     * batch with its counts; null for a batch already committed.
     */
    private CommitReport writeBatch(
            UUID runId,
            PendingBatch batch,
            PreviewService.Writers writers,
            long dryRunLimitSeconds,
            PreviewService.RunPrivacy privacy) {
        // A dry run's batch has its limit in seconds; a committed batch has none here (0).
        boolean dryRun = dryRunLimitSeconds > 0;
        BatchWriter writer = writers.of(batch.id());
        // Never a wait (Lead ruling 5 on #869). A dry run holds the key to its end, so a commit that waited would fail
        // at the statement timeout as a failed batch; whoever finds the key taken is answered at once instead.
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(" + LOCK_KEY + ")")
                .query(Boolean.class)
                .single()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IMPORT_IN_PROGRESS",
                    "Import in progress",
                    "Another import is being written for this business; try again when it ends.");
        }
        Map<String, Object> row = jdbc.sql(
                        "SELECT status, source_filename, row_count FROM import_batches WHERE id = ? FOR UPDATE")
                .param(batch.id())
                .query()
                .singleRow();
        if ("committed".equals(row.get("status"))) {
            return null;
        }
        if (!dryRun) {
            stopIfRequested(runId);
        }
        // Each statement of a committed batch has at most 10 minutes (spec section 3.7, issue #133). A dry run keeps
        // its own limit, the outer transaction's timeout, and its statements have that limit too (60 seconds into a
        // trading business; Lead ruling 4 and the Lead's note on the review of 8e570b0, #872). In both, nothing the
        // batch writes may send a message, start a task or raise an approval: V143's triggers refuse it while
        // app.import_commit is on, until the transaction ends.
        String statementLimit = dryRun ? dryRunLimitSeconds + "s" : "10min";
        jdbc.sql("SELECT set_config('statement_timeout', ?, true)")
                .param(statementLimit)
                .query()
                .singleRow();
        jdbc.sql("SET LOCAL app.import_commit = 'on'").update();
        // And every event the batch publishes is an imported record's: its listeners send nothing (Lead ruling 3).
        ImportedRecords.markTransaction();
        String file = row.get("source_filename") == null
                ? "batch " + batch.seq()
                : RowValidator.shortName((String) row.get("source_filename"));
        RowCheckpoint checkpoint =
                new RowCheckpoint(runId, batch.id(), writers.source(batch.id()), !dryRun, file, privacy);
        Map<String, Object> counts = writer.write(runId, batch.id(), batch.seq(), checkpoint);
        // No journal but the registered opening journals (Lead ruling 6 on #872): checked at the batch's end, in both.
        refs.requireOnlyOpeningJournals(runId, batch.id());
        CommitReport part = checkpoint.part(counts, (Integer) row.get("row_count"));
        jdbc.sql("""
                        UPDATE import_batches SET status = 'committed', committed_at = now(), counts = CAST(? AS jsonb),
                               report = CAST(? AS jsonb), dependency_rank = ?
                         WHERE id = ?
                        """)
                .params(
                        mapper.writeValueAsString(counts == null ? Map.of() : counts),
                        mapper.writeValueAsString(part),
                        writers.setRank(batch.id()),
                        batch.id())
                .update();
        return part;
    }

    /**
     * Runs the audit of a failed batch and returns the batch's failure to throw. When the audit fails too (a lost
     * connection fails both), its error is added to the failure as suppressed, so the cause is never replaced.
     */
    static RuntimeException audited(RuntimeException failure, Runnable audit) {
        try {
            audit.run();
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
        return failure;
    }

    /** Throws {@link Halted} when the kill switch is off or the run's halt is set, read with a fresh snapshot. */
    private void stopIfRequested(UUID runId) {
        String reason =
                jdbc.sql("""
                        SELECT CASE WHEN NOT s.commit_enabled THEN 'kill_switch'
                                    WHEN r.halt_requested THEN 'halt_requested' END
                          FROM import_runs r CROSS JOIN platform_import_settings s
                         WHERE r.id = ? AND s.id = 1
                        """).param(runId).query(String.class).optional().orElse(null);
        if (reason != null) {
            throw new Halted(reason);
        }
    }

    // JUSTIFICATION-A3: issue #132 makes the checkpoint the report's sink as well as the guard.
    /**
     * The guard a writer calls after each row, and the sink of the batch's part of the report. Every text a writer gives it
     * (a source's name, an anomaly's file and message, a journal's branch, kind, status and entry number, a branch or bucket
     * of a balance, a note, the checksum) is kept as {@link CommitReport#HIDDEN} when it holds a personal value of any
     * staged row of the run (review of #869).
     */
    private final class RowCheckpoint implements BatchWriter.Checkpoint {

        private final UUID runId;
        private final UUID batchId;
        private final PreviewService.BatchSource source;
        private final boolean stops;
        private final String file;
        private final PreviewService.RunPrivacy privacy;
        private final CommitReport.Builder part;
        private long rows;
        private Map<String, String> policy;

        RowCheckpoint(
                UUID runId,
                UUID batchId,
                PreviewService.BatchSource source,
                boolean stops,
                String file,
                PreviewService.RunPrivacy privacy) {
            this.runId = runId;
            this.batchId = batchId;
            this.source = source;
            this.stops = stops;
            this.file = file;
            this.privacy = privacy;
            this.part = new CommitReport.Builder(file);
        }

        @Override
        public void rowWritten() {
            if (++rows % CHECKPOINT_ROWS == 0 && stops) {
                stopIfRequested(runId);
            }
        }

        // JUSTIFICATION-A3: issue #133's framework side of the commit, delegated to RefService in the batch's tx.
        @Override
        public List<ImportRefs.StagedRow> rows(int afterLine, int limit) {
            if (source == null) {
                throw new IllegalStateException("the batch has no source to read rows from");
            }
            return refs.rows(batchId, source, afterLine, limit);
        }

        @Override
        public void register(String sourceRef, String targetType, UUID targetId) {
            refs.register(runId, batchId, sourceRef, targetType, targetId);
        }

        @Override
        public String openingJournal(ImportRefs.Opening opening) {
            RefService.Posted posted = refs.openingJournal(runId, batchId, opening);
            journal(
                    opening.branchCode() == null ? opening.branchId().toString() : opening.branchCode(),
                    opening.kind(),
                    posted.debitMinor(),
                    posted.creditMinor(),
                    posted.status(),
                    posted.entryNo());
            return posted.status();
        }

        @Override
        public Optional<String> policy(String key) {
            if (policy == null) {
                Map<String, String> read = new LinkedHashMap<>();
                jdbc.sql("SELECT key, value ->> 'option' FROM import_runs, jsonb_each(policy) WHERE id = ?")
                        .param(runId)
                        .query((rs, n) -> read.put(rs.getString(1), rs.getString(2)))
                        .list();
                policy = read;
            }
            return Optional.ofNullable(policy.get(key));
        }

        @Override
        public void counts(String source, int read, int written, int existing, int skipped) {
            part.counts(safe(source), read, written, existing, skipped);
        }

        @Override
        public void anomaly(int line, String message) {
            part.anomaly(safe(file), line, safe(message));
        }

        @Override
        public void anomaly(String other, int line, String message) {
            part.anomaly(safe(other), line, safe(message));
        }

        @Override
        public void journal(
                String branch, String kind, long debitMinor, long creditMinor, String status, String entryNo) {
            part.journal(safe(branch), safe(kind), debitMinor, creditMinor, safe(status), safeOrNull(entryNo));
        }

        @Override
        public void stock(String branch, BigDecimal qty, long valueMinor) {
            part.stock(safe(branch), qty, valueMinor);
        }

        @Override
        public void receivable(String branch, long amountMinor) {
            part.receivable(safe(branch), amountMinor);
        }

        @Override
        public void loanPrincipal(String branch, long amountMinor) {
            part.loanPrincipal(safe(branch), amountMinor);
        }

        @Override
        public void arrears(String branch, String bucket, int loans, long principalMinor) {
            part.arrears(safe(branch), safe(bucket), loans, principalMinor);
        }

        @Override
        public void note(String note) {
            part.note(safe(note));
        }

        @Override
        public void checksum(String line) {
            part.checksum(safeOrNull(line));
        }

        private String safe(String text) {
            String s = String.valueOf(text);
            return privacy.holds(s) ? CommitReport.HIDDEN : s;
        }

        private String safeOrNull(String text) {
            return text == null ? null : safe(text);
        }

        /** The batch's part: one line of the table from the writer's counts unless it gave its files' own lines. */
        CommitReport part(Map<String, Object> counts, Integer rowCount) {
            if (!part.hasCounts()) {
                Map<String, Object> c = counts == null ? Map.of() : counts;
                part.counts(
                        safe(file),
                        number(c.get("read"), rowCount == null ? 0 : rowCount),
                        number(c.get("written"), 0),
                        number(c.get("existing"), 0),
                        number(c.get("skipped"), 0));
            }
            return part.build(CommitReport.COMMIT);
        }
    }

    private static int number(Object value, int otherwise) {
        return value instanceof Number n ? Math.toIntExact(n.longValue()) : otherwise;
    }

    /** Stops a batch and rolls it back; the run stays committing. */
    private static final class Halted extends RuntimeException {

        final String reason;

        Halted(String reason) {
            super("import halted: " + reason, null, false, false);
            this.reason = reason;
        }
    }

    private record PendingBatch(UUID id, int seq) {}

    // ---------------------------------------------------------------------------------------------
    // The kill switch.
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean commitEnabled() {
        return jdbc.sql("SELECT commit_enabled FROM platform_import_settings WHERE id = 1")
                .query(Boolean.class)
                .single();
    }

    @Override
    public void setCommitEnabled(boolean enabled, UUID platformUserId, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5 || why.length() > 300) {
            throw ApiException.rule("reason_required", "Say why the import commit is switched on or off.");
        }
        tx.executeWithoutResult(s -> {
            requireOperator(platformUserId, "operator_required");
            Boolean previous = jdbc.sql("SELECT platform_set_import_commit_enabled(?, ?)")
                    .params(enabled, platformUserId)
                    .query(Boolean.class)
                    .single();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("before", Map.of("commit_enabled", previous));
            data.put("after", Map.of("commit_enabled", enabled));
            data.put("reason", why);
            platformAudit.record("platform.imports.commit_enabled_changed", platformUserId, null, data);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private Run find(UUID runId, boolean forUpdate) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM import_runs WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""))
                .param(runId)
                .query(ImportRunService::run)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private void setStatus(UUID runId, String status) {
        jdbc.sql("UPDATE import_runs SET status = ?, " + RunStates.stampColumn(status)
                        + " = now(), version = version + 1, updated_at = now() WHERE id = ?")
                .params(status, runId)
                .update();
    }

    private void requireBeforeCommit(Run run) {
        if (!List.of(RunStates.DRAFT, RunStates.UPLOADED, RunStates.IN_REVIEW, RunStates.READY)
                .contains(run.status())) {
            throw invalidTransition(
                    "The import is " + RunStates.words(run.status()) + ", so it can no longer be changed.");
        }
    }

    private void requireCommitPossible(Run run) {
        if (!commitEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "imports_disabled",
                    "Imports are switched off",
                    "The platform's import commit is switched off; ask the operator.");
        }
        if (run.preImportDumpFile() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "pre_import_dump_missing",
                    "No pre-import dump",
                    "Record the verified dump taken before the import; without it there is no way back.");
        }
        // Recorded and verified (issue #133, spec section 3.11): a dump nobody listed may not restore.
        if (!run.preImportDumpVerified()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "pre_import_dump_unverified",
                    "Pre-import dump not verified",
                    "Check the dump with pg_restore --list and record the check; an unchecked dump is no way back.");
        }
    }

    private void audit(UUID runId, String action, Map<String, Object> before, Map<String, Object> after) {
        audit.record(new AuditLog.Entry(action, "import_run", runId, null, before, after));
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An import commit takes one transaction per batch; call it outside one");
        }
        TenantContext.require();
    }

    private static ApiException importInProgress() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "IMPORT_IN_PROGRESS",
                "Import in progress",
                "Another import is being committed for this business; wait for it or stop it.");
    }

    private static ApiException invalidTransition(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_status_transition", "Invalid status transition", detail);
    }

    private static Run run(ResultSet rs, int n) throws SQLException {
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getInt("version"),
                instant(rs, "cutover_at"),
                rs.getBoolean("halt_requested"),
                rs.getBoolean("allow_trading_tenant"),
                rs.getObject("allow_trading_set_by", UUID.class),
                rs.getObject("allow_trading_approved_by", UUID.class),
                rs.getString("pre_import_dump_file"),
                instant(rs, "committing_at"),
                instant(rs, "committed_at"),
                rs.getTimestamp("pre_import_dump_verified_at") != null,
                rs.getObject("commit_requested_by", UUID.class),
                rs.getObject("commit_approved_by", UUID.class),
                (Integer) rs.getObject("commit_approved_version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
