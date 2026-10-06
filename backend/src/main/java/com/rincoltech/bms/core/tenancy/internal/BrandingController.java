package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.documents.Documents.Content;
import com.rincoltech.bms.core.tenancy.internal.BrandingService.BrandingResponse;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/branding} (chapter 7 section 7.11.4, FR-TEN-08): the tenant's name, theme colour
 * and logo with no sign-in, so the sign-in page can show them. The tenant is the one the request
 * host resolves (an unknown host is the usual 404), never a parameter. Public by design: only the
 * display name, a colour and one re-encoded image are exposed.
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
    @Operation(summary = "The tenant's display name, theme colour and logo URL (public)", operationId = "getBranding")
    BrandingResponse branding() {
        return service.branding();
    }

    /**
     * The type is the stored, re-encoded one (PNG or JPEG only; anything else is a 404), never
     * derived from a name, and {@code nosniff} stops a browser from second-guessing it.
     */
    @GetMapping(path = "/logo", produces = MediaType.ALL_VALUE)
    @PublicEndpoint
    @Operation(summary = "The tenant's logo image (public, cacheable)", operationId = "getBrandingLogo")
    ResponseEntity<byte[]> logo(@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Content logo = service.logo().orElseThrow(ApiException::notFound);
        String etag = "\"" + logo.sha256() + "\"";
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        etag.equals(ifNoneMatch) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("Content-Security-Policy", "default-src 'none'; sandbox");
        if (etag.equals(ifNoneMatch)) {
            return response.build();
        }
        return response.contentType(MediaType.parseMediaType(logo.contentType()))
                .body(logo.bytes());
    }
}
