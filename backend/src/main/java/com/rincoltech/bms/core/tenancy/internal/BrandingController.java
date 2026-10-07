package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.internal.BrandingService.BrandingResponse;
import com.rincoltech.bms.core.tenancy.internal.BrandingService.Logo;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/branding} (chapter 7 section 7.11.4, FR-TEN-08): the tenant's name, theme colour
 * and logo with no sign-in, so the sign-in page can show them. The tenant is the one the request
 * host resolves (an unknown host is the usual 404), never a parameter. Public by design: only the
 * display name, a colour, one re-encoded image and the enabled module keys (#99) are exposed.
 */
@RestController
@RequestMapping(path = "/api/v1/branding")
@Tag(name = "branding")
class BrandingController {

    private final BrandingService service;

    BrandingController(BrandingService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PublicEndpoint
    @Operation(
            summary = "The tenant's display name, theme colour, logo URL and enabled modules (public)",
            operationId = "getBranding")
    BrandingResponse branding() {
        return service.branding();
    }

    /**
     * The type is the stored, re-encoded one (PNG or JPEG only; anything else is a 404), never
     * derived from a name, and {@code nosniff} stops a browser from second-guessing it. The ETag is
     * the stored checksum, compared before any storage read, so a 304 costs the database only.
     * {@code v} (the logo's document id, in the URL the branding response gives) only busts caches.
     */
    @GetMapping(path = "/logo", produces = MediaType.ALL_VALUE)
    @PublicEndpoint
    @Operation(summary = "The tenant's logo image (public, cacheable)", operationId = "getBrandingLogo")
    @ApiResponse(responseCode = "304", description = "The ETag still matches")
    @ApiResponse(responseCode = "404", description = "No logo, or an unknown host (unknown_tenant)")
    @ApiResponse(responseCode = "503", description = "Storage cannot be read (logo_unavailable)")
    ResponseEntity<byte[]> logo(
            @RequestParam(name = "v", required = false) UUID version,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Logo logo = service.logo().orElseThrow(ApiException::notFound);
        String etag = "\"" + logo.meta().sha256() + "\"";
        boolean notModified = matches(ifNoneMatch, etag);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        notModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header(HttpHeaders.VARY, "Host")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("Content-Security-Policy", "default-src 'none'; sandbox");
        if (notModified) {
            return response.build();
        }
        byte[] bytes = service.bytes(logo).orElseThrow(ApiException::notFound);
        return response.contentType(MediaType.parseMediaType(logo.meta().contentType()))
                .body(bytes);
    }

    /** RFC 9110: a list of tags, the weak prefix ignored, or {@code *}. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*") || (tag.startsWith("W/") ? tag.substring(2) : tag).equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
