package com.rincoltech.bms.core.imports.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The upload's test seam (review of #790): a free disk a test sets, and a step a test runs between the fetch and the read
 * of a batch's file. Unset, it behaves as the application's {@link UploadHooks}. Import {@link Config}; reset both in an
 * {@code @AfterEach}.
 */
final class TestUploadHooks extends UploadHooks {

    volatile LongSupplier freeDisk;

    volatile Runnable afterFetch;

    void reset() {
        freeDisk = null;
        afterFetch = null;
    }

    @Override
    long freeDisk(Path workDir) throws IOException {
        LongSupplier set = freeDisk;
        return set != null ? set.getAsLong() : super.freeDisk(workDir);
    }

    @Override
    void afterFetch() {
        Runnable step = afterFetch;
        if (step != null) {
            step.run();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        TestUploadHooks testUploadHooks() {
            return new TestUploadHooks();
        }
    }
}
