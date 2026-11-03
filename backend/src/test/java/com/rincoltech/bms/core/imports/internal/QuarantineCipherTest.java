package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The quarantine's encryption (issue #128, spec section 6.2): a file opens only with its run's data key and for its own
 * batch; the run's key opens only with the platform import key for its own business and run; and a cut, a changed byte or
 * reordered segments fail. Random bytes only.
 */
class QuarantineCipherTest {

    final byte[] importKey = QuarantineCipher.newDataKey();
    final UUID tenant = UUID.randomUUID();
    final UUID run = UUID.randomUUID();
    final UUID batch = UUID.randomUUID();

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    static byte[] seal(byte[] plain, byte[] key, UUID batch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream enc = QuarantineCipher.encrypt(out, key, batch)) {
            // In uneven writes, as a stream copy makes them.
            for (int i = 0; i < plain.length; i += 7_777) {
                enc.write(plain, i, Math.min(7_777, plain.length - i));
            }
        }
        return out.toByteArray();
    }

    static byte[] open(byte[] sealed, byte[] key, UUID batch) throws IOException {
        return QuarantineCipher.decrypt(new ByteArrayInputStream(sealed), key, batch)
                .readAllBytes();
    }

    @Test
    void aFileOfManySegmentsOpensWithItsKeyAndIsNotPlainOnDisk() throws IOException {
        byte[] key = QuarantineCipher.newDataKey();
        for (int size : new int[] {
            0, 1, QuarantineCipher.SEGMENT, QuarantineCipher.SEGMENT + 1, 3 * QuarantineCipher.SEGMENT, 500_000
        }) {
            byte[] plain = random(size);
            byte[] sealed = seal(plain, key, batch);
            assertThat(open(sealed, key, batch)).as("size %d", size).isEqualTo(plain);
        }
        byte[] text =
                "Code,Description\r\nFAB-1,Fabricated item\r\n".repeat(2_000).getBytes();
        byte[] sealed = seal(text, key, batch);
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("no plaintext in the stored bytes")
                .doesNotContain("Fabricated item")
                .doesNotContain("Description");
    }

    @Test
    void theFileDoesNotOpenWithoutTheRunKeyOrForAnotherBatch() throws IOException {
        byte[] key = QuarantineCipher.newDataKey();
        byte[] sealed = seal(random(200_000), key, batch);
        assertThatThrownBy(() -> open(sealed, QuarantineCipher.newDataKey(), batch))
                .isInstanceOf(QuarantineCipher.Unreadable.class);
        assertThatThrownBy(() -> open(sealed, key, UUID.randomUUID()))
                .as("a file moved onto another batch")
                .isInstanceOf(QuarantineCipher.Unreadable.class);
    }

    @Test
    void aCutChangedOrReorderedFileFails() throws IOException {
        byte[] key = QuarantineCipher.newDataKey();
        int seg = QuarantineCipher.SEGMENT + 16;
        byte[] sealed = seal(random(3 * QuarantineCipher.SEGMENT + 10), key, batch);
        int header = sealed.length - 3 * seg - (10 + 16);
        // Cut at a segment boundary: the last segment left was sealed as not last.
        byte[] cut = Arrays.copyOf(sealed, header + 2 * seg);
        assertThatThrownBy(() -> open(cut, key, batch)).isInstanceOf(QuarantineCipher.Unreadable.class);
        byte[] changed = sealed.clone();
        changed[header + 5] ^= 1;
        assertThatThrownBy(() -> open(changed, key, batch)).isInstanceOf(QuarantineCipher.Unreadable.class);
        byte[] swapped = sealed.clone();
        System.arraycopy(sealed, header, swapped, header + seg, seg);
        System.arraycopy(sealed, header + seg, swapped, header, seg);
        assertThatThrownBy(() -> open(swapped, key, batch)).isInstanceOf(QuarantineCipher.Unreadable.class);
        assertThatThrownBy(() -> open(new byte[] {1, 2, 3}, key, batch))
                .isInstanceOf(QuarantineCipher.Unreadable.class);
    }

    @Test
    void theRunKeyOpensOnlyWithTheImportKeyForItsOwnBusinessAndRun() throws IOException {
        byte[] dataKey = QuarantineCipher.newDataKey();
        byte[] wrapped = QuarantineCipher.wrap(importKey, dataKey, tenant, run);
        assertThat(QuarantineCipher.unwrap(importKey, wrapped, tenant, run)).isEqualTo(dataKey);
        assertThat(java.util.HexFormat.of().formatHex(wrapped))
                .as("the stored key is not the key")
                .doesNotContain(java.util.HexFormat.of().formatHex(dataKey));
        assertThatThrownBy(() -> QuarantineCipher.unwrap(QuarantineCipher.newDataKey(), wrapped, tenant, run))
                .isInstanceOf(QuarantineCipher.Unreadable.class);
        assertThatThrownBy(() -> QuarantineCipher.unwrap(importKey, wrapped, UUID.randomUUID(), run))
                .as("copied into another business")
                .isInstanceOf(QuarantineCipher.Unreadable.class);
        assertThatThrownBy(() -> QuarantineCipher.unwrap(importKey, wrapped, tenant, UUID.randomUUID()))
                .as("copied onto another run")
                .isInstanceOf(QuarantineCipher.Unreadable.class);
    }
}
