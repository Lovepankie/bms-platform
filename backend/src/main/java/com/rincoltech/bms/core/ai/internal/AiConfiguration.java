package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.LlmException.Kind;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.kernel.BusinessClock;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Chooses the language model client (ADR-037, chapter 9 section 9.8). The default is none: with the
 * provider unset, {@code off}, or {@code openai_compatible} but missing its base URL, key or model
 * (or holding a key or URL of the wrong shape), the client answers "unavailable" and the gateway falls
 * back, so a staging host without a key never calls out and startup never fails on it. The
 * {@code recording} stub exists only in the dev and test profiles; asked for anywhere else, startup
 * fails, because a stub answering for a real tenant would be worse than no answer. A base URL must be
 * https, except a loopback address in the dev and test profiles (the adapter's own tests).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    @Bean
    LlmClient llmClient(AiProperties properties, Environment environment, ObjectMapper mapper) {
        boolean devOrTest = environment.acceptsProfiles(Profiles.of("dev", "test"));
        switch (properties.provider()) {
            case AiProperties.OFF:
                return new UnavailableLlmClient();
            case AiProperties.RECORDING:
                if (!devOrTest) {
                    throw new IllegalStateException(
                            "BMS_AI_PROVIDER=recording is only allowed in the dev and test profiles");
                }
                return new RecordingLlmClient(blank(properties.model()) ? "recording-model" : properties.model());
            case AiProperties.OPENAI_COMPATIBLE:
                return adapter(properties, devOrTest, mapper);
            default:
                throw new IllegalStateException("bms.ai.provider must be off, openai_compatible or recording");
        }
    }

    private static LlmClient adapter(AiProperties p, boolean devOrTest, ObjectMapper mapper) {
        if (blank(p.baseUrl()) || blank(p.apiKey()) || blank(p.model())) {
            log.warn(
                    "AI provider off: BMS_AI_BASE_URL, BMS_AI_API_KEY and BMS_AI_MODEL must all be set (values not logged)");
            return new UnavailableLlmClient();
        }
        if (!wellFormedKey(p.apiKey())) {
            log.warn("AI provider off: the API key has whitespace or control characters (value not logged)");
            return new UnavailableLlmClient();
        }
        if (!acceptableBaseUrl(p.baseUrl(), devOrTest)) {
            log.warn("AI provider off: the base URL must be https with a host and no credentials (value not logged)");
            return new UnavailableLlmClient();
        }
        return new OpenAiCompatibleClient(
                OpenAiCompatibleClient.endpointOf(p.baseUrl()),
                p.apiKey(),
                p.model().strip(),
                Duration.ofSeconds(p.budgetSeconds()),
                mapper,
                System::nanoTime,
                pause -> Thread.sleep(pause),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** One breaker for the process: five failures in a row open it for 60 seconds (spec 5.7). */
    @Bean
    CircuitBreaker aiCircuitBreaker(BusinessClock clock) {
        return new CircuitBreaker(5, Duration.ofSeconds(60), clock::now);
    }

    static boolean wellFormedKey(String key) {
        return key.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    static boolean acceptableBaseUrl(String baseUrl, boolean devOrTest) {
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return devOrTest && "http".equalsIgnoreCase(uri.getScheme()) && loopback(uri.getHost());
    }

    private static boolean loopback(String host) {
        // Literal names only: no DNS lookup decides whether a plain http URL is acceptable.
        return host.equals("localhost") || host.equals("[::1]") || host.matches("^127(\\.\\d{1,3}){3}$");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** No provider: the gateway sees {@code available() == false} and never calls it. */
    static final class UnavailableLlmClient implements LlmClient {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String model() {
            return "none";
        }

        @Override
        public Result complete(Request request) throws LlmException {
            throw new LlmException(Kind.UNAVAILABLE, 0, 0);
        }
    }
}
