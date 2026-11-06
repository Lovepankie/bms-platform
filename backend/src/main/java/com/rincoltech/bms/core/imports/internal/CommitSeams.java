package com.rincoltech.bms.core.imports.internal;

import java.util.UUID;

/**
 * The commit's test hook (issue #133, acceptance criterion 1): called after each batch's transaction has committed and before
 * the next batch starts. A test bean throws from it to stand for the process being killed after batch k, so the resume is
 * tested against a run interrupted exactly there. No production bean implements it; the commit uses a no-op without one, as
 * {@code UploadService} does with its own seams.
 */
interface CommitSeams {

    /** After the batch numbered {@code seq} of the run committed. */
    default void afterBatch(UUID runId, int seq) {}
}
