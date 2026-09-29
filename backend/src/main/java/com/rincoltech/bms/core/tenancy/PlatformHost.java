package com.rincoltech.bms.core.tenancy;

/**
 * Host rules for the platform API (chapter 7 section 7.2): it is served on
 * {@code app.<base domain>}, never on a tenant host.
 */
public interface PlatformHost {

    /** True unless the host names a tenant ({@code <slug>.<base domain>}). */
    boolean servesPlatform(String host);

    /**
     * FR-TEN-02: lower case letters, digits and hyphens, 3 to 63 characters, not starting or
     * ending with a hyphen, and not a reserved label.
     */
    boolean isValidSlug(String slug);

    /** The one-time links sent to users: {@code https://<slug>.<base domain>}. */
    String tenantOrigin(String slug);
}
