package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import com.rincoltech.bms.kernel.Problems;
import com.rincoltech.bms.kernel.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates every {@code /api/v1} request that carries {@code Authorization: Bearer}, in the
 * order of chapter 7 section 7.4.2: signature and expiry; the token's tenant equals the host's
 * ({@code tenant_mismatch}); the session is not revoked ({@code session_revoked}, a primary key
 * lookup on every request, FR-IAM-08); the user is active. Permissions and branch scope are then
 * loaded from the role assignments, never from the token.
 *
 * <p>Platform paths ({@code /api/v1/platform}) are served only on a host that names no tenant and
 * accept only platform tokens. Without a bearer token the request stays anonymous, unless the
 * development header stub is on ({@code AUTH_MODE=dev}, dev and test profiles only).
 */
class AuthenticationFilter extends OncePerRequestFilter {

    static final String PLATFORM_PREFIX = "/api/v1/platform";
    static final Set<String> PLATFORM_PERMISSIONS = Set.of("platform.tenants.read", "platform.tenants.manage");

    private final AccessTokens tokens;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final Grants grants;
    private final PlatformHost hosts;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final boolean devStub;

    AuthenticationFilter(
            AccessTokens tokens,
            JdbcClient jdbc,
            TransactionTemplate readOnly,
            Grants grants,
            PlatformHost hosts,
            ObjectMapper mapper,
            BusinessClock clock,
            boolean devStub) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.readOnly = readOnly;
        this.grants = grants;
        this.hosts = hosts;
        this.mapper = mapper;
        this.clock = clock;
        this.devStub = devStub;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/openapi.json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean platform = isPlatformPath(request.getRequestURI());
        if (platform && !hosts.servesPlatform(request.getServerName())) {
            Problems.write(response, mapper, Problems.of(ApiException.notFound()));
            return;
        }
        try {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                CurrentPrincipal.set(authenticate(authorization.substring(7).trim(), platform));
            } else if (devStub && !platform) {
                DevPrincipalHeaders.parse(request).ifPresent(CurrentPrincipal::set);
            }
        } catch (ApiException e) {
            CurrentPrincipal.clear();
            Problems.write(response, mapper, Problems.of(e));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentPrincipal.clear();
        }
    }

    static boolean isPlatformPath(String path) {
        return path.equals(PLATFORM_PREFIX) || path.startsWith(PLATFORM_PREFIX + "/");
    }

    private Principal authenticate(String token, boolean platform) {
        AccessTokens.Claims claims;
        try {
            claims = tokens.verify(token, AccessTokens.PURPOSE_ACCESS, clock.now());
        } catch (AccessTokens.Invalid e) {
            throw unauthorized(e.code());
        }
        if (platform) {
            if (!"platform".equals(claims.kind())) {
                throw unauthorized("unauthenticated");
            }
            return readOnly.execute(status -> platformPrincipal(claims));
        }
        if (!"staff".equals(claims.kind())) {
            throw unauthorized("unauthenticated");
        }
        Optional<UUID> hostTenant = TenantContext.current();
        if (hostTenant.isEmpty() || !hostTenant.get().equals(claims.tenantId())) {
            throw unauthorized("tenant_mismatch");
        }
        return readOnly.execute(status -> staffPrincipal(claims));
    }

    private Principal staffPrincipal(AccessTokens.Claims claims) {
        boolean live = jdbc.sql("""
                                SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id
                                 WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND u.status = 'active'
                                """)
                        .params(claims.sessionId(), claims.userId())
                        .query(Long.class)
                        .single()
                > 0;
        if (!live) {
            throw unauthorized("session_revoked");
        }
        return new Principal(claims.userId(), "staff", claims.sessionId(), grants.scopes(claims.userId()));
    }

    private Principal platformPrincipal(AccessTokens.Claims claims) {
        boolean live = jdbc.sql("""
                                SELECT count(*) FROM platform_sessions s JOIN platform_users u ON u.id = s.platform_user_id
                                 WHERE s.id = ? AND s.platform_user_id = ? AND s.revoked_at IS NULL AND u.is_active
                                """)
                        .params(claims.sessionId(), claims.userId())
                        .query(Long.class)
                        .single()
                > 0;
        if (!live) {
            throw unauthorized("session_revoked");
        }
        BranchScope none = new BranchScope(true, Set.of());
        Map<String, BranchScope> scopes = new java.util.LinkedHashMap<>();
        PLATFORM_PERMISSIONS.stream().sorted().forEach(p -> scopes.put(p, none));
        return new Principal(claims.userId(), "platform", claims.sessionId(), scopes);
    }

    private static ApiException unauthorized(String code) {
        String detail = switch (code) {
            case "token_expired" -> "The access token expired; refresh the session.";
            case "tenant_mismatch" -> "The token belongs to another tenant.";
            case "session_revoked" -> "The session has ended; sign in again.";
            default -> "Sign in to use this endpoint.";
        };
        return new ApiException(HttpStatus.UNAUTHORIZED, code, "Not authenticated", detail);
    }
}
