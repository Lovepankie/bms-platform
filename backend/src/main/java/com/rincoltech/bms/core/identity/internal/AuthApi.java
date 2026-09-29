package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.internal.AuthFlow.Outcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

/**
 * Request and response bodies of the sign-in endpoints (chapter 7 section 7.11.2), shared by
 * tenant staff ({@code /api/v1/auth}) and platform operators ({@code /api/v1/platform/auth}).
 */
final class AuthApi {

    private AuthApi() {}

    static final String STAFF_COOKIE = "bms_rt";
    static final String STAFF_COOKIE_PATH = "/api/v1/auth";
    static final String PLATFORM_COOKIE = "bms_prt";
    static final String PLATFORM_COOKIE_PATH = "/api/v1/platform/auth";

    @Schema(name = "SignInRequest")
    record SignInRequest(
            @NotBlank @Size(max = 320) @Schema(description = "Email, or phone for staff")
            String login,

            @NotBlank @Size(max = 200) String password) {}

    @Schema(name = "MfaVerifyRequest")
    record MfaVerifyRequest(
            @NotBlank String mfaToken,

            @NotBlank @Size(max = 20) @Schema(description = "A 6 digit TOTP code or a recovery code")
            String code) {}

    @Schema(
            name = "MfaEnrolRequest",
            description = "mfa_token when enrolment is forced at sign-in; empty when signed in")
    record MfaEnrolRequest(String mfaToken) {}

    @Schema(name = "MfaConfirmRequest")
    record MfaConfirmRequest(
            String mfaToken, @NotBlank @Size(max = 20) String code) {}

    @Schema(name = "RecoveryCodesRequest")
    record RecoveryCodesRequest(
            @NotBlank @Size(max = 20) @Schema(description = "A current TOTP code")
            String code) {}

    @Schema(name = "AcceptInvitationRequest")
    record AcceptInvitationRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(max = 200) String password) {}

    @Schema(name = "MfaEnrolment")
    record MfaEnrolmentResponse(
            @Schema(description = "Base32 secret for manual entry; shown once")
            String secret,

            @Schema(description = "otpauth URI for a QR code; shown once")
            String otpauthUri) {}

    @Schema(name = "RecoveryCodes")
    record RecoveryCodesResponse(
            @Schema(description = "Shown once; each works once")
            List<String> recoveryCodes) {}

    /**
     * {@code status} is {@code signed_in}, {@code mfa_required} or {@code mfa_enrolment_required}.
     * The refresh token travels only in the {@code HttpOnly} cookie, never in the body.
     */
    @Schema(name = "SignInResponse")
    record SignInResponse(
            String status,
            boolean mfaRequired,
            boolean mfaEnrolmentRequired,
            String accessToken,
            String tokenType,
            Long expiresIn,
            String mfaToken,

            @Schema(description = "Present once, right after MFA enrolment (FR-IAM-11)")
            List<String> recoveryCodes) {}

    static ResponseEntity<SignInResponse> respond(Outcome outcome, String cookie, String path, Duration maxAge) {
        SignInResponse body = new SignInResponse(
                outcome.step().name().toLowerCase(),
                outcome.step() == AuthFlow.Step.MFA_REQUIRED,
                outcome.step() == AuthFlow.Step.MFA_ENROLMENT_REQUIRED,
                outcome.accessToken(),
                outcome.accessToken() == null ? null : "Bearer",
                outcome.accessToken() == null ? null : AccessTokens.ACCESS_TTL.toSeconds(),
                outcome.mfaToken(),
                outcome.recoveryCodes().isEmpty() ? null : outcome.recoveryCodes());
        var response = ResponseEntity.ok();
        if (outcome.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookie(cookie, outcome.refreshToken(), path, maxAge));
        }
        return response.body(body);
    }

    /** {@code HttpOnly; Secure; SameSite=Strict}, scoped to the auth path (chapter 8 section 8.8). */
    static String cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
