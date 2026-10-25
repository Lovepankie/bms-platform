package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.VariantLine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The quick creation of an item's variants (issue #384, ADR-051): the sizes and colours are chosen once and every
 * combination is made, each with a code built from the item's base code and its own prices defaulting to the item's.
 * No database and no transaction: the service checks the plan against the catalogue. The PWA builds the same preview
 * (api/retail-variants.ts), so the codes it shows are the codes saved.
 */
final class VariantGrid {

    /** An item never has more than this many variants, however they are added (issue #395). */
    static final int MAX_VARIANTS = 100;

    /** A code is at most 40 characters: the base code (24 at most), then up to 7 of the size and 7 of the colour. */
    static final int TOKEN = 7;

    private VariantGrid() {}

    /** One variant to make: its size and colour (one may be null), code, prices and place in the item's list. */
    record Planned(String size, String colour, String code, long costMinor, long sellMinor, int position) {

        /** The size and colour, lower-cased, as the unique index compares them. */
        String key() {
            return key(size, colour);
        }

        static String key(String size, String colour) {
            return (size == null ? "" : size.toLowerCase(Locale.ROOT)) + "\u0000"
                    + (colour == null ? "" : colour.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The variants to make. With {@code lines} the request names them one by one (the PWA sends the preview the owner
     * checked); without, every combination of {@code sizes} and {@code colours} is made, the sizes in the order given
     * first. A combination already in the item ({@code existing}, keys of {@link Planned#key}) is skipped when generated
     * and refused when named. Positions continue from {@code nextPosition}.
     */
    static List<Planned> plan(
            String baseCode,
            List<String> sizes,
            List<String> colours,
            List<VariantLine> lines,
            long costMinor,
            long sellMinor,
            Set<String> existing,
            int nextPosition) {
        List<Planned> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(existing);
        int position = nextPosition;
        if (lines != null && !lines.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) {
                VariantLine l = lines.get(i);
                String size = option(l.size(), "variants[" + i + "].size");
                String colour = option(l.colour(), "variants[" + i + "].colour");
                if (size == null && colour == null) {
                    throw ApiException.validation(List.of(new FieldProblem(
                            "variants[" + i + "]",
                            "invalid",
                            "Give each size and colour line a size, a colour or both.")));
                }
                if (!seen.add(Planned.key(size, colour))) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "duplicate_variant",
                            "Duplicate",
                            "The item has " + label(size, colour) + " already. Each one is listed once.");
                }
                // Issue #562: the app makes every new item's code, a variant's too.
                if (l.code() != null && !l.code().isBlank()) {
                    throw CatalogueService.codeMadeByApp("variants[" + i + "].code", CatalogueService.MADE_BY_APP);
                }
                out.add(new Planned(
                        size,
                        colour,
                        code(baseCode, size, colour),
                        l.costMinor() == null ? costMinor : l.costMinor(),
                        l.sellMinor() == null ? sellMinor : l.sellMinor(),
                        position++));
            }
        } else {
            List<String> ss = distinct(sizes);
            List<String> cs = distinct(colours);
            List<String> sizeAxis = ss.isEmpty() ? java.util.Collections.<String>singletonList(null) : ss;
            List<String> colourAxis = cs.isEmpty() ? java.util.Collections.<String>singletonList(null) : cs;
            for (String size : sizeAxis) {
                for (String colour : colourAxis) {
                    if (size == null && colour == null) {
                        continue;
                    }
                    if (!seen.add(Planned.key(size, colour))) {
                        continue;
                    }
                    out.add(new Planned(size, colour, code(baseCode, size, colour), costMinor, sellMinor, position++));
                }
            }
        }
        if (out.size() > MAX_VARIANTS) {
            throw ApiException.rule(
                    "too_many_variants",
                    "That makes " + out.size() + " sizes and colours. An item has at most " + MAX_VARIANTS + ".");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (Planned p : out) {
            if (!codes.add(p.code().toLowerCase(Locale.ROOT))) {
                throw ApiException.rule(
                        "duplicate_variant_code",
                        "Two sizes or colours would get the code " + p.code()
                                + ". Spell one of them differently in its first seven letters or digits.");
            }
        }
        return out;
    }

    /** The description every variant has: the item's name and then its size and colour, "Baby romper (0-3m, Blue)". */
    static String description(String name, String size, String colour) {
        return name + " (" + label(size, colour) + ")";
    }

    /** "0-3m, Blue", "0-3m" or "Blue". */
    static String label(String size, String colour) {
        if (size == null) {
            return colour;
        }
        return colour == null ? size : size + ", " + colour;
    }

    /**
     * The code the app gives a variant (issue #562: always, never typed): the base code, then the size and the colour, each cut to their
     * letters and digits in capitals and to seven characters. ROMP, 0-3m and Sky blue give ROMP-03M-SKYBLUE;
     * a longer part keeps its first seven.
     */
    static String code(String baseCode, String size, String colour) {
        StringBuilder code = new StringBuilder(baseCode);
        for (String part : new String[] {size, colour}) {
            String t = token(part);
            if (!t.isEmpty()) {
                code.append('-').append(t);
            }
        }
        return code.toString();
    }

    static String token(String part) {
        if (part == null) {
            return "";
        }
        StringBuilder t = new StringBuilder();
        part.toUpperCase(Locale.ROOT)
                .codePoints()
                .filter(Character::isLetterOrDigit)
                .limit(TOKEN)
                .forEach(t::appendCodePoint);
        return t.toString();
    }

    /** At most this many characters in a size or a colour, after trimming. */
    static final int OPTION_MAX = 30;

    /**
     * A size or colour as the owner typed it: trimmed, null when blank, refused when longer than {@link #OPTION_MAX} or when
     * it holds a control character (a line break pasted from a spreadsheet).
     */
    static String option(String raw, String field) {
        String t = clean(raw);
        if (t != null
                && (t.codePointCount(0, t.length()) > OPTION_MAX
                        || t.codePoints()
                                .anyMatch(
                                        c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))) {
            throw ApiException.validation(List.of(new FieldProblem(
                    field, "invalid", "Keep a size or colour to " + OPTION_MAX + " letters, on one line.")));
        }
        return t;
    }

    /** Trimmed, or null when blank. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** Trimmed, blanks dropped, each once ignoring case, in the order given. */
    static List<String> distinct(List<String> values) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            String t = option(v, "sizes");
            if (t != null && seen.add(t.toLowerCase(Locale.ROOT))) {
                out.add(t);
            }
        }
        return out;
    }
}
