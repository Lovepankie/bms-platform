package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.PushApi.Config;
import com.rincoltech.bms.core.notifications.internal.PushApi.Device;
import com.rincoltech.bms.core.notifications.internal.PushApi.DeviceList;
import com.rincoltech.bms.core.notifications.internal.PushApi.SubscribeRequest;
import com.rincoltech.bms.core.notifications.internal.PushApi.UnsubscribeRequest;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notifications on the caller's own devices (ADR-050; chapter 7 section 7.11.7). Any signed-in staff user manages their own
 * devices and nobody else's, so these routes are {@code authenticated} and take no permission: the user is the principal.
 */
@RestController
@RequestMapping(path = "/api/v1/notifications/push", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "notifications")
class PushController {

    private final PushDevices devices;

    PushController(PushDevices devices) {
        this.devices = devices;
    }

    @GetMapping("/config")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary =
                    "Whether this server sends notifications, and the public key a browser subscribes against (ADR-050)",
            operationId = "getPushConfig")
    Config config() {
        return devices.config();
    }

    @GetMapping("/subscriptions")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary =
                    "The caller's own devices that receive notifications: label, when added, when last used (ADR-050)",
            operationId = "listPushDevices")
    DeviceList list() {
        return devices.mine();
    }

    @PostMapping(path = "/subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Register this device for notifications, or refresh it: one row per endpoint, moved to the caller"
                    + " when another person used the device before (ADR-050)",
            operationId = "subscribePushDevice")
    ResponseEntity<Device> subscribe(
            @Valid @RequestBody SubscribeRequest request,
            @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        return ResponseEntity.status(HttpStatus.CREATED).body(devices.subscribe(request, userAgent));
    }

    @PostMapping(path = "/subscriptions/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Stop notifications on this device by its endpoint; nothing to remove is still 204 (ADR-050)",
            operationId = "unsubscribePushDevice")
    ResponseEntity<Void> unsubscribe(@Valid @RequestBody UnsubscribeRequest request) {
        devices.unsubscribe(request.endpoint());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscriptions/{device_id}")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "Remove one of the caller's own devices (ADR-050)", operationId = "removePushDevice")
    ResponseEntity<Void> remove(@PathVariable("device_id") UUID deviceId) {
        devices.remove(deviceId);
        return ResponseEntity.noContent().build();
    }
}
