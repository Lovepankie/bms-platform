package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.notifications.SmsOptOuts;
import com.rincoltech.bms.core.notifications.SmsOptOuts.Entry;
import com.rincoltech.bms.core.notifications.SmsOptOuts.Scope;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/lending/members/{member_id}/sms-opt-out} (chapter 7 section 7.11.7, FR-NTF-14, ADR-036 decision 8): the
 * member screen's control. Staff who may update the member (in the member's branch scope) opt the member out of
 * reminders or of every SMS, or lift it; a member outside the caller's scope is 404, as for every member route. The
 * table and its audit live in {@code core.notifications} ({@link SmsOptOuts}); this module only knows the member.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/members/{member_id}/sms-opt-out", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-members")
class MemberSmsOptOutController {

    private final MemberRepository members;
    private final SmsOptOuts optOuts;

    MemberSmsOptOutController(MemberRepository members, SmsOptOuts optOuts) {
        this.members = members;
        this.optOuts = optOuts;
    }

    @Schema(name = "MemberSmsOptOut")
    record Response(
            boolean optedOut,

            @Schema(
                    description =
                            "reminders (reminders and arrears) or all (every SMS, receipts included); null when not opted out")
            String scope,

            @Schema(description = "The member's number when it was recorded, masked; the number is never stored")
            String recipientMasked,

            Instant since) {

        static Response of(java.util.Optional<Entry> entry) {
            return entry.map(e -> new Response(true, e.scope().key(), e.maskedNumber(), e.createdAt()))
                    .orElseGet(() -> new Response(false, null, null, null));
        }
    }

    @Schema(name = "SetMemberSmsOptOutRequest")
    record SetRequest(@NotBlank String scope) {}

    @GetMapping
    @RequiresPermission("lending.members.read")
    @Operation(summary = "Whether a member is opted out of SMS (FR-NTF-14)", operationId = "getMemberSmsOptOut")
    @Transactional(readOnly = true)
    public Response get(@PathVariable("member_id") UUID memberId) {
        member(memberId, "lending.members.read");
        return Response.of(optOuts.status(memberId));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("lending.members.update")
    @Operation(
            summary = "Opt a member out of reminders or of all SMS, or change the scope; audited (FR-NTF-14)",
            operationId = "setMemberSmsOptOut")
    @Transactional
    public ResponseEntity<Response> set(
            @PathVariable("member_id") UUID memberId, @Valid @RequestBody SetRequest request) {
        Scope scope = Scope.of(request.scope())
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("scope", "invalid", "Use reminders or all."))));
        MemberResponse member = member(memberId, "lending.members.update");
        optOuts.optOut(memberId, member.phoneE164(), scope);
        return ResponseEntity.ok(Response.of(optOuts.status(memberId)));
    }

    @Schema(name = "LiftMemberSmsOptOutRequest")
    record LiftRequest(
            @NotBlank
            @Size(min = 3, max = 200)
            @Schema(
                    description =
                            "Why it is lifted, 3 to 200 characters, kept in the audit row. Not for names or numbers")
            String reason) {}

    /**
     * Lifting is the tenant administrator's (a field officer holds {@code lending.members.update} and must not be able to
     * undo the member's protection with one click, spec 4.14), needs a reason, and is audited with it.
     */
    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Lift a member's SMS opt-out with a reason; audited (FR-NTF-14, the tenant administrator only)",
            operationId = "liftMemberSmsOptOut")
    @Transactional
    public Response lift(@PathVariable("member_id") UUID memberId, @Valid @RequestBody LiftRequest request) {
        member(memberId, "core.settings.manage");
        optOuts.lift(memberId, request.reason());
        return Response.of(optOuts.status(memberId));
    }

    /** A member outside the principal's branch scope is indistinguishable from a missing one. */
    private MemberResponse member(UUID memberId, String permission) {
        var principal = CurrentPrincipal.require();
        return members.findById(memberId)
                .filter(m -> principal.may(permission, m.branchId()))
                .orElseThrow(ApiException::notFound);
    }
}
