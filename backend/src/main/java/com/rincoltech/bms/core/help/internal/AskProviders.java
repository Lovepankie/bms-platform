package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmClientFactory;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider chain of Ask BMS, cheapest first (ADR-096, the owner's rule on #20): Gemini Flash or Flash-Lite on its free
 * tier, then a small Groq model on its free tier, then DeepSeek's cheapest chat model, paid, as the last resort. Claude is
 * never used: only the three names above exist, each with the one host it may call ({@link #ALLOWED}, a constant, so no
 * property or environment value can change it), a base URL off that host stops startup, and anything that names Anthropic
 * or Claude (a base URL, a model) stops it too. DeepSeek is skipped while either of its prices is 0, since a paid provider
 * with no price has no spend cap. {@code NeverClaudeTest} proves all of it, that nothing Ask BMS reaches names an Anthropic
 * SDK, endpoint or model id, and that this module uses nothing of {@code core.ai} but its neutral port.
 */
class AskProviders {

    private static final Logger log = LoggerFactory.getLogger(AskProviders.class);

    /** The only providers, in the order they are tried. */
    static final List<String> ORDER = List.of("gemini", "groq", "deepseek");

    /** The allow-list: the one host each provider may be called on. A constant on purpose (ADR-096 decision 4). */
    static final Map<String, String> ALLOWED = Map.of(
            "gemini", "generativelanguage.googleapis.com",
            "groq", "api.groq.com",
            "deepseek", "api.deepseek.com");

    private static final Pattern NEVER = Pattern.compile("anthropic|claude", Pattern.CASE_INSENSITIVE);

    /** One configured provider: its name, the client, and its prices. */
    record Provider(String name, LlmClient client, AskProperties.Provider config) {}

    private final List<Provider> chain;

    AskProviders(List<Provider> chain) {
        int last = -1;
        for (Provider p : chain) {
            int at = ORDER.indexOf(p.name());
            if (at <= last) {
                throw new IllegalStateException(
                        "Ask BMS providers are gemini, groq and deepseek, once each, in that order");
            }
            last = at;
            refuseClaude("the model of " + p.name(), p.client().model());
        }
        this.chain = List.copyOf(chain);
    }

    /** The configured providers in order; empty when no key is set, so every question is answered with the articles. */
    List<Provider> chain() {
        return chain;
    }

    /** The names and models, for the cache key: a provider or model change is a new key. */
    String version() {
        return chain().stream().map(p -> p.name() + "/" + p.client().model()).collect(Collectors.joining(","));
    }

    /**
     * Builds the chain from the configuration. A provider with no key, base URL or model is skipped quietly (that is how
     * the operator leaves one out), and DeepSeek without both prices with a warning; anything naming Anthropic or Claude,
     * or a base URL off the provider's allowed host, stops startup.
     */
    static AskProviders from(AskProperties properties, LlmClientFactory factory) {
        return from(
                properties,
                factory,
                Duration.ofSeconds(properties.budgetSeconds()),
                name -> properties.provider(name).model(),
                "Ask BMS");
    }

    /**
     * The same chain, keys and checks for another caller of the free tier (the setup helper, ADR-102) with its own budget
     * and its own model per provider: {@code modelOf} gives the model, and a blank one leaves the provider out (the
     * photo chain, since DeepSeek reads no pictures).
     */
    static AskProviders from(
            AskProperties properties,
            LlmClientFactory factory,
            Duration budget,
            java.util.function.Function<String, String> modelOf,
            String label) {
        List<Provider> chain = new ArrayList<>();
        for (String name : ORDER) {
            AskProperties.Provider config = properties.provider(name);
            String model = modelOf.apply(name);
            refuseClaude("BMS_HELP_ASK_" + name.toUpperCase(Locale.ROOT) + "_BASE_URL", config.baseUrl());
            refuseClaude("BMS_HELP_ASK_" + name.toUpperCase(Locale.ROOT) + "_MODEL", config.model());
            refuseClaude("the " + label + " model of " + name, model);
            if (blank(config.apiKey()) || blank(config.baseUrl()) || blank(model)) {
                continue;
            }
            String host = ALLOWED.get(name);
            if (!host.equals(hostOf(config.baseUrl()))) {
                throw new IllegalStateException(
                        "BMS_HELP_ASK_" + name.toUpperCase(Locale.ROOT) + "_BASE_URL must be on " + host);
            }
            if ("deepseek".equals(name)
                    && (config.priceInputMicroPerMillion() == 0 || config.priceOutputMicroPerMillion() == 0)) {
                log.warn("Ask BMS provider deepseek has a key but no price, so no spend cap could hold: skipped");
                continue;
            }
            // Only Gemini gets a reasoning effort (#601); Groq and DeepSeek requests stay as they were.
            String reasoning = "gemini".equals(name) ? config.reasoning() : null;
            Optional<LlmClient> client =
                    factory.openAiCompatible(config.baseUrl(), config.apiKey(), model.strip(), budget, reasoning);
            client.ifPresent(c -> chain.add(new Provider(name, c, config)));
        }
        log.info(
                "{} providers: {}",
                label,
                chain.isEmpty()
                        ? "Ask BMS".equals(label)
                                ? "none, the articles are shown"
                                : "none, the list is typed or imported"
                        : chain.stream().map(Provider::name).toList());
        return new AskProviders(chain);
    }

    private static void refuseClaude(String what, String value) {
        if (value != null && NEVER.matcher(value).find()) {
            throw new IllegalStateException(what + " names Anthropic or Claude, which Ask BMS never uses (ADR-096)");
        }
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl.strip()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
