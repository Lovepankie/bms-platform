package com.rincoltech.bms.core.ai.internal;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The language model gateway's configuration (ADR-037, chapter 9 section 9.8). Every value comes
 * from the environment and nothing has a default that reaches a provider: with {@code provider}
 * unset or {@code off}, or an {@code openai_compatible} provider missing its base URL, key or model,
 * the gateway answers every call with a fallback and nothing fails. Prices are operations
 * configuration, never in the repository. The key is never printed.
 *
 * @param provider {@code off} (default), {@code openai_compatible} or {@code recording} (the stub,
 *     refused outside the dev and test profiles)
 * @param baseUrl for example {@code https://api.deepseek.com}; the adapter appends
 *     {@code /chat/completions}
 * @param apiKey {@code BMS_AI_API_KEY}
 * @param model the model name, for example the provider's cheapest chat model; swapped by changing this
 * @param budgetSeconds the total time one call may take including its retries, at most 30
 * @param maxTokensPerCall the most output tokens one call may ask for (the request's cap is clamped to it)
 * @param maxInputTokensPerCall the most input tokens (estimated) one call may carry
 * @param platformDailyTokens {@code BMS_AI_PLATFORM_DAILY_TOKENS}: all tenants together, per business day
 * @param platformDailyCostMicro the same for estimated cost, in micro-units of {@code priceCurrency}
 * @param tenantDailyTokens the default tenant daily cap, until the tenant's settings name one
 * @param tenantDailyCostMicro the default tenant daily cost cap
 * @param priceInputMicroPerMillion micro-units of {@code priceCurrency} per million input tokens
 * @param priceOutputMicroPerMillion the same for output tokens
 * @param enabledTenants {@code BMS_AI_ENABLED_TENANTS}: comma separated tenant ids (UUIDs, never slugs) allowed to
 *     switch the assistant on (the operator fills it once the provider's written terms are on file)
 */
@ConfigurationProperties("bms.ai")
record AiProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer budgetSeconds,
        Integer maxTokensPerCall,
        Integer maxInputTokensPerCall,
        Long platformDailyTokens,
        Long platformDailyCostMicro,
        Long tenantDailyTokens,
        Long tenantDailyCostMicro,
        Long priceInputMicroPerMillion,
        Long priceOutputMicroPerMillion,
        String priceCurrency,
        String enabledTenants) {

    static final String OFF = "off";
    static final String OPENAI_COMPATIBLE = "openai_compatible";
    static final String RECORDING = "recording";
    static final int MAX_BUDGET_SECONDS = 30;

    AiProperties {
        provider =
                provider == null || provider.isBlank() ? OFF : provider.strip().toLowerCase(Locale.ROOT);
        // An unset value takes the default; a value that cannot be meant (zero or less for a per call limit or the
        // budget, below zero for a daily cap) fails startup, because a typo must not turn a limit into the default.
        // A daily cap of exactly zero is valid and means stop: every call is refused.
        budgetSeconds = budgetSeconds == null
                ? MAX_BUDGET_SECONDS
                : Math.min(positive("BMS_AI_BUDGET_SECONDS", budgetSeconds), MAX_BUDGET_SECONDS);
        maxTokensPerCall = maxTokensPerCall == null ? 800 : positive("BMS_AI_MAX_TOKENS_PER_CALL", maxTokensPerCall);
        maxInputTokensPerCall = maxInputTokensPerCall == null
                ? 8000
                : positive("BMS_AI_MAX_INPUT_TOKENS_PER_CALL", maxInputTokensPerCall);
        platformDailyTokens = platformDailyTokens == null
                ? 200_000L
                : notNegative("BMS_AI_PLATFORM_DAILY_TOKENS", platformDailyTokens);
        platformDailyCostMicro = platformDailyCostMicro == null
                ? 2_000_000L
                : notNegative("BMS_AI_PLATFORM_DAILY_COST_MICRO", platformDailyCostMicro);
        tenantDailyTokens =
                tenantDailyTokens == null ? 20_000L : notNegative("BMS_AI_TENANT_DAILY_TOKENS", tenantDailyTokens);
        tenantDailyCostMicro = tenantDailyCostMicro == null
                ? 500_000L
                : notNegative("BMS_AI_TENANT_DAILY_COST_MICRO", tenantDailyCostMicro);
        // Unset is zero (cost is then not estimated and only the token caps bound the spend, said so in the runbook);
        // a negative price, or a currency that is not three letters, is a typo and fails startup.
        priceInputMicroPerMillion = priceInputMicroPerMillion == null
                ? 0L
                : notNegative("BMS_AI_PRICE_INPUT_MICRO_PER_MILLION", priceInputMicroPerMillion);
        priceOutputMicroPerMillion = priceOutputMicroPerMillion == null
                ? 0L
                : notNegative("BMS_AI_PRICE_OUTPUT_MICRO_PER_MILLION", priceOutputMicroPerMillion);
        if (priceCurrency == null || priceCurrency.isBlank()) {
            priceCurrency = "USD";
        } else if (!priceCurrency.strip().matches("^[A-Za-z]{3}$")) {
            throw new IllegalArgumentException("BMS_AI_PRICE_CURRENCY must be three letters");
        } else {
            priceCurrency = priceCurrency.strip().toUpperCase(Locale.ROOT);
        }
        enabledTenants = enabledTenants == null ? "" : enabledTenants;
    }

    private static int positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be 1 or more");
        }
        return value;
    }

    private static long notNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be 0 (stop) or more");
        }
        return value;
    }

    /**
     * The tenant ids on the platform allow-list. Ids only, never slugs: a slug is a name an administrator
     * can choose and an id is not. An entry that is not a UUID is ignored (it names nobody), so a typo
     * can only make the list shorter.
     */
    Set<UUID> allowedTenants() {
        return Arrays.stream(enabledTenants.split(","))
                .map(String::strip)
                .filter(e -> e.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Estimated cost in micro-units, rounded up, from the operations prices. Zero while the prices are
     * unset, which is why the token caps, not the cost caps, are the first line of defence.
     */
    long costMicro(long inputTokens, long outputTokens) {
        BigInteger micro = BigInteger.valueOf(inputTokens)
                .multiply(BigInteger.valueOf(priceInputMicroPerMillion))
                .add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(priceOutputMicroPerMillion)));
        BigInteger million = BigInteger.valueOf(1_000_000L);
        return micro.add(million.subtract(BigInteger.ONE)).divide(million).longValueExact();
    }

    @Override
    public String toString() {
        return "AiProperties[provider=" + provider + ", model=" + model + ", keySet="
                + (apiKey != null && !apiKey.isBlank()) + "]";
    }
}
