package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The parsing and cursor rules of the shared search helper (ADR-039); the SQL is proved in SearchLendingIT and SearchRetailIT. */
class SearchTest {

    static final Search.Fields NAME_AND_PHONE =
            Search.Fields.of().names("full_name").codes("member_no").phones("phone_e164");

    @Test
    void nothingToSearchIsNull() {
        assertThat(Search.parse(null)).isNull();
        assertThat(Search.parse("")).isNull();
        assertThat(Search.parse(" \t\n ")).isNull();
    }

    @Test
    void textIsSplitOnWhiteSpaceAndLimited() {
        assertThat(Search.parse("  john   doe ").terms()).containsExactly("john", "doe");
        assertThat(Search.parse("a b c d e f g h").terms()).hasSize(Search.MAX_TERMS);
        assertThat(Search.parse("x".repeat(500)).terms().getFirst()).hasSize(Search.MAX_LENGTH);
    }

    @Test
    void aPhoneKeyIsTheTypedDigitsAndWhatFollowsA256OrA0() {
        assertThat(Search.phoneKey("0700000111")).isEqualTo(new Search.PhoneKey("0700000111", "700000111"));
        assertThat(Search.phoneKey("+256 700 000 111")).isEqualTo(new Search.PhoneKey("256700000111", "700000111"));
        assertThat(Search.phoneKey("700000111")).isEqualTo(new Search.PhoneKey("700000111", ""));
        assertThat(Search.parse("+256 700 000 111").wholePhone().rest()).isEqualTo("700000111");
        assertThat(Search.parse("0700 000 111").wholePhone().rest()).isEqualTo("700000111");
    }

    /** The length is checked before the 256 or the 0 is taken off, so a typed prefix is already a search. */
    @Test
    void everyPrefixOfAPhoneNumberIsASearchFromFourTypedDigits() {
        assertThat(Search.phoneKey("0772")).isEqualTo(new Search.PhoneKey("0772", "772"));
        assertThat(Search.phoneKey("0700")).isEqualTo(new Search.PhoneKey("0700", "700"));
        assertThat(Search.phoneKey("+2567")).isEqualTo(new Search.PhoneKey("2567", "7"));
        assertThat(Search.phoneKey("2567")).isEqualTo(new Search.PhoneKey("2567", "7"));
        assertThat(Search.phoneKey("+25677")).isEqualTo(new Search.PhoneKey("25677", "77"));
        assertThat(Search.phoneKey("0772 1")).isEqualTo(new Search.PhoneKey("07721", "7721"));
        assertThat(Search.phoneKey("(0772)")).isEqualTo(new Search.PhoneKey("0772", "772"));
        // under four typed digits, or not phone-like at all, there is no phone search
        assertThat(Search.phoneKey("077")).isNull();
        assertThat(Search.phoneKey("+256")).isNull();
        assertThat(Search.phoneKey("M000123")).isNull();
        assertThat(Search.phoneKey("07a2")).isNull();
    }

    /** The rest is found at the start of a number, in each way a number is written, and nowhere else. */
    @Test
    void theRestOfATypedNumberIsMatchedAtTheStartOfTheNumberOnly() {
        assertThat(Search.phoneKey("+2567").starts()).containsExactly("7", "07", "2567");
        assertThat(Search.phoneKey("0772").starts()).containsExactly("772", "0772", "256772");
        assertThat(Search.phoneKey("700000111").starts()).isEmpty();
        Search.Match m = Search.parse("0772").match(NAME_AND_PHONE);
        assertThat(m.params())
                .containsEntry("d0t", "%0772%")
                .containsEntry("d0s0", "772%")
                .containsEntry("d0s1", "0772%")
                .containsEntry("d0s2", "256772%");
        // never "%772%": a four digit term such as 0123 must not match every number that has 123 inside it
        assertThat(m.params().values()).doesNotContain("%772%");
        assertThat(Search.parse("0123").match(NAME_AND_PHONE).params().values()).doesNotContain("%123%");
    }

    @Test
    void aShortOrMixedTextIsNotAWholePhone() {
        assertThat(Search.parse("07 0").wholePhone()).isNull();
        assertThat(Search.parse("john 0700000111").wholePhone()).isNull();
        assertThat(Search.parse("0700000111").wholePhone()).isNull();
    }

    /** Spaces in a phone-like text are separators: "07 00" is the number 0700, four typed digits. */
    @Test
    void spacesInAPhoneLikeTextAreSeparators() {
        assertThat(Search.parse("07 00").wholePhone()).isEqualTo(new Search.PhoneKey("0700", "700"));
        assertThat(Search.parse("0700 000 111").wholePhone().typed()).isEqualTo("0700000111");
    }

    @Test
    void aSearchedPageIsFencedSoTheRankIsWorkedOutOnce() {
        String sql = Search.page("SELECT 1 AS search_rank", "(search_rank, id) > (:r, :i)", "search_rank, id");
        assertThat(sql)
                .isEqualTo("SELECT * FROM (SELECT 1 AS search_rank OFFSET 0) ranked WHERE (search_rank, id) > (:r, :i)"
                        + " ORDER BY search_rank, id LIMIT :limit");
    }

    @Test
    void likeCharactersAreEscaped() {
        assertThat(Search.escape("50%")).isEqualTo("50\\%");
        assertThat(Search.escape("a_b")).isEqualTo("a\\_b");
        assertThat(Search.escape("a\\b")).isEqualTo("a\\\\b");
        Search.Match m = Search.parse("50% a_b").match(NAME_AND_PHONE);
        assertThat(m.params()).containsEntry("s0", "%50\\%%").containsEntry("s1", "%a\\_b%");
        assertThat(m.where()).doesNotContain("50%").doesNotContain("a_b");
    }

    @Test
    void everyTermMustMatchSomewhereAndTheRankHasFourTiers() {
        Search.Match m = Search.parse("john doe").match(NAME_AND_PHONE);
        assertThat(m.where()).contains(" AND ");
        assertThat(m.where()).contains("bms_fold(full_name) LIKE bms_fold(:s0)");
        assertThat(m.where()).contains("bms_fold(member_no) LIKE bms_fold(:s1)");
        assertThat(m.rank())
                .startsWith("CASE WHEN")
                .contains("THEN 0")
                .contains("THEN 1")
                .contains("THEN 2")
                .contains("ELSE 3");
        // the whole phrase starts the text, or starts a word in it, before each term does
        assertThat(m.params())
                .containsEntry("rp", "john doe%")
                .containsEntry("rw", "% john doe%")
                .containsEntry("tp0", "john%")
                .containsEntry("tw1", "% doe%");
    }

    @Test
    void aSingleTermHasNoAllWordsTier() {
        Search.Match m = Search.parse("john").match(NAME_AND_PHONE);
        assertThat(m.rank()).contains("THEN 2").contains("false");
        assertThat(m.params()).doesNotContainKey("tp0");
    }

    @Test
    void aPhoneColumnIsMatchedByDigitsOnlyForALongEnoughNumber() {
        Search.Match number = Search.parse("0700 000 111").match(NAME_AND_PHONE);
        assertThat(number.where()).contains("bms_digits(phone_e164) LIKE :dwt").contains(":dws0");
        assertThat(number.params()).containsEntry("dwt", "%0700000111%").containsEntry("dws0", "700000111%");

        Search.Match prefix = Search.parse("0772").match(NAME_AND_PHONE);
        assertThat(prefix.where()).contains("bms_digits(phone_e164) LIKE :d0t").contains(":d0s0");

        Search.Match shortText = Search.parse("700").match(NAME_AND_PHONE);
        assertThat(shortText.where()).doesNotContain("bms_digits");

        Search.Match noPhoneColumn =
                Search.parse("0700000111").match(Search.Fields.of().names("name"));
        assertThat(noPhoneColumn.where()).doesNotContain("bms_digits");
    }

    @Test
    void aRelatedColumnIsAnUncorrelatedSubqueryEvaluatedOncePerStatement() {
        Search.Fields fields = Search.Fields.of()
                .names("s.buyer_name")
                .related(
                        "s.customer_id",
                        "retail_customers c",
                        "c.id",
                        Search.Fields.of().names("c.name"));
        Search.Match m = Search.parse("kato").match(fields);
        assertThat(m.where())
                .contains(
                        "s.customer_id IN (SELECT c.id FROM retail_customers c WHERE bms_fold(c.name) LIKE bms_fold(:s0))");
        // the subquery never mentions the outer row's columns other than through the foreign key
        assertThat(m.where()).doesNotContain("WHERE c.id = s.");
    }

    @Test
    void aGuardedColumnMatchesOnlyWhereTheGuardHolds() {
        Search.Fields fields = Search.Fields.of()
                .names("name")
                .guarded("m.branch_id IN (:memberBranches)", Search.Fields.of().codes("national_id"));
        Search.Match m = Search.parse("abc").match(fields);
        assertThat(m.where())
                .contains("(m.branch_id IN (:memberBranches) AND bms_fold(national_id) LIKE bms_fold(:s0))");
        assertThat(m.where()).contains("bms_fold(name) LIKE bms_fold(:s0)");
    }

    @Test
    void theRankAndTheTextTravelInTheCursorOfASearchedListOnly() {
        Search.Query kato = Search.parse("kato");
        String inner = Cursor.encode("M000007");
        assertThat(Search.wrap(null, 1, inner)).isEqualTo(inner);
        String wrapped = Search.wrap(kato, 2, inner);
        Search.After after = Search.unwrap(kato, wrapped);
        assertThat(after.rank()).isEqualTo(2);
        assertThat(after.cursor()).isEqualTo(inner);
        assertThat(Search.unwrap(null, inner)).isEqualTo(new Search.After(0, inner));
        assertThat(Search.unwrap(kato, null)).isEqualTo(new Search.After(0, null));
        // the same words in another case or spacing are the same search
        assertThat(Search.unwrap(Search.parse("  KATO "), wrapped).rank()).isEqualTo(2);
    }

    @Test
    void aCursorOfAnotherSearchIsRefusedWithAClearMessage() {
        String wrapped = Search.wrap(Search.parse("kato"), 0, Cursor.encode("M000007"));
        assertThatThrownBy(() -> Search.unwrap(Search.parse("katoo"), wrapped))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different search text");
        assertThatThrownBy(() -> Search.unwrap(null, wrapped)).isInstanceOf(ApiException.class);
    }

    @Test
    void aCursorOfTheOtherKindIsRefused() {
        Search.Query kato = Search.parse("kato");
        String plain = Cursor.encode("M000007");
        assertThatThrownBy(() -> Search.unwrap(kato, plain)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Search.unwrap(kato, Cursor.encode("x~M000007")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Search.unwrap(kato, Cursor.encode("\u0001x~M000007")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Search.unwrap(kato, "!!not base64!!")).isInstanceOf(ApiException.class);
    }
}
