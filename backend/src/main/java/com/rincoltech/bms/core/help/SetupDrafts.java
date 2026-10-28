package com.rincoltech.bms.core.help;

import java.util.Optional;

/**
 * The setup helper's door to the free tier models (issue #626, owner decision D62, ADR-102). A vertical hands over what the
 * owner gave (typed text, a photo, or a CSV, Excel or PDF file) with its own instructions and a short description of what
 * the business already holds, and gets back its own draft rows read from the model's answer, or the reason there is none.
 * It never saves anything: the rows go into the vertical's own import check, and only the owner's tap there adds them.
 *
 * <p>The chain, keys, never-Claude guard and spend log are Ask BMS's (ADR-096): Gemini, then Groq, then DeepSeek, each on
 * its own host, every call reserved in {@code help_ask_spend} before it is made. Business data reaches a provider only for
 * a business named in {@code BMS_SETUP_AI_TENANTS}; for every other one {@link #draft} answers 403
 * {@code setup_ai_off} and nothing is read.
 */
public interface SetupDrafts {

    /** Whether the helper is on for the bound business, and whether a photo can be read (a provider takes pictures). */
    Status status();

    /**
     * Throws 403 {@code setup_ai_off} unless the platform names the bound business. A caller calls it first, before it reads
     * anything of the business for the context or the upload.
     */
    void requireOn();

    /**
     * Drafts rows from what the owner gave. Throws 403 {@code setup_ai_off} while the helper is off for the business, 429
     * {@code setup_ai_rate_limited} past the person's drafts an hour, and 422 {@code setup_file_not_read} for a file of a
     * kind or size it cannot read. Not to be called inside a transaction: a provider may take its whole budget.
     */
    <T> Result<T> draft(Request request, Reader<T> reader);

    record Status(boolean enabled, boolean photos) {}

    /**
     * @param kind {@code retail} or {@code lending}, for the log
     * @param instructions the system prompt: what to draft and the JSON to answer with
     * @param context what the business already holds, in words, so names are matched and not repeated
     * @param text what the owner typed, or null when a file is given
     * @param upload the file the owner chose (a photo, CSV, Excel or PDF), or null when text is given
     */
    record Request(String kind, String instructions, String context, String text, Upload upload) {}

    /** A chosen file. Its bytes are never printed. */
    record Upload(String fileName, String contentType, byte[] bytes) {

        /** The file of a multipart request, or null when none was sent. */
        public static Upload of(org.springframework.web.multipart.MultipartFile file) {
            if (file == null || file.isEmpty()) {
                return null;
            }
            try {
                return new Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
            } catch (java.io.IOException e) {
                throw new com.rincoltech.bms.kernel.ApiException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "malformed_request",
                        "Malformed request",
                        "The upload could not be read.");
            }
        }

        @Override
        public String toString() {
            return "Upload[type=" + contentType + ", bytes=" + (bytes == null ? 0 : bytes.length) + "]";
        }
    }

    /** Reads a model's answer into the caller's draft. */
    interface Reader<T> {

        /** The draft, or empty when the answer is not one (the next provider is then tried). */
        Optional<T> read(String answer);

        /** How many rows the draft holds, for the log. */
        int rows(T draft);
    }

    /**
     * A draft, or why there is none: {@code no_provider} (none is set, or none reads photos), {@code all_failed} (every
     * provider failed or answered badly), {@code daily_cap} (the business's drafts for the day are used up) or
     * {@code unreadable} (the file held nothing to read). The caller then offers to type the list or import a file.
     *
     * @param provider the provider that drafted it, or null
     */
    record Result<T>(T draft, String provider, String reason) {

        public static <T> Result<T> none(String reason) {
            return new Result<>(null, null, reason);
        }

        public Optional<T> drafted() {
            return Optional.ofNullable(draft);
        }
    }
}
