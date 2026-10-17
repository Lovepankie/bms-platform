package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order of the checks on the provider's notification path (spec 3.6): the path token is checked in memory before any
 * rate limit slot is taken, so garbage tokens, which anyone can send, can never use up the slots the provider's real
 * notifications need.
 */
class CallbackControllerTest {

    static final String ID = "aaaaaaaa-0000-4000-8000-0000000000d1";
    static final String REF = "BMS-ST-000000000000000000000000000000d1";

    final CallbackService service = mock(CallbackService.class);
    final PaymentCounters counters = mock(PaymentCounters.class);
    final BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2026-10-08T10:00:10Z"), ZoneOffset.UTC));
    final PaymentsProperties properties = new PaymentsProperties(true, 5, 40, 2, 1000, 4096);
    final CallbackLimiter limiter = new CallbackLimiter(properties, clock);
    final PesapalSettings settings = PesapalSettings.resolve("staging", PesapalSettingsTest.props("pesapal", null));
    final CallbackController controller = new CallbackController(
            settings,
            service,
            limiter,
            properties,
            counters,
            JsonMapper.builder().build());

    static MockHttpServletRequest ipn() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/payments/callbacks/pesapal/x");
        r.setQueryString(
                "OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF + "&OrderNotificationType=IPNCHANGE");
        return r;
    }

    @Test
    void aFloodOfWrongTokensFromOneAddressIsAnswered404ThenItsOwnBucketRunsOutAndTheProvidersIpnIsUntouched() {
        // The same address (the test thread has none, so all share one key) sends far more than the per address limit.
        int notFound = 0;
        int tooMany = 0;
        for (int i = 0; i < 20; i++) {
            try {
                ResponseEntity<Map<String, Object>> r = controller.ipn("wrong-token-number-" + i, ipn());
                assertThat(r.getStatusCode().value()).isEqualTo(429);
                tooMany++;
            } catch (ApiException e) {
                assertThat(e.status().value()).isEqualTo(404);
                notFound++;
            }
        }
        assertThat(notFound).isEqualTo(2);
        assertThat(tooMany).isEqualTo(18);
        // A real notification from the same address still gets through: it has its own bucket.
        ResponseEntity<Map<String, Object>> real = controller.ipn(PesapalSettingsTest.TOKEN, ipn());
        assertThat(real.getStatusCode().value()).isEqualTo(200);
        assertThat(real.getBody()).containsEntry("status", 200).containsEntry("orderMerchantReference", REF);
        verify(service).receive(Scope.TENANT, "ipn", "IPNCHANGE", ID, REF);
    }

    @Test
    void aWrongTokenNeverReachesTheServiceOrTheDatabaseWork() {
        assertThatThrownBy(() -> controller.ipn("nope", ipn())).isInstanceOf(ApiException.class);
        verify(service, never())
                .receive(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theProvidersOwnBucketStillLimitsAFloodWithTheRightToken() {
        for (int i = 0; i < 2; i++) {
            assertThat(controller
                            .ipn(PesapalSettingsTest.TOKEN, ipn())
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
        }
        assertThat(controller
                        .ipn(PesapalSettingsTest.TOKEN, ipn())
                        .getStatusCode()
                        .value())
                .isEqualTo(429);
    }
}
