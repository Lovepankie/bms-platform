package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the SMS channel (ADR-036). The settings bean is where the startup guard runs: a
 * configuration that could text a real phone from the wrong place (spec section 4.4) stops the
 * application from starting, in every mode. The provider follows the mode: {@code off} has none,
 * {@code sink} the non-sending sink, {@code allowlist} the real provider for the listed numbers and
 * the sink for the rest, {@code live} the real provider. A real provider with unset keys is no
 * provider: the channel is off and its rows stay pending, as email is without its SMTP values.
 */
@Configuration(proxyBeanMethods = false)
class SmsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SmsConfiguration.class);

    @Bean
    SmsSettings smsSettings(SmsProperties properties, @Value("${bms.environment:}") String environment) {
        SmsSettings settings = SmsSettings.resolve(environment, properties);
        log.info(
                "SMS channel: environment={} mode={} provider={} credentials={}",
                settings.environment().isBlank() ? "unset" : settings.environment(),
                settings.mode().key(),
                settings.provider().name().toLowerCase(java.util.Locale.ROOT),
                settings.providerConfigured() ? "set" : "unset");
        return settings;
    }

    /** The one provider of this run, shared by the channel and the test message (which never sends to a real number). */
    record SmsProviderRef(SmsProvider provider) {}

    @Bean
    SmsProviderRef smsProviderRef(SmsSettings settings, ObjectMapper mapper) {
        return new SmsProviderRef(providerFor(settings, mapper));
    }

    @Bean
    OutboxChannel smsOutboxChannel(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            SmsSettings settings,
            OutboxTemplates templates,
            BusinessClock clock,
            SmsTenantConfig tenantConfig,
            TenantJobs tenantJobs,
            SmsRecipientHash hasher,
            Outbox outbox,
            SmsProviderRef providerRef) {
        SmsProvider provider = providerRef.provider();
        if (provider == null && settings.mode() != SmsSettings.Mode.OFF && settings.mode() != SmsSettings.Mode.SINK) {
            log.warn(
                    "SMS mode {} but no provider is configured: the SMS sender stays off and rows stay pending",
                    settings.mode().key());
        }
        return new SmsOutboxChannel(
                jdbc,
                transactionManager,
                settings,
                provider,
                templates,
                clock,
                tenantConfig,
                tenantJobs,
                hasher,
                outbox);
    }

    /** The provider for a resolved configuration, or null when the channel cannot send. */
    static SmsProvider providerFor(SmsSettings settings, ObjectMapper mapper) {
        return switch (settings.mode()) {
            case OFF -> null;
            case SINK -> new SinkSmsProvider();
            case ALLOWLIST, LIVE -> {
                SmsProvider real = real(settings, mapper);
                yield real == null || settings.mode() == SmsSettings.Mode.LIVE
                        ? real
                        : new AllowlistSmsProvider(real, new SinkSmsProvider(), settings.allowlist());
            }
        };
    }

    private static SmsProvider real(SmsSettings settings, ObjectMapper mapper) {
        return switch (settings.provider()) {
            case NONE -> null;
            case FAKE -> new RecordingSmsProvider();
            case AFRICASTALKING ->
                settings.providerConfigured()
                        ? new AfricasTalkingSmsProvider(
                                settings.baseUrl(), settings.username(), settings.apiKey(), mapper)
                        : null;
        };
    }
}
