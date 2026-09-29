package com.rincoltech.bms.core.identity;

import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The development authentication stub of chapter 7 section 7.4.3, active only with
 * {@code AUTH_MODE=dev}, which only the dev and test profiles accept. Reads the principal from
 * {@code X-Dev-User-Id}, {@code X-Dev-Kind}, {@code X-Dev-Permissions} (comma separated) and
 * {@code X-Dev-Branch-Ids} (comma separated UUIDs, or {@code *} for all branches). Malformed or
 * missing headers leave the request unauthenticated.
 */
class DevAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        parse(request).ifPresent(CurrentPrincipal::set);
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentPrincipal.clear();
        }
    }

    static Optional<Principal> parse(HttpServletRequest request) {
        try {
            String userId = request.getHeader("X-Dev-User-Id");
            if (userId == null) {
                return Optional.empty();
            }
            String kind = Optional.ofNullable(request.getHeader("X-Dev-Kind")).orElse("staff");
            if (!kind.equals("staff") && !kind.equals("member")) {
                return Optional.empty();
            }
            Set<String> permissions = split(request.getHeader("X-Dev-Permissions"));
            String branches =
                    Optional.ofNullable(request.getHeader("X-Dev-Branch-Ids")).orElse("");
            boolean all = branches.trim().equals("*");
            Set<UUID> branchIds = all
                    ? Set.of()
                    : split(branches).stream().map(UUID::fromString).collect(Collectors.toSet());
            return Optional.of(Principal.uniform(UUID.fromString(userId), kind, permissions, all, branchIds));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Set<String> split(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
