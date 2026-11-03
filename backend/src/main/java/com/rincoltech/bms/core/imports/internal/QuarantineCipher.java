package com.rincoltech.bms.core.imports.internal;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encryption of the quarantine (issue #128, spec section 6.2). Plain Java, no Spring, so the memory test runs it in a
 * child JVM.
 *
 * <p><b>The run's data key</b> is 32 random bytes, wrapped with the platform import key in AES-256-GCM. The wrap is bound to
 * the business and the run (the associated data), so a wrapped key copied onto another run, or into another business, does
 * not open. Format: version byte {@code 1}, the 12 byte IV, the 48 bytes of ciphertext and tag.
 *
 * <p><b>A file</b> is encrypted in segments of {@link #SEGMENT} bytes, each sealed with AES-256-GCM on its own, so neither
 * side ever holds more than one segment: the JDK's GCM decryption through a {@code CipherInputStream} would buffer the whole
 * file to check one tag, which the memory rule forbids. The header is a magic {@code BQF1} and a 7 byte random prefix; a
 * segment's IV is the prefix, its 4 byte number and a byte that is 1 only on the last segment, and its associated data is the
 * header and the batch id. A segment moved, dropped, reordered, cut off at the end or taken from another batch fails its tag.
 */
final class QuarantineCipher {

    /** Plaintext bytes per segment. */
    static final int SEGMENT = 64 * 1024;

    static final int KEY_BYTES = 32;

    private static final int TAG_BYTES = 16;
    private static final int TAG_BITS = TAG_BYTES * 8;
    private static final byte[] MAGIC = "BQF1".getBytes(StandardCharsets.US_ASCII);
    private static final int PREFIX_BYTES = 7;
    private static final byte WRAP_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private QuarantineCipher() {}

    /** The file failed its authentication: the wrong key, another batch's file, or bytes changed or cut off. */
    static final class Unreadable extends IOException {

        Unreadable(String message) {
            super(message);
        }
    }

    static byte[] newDataKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    static byte[] wrap(byte[] importKey, byte[] dataKey, UUID tenantId, UUID runId) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(importKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(wrapContext(tenantId, runId));
            byte[] sealed = c.doFinal(dataKey);
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
                    .put(WRAP_VERSION)
                    .put(iv)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The run's data key could not be wrapped", e);
        }
    }

    /** The run's data key; {@link Unreadable} when the import key, the business or the run do not match. */
    static byte[] unwrap(byte[] importKey, byte[] wrapped, UUID tenantId, UUID runId) throws Unreadable {
        if (wrapped == null || wrapped.length != 1 + IV_BYTES + KEY_BYTES + TAG_BYTES || wrapped[0] != WRAP_VERSION) {
            throw new Unreadable("the run's data key is not in a known form");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(importKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, wrapped, 1, IV_BYTES));
            c.updateAAD(wrapContext(tenantId, runId));
            return c.doFinal(wrapped, 1 + IV_BYTES, wrapped.length - 1 - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new Unreadable("the run's data key does not open with this import key for this run");
        }
    }

    private static byte[] wrapContext(UUID tenantId, UUID runId) {
        return ("bms.imports.run-key.v1:" + tenantId + ":" + runId).getBytes(StandardCharsets.US_ASCII);
    }

    /** Wraps {@code out}: what is written is encrypted segment by segment; closing it seals the last segment. */
    static OutputStream encrypt(OutputStream out, byte[] dataKey, UUID batchId) throws IOException {
        byte[] prefix = new byte[PREFIX_BYTES];
        RANDOM.nextBytes(prefix);
        byte[] header = header(prefix);
        out.write(header);
        return new Sealing(out, new SecretKeySpec(dataKey, "AES"), prefix, aad(header, batchId));
    }

    /** Opens what {@link #encrypt} wrote, segment by segment; a failed segment throws {@link Unreadable}. */
    static InputStream decrypt(InputStream in, byte[] dataKey, UUID batchId) throws IOException {
        byte[] header = in.readNBytes(MAGIC.length + PREFIX_BYTES);
        if (header.length != MAGIC.length + PREFIX_BYTES
                || !Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)) {
            throw new Unreadable("not a quarantined file");
        }
        byte[] prefix = Arrays.copyOfRange(header, MAGIC.length, header.length);
        return new Opening(in, new SecretKeySpec(dataKey, "AES"), prefix, aad(header, batchId));
    }

    private static byte[] header(byte[] prefix) {
        return ByteBuffer.allocate(MAGIC.length + PREFIX_BYTES)
                .put(MAGIC)
                .put(prefix)
                .array();
    }

    private static byte[] aad(byte[] header, UUID batchId) {
        return ByteBuffer.allocate(header.length + 16)
                .put(header)
                .putLong(batchId.getMostSignificantBits())
                .putLong(batchId.getLeastSignificantBits())
                .array();
    }

    private static GCMParameterSpec iv(byte[] prefix, int segment, boolean last) {
        byte[] iv = ByteBuffer.allocate(IV_BYTES)
                .put(prefix)
                .putInt(segment)
                .put((byte) (last ? 1 : 0))
                .array();
        return new GCMParameterSpec(TAG_BITS, iv);
    }

    private static final class Sealing extends FilterOutputStream {

        private final SecretKeySpec key;
        private final byte[] prefix;
        private final byte[] aad;
        private final byte[] buffer = new byte[SEGMENT];
        private int filled;
        private int segment;
        private boolean closed;

        Sealing(OutputStream out, SecretKeySpec key, byte[] prefix, byte[] aad) {
            super(out);
            this.key = key;
            this.prefix = prefix;
            this.aad = aad;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (filled == SEGMENT) {
                    // More bytes follow, so the full buffer is not the last segment.
                    seal(false);
                }
                int n = Math.min(len, SEGMENT - filled);
                System.arraycopy(b, off, buffer, filled, n);
                filled += n;
                off += n;
                len -= n;
            }
        }

        private void seal(boolean last) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, iv(prefix, segment++, last));
                c.updateAAD(aad);
                out.write(c.doFinal(buffer, 0, filled));
                filled = 0;
            } catch (GeneralSecurityException e) {
                throw new IOException("a segment could not be sealed", e);
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                seal(true);
                Arrays.fill(buffer, (byte) 0);
            } finally {
                out.close();
            }
        }
    }

    private static final class Opening extends InputStream {

        private final PushbackInputStream in;
        private final SecretKeySpec key;
        private final byte[] prefix;
        private final byte[] aad;
        private byte[] plain = new byte[0];
        private int pos;
        private int segment;
        private boolean done;

        Opening(InputStream in, SecretKeySpec key, byte[] prefix, byte[] aad) {
            this.in = new PushbackInputStream(in, 1);
            this.key = key;
            this.prefix = prefix;
            this.aad = aad;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (pos == plain.length) {
                if (done) {
                    return -1;
                }
                next();
            }
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        private void next() throws IOException {
            byte[] sealed = in.readNBytes(SEGMENT + TAG_BYTES);
            boolean last;
            if (sealed.length < SEGMENT + TAG_BYTES) {
                last = true;
            } else {
                int peek = in.read();
                last = peek == -1;
                if (!last) {
                    in.unread(peek);
                }
            }
            if (sealed.length < TAG_BYTES) {
                throw new Unreadable("the quarantined file is cut off");
            }
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, iv(prefix, segment++, last));
                c.updateAAD(aad);
                plain = c.doFinal(sealed);
                pos = 0;
                done = last;
            } catch (GeneralSecurityException e) {
                throw new Unreadable("the quarantined file does not open with this key for this batch");
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
