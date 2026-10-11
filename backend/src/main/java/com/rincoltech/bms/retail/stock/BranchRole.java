package com.rincoltech.bms.retail.stock;

import java.util.Optional;

/**
 * What a retail branch may do (ADR-033, docs/specs/trade-capabilities.md section 2): a
 * {@code store} never sells, a {@code shop} and a {@code both} branch do. A role only removes
 * what a branch cannot do; it never grants a permission or widens a user's branch scope.
 */
public enum BranchRole {
    STORE("store", false),
    SHOP("shop", true),
    BOTH("both", true);

    private final String wire;
    private final boolean sells;

    BranchRole(String wire, boolean sells) {
        this.wire = wire;
        this.sells = sells;
    }

    public String wire() {
        return wire;
    }

    public boolean sells() {
        return sells;
    }

    public static Optional<BranchRole> parse(String wire) {
        for (BranchRole r : values()) {
            if (r.wire.equals(wire)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
