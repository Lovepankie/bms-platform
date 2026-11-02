package com.rincoltech.bms.core.tenancy;

import java.util.Optional;

/**
 * A module's own links on the short link host (issue #760, ADR-116): a name no shop has may be one of these, such as the
 * link to one sale's receipt. The short link endpoint asks each of them after the shops (ADR-108 decision 3), so this
 * package never names a vertical.
 */
public interface ShortLinkTarget {

    /** What the name opens, or empty when it is not one of this module's links. */
    Optional<Answer> follow(String name);

    /**
     * Either the path on a shop's host the link opens ({@code slug} and {@code path}), or, for a link of this kind that is
     * wrong or has expired, a plain HTML page to show instead ({@code page}).
     */
    record Answer(String slug, String path, String page) {

        public static Answer to(String slug, String path) {
            return new Answer(slug, path, null);
        }

        public static Answer gone(String page) {
            return new Answer(null, null, page);
        }
    }
}
