package com.rincoltech.bms.core.tenancy.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The brand colour rule (FR-TEN-08): a theme colour is {@code #RRGGBB} and must carry readable
 * text, so its WCAG 2.x contrast ratio against white or against near-black is at least 4.5. Of the
 * two, the better one is the text colour. The frontend has the same function ({@code contrast.ts});
 * the two must agree, and both are tested against the same reference values.
 */
final class BrandColour {

    static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    static final String LIGHT_TEXT = "#FFFFFF";
    static final String DARK_TEXT = "#111111";
    static final double MIN_CONTRAST = 4.5;

    private BrandColour() {}

    /** WCAG relative luminance of a {@code #RRGGBB} colour. */
    static double luminance(String hex) {
        double r = channel(Integer.parseInt(hex.substring(1, 3), 16));
        double g = channel(Integer.parseInt(hex.substring(3, 5), 16));
        double b = channel(Integer.parseInt(hex.substring(5, 7), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG contrast ratio, from 1 to 21, of two {@code #RRGGBB} colours. */
    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The text colour with the better contrast on this colour, or empty when neither reaches 4.5. */
    static Optional<String> readableText(String hex) {
        if (hex == null || !HEX.matcher(hex).matches()) {
            return Optional.empty();
        }
        double light = contrast(hex, LIGHT_TEXT);
        double dark = contrast(hex, DARK_TEXT);
        double best = Math.max(light, dark);
        if (best < MIN_CONTRAST) {
            return Optional.empty();
        }
        return Optional.of(light >= dark ? LIGHT_TEXT : DARK_TEXT);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
