package com.rincoltech.bms.core.approvals.internal;

import com.rincoltech.bms.core.tenancy.PolicyChangeGuard;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/settings/approvals} (ADR-091): which actions of the business ask for a second person, and from what amount. Read
 * with the settings; changed by a tenant administrator with their proof, audited.
 */
@RestController
@RequestMapping(path = "/api/v1/settings/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class ApprovalRulesController {

    private final ApprovalRulesService service;
    private final PolicyChangeGuard guard;

    ApprovalRulesController(ApprovalRulesService service, PolicyChangeGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @Schema(name = "ApprovalRule")
    record RuleView(
            @Schema(description = "The action type, as on an approval request")
            String actionType,

            @Schema(description = "The module the action belongs to: lending or retail")
            String module,

            @Schema(description = "The action in words") String label,

            @Schema(description = "Whether the action has an amount a threshold applies to")
            boolean hasAmount,

            @Schema(description = "Whether the business may switch it off; lending actions always ask")
            boolean switchable,

            @Schema(description = "Whether the action asks for a second person now")
            boolean on,

            @Schema(
                    description =
                            "The amount from which it asks, in minor units; 0 means every one; null without an amount")
            Long thresholdMinor) {}

    @Schema(name = "ApprovalRules")
    record RulesView(
            List<RuleView> rules,

            @Schema(description = "The business's currency, for the amounts")
            String currency,

            int version) {}

    @Schema(name = "ApprovalRuleChange")
    record RuleChange(
            @NotNull @Pattern(regexp = "^[a-z][a-z0-9_]{2,62}$")
            String actionType,

            @NotNull Boolean on,

            @Min(0) @Schema(description = "Only for an action with an amount; omitted keeps the amount")
            Long thresholdMinor) {}

    @Schema(name = "UpdateApprovalRulesRequest")
    record UpdateRulesRequest(
            @NotEmpty @Size(max = 50) List<@NotNull @Valid RuleChange> rules,

            @Size(max = 20)
            @Schema(
                    description =
                            "The current code of the administrator's authenticator, when they have one (ADR-046). Never stored or logged")
            String totpCode,

            @Size(max = 200)
            @Schema(
                    description =
                            "The administrator's own password, when they have no authenticator (ADR-046). Never stored or logged")
            String password) {}

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(
            summary = "Which actions ask for a second person, and from what amount (ADR-091)",
            operationId = "getApprovalRules")
    ResponseEntity<RulesView> get() {
        RulesView rules = service.list();
        return ResponseEntity.ok().eTag(String.valueOf(rules.version())).body(rules);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Change the approval rules, with the administrator's proof, audited (ADR-091)",
            operationId = "updateApprovalRules")
    ResponseEntity<RulesView> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateRulesRequest request) {
        // The proof comes before the transaction, as for the two-step rule: a wrong one is counted even though nothing
        // is saved.
        guard.requireProof(request.totpCode(), request.password());
        RulesView rules = service.update(ifMatch, request.rules());
        return ResponseEntity.ok().eTag(String.valueOf(rules.version())).body(rules);
    }
}
