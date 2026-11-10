package com.rincoltech.bms.kernel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Text keys, each with a {@code long}, held as the keys' UTF-8 bytes in one array and two tables of numbers: what a
 * {@code Map<String, Long>} answers for keys that are only added, in about a quarter of its heap (issue #880: the import of
 * a batch at the row limit keeps one entry per row, on the staging heap). Open addressing, linear probing, at most half full.
 * Each key's bytes follow their length in four bytes, so a key may be as long as a cell (review of 12e9d33 on #898). Not
 * thread safe.
 */
public final class CompactKeys {

    /** The bytes before each key's own: its length. */
    private static final int LENGTH = 4;

    private byte[] bytes = new byte[1 << 12];
    private int used;

    /** Per slot, one more than where its key starts in {@link #bytes}; 0 for a free slot. */
    private int[] slots = new int[1 << 8];

    private long[] values = new long[1 << 8];
    private int size;

    /** The number of keys. */
    public int size() {
        return size;
    }

    /** Whether the key was added. */
    public boolean contains(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        return slots[slot(slots, k, 0, k.length)] != 0;
    }

    /** The key's value, or {@code otherwise} when it was not added. */
    public long get(String key, long otherwise) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int i = slot(slots, k, 0, k.length);
        return slots[i] == 0 ? otherwise : values[i];
    }

    /** The key's value when it was added before (and it keeps it), or null when it is new and now has this value. */
    public Long putIfAbsent(String key, long value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int i = slot(slots, k, 0, k.length);
        if (slots[i] != 0) {
            return values[i];
        }
        int end = Math.addExact(used, LENGTH + k.length);
        if (end > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, end));
        }
        for (int b = 0; b < LENGTH; b++) {
            bytes[used + b] = (byte) (k.length >>> (8 * (LENGTH - 1 - b)));
        }
        System.arraycopy(k, 0, bytes, used + LENGTH, k.length);
        slots[i] = used + 1;
        values[i] = value;
        used = end;
        if (++size * 2 > slots.length) {
            grow();
        }
        return null;
    }

    private int length(int at) {
        int n = 0;
        for (int b = 0; b < LENGTH; b++) {
            n = (n << 8) | (bytes[at + b] & 0xff);
        }
        return n;
    }

    /** The slot of {@code table} that holds these bytes, or the free slot where they go. */
    private int slot(int[] table, byte[] key, int from, int to) {
        int h = 1;
        for (int k = from; k < to; k++) {
            h = 31 * h + key[k];
        }
        h ^= h >>> 16;
        int mask = table.length - 1;
        int i = h & mask;
        while (table[i] != 0) {
            int at = table[i] - 1;
            if (Arrays.equals(bytes, at + LENGTH, at + LENGTH + length(at), key, from, to)) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return i;
    }

    private void grow() {
        int[] table = new int[slots.length * 2];
        long[] moved = new long[slots.length * 2];
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != 0) {
                int at = slots[i] - 1;
                int j = slot(table, bytes, at + LENGTH, at + LENGTH + length(at));
                table[j] = slots[i];
                moved[j] = values[i];
            }
        }
        slots = table;
        values = moved;
    }
}
