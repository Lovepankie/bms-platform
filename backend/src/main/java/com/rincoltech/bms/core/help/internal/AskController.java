package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.help.internal.AskApi.AskAnswer;
import com.rincoltech.bms.core.help.internal.AskApi.AskRequest;
import com.rincoltech.bms.core.help.internal.AskApi.AskStatus;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/help/ask} (chapter 7 section 7.11.28, issue #20, ADR-096): any signed-in member of staff asks how to do
 * something, like reading How do I...?, so no matrix permission; what the answer may draw on is filtered to the asker's
 * own permissions. Not transactional: a question may wait on a provider, and no connection is held meanwhile.
 */
@RestController
@RequestMapping(path = "/api/v1/help/ask", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "help")
class AskController {

    private final AskService service;

    AskController(AskService service) {
        this.service = service;
    }

    @GetMapping
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Whether Ask BMS is on for this business, and the corpus revision",
            operationId = "getAskStatus")
    AskStatus status() {
        return service.status();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary =
                    "Ask how to do something: an answer phrased from the help articles the caller may read, citing one,"
                            + " or the matching articles; 403 ask_bms_off while it is off for the business; 429 ask_rate_limited past"
                            + " the person's questions an hour",
            operationId = "askBms")
    AskAnswer ask(@Valid @RequestBody AskRequest request) {
        return service.ask(request);
    }
}
