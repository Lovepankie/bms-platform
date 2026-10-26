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
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in, second factor, sessions (FR-IAM-04 to FR-IAM-08, FR-IAM-11; chapter 8 section 8.2).
 * One flow for tenant staff and platform operators; {@link Accounts} says where the accounts live.
 *
 * <p>Failures that must be remembered (the failed attempt counter, the lock, the audit row, a
 * family revoked for refresh token reuse) are written before the method throws; the methods do
 * not roll back on {@link ApiException}, so the answer is an error and the record commits.
 *
 * <p>Sign-in answers do not say whether an account exists (ADR-059): an address that matches no account is counted and
 * locked in the database ({@link SignInAddressFailures}) after the same five failures, for the same 15 minutes, with the
 * same 423 and words as a real account, and the lock survives a restart. Both paths read the same rows before the hash;
 * after it both lock their row and read the lock again before looking at the hash's result (so once locked, right and
 * wrong answer alike), then write one counter row and the same audit rows; neither lock pays for a hash. What is left, and is
 * not hidden: an address of nobody forgets its count after a week without a failure, a real account only at a right
 * password; and a few database round trips more or less are not timed away.
 *
 * <p>No password is hashed inside a transaction (ADR-059, review of #433 finding 3): {@link #login} and
 * {@link #confirmPassword} read in one short transaction, hash with no connection held, and record the result in a second
 * one, so a flood of sign-ins waiting for a hashing slot cannot take the database pool from every other tenant.
 */
@Service
class AuthFlow {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    private final Passwords passwords;
    private final AccessTokens tokens;
    private final SecretBox box;
    private final BusinessClock clock;
    private final AuthThrottle throttle;
    private final SignInAddressFailures addresses;
    private final TransactionTemplate transactions;

    AuthFlow(
            Passwords passwords,
            AccessTokens tokens,
            SecretBox box,
            BusinessClock clock,
            AuthThrottle throttle,
            SignInAddressFailures addresses,
            PlatformTransactionManager transactionManager) {
        this.passwords = passwords;
        this.tokens = tokens;
        this.box = box;
        this.clock = clock;
        this.throttle = throttle;
        this.addresses = addresses;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    enum Step {
        SIGNED_IN,
        MFA_REQUIRED,
        MFA_ENROLMENT_REQUIRED,
        /** The password was reset but no session is issued: the user signs in with the new password. */
        SIGN_IN_REQUIRED
    }

    /** What a step returns: tokens when signed in, an MFA token between the two factors. */
    record Outcome(Step step, String accessToken, String refreshToken, String mfaToken, List<String> recoveryCodes) {

        static Outcome mfa(Step step, String mfaToken) {
            return new Outcome(step, null, null, mfaToken, List.of());
        }
    }

    record Enrolment(String secret, String otpauthUri) {}

    /**
     * What the first look of a sign-in found, before the hash: the key of the address typed and the account it names, or
     * {@code null} when it names none.
     */
    record Attempt(String login, String addressKey, Account account) {}

    /**
     * The password step of sign-in (FR-IAM-04, FR-IAM-05). Not one transaction but two, with the hash between them and no
     * connection held while it waits for a slot or runs (ADR-059): {@link #firstLook} refuses a locked address or account
     * with no hash, then the password is checked, then {@link #settle} counts a failure or signs in.
     */
    Outcome login(Accounts accounts, String login, String password, String issuer, String userAgent) {
        Attempt attempt = inTransaction(() -> firstLook(accounts, login));
        Account seen = attempt.account();
        boolean ok = passwords.matches(password == null ? "" : password, seen == null ? null : seen.passwordHash());
        return inTransaction(() -> settle(accounts, attempt, ok, issuer, userAgent));
    }

    /**
     * Reads, for every address alike, the lock row of the address and the account; refuses with 423 when the one that
     * applies is locked (the address's own row for an address of nobody, the account's for an account).
     */
    private Attempt firstLook(Accounts accounts, String login) {
        Instant now = clock.now();
        String key = SignInAddressFailures.key(AuthThrottle.address(accounts, login));
        boolean addressLocked = addresses.lockedAt(key, now);
        Account account = accounts.byLogin(login).orElse(null);
        if (account == null ? addressLocked : account.lockedAt(now)) {
            accounts.audit(
                    "core.auth.sign_in_refused_locked",
                    account == null ? null : account.id(),
                    account == null ? Map.of("login", typed(login)) : Map.of());
            throw locked();
        }
        return new Attempt(login, key, account);
    }

    /**
     * After the hash: the row of what was typed (the account, or the address of nobody) is locked and its lock read again
     * BEFORE the result of the hash is looked at, so once a lock is in force every answer is 423, right password or
     * wrong (review of #433): a burst of guesses that all passed {@link #firstLook} before the lock cannot learn which
     * one was right from a 423 among 401s. Otherwise one failure is counted, or the sign-in goes on.
     */
    private Outcome settle(Accounts accounts, Attempt attempt, boolean ok, String issuer, String userAgent) {
        Instant now = clock.now();
        Account seen = attempt.account();
        if (seen == null) {
            if (addresses.lockedForUpdate(attempt.addressKey(), now)) {
                accounts.audit("core.auth.sign_in_refused_locked", null, Map.of("login", typed(attempt.login())));
                throw locked();
            }
            Accounts.Failures failures =
                    addresses.recordFailure(attempt.addressKey(), now, MAX_FAILURES, now.plus(LOCK));
            accounts.audit(
                    "core.auth.sign_in_failed",
                    null,
                    Map.of("login", typed(attempt.login()), "consecutive_failures", failures.consecutive()));
            if (failures.consecutive() == MAX_FAILURES) {
                accounts.audit(
                        "core.auth.account_locked",
                        null,
                        Map.of("login", typed(attempt.login()), "locked_minutes", LOCK.toMinutes()));
            }
            throw invalidCredentials();
        }
        // The first read took no lock: parallel failures may have locked the account since, or the password changed.
        Account account = accounts.byIdForUpdate(seen.id()).orElseThrow(AuthFlow::invalidCredentials);
        if (account.lockedAt(now)) {
            accounts.audit("core.auth.sign_in_refused_locked", account.id(), Map.of());
            throw locked();
        }
        if (!ok || !account.active()) {
            fail(accounts, account, now, "core.auth.sign_in_failed");
            throw invalidCredentials();
        }
        if (!Objects.equals(account.passwordHash(), seen.passwordHash())) {
            // A password set while this one was checked: what matched is no longer the password.
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

    private static String typed(String login) {
        return login == null ? "" : login;
    }

    /**
     * Runs {@code work} in a transaction of its own that commits even when the work refuses with an {@link ApiException}
     * (the failure counter, the lock and the audit rows are written before the refusal, as {@code noRollbackFor} does on
     * the annotated methods), then rethrows the refusal.
     */
    private <T> T inTransaction(Supplier<T> work) {
        Object result = transactions.execute(status -> {
            try {
                return work.get();
            } catch (ApiException e) {
                return new Refusal(e);
            }
        });
        if (result instanceof Refusal refusal) {
            throw refusal.exception();
        }
        @SuppressWarnings("unchecked")
        T value = (T) result;
        return value;
    }

    private record Refusal(ApiException exception) {}

    /**
     * The first sign-in, right after the invitee set a password with the one-time token (issue
     * #86): the token and the new password have just proved who the user is, so the password is
     * not checked a second time. A role that requires a second factor still goes to enrolment
     * first, exactly as {@link #login} would send it; nobody skips a required factor this way.
     */
    @Transactional(noRollbackFor = ApiException.class)
    Outcome afterInvitation(Accounts accounts, UUID userId, String issuer, String userAgent) {
        Instant now = clock.now();
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (!account.active() || account.lockedAt(now)) {
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
        return signIn(accounts, account.id(), now, issuer, userAgent, List.of(), "invitation");
    }

    /**
     * The sign-in right after a password reset (FR-IAM-15, ADR-042): the link and the new password have just proved who the
     * user is, as for {@link #afterInvitation}, and a second factor is still asked for exactly as {@link #login} would. The
     * reset itself lifted the failed-attempt lock ({@link PasswordResets#complete}, ADR-059), so the owner of the mailbox is
     * never left locked out by a stranger's guesses; an account locked again in the moment between the two (a race) is told
     * to sign in later and gets no session.
     */
    @Transactional(noRollbackFor = ApiException.class)
    Outcome afterReset(Accounts accounts, UUID userId, String issuer, String userAgent) {
        Instant now = clock.now();
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (!account.active()) {
            throw invalidCredentials();
        }
        if (account.lockedAt(now)) {
            return new Outcome(Step.SIGN_IN_REQUIRED, null, null, null, List.of());
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
        return signIn(accounts, account.id(), now, issuer, userAgent, List.of(), "password_reset");
    }

    /** The second factor: a TOTP code or one unused recovery code (FR-IAM-06, FR-IAM-11). */
    @Transactional(noRollbackFor = ApiException.class)
    Outcome verifyMfa(Accounts accounts, String mfaToken, String code, String issuer, String userAgent) {
        Instant now = clock.now();
        Account account = accountOf(accounts, mfaToken, now);
        throttle.acquireFor(AuthThrottle.MFA_PER_ACCOUNT, accounts.kind() + ":" + account.id());
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
            if (step.isEmpty() || !accounts.advanceTotpStep(account.id(), step.getAsLong())) {
                fail(accounts, account, now, "core.mfa.failed");
                throw invalidCode(HttpStatus.UNAUTHORIZED);
            }
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
        // Wrong passwords at the password step-up share this counter with wrong codes at the code step-up: someone who
        // made four
        // wrong passwords and then enrols must not be one wrong code from losing every session (ADR-042).
        accounts.clearStepUpFailures(userId);
        List<String> codes = issueRecoveryCodes(accounts, userId);
        accounts.audit("core.mfa.enrolled", userId, Map.of("recovery_codes_issued", codes.size()));
        if (signIn) {
            return signIn(accounts, userId, now, issuer, userAgent, codes, "totp_enrolment");
        }
        return new Outcome(Step.SIGNED_IN, null, null, null, codes);
    }

    /**
     * A second-factor re-entry for one sensitive action by a signed-in user (step-up, issue #233): the current TOTP code
     * of the user's own enrolment, each code usable once. A wrong code counts toward the account's lockout like any other.
     */
    @Transactional(noRollbackFor = ApiException.class)
    void confirmTotp(Accounts accounts, UUID userId, String code) {
        Instant now = clock.now();
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (account.lockedAt(now) || accounts.stepUpLockedAt(account.id(), now)) {
            // Too many wrong codes: a stolen session cannot go on guessing a code for a sensitive action.
            accounts.audit("core.auth.step_up_refused_locked", account.id(), Map.of());
            throw locked();
        }
        if (!account.mfaEnabled() || account.totpSecret() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "mfa_not_enrolled", "MFA not enrolled", "Enrol a second factor first.");
        }
        OptionalLong step =
                Totp.verify(box.open(account.totpSecret()), code == null ? "" : code, now, account.totpLastStep());
        if (step.isEmpty() || !accounts.advanceTotpStep(userId, step.getAsLong())) {
            failStepUp(accounts, account, now);
            throw invalidCode(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        accounts.clearStepUpFailures(userId);
    }

    /**
     * A re-entry of the signed-in user's own password for one sensitive action, for an account with no second factor (the admin
     * reset of a sign-in, ADR-042): the step-up of {@link #confirmTotp} where there is no authenticator to ask. A stolen session or
     * token does not give the password. The account must be active and not locked, and a wrong password:
     *
     * <ul>
     *   <li>counts on the account's sign-in counter and lock exactly as a wrong password at sign-in does (the fifth locks the
     *       account for 15 minutes, audited; the count is the shared one, so a stranger's wrong sign-ins add to it);
     *   <li>also counts on the step-up counter, which only an authenticated session can feed. The fifth wrong password there
     *       locks step-up for 15 minutes and ends the CALLING session's family and no other: a stolen token cannot go on guessing
     *       the password from a live session (about 480 tries a day on the sign-in lock alone), while a stranger's wrong sign-ins
     *       and the admin's other devices are untouched. This is the counterpart of {@link #failStepUp}, which ends every session
     *       because a wrong code is only ever sent by a caller who holds a session.
     * </ul>
     *
     * A right password starts the sign-in failure count again, as at sign-in, and leaves any lock alone. The password is never
     * logged, audited or echoed.
     *
     * @param sessionId the calling session, whose family a fifth wrong password ends; {@code null} for a caller with no session
     */
    void confirmPassword(Accounts accounts, UUID userId, UUID sessionId, String password) {
        Account seen = inTransaction(() -> {
            Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
            refuseStepUp(accounts, account, clock.now());
            return account;
        });
        // The hash runs between the two transactions, with no connection held (ADR-059).
        boolean ok = passwords.matches(password == null ? "" : password, seen.passwordHash());
        inTransaction(() -> {
            Instant now = clock.now();
            Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
            refuseStepUp(accounts, account, now);
            if (!ok || !Objects.equals(account.passwordHash(), seen.passwordHash())) {
                wrongStepUpPassword(accounts, account, sessionId, now);
            }
            accounts.clearFailedLogins(account.id());
            accounts.clearStepUpFailures(account.id());
            return null;
        });
    }

    /** What {@link #confirmPassword} refuses before and after the hash: an account not active, or locked either way. */
    private static void refuseStepUp(Accounts accounts, Account account, Instant now) {
        if (!account.active()) {
            throw invalidCredentials();
        }
        if (account.lockedAt(now) || accounts.stepUpLockedAt(account.id(), now)) {
            accounts.audit("core.auth.step_up_refused_locked", account.id(), Map.of());
            throw locked();
        }
    }

    private void wrongStepUpPassword(Accounts accounts, Account account, UUID sessionId, Instant now) {
        fail(accounts, account, now, "core.auth.step_up_password_failed");
        Accounts.Failures stepUp = accounts.recordStepUpFailure(account.id(), now, MAX_FAILURES, now.plus(LOCK));
        if (stepUp.consecutive() == MAX_FAILURES) {
            int ended = sessionId == null
                    ? 0
                    : accounts.sessions()
                            .byId(sessionId)
                            .map(row -> accounts.sessions().revokeFamily(row.familyId(), "admin", now))
                            .orElse(0);
            accounts.audit(
                    "core.auth.step_up_session_ended",
                    account.id(),
                    Map.of("locked_minutes", LOCK.toMinutes(), "sessions_ended", ended));
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password",
                "Invalid password",
                "The password is not correct.");
    }

    /** New recovery codes replace every old one; needs a current TOTP code (FR-IAM-11). */
    @Transactional(noRollbackFor = ApiException.class)
    List<String> regenerateRecoveryCodes(Accounts accounts, UUID userId, String code) {
        Instant now = clock.now();
        Account account = accounts.byIdForUpdate(userId).orElseThrow(AuthFlow::invalidCredentials);
        if (account.lockedAt(now) || accounts.stepUpLockedAt(account.id(), now)) {
            // Too many wrong codes: a stolen session cannot go on guessing a code for a sensitive action.
            accounts.audit("core.auth.step_up_refused_locked", account.id(), Map.of());
            throw locked();
        }
        if (!account.mfaEnabled() || account.totpSecret() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "mfa_not_enrolled", "MFA not enrolled", "Enrol a second factor first.");
        }
        OptionalLong step = Totp.verify(box.open(account.totpSecret()), code, now, account.totpLastStep());
        if (step.isEmpty() || !accounts.advanceTotpStep(userId, step.getAsLong())) {
            failStepUp(accounts, account, now);
            throw invalidCode(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        accounts.clearStepUpFailures(userId);
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
        if (!account.mfaEnabled() && accounts.mfaRequired(account.id())) {
            // The business requires two-step sign-in of this person and the date has passed (or a role came after it),
            // or a platform operator has no second factor: a session with no second factor is not renewed. It ends here
            // and the person signs in again with the password, which leads to enrolment (ADR-046). No MFA token is
            // minted from a refresh cookie: a stolen cookie must not be able to enrol the thief's authenticator.
            accounts.sessions().revokeFamily(row.familyId(), "two_step_required", now);
            accounts.audit("core.auth.session_ended_two_step_required", account.id(), Map.of());
            return new Outcome(Step.SIGN_IN_REQUIRED, null, null, null, List.of());
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

    /**
     * FR-IAM-05: the fifth consecutive failure locks the account for 15 minutes, audited. The count
     * and the lock come back from one atomic statement, so parallel failures cannot be lost.
     */
    private void fail(Accounts accounts, Account account, Instant now, String action) {
        Accounts.Failures failures = accounts.recordFailure(account.id(), now, MAX_FAILURES, now.plus(LOCK));
        accounts.audit(action, account.id(), Map.of("consecutive_failures", failures.consecutive()));
        // Only the failure that reached the threshold set the lock; later ones keep it.
        if (failures.consecutive() == MAX_FAILURES) {
            accounts.audit("core.auth.account_locked", account.id(), Map.of("locked_minutes", LOCK.toMinutes()));
        }
    }

    /**
     * A wrong code entered by a signed-in session for a sensitive action (issue #309). It counts on its own counter, never the
     * password's, and the failure that reaches the threshold locks step-up and ends every session of the user: whoever holds
     * one (a stolen session guessing codes) signs in again. Password failures are unauthenticated (anyone who knows a login
     * name can send one), so they neither count here nor end a session; and a step-up failure never counts toward the password
     * lock, so a stranger's wrong passwords plus one real slip cannot log staff out.
     */
    private void failStepUp(Accounts accounts, Account account, Instant now) {
        Accounts.Failures failures = accounts.recordStepUpFailure(account.id(), now, MAX_FAILURES, now.plus(LOCK));
        accounts.audit("core.mfa.failed", account.id(), Map.of("consecutive_failures", failures.consecutive()));
        if (failures.consecutive() == MAX_FAILURES) {
            accounts.audit("core.auth.step_up_locked", account.id(), Map.of("locked_minutes", LOCK.toMinutes()));
            accounts.sessions().revokeAllOf(account.id(), "admin", now);
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
