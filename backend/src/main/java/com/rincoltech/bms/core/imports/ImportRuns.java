package com.rincoltech.bms.core.imports;

import java.time.Instant;
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
     * {@link #commit}.
     */
    Run transition(UUID runId, String status);

    /** Adds a pending batch to a run not yet committing, in commit order {@code seq} (1, 2, ...). Upload replaces this in #128. */
    UUID addBatch(UUID runId, int seq);

    /** Records the verified dump taken before the commit (file name, time, SHA-256 in lower-case hex). */
    Run recordPreImportDump(UUID runId, String file, Instant takenAt, String sha256);

    /** The operator's stop button: the commit stops before the next batch, or within 1,000 rows of the current one. */
    Run requestHalt(UUID runId);

    /** A platform operator allows this run to write into a business that already trades, with a reason. Audited. */
    Run allowTradingTenant(UUID runId, UUID platformUserId, String reason);

    /** A different platform operator (the dev lead) approves the override. Audited. */
    Run approveTradingTenant(UUID runId, UUID platformUserId);

    /**
     * Commits a {@code ready} run: checks the kill switch, the dump and the trading tenant guard, moves it to
     * {@code committing}, then writes its batches in order, one transaction each, and moves it to {@code committed}.
     */
    CommitOutcome commit(UUID runId, BatchWriter writer);

    /** Continues a {@code committing} run from its first uncommitted batch, after a halt, a failure or a restart. */
    CommitOutcome resume(UUID runId, BatchWriter writer);

    /** The platform kill switch {@code imports.commit_enabled}. */
    boolean commitEnabled();

    /** Sets the kill switch; audited in the platform log with the operator's reason. */
    void setCommitEnabled(boolean enabled, UUID platformUserId, String reason);

    /**
     * How a commit ended: every batch committed, or stopped by the kill switch or the halt with the run still
     * {@code committing} and resumable.
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
            Instant committedAt) {

        /** Whether the trading tenant override is both set and approved. */
        public boolean tradingOverrideApproved() {
            return allowTradingTenant && allowTradingApprovedBy != null;
        }
    }
}
