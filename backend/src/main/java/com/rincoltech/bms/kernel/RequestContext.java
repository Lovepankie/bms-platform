package com.rincoltech.bms.kernel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request id and client address for the current request (NFR-OBS-01, NFR-OBS-02). The id is
 * returned in {@code X-Request-Id}, put in every error body and log line, and stored on audit
 * rows. Runs first, before tenant resolution, so even an {@code unknown_tenant} error carries it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContext extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();

    /** The current request id, or {@code null} outside a request (for example in a job). */
    public static String requestId() {
        return REQUEST_ID.get();
    }

    public static String clientIp() {
        return CLIENT_IP.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
        REQUEST_ID.set(id);
        CLIENT_IP.set(request.getRemoteAddr());
        MDC.put("request_id", id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            REQUEST_ID.remove();
            CLIENT_IP.remove();
            MDC.remove("request_id");
        }
    }
}
