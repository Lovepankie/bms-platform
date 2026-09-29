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
    /** Request attribute holding the resolved tenant's status, {@code active} or {@code suspended}. */
    static final String STATUS_ATTRIBUTE = "bms.tenant.status";

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
        // Tenant-free paths: health, version, the contract document and the platform API
        // (/api/v1/platform, served on app.<base domain>, which resolves no tenant).
        return !path.startsWith("/api/v1/")
                || path.startsWith("/api/v1/openapi.json")
                || path.equals("/api/v1/platform")
                || path.startsWith("/api/v1/platform/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Resolved> tenant = slugFrom(request).flatMap(this::resolve);
        if (tenant.isEmpty()) {
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
        TenantContext.bind(tenant.get().id());
        request.setAttribute(STATUS_ATTRIBUTE, tenant.get().status());
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

    private Optional<Resolved> resolve(String slug) {
        // app_resolve_tenant_status is a SECURITY DEFINER function of chapter 6 section 6.3.2: it
        // returns the id and status of an active or suspended tenant with this slug, and nothing
        // else. A suspended tenant is served read only (FR-TEN-06).
        return jdbc.sql("SELECT id, status FROM app_resolve_tenant_status(?)")
                .param(slug)
                .query((rs, n) -> new Resolved(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional();
    }

    private record Resolved(UUID id, String status) {}
}
