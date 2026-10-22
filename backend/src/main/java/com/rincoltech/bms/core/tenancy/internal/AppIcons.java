package com.rincoltech.bms.core.tenancy.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The installed app's icons drawn from the tenant logo (ADR-066, issue #446): a square of the theme
 * colour with the logo centred inside it, PNG, with Java2D alone. The plain icons and the iPhone's
 * keep the logo inside 80 percent of the side; the maskable ones keep it inside the safe zone, the
 * circle of 40 percent radius that every launcher mask leaves visible. The rendered icons are kept
 * per business under one version (the drawing's {@link #RENDER_VERSION}, the logo's checksum and the
 * colour), so a logo or colour change replaces that business's icons rather than adding to them.
 */
final class AppIcons {

    private static final Logger log = LoggerFactory.getLogger(AppIcons.class);

    /** Raised whenever the drawing changes, so every ETag and manifest {@code ?v=} changes with it. */
    static final int RENDER_VERSION = 2;

    /** The icons the manifest and the page name; the file name is also the static BMS icon's under {@code /icons/}. */
    enum Variant {
        ICON_192("icon-192.png", 192, false),
        ICON_512("icon-512.png", 512, false),
        MASKABLE_192("icon-maskable-192.png", 192, true),
        MASKABLE_512("icon-maskable-512.png", 512, true),
        APPLE_TOUCH("apple-touch-icon.png", 180, false);

        final String file;
        final int size;
        final boolean maskable;

        Variant(String file, int size, boolean maskable) {
            this.file = file;
            this.size = size;
            this.maskable = maskable;
        }

        static Optional<Variant> of(String file) {
            return Arrays.stream(values()).filter(v -> v.file.equals(file)).findFirst();
        }
    }

    /** Share of the side the logo may fill on a plain icon. */
    static final double PLAIN_BOX = 0.8;
    /** Radius of the maskable safe zone as a share of the side (the W3C manifest's 80 percent circle). */
    static final double SAFE_RADIUS = 0.4;

    private AppIcons() {}

    /** The icon from the logo's bytes (PNG or JPEG, as stored) on {@code background} ({@code #RRGGBB}). */
    static byte[] render(byte[] logo, String background, Variant variant) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(logo));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (source == null) {
            throw new IllegalArgumentException("the logo is not a readable image");
        }
        int size = variant.size;
        double w = source.getWidth();
        double h = source.getHeight();
        // Plain: the longer side fills 80 percent. Maskable: the logo's diagonal is the safe circle's diameter.
        double scale =
                variant.maskable ? (2 * SAFE_RADIUS * size) / Math.hypot(w, h) : (PLAIN_BOX * size) / Math.max(w, h);
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = icon.createGraphics();
        try {
            g.setColor(Color.decode(background));
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(shrink(source, dw, dh), (size - dw) / 2, (size - dh) / 2, dw, dh, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(icon, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Halves the image until one bicubic step is left, so a large logo does not alias when it becomes small. */
    private static BufferedImage shrink(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            BufferedImage half =
                    new BufferedImage(current.getWidth() / 2, current.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = half.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(current, 0, 0, half.getWidth(), half.getHeight(), null);
            } finally {
                g.dispose();
            }
            current = half;
        }
        return current;
    }

    /**
     * The icons of at most {@value #CACHE_BUSINESSES} businesses, least recently used out. One entry per
     * business holds at most the five variants of its current version (together well under 1 MB), so
     * the cache grows with the number of businesses whose icons are asked for, never with their changes.
     */
    static final int CACHE_BUSINESSES = 1000;

    /** A render that failed for this version: the BMS icon is served until the logo or colour changes. */
    private static final byte[] FAILED = new byte[0];

    private record Rendered(String version, Map<Variant, byte[]> icons) {}

    private static final Map<UUID, Rendered> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Rendered> eldest) {
            return size() > CACHE_BUSINESSES;
        }
    };

    /**
     * The business's icon of this version, drawn from {@code logo} on {@code background} at most once
     * however many requests miss at the same moment. Empty means serve the BMS icon: the logo could
     * not be read now (not kept, so the next request tries again), or it could not be drawn (kept for
     * this version, and logged once).
     */
    static Optional<byte[]> cached(
            UUID business, String version, Variant variant, Supplier<Optional<byte[]>> logo, String background) {
        Rendered rendered;
        synchronized (CACHE) {
            rendered = CACHE.get(business);
            if (rendered == null || !rendered.version().equals(version)) {
                rendered = new Rendered(version, new ConcurrentHashMap<>());
                CACHE.put(business, rendered);
            }
        }
        byte[] png = rendered.icons()
                .computeIfAbsent(
                        variant,
                        v -> logo.get()
                                .map(bytes -> renderOrFailed(bytes, background, v, business))
                                .orElse(null));
        return png == null || png == FAILED ? Optional.empty() : Optional.of(png);
    }

    private static byte[] renderOrFailed(byte[] logo, String background, Variant variant, UUID business) {
        try {
            return render(logo, background, variant);
        } catch (RuntimeException e) {
            log.warn(
                    "the logo of business {} could not be drawn as the {} app icon; the BMS icon is served: {}",
                    business,
                    variant.file,
                    e.toString());
            return FAILED;
        }
    }
}
