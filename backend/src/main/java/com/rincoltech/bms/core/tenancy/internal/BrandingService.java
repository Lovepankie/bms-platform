package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.AssetMeta;
import com.rincoltech.bms.core.documents.Documents.ImagePolicy;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.SettingsResponse;
import com.rincoltech.bms.kernel.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's brand (FR-TEN-08): the logo upload and what the public branding routes show. The
 * logo is a core document of the tenant; its rules are {@link #LOGO}. Nothing here takes a tenant:
 * it is the one the request host resolved.
 */
@Service
class BrandingService {

    private static final Logger log = LoggerFactory.getLogger(BrandingService.class);

    /** PNG, JPEG or WebP; 1 MB in, 128 px on the short side at least, 512 px on the long edge at most, 4 megapixels decoded at most. */
    static final ImagePolicy LOGO = new ImagePolicy(1024 * 1024, 128, 512, 4_000_000L);

    /** The only types the public logo route will ever send: never anything a browser could run. */
    static final Set<String> SERVABLE = Set.of("image/png", "image/jpeg");

    /** What the sign-in page and the app shell need; public by design. */
    @Schema(name = "Branding")
    record BrandingResponse(
            String displayName,

            @Schema(description = "#RRGGBB or null: the platform look")
            String themePrimary,

            @Schema(description = "#FFFFFF or #111111, the readable text colour on themePrimary; null with it")
            String themeText,

            @Schema(description = "Same-origin URL of the logo image, or null")
            String logoUrl) {}

    private final SettingsService settings;
    private final Documents documents;

    BrandingService(SettingsService settings, Documents documents) {
        this.settings = settings;
        this.documents = documents;
    }

    /** The file is checked and re-encoded before a transaction opens, so no connection waits on it. */
    SettingsResponse uploadLogo(byte[] bytes) {
        return settings.replaceLogo(documents.prepareImage(bytes, LOGO));
    }

    @Transactional(readOnly = true)
    BrandingResponse branding() {
        SettingsResponse s = settings.current();
        Optional<String> text = BrandColour.readableText(s.themePrimary());
        return new BrandingResponse(
                s.displayName(),
                text.isPresent() ? s.themePrimary() : null,
                text.orElse(null),
                s.logoDocumentId() == null ? null : "/api/v1/branding/logo?v=" + s.logoDocumentId());
    }

    /** The current logo's id and checksum, from the database only: a conditional request reads no storage. */
    record Logo(UUID id, AssetMeta meta) {}

    @Transactional(readOnly = true)
    Optional<Logo> logo() {
        SettingsResponse s = settings.current();
        if (s.logoDocumentId() == null) {
            return Optional.empty();
        }
        return documents
                .publicAssetMeta(TenantDocumentAccess.SUBJECT, s.logoDocumentId())
                .filter(m -> SERVABLE.contains(m.contentType()))
                .map(m -> new Logo(s.logoDocumentId(), m));
    }

    /**
     * The logo's bytes: from a small in-process cache (a document is immutable, so its id is a safe
     * key, and {@link #logo()} has just proved it is this tenant's) or from storage. A storage
     * failure is a 503 on this public route, never a 500; a missing object is empty (404).
     */
    Optional<byte[]> bytes(Logo logo) {
        byte[] cached = cache.get(logo.id());
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<byte[]> loaded;
        try {
            loaded = documents.publicAssetBytes(TenantDocumentAccess.SUBJECT, logo.id());
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("the tenant logo could not be read from storage", e);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "logo_unavailable",
                    "Logo unavailable",
                    "The logo cannot be read right now; try again shortly.");
        }
        loaded.ifPresent(b -> cache.put(logo.id(), b));
        return loaded;
    }

    /** At most {@value #CACHE_ENTRIES} logos of at most about 1 MB each, least recently used out. */
    static final int CACHE_ENTRIES = 16;

    private final Map<UUID, byte[]> cache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, byte[]> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            });
}
