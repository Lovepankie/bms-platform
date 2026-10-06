package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.TenantContext;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-DOC-02, FR-DOC-03 and the upload rules of chapter 8 section 8.8. Uploads are checked by
 * content (never by name), images are decoded and written again so no metadata survives, and
 * every download URL is issued only after the owning module's {@link DocumentAccess} says yes.
 */
@Service
class DocumentService implements Documents {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** Bounds decoding: a small compressed image can declare a huge canvas. */
    static final long MAX_PIXELS = 16_000_000L;

    /** Image re-encodes at once; the staging host is a shared ARM board. */
    static final int MAX_CONCURRENT_REENCODES = 2;

    static final long REENCODE_WAIT_SECONDS = 10;

    static final Duration URL_TTL = Duration.ofMinutes(5);

    private static final String COLUMNS = """
            id, doc_type, subject_type, subject_id, branch_id, content_type, size_bytes, sha256, created_at, created_by
            """;

    /** What a file is, decided from its first bytes (chapter 8 section 8.8). */
    enum Kind {
        PDF("application/pdf", "pdf", null),
        PNG("image/png", "png", "png"),
        JPEG("image/jpeg", "jpg", "jpeg");

        final String contentType;
        final String extension;
        final String imageFormat;

        Kind(String contentType, String extension, String imageFormat) {
            this.contentType = contentType;
            this.extension = extension;
            this.imageFormat = imageFormat;
        }

        static Kind of(byte[] b) {
            if (startsWith(b, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
                return PDF;
            }
            if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
                return PNG;
            }
            if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
                return JPEG;
            }
            throw unsupported();
        }

        private static boolean startsWith(byte[] b, int... magic) {
            if (b.length < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if ((b[i] & 0xFF) != magic[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    record DownloadUrl(String url, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final List<DocumentAccess> access;
    private final AuditLog audit;
    private final BusinessClock clock;
    /** Package-private so a test can exhaust the slots. */
    final Semaphore reencodes = new Semaphore(MAX_CONCURRENT_REENCODES);

    /** Writes a denial's audit row in its own transaction, so it survives the refused request. */
    private final TransactionTemplate ownTransaction;

    DocumentService(
            JdbcClient jdbc,
            ObjectStorage storage,
            List<DocumentAccess> access,
            AuditLog audit,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
        this.storage = storage;
        this.access = List.copyOf(access);
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public StoredDocument upload(Upload upload) {
        return store(prepare(upload.bytes()), upload.subjectType(), upload.subjectId(), upload.branchId());
    }

    /** No transaction here: the wait for a re-encode slot must not hold a database connection. */
    @Override
    public Prepared prepare(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw ApiException.validation(List.of(new FieldProblem("file", "required", "The file is empty.")));
        }
        if (raw.length > MAX_BYTES) {
            throw fileTooLarge();
        }
        Kind kind = Kind.of(raw);
        byte[] stored = kind.imageFormat == null ? raw : reencodeBounded(raw, kind.imageFormat);
        if (stored.length > MAX_BYTES) {
            // Re-encoding can grow a file; the stored bytes obey the same limit.
            throw fileTooLarge();
        }
        return new Prepared(stored, kind.contentType, kind.extension, sha256(stored));
    }

    /** No transaction here either. The type comes from the bytes: SVG is refused, never re-encoded. */
    @Override
    public Prepared prepareImage(byte[] raw, ImagePolicy policy) {
        if (raw == null || raw.length == 0) {
            throw ApiException.validation(List.of(new FieldProblem("file", "required", "The file is empty.")));
        }
        if (raw.length > policy.maxBytes()) {
            throw fileTooLarge(policy.maxBytes());
        }
        if (isSvg(raw)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "svg_not_allowed",
                    "SVG not allowed",
                    "SVG images can carry scripts and are not accepted. Upload a PNG, JPEG or WebP image.");
        }
        boolean jpeg = Kind.startsWith(raw, 0xFF, 0xD8, 0xFF);
        boolean png = Kind.startsWith(raw, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        boolean webp = raw.length > 12
                && Kind.startsWith(raw, 0x52, 0x49, 0x46, 0x46)
                && raw[8] == 'W'
                && raw[9] == 'E'
                && raw[10] == 'B'
                && raw[11] == 'P';
        if (!jpeg && !png && !webp) {
            throw imageUnsupported();
        }
        Kind out = jpeg ? Kind.JPEG : Kind.PNG;
        byte[] stored = reencodeBounded(raw, out.imageFormat, policy);
        if (stored.length > (long) policy.maxBytes() * 2) {
            throw fileTooLarge(policy.maxBytes());
        }
        return new Prepared(stored, out.contentType, out.extension, sha256(stored));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssetMeta> publicAssetMeta(String subjectType, UUID documentId) {
        return jdbc.sql("""
                        SELECT content_type, sha256, size_bytes FROM documents
                         WHERE id = ? AND subject_type = ? AND subject_id = current_setting('app.tenant_id')::uuid
                        """)
                .params(documentId, subjectType)
                .query((rs, n) -> new AssetMeta(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> publicAssetBytes(String subjectType, UUID documentId) {
        return jdbc.sql("""
                        SELECT object_key FROM documents
                         WHERE id = ? AND subject_type = ? AND subject_id = current_setting('app.tenant_id')::uuid
                        """)
                .params(documentId, subjectType)
                .query(String.class)
                .optional()
                .flatMap(storage::get);
    }

    @Override
    @Transactional
    public StoredDocument store(Prepared prepared, String subjectType, UUID subjectId, UUID branchId) {
        UUID id = UUID.randomUUID();
        ZonedDateTime at = clock.now().atZone(ZoneOffset.UTC);
        String key = "tenants/%s/upload/%04d/%02d/%s.%s"
                .formatted(TenantContext.require(), at.getYear(), at.getMonthValue(), id, prepared.extension());
        storage.put(key, prepared.bytes(), prepared.contentType());
        deleteIfRolledBack(key);
        jdbc.sql("""
                        INSERT INTO documents (id, tenant_id, branch_id, doc_type, subject_type, subject_id, object_key,
                                               content_type, size_bytes, sha256, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 'upload', ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        branchId,
                        subjectType,
                        subjectId,
                        key,
                        prepared.contentType(),
                        prepared.bytes().length,
                        prepared.sha256(),
                        CurrentPrincipal.require().userId())
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("subject_type", subjectType);
        after.put("subject_id", subjectId);
        after.put("content_type", prepared.contentType());
        after.put("size_bytes", prepared.bytes().length);
        after.put("sha256", prepared.sha256());
        audit.record(AuditLog.Entry.created("core.document.uploaded", "core.document", id, branchId, after));
        return find(id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredDocument> find(UUID documentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents WHERE id = ?")
                .param(documentId)
                .query(DocumentService::map)
                .optional();
    }

    @Transactional(readOnly = true)
    StoredDocument metadata(UUID documentId) {
        return readable(documentId);
    }

    /** FR-DOC-03: 5 minutes, after the permission check; every issue is audited. */
    @Transactional
    DownloadUrl downloadUrl(UUID documentId) {
        StoredDocument d = readable(documentId);
        String key = jdbc.sql("SELECT object_key FROM documents WHERE id = ?")
                .param(documentId)
                .query(String.class)
                .single();
        String url = storage.signedGetUrl(key, URL_TTL, downloadName(d), d.contentType());
        audit.record(AuditLog.Entry.created(
                "core.document.download_url_issued",
                "core.document",
                documentId,
                d.branchId(),
                Map.of("subject_type", d.subjectType(), "subject_id", d.subjectId())));
        return new DownloadUrl(url, clock.now().plus(URL_TTL));
    }

    /** A name the server makes up (doc type + id + an extension for the sniffed type), never the uploader's. */
    static String downloadName(StoredDocument d) {
        String ext = switch (d.contentType()) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
        return d.docType().replaceAll("[^A-Za-z0-9_-]", "_") + "-" + d.id() + ext;
    }

    /** 404 when missing; 403 when the owning module says the caller may not read the subject (FR-DOC-03). */
    private StoredDocument readable(UUID documentId) {
        StoredDocument d = find(documentId).orElseThrow(ApiException::notFound);
        var principal = CurrentPrincipal.require();
        boolean allowed = access.stream()
                .filter(a -> a.subjectType().equals(d.subjectType()))
                .anyMatch(a -> a.canRead(principal, d.subjectId(), d.id()));
        if (!allowed) {
            // Denied attempts are audited too (who tried, which subject, which document).
            ownTransaction.executeWithoutResult(status -> audit.record(AuditLog.Entry.created(
                    "core.document.access_denied",
                    "core.document",
                    d.id(),
                    d.branchId(),
                    Map.of("subject_type", d.subjectType(), "subject_id", d.subjectId()))));
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You do not have permission to read this document.");
        }
        return d;
    }

    /**
     * Decodes and writes the image again: the pixels survive, the metadata (EXIF, location, camera)
     * does not (chapter 8 section 8.8). The canvas size is read before decoding.
     */
    static byte[] reencode(byte[] raw, String format) {
        return transcode(raw, format, null);
    }

    /**
     * {@link #reencode} under an image policy (null: none): a canvas over the policy's pixel cap or
     * a shorter side under its minimum is refused, a longer edge over its maximum is scaled down, and
     * a JPEG's EXIF orientation is applied first (the metadata itself is dropped, so a sideways
     * phone photo would otherwise stay sideways).
     */
    static byte[] transcode(byte[] raw, String format, ImagePolicy policy) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(in, true, true);
                long cap = policy != null && policy.maxPixels() > 0 ? policy.maxPixels() : MAX_PIXELS;
                if ((long) reader.getWidth(0) * reader.getHeight(0) > cap) {
                    throw ApiException.rule(
                            "image_too_large", "The image is larger than " + cap / 1_000_000 + " megapixels.");
                }
                int orientation = policy != null && format.equals("jpeg") ? exifOrientation(raw) : 1;
                int shortSide = Math.min(reader.getWidth(0), reader.getHeight(0));
                if (policy != null && shortSide < policy.minShortSide()) {
                    throw ApiException.rule(
                            "image_too_small",
                            "The shorter side of the image must be at least " + policy.minShortSide() + " px.");
                }
                image = reader.read(0);
                if (orientation > 1) {
                    image = orient(image, orientation);
                }
                if (policy != null) {
                    image = scaleDown(image, policy.maxLongEdge());
                }
            } finally {
                reader.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (format.equals("jpeg")) {
                    // ImageIO's default of 0.75 visibly softens small print on ID cards.
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.92f);
                }
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            // Truncated files, CMYK JPEGs and anything else the decoder refuses.
            throw unsupported();
        }
    }

    /** Halves while the image is more than twice the target, then one smooth last step; alpha is kept. */
    private static BufferedImage scaleDown(BufferedImage image, int maxLongEdge) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (maxLongEdge <= 0 || Math.max(w, h) <= maxLongEdge) {
            return image;
        }
        double factor = (double) maxLongEdge / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * factor));
        int nh = Math.max(1, (int) Math.round(h * factor));
        BufferedImage current = image;
        while (current.getWidth() > nw * 2 || current.getHeight() > nh * 2) {
            current = resize(current, Math.max(nw, current.getWidth() / 2), Math.max(nh, current.getHeight() / 2));
        }
        return resize(current, nw, nh);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(
                w, h, src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** The EXIF orientation (1 to 8) of a JPEG; 1 when there is none or it cannot be read. */
    static int exifOrientation(byte[] jpeg) {
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            int start = pos + 4;
            if (marker == 0xE1
                    && length >= 16
                    && start + 14 <= jpeg.length
                    && jpeg[start] == 'E'
                    && jpeg[start + 1] == 'x'
                    && jpeg[start + 2] == 'i'
                    && jpeg[start + 3] == 'f') {
                int tiff = start + 6;
                boolean little = jpeg[tiff] == 'I';
                int ifd = tiff + (int) readInt(jpeg, tiff + 4, 4, little);
                if (ifd < 0 || ifd + 2 > jpeg.length) {
                    return 1;
                }
                int entries = (int) readInt(jpeg, ifd, 2, little);
                for (int i = 0; i < entries && ifd + 2 + (i + 1) * 12 <= jpeg.length; i++) {
                    int entry = ifd + 2 + i * 12;
                    if (readInt(jpeg, entry, 2, little) == 0x0112) {
                        int value = (int) readInt(jpeg, entry + 8, 2, little);
                        return value >= 1 && value <= 8 ? value : 1;
                    }
                }
                return 1;
            }
            pos += 2 + length;
        }
        return 1;
    }

    private static long readInt(byte[] b, int at, int bytes, boolean little) {
        if (at < 0 || at + bytes > b.length) {
            return -1;
        }
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            int octet = b[at + (little ? bytes - 1 - i : i)] & 0xFF;
            v = (v << 8) | octet;
        }
        return v;
    }

    /** Applies an EXIF orientation (2 to 8) so the pixels are upright. */
    private static BufferedImage orient(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w);
        };
        BufferedImage dst = new BufferedImage(
                swap ? h : w,
                swap ? w : h,
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** SVG is XML text with an svg element; NULs are dropped so a UTF-16 file is seen too. */
    static boolean isSvg(byte[] raw) {
        int n = Math.min(raw.length, 4096);
        StringBuilder head = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            if (raw[i] != 0) {
                head.append((char) (raw[i] & 0xFF));
            }
        }
        return head.toString().toLowerCase(java.util.Locale.ROOT).contains("<svg");
    }

    private static String sizeText(int bytes) {
        int mb = 1024 * 1024;
        return bytes % mb == 0 ? bytes / mb + " MB" : bytes / 1024 + " KB";
    }

    /**
     * Bounds memory and CPU on a small host: at most {@link #MAX_CONCURRENT_REENCODES} images are
     * decoded at once; a caller that waits {@link #REENCODE_WAIT_SECONDS} seconds gets a 503.
     */
    private byte[] reencodeBounded(byte[] raw, String format) {
        return reencodeBounded(raw, format, null);
    }

    private byte[] reencodeBounded(byte[] raw, String format, ImagePolicy policy) {
        boolean acquired;
        try {
            acquired = reencodes.tryAcquire(REENCODE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "uploads_busy",
                    "Uploads busy",
                    "Too many images are being processed; try again shortly.");
        }
        try {
            return transcode(raw, format, policy);
        } finally {
            reencodes.release();
        }
    }

    /** The object is stored before the row commits; a rollback removes it again. */
    private void deleteIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // Only a known rollback: after an unknown outcome the row may have committed, and a
                // row without its object is worse than an orphan (the sweeper issue covers orphans).
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    storage.delete(key);
                } catch (RuntimeException e) {
                    log.warn("could not delete object {} after a rollback", key, e);
                }
            }
        });
    }

    static ApiException unsupported() {
        return new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_file_type",
                "Unsupported file type",
                "Upload a JPEG, PNG or PDF file.");
    }

    static ApiException imageUnsupported() {
        return new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_file_type",
                "Unsupported file type",
                "Upload a PNG, JPEG or WebP image.");
    }

    static ApiException fileTooLarge(int maxBytes) {
        return new ApiException(
                HttpStatus.CONTENT_TOO_LARGE,
                "file_too_large",
                "File too large",
                "The file is larger than " + sizeText(maxBytes) + ".");
    }

    static ApiException fileTooLarge() {
        return new ApiException(
                HttpStatus.CONTENT_TOO_LARGE, "file_too_large", "File too large", "The file is larger than 5 MB.");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StoredDocument map(ResultSet rs, int n) throws SQLException {
        return new StoredDocument(
                rs.getObject("id", UUID.class),
                rs.getString("doc_type"),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class));
    }
}
