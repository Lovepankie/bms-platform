package com.rincoltech.bms.core.payments.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the three names a notification carries and nothing else (spec 3.6 step 2, 3.12): {@code OrderTrackingId},
 * {@code OrderMerchantReference} and {@code OrderNotificationType}. A GET carries them in the query string and a POST in a
 * JSON body; both are read, whatever the declared content type says, because the body of a notification is not trusted
 * to describe itself. Every other name is ignored, so an amount or a status in a forged request is never even looked at.
 *
 * <p>The values are checked for shape before they go anywhere: the tracking id is a GUID, the reference is one of this
 * environment's, the type is letters. A value that fails, a name that appears twice with different values (parameter
 * pollution) and a body that is not a JSON object are malformed; a body over the limit is too large. A made-up value
 * can therefore reach neither the database nor the response, which echoes the validated values only.
 */
final class NotificationReader {

    private static final Pattern GUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TYPE = Pattern.compile("^[A-Za-z]{3,20}$");
    static final int MAX_QUERY = 2048;

    record Notification(String type, String trackingId, String reference) {}

    /** The request is not a notification we can read. */
    static final class Malformed extends Exception {
        Malformed() {
            super("malformed notification", null, false, false);
        }
    }

    static final class TooLarge extends Exception {
        TooLarge() {
            super("notification too large", null, false, false);
        }
    }

    private NotificationReader() {}

    static Notification read(
            HttpServletRequest request, ObjectMapper mapper, int maxBody, String environment, String defaultType)
            throws Malformed, TooLarge {
        Map<String, String> found = new HashMap<>();
        boolean[] conflict = new boolean[1];
        String query = request.getQueryString();
        if (query != null) {
            if (query.length() > MAX_QUERY) {
                throw new Malformed();
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    put(found, conflict, decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
        }
        if ("POST".equals(request.getMethod())) {
            byte[] body;
            try {
                body = request.getInputStream().readNBytes(maxBody + 1);
            } catch (IOException e) {
                throw new Malformed();
            }
            if (body.length > maxBody) {
                throw new TooLarge();
            }
            String text = new String(body, StandardCharsets.UTF_8).strip();
            if (text.startsWith("{")) {
                JsonNode json;
                try {
                    json = mapper.readTree(text);
                } catch (RuntimeException e) {
                    throw new Malformed();
                }
                if (json == null || !json.isObject()) {
                    throw new Malformed();
                }
                for (var entry : json.properties()) {
                    if (entry.getValue().isString()) {
                        put(found, conflict, entry.getKey(), entry.getValue().asString());
                    }
                }
            }
        }
        String tracking = found.get("ordertrackingid");
        String reference = found.get("ordermerchantreference");
        String type = found.getOrDefault("ordernotificationtype", defaultType);
        if (tracking == null || reference == null || conflict[0]) {
            throw new Malformed();
        }
        if (!GUID.matcher(tracking).matches()
                || MerchantReference.parse(reference, environment).isEmpty()
                || !TYPE.matcher(type).matches()) {
            throw new Malformed();
        }
        return new Notification(type, tracking, reference);
    }

    /** Keeps the first value of a name we want; a second, different value marks the request as polluted. */
    private static void put(Map<String, String> found, boolean[] conflict, String name, String value) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!key.equals("ordertrackingid")
                && !key.equals("ordermerchantreference")
                && !key.equals("ordernotificationtype")) {
            return;
        }
        String earlier = found.putIfAbsent(key, value);
        if (earlier != null && !earlier.equals(value)) {
            conflict[0] = true;
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
