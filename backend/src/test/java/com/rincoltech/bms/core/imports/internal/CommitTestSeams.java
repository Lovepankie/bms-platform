package com.rincoltech.bms.core.imports.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The tests' {@link CommitSeams}, registered only on the test classpath (as {@link UploadTestSeams} is): when
 * {@link #killAfter} is set, the commit loop dies right after the batch of that number committed, as a process killed
 * there would, so the resume is tested from exactly that point. Unset by default; a test that sets it clears it after.
 */
@Component
public class CommitTestSeams implements CommitSeams {

    /** The batch number after whose commit the loop dies, or 0 for never (public for the lending template, #136). */
    public volatile int killAfter;

    @Override
    public void afterBatch(UUID runId, int seq) {
        if (killAfter > 0 && seq == killAfter) {
            killAfter = 0;
            throw new Killed(seq);
        }
    }

    /**
     * The process dying: an {@link Error}, so the commit's own handling of a failed batch, which catches runtime
     * exceptions, never sees it, and the loop's claim is let go as a dead process's would be.
     */
    public static final class Killed extends Error {

        Killed(int seq) {
            super("fabricated kill after batch " + seq, null, false, false);
        }
    }
}
