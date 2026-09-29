package com.rincoltech.bms.kernel;

import java.util.regex.Pattern;

/**
 * An amount in integer minor units with its ISO 4217 currency (ADR-004). There is no floating
 * point anywhere on this type; UGX has exponent 0, so {@code Money.of(150000, "UGX")} is 150,000
 * shillings.
 */
public record Money(long minor, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Money {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be three upper case letters");
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
