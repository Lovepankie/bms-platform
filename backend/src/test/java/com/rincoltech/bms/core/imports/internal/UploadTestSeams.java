package com.rincoltech.bms.core.imports.internal;

import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * The tests' {@link UploadService.Seams}, registered only on the test classpath (as {@code TestApprovalAction} is): a
 * fixed free disk, and a step run between fetching a batch's file and writing its rows. Both unset by default, so every
 * other test sees the real behaviour; a test that sets one clears it after.
 */
@Component
class UploadTestSeams implements UploadService.Seams {

    /** The free disk the next uploads see, in bytes; {@code null} reads the work directory's file store. */
    volatile LongSupplier freeDisk;

    volatile Runnable afterFetch;

    @Override
    public long freeDisk(Path work) throws java.io.IOException {
        LongSupplier fixed = freeDisk;
        return fixed != null ? fixed.getAsLong() : UploadService.Seams.super.freeDisk(work);
    }

    @Override
    public void afterFetch() {
        Runnable step = afterFetch;
        if (step != null) {
            step.run();
        }
    }
}
