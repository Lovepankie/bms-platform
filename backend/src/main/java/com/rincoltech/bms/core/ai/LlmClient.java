package com.rincoltech.bms.core.ai;

import java.util.List;

/**
 * The port to a chat-completion model (chapter 12 section 12.4.5). A client sends one request and
 * returns one answer, or throws {@link LlmException}; it knows nothing of tenants, caps, the
 * breaker or the call log, which the {@link AiGateway} owns. The model name is the client's
 * configuration, not part of a request, so swapping it is a configuration change only. No type
 * here prints message text: a request or an answer in a log line shows its size only.
 */
public interface LlmClient {

    /** True when this client can reach a provider: a configured adapter or a recording stub. */
    boolean available();

    /** The model name calls are made with, for the call log. */
    String model();

    /** One request, one answer. Retries on 429 and 5xx and the total budget are the adapter's. */
    Result complete(Request request) throws LlmException;

    /**
     * {@code role} is {@code system} or {@code user}. A user message may carry one picture as a {@code data:} URL of a
     * JPEG, PNG or WebP (the setup helper's photo of a list, ADR-102); never a link, so a provider is never asked to fetch
     * anything.
     */
    record Message(String role, String content, String imageDataUrl) {

        private static final java.util.regex.Pattern DATA_URL =
                java.util.regex.Pattern.compile("^data:image/(?:jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}$");

        public Message(String role, String content) {
            this(role, content, null);
        }

        public Message {
            if (!"system".equals(role) && !"user".equals(role)) {
                throw new IllegalArgumentException("role must be system or user");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content must not be blank");
            }
            if (imageDataUrl != null
                    && (!"user".equals(role) || !DATA_URL.matcher(imageDataUrl).matches())) {
                throw new IllegalArgumentException(
                        "a picture is a base64 data URL of a JPEG, PNG or WebP on a user message");
            }
        }

        @Override
        public String toString() {
            return "Message[role=" + role + ", chars=" + content.length()
                    + (imageDataUrl == null ? "" : ", picture=" + imageDataUrl.length()) + "]";
        }
    }

    /**
     * @param maxTokens the most output tokens the model may produce; always sent (spec 5.7)
     * @param json ask for a JSON object as the answer ({@code response_format})
     */
    record Request(List<Message> messages, int maxTokens, boolean json) {

        public Request {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("a request needs at least one message");
            }
            if (maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens must be at least 1");
            }
            messages = List.copyOf(messages);
        }

        /** The characters of every message, for the input-size estimate. */
        public int characters() {
            return messages.stream().mapToInt(m -> m.content().length()).sum();
        }

        @Override
        public String toString() {
            return "Request[messages=" + messages.size() + ", chars=" + characters() + ", maxTokens=" + maxTokens
                    + ", json=" + json + "]";
        }
    }

    /**
     * @param finishReason the provider's value, for example {@code stop} or {@code length}
     * @param attempts the HTTP attempts it took, counting the first
     */
    record Result(String text, int inputTokens, int outputTokens, String finishReason, int attempts) {

        public Result(String text, int inputTokens, int outputTokens, String finishReason) {
            this(text, inputTokens, outputTokens, finishReason, 1);
        }

        @Override
        public String toString() {
            return "Result[chars=" + (text == null ? 0 : text.length()) + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens + ", finishReason=" + finishReason + "]";
        }
    }
}
