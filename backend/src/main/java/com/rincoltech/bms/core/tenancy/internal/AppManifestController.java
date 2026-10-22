package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.internal.AppIcons.Variant;
import com.rincoltech.bms.core.tenancy.internal.BrandingService.AppBrand;
import com.rincoltech.bms.core.tenancy.internal.BrandingService.Logo;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code /api/v1/branding/app} (ADR-066, issue #446, chapter 7 section 7.11.4): the installed app's
 * web app manifest and icons for the business the host resolves, so its home screen icon is the
 * business's own logo. Public like the rest of branding. A host that resolves no business, and a
 * business with no logo, get the BMS icons the web container serves under {@code /icons/}.
 */
@RestController
@RequestMapping(path = AppManifestController.BASE)
@Tag(name = "branding")
class AppManifestController {

    static final String BASE = "/api/v1/branding/app";
    static final String MANIFEST_TYPE = "application/manifest+json";

    /** The colour of the static manifest, for a business without a readable one. */
    static final String DEFAULT_THEME = "#0077B6";

    static final int SHORT_NAME_MAX = 12;

    /** A cut at a word boundary is preferred when it keeps at least this many characters. */
    static final int SHORT_NAME_WORD_MIN = 6;

    private static final Logger log = LoggerFactory.getLogger(AppManifestController.class);

    @Schema(name = "AppManifest", description = "A W3C web app manifest")
    record AppManifest(
            String id,
            String name,
            String shortName,
            String description,
            String lang,
            String startUrl,
            String scope,
            String display,
            String backgroundColor,

            @Schema(defaultValue = DEFAULT_THEME, description = "The business's colour, or this one when it has none")
            String themeColor,

            List<Icon> icons) {}

    @Schema(name = "AppManifestIcon")
    record Icon(String src, String sizes, String type, String purpose) {}

    private final BrandingService service;
    private final ObjectMapper mapper;

    AppManifestController(BrandingService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** Revalidated on every load ({@code no-cache} with an ETag), so a new name, colour or logo reaches the phone. */
    @GetMapping(path = "/manifest.webmanifest", produces = MANIFEST_TYPE)
    @PublicEndpoint
    @Operation(summary = "The installed app's manifest for this business (public)", operationId = "getAppManifest")
    @ApiResponse(responseCode = "200", description = "The manifest of the host's business, or the BMS one")
    @ApiResponse(responseCode = "304", description = "The ETag still matches")
    ResponseEntity<AppManifest> manifest(
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        AppManifest manifest = manifestFor(brand());
        String etag = "\"" + sha256(mapper.writeValueAsBytes(manifest)) + "\"";
        boolean notModified = BrandingController.matches(ifNoneMatch, etag);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        notModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.VARY, "Host");
        return notModified ? response.build() : response.body(manifest);
    }

    /**
     * One icon by its file name. With a logo it is drawn on the theme colour and cached per business
     * by the drawing's version, the logo's checksum and the colour (with the variant, the ETag);
     * without one, when storage cannot be read, or when the logo cannot be drawn, it is a redirect to
     * the BMS icon of the same name, never a 500. {@code v} only busts caches.
     */
    @GetMapping(path = "/{file}", produces = MediaType.IMAGE_PNG_VALUE)
    @PublicEndpoint
    @Operation(
            summary = "An installed app icon drawn from the business's logo (public, cacheable)",
            operationId = "getAppIcon")
    @ApiResponse(responseCode = "200", description = "The PNG icon drawn from the logo")
    @ApiResponse(responseCode = "302", description = "No logo here: the BMS icon of the same name")
    @ApiResponse(responseCode = "304", description = "The ETag still matches")
    @ApiResponse(responseCode = "404", description = "Not one of the icon file names")
    ResponseEntity<byte[]> icon(
            @Schema(
                            allowableValues = {
                                "icon-192.png",
                                "icon-512.png",
                                "icon-maskable-192.png",
                                "icon-maskable-512.png",
                                "apple-touch-icon.png"
                            })
                    @PathVariable
                    String file,
            @RequestParam(name = "v", required = false) String version,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Variant variant = Variant.of(file).orElseThrow(ApiException::notFound);
        AppBrand brand = brand();
        if (brand.logo().isEmpty()) {
            return platformIcon(variant);
        }
        Logo logo = brand.logo().get();
        String colour = theme(brand);
        String drawing = AppIcons.RENDER_VERSION + ":" + logo.meta().sha256() + ":" + colour;
        String etag = "\"" + sha256((drawing + ":" + variant.file).getBytes(StandardCharsets.UTF_8)) + "\"";
        boolean notModified = BrandingController.matches(ifNoneMatch, etag);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        notModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header(HttpHeaders.VARY, "Host")
                .header("X-Content-Type-Options", "nosniff");
        if (notModified) {
            return response.build();
        }
        UUID business = TenantContext.current().orElseThrow();
        Optional<byte[]> png = AppIcons.cached(business, drawing, variant, () -> logoBytes(logo), colour);
        if (png.isEmpty()) {
            return platformIcon(variant);
        }
        return response.contentType(MediaType.IMAGE_PNG).body(png.get());
    }

    /** A logo that storage cannot give now is drawn as the BMS icon rather than a broken one. */
    private Optional<byte[]> logoBytes(Logo logo) {
        try {
            return service.bytes(logo);
        } catch (ApiException e) {
            log.warn("the tenant logo is unavailable for an app icon: {}", e.code());
            return Optional.empty();
        }
    }

    /** The business of the host, or the platform's look when the host resolves none. */
    private AppBrand brand() {
        return TenantContext.current().isPresent()
                ? service.appBrand()
                : new AppBrand(null, Optional.empty(), Optional.empty());
    }

    static AppManifest manifestFor(AppBrand brand) {
        String name = brand.displayName() == null || brand.displayName().isBlank()
                ? "BMS"
                : brand.displayName().strip();
        List<Icon> icons;
        if (brand.logo().isPresent()) {
            String v = "?v=" + AppIcons.RENDER_VERSION + "-"
                    + brand.logo().get().id() + "-" + theme(brand).substring(1);
            icons = List.of(
                    new Icon(BASE + "/icon-192.png" + v, "192x192", "image/png", "any"),
                    new Icon(BASE + "/icon-512.png" + v, "512x512", "image/png", "any"),
                    new Icon(BASE + "/icon-maskable-192.png" + v, "192x192", "image/png", "maskable"),
                    new Icon(BASE + "/icon-maskable-512.png" + v, "512x512", "image/png", "maskable"));
        } else {
            icons = List.of(
                    new Icon("/icons/icon-192.png", "192x192", "image/png", "any"),
                    new Icon("/icons/icon-512.png", "512x512", "image/png", "any"),
                    new Icon("/icons/icon-maskable-192.png", "192x192", "image/png", "maskable"),
                    new Icon("/icons/icon-maskable-512.png", "512x512", "image/png", "maskable"),
                    new Icon("/icon.svg", "any", "image/svg+xml", "any"));
        }
        return new AppManifest(
                "/staff",
                name,
                shortName(name),
                "Business management for lenders, retailers and their members",
                "en",
                "/staff",
                "/",
                "standalone",
                "#ffffff",
                theme(brand),
                icons);
    }

    /**
     * At most {@value #SHORT_NAME_MAX} characters as a reader counts them (grapheme clusters, so an
     * emoji or an accented letter is never cut in half), cut at the last word boundary when that keeps
     * at least {@value #SHORT_NAME_WORD_MIN} of them.
     */
    static String shortName(String name) {
        BreakIterator characters = BreakIterator.getCharacterInstance();
        characters.setText(name);
        int minimum = -1;
        int cut = characters.first();
        for (int n = 0; n < SHORT_NAME_MAX; n++) {
            cut = characters.next();
            if (cut == BreakIterator.DONE) {
                return name;
            }
            if (n + 1 == SHORT_NAME_WORD_MIN) {
                minimum = cut;
            }
        }
        if (characters.next() == BreakIterator.DONE) {
            return name;
        }
        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(name);
        int word = words.isBoundary(cut) ? cut : words.preceding(cut);
        int end = word != BreakIterator.DONE && word >= minimum ? word : cut;
        return name.substring(0, end).strip();
    }

    private static String theme(AppBrand brand) {
        return brand.themeColour().orElse(DEFAULT_THEME);
    }

    private static ResponseEntity<byte[]> platformIcon(Variant variant) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/icons/" + variant.file))
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.VARY, "Host")
                .build();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
