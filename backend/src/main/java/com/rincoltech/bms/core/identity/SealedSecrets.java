package com.rincoltech.bms.core.identity;

/**
 * Re-sealing of the second-factor seeds after the application data key is rotated (issue #233). A data key may be retired
 * only when nothing is sealed with it any more: the payment account job calls these together with its own rows, so one run
 * covers every secret that uses {@link DataKeys}. Each method runs in the caller's transaction.
 */
public interface SealedSecrets {

    /** Re-seals the TOTP seeds of the bound tenant's users; returns the number of boxes changed. */
    int resealBoundTenant();

    /** Re-seals the platform operators' TOTP seeds (no tenant); returns the number of boxes changed. */
    int resealPlatformUsers();

    /** Boxes of the bound tenant not sealed with the current key. */
    long remainingBoundTenant();

    /** Platform operators' boxes not sealed with the current key. */
    long remainingPlatformUsers();
}
