package com.rincoltech.bms.core.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The bound tenant's letterhead for generated documents (ADR-034, FR-TEN-08): its display name,
 * brand colour with the readable text colour on it, receipt footer and logo. A tenant with no
 * readable colour of its own gets the platform default, so every document has a brand colour.
 */
public interface Letterheads {

    Letterhead current();

    /**
     * The letterhead of a document made at one shop (issue #461, ADR-068): {@link #current()} with the shop's own logo in
     * place of the business logo when it has one. A null id, a branch with no logo, or a logo storage cannot read keeps the
     * business logo.
     */
    Letterhead forBranch(UUID branchId);

    /** As {@link #forBranch} by the branch code, which never changes: what an issued document's snapshot keeps. */
    Letterhead forBranchCode(String code);

    /**
     * The same-origin path of the bound tenant's servable logo, versioned by its document id
     * ({@code /api/v1/branding/logo?v=<id>}, as the public branding route gives it), when one is set; read from the
     * database only. For links to the logo that leave the app, such as the header of an email (issue #396).
     */
    Optional<String> logoPath();

    /**
     * @param brandColour {@code #RRGGBB}, the tenant's when it passes the contrast rule, else the default
     * @param textOnBrand the text colour that passes the contrast rule on {@code brandColour}
     * @param logo the stored logo (PNG or JPEG, re-encoded at upload), when there is one and storage answers
     */
    record Letterhead(
            String displayName, String brandColour, String textOnBrand, String receiptFooter, Optional<Logo> logo) {}

    record Logo(String contentType, byte[] bytes) {}
}
