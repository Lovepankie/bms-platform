package com.rincoltech.bms.lending.collateral;

import java.util.UUID;

/**
 * Collateral items from a loan register imported by the template {@code lending_loans_v1} (issue #136, chapter 13 section
 * 13.6.8): created {@code pledged}, with no estimated value and the note "imported; verify custody and value", so the
 * collateral register lists them for verification. Runs in the caller's transaction, bound to the business.
 */
public interface CollateralImports {

    /** The register's normal form of a reference: every space removed, upper case; null for none. */
    static String normalise(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return reference.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether an item of this type with this normalised reference is still pledged, in custody or seized at the business:
     * a plate already on another open loan ({@code COLLATERAL_PLEDGED_TWICE}).
     */
    boolean referenceHeld(String collateralType, String referenceNormalised);

    /**
     * Creates one item of a member, {@code pledged}. A reference another active item of its type already holds is not
     * repeated (the register allows one active item per type and reference): the item is then kept without it, the
     * reference in its description, and a vehicle so held becomes {@code other}. The item of a loan the register shows
     * repaid is created {@code released}: it keeps its reference and holds no other pledge back. Returns its id.
     */
    UUID create(
            UUID branchId,
            UUID memberId,
            String collateralType,
            String description,
            String reference,
            String currency,
            boolean released,
            UUID createdBy);
}
