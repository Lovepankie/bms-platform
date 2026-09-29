package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.identity.internal.AuthApi.AcceptInvitationRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaConfirmRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaEnrolRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaEnrolmentResponse;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaVerifyRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.SignInRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.SignInResponse;
import com.rincoltech.bms.core.identity.internal.AuthFlow.Enrolment;
import com.rincoltech.bms.core.identity.internal.UserApi.PlatformMeResponse;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform operator sign-in on {@code app.<base domain>} (chapter 7 section 7.11.3, chapter 8
 * section 8.2): email, password and a mandatory TOTP factor, enrolled at first sign-in. The first
 * password is set with a one-time setup token issued by {@code deploy/sql/create-platform-user.sql}.
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform-auth")
class PlatformAuthController {

    private final AuthFlow flow;
    private final PlatformAccounts accounts;
    private final Passwords passwords;
    private final JdbcClient jdbc;
    private final PlatformAuditLog audit;
    private final BusinessClock clock;

    PlatformAuthController(
            AuthFlow flow,
            PlatformAccounts accounts,
            Passwords passwords,
            JdbcClient jdbc,
            PlatformAuditLog audit,
            BusinessClock clock) {
        this.flow = flow;
        this.accounts = accounts;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping("/auth/setup")
    @PublicEndpoint
    @Operation(summary = "Set the first password with the one-time setup token", operationId = "platformSetup")
    @Transactional
    public ResponseEntity<Void> setup(@Valid @RequestBody AcceptInvitationRequest request) {
        Instant now = clock.now();
        record Setup(UUID id, String email, Instant expiresAt) {}
        Setup setup = jdbc.sql("""
                        SELECT id, email, setup_token_expires_at FROM platform_users
                         WHERE setup_token_hash = ? AND password_hash IS NULL AND is_active FOR UPDATE
                        """)
                .param(Secrets.sha256(request.token()))
                .query((rs, n) -> new Setup(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        SessionStore.instant(rs.getTimestamp("setup_token_expires_at"))))
                .optional()
                .filter(s -> s.expiresAt() != null && now.isBefore(s.expiresAt()))
                .orElseThrow(() ->
                        ApiException.rule("invitation_invalid", "The setup link is not valid; ask for a new one."));
        passwords.checkStrength(request.password(), setup.email());
        jdbc.sql("""
                        UPDATE platform_users SET password_hash = ?, setup_token_hash = NULL, setup_token_expires_at = NULL,
                               updated_at = ? WHERE id = ?
                        """)
                .params(passwords.hash(request.password()), Timestamp.from(now), setup.id())
                .update();
        audit.record("platform.user.password_set", setup.id(), null, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/login")
    @PublicEndpoint
    @Operation(summary = "Platform sign-in, first factor", operationId = "platformLogin")
    ResponseEntity<SignInResponse> login(@Valid @RequestBody SignInRequest request, HttpServletRequest http) {
        return respond(flow.login(
                accounts, request.login(), request.password(), http.getServerName(), StaffAuthController.agent(http)));
    }

    @PostMapping("/auth/mfa/verify")
    @PublicEndpoint
    @Operation(summary = "Platform sign-in, second factor", operationId = "platformMfaVerify")
    ResponseEntity<SignInResponse> verify(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest http) {
        return respond(flow.verifyMfa(
                accounts, request.mfaToken(), request.code(), http.getServerName(), StaffAuthController.agent(http)));
    }

    @PostMapping("/auth/mfa/enrol")
    @PublicEndpoint
    @Operation(summary = "Start the mandatory TOTP enrolment with the MFA token", operationId = "platformMfaEnrol")
    MfaEnrolmentResponse enrol(@RequestBody MfaEnrolRequest request) {
        Enrolment e = flow.startEnrolment(accounts, flow.userOfMfaToken(accounts, request.mfaToken()));
        return new MfaEnrolmentResponse(e.secret(), e.otpauthUri());
    }

    @PostMapping("/auth/mfa/confirm")
    @PublicEndpoint
    @Operation(summary = "Confirm TOTP enrolment and sign in", operationId = "platformMfaConfirm")
    ResponseEntity<SignInResponse> confirm(@Valid @RequestBody MfaConfirmRequest request, HttpServletRequest http) {
        UUID userId = flow.userOfMfaToken(accounts, request.mfaToken());
        return respond(flow.confirmEnrolment(
                accounts, userId, request.code(), true, http.getServerName(), StaffAuthController.agent(http)));
    }

    @PostMapping("/auth/refresh")
    @PublicEndpoint
    @Operation(summary = "Rotate the platform refresh token", operationId = "platformRefresh")
    ResponseEntity<SignInResponse> refresh(
            @CookieValue(name = AuthApi.PLATFORM_COOKIE, required = false) String refreshToken,
            HttpServletRequest http) {
        return respond(flow.refresh(accounts, refreshToken, http.getServerName(), StaffAuthController.agent(http)));
    }

    @PostMapping("/auth/logout")
    @AuthenticatedEndpoint(kind = "platform")
    @Operation(summary = "Platform sign-out", operationId = "platformLogout")
    ResponseEntity<Void> logout() {
        flow.logout(accounts, CurrentPrincipal.require().sessionId());
        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        AuthApi.cookie(AuthApi.PLATFORM_COOKIE, "", AuthApi.PLATFORM_COOKIE_PATH, Duration.ZERO))
                .build();
    }

    @GetMapping("/me")
    @AuthenticatedEndpoint(kind = "platform")
    @Operation(summary = "The signed-in platform operator", operationId = "platformMe")
    @Transactional(readOnly = true)
    public PlatformMeResponse me() {
        Principal principal = CurrentPrincipal.require();
        return jdbc.sql("SELECT full_name, email FROM platform_users WHERE id = ?")
                .param(principal.userId())
                .query((rs, n) -> new PlatformMeResponse(
                        principal.userId(),
                        principal.kind(),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        principal.permissions().stream().sorted().toList()))
                .single();
    }

    private ResponseEntity<SignInResponse> respond(AuthFlow.Outcome outcome) {
        return AuthApi.respond(outcome, AuthApi.PLATFORM_COOKIE, AuthApi.PLATFORM_COOKIE_PATH, accounts.absoluteTtl());
    }
}
