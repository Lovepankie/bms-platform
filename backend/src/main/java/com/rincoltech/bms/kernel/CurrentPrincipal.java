package com.rincoltech.bms.kernel;

import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * The principal of the current request, set by the identity module's authentication filters for
 * one request. It lives in the kernel so that every module (the audit writer included) can read
 * it without depending on the identity module; only {@code core.identity} may set or clear it,
 * which {@code SecurityArchitectureTest} enforces.
 */
public final class CurrentPrincipal {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private CurrentPrincipal() {}

    public static Optional<Principal> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Principal require() {
        Principal principal = CURRENT.get();
        if (principal == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Not authenticated", "Sign in to use this endpoint.");
        }
        return principal;
    }

    /** Identity module only. */
    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    /** Identity module only. */
    public static void clear() {
        CURRENT.remove();
    }
}
