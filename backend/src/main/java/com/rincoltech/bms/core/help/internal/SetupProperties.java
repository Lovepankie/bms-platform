package com.rincoltech.bms.core.help.internal;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The setup helper's configuration (ADR-102, {@code docs/runbooks/ask-bms.md}). The providers, keys and prices are Ask
 * BMS's ({@link AskProperties}); only the switch, the limits, the budget and the photo models are its own.
 *
 * @param enabledTenants {@code BMS_SETUP_AI_TENANTS}: comma separated tenant ids (never slugs) whose lists may be sent to a
 *     provider; empty means off everywhere (the default). The owner names the first businesses (D62)
 * @param dailyDrafts {@code BMS_SETUP_AI_DAILY_DRAFTS}: the drafts one business may send to the providers per day
 * @param userHourlyDrafts {@code BMS_SETUP_AI_USER_HOURLY_DRAFTS}: the drafts one person may ask for in any hour
 * @param budgetSeconds the time one provider may take with its retries, at most 30, before the next is tried
 * @param maxTokens the most output tokens one draft may take
 * @param maxTextChars the longest typed list or file text that is sent
 * @param geminiPhotoModel {@code BMS_SETUP_AI_GEMINI_PHOTO_MODEL}: the Gemini model that reads a photo; blank is Ask BMS's
 *     Gemini model, which reads pictures
 * @param groqPhotoModel {@code BMS_SETUP_AI_GROQ_PHOTO_MODEL}: a Groq vision model; blank leaves Groq out of photos.
 *     DeepSeek reads no pictures, so it is never asked about a photo
 */
@ConfigurationProperties("bms.help.setup")
record SetupProperties(
        String enabledTenants,
        Integer dailyDrafts,
        Integer userHourlyDrafts,
        Integer budgetSeconds,
        Integer maxTokens,
        Integer maxTextChars,
        String geminiPhotoModel,
        String groqPhotoModel) {

    static final int MAX_BUDGET_SECONDS = 30;

    SetupProperties {
        enabledTenants = enabledTenants == null ? "" : enabledTenants;
        dailyDrafts = dailyDrafts == null ? 30 : Math.max(0, dailyDrafts);
        userHourlyDrafts = userHourlyDrafts == null ? 10 : Math.max(0, userHourlyDrafts);
        budgetSeconds = budgetSeconds == null ? 25 : Math.clamp(budgetSeconds, 5, MAX_BUDGET_SECONDS);
        maxTokens = maxTokens == null ? 8000 : Math.clamp(maxTokens, 500, 16_000);
        maxTextChars = maxTextChars == null ? 30_000 : Math.clamp(maxTextChars, 1000, 100_000);
        geminiPhotoModel = geminiPhotoModel == null ? "" : geminiPhotoModel.strip();
        groqPhotoModel = groqPhotoModel == null ? "" : groqPhotoModel.strip();
    }

    /** The businesses the helper is on for. An entry that is not a UUID names nobody. */
    Set<UUID> tenants() {
        return Arrays.stream(enabledTenants.split(","))
                .map(String::strip)
                .filter(e -> e.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }
}
