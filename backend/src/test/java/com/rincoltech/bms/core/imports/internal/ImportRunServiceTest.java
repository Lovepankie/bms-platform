package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.rincoltech.bms.kernel.ApiException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The audit of a failed batch never replaces the batch's own error (review of #736, issue #779), and the claim probe is
 * bounded by the connection's network timeout, which it puts back (issue #792).
 */
class ImportRunServiceTest {

    private static final String KEY = "core.imports.loop:fabricated";

    private static final String MESSAGE = "The plan allows at most 1 (max_branches); the limit has been reached.";

    private static final String SENTENCE = "the plan's limit refused this batch; move the business to a plan with room";

    @Test
    void aFailingAuditIsAddedAsSuppressedAndTheBatchErrorIsTheOneThrown() {
        IllegalStateException batch = new IllegalStateException("fabricated row refused");
        IllegalStateException lost = new IllegalStateException("fabricated connection lost");

        RuntimeException thrown = ImportRunService.audited(batch, () -> {
            throw lost;
        });

        assertThat(thrown).isSameAs(batch);
        assertThat(thrown.getSuppressed()).containsExactly(lost);
    }

    // JUSTIFICATION-A3: review of #902 asks for a test that a value-bearing refusal keeps no message.
    @Test
    void aRefusalKeepsItsCodeAndAFixedSentenceNeverItsMessage() {
        ApiException quoting = ApiException.rule("duplicate_sku", "SKU 'TST-0001' already exists for Test Sugar 1kg");
        ApiException silent = new ApiException(HttpStatus.CONFLICT, "no_message", "Refused", null);
        ApiException limit = ApiException.rule("plan_limit_reached", MESSAGE);

        assertThat(ImportRunService.refused(2, quoting)).isEqualTo("batch 2 failed (refused with code duplicate_sku)");
        assertThat(ImportRunService.refused(3, silent)).isEqualTo("batch 3 failed (refused with code no_message)");
        String kept = ImportRunService.refused(1, limit);
        assertThat(kept).isEqualTo("batch 1 failed (plan_limit_reached: " + SENTENCE + ")");
        assertThat(kept).doesNotContain("max_branches");
    }

    @Test
    void anAuditThatSucceedsLeavesTheBatchErrorAsItWas() {
        IllegalStateException batch = new IllegalStateException("fabricated row refused");
        AtomicBoolean audited = new AtomicBoolean();

        RuntimeException thrown = ImportRunService.audited(batch, () -> audited.set(true));

        assertThat(audited).isTrue();
        assertThat(thrown).isSameAs(batch);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    @Test
    void aProbeWhoseReadStallsCountsTheClaimLostWithinItsNetworkTimeout() {
        // JUSTIFICATION-A3: the stalled read test issue #792 asks for; without the network timeout the read never ends.
        FakeClaimConnection claim = new FakeClaimConnection(Read.STALLS, 0);

        String reason = assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> ImportRunService.probeClaim(claim.connection(), KEY, 200));

        assertThat(reason).isEqualTo("claim_connection_lost");
        assertThat(claim.timeouts)
                .as("the bound was set; the closed connection has nothing to restore")
                .containsExactly(200);
    }

    @Test
    void aProbeThatAnswersPutsThePreviousNetworkTimeoutBack() {
        FakeClaimConnection claim = new FakeClaimConnection(Read.HOLDS, 30_000);

        String reason = ImportRunService.probeClaim(claim.connection(), KEY, 200);

        assertThat(reason).as("the claim is still held").isNull();
        assertThat(claim.timeouts).containsExactly(200, 30_000);
    }

    @Test
    void aProbeRefusedOnAnOpenConnectionPutsThePreviousNetworkTimeoutBack() {
        // JUSTIFICATION-A3: the restore paths the review of #801 asks to cover, a refused probe and a failed restore.
        FakeClaimConnection claim = new FakeClaimConnection(Read.REFUSED, 30_000);

        String reason = ImportRunService.probeClaim(claim.connection(), KEY, 200);

        assertThat(reason).isEqualTo("claim_connection_lost");
        assertThat(claim.timeouts).containsExactly(200, 30_000);
    }

    @Test
    void aProbeThatAnswersButCannotPutTheTimeoutBackCountsTheClaimLost() {
        FakeClaimConnection claim = new FakeClaimConnection(Read.HOLDS_THEN_DROPS, 30_000);

        String reason = ImportRunService.probeClaim(claim.connection(), KEY, 200);

        assertThat(reason).as("the session that answered is gone").isEqualTo("claim_connection_lost");
        assertThat(claim.timeouts).containsExactly(200);
    }

    /** What the fabricated socket does with the probe's read. */
    private enum Read {
        STALLS,
        HOLDS,
        REFUSED,
        HOLDS_THEN_DROPS
    }

    /**
     * A claim connection over a fabricated socket. A stalled read blocks until the network timeout, then ends the
     * connection, as pgjdbc does on a socket read timeout; with no network timeout it blocks past the test's deadline.
     */
    private static final class FakeClaimConnection {

        final List<Integer> timeouts = new CopyOnWriteArrayList<>();
        private final Read read;
        private volatile int networkTimeout;
        private volatile boolean closed;

        FakeClaimConnection(Read read, int networkTimeout) {
            this.read = read;
            this.networkTimeout = networkTimeout;
        }

        Connection connection() {
            return proxy(Connection.class, (p, method, args) -> {
                if (closed) {
                    throw new SQLException("fabricated connection closed", "08003");
                }
                return switch (method.getName()) {
                    case "getNetworkTimeout" -> networkTimeout;
                    case "setNetworkTimeout" -> {
                        networkTimeout = (Integer) args[1];
                        timeouts.add(networkTimeout);
                        yield null;
                    }
                    case "prepareStatement" -> statement();
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
                case "setString", "close" -> null;
                case "executeQuery" -> read();
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private ResultSet read() throws InterruptedException, SQLException {
            if (read == Read.STALLS) {
                Thread.sleep(networkTimeout == 0 ? 60_000 : networkTimeout);
                closed = true;
                throw new SQLException(
                        "fabricated read timed out", "08006", new SocketTimeoutException("Read timed out"));
            }
            if (read == Read.REFUSED) {
                throw new SQLException("fabricated relation refused", "42P01");
            }
            if (read == Read.HOLDS_THEN_DROPS) {
                closed = true;
            }
            return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
                case "next", "getBoolean" -> true;
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(
                    Proxy.newProxyInstance(FakeClaimConnection.class.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }
}
