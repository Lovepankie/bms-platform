package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The gateway's second line of defence against personal data (spec 5.4 and 5.12; the first line is the
 * typed fact list of the slice that builds the prompts, and the question scrub of the slice that takes a
 * question). The port accepts any text, so before a request is reserved, let alone sent, every message is
 * looked at for the shapes personal data takes, and a request that holds one is refused with no call.
 * It is deliberately blunt and cannot recognise a name; that is the caller's job. What it refuses:
 *
 * <ul>
 *   <li>a run of six or more digits (a phone, an ID or account number, a plain large amount: facts are
 *       sent in their grouped display form, {@code 1,250,000}, so they are not in this class);
 *   <li>an email address;
 *   <li>a Uganda mobile number in any spacing, with or without {@code +256}, and any {@code +} number of
 *       ten to fourteen digits, split by spaces, dots, dashes, slashes, underscores or brackets;
 *   <li>a national ID number shape ({@code CM} or {@code CF} and twelve letters or digits).
 * </ul>
 *
 * <p>The text is normalised first, so the shapes cannot be dressed up: Unicode compatibility form (NFKC,
 * which turns full-width digits and the full-width at sign into ASCII and no-break spaces into spaces),
 * every other decimal digit of any script mapped to its ASCII value, and every invisible format character
 * (zero-width spaces and joiners, the soft hyphen, the byte order mark, direction marks) removed.
 *
 * <p>Shapes that are not personal data are set aside before the check, so a brief can carry them: dates
 * ({@code 2026-10-14}, {@code 20261014}, {@code 07-10-2026}, {@code 07.10.2026}), UUIDs, and the masked
 * identifiers (stars and the last four digits) that later slices send. A real phone number next to them is
 * still refused.
 */
final class PromptScreen {

    /** What was found, as a closed word that is logged (never the text). */
    enum Finding {
        DIGITS,
        EMAIL,
        PHONE,
        ID_NUMBER
    }

    /** Separators people put inside a phone number: any space (no-break and the rest), dot, slash, underscore, dash, bracket. */
    private static final String SEP = "[\\s\\p{Z}./_\\-()]";

    private static final Pattern FORMAT_CHARS = Pattern.compile("[\\p{Cf}\\u00AD\\u200B-\\u200F\\u2060\\uFEFF]");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern UGANDA_MOBILE =
            Pattern.compile("(?<!\\d)(?:\\+?256|0)" + SEP + "*7(?:" + SEP + "*\\d){8}(?!\\d)");
    private static final Pattern INTERNATIONAL = Pattern.compile("(?<!\\d)\\+\\d(?:" + SEP + "*\\d){9,13}(?!\\d)");
    private static final Pattern NIN = Pattern.compile("(?<![A-Za-z0-9])[Cc][MFmf][A-Za-z0-9]{12}(?![A-Za-z0-9])");

    // Set aside before the check; each is replaced by a space so a neighbouring phone number stays whole.
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "(?<![0-9A-Fa-f])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f])");
    private static final Pattern DAY_FIRST_DATE =
            Pattern.compile("(?<!\\d)(?:0?[1-9]|[12]\\d|3[01])[-./](?:0?[1-9]|1[0-2])[-./](?:19|20)\\d{2}(?!\\d)");
    private static final Pattern ISO_DATE =
            Pattern.compile("(?<!\\d)(?:19|20)\\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])(?!\\d)");
    private static final Pattern COMPACT_DATE =
            Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])(?!\\d)");
    private static final Pattern MASKED_ID = Pattern.compile("[*\\u2022Xx]{3,}\\d{1,4}(?!\\d)");

    private PromptScreen() {}

    static Optional<Finding> check(LlmClient.Request request) {
        for (LlmClient.Message message : request.messages()) {
            Optional<Finding> found = check(message.content());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    static Optional<Finding> check(String raw) {
        String text = normalise(raw);
        if (EMAIL.matcher(text).find()) {
            return Optional.of(Finding.EMAIL);
        }
        String rest = setAside(text);
        if (UGANDA_MOBILE.matcher(rest).find() || INTERNATIONAL.matcher(rest).find()) {
            return Optional.of(Finding.PHONE);
        }
        if (NIN.matcher(rest).find()) {
            return Optional.of(Finding.ID_NUMBER);
        }
        if (DIGIT_RUN.matcher(rest).find()) {
            return Optional.of(Finding.DIGITS);
        }
        return Optional.empty();
    }

    /** NFKC, invisible format characters removed, and every decimal digit of any script as its ASCII digit. */
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

    private static String setAside(String text) {
        String s = UUID_SHAPE.matcher(text).replaceAll(" ");
        s = MASKED_ID.matcher(s).replaceAll(" ");
        s = ISO_DATE.matcher(s).replaceAll(" ");
        s = DAY_FIRST_DATE.matcher(s).replaceAll(" ");
        return COMPACT_DATE.matcher(s).replaceAll(" ");
    }
}
