package com.rincoltech.bms.core.tenancy.internal;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tenant host pattern of chapter 7 section 7.2, for example
 * {@code {slug}-bms-staging.rincoltech.com}: exactly one {@code {slug}}, inside the leftmost DNS
 * label, so every tenant host is one label directly under the zone and one wildcard edge
 * certificate ({@code *.rincoltech.com}) covers them all. A host matches only when it is exactly
 * that label under exactly that zone, compared case-insensitively; a trailing dot, a port, extra
 * labels on either side or a host that merely contains the pattern carry no slug.
 */
final class TenantHostPattern {

    static final String PLACEHOLDER = "{slug}";

    /** FR-TEN-02: lower case letters, digits and hyphens, 3 to 63 characters, no edge hyphen. */
    static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$");

    static final Set<String> RESERVED_SLUGS = Set.of("www", "api", "app", "admin", "static", "mail");

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern LABEL_PART = Pattern.compile("^[a-z0-9-]*$");

    private final String pattern;
    private final String prefix;
    private final String suffix;
    private final int labelRest;

    private TenantHostPattern(String pattern, String prefix, String suffix, int labelRest) {
        this.pattern = pattern;
        this.prefix = prefix;
        this.suffix = suffix;
        this.labelRest = labelRest;
    }

    /** Parses and validates a pattern; the message names what is wrong. */
    static TenantHostPattern parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("bms.tenancy.tenant-host-pattern (BMS_TENANT_HOST_PATTERN) must be set");
        }
        String pattern = raw.trim().toLowerCase(Locale.ROOT);
        int at = pattern.indexOf(PLACEHOLDER);
        if (at < 0 || pattern.indexOf(PLACEHOLDER, at + 1) >= 0) {
            throw invalid(raw, "it must contain exactly one " + PLACEHOLDER);
        }
        String[] labels = pattern.split("\\.", -1);
        if (labels.length < 2) {
            throw invalid(raw, "the slug label must sit under a zone, for example {slug}-bms.example.com");
        }
        String first = labels[0];
        if (!first.contains(PLACEHOLDER)) {
            throw invalid(raw, PLACEHOLDER + " must be in the leftmost label, so the host is one label under the zone");
        }
        String before = first.substring(0, first.indexOf(PLACEHOLDER));
        String after = first.substring(first.indexOf(PLACEHOLDER) + PLACEHOLDER.length());
        if (!LABEL_PART.matcher(before).matches()
                || !LABEL_PART.matcher(after).matches()
                || before.startsWith("-")
                || after.endsWith("-")) {
            throw invalid(raw, "the slug label may add only letters, digits and inner hyphens around " + PLACEHOLDER);
        }
        // The shortest slug (3 characters) must still leave a legal label of at most 63.
        if (before.length() + after.length() + 3 > 63) {
            throw invalid(raw, "the slug label is longer than 63 characters");
        }
        for (int i = 1; i < labels.length; i++) {
            if (!LABEL.matcher(labels[i]).matches()) {
                throw invalid(raw, "'" + labels[i] + "' is not a valid DNS label of the zone");
            }
        }
        String zone = pattern.substring(first.length());
        return new TenantHostPattern(pattern, before, after + zone, before.length() + after.length());
    }

    private static IllegalArgumentException invalid(String raw, String why) {
        return new IllegalArgumentException(
                "bms.tenancy.tenant-host-pattern (BMS_TENANT_HOST_PATTERN) '" + raw + "' is invalid: " + why);
    }

    /** FR-TEN-02 plus: the slug's host label stays within 63 characters under this pattern. */
    boolean isValidSlug(String slug) {
        return slug != null
                && SLUG.matcher(slug).matches()
                && !RESERVED_SLUGS.contains(slug)
                && slug.length() + labelRest <= 63;
    }

    /** The slug when the host is exactly the pattern's host for a valid slug. */
    Optional<String> slugFromHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.length() <= prefix.length() + suffix.length() || !h.startsWith(prefix) || !h.endsWith(suffix)) {
            return Optional.empty();
        }
        String slug = h.substring(prefix.length(), h.length() - suffix.length());
        return isValidSlug(slug) ? Optional.of(slug) : Optional.empty();
    }

    /** The host of a tenant, for links; the slug must be valid. */
    String hostFor(String slug) {
        if (!isValidSlug(slug)) {
            throw new IllegalArgumentException("not a valid slug: " + slug);
        }
        return prefix + slug + suffix;
    }

    @Override
    public String toString() {
        return pattern;
    }
}
