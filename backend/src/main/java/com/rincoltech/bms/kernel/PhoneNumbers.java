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

    private static final Pattern SMS_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern SMS_UGANDA = Pattern.compile("^(?:\\+256|256|0)(7\\d{8})$");

    private PhoneNumbers() {}

    /**
     * The only forms a message may be sent to (ADR-036): an E.164 Uganda mobile number with its
     * {@code +256}, the same without the plus ({@code 2567XXXXXXXX}, 12 digits) or the national
     * form {@code 07XXXXXXXX} (10 digits). Anything else is empty and the caller refuses to send:
     * a bare 9 digit number, a bare 10 digit number that does not start with 07 (a foreign number
     * pasted without its country code would be read as a stranger's in another country), a number
     * of another country (a later decision), letters, and anything with digits left over. Never
     * guesses. A text message costs money and reaches a person, so unlike {@link #normaliseUganda}
     * (member entry, which also takes the bare 9 digit form) this refuses what it cannot be sure of.
     */
    // JUSTIFICATION-A3: a new strict method for SMS; normaliseUganda must keep accepting the bare forms member entry
    // allows.
    public static Optional<String> forSms(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var m = SMS_UGANDA.matcher(SMS_SEPARATORS.matcher(raw.trim()).replaceAll(""));
        return m.matches() ? Optional.of("+256" + m.group(1)) : Optional.empty();
    }

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
