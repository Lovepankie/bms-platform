package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.AssetMeta;
import com.rincoltech.bms.core.documents.Documents.ImagePolicy;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.core.tenancy.internal.BranchController.BranchResponse;
import com.rincoltech.bms.core.tenancy.internal.SettingsController.SettingsResponse;
import com.rincoltech.bms.kernel.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
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
class BrandingService implements Letterheads {

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
            String logoUrl,

            @Schema(
                    description =
                            "Keys of the modules the tenant has switched on, sorted (#99): what the landing and sign-in copy may promise")
            List<String> modules) {}

    private final SettingsService settings;
    private final Documents documents;
    private final TenantModules modules;
    private final BranchService branches;

    BrandingService(SettingsService settings, Documents documents, TenantModules modules, BranchService branches) {
        this.settings = settings;
        this.documents = documents;
        this.modules = modules;
        this.branches = branches;
    }

    /** The file is checked and re-encoded before a transaction opens, so no connection waits on it. */
    SettingsResponse uploadLogo(byte[] bytes) {
        return settings.replaceLogo(documents.prepareImage(bytes, LOGO));
    }

    /** A shop's own logo (issue #461, ADR-068): the rules and the processing of the business logo, checked the same way. */
    BranchResponse uploadBranchLogo(UUID branchId, byte[] bytes) {
        return branches.replaceLogo(branchId, documents.prepareImage(bytes, LOGO));
    }

    @Transactional(readOnly = true)
    BrandingResponse branding() {
        SettingsResponse s = settings.current();
        Optional<String> text = BrandColour.readableText(s.themePrimary());
        return new BrandingResponse(
                s.displayName(),
                text.isPresent() ? s.themePrimary() : null,
                text.orElse(null),
                s.logoDocumentId() == null ? null : LOGO_PATH + "?v=" + s.logoDocumentId(),
                modules.enabledKeys());
    }

    /** What the installed app's manifest and icons are drawn from (ADR-066): the name, a readable colour or none, the logo. */
    record AppBrand(String displayName, Optional<String> themeColour, Optional<Logo> logo) {}

    @Transactional(readOnly = true)
    AppBrand appBrand() {
        SettingsResponse s = settings.current();
        Optional<String> colour = BrandColour.readableText(s.themePrimary())
                .map(t -> s.themePrimary().toUpperCase());
        return new AppBrand(s.displayName(), colour, logo());
    }

    /** The platform's own colour (the PWA's default {@code --brand}), for a tenant without a readable one. */
    static final String DEFAULT_BRAND = "#0D5C75";

    @Override
    @Transactional(readOnly = true)
    public Letterhead current() {
        return letterhead(Optional.empty());
    }

    @Override
    @Transactional(readOnly = true)
    public Letterhead forBranch(UUID branchId) {
        return letterhead(
                branchId == null
                        ? Optional.empty()
                        : branchLogo(branchId).map(l -> new ShopLogo(l, BranchService.logoUrl(branchId, l.id()))));
    }

    @Override
    @Transactional(readOnly = true)
    public Letterhead forBranchCode(String code) {
        return letterhead(
                code == null
                        ? Optional.empty()
                        : branches.logoByCode(code)
                                .flatMap(b -> servable(b.documentId())
                                        .map(l -> new ShopLogo(l, BranchService.logoUrl(b.branchId(), l.id())))));
    }

    /**
     * The shop's own logo when its bytes can be read, else the business logo (issue #461, ADR-068): a shop logo whose stored file
     * is gone or unreadable falls back to the business logo rather than leaving the document without one.
     */
    private Letterhead letterhead(Optional<ShopLogo> branchLogo) {
        SettingsResponse s = settings.current();
        String colour = s.themePrimary() != null
                        && BrandColour.readableText(s.themePrimary()).isPresent()
                ? s.themePrimary().toUpperCase()
                : DEFAULT_BRAND;
        Optional<Letterheads.Logo> logo = branchLogo.flatMap(b -> letterheadLogo(b.logo(), b.path()));
        if (logo.isEmpty()) {
            logo = logo().flatMap(l -> letterheadLogo(l, LOGO_PATH + "?v=" + l.id()));
        }
        return new Letterhead(
                s.displayName(),
                colour,
                BrandColour.readableText(colour).orElse(BrandColour.LIGHT_TEXT),
                s.receiptFooter() == null ? "" : s.receiptFooter(),
                logo);
    }

    /** A shop's own logo with the path it is served at. */
    private record ShopLogo(Logo logo, String path) {}

    /** A logo's bytes for a document, or empty when they cannot be read: a document without its logo is better than none. */
    private Optional<Letterheads.Logo> letterheadLogo(Logo l, String path) {
        try {
            return bytes(l).map(b -> new Letterheads.Logo(l.meta().contentType(), b, path));
        } catch (ApiException e) {
            log.warn("a logo is unavailable for a generated document: {}", e.code());
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> logoPath() {
        return logo().map(l -> LOGO_PATH + "?v=" + l.id());
    }

    static final String LOGO_PATH = "/api/v1/branding/logo";

    /** The current logo's id and checksum, from the database only: a conditional request reads no storage. */
    record Logo(UUID id, AssetMeta meta) {}

    @Transactional(readOnly = true)
    Optional<Logo> logo() {
        return Optional.ofNullable(settings.current().logoDocumentId()).flatMap(this::servable);
    }

    /**
     * A shop's own logo (issue #461, ADR-068), from the database only, like {@link #logo()}. Empty for a branch with none and
     * for a branch of another business, which row-level security hides.
     */
    @Transactional(readOnly = true)
    Optional<Logo> branchLogo(UUID branchId) {
        return branches.logoDocumentId(branchId).flatMap(this::servable);
    }

    /** A core.tenant document of this tenant whose stored type the public routes may send. */
    private Optional<Logo> servable(UUID documentId) {
        return documents
                .publicAssetMeta(TenantDocumentAccess.SUBJECT, documentId)
                .filter(m -> SERVABLE.contains(m.contentType()))
                .map(m -> new Logo(documentId, m));
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
