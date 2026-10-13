package com.rincoltech.bms.kernel;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * Responses that carry a member's financial documents, or a link that fetches one, must not be kept by
 * a browser, a shared device or a proxy: {@code Cache-Control: no-store} and, for old clients,
 * {@code Pragma: no-cache}.
 */
public final class NoStore {

    private NoStore() {}

    public static void apply(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
