package com.rincoltech.bms.core.tenancy;

/**
 * Host rules of chapter 7 section 7.2: the platform API is served only on the configured platform
 * host ({@code BMS_PLATFORM_HOST}), tenants on the tenant host pattern
 * ({@code BMS_TENANT_HOST_PATTERN}, for example {@code {slug}-bms-staging.rincoltech.com}).
 */
public interface PlatformHost {

    /** True only for the platform host, compared case-insensitively. */
    boolean servesPlatform(String host);

    /**
     * FR-TEN-02: lower case letters, digits and hyphens, 3 to 63 characters, not starting or
     * ending with a hyphen, not a reserved label, and short enough that its host label under the
     * tenant host pattern stays within 63 characters.
     */
    boolean isValidSlug(String slug);

    /** Origin of the one-time links sent to users: the tenant's host under the pattern. */
    String tenantOrigin(String slug);

    /**
     * Origin of links to the platform host (sign-up, applicant page, operator portal), built from
     * {@code BMS_PLATFORM_HOST} and never from a request header.
     */
    String platformOrigin();
}
