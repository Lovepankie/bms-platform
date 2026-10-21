package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A failure inside "Find your sign-in page" (the database or the outbox) still gets the usual 202 and words, and is audited:
 * a 500 that only matching addresses reach would tell the asker who works where (issue #383).
 */
class FindSignInControllerTest {

    final FindSignInService service = mock(FindSignInService.class);
    final BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));
    final FindSignInController controller = new FindSignInController(service, new SignUpRateLimiter(clock), 0);

    @Test
    void aFailureInsideGetsTheSameAnswerAndIsAudited() {
        doThrow(new DataAccessResourceFailureException("Test database down"))
                .when(service)
                .request("staff@example.test");

        ResponseEntity<FindSignInController.Accepted> answer =
                controller.find(new FindSignInController.FindSignInRequest("staff@example.test"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).isEqualTo(new FindSignInController.Accepted(FindSignInController.ANSWER));
        verify(service).recordFailure("staff@example.test");
    }
}
