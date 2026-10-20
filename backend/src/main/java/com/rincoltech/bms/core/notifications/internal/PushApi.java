package com.rincoltech.bms.core.notifications.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wire shapes of the push device routes (ADR-050, chapter 7 section 7.11.7). A subscription is the browser's own
 * {@code PushSubscription.toJSON()} reduced to what this server needs. The keys are accepted and never returned.
 */
final class PushApi {

    private PushApi() {}

    /** Whether push is on for this server, and the key a browser subscribes against. */
    @Schema(name = "PushConfig")
    record Config(boolean enabled, String publicKey) {}

    @Schema(name = "PushKeys")
    record Keys(
            @NotBlank @Size(max = 100) String p256dh,
            @NotBlank @Size(max = 30) String auth) {}

    @Schema(name = "PushSubscribeRequest")
    record SubscribeRequest(
            @NotBlank @Size(max = 2048) String endpoint,
            @NotNull @Valid Keys keys) {}

    @Schema(name = "PushUnsubscribeRequest")
    record UnsubscribeRequest(@NotBlank @Size(max = 2048) String endpoint) {}

    /** One of the caller's devices. No endpoint and no key: those are not the page's business after they are stored. */
    @Schema(name = "PushDevice")
    record Device(UUID id, String label, Instant createdAt, Instant lastUsedAt) {}

    @Schema(name = "PushDeviceList")
    record DeviceList(List<Device> items) {}
}
