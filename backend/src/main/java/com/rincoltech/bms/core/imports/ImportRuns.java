package com.rincoltech.bms.core.imports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The runs of customer data onboarding (issue #127, spec sections 3.7, 3.11 and 5.4). Every method works for the business
 * bound to the thread (ADR-003). The methods that change one run take their own transaction; {@link #commit} and
 * {@link #resume} take one per batch and refuse to start inside a caller's transaction.
 *
 * <p>Refusals: a transition the state diagram does not allow ({@code invalid_status_transition}, 409); a second commit for
 * the business while one is committing ({@code IMPORT_IN_PROGRESS}, 409); a commit into a business that already trades
 * without the approved override ({@code TENANT_TRADING}, 409, audited); a commit with the kill switch off
 * ({@code imports_disabled}, 409) or without the pre-import dump ({@code pre_import_dump_missing}, 409); an override
 * approved by the operator who set it ({@code self_approval_forbidden}, 403).
 */
public interface ImportRuns {

    /** Opens a run in {@code draft}. */
    Run open(UUID createdBy);

    Run get(UUID runId);

    /**
     * Moves a run along the diagram of spec section 3.11, stamping the stage time: {@code draft} to {@code uploaded} to
     * {@code in_review} to {@code ready}, {@code committed} to {@code signed_off} to {@code purged}, and {@code cancelled}
     * from any state before {@code committing}. {@code committing} and {@code committed} are reached only through
     * {@link #commit}. {@code ready} needs a stored dry run that still counts, did not fail and matched the preview
     * (issue #132): 409 {@code dry_run_required}, {@code dry_run_failed} or {@code preview_differs} otherwise; the commit
     * checks the same again and, with the resume, refuses a run whose writers (its templates' loaders, their versions and
     * code) are not the ones the dry run rehearsed (409 {@code dry_run_writer_differs}).
     */
    Run transition(UUID runId, String status);

    /** Adds a pending batch to a run not yet committing, in commit order {@code seq} (1, 2, ...). Upload replaces this in #128. */
    UUID addBatch(UUID runId, int seq);

    /** Records the verified dump taken before the commit (file name, time, SHA-256 in lower-case hex). */
    Run recordPreImportDump(UUID runId, String file, Instant takenAt, String sha256);

    // JUSTIFICATION-A3: issue #133 adds the verified dump, the policy decisions and the import_commit request.
    /**
     * Records that the dump recorded on the run was verified (issue #133, spec section 3.11): its SHA-256 again, which must
     * be the one recorded, and the number of entries {@code pg_restore --list} listed, above zero. Recording another dump
     * clears it. No verified dump, no commit (409 {@code pre_import_dump_unverified}); a SHA-256 that differs is 422
     * {@code pre_import_dump_differs}.
     */
    Run verifyPreImportDump(UUID runId, String sha256, int listedEntries);

    /**
     * Records a policy decision the customer confirmed (issue #133, spec section 3.8), on a run not yet ready: one of the
     * options the run's template declares for the key ({@code policy_unknown}, {@code policy_option_unknown} otherwise,
     * 422), with the day the customer confirmed it and the reference of their confirmation (the numbered question list or
     * a document, never a personal value). An option allowed only with the dev lead's exception ({@code defer}) also needs
     * the platform operator who granted it and the day of the follow-up ({@code policy_exception_required}, 422). Audited.
     */
    Run recordPolicy(UUID runId, String key, PolicyDecision decision);

    /**
     * The maker's request of {@code import_commit} (issue #133, chapter 8 section 8.4, ADR-015) for a {@code ready} run with
     * a verified dump and every policy decision it needs, by the signed-in staff user, who needs {@code core.imports.manage}
     * (403 otherwise). Returns the pending approval request; a different user with {@code core.imports.approve_commit}
     * approves it in the approvals queue, and the database refuses a checker who is the maker. The approval marks the run
     * approved at its version then; a change to the run after it (a new dump, a trading override) needs a new request. The
     * request goes stale when the run changes before it is decided.
     */
    UUID requestCommit(UUID runId);

    /**
     * A recorded policy decision: the option, the day the customer confirmed it and the reference of that confirmation,
     * and, for an option that needs it, the platform operator who granted the exception and the day of the follow-up.
     */
    record PolicyDecision(
            String option,
            java.time.LocalDate confirmedOn,
            String confirmation,
            UUID recordedBy,
            UUID exceptionBy,
            java.time.LocalDate followUpOn) {}

    /** The operator's stop button: the commit stops before the next batch, or within 1,000 rows of the current one. */
    Run requestHalt(UUID runId);

    /** A platform operator allows this run to write into a business that already trades, with a reason. Audited. */
    Run allowTradingTenant(UUID runId, UUID platformUserId, String reason);

    /** A different platform operator (the dev lead) approves the override. Audited. */
    Run approveTradingTenant(UUID runId, UUID platformUserId);

    /**
     * Commits a {@code ready} run: checks the kill switch, the dump and the trading tenant guard, moves it to
     * {@code committing}, then writes its batches in order, one transaction each, and moves it to {@code committed}. Each
     * batch is written by its template's loader for the entity set its mapping source names (Lead ruling on #869): the
     * service resolves the writer, a caller never passes one, and a batch read without a mapping is 422
     * {@code BATCH_HAS_NO_MAPPING}. Since issue #133 the batches are written in the template's dependency order (its entity
     * sets, then the batch's own number), each under {@code SET LOCAL statement_timeout} of 10 minutes, and the commit needs
     * a verified dump ({@code pre_import_dump_unverified}), every policy decision the run needs (409 {@code
     * CREDIT_DECISION_MISSING} or the policy's own code) and an approved {@code import_commit} for the run as it is now
     * (409 {@code commit_not_approved}).
     */
    CommitOutcome commit(UUID runId);

    /**
     * Continues a {@code committing} run from its first uncommitted batch, after a halt, a failure or a restart, only on
     * the build and with the writers its dry run rehearsed (409 {@code dry_run_writer_differs}); after a new deploy, a new
     * {@link #dryRun} of the batches it has left comes first, and one that failed is 409 {@code dry_run_failed}, one that
     * did not match the preview 409 {@code preview_differs}.
     */
    CommitOutcome resume(UUID runId);

    // JUSTIFICATION-A3: issue #132 adds the dry run and the stored reports to the run's API, new methods beside commit.
    /**
     * The dry run of a run {@code in_review}, or of one {@code committing} for the batches it has left before a resume on
     * a new build (issue #132, ADR-027 decision 5): the commit's own batch code, with the writers
     * the commit resolves (each batch's template loader), inside one outer transaction that is rolled back, so nothing is
     * written (no row, no sequence value, no file, no audit row); then its report is stored on the run with the stamp of the
     * run's inputs (the writers' identities and template versions and the application's version included), the writers'
     * identity and whether the preview's balances equal the dry run's. It holds the business's commit loop while it runs,
     * and takes the business's import key with {@code pg_try_advisory_xact_lock}, so it never waits on a commit and a
     * commit never waits on it: either is answered at once with 409 {@code IMPORT_IN_PROGRESS}. It is not stopped by the
     * kill switch or the halt, which guard writes. A writer that throws stops the dry run at that batch, and the stored
     * report says which batch failed and the kind of failure. Refused with 409 {@code run_not_open} for a run in another
     * state, 409 {@code batch_not_parsed} while a file of the run is stored and not read or was refused, 422
     * {@code BATCH_HAS_NO_MAPPING} for a batch read without a mapping, and 409 {@code run_changed} when a batch, issue or
     * resolution of the run changed while it ran (nothing is stored). Into a business that already trades it is 409
     * {@code TENANT_TRADING} without the approved override, and under it bounded by
     * {@code bms.imports.dry-run-limit-while-trading}, since its writes hold the business's rows until the rollback; any
     * other dry run by {@code bms.imports.dry-run-limit}. A
     * server with no build identity refuses it, the commit and a resume with 503 {@code build_unknown}. Takes its own
     * transactions; none may be open around it.
     */
    DryRun dryRun(UUID runId, UUID by);

    /**
     * The run's stored dry run, if any. It no longer counts ({@link DryRun#current()} false) once any batch, mapping
     * version, issue, resolution, the cutover moment or a policy decision of the run changed after it.
     */
    Optional<DryRun> dryRunReport(UUID runId);

    /** The report of the real run so far: the parts its committed batches stored, in order. */
    CommitReport commitReport(UUID runId);

    /**
     * A stored dry run: its report, when and by whom it ran, whether the preview's balances equalled its own, the writers it
     * rehearsed (per batch its template and version, the template's class, the entity set and the loader's
     * {@link BatchWriter#identity()}), and whether it still counts (the run's inputs, the writers, the application's version
     * and the business's committed imports are those it ran on).
     */
    record DryRun(CommitReport report, Instant at, UUID by, boolean previewMatches, String writer, boolean current) {}

    /** The platform kill switch {@code imports.commit_enabled}. */
    boolean commitEnabled();

    /** Sets the kill switch; audited in the platform log with the operator's reason. */
    void setCommitEnabled(boolean enabled, UUID platformUserId, String reason);

    /**
     * How a commit ended: every batch committed, or stopped by the kill switch, the halt or a lost loop claim with the
     * run still {@code committing} and resumable.
     */
    enum CommitOutcome {
        COMMITTED,
        HALTED
    }

    record Run(
            UUID id,
            String status,
            int version,
            Instant cutoverAt,
            boolean haltRequested,
            boolean allowTradingTenant,
            UUID allowTradingSetBy,
            UUID allowTradingApprovedBy,
            String preImportDumpFile,
            Instant committingAt,
            Instant committedAt,
            boolean preImportDumpVerified,
            UUID commitRequestedBy,
            UUID commitApprovedBy,
            Integer commitApprovedVersion) {

        /** Whether {@code import_commit} is approved for the run as it is now: the run has not changed since. */
        public boolean commitApproved() {
            return commitApprovedBy != null && commitApprovedVersion != null && commitApprovedVersion == version;
        }

        /** Whether the trading tenant override is both set and approved. */
        public boolean tradingOverrideApproved() {
            return allowTradingTenant && allowTradingApprovedBy != null;
        }
    }
}
