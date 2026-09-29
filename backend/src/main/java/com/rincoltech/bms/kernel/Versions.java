package com.rincoltech.bms.kernel;

import org.springframework.http.HttpStatus;

/** Optimistic concurrency with {@code ETag} and {@code If-Match} (chapter 7 section 7.9). */
public final class Versions {

    private Versions() {}

    /**
     * The version a client sent in {@code If-Match} ({@code "3"} or {@code 3}). Missing returns
     * 428 {@code precondition_required}; anything else that is not a version is malformed.
     */
    public static int fromIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "precondition_required",
                    "If-Match required",
                    "Send the If-Match header with the version from the last read.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "If-Match is not a version.");
        }
    }

    /** 409 {@code version_conflict}, naming the current version. */
    public static ApiException conflict(int current) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "version_conflict",
                "Version conflict",
                "The resource changed since it was read; the current version is " + current + ".");
    }
}
