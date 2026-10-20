package com.rincoltech.bms.core.identity;

/**
 * The step-up for a signed-in user who has no second factor enrolled (ADR-042): their own current password, where
 * {@link AdminSecurity#requireTotp} asks for the authenticator's code. Two-step sign-in is optional, so a sensitive action must not
 * lock out an admin who has none; a stolen session or token does not give the password.
 */
public interface PasswordStepUp {

    /**
     * Checks the signed-in user's own password. A wrong one is counted on the sign-in counter and lock exactly as at sign-in (422
     * {@code invalid_password}; the fifth locks the account, 423 {@code account_locked}); the fifth wrong password a session makes
     * here also ends that session; a caller with no active staff account is refused (401). Must be called before the transaction of the action. The password is never logged, audited or echoed.
     */
    void requirePassword(String password);
}
