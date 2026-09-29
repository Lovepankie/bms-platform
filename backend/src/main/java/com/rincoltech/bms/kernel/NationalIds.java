package com.rincoltech.bms.kernel;

import java.util.Optional;
import java.util.regex.Pattern;

/** Uganda NIN normalisation and validation (FR-MEM-03): upper case, no spaces, {@code ^C[MF][A-Z0-9]{12}$}. */
public final class NationalIds {

    private static final Pattern NIN = Pattern.compile("^C[MF][A-Z0-9]{12}$");

    private NationalIds() {}

    public static Optional<String> normaliseNin(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.replaceAll("\\s", "").toUpperCase();
        return NIN.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }
}
