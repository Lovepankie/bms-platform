package com.rincoltech.bms.core.payments.internal;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The merchant reference of an order (ADR-035 decision 2, spec 3.5): {@code BMS-<env>-<32 hex characters of the intent id>},
 * 39 characters from letters, digits and dashes. The intent id is a random version 4 UUID, so a reference cannot be
 * guessed and carries no tenant, amount or person; the environment code keeps one merchant account shared by two
 * environments from colliding; the database holds it unique across every tenant, and the tenant is found from the
 * reference, never the other way round. {@code parse} is the cheap shape check that stops a made-up value reaching the
 * database or the provider.
 */
final class MerchantReference {

    static final Pattern SHAPE = Pattern.compile("^BMS-(ST|PR|DV)-([0-9a-f]{32})$");

    private MerchantReference() {}

    /** {@code ST} on staging, {@code PR} in production, {@code DV} anywhere else (a laptop, a test). */
    static String envCode(String environment) {
        String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        return switch (env) {
            case "staging" -> "ST";
            case "production" -> "PR";
            default -> "DV";
        };
    }

    static String of(UUID intentId, String environment) {
        return "BMS-" + envCode(environment) + "-" + HexFormat.of().formatHex(bytes(intentId));
    }

    /** The intent id inside a reference of this environment, or empty for any other shape or environment. */
    static Optional<UUID> parse(String reference, String environment) {
        if (reference == null) {
            return Optional.empty();
        }
        var m = SHAPE.matcher(reference);
        if (!m.matches() || !m.group(1).equals(envCode(environment))) {
            return Optional.empty();
        }
        byte[] raw = HexFormat.of().parseHex(m.group(2));
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (raw[i] & 0xff);
            low = (low << 8) | (raw[8 + i] & 0xff);
        }
        return Optional.of(new UUID(high, low));
    }

    private static byte[] bytes(UUID id) {
        byte[] out = new byte[16];
        long high = id.getMostSignificantBits();
        long low = id.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) high;
            high >>>= 8;
            out[8 + i] = (byte) low;
            low >>>= 8;
        }
        return out;
    }
}
