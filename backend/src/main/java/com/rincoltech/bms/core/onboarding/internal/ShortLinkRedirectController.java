package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.ShortLinkTarget;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the one Cloudflare rule on rincoltech.com sends a short link (issue #708, ADR-108 decision 3), on the platform host,
 * with no sign-in: {@code /platform/short-links/<name>} answers a 302 to the shop's current address, to the new address
 * while an old name still forwards, and otherwise to the page "No shop uses this link". {@code /platform/short-link-rule}
 * gives the operator the reserved names and the rule built from them, to copy into the Cloudflare dashboard.
 */
@RestController
@Tag(name = "sign-up")
class ShortLinkRedirectController {

    private final ShortNames names;
    private final PlatformHost hosts;
    private final List<ShortLinkTarget> targets;

    private final SignUpRateLimiter limiter;

    /** What a phone shows when one address asked for too many names no shop has; it names no business. */
    static final String TOO_MANY_PAGE = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex"><title>Too many tries</title>
            <style>body { margin: 0; padding: 32px 16px; font: 16px/1.5 system-ui, sans-serif; color: #1f2328;
            background: #ffffff; } main { max-width: 32rem; margin: 0 auto; } h1 { font-size: 1.4rem; }</style></head>
            <body><main><h1>Too many tries</h1><p>Wait a few minutes and open the link again.</p></main></body>
            </html>
            """;

    ShortLinkRedirectController(
            ShortNames names, PlatformHost hosts, List<ShortLinkTarget> targets, SignUpRateLimiter limiter) {
        this.names = names;
        this.hosts = hosts;
        this.targets = targets;
        this.limiter = limiter;
    }

    @Schema(name = "ShortLinkRule")
    record Rule(
            @Schema(description = "The host the short links are on, for example rincoltech.com")
            String shortLinkHost,

            @Schema(description = "Names the rule leaves to the website and sign-up refuses")
            List<String> reserved,

            @Schema(description = "The single redirect's custom filter expression")
            String expression,

            @Schema(description = "The dynamic target URL expression")
            String target,

            int statusCode) {}

    // The rule forwards /name/ as typed, so the trailing slash form is the same link.
    @GetMapping({"/api/v1/platform/short-links/{name}", "/api/v1/platform/short-links/{name}/"})
    @PublicEndpoint
    @Operation(
            summary =
                    "Follow a short link: 302 to the shop, to its new address while an old name forwards, or to the page 'No shop uses this link' (ADR-108)",
            operationId = "followShortLink")
    @ApiResponse(
            responseCode = "302",
            description = "Location is the shop's address, a module's page or the not found page",
            content = @Content)
    @ApiResponse(
            responseCode = "404",
            description = "A module's link that is wrong or has expired, such as a receipt link (issue #760)",
            content = @Content(mediaType = "text/html", schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "429",
            description = "Too many names no shop has from this address in ten minutes: a plain page",
            content = @Content(mediaType = "text/html", schema = @Schema(type = "string")))
    ResponseEntity<String> follow(@PathVariable("name") String name) {
        // JUSTIFICATION-A3: after the shops, the modules' own links, such as a receipt link (issue #760, ADR-116).
        String shop = names.target(name).orElse(null);
        if (shop == null) {
            // A name no shop has may be a module's link, looked up in every business's rows: limited per address
            // (review
            // of #762). A shop's own short link never is.
            if (!limiter.tryAcquire(SignUpRateLimiter.MODULE_LINK_PER_IP, RequestContext.clientIp())) {
                // A plain page, since a customer's phone opens this address (second review of #762).
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .header("Cache-Control", "no-store")
                        .header("Retry-After", "600")
                        .body(TOO_MANY_PAGE);
            }
        }
        ShortLinkTarget.Answer other = shop != null
                ? null
                : targets.stream()
                        .map(t -> t.follow(name))
                        .flatMap(Optional::stream)
                        .findFirst()
                        .orElse(null);
        if (other != null && other.page() != null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .header("Cache-Control", "no-store")
                    .body(other.page());
        }
        String location = shop != null
                ? hosts.tenantOrigin(shop) + "/"
                : other != null
                        ? hosts.tenantOrigin(other.slug()) + other.path()
                        : hosts.platformOrigin() + "/link-not-found?name="
                                + URLEncoder.encode(ShortNames.normalise(name), StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .header("Cache-Control", "no-store")
                .build();
    }

    @GetMapping(path = "/api/v1/platform/short-link-rule", produces = MediaType.APPLICATION_JSON_VALUE)
    @PublicEndpoint
    @Operation(
            summary =
                    "The reserved names and the one Cloudflare single redirect built from them, for the operator (ADR-108)",
            operationId = "getShortLinkRule")
    Rule rule() {
        ShortNames.Rule r = names.rule(hosts.platformOrigin());
        return new Rule(r.shortLinkHost(), r.reserved(), r.expression(), r.target(), r.statusCode());
    }
}
