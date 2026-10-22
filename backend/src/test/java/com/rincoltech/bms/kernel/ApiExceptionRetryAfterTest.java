package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * ADR-059 (review of #433, finding 7): a refusal that knows how long to wait sends it as Retry-After, in whole seconds
 * rounded up; one that does not keeps the short default on 429 and 503.
 */
class ApiExceptionRetryAfterTest {

    final ApiExceptionHandler handler = new ApiExceptionHandler();

    static ApiException limited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests", "Wait.");
    }

    @Test
    void theRefillTimeIsSentRoundedUpToWholeSeconds() {
        ResponseEntity<ProblemDetail> response =
                handler.handleApi(limited().withRetryAfter(Duration.ofMillis(199_001)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("200");
        assertThat(response.getBody().getProperties()).doesNotContainKey("retry_after");
    }

    @Test
    void aWaitBelowOneSecondIsSentAsOne() {
        assertThat(ApiException.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
        assertThat(ApiException.retryAfterSeconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(ApiException.retryAfterSeconds(Duration.ofMillis(1_000))).isEqualTo(1);
        assertThat(ApiException.retryAfterSeconds(Duration.ofMillis(1_001))).isEqualTo(2);
    }

    @Test
    void aBusyAnswerCarriesItsOwnPause() {
        ApiException busy = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sign_in_busy", "Sign-in busy", "Wait.")
                .withRetryAfter(Duration.ofSeconds(4));
        assertThat(handler.handleApi(busy).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("4");
        // The copy keeps everything else.
        assertThat(busy.code()).isEqualTo("sign_in_busy");
        assertThat(busy.getMessage()).isEqualTo("Wait.");
    }

    @Test
    void withoutAKnownWaitTheDefaultStays() {
        assertThat(handler.handleApi(limited()).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("2");
        assertThat(handler.handleApi(ApiException.notFound()).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isNull();
    }
}
