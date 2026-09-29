package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.internal.Accounts.Account;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.RequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, second factor, sessions (FR-IAM-04 to FR-IAM-08, FR-IAM-11; chapter 8 section 8.2).
 * One flow for tenant staff and platform operators; {@link Accounts} says where the accounts live.
 *
 * <p>Failures that must be remembered (the failed attempt counter, the lock, the audit row, a
 * family revoked for refresh token reuse) are written before the method throws; the methods do
 * not roll back on {@link ApiException}, so the answer is an error and the record commits.
 */
@Service
class AuthFlow {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    private final Passwords passwords;
    private final AccessTokens tokens;
    private final SecretBox box;
    private final BusinessClock clock;

    AuthFlow(Passwords passwords, AccessTokens tokens, SecretBox box, BusinessClock clock) {
        this.passwords = passwords;
        this.tokens = tokens;
        this.box = box;
        this.clock = clock;
    }

    enum Step {
        SIGNED_IN,
        MFA_REQUIRED,
        MFA_ENROLMENT_REQUIRED
    }

    /** What a step returns: tokens when signed in, an MFA token between the two factors. */
    record Outcome(Step step, String accessToken, String refreshToken, String mfaToken, List<String> recoveryCodes) {

        static Outcome mfa(Step step, String mfaToken) {
            return new Outcome(step, null, null, mfaToken, List.of());
        }
    }

    record Enrolment(String secret, String otpauthUri) {}

    @Transactional(noRollbackFor = ApiException.class)
    Outcome login(Accounts accounts, String login, String password, String issuer, String userAgent) {
        Instant now = clock.now();
        Account account = accounts.byLogin(login).orElse(null);
        if (account == null) {
            passwords.matches(password == null ? "" : password, null);
            accounts.audit("core.auth.sign_in_failed", null, Map.of("login", login == null ? "" : login));
            throw invalidCredentials();
        }
        if (account.lockedAt(now)) {
            accounts.audit("core.auth.sign_in_refused_locked", account.id(), Map.of());
            throw locked();
        }
        boolean ok = passwords.matches(password == null ? "" : password, account.passwordHash());
        if (!ok || !account.active()) {
            fail(accounts, account, now, "core.auth.sign_in_failed");
            throw invalidCredentials();
        }
        if (account.mfaEnabled()) {
            return Outcome.mfa(
                    Step.MFA_REQUIRED, tokens.mfa(account.id(), accounts.tenantId(), accounts.kind(), issuer, now));
        }
        if (accounts.mfaRequired(account.id())) {
            return Outcome.mfa(
                    Step.MFA_ENROLMENT_REQUIRED,
                    tokens.mfa(account.id(), accounts.tenantId(), accounts.kind(), issuer, now));
        }
        return signIn(accounts, account.id(), now, issuer, userAgent, List.of(), "password");
    }

    /** The second factor: a TOTP code or one unused recovery code (FR-IAM-06, FR-IAM-11). */
    @Transactional(noRollbackFor = ApiException.class)
    Outcome verifyMfa(Accounts accounts, String mfaToken, String code, String issuer, String userAgent) {
        Instant now = clock.now();
        Account account = accountOf(accounts, mfaToken, now);
        if (!account.mfaEnabled() || account.totpSecret() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "mfa_not_enrolled", "MFA not enrolled", "Enrol a second factor first.");
        }
        if (account.lockedAt(now)) {
            accounts.audit("core.auth.sign_in_refused_locked", account.id(), Map.of());
            throw locked();
        }
        String method;
        if (Secrets.looksLikeRecoveryCode(code)) {
            if (!accounts.useRecoveryCode(account.id(), Secrets.recoveryCodeHash(code), now)) {
                fail(accounts, account, now, "core.mfa.failed");
                throw invalidCode(HttpStatus.UNAUTHORIZED);
            }
            method = "recovery_code";
            accounts.audit(
                    "core.mfa.recovery_code_used",
                    account.id(),
                    Map.of("unused_codes_left", accounts.unusedRecoveryCodes(account.id())));
        } else {
            OptionalLong step = Totp.verify(box.open(account.totpSecret()), code, now, account.totpLastStep());
            if (step.isEmpty()) {
                fail(accounts, account, now, "core.mfa.failed");
                throw invalidCode(HttpStatus.UNAUTHORIZED);
            }
            accounts.setTotpStep(account.id(), step.getAsLong());
            method = "totp";
        }
        return signIn(accounts, account.id(), now, issuer, userAgent, List.of(), method);
    }

    /**
     * Starts TOTP enrolment for a signed-in user, or for one holding an MFA token after the
     * password step (forced enrolment, FR-IAM-06). The secret is shown once and kept encrypted.
     */
    @Transactional
    Enrolment startEnrolment(Accounts accounts, UUID userId) {
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (account.mfaEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "mfa_already_enrolled",
                    "MFA already enrolled",
                    "A second factor is already active; an admin can reset it.");
        }
        byte[] secret = Totp.newSecret();
        accounts.setPendingTotp(userId, box.seal(secret));
        accounts.audit("core.mfa.enrolment_started", userId, Map.of());
        return new Enrolment(Totp.base32(secret), Totp.uri(accounts.totpIssuer(), account.login(), secret));
    }

    @Transactional
    UUID userOfMfaToken(Accounts accounts, String mfaToken) {
        return accountOf(accounts, mfaToken, clock.now()).id();
    }

    /**
     * Confirms enrolment with a first code, activates the factor and issues ten recovery codes,
     * shown once. With {@code signIn} (the forced enrolment path) it also completes the sign-in.
     */
    @Transactional(noRollbackFor = ApiException.class)
    Outcome confirmEnrolment(
            Accounts accounts, UUID userId, String code, boolean signIn, String issuer, String userAgent) {
        Instant now = clock.now();
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (account.totpPendingSecret() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "mfa_enrolment_not_started",
                    "Enrolment not started",
                    "Start the enrolment to get a secret first.");
        }
        OptionalLong step = Totp.verify(box.open(account.totpPendingSecret()), code, now, null);
        if (step.isEmpty()) {
            fail(accounts, account, now, "core.mfa.failed");
            throw invalidCode(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        accounts.enableTotp(userId, account.totpPendingSecret(), step.getAsLong());
        List<String> codes = issueRecoveryCodes(accounts, userId);
        accounts.audit("core.mfa.enrolled", userId, Map.of("recovery_codes_issued", codes.size()));
        if (signIn) {
            return signIn(accounts, userId, now, issuer, userAgent, codes, "totp_enrolment");
        }
        return new Outcome(Step.SIGNED_IN, null, null, null, codes);
    }

    /** New recovery codes replace every old one; needs a current TOTP code (FR-IAM-11). */
    @Transactional(noRollbackFor = ApiException.class)
    List<String> regenerateRecoveryCodes(Accounts accounts, UUID userId, String code) {
        Instant now = clock.now();
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (!account.mfaEnabled() || account.totpSecret() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "mfa_not_enrolled", "MFA not enrolled", "Enrol a second factor first.");
        }
        OptionalLong step = Totp.verify(box.open(account.totpSecret()), code, now, account.totpLastStep());
        if (step.isEmpty()) {
            fail(accounts, account, now, "core.mfa.failed");
            throw invalidCode(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        accounts.setTotpStep(userId, step.getAsLong());
        List<String> codes = issueRecoveryCodes(accounts, userId);
        accounts.audit("core.mfa.recovery_codes_regenerated", userId, Map.of("recovery_codes_issued", codes.size()));
        return codes;
    }

    /**
     * Rotates a refresh token (FR-IAM-07). A token that was already rotated is a reuse: the whole
     * family is revoked, audited, and the caller signs in again.
     */
    @Transactional(noRollbackFor = ApiException.class)
    Outcome refresh(Accounts accounts, String refreshToken, String issuer, String userAgent) {
        Instant now = clock.now();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthenticated();
        }
        SessionStore.Row row = accounts.sessions()
                .byRefreshHashForUpdate(Secrets.sha256(refreshToken))
                .orElseThrow(AuthFlow::unauthenticated);
        if (row.revokedAt() != null) {
            throw revoked();
        }
        if (row.rotatedAt() != null) {
            accounts.sessions().revokeFamily(row.familyId(), "rotation_reuse", now);
            accounts.audit("core.auth.refresh_token_reused", row.userId(), Map.of("family_id", row.familyId()));
            throw revoked();
        }
        if (!now.isBefore(row.idleExpiresAt()) || !now.isBefore(row.expiresAt())) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "session_expired", "Session expired", "Sign in again to continue.");
        }
        Account account = accounts.byIdForUpdate(row.userId()).orElseThrow(AuthFlow::revoked);
        if (!account.active()) {
            accounts.sessions().revokeFamily(row.familyId(), "user_deactivated", now);
            throw revoked();
        }
        accounts.sessions().markRotated(row.id(), now);
        return issue(accounts, row.userId(), row.familyId(), row.expiresAt(), now, issuer, userAgent, List.of());
    }

    /** Sign-out revokes the session's whole family (FR-IAM-07). */
    @Transactional
    void logout(Accounts accounts, UUID sessionId) {
        Instant now = clock.now();
        accounts.sessions().byId(sessionId).ifPresent(row -> {
            accounts.sessions().revokeFamily(row.familyId(), "sign_out", now);
            accounts.audit("core.auth.signed_out", row.userId(), Map.of());
        });
    }

    private Outcome signIn(
            Accounts accounts,
            UUID userId,
            Instant now,
            String issuer,
            String userAgent,
            List<String> recoveryCodes,
            String method) {
        accounts.recordSuccess(userId, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", method);
        data.put("ip", RequestContext.clientIp());
        accounts.audit("core.auth.signed_in", userId, data);
        return issue(
                accounts,
                userId,
                UUID.randomUUID(),
                now.plus(accounts.absoluteTtl()),
                now,
                issuer,
                userAgent,
                recoveryCodes);
    }

    private Outcome issue(
            Accounts accounts,
            UUID userId,
            UUID familyId,
            Instant absoluteExpiry,
            Instant now,
            String issuer,
            String userAgent,
            List<String> recoveryCodes) {
        UUID sessionId = UUID.randomUUID();
        String refresh = Secrets.token();
        Instant idle = now.plus(accounts.idleTtl());
        accounts.sessions()
                .insert(
                        sessionId,
                        userId,
                        familyId,
                        Secrets.sha256(refresh),
                        absoluteExpiry,
                        idle.isBefore(absoluteExpiry) ? idle : absoluteExpiry,
                        RequestContext.clientIp(),
                        userAgent);
        String access = tokens.access(userId, accounts.tenantId(), accounts.kind(), sessionId, issuer, now);
        return new Outcome(Step.SIGNED_IN, access, refresh, null, recoveryCodes);
    }

    private List<String> issueRecoveryCodes(Accounts accounts, UUID userId) {
        List<String> codes = Secrets.recoveryCodes();
        accounts.replaceRecoveryCodes(
                userId, codes.stream().map(Secrets::recoveryCodeHash).toList());
        return codes;
    }

    private Account accountOf(Accounts accounts, String mfaToken, Instant now) {
        AccessTokens.Claims claims;
        try {
            claims = tokens.verify(mfaToken == null ? "" : mfaToken, AccessTokens.PURPOSE_MFA, now);
        } catch (AccessTokens.Invalid e) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "mfa_token_invalid",
                    "Sign in again",
                    "The sign-in step expired or is not valid; enter your password again.");
        }
        if (!accounts.kind().equals(claims.kind()) || !Objects.equals(accounts.tenantId(), claims.tenantId())) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "tenant_mismatch", "Tenant mismatch", "The token is for another tenant.");
        }
        Account account = accounts.byIdForUpdate(claims.userId()).orElseThrow(AuthFlow::invalidCredentials);
        if (!account.active()) {
            throw invalidCredentials();
        }
        return account;
    }

    /** FR-IAM-05: the fifth consecutive failure locks the account for 15 minutes, audited. */
    private void fail(Accounts accounts, Account account, Instant now, String action) {
        int failures = account.failedLoginCount() + 1;
        boolean lock = failures >= MAX_FAILURES;
        accounts.recordFailure(account.id(), lock ? 0 : failures, lock ? now.plus(LOCK) : null);
        accounts.audit(action, account.id(), Map.of("consecutive_failures", failures));
        if (lock) {
            accounts.audit("core.auth.account_locked", account.id(), Map.of("locked_minutes", LOCK.toMinutes()));
        }
    }

    static ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "invalid_credentials",
                "Sign-in failed",
                "The sign-in name or password is not correct.");
    }

    static ApiException unauthenticated() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Not authenticated", "Sign in to use this endpoint.");
    }

    static ApiException revoked() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "session_revoked", "Session revoked", "The session has ended; sign in again.");
    }

    private static ApiException locked() {
        return new ApiException(
                HttpStatus.LOCKED,
                "account_locked",
                "Account locked",
                "Too many failed attempts. Try again in " + LOCK.toMinutes() + " minutes.");
    }

    private static ApiException invalidCode(HttpStatus status) {
        return new ApiException(status, "invalid_mfa_code", "Invalid code", "The code is not valid.");
    }
}
