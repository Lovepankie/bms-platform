package com.rincoltech.bms.core.help.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ask BMS configuration (ADR-096, {@code docs/runbooks/ask-bms.md}). Keys come from the host environment only and are never
 * printed; nothing in the repository holds one. With no key a provider is simply skipped, and with none at all every
 * question is answered with the matching articles.
 *
 * @param enabledTenants {@code BMS_HELP_ASK_TENANTS}: comma separated tenant ids (never slugs) for which Ask BMS is on;
 *     empty means off everywhere (the default). Demo businesses are named first (owner decision D52).
 * @param dailyQuestions {@code BMS_HELP_ASK_DAILY_QUESTIONS}: the questions one business may send to the providers per day
 *     (0 stops them; the articles are still shown)
 * @param userHourlyQuestions {@code BMS_HELP_ASK_USER_HOURLY_QUESTIONS}: the questions one person may ask in any hour, on
 *     every path (429 {@code ask_rate_limited} past it)
 * @param budgetSeconds the time one provider may take with its retries, at most 15, before the next is tried
 * @param maxTokens the most output tokens one answer may take
 * @param cacheDays how long a cached answer may be served; a safety net, since a new revision is a new key
 * @param deepseekMonthlyCostMicro the estimated DeepSeek spend of every business together per calendar month (UTC) in
 *     micro-units of {@code costCurrency}, after which DeepSeek is skipped; 0 never calls it
 */
@ConfigurationProperties("bms.help.ask")
record AskProperties(
        String enabledTenants,
        Integer dailyQuestions,
        Integer userHourlyQuestions,
        Integer budgetSeconds,
        Integer maxTokens,
        Integer cacheDays,
        String costCurrency,
        Long deepseekMonthlyCostMicro,
        Provider gemini,
        Provider groq,
        Provider deepseek) {

    static final int MAX_BUDGET_SECONDS = 15;

    /**
     * @param apiKey from {@code BMS_HELP_ASK_<NAME>_API_KEY}
     * @param priceInputMicroPerMillion micro-units of the cost currency per million input tokens (0 on a free tier)
     */
    record Provider(
            String baseUrl,
            String apiKey,
            String model,
            Long priceInputMicroPerMillion,
            Long priceOutputMicroPerMillion) {

        Provider {
            priceInputMicroPerMillion = priceInputMicroPerMillion == null ? 0L : notNegative(priceInputMicroPerMillion);
            priceOutputMicroPerMillion =
                    priceOutputMicroPerMillion == null ? 0L : notNegative(priceOutputMicroPerMillion);
        }

        long costMicro(long inputTokens, long outputTokens) {
            long micro = Math.addExact(
                    Math.multiplyExact(inputTokens, priceInputMicroPerMillion),
                    Math.multiplyExact(outputTokens, priceOutputMicroPerMillion));
            return (micro + 999_999L) / 1_000_000L;
        }

        @Override
        public String toString() {
            return "Provider[model=" + model + ", keySet=" + (apiKey != null && !apiKey.isBlank()) + "]";
        }
    }

    AskProperties {
        enabledTenants = enabledTenants == null ? "" : enabledTenants;
        dailyQuestions = dailyQuestions == null ? 100 : (int) notNegative(dailyQuestions);
        userHourlyQuestions = userHourlyQuestions == null ? 30 : (int) notNegative(userHourlyQuestions);
        budgetSeconds = budgetSeconds == null ? 10 : Math.clamp(budgetSeconds, 1, MAX_BUDGET_SECONDS);
        maxTokens = maxTokens == null ? 400 : Math.clamp(maxTokens, 50, 1000);
        cacheDays = cacheDays == null ? 30 : Math.clamp(cacheDays, 1, 365);
        costCurrency = costCurrency == null || costCurrency.isBlank()
                ? "USD"
                : costCurrency.strip().toUpperCase(Locale.ROOT);
        if (!costCurrency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("BMS_HELP_ASK_COST_CURRENCY must be three letters");
        }
        deepseekMonthlyCostMicro =
                deepseekMonthlyCostMicro == null ? 5_000_000L : notNegative(deepseekMonthlyCostMicro);
        gemini = gemini == null ? new Provider(null, null, null, null, null) : gemini;
        groq = groq == null ? new Provider(null, null, null, null, null) : groq;
        deepseek = deepseek == null ? new Provider(null, null, null, null, null) : deepseek;
    }

    private static long notNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("an Ask BMS limit or price must be 0 or more");
        }
        return value;
    }

    /** The tenants Ask BMS is on for. An entry that is not a UUID names nobody, so a typo only makes the list shorter. */
    Set<UUID> tenants() {
        return Arrays.stream(enabledTenants.split(","))
                .map(String::strip)
                .filter(e -> e.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    Provider provider(String name) {
        return switch (name) {
            case "gemini" -> gemini;
            case "groq" -> groq;
            case "deepseek" -> deepseek;
            default -> throw new IllegalArgumentException("unknown provider");
        };
    }
}
