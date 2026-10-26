package com.rincoltech.bms.core.help.internal;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes business data out of a question before it leaves the server (ADR-096 decision 3): Ask BMS explains how the app
 * works, so a phone number, an ID number, an email, a long figure or a person's name in a question is never needed and is
 * replaced by a placeholder ({@code [phone]}, {@code [id]}, {@code [email]}, {@code [number]}, {@code [name]}). The text is
 * normalised first, as the gateway's screen does (NFKC, invisible format characters removed, every decimal digit as its
 * ASCII digit), so the shapes cannot be dressed up.
 *
 * <p>A name is a word with a capital letter that is not a word of the help articles or of a question ("How", "Where"),
 * wherever it stands; a name typed in lower case is not found, which is why nothing the scrubbed text says is stored
 * (only its hash) and the answer comes from the articles alone. Short figures (a quantity, "3 days") stay: they carry no
 * person.
 */
final class QuestionScrubber {

    private static final String SEP = "[\\s\\p{Z}./_\\-()]";
    private static final Pattern FORMAT_CHARS = Pattern.compile("[\\p{Cf}\\u00AD\\u200B-\\u200F\\u2060\\uFEFF]");
    private static final Pattern EMAIL = Pattern.compile("[\\p{L}0-9._%+\\-]+@[\\p{L}0-9.\\-]+\\.[\\p{L}]{2,}");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern NIN = Pattern.compile("(?<![\\p{L}0-9])[Cc][MFmf][A-Za-z0-9]{12}(?![\\p{L}0-9])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:(?:\\+?256|0)" + SEP + "*7(?:" + SEP + "*\\d){8}|\\+\\d(?:" + SEP + "*\\d){7,13})(?!\\d)");
    /** Four or more digits, however they are grouped: an amount, an account, a receipt or a loan number. */
    private static final Pattern FIGURE = Pattern.compile("(?<!\\d)\\d(?:[\\s,.'\\-]?\\d){3,}(?!\\d)");

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'\\-]*");

    /** Words of a question that are not in the articles. */
    private static final Set<String> QUESTION_WORDS = Set.of(
            "how", "what", "why", "where", "when", "who", "which", "whose", "can", "could", "should", "would", "will",
            "is", "are", "was", "do", "does", "did", "i", "we", "my", "our", "please", "hello", "hi", "help", "bms",
            "ask", "there", "it", "this", "that", "the", "a", "an", "if", "then", "need", "want", "tell", "show");

    private final Set<String> vocabulary;

    /** @param articleText every article's text, whose words are never taken for a name */
    QuestionScrubber(Iterable<String> articleText) {
        Set<String> words = new HashSet<>(QUESTION_WORDS);
        for (String text : articleText) {
            Matcher m = WORD.matcher(text);
            while (m.find()) {
                words.add(m.group().toLowerCase(Locale.ROOT));
            }
        }
        this.vocabulary = Set.copyOf(words);
    }

    /** The question with every shape of business data replaced. */
    String scrub(String raw) {
        String s = normalise(raw);
        s = EMAIL.matcher(s).replaceAll("[email]");
        s = UUID_SHAPE.matcher(s).replaceAll("[id]");
        s = NIN.matcher(s).replaceAll("[id]");
        s = PHONE.matcher(s).replaceAll("[phone]");
        s = FIGURE.matcher(s).replaceAll("[number]");
        StringBuilder out = new StringBuilder();
        Matcher m = WORD.matcher(s);
        int at = 0;
        while (m.find()) {
            String word = m.group();
            boolean placeholder = m.start() > 0 && s.charAt(m.start() - 1) == '[';
            out.append(s, at, m.start());
            out.append(!placeholder && looksLikeName(word) ? "[name]" : word);
            at = m.end();
        }
        out.append(s.substring(at));
        // Two names in a row ("Akello Grace") read as one.
        return out.toString()
                .replaceAll("\\[name\\](?:\\s+\\[name\\])+", "[name]")
                .strip();
    }

    /** The scrubbed question as the cache key reads it: lower case, single spaces, no punctuation at the ends. */
    static String normalised(String scrubbed) {
        return scrubbed.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\p{P}\\s&&[^\\[]]+|[\\p{P}\\s&&[^\\]]]+$", "")
                .strip();
    }

    private boolean looksLikeName(String word) {
        if (!Character.isUpperCase(word.codePointAt(0))) {
            return false;
        }
        return !vocabulary.contains(word.toLowerCase(Locale.ROOT));
    }

    static String normalise(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = FORMAT_CHARS.matcher(s).replaceAll("");
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                out.append((char) ('0' + Character.digit(cp, 10)));
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
