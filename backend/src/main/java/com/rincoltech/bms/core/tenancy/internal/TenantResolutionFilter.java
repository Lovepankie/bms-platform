package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.Problems;
import com.rincoltech.bms.kernel.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the tenant of every {@code /api/v1} request from the host (chapter 7 section 7.2) and
 * binds it to the thread for exactly this request. No slug, an unknown slug or an inactive
 * tenant stops the request here with 404 {@code unknown_tenant}: nothing downstream ever runs
 * without a tenant, and nothing downstream can choose a different one.
 */
class TenantResolutionFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$");

    private final TenancyProperties properties;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    TenantResolutionFilter(TenancyProperties properties, JdbcClient jdbc, ObjectMapper mapper) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Tenant-free paths: health, version and the contract document. The platform console
        // (/api/v1/platform, on app.<base domain>) will get its own resolution when it lands.
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/openapi.json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> tenantId = slugFrom(request).flatMap(this::resolve);
        if (tenantId.isEmpty()) {
            Problems.write(
                    response,
                    mapper,
                    Problems.of(
                            HttpStatus.NOT_FOUND,
                            "unknown_tenant",
                            "Unknown tenant",
                            "No active tenant is served at this address."));
            return;
        }
        TenantContext.bind(tenantId.get());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    Optional<String> slugFrom(HttpServletRequest request) {
        Optional<String> fromHost = slugFromHost(request.getServerName());
        if (fromHost.isPresent()) {
            return fromHost;
        }
        if (properties.allowTenantHeader()) {
            String header = request.getHeader(TENANT_HEADER);
            if (header != null && SLUG.matcher(header).matches()) {
                return Optional.of(header);
            }
        }
        return Optional.empty();
    }

    Optional<String> slugFromHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host.toLowerCase();
        String suffix = "." + properties.baseDomain();
        if (!h.endsWith(suffix)) {
            return Optional.empty();
        }
        String label = h.substring(0, h.length() - suffix.length());
        if (label.contains(".")
                || TenancyProperties.RESERVED_LABELS.contains(label)
                || !SLUG.matcher(label).matches()) {
            return Optional.empty();
        }
        return Optional.of(label);
    }

    private Optional<UUID> resolve(String slug) {
        // app_resolve_tenant is the SECURITY DEFINER function of chapter 6 section 6.3.2: it
        // returns the id of an active tenant with this slug, or NULL, and nothing else.
        return jdbc.sql("SELECT app_resolve_tenant(?)")
                .param(slug)
                .query(UUID.class)
                .optional();
    }
}
