package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.internal.CircuitBreaker.Permit;
import com.rincoltech.bms.core.ai.internal.CircuitBreaker.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The breaker's states (spec 5.7): five failures in a row open it for 60 seconds, then one probe
 * decides; and a permit per call so a late result from a call that began before the breaker opened
 * cannot close it, count against it, or free the probe's slot. The clock is a field the test moves.
 */
class CircuitBreakerTest {

    Instant now = Instant.parse("2026-03-02T08:00:00Z");
    final CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(60), () -> now);

    Permit acquire() {
        return breaker.tryAcquire().orElseThrow();
    }

    void fail(int times) {
        for (int i = 0; i < times; i++) {
            breaker.recordFailure(acquire());
        }
    }

    @Test
    void staysClosedBelowTheThreshold() {
        fail(4);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void aSuccessResetsTheCountOfConsecutiveFailures() {
        fail(4);
        breaker.recordSuccess(acquire());
        fail(4);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void fiveFailuresInARowOpenItAndRefuseWithoutACall() {
        fail(5);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquire()).isEmpty();
        now = now.plusSeconds(59);
        assertThat(breaker.tryAcquire()).isEmpty();
    }

    @Test
    void afterSixtySecondsExactlyOneProbeGoesThrough() {
        fail(5);
        now = now.plusSeconds(60);
        Permit probe = acquire();
        assertThat(probe.probe()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isEmpty();
        assertThat(breaker.tryAcquire()).isEmpty();
    }

    @Test
    void aSuccessfulProbeClosesIt() {
        fail(5);
        now = now.plusSeconds(61);
        breaker.recordSuccess(acquire());
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isPresent();
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void aFailedProbeOpensItAgainForAFullPeriod() {
        fail(5);
        now = now.plusSeconds(61);
        breaker.recordFailure(acquire());
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        now = now.plusSeconds(59);
        assertThat(breaker.tryAcquire()).isEmpty();
        now = now.plusSeconds(2);
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void releasingAProbeWithoutAVerdictFreesTheSlotAndNothingElse() {
        fail(5);
        now = now.plusSeconds(61);
        Permit probe = acquire();
        breaker.release(probe);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void aProbeThatDiesOnAnErrorIsReleasedInAFinallyAndNeverStrandsTheSlot() {
        fail(5);
        now = now.plusSeconds(61);
        Permit probe = acquire();
        try {
            throw new AssertionError("an Error in the middle of the probe");
        } catch (AssertionError e) {
            // The gateway releases in a finally; releasing twice, or after a verdict, is harmless.
        } finally {
            breaker.release(probe);
            breaker.release(probe);
        }
        assertThat(breaker.tryAcquire()).isPresent();
    }

    // ---- a late result from a call that began before the breaker opened ---------------------------------

    @Test
    void aLateSuccessWhileOpenDoesNotCloseIt() {
        Permit early = acquire();
        fail(5);
        breaker.recordSuccess(early);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void aLateSuccessWhileTheProbeRunsDoesNotCloseItNorFreeTheSlot() {
        Permit early = acquire();
        fail(5);
        now = now.plusSeconds(61);
        Permit probe = acquire();
        breaker.recordSuccess(early);
        breaker.release(early);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        // The slot is still the probe's.
        assertThat(breaker.tryAcquire()).isEmpty();
        breaker.recordSuccess(probe);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aLateFailureWhileTheProbeRunsDoesNotReopenIt() {
        Permit early = acquire();
        fail(5);
        now = now.plusSeconds(61);
        Permit probe = acquire();
        breaker.recordFailure(early);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        breaker.recordSuccess(probe);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aLateFailureAfterTheBreakerClosedAgainIsNotCounted() {
        Permit early = acquire();
        fail(5);
        now = now.plusSeconds(61);
        breaker.recordSuccess(acquire());
        // Four late failures from the old generation, then one real one: still closed, count is 1.
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure(early);
        }
        breaker.recordFailure(acquire());
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    // ---- a probe has its own ticket: an old probe cannot touch a new probe's slot -----------------------

    private Permit openThenFirstProbe() {
        fail(5);
        now = now.plusSeconds(61);
        return acquire();
    }

    @Test
    void aLateSuccessOfAReleasedProbeCannotCloseTheBreakerWhileTheNewProbeRuns() {
        Permit first = openThenFirstProbe();
        breaker.release(first);
        Permit second = acquire();
        assertThat(second.ticket()).isNotEqualTo(first.ticket());
        // The first probe, long gone, reports late: nothing happens, and the second still holds the slot.
        breaker.recordSuccess(first);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isEmpty();
        breaker.recordSuccess(second);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aLateFailureOfAReleasedProbeCannotReopenTheBreakerWhileTheNewProbeRuns() {
        Permit first = openThenFirstProbe();
        breaker.release(first);
        Permit second = acquire();
        breaker.recordFailure(first);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        breaker.recordSuccess(second);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aSecondReleaseOfAnOldProbeDoesNotFreeTheNewProbesSlot() {
        Permit first = openThenFirstProbe();
        breaker.release(first);
        Permit second = acquire();
        breaker.release(first);
        breaker.release(first);
        assertThat(breaker.tryAcquire())
                .as("the second probe still holds the slot")
                .isEmpty();
        breaker.release(second);
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void aSettledProbeCannotSettleAgain() {
        Permit probe = openThenFirstProbe();
        breaker.recordFailure(probe);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        now = now.plusSeconds(61);
        Permit next = acquire();
        // The first probe's success, delivered a second time and late, does not close the breaker under the next one.
        breaker.recordSuccess(probe);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        breaker.recordSuccess(next);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }
}
