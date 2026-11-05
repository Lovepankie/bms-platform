package com.rincoltech.bms.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #852 (ADR-026 decision 5) for work with no signed-in person: a job or a provider's callback keys under its own
 * stable principal ({@link Idempotency#onceAsJob}), so a retried run replays the first run's answer instead of being
 * refused as someone else; another job with the key is refused; and {@link Idempotency#once} with nobody signed in does
 * nothing. Fabricated names.
 */
class IdempotencyJobRetryIT extends IntegrationTest {

    @Autowired
    TenantJobs jobs;

    @Autowired
    Idempotency idempotency;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("idem-job", false, true);
    }

    Idempotency.Outcome<Map> run(String job, String key, Map<String, Object> request, AtomicInteger runs) {
        return jobs.callForTenant(
                t.tenantId(),
                () -> idempotency.onceAsJob(job, key, "POST", "/test/job", request, Map.class, () -> {
                    runs.incrementAndGet();
                    return Map.of("done", "Test run");
                }));
    }

    @Test
    void aRetriedJobReplaysItsFirstAnswerAndDoesTheWorkOnce() {
        String key = "job-" + UUID.randomUUID();
        Map<String, Object> request = Map.of("subject", "Test subject");
        AtomicInteger runs = new AtomicInteger();

        Idempotency.Outcome<Map> first = run("test-nightly-job", key, request, runs);
        Idempotency.Outcome<Map> retried = run("test-nightly-job", key, request, runs);

        assertThat(first.replayed()).isFalse();
        assertThat(retried.replayed()).isTrue();
        assertThat(retried.body()).isEqualTo(first.body());
        assertThat(runs).hasValue(1);
        assertThat(KeyOwnership.holders(t.tenantId(), key))
                .containsExactly(Idempotency.jobPrincipal("test-nightly-job"));

        assertThatThrownBy(() -> run("test-other-job", key, request, runs))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(Idempotency.OTHER_USER));
        assertThat(runs).hasValue(1);
    }

    @Test
    void onceWithNobodySignedInDoesNothing() {
        String key = "job-" + UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> jobs.callForTenant(
                        t.tenantId(),
                        () -> idempotency.once(key, "POST", "/test/job", Map.of(), Map.class, () -> {
                            runs.incrementAndGet();
                            return Map.of();
                        })))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("unauthenticated"));
        assertThat(runs).hasValue(0);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).isEmpty();
    }
}
