package com.rincoltech.bms.lending.advisor.internal;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * How a model's text is read before anything is checked, so that it cannot be dressed up (the gateway's input screen
 * does the same for what goes in): Unicode compatibility form (full-width digits become ASCII, no-break and thin spaces
 * become spaces), every other decimal digit of any script becomes its ASCII digit, every invisible format character
 * (zero-width spaces and joiners, the soft hyphen, direction marks, the byte order mark) is removed, and the minus sign
 * and the dashes that stand for one become a plain hyphen-minus.
 */
final class Text {

    private static final Pattern FORMAT_CHARS = Pattern.compile("[\\p{Cf}\\u00AD\\u200B-\\u200F\\u2060\\uFEFF]");

    private Text() {}

    /** The text as it is checked: see the class comment. */
    static String normalise(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = FORMAT_CHARS.matcher(s).replaceAll("");
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                out.append((char) ('0' + Character.digit(cp, 10)));
            } else if (cp == 0x2212 || cp == 0x2013 || cp == 0x2014 || cp == 0x2012 || cp == 0x2010 || cp == 0x2011) {
                out.append('-');
            } else if (cp == 0x2044) {
                out.append('/');
            } else if (cp == 0x066B) {
                out.append('.');
            } else if (cp == 0x066C) {
                out.append(',');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /**
     * The text as it is shown once accepted: in the same compatibility form as the text that was checked (so what is shown
     * is what was validated), invisible characters removed and digits of other scripts as ASCII digits.
     */
    static String display(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = FORMAT_CHARS.matcher(s).replaceAll("");
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                out.append((char) ('0' + Character.digit(cp, 10)));
            } else if (cp == 0x2044) {
                out.append('/');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString().strip();
    }

    /** True when every letter is Latin: the prompt asks for English, and another script can only be a disguise. */
    static boolean onlyLatinLetters(String s) {
        return s.codePoints().noneMatch(cp -> {
            if (!Character.isLetter(cp)) {
                return false;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script != Character.UnicodeScript.LATIN;
        });
    }
}
