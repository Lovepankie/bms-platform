package com.rincoltech.bms.core.notifications.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which addresses this server will POST a push message to (ADR-050). A subscription's endpoint is typed by a browser but sent
 * by whoever calls the API, so it is a server-side request forgery risk: it must be https, on the default port, without
 * credentials, a name and not an address, and a host of a known push service (a host equal to an entry or a subdomain of it).
 * It is checked when a device subscribes and again before every send.
 */
final class PushEndpoints {

    /** Chrome and Edge (FCM), Firefox (autopush), Safari and iOS web apps (APNs), Windows (WNS). */
    static final List<String> BUILT_IN =
            List.of("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    private final List<String> hosts;

    PushEndpoints(String extraHosts) {
        List<String> all = new ArrayList<>(BUILT_IN);
        if (extraHosts != null) {
            for (String host : extraHosts.split(",")) {
                String clean = host.trim().toLowerCase(Locale.ROOT);
                if (!clean.isEmpty()) {
                    all.add(clean);
                }
            }
        }
        this.hosts = List.copyOf(all);
    }

    boolean allowed(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme())
                || host == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        // A name has a letter in it; this refuses 127.0.0.1, [::1] and every other literal address outright.
        if (name.chars().noneMatch(Character::isLetter)) {
            return false;
        }
        return hosts.stream().anyMatch(h -> name.equals(h) || name.endsWith("." + h));
    }
}
