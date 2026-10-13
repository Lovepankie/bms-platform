package com.rincoltech.bms.core.tenancy;

import java.util.Optional;

/**
 * The bound tenant's letterhead for generated documents (ADR-034, FR-TEN-08): its display name,
 * brand colour with the readable text colour on it, receipt footer and logo. A tenant with no
 * readable colour of its own gets the platform default, so every document has a brand colour.
 */
public interface Letterheads {

    Letterhead current();

    /**
     * @param brandColour {@code #RRGGBB}, the tenant's when it passes the contrast rule, else the default
     * @param textOnBrand the text colour that passes the contrast rule on {@code brandColour}
     * @param logo the stored logo (PNG or JPEG, re-encoded at upload), when there is one and storage answers
     */
    record Letterhead(
            String displayName, String brandColour, String textOnBrand, String receiptFooter, Optional<Logo> logo) {}

    record Logo(String contentType, byte[] bytes) {}
}
