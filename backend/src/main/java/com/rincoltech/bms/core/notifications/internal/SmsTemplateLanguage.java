package com.rincoltech.bms.core.notifications.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The safe placeholder language of a tenant's own SMS wording (FR-NTF-03, ADR-036 decision 9, spec 4.10).
 *
 * <p>A template is plain text with named placeholders written {@code {name}}. That is all of it: no
 * conditions, no loops, no expressions, no formatting directives, no way to reach anything but the
 * values the event itself supplies, so there is nothing to execute. Rules, each with its own error code:
 *
 * <ul>
 *   <li>a name must be on the event's whitelist, else {@code unknown_placeholder} (so {@code {unknown}} and
 *       {@code {balance}} are refused; an override never carries a balance);
 *   <li>a brace that does not belong to a whole {@code {name}} is {@code malformed_placeholder}; there is no
 *       escape for a literal brace, so one cannot be sent;
 *   <li>the placeholders an event needs for the message to make sense are required
 *       ({@code missing_required_placeholder});
 *   <li>control characters other than a line feed, and the invisible and direction-changing characters that can
 *       disguise a text (zero width, bidirectional overrides, the byte order mark), are
 *       {@code invalid_character};
 *   <li>the text is at most {@link #MAX_CHARS} characters ({@code too_long}), and in the worst case, every
 *       placeholder at its longest and written outside the SMS alphabet (UCS-2), at most
 *       {@link #MAX_WORST_SEGMENTS} segments ({@code too_many_segments}), so a tenant cannot save wording that
 *       quietly costs five messages each.
 * </ul>
 *
 * Rendering is one pass over the template: a value is inserted as it is and never read for placeholders again, so a
 * business name that contains {@code {amount}} stays that text. A value loses its control characters (a line feed
 * becomes a space, so a value cannot add a line). A value longer than the placeholder's length, counted in UTF-16 units as
 * the segment counter counts them (an emoji is two), is never cut: the render is refused ({@code value_too_long}) and the
 * caller sends the default text, so a shortened amount or name can never reach a member in a receipt. Because the length
 * is in the same units as the segment count, the worst case below holds for every value the render accepts. Nothing is HTML escaped:
 * an SMS is plain text, and the screens show the result as text, never as markup. Pure, so the golden tests pin it.
 */
final class SmsTemplateLanguage {

    static final int MAX_CHARS = 320;
    static final int MAX_WORST_SEGMENTS = 4;

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}|[{}]");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z_]{0,29}");

    /** One placeholder an event offers: its name, what it is, a fabricated sample and its longest value. */
    record Placeholder(String name, String description, String sample, int maxLength) {}

    /** One event type a tenant may reword: its default text and its placeholders. */
    record Event(String key, String title, String defaultText, List<Placeholder> placeholders, Set<String> required) {

        Placeholder placeholder(String name) {
            return placeholders.stream()
                    .filter(p -> p.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** A refused template: a stable code and the field-level message, never the whole text. */
    static final class Problem extends RuntimeException {

        private final String code;

        Problem(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private SmsTemplateLanguage() {}

    /** Checks a template against an event; returns the placeholders it uses, or throws {@link Problem}. */
    static Set<String> validate(Event event, String text) {
        if (text == null || text.isBlank()) {
            throw new Problem("blank", "The message cannot be empty.");
        }
        if (text.codePointCount(0, text.length()) > MAX_CHARS) {
            throw new Problem("too_long", "The message is longer than " + MAX_CHARS + " characters.");
        }
        text.codePoints().forEach(cp -> {
            if (forbidden(cp, true)) {
                throw new Problem("invalid_character", "The message holds a character that cannot be sent as text.");
            }
        });
        Set<String> used = new LinkedHashSet<>();
        for (Part part : parse(event, text)) {
            if (part instanceof Part.Hole hole) {
                used.add(hole.name());
            }
        }
        for (String name : event.required()) {
            if (!used.contains(name)) {
                throw new Problem("missing_required_placeholder", "The message must include {" + name + "}.");
            }
        }
        int worst = SmsSegments.count(worstCase(event, text)).segments();
        if (worst > MAX_WORST_SEGMENTS) {
            throw new Problem(
                    "too_many_segments",
                    "In the worst case this message takes " + worst + " text messages; the most allowed is "
                            + MAX_WORST_SEGMENTS + ". Shorten it.");
        }
        return used;
    }

    /**
     * The message with the event's values filled in. Throws {@link Problem} when the template is invalid for the
     * event or a placeholder has no value, so a message is never sent half filled.
     */
    static String render(Event event, String text, Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Part part : parse(event, text)) {
            switch (part) {
                case Part.Literal literal -> out.append(literal.text());
                case Part.Hole hole -> {
                    String value = values.get(hole.name());
                    if (value == null) {
                        throw new Problem("missing_value", "No value for {" + hole.name() + "}.");
                    }
                    String cleaned = clean(value);
                    int max = event.placeholder(hole.name()).maxLength();
                    if (cleaned.length() > max) {
                        throw new Problem(
                                "value_too_long", "The value of {" + hole.name() + "} is longer than " + max + ".");
                    }
                    out.append(cleaned);
                }
            }
        }
        return out.toString();
    }

    /** The values of the fabricated sample, with the tenant's own business name for {@code tenant_name}. */
    static Map<String, String> sampleValues(Event event, String tenantName) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (Placeholder p : event.placeholders()) {
            values.put(p.name(), "tenant_name".equals(p.name()) && tenantName != null ? tenantName : p.sample());
        }
        return values;
    }

    /** Removes control characters (a line feed becomes a space) and the disguising characters; never shortens a value otherwise. */
    static String clean(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                out.append(' ');
            } else if (!forbidden(cp, false)) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString().strip();
    }

    /**
     * The longest the message can be: every placeholder at its longest (in UTF-16 units, which is how the render limits a
     * value), in a letter outside the SMS alphabet, so the whole text is UCS-2 at one unit a letter. A value of emoji, two
     * units each, reaches the same unit count with fewer letters, so it cannot exceed this.
     */
    static String worstCase(Event event, String text) {
        StringBuilder out = new StringBuilder();
        for (Part part : parse(event, text)) {
            switch (part) {
                case Part.Literal literal -> out.append(literal.text());
                case Part.Hole hole ->
                    out.append("ŋ".repeat(event.placeholder(hole.name()).maxLength()));
            }
        }
        return out.toString();
    }

    private sealed interface Part {
        record Literal(String text) implements Part {}

        record Hole(String name) implements Part {}
    }

    private static List<Part> parse(Event event, String text) {
        List<Part> parts = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                parts.add(new Part.Literal(text.substring(at, m.start())));
            }
            String name = m.group(1);
            if (name == null || !NAME.matcher(name).matches()) {
                throw new Problem(
                        "malformed_placeholder",
                        "A { or } is not part of a placeholder such as {amount}. Braces cannot be used as text.");
            }
            if (event.placeholder(name) == null) {
                throw new Problem("unknown_placeholder", "{" + name + "} is not a placeholder of this message.");
            }
            parts.add(new Part.Hole(name));
            at = m.end();
        }
        if (at < text.length()) {
            parts.add(new Part.Literal(text.substring(at)));
        }
        return parts;
    }

    /** Controls (a line feed allowed in a template), and characters that hide or reorder text. */
    private static boolean forbidden(int cp, boolean allowLineFeed) {
        if (cp == '\n') {
            return !allowLineFeed;
        }
        if (Character.isISOControl(cp)) {
            return true;
        }
        return (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x2069)
                || cp == 0xFEFF
                || cp == 0x2028
                || cp == 0x2029
                || Character.getType(cp) == Character.SURROGATE
                || Character.getType(cp) == Character.UNASSIGNED;
    }
}
