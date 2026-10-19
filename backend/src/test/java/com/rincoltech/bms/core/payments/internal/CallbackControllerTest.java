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
    static final java.util.UUID LENDER = java.util.UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    final AccountTokens tokens = mock(AccountTokens.class);
    final PesapalSettings settings = PesapalSettings.resolve("staging", PesapalSettingsTest.props("pesapal", null));
    final CallbackController controller = new CallbackController(
            settings,
            tokens,
            service,
            limiter,
            properties,
            counters,
            JsonMapper.builder().build());

    {
        org.mockito.Mockito.when(tokens.resolve(PesapalSettingsTest.TOKEN))
                .thenReturn(java.util.Optional.of(new AccountTokens.Selected(Scope.TENANT, LENDER)));
        org.mockito.Mockito.when(tokens.resolve(PesapalSettingsTest.PLATFORM_TOKEN))
                .thenReturn(java.util.Optional.of(new AccountTokens.Selected(Scope.PLATFORM, null)));
    }

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
        // The platform's own notifications still get through from the same address: its token is checked in memory,
        // with no lookup, and it has its own bucket. A lender's token from an address that has used up its wrong-token
        // quota is refused before any database lookup is made (a flood of random tokens is not a flood of lookups).
        ResponseEntity<Map<String, Object>> real = controller.ipn(PesapalSettingsTest.PLATFORM_TOKEN, ipn());
        assertThat(real.getStatusCode().value()).isEqualTo(200);
        assertThat(real.getBody()).containsEntry("status", 200).containsEntry("orderMerchantReference", REF);
        verify(service).receive(Scope.PLATFORM, null, "ipn", "IPNCHANGE", ID, REF);
        assertThat(controller
                        .ipn(PesapalSettingsTest.TOKEN, ipn())
                        .getStatusCode()
                        .value())
                .isEqualTo(429);
        verify(tokens, org.mockito.Mockito.times(2)).resolve(org.mockito.ArgumentMatchers.startsWith("wrong-token"));
        verify(tokens, never()).resolve(PesapalSettingsTest.TOKEN);
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
