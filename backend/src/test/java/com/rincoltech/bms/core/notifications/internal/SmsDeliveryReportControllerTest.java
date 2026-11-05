package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The follow-ups of issue #808 on the delivery report path, without a database: past the rate limit only the first few
 * throttled requests of a window are counted, so a flood costs no database writes; and a body of unknown length (chunked)
 * is refused before it is read. The token and the message id are fabricated.
 */
class SmsDeliveryReportControllerTest {

    static final String TOKEN = "test-only-fabricated-sms-callback-token-0123456789";

    final SmsDeliveryReports reports = mock(SmsDeliveryReports.class);
    final BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2026-11-05T10:00:10Z"), ZoneOffset.UTC));

    /** Ten reports a minute per address: the counted throttles are a tenth of that, one. */
    final SmsDeliveryReportController controller = new SmsDeliveryReportController(reports, clock, TOKEN, 10, 1000);

    static MockHttpServletRequest report() {
        MockHttpServletRequest r =
                new MockHttpServletRequest("POST", "/api/v1/channels/sms/africastalking/delivery-reports/x");
        String body = "id=ATXid_test0001&status=Success";
        r.setContentType("application/x-www-form-urlencoded");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        r.addParameter("id", "ATXid_test0001");
        r.addParameter("status", "Success");
        return r;
    }

    static int status(org.springframework.http.ResponseEntity<Void> answer) {
        return answer.getStatusCode().value();
    }

    @Test
    void pastTheLimitOnlyTheFirstThrottlesOfTheWindowAreCounted() {
        when(reports.apply(anyString(), any())).thenReturn("moved");
        for (int i = 0; i < 10; i++) {
            assertThat(status(controller.africasTalking(TOKEN, report()))).isEqualTo(200);
        }
        for (int i = 0; i < 25; i++) {
            assertThat(status(controller.africasTalking(TOKEN, report()))).isEqualTo(429);
        }
        verify(reports, times(10)).apply(anyString(), any());
        verify(reports, times(1)).count("throttled");
    }

    @Test
    void aBodyOfUnknownLengthIsRefusedBeforeItIsRead() {
        MockHttpServletRequest chunked = report();
        chunked.setContent(null);
        assertThat(chunked.getContentLengthLong()).isEqualTo(-1);

        assertThatThrownBy(() -> controller.africasTalking(TOKEN, chunked))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.LENGTH_REQUIRED));
        verify(reports).count("no_length");
        verify(reports, never()).apply(anyString(), any());
    }
}
