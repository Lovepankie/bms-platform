package com.rincoltech.bms.retail.cashbook.internal;

/**
 * The flag of a banking day (FR-RET-22; ADR-022 decision 10): {@code not_banked} when something was
 * expected and nothing was banked, else the difference between banked and expected is a
 * {@code shortfall} or a {@code surplus} beyond the tenant tolerance, or {@code ok} within it.
 */
final class CashFlag {

    static final String OK = "ok";
    static final String SHORTFALL = "shortfall";
    static final String SURPLUS = "surplus";
    static final String NOT_BANKED = "not_banked";

    private CashFlag() {}

    static String of(long expectedMinor, long bankedMinor, long toleranceMinor) {
        if (expectedMinor > 0 && bankedMinor <= 0) {
            return NOT_BANKED;
        }
        long difference = bankedMinor - expectedMinor;
        if (difference < -toleranceMinor) {
            return SHORTFALL;
        }
        return difference > toleranceMinor ? SURPLUS : OK;
    }
}
