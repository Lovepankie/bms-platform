package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/sign-up}: the public sign-up form and the applicant page (spec section
 * 10, chapter 7 section 7.11.3). No sign-in; served only on the platform host. Rate limited per
 * address and per email; a known and an unknown email get the same answer; the applicant's link
 * token travels in the body, never in a URL the server sees.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/sign-up", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "sign-up")
class SignUpController {

    private final ApplicationService service;

    SignUpController(ApplicationService service) {
        this.service = service;
    }

    @Schema(name = "SignUpRequest")
    record SignUpRequest(
            @NotBlank @Size(max = 200) String businessName,
            @NotBlank @Size(max = 200) String contactName,

            @NotBlank
            @Email
            @Size(max = 254)
            @Pattern(
                    regexp = "^[\\x21-\\x7E]+$",
                    message = "Use an address in plain letters, digits and symbols (no accents).")
            @Schema(description = "ASCII only: an address needing SMTPUTF8 is refused")
            String contactEmail,

            @NotBlank @Size(max = 20) @Schema(description = "Local or international form; stored as E.164")
            String contactPhone,

            @NotBlank @Pattern(regexp = "UG|KE|TZ|RW") @Schema(description = "ISO 3166 alpha-2: UG, KE, TZ or RW")
            String country,

            @NotEmpty @Size(max = 5) List<@Pattern(regexp = "^[a-z]{2,30}$") String> modules,
            @NotBlank @Pattern(regexp = "monthly|annual") String term,

            @NotBlank
            @Pattern(regexp = "trial|paid")
            @Schema(description = "trial: one month free; paid: subscribe now")
            String wayIn,

            @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9-]*$") @Schema(description = "Recorded; agents arrive later")
            String agentCode,

            @Size(max = 1000) String message,

            @Size(max = 200)
            @Schema(description = "Leave empty. A hidden field: a value marks the request as automated")
            String website) {}

    @Schema(name = "SignUpAccepted", description = "The same answer whether or not the email is already known")
    record Accepted(String status) {}

    @Schema(name = "ApplicantLinkRequest")
    record LinkRequest(@NotBlank @Size(max = 64) String token) {}

    @Schema(name = "ApplicantReplyRequest")
    record ReplyRequest(
            @NotBlank @Size(max = 64) String token,
            @NotBlank @Size(max = 1000) String reply) {}

    @Schema(name = "ApplicantView", description = "What the applicant sees: no contact details")
    record ApplicantView(
            String reference,

            @Schema(description = "submitted, needs_info, verified, activated, rejected or expired")
            String status,

            String businessName,
            List<String> modules,
            String term,
            String wayIn,

            @Schema(description = "The operator's question, while the status is needs_info")
            String operatorNote,

            @Schema(description = "The reason, when rejected")
            String rejectReason,

            boolean emailVerified,
            Instant createdAt) {}

    @PostMapping("/applications")
    @PublicEndpoint
    @Operation(
            summary = "Apply: the form of spec section 4; a link to confirm the email is sent (FR-ONB-01, FR-ONB-02)",
            operationId = "signUpApply")
    ResponseEntity<Accepted> apply(@Valid @RequestBody SignUpRequest request) {
        service.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Accepted("check_your_email"));
    }

    @PostMapping("/verify")
    @PublicEndpoint
    @Operation(
            summary = "Confirm the email with the link token; the application joins the queue (FR-ONB-02)",
            operationId = "signUpVerify")
    ApplicantView verify(@Valid @RequestBody LinkRequest request) {
        return service.view(request.token(), true);
    }

    @PostMapping("/status")
    @PublicEndpoint
    @Operation(summary = "The applicant page (FR-ONB-04)", operationId = "signUpStatus")
    ApplicantView status(@Valid @RequestBody LinkRequest request) {
        return service.view(request.token(), false);
    }

    @PostMapping("/reply")
    @PublicEndpoint
    @Operation(
            summary = "Answer the operator's question; the application returns to the queue (FR-ONB-04)",
            operationId = "signUpReply")
    ApplicantView reply(@Valid @RequestBody ReplyRequest request) {
        return service.reply(request.token(), request.reply());
    }
}
