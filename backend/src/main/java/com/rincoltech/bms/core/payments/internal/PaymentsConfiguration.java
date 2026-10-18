package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.FakePaymentGateway;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.GatewayException.Kind;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the payment gateway (ADR-035). The settings bean is where the startup guard runs: a configuration that could
 * reach the live provider from outside production, or the fake from production, stops the application from starting, in
 * every mode ({@link PesapalSettings#resolve}). With no provider configured the gateway is off: every call answers
 * "unavailable", no intent can be made, and nothing fails at start, as the SMTP and Telegram senders are without their
 * values.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PesapalProperties.class, PaymentsProperties.class})
class PaymentsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentsConfiguration.class);

    static final String PAYER_RETURN_PATH = "/api/v1/payments/return/pesapal";
    static final String IPN_PATH = "/api/v1/payments/callbacks/pesapal/";

    @Bean
    PesapalSettings pesapalSettings(PesapalProperties properties, @Value("${bms.environment:}") String environment) {
        PesapalSettings settings = PesapalSettings.resolve(environment, properties);
        log.info(
                "Payment gateway: environment={} provider={} tenant-credentials={} platform-credentials={} bound-tenant={}",
                settings.environment().isBlank() ? "unset" : settings.environment(),
                settings.provider().name().toLowerCase(java.util.Locale.ROOT),
                settings.tenantPair().complete() ? "set" : "unset",
                settings.platformPair().complete() ? "set" : "unset",
                settings.boundTenant() == null ? "unset" : "set");
        return settings;
    }

    /** One breaker for the process: five failures in a row open it for 60 seconds (chapter 12 section 12.1). */
    @Bean
    CircuitBreaker paymentsCircuitBreaker(BusinessClock clock) {
        return new CircuitBreaker(5, Duration.ofSeconds(60), clock::now);
    }

    @Bean
    Pauser paymentsPauser() {
        return Thread::sleep;
    }

    @Bean
    PaymentGateway paymentGateway(
            PesapalSettings settings, ObjectMapper mapper, BusinessClock clock, CircuitBreaker breaker, Pauser pauser) {
        return switch (settings.provider()) {
            case OFF -> new UnavailableGateway();
            case FAKE -> new FakePaymentGateway();
            case PESAPAL ->
                new PesapalGateway(
                        settings.baseUrl(),
                        settings.tenantPair(),
                        settings.platformPair(),
                        settings.callbackBase() == null ? null : settings.callbackBase() + PAYER_RETURN_PATH,
                        mapper,
                        clock::now,
                        breaker,
                        pauser::pause);
        };
    }

    /** No provider: every call answers unavailable and nothing is sent anywhere. */
    static final class UnavailableGateway implements PaymentGateway {

        @Override
        public String providerKey() {
            return "pesapal";
        }

        @Override
        public boolean available(Scope scope) {
            return false;
        }

        @Override
        public OrderResult createOrder(Scope scope, OrderRequest request) throws GatewayException {
            throw unavailable();
        }

        @Override
        public StatusResult queryStatus(Scope scope, String providerReference) throws GatewayException {
            throw unavailable();
        }

        @Override
        public void cancelOrder(Scope scope, String providerReference) throws GatewayException {
            throw unavailable();
        }

        @Override
        public IpnRegistration registerIpn(Scope scope, String url, IpnType type) throws GatewayException {
            throw unavailable();
        }

        private static GatewayException unavailable() {
            return new GatewayException(Kind.UNAVAILABLE, 0, "not_configured");
        }
    }
}
