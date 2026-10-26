package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.ai.LlmException.Kind;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One adapter for any OpenAI-compatible chat completions API (the first provider is DeepSeek):
 * {@code POST <base>/chat/completions} with a bearer key. One call has a total budget (30 seconds at
 * most, spec 5.7) that every attempt and every pause must fit in. A 429 or a 5xx is retried up to
 * three attempts in all, after a pause that doubles (and follows a short {@code Retry-After}); a
 * failure to connect is retried the same way; a read timeout, a 4xx other than 429 and an answer that
 * is not a chat completion are not. Redirects are never followed, so the key cannot be forwarded.
 *
 * <p>Nothing the provider says is kept in an exception: the status number and a closed kind only. The
 * request text and the key are never logged, and no JDK exception message (which can quote the URL)
 * is read. The model name is configuration, so a swap is a deployment change only.
 */
final class OpenAiCompatibleClient implements LlmClient {

    static final int MAX_ATTEMPTS = 3;
    static final Duration FIRST_BACKOFF = Duration.ofMillis(500);
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);
    /** No pause is shorter than this, so a {@code Retry-After: 0} cannot turn the retries into a hot loop. */
    static final Duration MIN_PAUSE = Duration.ofMillis(250);
    /** Random extra on every pause, up to a quarter of it, so tenants retrying together do not move in step. */
    static final double JITTER_SHARE = 0.25;

    static final int MAX_BODY_BYTES = 1_048_576;

    /** Pauses between attempts; replaced in tests so no test sleeps. */
    interface Sleeper {
        void sleep(Duration pause) throws InterruptedException;
    }

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    /** Sent as {@code reasoning_effort} when not null (Ask BMS's Gemini, #601); other providers never get it. */
    private final String reasoningEffort;

    private final Duration budget;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;

    OpenAiCompatibleClient(
            URI endpoint,
            String apiKey,
            String model,
            Duration budget,
            ObjectMapper mapper,
            LongSupplier nanoClock,
            Sleeper sleeper,
            DoubleSupplier jitter) {
        this(endpoint, apiKey, model, null, budget, mapper, nanoClock, sleeper, jitter);
    }

    OpenAiCompatibleClient(
            URI endpoint,
            String apiKey,
            String model,
            String reasoningEffort,
            Duration budget,
            ObjectMapper mapper,
            LongSupplier nanoClock,
            Sleeper sleeper,
            DoubleSupplier jitter) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        this.budget = budget;
        this.mapper = mapper;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    static URI endpointOf(String baseUrl) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/chat/completions");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Result complete(Request request) throws LlmException {
        byte[] body = body(request);
        long deadline = nanoClock.getAsLong() + budget.toNanos();
        int attempts = 0;
        while (true) {
            Duration remaining = Duration.ofNanos(deadline - nanoClock.getAsLong());
            if (remaining.isNegative() || remaining.isZero()) {
                throw new LlmException(Kind.TIMEOUT, 0, attempts);
            }
            attempts++;
            Attempt attempt = attempt(request, body, remaining, attempts);
            if (attempt.result() != null) {
                return attempt.result();
            }
            LlmException failure = attempt.failure();
            if (!attempt.retryable() || attempts >= MAX_ATTEMPTS) {
                throw failure;
            }
            Duration pause = pauseBefore(attempts, attempt.retryAfter());
            Duration left = Duration.ofNanos(deadline - nanoClock.getAsLong());
            if (pause.compareTo(left) >= 0) {
                // The pause would use the rest of the budget: give up now with what the provider said.
                throw failure;
            }
            try {
                sleeper.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(Kind.TIMEOUT, 0, attempts);
            }
        }
    }

    /**
     * The pause before the next attempt: the provider's {@code Retry-After} capped at ten seconds, else the doubling
     * backoff; never under the minimum; plus up to a quarter of itself at random.
     */
    Duration pauseBefore(int attemptsMade, Duration retryAfter) {
        Duration base = retryAfter != null
                ? (retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter)
                : FIRST_BACKOFF.multipliedBy(1L << (attemptsMade - 1));
        if (base.compareTo(MIN_PAUSE) < 0) {
            base = MIN_PAUSE;
        }
        double share = Math.clamp(jitter.getAsDouble(), 0.0, 1.0) * JITTER_SHARE;
        return base.plusMillis(Math.round(base.toMillis() * share));
    }

    private record Attempt(Result result, LlmException failure, boolean retryable, Duration retryAfter) {

        static Attempt ok(Result result) {
            return new Attempt(result, null, false, null);
        }

        static Attempt fail(LlmException failure, boolean retryable, Duration retryAfter) {
            return new Attempt(null, failure, retryable, retryAfter);
        }
    }

    private Attempt attempt(Request asked, byte[] body, Duration remaining, int attempts) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response;
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(
                request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES));
        try {
            // The request timeout covers the headers; this covers the body too.
            response = future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Attempt.fail(new LlmException(Kind.TIMEOUT, 0, attempts), false, null);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Attempt.fail(new LlmException(Kind.TIMEOUT, 0, attempts), false, null);
        } catch (ExecutionException e) {
            // The cause's message can quote the URL; only its type is read.
            Throwable cause = e.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return Attempt.fail(new LlmException(Kind.NETWORK, 0, attempts), true, null);
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return Attempt.fail(new LlmException(Kind.TIMEOUT, 0, attempts), false, null);
            }
            return Attempt.fail(new LlmException(Kind.NETWORK, 0, attempts), false, null);
        }
        int status = response.statusCode();
        if (status == 200) {
            try {
                return Attempt.ok(parse(response.body(), asked, attempts));
            } catch (LlmException e) {
                return Attempt.fail(
                        new LlmException(e.kind(), status, attempts, e.reportedInputTokens(), e.reportedOutputTokens()),
                        false,
                        null);
            }
        }
        if (status == 429) {
            return Attempt.fail(new LlmException(Kind.RATE_LIMITED, status, attempts), true, retryAfter(response));
        }
        if (status >= 500) {
            return Attempt.fail(new LlmException(Kind.PROVIDER_ERROR, status, attempts), true, retryAfter(response));
        }
        if (status == 401 || status == 403) {
            return Attempt.fail(new LlmException(Kind.AUTH, status, attempts), false, null);
        }
        if (status >= 400) {
            return Attempt.fail(new LlmException(Kind.BAD_REQUEST, status, attempts), false, null);
        }
        return Attempt.fail(new LlmException(Kind.BAD_RESPONSE, status, attempts), false, null);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(String::strip)
                .filter(v -> v.matches("^\\d{1,4}$"))
                .map(v -> Duration.ofSeconds(Long.parseLong(v)))
                .orElse(null);
    }

    private byte[] body(Request request) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("model", model);
        json.put("messages", messages);
        json.put("max_tokens", request.maxTokens());
        json.put("temperature", 0);
        json.put("stream", false);
        if (reasoningEffort != null) {
            json.put("reasoning_effort", reasoningEffort);
        }
        if (request.json()) {
            json.put("response_format", Map.of("type", "json_object"));
        }
        return mapper.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
    }

    private Result parse(byte[] bytes, Request asked, int attempts) throws LlmException {
        try {
            JsonNode root = mapper.readTree(bytes);
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            JsonNode usage = root.path("usage");
            int in = usage.path("prompt_tokens").asInt(-1);
            int out = usage.path("completion_tokens").asInt(-1);
            if (!content.isString()) {
                // Billed all the same: hand the usage back so the caps count it.
                throw new LlmException(Kind.BAD_RESPONSE, 0, 0, in, out);
            }
            String text = content.asString();
            if (in < 0 || out < 0) {
                // No usage block: count by the same rough size estimate the reservation used, so the caps still bite.
                in = Math.max(1, asked.characters() / 3);
                out = Math.max(1, text.length() / 3);
            }
            String finish = choice.path("finish_reason").asString("");
            return new Result(text, in, out, finish.isEmpty() ? null : finish, attempts);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            // Unparseable JSON: the parser's message can quote the text, so it is not kept.
            throw new LlmException(Kind.BAD_RESPONSE, 0, 0);
        }
    }

    @Override
    public String toString() {
        // The endpoint holds no secret (the key is a header), and shows where a client calls.
        return "OpenAiCompatibleClient[endpoint=" + endpoint + ", model=" + model + "]";
    }
}
