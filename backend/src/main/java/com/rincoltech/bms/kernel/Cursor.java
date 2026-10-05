package com.rincoltech.bms.kernel;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
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
            throw malformed();
        }
    }

    /**
     * The shared parser for a {@code sortKey|id} cursor, used by every list endpoint whose order ends
     * in an id. Anything that does not decode to a sort key, a bar and a UUID is 400
     * {@code malformed_request}, never a 500.
     */
    public static Optional<Key> decodeKey(String cursor) {
        return decode(cursor).map(after -> {
            int bar = after.lastIndexOf('|');
            if (bar < 0) {
                throw malformed();
            }
            try {
                return new Key(after.substring(0, bar), UUID.fromString(after.substring(bar + 1)));
            } catch (IllegalArgumentException e) {
                throw malformed();
            }
        });
    }

    static ApiException malformed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
    }

    /** A decoded cursor: the last row's sort key and id. */
    public record Key(String sortKey, UUID id) {

        /** The sort key read as a timestamp; 400 {@code malformed_request} when it is not one. */
        public Instant at() {
            try {
                return Instant.parse(sortKey);
            } catch (DateTimeParseException e) {
                throw malformed();
            }
        }
    }
}
