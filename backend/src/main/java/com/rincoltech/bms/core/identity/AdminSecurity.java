package com.rincoltech.bms.core.identity;

import java.util.List;
import java.util.UUID;

/**
 * What a sensitive tenant action needs from identity (issue #233): a second-factor re-entry from the signed-in user (step-up)
 * and the other holders of a permission, to tell them what changed. Both run in the caller's request and are bound to the
 * request's tenant; neither takes a tenant id.
 */
public interface AdminSecurity {

    /**
     * Proves the signed-in user is still the person who enrolled the second factor: the current TOTP code of their own
     * enrolment, each code usable once. Refuses with 422 {@code invalid_code} (counted toward the account lockout) or 409
     * {@code mfa_not_enrolled}. Must be called before the transaction of the action, not inside it.
     */
    void requireTotp(String code);

    /**
     * Active staff users who hold the permission through a current role, with their email, **the signed-in user included**: a
     * business with one administrator must still be told when its account was changed by a hijacked session.
     */
    List<Holder> holders(String permission);

    /** The full name of the signed-in user, for a notice that names who made a change; empty when there is none. */
    String actorName();

    record Holder(UUID userId, String email) {}
}
