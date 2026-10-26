package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmClientFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@link LlmClientFactory} bean (ADR-096): it builds the one {@link OpenAiCompatibleClient} for a caller with its own
 * providers, and nothing else, so the endpoint it calls is the base URL it was given plus {@code /chat/completions}. The
 * gateway's own provider (ADR-037) is built through {@link #build} too, with the same checks. Ask BMS's never-Claude guard
 * reads this file and the client's.
 */
final class OpenAiCompatibleFactory implements LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleFactory.class);

    private final boolean devOrTest;
    private final ObjectMapper mapper;

    OpenAiCompatibleFactory(boolean devOrTest, ObjectMapper mapper) {
        this.devOrTest = devOrTest;
        this.mapper = mapper;
    }

    @Override
    public Optional<LlmClient> openAiCompatible(
            String baseUrl, String apiKey, String model, Duration budget, String reasoningEffort) {
        return blank(baseUrl) || blank(apiKey) || blank(model)
                ? Optional.empty()
                : build(baseUrl, apiKey, model, budget, reasoningEffort, devOrTest, mapper);
    }

    static Optional<LlmClient> build(
            String baseUrl,
            String apiKey,
            String model,
            Duration budget,
            String reasoningEffort,
            boolean devOrTest,
            ObjectMapper mapper) {
        if (!AiConfiguration.wellFormedKey(apiKey)) {
            log.warn("AI provider off: the API key has whitespace or control characters (value not logged)");
            return Optional.empty();
        }
        if (!AiConfiguration.acceptableBaseUrl(baseUrl, devOrTest)) {
            log.warn("AI provider off: the base URL must be https with a host and no credentials (value not logged)");
            return Optional.empty();
        }
        return Optional.of(new OpenAiCompatibleClient(
                OpenAiCompatibleClient.endpointOf(baseUrl),
                apiKey,
                model.strip(),
                blank(reasoningEffort) ? null : reasoningEffort.strip(),
                budget,
                mapper,
                System::nanoTime,
                pause -> Thread.sleep(pause),
                () -> ThreadLocalRandom.current().nextDouble()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
