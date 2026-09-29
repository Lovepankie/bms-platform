package com.rincoltech.bms.core.tenancy;

/**
 * Host rules for the platform API (chapter 7 section 7.2): it is served on
 * {@code app.<base domain>}, never on a tenant host.
 */
public interface PlatformHost {

    /** True unless the host names a tenant ({@code <slug>.<base domain>}). */
    boolean servesPlatform(String host);

    /** The one-time links sent to users: {@code https://<slug>.<base domain>}. */
    String tenantOrigin(String slug);
}
