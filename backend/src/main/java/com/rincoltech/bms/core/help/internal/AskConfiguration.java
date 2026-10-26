package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.ai.LlmClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the provider chain of Ask BMS from its configuration (ADR-096). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AskProperties.class)
class AskConfiguration {

    @Bean
    AskProviders askProviders(AskProperties properties, LlmClientFactory factory) {
        return AskProviders.from(properties, factory);
    }
}
