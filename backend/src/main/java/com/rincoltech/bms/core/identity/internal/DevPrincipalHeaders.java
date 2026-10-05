package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Principal.BranchScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The development authentication stub of chapter 7 section 7.4.3, active only with
 * {@code AUTH_MODE=dev}, which only the dev and test profiles accept. Reads the principal from
 * {@code X-Dev-User-Id}, {@code X-Dev-Kind}, {@code X-Dev-Permissions} (comma separated) and
 * {@code X-Dev-Branch-Ids} (comma separated UUIDs, or {@code *} for all branches). The optional
 * {@code X-Dev-Scopes} gives single permissions their own branch scope, as a signed-in user with
 * several roles has (ADR-017): semicolon separated {@code permission=*} or {@code
 * permission=uuid,uuid} entries, which replace that permission's scope or add the permission.
 * Malformed or missing headers leave the request unauthenticated. A bearer token always takes
 * precedence.
 */
final class DevPrincipalHeaders {

    private DevPrincipalHeaders() {}

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
            Principal uniform = Principal.uniform(UUID.fromString(userId), kind, permissions, all, branchIds);
            String scopes = request.getHeader("X-Dev-Scopes");
            if (scopes == null || scopes.isBlank()) {
                return Optional.of(uniform);
            }
            Map<String, BranchScope> byPermission = new LinkedHashMap<>(uniform.scopes());
            for (String entry : scopes.split(";")) {
                String[] parts = entry.split("=", 2);
                if (parts.length != 2 || parts[0].isBlank()) {
                    return Optional.empty();
                }
                boolean every = parts[1].trim().equals("*");
                byPermission.put(
                        parts[0].trim(),
                        new BranchScope(
                                every,
                                every
                                        ? Set.of()
                                        : split(parts[1]).stream()
                                                .map(UUID::fromString)
                                                .collect(Collectors.toSet())));
            }
            return Optional.of(new Principal(uniform.userId(), kind, null, byPermission));
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
