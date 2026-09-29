package com.rincoltech.bms.kernel;

/** Masks personal identifiers to their last 4 characters (chapter 7 section 7.5, FR-AUD-05). */
public final class Masking {

    private Masking() {}

    public static String lastFour(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }
}
