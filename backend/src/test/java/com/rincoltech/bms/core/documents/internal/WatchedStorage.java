package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test double for the report runs (extras slice D1): the in-memory object storage, but one that behaves like the real
 * upload from a file. It never reads the file in one go (the real one hands the file to the S3 client, which streams it):
 * it reads in blocks of 64 KiB and records the largest read it ever made, so a test can assert that a file of megabytes
 * was taken in blocks and never as a whole. It also records, at the moment of an upload, whether a transaction is open on
 * the uploading thread and whether that thread holds a pooled connection (the run must hold neither while the output
 * goes). With {@link #discard} set it keeps nothing of the content, so a memory test measures the code under test and not
 * this double. It lives in this package because the storage is internal to the documents module. Import {@link Config}.
 */
public final class WatchedStorage extends FakeObjectStorage {

    /** {@code tx=<true|false> conn=<true|false>} as of the last upload from a file. */
    public static final AtomicReference<String> UPLOAD = new AtomicReference<>("");

    /** The largest single read made from a file being uploaded, in bytes. */
    public static final AtomicInteger MAX_READ = new AtomicInteger();

    /** Bytes read from files being uploaded, and the largest file. */
    public static final AtomicLong TOTAL = new AtomicLong();

    public static final AtomicLong LARGEST = new AtomicLong();

    /** Keep nothing of an uploaded file's content (for a memory test). */
    public static volatile boolean discard;

    static final int BLOCK = 64 * 1024;

    private final DataSource pool;

    private WatchedStorage(String secret, BusinessClock clock, DataSource pool) {
        super(secret, clock);
        this.pool = pool;
    }

    @Override
    public void putFile(String key, Path file, String contentType) {
        UPLOAD.set("tx=" + TransactionSynchronizationManager.isActualTransactionActive() + " conn="
                + TransactionSynchronizationManager.hasResource(pool));
        ByteArrayOutputStream kept = discard ? null : new ByteArrayOutputStream();
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] block = new byte[BLOCK];
            int n;
            while ((n = in.read(block)) > 0) {
                MAX_READ.accumulateAndGet(n, Math::max);
                size += n;
                if (kept != null) {
                    kept.write(block, 0, n);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TOTAL.addAndGet(size);
        LARGEST.accumulateAndGet(size, Math::max);
        put(key, kept == null ? new byte[0] : kept.toByteArray(), contentType);
    }

    @TestConfiguration
    public static class Config {

        /** The primary storage of the test context, signing with the secret of application-test.yml. */
        @Bean
        @Primary
        FakeObjectStorage watchedStorage(BusinessClock clock, DataSource pool) {
            return new WatchedStorage("test-only-fake-storage-secret", clock, pool);
        }
    }
}
