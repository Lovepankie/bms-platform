package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #39: the orphan sweep treats an upload object that no {@code documents} row names as an orphan. That is only
 * safe while {@code DocumentService.store} is the one writer of {@code upload/} keys, and it writes the row in the same
 * transaction. This test fails if any other main source file names the {@code upload/} key segment, and the generated
 * file path refuses the type {@code upload}.
 */
class UploadKeysTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void onlyTheUploadPathAndTheSweepNameUploadKeys() throws IOException {
        List<String> naming;
        try (Stream<Path> files = Files.walk(MAIN)) {
            naming = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(UploadKeysTest::namesUploadKeys)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertThat(naming).containsExactly("DocumentService.java", "OrphanSweep.java");
    }

    @Test
    void aGeneratedFileCannotBeStoredAsAnUpload() {
        assertThatThrownBy(() -> DocumentService.generatedType("upload")).isInstanceOf(IllegalArgumentException.class);
        assertThat(DocumentService.generatedType("receipt")).isEqualTo("receipt");
    }

    private static boolean namesUploadKeys(Path file) {
        try {
            return Files.readString(file).contains("/upload/");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
