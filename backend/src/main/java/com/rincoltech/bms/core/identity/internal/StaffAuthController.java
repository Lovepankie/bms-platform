package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.internal.AuthApi.AcceptInvitationRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaConfirmRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaEnrolRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaEnrolmentResponse;
import com.rincoltech.bms.core.identity.internal.AuthApi.MfaVerifyRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.RecoveryCodesRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.RecoveryCodesResponse;
import com.rincoltech.bms.core.identity.internal.AuthApi.SignInRequest;
import com.rincoltech.bms.core.identity.internal.AuthApi.SignInResponse;
import com.rincoltech.bms.core.identity.internal.AuthFlow.Enrolment;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff sign-in, second factor, invitation acceptance and sessions (chapter 7 section 7.11.2). */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth")
class StaffAuthController {

    private final AuthFlow flow;
    private final StaffAccounts accounts;
    private final UserService users;

    StaffAuthController(AuthFlow flow, StaffAccounts accounts, UserService users) {
        this.flow = flow;
        this.accounts = accounts;
        this.users = users;
    }

    @PostMapping("/staff/login")
    @PublicEndpoint
    @Operation(summary = "Sign in with email or phone and password (FR-IAM-04, FR-IAM-05)", operationId = "staffLogin")
    ResponseEntity<SignInResponse> login(@Valid @RequestBody SignInRequest request, HttpServletRequest http) {
        return respond(flow.login(accounts, request.login(), request.password(), http.getServerName(), agent(http)));
    }

    @PostMapping("/staff/mfa/verify")
    @PublicEndpoint
    @Operation(summary = "Second factor: TOTP or recovery code (FR-IAM-06, FR-IAM-11)", operationId = "staffMfaVerify")
    ResponseEntity<SignInResponse> verify(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest http) {
        return respond(flow.verifyMfa(accounts, request.mfaToken(), request.code(), http.getServerName(), agent(http)));
    }

    /** Public route: the caller proves who it is with the MFA token, or is signed in already. */
    @PostMapping("/staff/mfa/enrol")
    @PublicEndpoint
    @Operation(summary = "Start TOTP enrolment; returns the secret once (FR-IAM-06)", operationId = "staffMfaEnrol")
    MfaEnrolmentResponse enrol(@RequestBody(required = false) MfaEnrolRequest request) {
        Enrolment e = flow.startEnrolment(accounts, enrollee(request == null ? null : request.mfaToken()));
        return new MfaEnrolmentResponse(e.secret(), e.otpauthUri());
    }

    @PostMapping("/staff/mfa/confirm")
    @PublicEndpoint
    @Operation(
            summary = "Confirm TOTP enrolment; returns recovery codes once, and tokens when enrolment was forced",
            operationId = "staffMfaConfirm")
    ResponseEntity<SignInResponse> confirm(@Valid @RequestBody MfaConfirmRequest request, HttpServletRequest http) {
        boolean forced = request.mfaToken() != null && !request.mfaToken().isBlank();
        UUID userId = enrollee(request.mfaToken());
        return respond(
                flow.confirmEnrolment(accounts, userId, request.code(), forced, http.getServerName(), agent(http)));
    }

    @PostMapping("/staff/mfa/recovery-codes")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Replace the recovery codes; returns the new ones once (FR-IAM-11)",
            operationId = "staffRecoveryCodes")
    RecoveryCodesResponse recoveryCodes(@Valid @RequestBody RecoveryCodesRequest request) {
        return new RecoveryCodesResponse(flow.regenerateRecoveryCodes(
                accounts, CurrentPrincipal.require().userId(), request.code()));
    }

    @PostMapping("/staff/invitations/accept")
    @PublicEndpoint
    @Operation(
            summary = "Set a password with the one-time invitation token (FR-IAM-01)",
            operationId = "acceptInvitation")
    ResponseEntity<Void> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        users.acceptInvitation(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    @PublicEndpoint
    @Operation(
            summary = "Rotate the refresh token in the __Host-bms_rt cookie (FR-IAM-07)",
            operationId = "refreshSession")
    ResponseEntity<SignInResponse> refresh(
            @CookieValue(name = AuthApi.STAFF_COOKIE, required = false) String refreshToken, HttpServletRequest http) {
        return respond(flow.refresh(accounts, refreshToken, http.getServerName(), agent(http)));
    }

    @PostMapping("/logout")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "Sign out: revokes the session family (FR-IAM-07)", operationId = "logout")
    ResponseEntity<Void> logout() {
        Principal principal = CurrentPrincipal.require();
        if (principal.sessionId() != null) {
            flow.logout(accounts, principal.sessionId());
        }
        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        AuthApi.cookie(AuthApi.STAFF_COOKIE, "", AuthApi.STAFF_COOKIE_PATH, Duration.ZERO))
                .build();
    }

    private UUID enrollee(String mfaToken) {
        if (mfaToken != null && !mfaToken.isBlank()) {
            return flow.userOfMfaToken(accounts, mfaToken);
        }
        Principal principal = CurrentPrincipal.require();
        if (!"staff".equals(principal.kind()) || principal.sessionId() == null) {
            throw AuthFlow.unauthenticated();
        }
        return principal.userId();
    }

    private ResponseEntity<SignInResponse> respond(AuthFlow.Outcome outcome) {
        return AuthApi.respond(outcome, AuthApi.STAFF_COOKIE, AuthApi.STAFF_COOKIE_PATH, accounts.absoluteTtl());
    }

    static String agent(HttpServletRequest http) {
        return http.getHeader(HttpHeaders.USER_AGENT);
    }
}
