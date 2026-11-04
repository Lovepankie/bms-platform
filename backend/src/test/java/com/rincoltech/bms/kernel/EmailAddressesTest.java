package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #823: a typed email loses every whitespace character, inside and at the ends, and is lower cased. */
class EmailAddressesTest {

    static final String NBSP = Character.toString(0x00A0);
    static final String FIGURE_SPACE = Character.toString(0x2007);
    static final String NARROW_NBSP = Character.toString(0x202F);
    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000);
    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);
    static final String BYTE_ORDER_MARK = Character.toString(0xFEFF);

    @Test
    void everyWhitespaceCharacterIsRemovedAndTheAddressIsLowerCased() {
        for (String typed : List.of(
                "test.owner@example.test",
                "  Test.Owner@Example.TEST  ",
                "test.owner @example.test",
                "test. owner@example.test",
                "test.owner@ example.test",
                "te st.ow ner@exa mple.test",
                "\ttest.owner@example.test\n",
                "test.owner" + NBSP + "@example.test",
                NBSP + "test.owner@example.test" + NBSP,
                "test.owner" + FIGURE_SPACE + "@" + NARROW_NBSP + "example" + IDEOGRAPHIC_SPACE + ".test",
                "test.owner" + ZERO_WIDTH_SPACE + "@example.test" + BYTE_ORDER_MARK)) {
            assertThat(EmailAddresses.normalise(typed)).as(typed).isEqualTo("test.owner@example.test");
        }
    }

    @Test
    void nothingStaysNothing() {
        assertThat(EmailAddresses.normalise(null)).isNull();
        assertThat(EmailAddresses.normalise(" " + NBSP + " ")).isEmpty();
    }
}
