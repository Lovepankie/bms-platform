package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.ai.LlmClientFactory;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the provider chain of Ask BMS (ADR-096) and the setup helper's two chains (ADR-102) from their configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AskProperties.class, SetupProperties.class})
class AskConfiguration {

    @Bean
    AskProviders askProviders(AskProperties properties, LlmClientFactory factory) {
        return AskProviders.from(properties, factory);
    }

    @Bean
    SetupProviders setupProviders(AskProperties ask, SetupProperties setup, LlmClientFactory factory) {
        Duration budget = Duration.ofSeconds(setup.budgetSeconds());
        AskProviders text = AskProviders.from(
                ask, factory, budget, name -> ask.provider(name).model(), "Setup helper");
        AskProviders photos = AskProviders.from(
                ask,
                factory,
                budget,
                name -> switch (name) {
                    case "gemini" ->
                        setup.geminiPhotoModel().isEmpty() ? ask.gemini().model() : setup.geminiPhotoModel();
                    case "groq" -> setup.groqPhotoModel();
                    default -> null;
                },
                "Setup helper photos");
        return new SetupProviders(text, photos);
    }
}
