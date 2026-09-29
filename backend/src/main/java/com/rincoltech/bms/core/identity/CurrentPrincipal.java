package com.rincoltech.bms.core.identity;

import com.rincoltech.bms.kernel.ApiException;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** The principal of the current request, set by the authentication filter for one request. */
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

    static void set(Principal principal) {
        CURRENT.set(principal);
    }

    static void clear() {
        CURRENT.remove();
    }
}
