package com.rincoltech.bms.core.identity;

/**
 * What a sensitive platform action needs from identity (ADR-042): a second-factor re-entry from the signed-in platform operator
 * (step-up), the platform's counterpart of {@link AdminSecurity#requireTotp} for a tenant's staff. A stolen operator token alone
 * must not be able to reset a tenant admin's second factor or sign-in.
 */
public interface OperatorSecurity {

    /**
     * Proves the signed-in operator is still the person who enrolled the second factor: the current TOTP code of their own
     * enrolment, each code usable once, a wrong code counted on the operator's step-up counter (five lock it for 15 minutes and
     * end the operator's sessions). Refuses with 422 {@code invalid_mfa_code} or 409 {@code mfa_not_enrolled}. Must be called
     * before the transaction of the action, not inside it.
     */
    void requireTotp(String code);
}
