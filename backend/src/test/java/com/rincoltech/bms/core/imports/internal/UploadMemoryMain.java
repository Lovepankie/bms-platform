package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/**
 * The child JVM of {@link UploadMemoryTest}: runs the upload's whole path over one file, as the API does, at the heap the
 * parent gives it. It streams the file into its encrypted copy (type check, size limit, SHA-256, UTF-8 check), then
 * decrypts and parses that copy with the reader of its type at the production limits, turning every row into its JSON in
 * chunks of 1,000 that are then dropped (the database's part is measured by {@code ImportUploadsIT}). Prints the rows read,
 * the largest chunk and the heap it ran on.
 */
public final class UploadMemoryMain {

    private UploadMemoryMain() {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        FileType type = FileType.fromName(source.getFileName().toString());
        byte[] dataKey = QuarantineCipher.newDataKey();
        UUID batch = UUID.randomUUID();
        Path sealed = Files.createTempFile(UploadService.TEMP_PREFIX, ".bin");
        try {
            QuarantineWriter.Written written;
            try (InputStream in = Files.newInputStream(source);
                    OutputStream out = QuarantineCipher.encrypt(Files.newOutputStream(sealed), dataKey, batch)) {
                written = QuarantineWriter.copy(in, type, 25L * 1024 * 1024, out);
            }
            AtomicLong chunks = new AtomicLong();
            AtomicLong largest = new AtomicLong();
            AtomicLong largestChars = new AtomicLong();
            SourceParser.Parsed parsed;
            try {
                parsed = SourceParser.parse(
                        type,
                        written.utf8(),
                        () -> QuarantineCipher.decrypt(
                                new BufferedInputStream(Files.newInputStream(sealed), 64 * 1024), dataKey, batch),
                        null,
                        new Parsing.Limits(100_000, 200, Set.of(), Set.of()),
                        JsonMapper.builder().build(),
                        chunk -> {
                            chunks.incrementAndGet();
                            largest.accumulateAndGet(chunk.size(), Math::max);
                            largestChars.accumulateAndGet(
                                    chunk.stream()
                                            .mapToLong(r -> r.rawJson().length())
                                            .sum(),
                                    Math::max);
                        });
            } catch (Parsing.Failure f) {
                System.out.println(
                        "refused=" + f.code + " chunks=" + chunks.get() + " largestChunkChars=" + largestChars.get()
                                + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
                return;
            }
            System.out.println("size=" + written.size() + " rows=" + parsed.rows() + " columns=" + parsed.columns()
                    + " chunks=" + chunks.get() + " largestChunk=" + largest.get() + " maxHeapMb="
                    + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        } finally {
            Files.deleteIfExists(sealed);
        }
    }
}
