package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ADR-034: a statement of five hundred lines renders on the smallest host's heap. The renderer runs in
 * a child JVM capped at 224 MB (the Pi's), so an out of memory error fails this test rather than a
 * production request. The statement is not paged: the lines of one loan are a few hundred rows of
 * short strings, and the cap on concurrent renders (two by default) bounds the rest.
 */
class StatementMemoryTest {

    @Test
    void aFiveHundredLineStatementRendersOnA224MegabyteHeap() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(List.of(
                        javaBin,
                        "-Xmx224m",
                        "-Djava.awt.headless=true",
                        "-cp",
                        System.getProperty("java.class.path"),
                        StatementMemoryMain.class.getName()))
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.waitFor(120, TimeUnit.SECONDS))
                .as("the child finished")
                .isTrue();
        assertThat(child.exitValue()).as(output).isZero();
        assertThat(output).contains("maxHeapMb=224");
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("pages=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        assertThat(Integer.parseInt(m.group(1)))
                .as("five hundred lines run to many pages")
                .isGreaterThanOrEqualTo(10);
    }
}
