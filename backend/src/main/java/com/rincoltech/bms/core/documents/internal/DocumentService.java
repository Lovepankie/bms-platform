package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.TenantContext;
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
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-DOC-02, FR-DOC-03 and the upload rules of chapter 8 section 8.8. Uploads are checked by
 * content (never by name), images are decoded and written again so no metadata survives, and
 * every download URL is issued only after the owning module's {@link DocumentAccess} says yes.
 */
@Service
class DocumentService implements Documents {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** Bounds decoding: a small compressed image can declare a huge canvas. */
    static final long MAX_PIXELS = 40_000_000L;

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

    DocumentService(
            JdbcClient jdbc, ObjectStorage storage, List<DocumentAccess> access, AuditLog audit, BusinessClock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.access = List.copyOf(access);
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public StoredDocument upload(Upload upload) {
        byte[] raw = upload.bytes();
        if (raw == null || raw.length == 0) {
            throw ApiException.validation(List.of(new FieldProblem("file", "required", "The file is empty.")));
        }
        if (raw.length > MAX_BYTES) {
            throw fileTooLarge();
        }
        Kind kind = Kind.of(raw);
        byte[] stored = kind.imageFormat == null ? raw : reencode(raw, kind.imageFormat);
        UUID id = UUID.randomUUID();
        ZonedDateTime at = clock.now().atZone(ZoneOffset.UTC);
        String key = "tenants/%s/upload/%04d/%02d/%s.%s"
                .formatted(TenantContext.require(), at.getYear(), at.getMonthValue(), id, kind.extension);
        String sha256 = sha256(stored);
        storage.put(key, stored, kind.contentType);
        jdbc.sql("""
                        INSERT INTO documents (id, tenant_id, branch_id, doc_type, subject_type, subject_id, object_key,
                                               content_type, size_bytes, sha256, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, 'upload', ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        upload.branchId(),
                        upload.subjectType(),
                        upload.subjectId(),
                        key,
                        kind.contentType,
                        stored.length,
                        sha256,
                        CurrentPrincipal.require().userId())
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("subject_type", upload.subjectType());
        after.put("subject_id", upload.subjectId());
        after.put("content_type", kind.contentType);
        after.put("size_bytes", stored.length);
        after.put("sha256", sha256);
        audit.record(AuditLog.Entry.created("core.document.uploaded", "core.document", id, upload.branchId(), after));
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
        String url = storage.signedGetUrl(key, URL_TTL);
        audit.record(AuditLog.Entry.created(
                "core.document.download_url_issued",
                "core.document",
                documentId,
                d.branchId(),
                Map.of("subject_type", d.subjectType(), "subject_id", d.subjectId())));
        return new DownloadUrl(url, clock.now().plus(URL_TTL));
    }

    /** 404 when missing; 403 when the owning module says the caller may not read the subject (FR-DOC-03). */
    private StoredDocument readable(UUID documentId) {
        StoredDocument d = find(documentId).orElseThrow(ApiException::notFound);
        var principal = CurrentPrincipal.require();
        boolean allowed = access.stream()
                .filter(a -> a.subjectType().equals(d.subjectType()))
                .anyMatch(a -> a.canRead(principal, d.subjectId()));
        if (!allowed) {
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
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw ApiException.rule("image_too_large", "The image is larger than 40 megapixels.");
                }
                image = reader.read(0);
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

    static ApiException unsupported() {
        return new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_file_type",
                "Unsupported file type",
                "Upload a JPEG, PNG or PDF file.");
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
