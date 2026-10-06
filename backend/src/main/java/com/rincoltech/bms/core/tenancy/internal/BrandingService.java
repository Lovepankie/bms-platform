package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.Content;
import com.rincoltech.bms.core.documents.Documents.ImagePolicy;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.SettingsResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's brand (FR-TEN-08): the logo upload and what the public branding routes show. The
 * logo is a core document of the tenant; its rules are {@link #LOGO}. Nothing here takes a tenant:
 * it is the one the request host resolved.
 */
@Service
class BrandingService {

    /** PNG, JPEG or WebP; 1 MB in, 128 px on the short side at least, 512 px on the long edge at most. */
    static final ImagePolicy LOGO = new ImagePolicy(1024 * 1024, 128, 512);

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

    @Transactional(readOnly = true)
    Optional<Content> logo() {
        SettingsResponse s = settings.current();
        if (s.logoDocumentId() == null) {
            return Optional.empty();
        }
        return documents.content(s.logoDocumentId()).filter(c -> SERVABLE.contains(c.contentType()));
    }
}
