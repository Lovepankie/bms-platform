package com.rincoltech.bms.kernel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** Opaque list cursor (chapter 7 section 7.6): the sort key of the last row, base64url encoded. */
public final class Cursor {

    private Cursor() {}

    public static String encode(String sortKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sortKey.getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<String> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
        }
    }
}
