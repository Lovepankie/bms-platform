package com.rincoltech.bms.core.notifications.internal;

import java.util.Set;

/**
 * Counts the segments an SMS body costs (FR-NTF-02, FR-NTF-07). A body made only of the GSM 03.38
 * default alphabet (and its extension table, where each character takes two septets) goes as GSM 7:
 * 160 characters in one segment, 153 in each of several. Any other character (a curly quote, an
 * accented letter outside the table, an emoji) switches the whole body to UCS-2: 70 code units in
 * one segment, 67 in each of several; characters outside the Basic Multilingual Plane take two
 * code units. Pure, so the golden tests pin the numbers.
 */
final class SmsSegments {

    enum Encoding {
        GSM7,
        UCS2
    }

    record Count(Encoding encoding, int units, int segments) {}

    private static final String BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    /** Each of these takes two septets: an escape and the character. */
    private static final Set<Character> EXTENSION = Set.of('^', '{', '}', '\\', '[', '~', ']', '|', '€', '\f');

    private SmsSegments() {}

    static Count count(String body) {
        int septets = 0;
        boolean gsm = true;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\u001b' && BASIC.indexOf(c) >= 0) {
                septets++;
            } else if (EXTENSION.contains(c)) {
                septets += 2;
            } else {
                gsm = false;
                break;
            }
        }
        if (gsm) {
            return new Count(Encoding.GSM7, septets, segments(septets, 160, 153));
        }
        int units = body.length();
        return new Count(Encoding.UCS2, units, segments(units, 70, 67));
    }

    private static int segments(int units, int single, int multi) {
        if (units == 0) {
            return 1;
        }
        return units <= single ? 1 : (units + multi - 1) / multi;
    }
}
