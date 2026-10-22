package com.rincoltech.bms.kernel;

import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * The log redaction filter of NFR-SEC-08 and standing rule 9 (ADR-059, issue #426): every string written to a log line on
 * staging and production (the message, the exception text and stack trace, every MDC value) passes through
 * {@link #redact} before it leaves the process. Named in {@code logging.structured.json.customizer} of the {@code server}
 * profile, so it runs inside Spring Boot's ECS JSON formatter; the dev and test profiles log plain text of fabricated data
 * and are not filtered.
 *
 * <p>What it replaces:
 *
 * <ul>
 *   <li>the value after a secret-looking key, the key standing alone or ending a longer name ({@code token=},
 *       {@code resetToken=}, {@code "newPassword":}, {@code client_secret=}, {@code BMS_SMTP_PASSWORD=},
 *       {@code x-api-key:}, {@code pin=} and the like), a quoted value up to its closing quote; the credential after
 *       {@code Authorization:}, {@code Bearer} or {@code Basic}; signed tokens ({@code [redacted]});
 *   <li>email addresses ({@code [email]});
 *   <li>NINs, in any letter case ({@code [nin]});
 *   <li>Uganda mobile and landline numbers, written with spaces or dashes, with {@code +}, a URL-encoded {@code %2B} or a
 *       leading 0, and any other standalone run of 9 to 15 digits ({@code [phone]}), except a run that is a labelled
 *       amount, timestamp, count or id ({@code amount=}, {@code _minor=}, {@code id=}, {@code ts=} and the like), an epoch
 *       time in seconds or milliseconds, or a compact date and time: a lending ledger needs its amounts and times;
 *   <li>the value after a name key ({@code name=}, {@code full_name=}, {@code customerName=}, a record's
 *       {@code Customer[name=...]}) ({@code [name]}).
 * </ul>
 *
 * A name written into free text with no key cannot be recognised: code must not log one (chapter 8 section 8.11).
 *
 * <p>Every pattern is linear in the length of the line: runs are bounded or possessive and each starts at the beginning
 * of a run, so a long logged URL or token costs no more than reading it once.
 */
public final class LogRedaction implements StructuredLoggingJsonMembersCustomizer<Object> {

    /**
     * A pattern and what replaces a match; the function sees the match and the whole text it was found in. {@code worth}
     * is a cheap test on the text in lower case (a word or a character every match must contain) that skips the pattern
     * on a line that cannot match it.
     */
    private record Rule(
            Predicate<String> worth, Pattern pattern, BiFunction<MatchResult, String, String> replacement) {}

    /** Not after a letter, digit, underscore, dot, plus or dash, or else right after a URL escape such as {@code %2B}. */
    private static final String NUMBER_START = "(?:(?<![\\w.+-])|(?<=%[0-9A-Fa-f]{2}))";

    /** A label that makes the digits after it an amount, a time, a count or an id rather than a phone number. */
    private static final Pattern LABEL = Pattern.compile(
            "(?i)(?:amount|minor|balance|total|price|cost|fee|limit|principal|id|ms|millis|nanos|epoch|time|timestamp|ts"
                    + "|_at|version|count|size|bytes|rows|seq|offset)[\"']?\\s{0,3}[:=]\\s{0,3}[\"']?$");

    /** An epoch time in seconds or milliseconds from 2020 to 2033, or a compact date and time from 2000. */
    private static final Pattern TIME =
            Pattern.compile("1[6-9]\\d{8}|1[6-9]\\d{11}|20\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{0,9}");

    /** The credential after an Authorization header, its scheme kept. */
    private static final Rule AUTHORIZATION = new Rule(
            lower -> lower.contains("authorization"),
            Pattern.compile("(?i)(authorization\"?\\s*[:=]\\s*\"?)((?:basic|bearer|digest|token|negotiate)\\s+)?"
                    + "([^\\s\"',;&)}\\]]+)"),
            (m, text) -> m.group(1) + (m.group(2) == null ? "" : m.group(2)) + "[redacted]");

    /**
     * A secret key and its separator, the key alone or at the end of a longer name ({@code resetToken},
     * {@code BMS_SMTP_PASSWORD}, {@code x-api-key}); the value after it is found by {@link #valueEnd}, not by the pattern, so
     * a long quoted value cannot overflow the regex engine's stack.
     */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(?:token|password|passwd|pwd|secret|api[_-]?key|apikey|access_?key|private_?key|data_?key|consumer_?key"
                    + "|signing_?jwk|credentials?|totp_?code|recovery_?code|pin|otp)\"?\\s*[:=]\\s*");

    private static final List<Rule> RULES = List.of(
            new Rule(
                    lower -> lower.contains("bearer"),
                    Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"),
                    (m, text) -> "Bearer [redacted]"),
            // Basic credentials with no header name: base64 with a digit, a + / = or a capital past the first letter,
            // so
            // "Basic information" stays.
            new Rule(
                    lower -> lower.contains("basic"),
                    Pattern.compile("\\b(?i:basic)\\s+(?=.[A-Za-z0-9+/]*[0-9+/=A-Z])[A-Za-z0-9+/]{8,}+={0,2}"),
                    (m, text) -> "Basic [redacted]"),
            new Rule(
                    lower -> lower.contains("eyj"),
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_-]*+"),
                    (m, text) -> "[redacted]"),
            // The local part starts a run and is at most 64 characters, taken possessively, so a long run with no @ is
            // read once, not once from every position. The labels are possessive and at most ten (a bounded repeat, so
            // the engine's stack stays small); only whole labels are given back, so "x@example.test." still matches.
            new Rule(
                    lower -> lower.indexOf('@') >= 0,
                    Pattern.compile(
                            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}+@(?:[A-Za-z0-9-]{1,63}+\\.){1,10}[A-Za-z]{2,63}+"),
                    (m, text) -> "[email]"),
            new Rule(
                    lower -> lower.contains("cm") || lower.contains("cf"),
                    Pattern.compile("(?i)\\bC[MF][A-Z0-9]{12}\\b"),
                    (m, text) -> "[nin]"),
            // Uganda numbers: mobile 07xx, landline 03xx and 04xx (and 02xx), as 0..., 256..., +256... or %2B256...
            new Rule(
                    LogRedaction::hasNineDigits,
                    Pattern.compile(NUMBER_START
                            + "(?:(?:\\+|%2[Bb])?256[ -]?|0)[2-47]\\d{2}[ -]?\\d{3}[ -]?\\d{3}(?![\\w.-])"),
                    (m, text) -> "[phone]"),
            new Rule(
                    LogRedaction::hasNineDigits,
                    Pattern.compile(NUMBER_START + "(?:\\+|%2[Bb])?\\d{9,15}(?![\\w.-])"),
                    (m, text) -> keepsDigits(m, text) ? m.group() : "[phone]"),
            new Rule(
                    lower -> lower.contains("name"),
                    Pattern.compile("(?i)((?:full_?|first_?|last_?|middle_?|other_?|display_?|member_?|borrower_?"
                            + "|customer_?|buyer_?|kin_?|contact_?|guarantor_?|holder_?|account_?|user_?)names?"
                            + "|surname|\\bname)"
                            + "(\"?\\s*[:=]\\s*)(\"[^\"]*+\"|'[^']*+'|[^,;)}\\]\\n]+)"),
                    (m, text) -> m.group(1) + m.group(2) + "[name]"));

    /** The text with every personal value it recognises replaced; {@code null} stays {@code null}. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = secrets(apply(AUTHORIZATION, text));
        for (Rule rule : RULES) {
            out = apply(rule, out);
        }
        return out;
    }

    /**
     * Replaces the value after every secret key: a quoted value up to its closing quote (a backslash escapes one), or the
     * end of a cut line; an unquoted one up to a space or one of {@code & , ; ) \} ]}. One pass, no backtracking.
     */
    private static String secrets(String text) {
        Matcher m = SECRET_KEY.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && m.find(from)) {
            int start = m.end();
            int end = valueEnd(text, start);
            if (end == start) {
                from = m.end();
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            char first = text.charAt(start);
            boolean quotedValue = first == '"' || first == '\'';
            out.append(text, copied, start).append(quotedValue ? first + "[redacted]" + first : "[redacted]");
            copied = end;
            from = end;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** Where the value starting at {@code start} ends (exclusive); {@code start} when there is none. */
    private static int valueEnd(String text, int start) {
        if (start >= text.length()) {
            return start;
        }
        char first = text.charAt(start);
        int i = start + 1;
        if (first == '"' || first == '\'') {
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\' && first == '"') {
                    i += 2;
                } else if (c == first) {
                    return i + 1;
                } else {
                    i++;
                }
            }
            return text.length();
        }
        i = start;
        while (i < text.length() && "\"' \t\r\n&,;)}]".indexOf(text.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    private static String apply(Rule rule, String text) {
        if (!rule.worth().test(text.toLowerCase(Locale.ROOT))) {
            return text;
        }
        Matcher m = rule.pattern().matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            m.appendReplacement(out, Matcher.quoteReplacement(rule.replacement().apply(m, text)));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    /** Whether the text holds nine digits at least, the fewest any phone number pattern needs. */
    private static boolean hasNineDigits(String text) {
        int digits = 0;
        for (int i = 0; i < text.length() && digits < 9; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits >= 9;
    }

    /** A run of digits that is an amount, a time, a count or an id: labelled so just before it, or shaped like a time. */
    private static boolean keepsDigits(MatchResult m, String text) {
        String digits = m.group();
        if (digits.startsWith("+") || digits.startsWith("%")) {
            return false;
        }
        if (TIME.matcher(digits).matches()) {
            return true;
        }
        int from = Math.max(0, m.start() - 24);
        return LABEL.matcher(text.substring(from, m.start())).find();
    }

    @Override
    public void customize(Members<Object> members) {
        members.applyingValueProcessor(ValueProcessor.of(String.class, LogRedaction::redact));
    }
}
