package com.rincoltech.bms.core.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Streams an uploaded file once, in blocks of 64 KiB, into its encrypted quarantine copy (issue #128): it checks that the
 * first bytes agree with the type the name gives, counts the bytes against the size limit, takes the SHA-256 of the
 * plaintext and, for CSV and JSON Lines, checks whether the bytes are UTF-8 (else they are read as Windows-1252). The
 * plaintext is never written anywhere. Plain Java, no Spring, so the memory test runs it in a child JVM.
 */
final class QuarantineWriter {

    /** The three accepted types and the extensions that name them. */
    enum FileType {
        CSV("csv"),
        XLSX("xlsx"),
        JSONL("jsonl");

        final String key;

        FileType(String key) {
            this.key = key;
        }

        static FileType fromName(String fileName) {
            String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
            if (name.endsWith(".csv")) {
                return CSV;
            }
            if (name.endsWith(".xlsx")) {
                return XLSX;
            }
            if (name.endsWith(".jsonl") || name.endsWith(".ndjson")) {
                return JSONL;
            }
            return null;
        }

        static FileType fromKey(String key) {
            for (FileType t : values()) {
                if (t.key.equals(key)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown file type");
        }
    }

    /** What the copy found. {@code utf8} is meaningful for the text types only. */
    record Written(long size, String sha256, boolean utf8) {}

    /** The bytes do not agree with the type, or there are more of them than the limit. */
    static final class Refused extends RuntimeException {

        final String code;

        Refused(String code, String reason) {
            super(reason, null, false, false);
            this.code = code;
        }
    }

    private static final int BLOCK = 64 * 1024;

    private QuarantineWriter() {}

    static Written copy(InputStream in, FileType type, long maxBytes, OutputStream encrypted) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Utf8 utf8 = type == FileType.XLSX ? null : new Utf8();
        byte[] block = new byte[BLOCK];
        long size = 0;
        boolean first = true;
        int n;
        while ((n = in.readNBytes(block, 0, BLOCK)) > 0) {
            if (first) {
                sniff(block, n, type);
                first = false;
            }
            size += n;
            if (size > maxBytes) {
                throw tooLarge(maxBytes);
            }
            sha.update(block, 0, n);
            if (utf8 != null) {
                utf8.feed(block, n);
            }
            encrypted.write(block, 0, n);
        }
        if (first) {
            throw new Refused(Parsing.FILE_TYPE_UNSUPPORTED, "The file is empty.");
        }
        return new Written(size, HexFormat.of().formatHex(sha.digest()), utf8 == null || utf8.end());
    }

    static Refused tooLarge(long maxBytes) {
        return new Refused(
                Parsing.FILE_TOO_LARGE,
                "The file is over the limit of " + maxBytes / (1024 * 1024) + " MB; split it and upload the parts.");
    }

    /** The first bytes must be a zip for XLSX, and must not be a zip, an old Excel file, a PDF or UTF-16 for the text types. */
    private static void sniff(byte[] b, int n, FileType type) {
        boolean zip = n >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
        if (type == FileType.XLSX) {
            if (!zip) {
                throw unsupported();
            }
            return;
        }
        boolean ole = n >= 4 && (b[0] & 0xff) == 0xD0 && (b[1] & 0xff) == 0xCF && b[2] == 0x11 && (b[3] & 0xff) == 0xE0;
        boolean pdf = n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
        if (zip || ole || pdf) {
            throw unsupported();
        }
        for (int i = 0; i < Math.min(n, 4096); i++) {
            if (b[i] == 0) {
                throw unsupported();
            }
        }
        if (type == FileType.JSONL) {
            int i = n >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF ? 3 : 0;
            while (i < n && Character.isWhitespace(b[i])) {
                i++;
            }
            if (i < n && b[i] != '{') {
                throw unsupported();
            }
        }
    }

    private static Refused unsupported() {
        return new Refused(Parsing.FILE_TYPE_UNSUPPORTED, "The file is not CSV, XLSX or JSON Lines.");
    }

    /** A strict UTF-8 check fed block by block; a sequence split between two blocks is carried over. */
    private static final class Utf8 {

        private final CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        private final ByteBuffer in = ByteBuffer.allocate(BLOCK + 8);
        private final CharBuffer out = CharBuffer.allocate(BLOCK + 8);
        private boolean valid = true;

        void feed(byte[] b, int n) {
            if (!valid) {
                return;
            }
            in.put(b, 0, n).flip();
            out.clear();
            valid = !decoder.decode(in, out, false).isError();
            in.compact();
        }

        boolean end() {
            if (!valid) {
                return false;
            }
            in.flip();
            out.clear();
            return !decoder.decode(in, out, true).isError()
                    && !decoder.flush(out).isError();
        }
    }
}
