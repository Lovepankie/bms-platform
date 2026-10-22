package com.rincoltech.bms.retail.catalogue.internal;

import java.util.Optional;

/**
 * The barcode formats a product accepts (ADR-070, issue #345; FR-RET-68): EAN-13, EAN-8 and UPC-A, whose last digit is
 * a check digit, and Code 128, which carries any printable ASCII and whose check symbol is inside the bars, not the
 * text. A code of 13, 12 or 8 digits is read as EAN-13, UPC-A or EAN-8 unless Code 128 is asked for by name.
 */
final class Barcodes {

    static final int MAX_LENGTH = 48;

    /** The prefix of a Code 128 code the system makes for a product that has none (its labels). */
    static final String GENERATED_PREFIX = "IN";

    /**
     * Why an all-digit code of an unusual length, or a number in scientific notation, is refused unless Code 128 is
     * asked for by name: a spreadsheet turns a barcode cell into a number, dropping a leading zero (a 13 digit EAN-13
     * becomes 12 digits, a UPC-A 11) or writing it as 4.00638E+12.
     */
    static final String SPREADSHEET_DAMAGE =
            "This does not look like a barcode: the spreadsheet may have dropped a leading"
                    + " zero or turned the code into a number. Type the code exactly as printed under the bars, or set the"
                    + " format to code128 if it really is this.";

    private static final java.util.regex.Pattern SCIENTIFIC =
            java.util.regex.Pattern.compile("[+-]?\\d+(?:[.,]\\d+)?[eE][+-]?\\d+");

    private Barcodes() {}

    enum Format {
        EAN13("ean13", 13),
        EAN8("ean8", 8),
        UPCA("upca", 12),
        CODE128("code128", 0);

        final String key;
        final int digits;

        Format(String key, int digits) {
            this.key = key;
            this.digits = digits;
        }

        static Optional<Format> of(String key) {
            for (Format f : values()) {
                if (f.key.equals(key)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }
    }

    /** A code that passed its format's rules, with the format it was read as. */
    record Checked(String code, Format format) {}

    /**
     * The code read as {@code asked} (null to infer it from the shape), or the reason it is refused in plain words.
     * Surrounding spaces are dropped; a scanner's trailing Enter never reaches here.
     */
    static Result check(String raw, String asked) {
        String code = raw == null ? "" : raw.strip();
        if (code.isEmpty()) {
            return Result.refused("The barcode is empty.");
        }
        if (code.length() > MAX_LENGTH) {
            return Result.refused("A barcode has at most " + MAX_LENGTH + " characters.");
        }
        if (!code.chars().allMatch(c -> c >= '!' && c <= '~')) {
            return Result.refused("A barcode holds only letters, digits and signs, with no spaces.");
        }
        if (code.indexOf('/') >= 0 || code.indexOf('\\') >= 0) {
            // A slash cannot travel in the lookup's path (GET /retail/products/by-barcode/{code}).
            return Result.refused("A barcode here cannot hold a slash or a backslash.");
        }
        Format format;
        if (asked != null && !asked.isBlank()) {
            format = Format.of(asked).orElse(null);
            if (format == null) {
                return Result.refused("The format must be ean13, ean8, upca or code128.");
            }
        } else {
            if (spreadsheetDamaged(code)) {
                return Result.refused(SPREADSHEET_DAMAGE);
            }
            format = inferred(code);
        }
        if (format == Format.CODE128) {
            return Result.ok(new Checked(code, format));
        }
        if (code.length() != format.digits || !code.chars().allMatch(Character::isDigit)) {
            return Result.refused("A barcode in " + name(format) + " has exactly " + format.digits + " digits.");
        }
        if (!checkDigitOk(code)) {
            return Result.refused("The last digit of this " + name(format)
                    + " barcode does not match the others. Check it was typed or scanned correctly.");
        }
        return Result.ok(new Checked(code, format));
    }

    /** All digits of a length no barcode format has (6, 7, 9, 10, 11 or 14), or a number in scientific notation. */
    static boolean spreadsheetDamaged(String code) {
        if (SCIENTIFIC.matcher(code).matches()) {
            return true;
        }
        if (!code.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return switch (code.length()) {
            case 6, 7, 9, 10, 11, 14 -> true;
            default -> false;
        };
    }

    static Format inferred(String code) {
        if (code.chars().allMatch(Character::isDigit)) {
            switch (code.length()) {
                case 13:
                    return Format.EAN13;
                case 12:
                    return Format.UPCA;
                case 8:
                    return Format.EAN8;
                default:
                    break;
            }
        }
        return Format.CODE128;
    }

    /**
     * The GS1 check digit (EAN-13, EAN-8 and UPC-A share it): from the right, not counting the check digit, weights 3
     * and 1 alternate; the check digit brings the sum to a multiple of ten.
     */
    static boolean checkDigitOk(String digits) {
        int sum = 0;
        int n = digits.length();
        for (int i = n - 2, w = 3; i >= 0; i--, w = 4 - w) {
            sum += (digits.charAt(i) - '0') * w;
        }
        return (10 - sum % 10) % 10 == digits.charAt(n - 1) - '0';
    }

    /** The key a code is unique and looked up under; the database computes the same (V90 lookup_key). */
    static String key(String code) {
        String c = code.strip();
        return c.length() == 12 && c.chars().allMatch(Character::isDigit) ? "0" + c : c;
    }

    static String generated(long n) {
        return GENERATED_PREFIX + String.format("%08d", n);
    }

    private static String name(Format f) {
        return switch (f) {
            case EAN13 -> "EAN-13";
            case EAN8 -> "EAN-8";
            case UPCA -> "UPC-A";
            case CODE128 -> "Code 128";
        };
    }

    record Result(Checked checked, String problem) {
        static Result ok(Checked c) {
            return new Result(c, null);
        }

        static Result refused(String problem) {
            return new Result(null, problem);
        }

        boolean ok() {
            return checked != null;
        }
    }
}
