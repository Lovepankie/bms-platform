package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link CompactKeys} answers as a map of its keys would (issue #880). Fabricated keys. */
class CompactKeysTest {

    @Test
    void aKeyAddedBeforeKeepsItsValueAndANewOneTakesIt() {
        CompactKeys keys = new CompactKeys();
        assertThat(keys.putIfAbsent("fab-0001", 2)).isNull();
        assertThat(keys.putIfAbsent("fab-0002", 3)).isNull();
        assertThat(keys.putIfAbsent("fab-0001", 9)).isEqualTo(2L);
        assertThat(keys.putIfAbsent("fäb-0001", 10))
                .as("another key by one letter")
                .isNull();
        assertThat(keys.get("fäb-0001", -1)).isEqualTo(10);
        assertThat(keys.get("fab-0003", -1)).isEqualTo(-1);
        assertThat(keys.contains("fab-0002")).isTrue();
        assertThat(keys.contains("fab-0003")).isFalse();
        assertThat(keys.size()).isEqualTo(3);
        assertThat(keys.putIfAbsent("", 7)).as("the empty key").isNull();
        assertThat(keys.get("", -1)).isEqualTo(7);
    }

    // JUSTIFICATION-A3: the review of 12e9d33 on #898 asks a key over 64 KiB proven.
    /** A key as long as a cell may be (32,767 characters, three bytes each in UTF-8), beside short ones. */
    @Test
    void aKeyOverSixtyFourKibibytesIsHeld() {
        CompactKeys keys = new CompactKeys();
        String longKey = "碼".repeat(32_767);
        String other = "碼".repeat(32_766) + "x";
        assertThat(longKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(64 * 1024);
        assertThat(keys.putIfAbsent("fab-0001", 1)).isNull();
        assertThat(keys.putIfAbsent(longKey, 2)).isNull();
        assertThat(keys.putIfAbsent(other, 3)).isNull();
        assertThat(keys.putIfAbsent(longKey, 9)).isEqualTo(2L);
        for (int n = 0; n < 1_000; n++) {
            keys.putIfAbsent("fab-" + n + "-more", n);
        }
        assertThat(keys.get(longKey, -1)).isEqualTo(2);
        assertThat(keys.get(other, -1)).isEqualTo(3);
        assertThat(keys.get("fab-0001", -1)).isEqualTo(1);
    }

    @Test
    void manyKeysAnswerAsAMapWould() {
        CompactKeys keys = new CompactKeys();
        Map<String, Long> map = new HashMap<>();
        for (int line = 2; line < 60_000; line++) {
            // Every key twice, the second time 30,000 lines later, some of them 40 characters long.
            String key = "fab-" + (line % 30_000) + (line % 7 == 0 ? "-" + "x".repeat(30) : "");
            long value = line * 1_000_000_007L;
            assertThat(keys.putIfAbsent(key, value)).isEqualTo(map.putIfAbsent(key, value));
        }
        assertThat(keys.size()).isEqualTo(map.size());
        map.forEach((key, value) -> assertThat(keys.get(key, -1)).isEqualTo(value));
    }
}
