package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires Web Push (ADR-050). The VAPID keys are resolved once at start: a half set or mismatched pair stops the application,
 * so a typo is found at deploy time and not at the first notification. With no keys the channel is off and the rest of the
 * module behaves as before. The real sender is the default; a test supplies its own {@link PushSender} as the primary bean so
 * that no test reaches a network.
 */
@Configuration(proxyBeanMethods = false)
class PushConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PushConfiguration.class);

    @Bean
    VapidKeys vapidKeys(PushProperties properties, @Value("${bms.environment:}") String environment) {
        VapidKeys keys = VapidKeys.resolve(properties, environment);
        log.info(
                "Web Push: {}",
                !keys.available() ? "off (no VAPID keys)" : keys.ephemeral() ? "on, ephemeral development keys" : "on");
        return keys;
    }

    @Bean
    PushEndpoints pushEndpoints(PushProperties properties) {
        return new PushEndpoints(properties.extraEndpointHosts());
    }

    @Bean
    PushSender webPushSender(VapidKeys keys, PushEndpoints endpoints, BusinessClock clock) {
        return new WebPushSender(keys, endpoints, clock);
    }

    @Bean
    OutboxChannel pushOutboxChannel(
            VapidKeys keys,
            PushSender sender,
            TenantJobs tenantJobs,
            JdbcClient jdbc,
            ObjectMapper mapper,
            BusinessClock clock) {
        return new PushOutboxChannel(keys, sender, tenantJobs, jdbc, mapper, clock);
    }
}
