package com.rincoltech.bms.core.ai;

import java.time.Duration;
import java.util.Optional;

/**
 * Builds the gateway's OpenAI-compatible adapter for a caller with its own provider list (Ask BMS, ADR-096), so there is
 * one HTTP client for every provider: the same retries, budget, no redirects and no provider text kept. The checks are
 * the gateway's (ADR-037): an https base URL with a host and no credentials (a loopback http URL only in the dev and test
 * profiles) and a key with no whitespace or control characters. Which providers a caller may use is the caller's rule.
 */
public interface LlmClientFactory {

    /**
     * @return the adapter, or empty when the base URL, key or model is blank or of the wrong shape (nothing is logged
     *     but the fact; never the key or the URL)
     */
    default Optional<LlmClient> openAiCompatible(String baseUrl, String apiKey, String model, Duration budget) {
        return openAiCompatible(baseUrl, apiKey, model, budget, null);
    }

    /**
     * The same adapter, sending {@code reasoning_effort} on every request when {@code reasoningEffort} is not blank (Ask
     * BMS's Gemini, #601); the caller decides which values its provider accepts.
     */
    Optional<LlmClient> openAiCompatible(
            String baseUrl, String apiKey, String model, Duration budget, String reasoningEffort);
}
