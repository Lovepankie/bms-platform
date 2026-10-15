package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.SmsTemplateService.Item;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateService.Preview;
import com.rincoltech.bms.core.notifications.internal.SmsTestService.Result;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/notification-templates} and {@code /api/v1/sms/test-message} (chapter 7 section 7.11.7, FR-NTF-03,
 * ADR-036 decision 9): the tenant's own SMS wording per event type, saved under {@code If-Match} on the override's
 * version (0 while there is none), previewed with fabricated data, and tried on one number that only ever reaches the
 * sink or the allow-list ({@link SmsTestService}). Only the channel {@code sms} exists today.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "notifications")
class SmsTemplatesController {

    private final SmsTemplateService templates;
    private final SmsTestService tests;

    SmsTemplatesController(SmsTemplateService templates, SmsTestService tests) {
        this.templates = templates;
        this.tests = tests;
    }

    @Schema(name = "SmsTemplateList")
    record ListResponse(List<Item> items) {}

    @Schema(name = "SaveSmsTemplateRequest")
    record SaveRequest(
            @NotBlank
            @Size(max = 2000)
            @Schema(
                    description =
                            "The wording with {placeholders} from the event's list; 422 with the code of the first problem")
            String text) {}

    @Schema(name = "PreviewSmsTemplateRequest")
    record PreviewRequest(
            @Size(max = 2000)
            @Schema(description = "Wording to preview without saving; omitted: the saved wording, else the default")
            String text) {}

    @Schema(name = "SmsTestMessageRequest")
    record TestRequest(
            @NotBlank @Size(max = 100) String eventKey,

            @Size(max = 2000) @Schema(description = "Wording to try; omitted: the saved wording, else the default")
            String text,

            @NotBlank
            @Size(max = 30)
            @Schema(description = "A Uganda mobile number; a real send only if it is on the environment's allow-list")
            String to) {}

    @GetMapping("/api/v1/notification-templates")
    @RequiresPermission("core.notifications.read")
    @Operation(
            summary = "The SMS wording per event type with defaults, placeholders and overrides",
            operationId = "listNotificationTemplates")
    ListResponse list() {
        return new ListResponse(templates.list());
    }

    @PutMapping(
            path = "/api/v1/notification-templates/{event_key}/{channel}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.notification_templates.manage")
    @Operation(
            summary = "Save the tenant's own wording for an event, audited (FR-NTF-03)",
            operationId = "saveNotificationTemplate")
    Item save(
            @PathVariable("event_key") String eventKey,
            @PathVariable("channel") String channel,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody SaveRequest request) {
        sms(channel);
        return templates.put(eventKey, ifMatch, request.text());
    }

    @DeleteMapping("/api/v1/notification-templates/{event_key}/{channel}")
    @RequiresPermission("core.notification_templates.manage")
    @Operation(
            summary = "Go back to the default wording for an event, audited",
            operationId = "revertNotificationTemplate")
    Item revert(
            @PathVariable("event_key") String eventKey,
            @PathVariable("channel") String channel,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        sms(channel);
        return templates.revert(eventKey, ifMatch);
    }

    @PostMapping(
            path = "/api/v1/notification-templates/{event_key}/{channel}/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.notification_templates.manage")
    @Operation(
            summary = "Render wording with fabricated sample data: text, encoding, segments, cost estimate",
            operationId = "previewNotificationTemplate")
    Preview preview(
            @PathVariable("event_key") String eventKey,
            @PathVariable("channel") String channel,
            @Valid @RequestBody PreviewRequest request) {
        sms(channel);
        return templates.preview(eventKey, request.text());
    }

    @PostMapping(path = "/api/v1/sms/test-message", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.notification_templates.manage")
    @Operation(
            summary = "Try wording on one number: only ever to the sink or the allow-list, never to a real number",
            operationId = "sendSmsTestMessage")
    Result testMessage(@Valid @RequestBody TestRequest request) {
        return tests.send(request.eventKey(), request.text(), request.to());
    }

    private static void sms(String channel) {
        if (!SmsTemplateEvents.CHANNEL.equals(channel)) {
            throw ApiException.notFound();
        }
    }
}
