package com.rincoltech.bms.kernel;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one form a typed email address is looked up and saved in (issue #823): every whitespace character removed, at
 * the ends and inside (spaces, tabs, line breaks, the non-breaking space and the other Unicode spaces, and the zero
 * width characters a copy and paste can carry), then lower case, as stored emails are compared with {@code lower()}.
 * An email address never holds a space, so nothing a person meant is lost. Only for a value already read as an email:
 * a phone number keeps its own check ({@link PhoneNumbers}).
 */
public final class EmailAddresses {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}\\u0085\\u180E\\u200B-\\u200D\\u2060\\uFEFF]");

    private EmailAddresses() {}

    /** The cleaned address; {@code null} stays {@code null}. */
    public static String normalise(String typed) {
        if (typed == null) {
            return null;
        }
        return WHITESPACE.matcher(typed).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
