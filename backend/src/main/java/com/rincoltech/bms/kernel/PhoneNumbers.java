package com.rincoltech.bms.kernel;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Uganda phone normalisation to E.164 (FR-MEM-02, chapter 13 section 13.6). Accepts
 * {@code 07XXXXXXXX}, {@code 7XXXXXXXX}, {@code 2567XXXXXXXX} and {@code +256 7XX XXX XXX} with
 * spaces or hyphens; anything else is empty, which callers report as {@code invalid_phone}.
 */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern NINE_DIGITS = Pattern.compile("^7\\d{8}$");

    private PhoneNumbers() {}

    public static Optional<String> normaliseUganda(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (s.startsWith("+256")) {
            s = s.substring(4);
        } else if (s.startsWith("256") && s.length() == 12) {
            s = s.substring(3);
        } else if (s.startsWith("0") && s.length() == 10) {
            s = s.substring(1);
        }
        return NINE_DIGITS.matcher(s).matches() ? Optional.of("+256" + s) : Optional.empty();
    }
}
