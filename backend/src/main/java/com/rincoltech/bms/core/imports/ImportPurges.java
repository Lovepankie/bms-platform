package com.rincoltech.bms.core.imports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Retention, purge and the certificate of deletion of a run (issue #139, spec section 6.4, ADR-027 decision 8, migration
 * V147). Every method works for the business bound to the thread and takes its own transactions; none may be open around
 * it.
 *
 * <p>The purge deletes the run's quarantined files from object storage and its wrapped data key, the cells and typed values
 * of its staged rows (each row's reference becomes its hash), the original cell values of its issues, and the values before
 * and after of its resolutions with their row keys and notes, which become their hash. It keeps the run, its batches with their file
 * names, sizes and SHA-256, its issues without values, its resolutions with hashes, the counts, the reports, the signed
 * reconciliation, the rehearsals and the references of the records it imported. The run becomes {@code purged}; the
 * certificate of deletion is stored on it; both are audited ({@code core.import_run.purged}). A run already purged is
 * never purged again: the call answers its certificate.
 *
 * <p>The scheduled purge (job {@code core.imports.retention}) does the same within 30 days of a sign-off (on day 29) and
 * 90 days after the last activity of a run never signed off. The operators are warned a week before; the deadline wins over
 * a warning that never went, and the purge then tells the operators and the business's Owner (Lead ruling on #886).
 */
public interface ImportPurges {

    /**
     * The purge at stage 9 (spec section 2) by a platform operator with a different platform operator as witness, both
     * named by their sign-in email, of a {@code signed_off} run (409 {@code run_not_signed_off} otherwise). When the run
     * recorded a pre-import dump the operator confirms they deleted it by its file name and SHA-256, which must be the
     * ones recorded (422 {@code pre_import_dump_confirmation_required}, {@code pre_import_dump_differs}); a run without one
     * takes none (422 {@code no_pre_import_dump}). An operator or witness who is not an active platform operator is 422
     * {@code platform_operator_unknown}; the same person twice is 422 {@code witness_is_operator}.
     *
     * <p>On a run the scheduled purge already purged, the operator's confirmation of the dump issues the next version of
     * its certificate, signed by them and the witness, and changes nothing else; any other call on a purged run answers
     * its newest certificate and writes nothing. Object storage that refuses a delete is 503 {@code storage_unavailable}:
     * the files deleted before it stay deleted, the run, its key and its rows are unchanged, and running the purge again
     * finishes it. Any failure after the files are deleted leaves the run the same way.
     */
    Certificate purge(UUID runId, Purge request);

    /** The newest certificate of deletion of a run, empty until it is purged. */
    Optional<Certificate> certificate(UUID runId);

    /**
     * The operator's request: who purges, who witnesses, the pre-import dump as they deleted it (both null for none), and
     * where it was run from (the host command's host and user), which the audit row records beside the two names.
     */
    record Purge(String operatorEmail, String witnessEmail, String dumpFile, String dumpSha256, String origin) {

        public Purge(String operatorEmail, String witnessEmail, String dumpFile, String dumpSha256) {
            this(operatorEmail, witnessEmail, dumpFile, dumpSha256, null);
        }
    }

    /**
     * A certificate of deletion: why the run was purged ({@code operator}, {@code signed_off_30_days} or
     * {@code inactive_90_days}), whether the operator confirmed the dump, the document as plain text and its SHA-256.
     */
    record Certificate(
            UUID id,
            UUID runId,
            int version,
            Instant issuedAt,
            String reason,
            boolean dumpConfirmed,
            String document,
            String sha256) {}
}
