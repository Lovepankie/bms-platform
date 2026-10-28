package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.help.SetupDrafts;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/help/setup} (chapter 7 section 7.11.28, issue #626, ADR-102): whether the setup helper may read a list for
 * this business, so the import screens draw its box only then. The drafts themselves are the imports' own routes, under
 * their permissions.
 */
@RestController
@RequestMapping(path = "/api/v1/help/setup", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "help")
class SetupHelperController {

    @Schema(name = "SetupHelperStatus")
    record SetupHelperStatus(
            @Schema(
                    description = "The helper may read a typed list or a file for this business; while false the box is"
                            + " not shown")
            boolean enabled,

            @Schema(description = "A photo can be read too (a provider that reads pictures is set)")
            boolean photos) {}

    private final SetupDrafts drafts;

    SetupHelperController(SetupDrafts drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Whether the setup helper can read a list, a photo or a file for this business",
            operationId = "getSetupHelperStatus")
    SetupHelperStatus status() {
        SetupDrafts.Status s = drafts.status();
        return new SetupHelperStatus(s.enabled(), s.photos());
    }
}
