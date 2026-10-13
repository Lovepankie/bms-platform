package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** Review F2: a deadlock or serialisation failure is a 409 the client may retry, not a 500. */
class ApiExceptionHandlerTest {

    final ApiExceptionHandler handler = new ApiExceptionHandler();

    @ParameterizedTest
    @ValueSource(strings = {"40P01", "40001"})
    void aDeadlockOrSerialisationFailureAsksTheClientToRetry(String state) {
        Exception e =
                new PessimisticLockingFailureException("aborted", new SQLException("ERROR: deadlock detected", state));
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(e);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "transaction_conflict");
        assertThat(response.getBody().getDetail()).contains("again").doesNotContain("deadlock");
    }

    @Test
    void aBusyOrRateLimitedAnswerSaysWhenToComeBack() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS}) {
            ResponseEntity<ProblemDetail> response =
                    handler.handleApi(new ApiException(status, "busy", "Busy", "Later."));
            assertThat(response.getStatusCode()).isEqualTo(status);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        }
        assertThat(handler.handleApi(ApiException.notFound()).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isNull();
    }

    @Test
    void anyOtherFailureStaysAnInternalError() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalStateException("x", new SQLException("boom", "XX000")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
