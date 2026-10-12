package com.rincoltech.bms.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * One matching convention on every lending list (ADR-039, docs/specs/search.md): phone numbers in
 * any written form, several terms ANDed, accents folded, LIKE metacharacters literal, prefix matches
 * ranked first with paging that keeps the order, and a search that never reaches a row (or a column)
 * the caller may not list. Members, loans, savings accounts and investments. All data fabricated.
 */
class SearchLendingIT extends LoanFixtures {

    static final String MEMBERS = "/api/v1/lending/members";
    static final String SAVINGS = "/api/v1/lending/savings-accounts";
    static final String INVESTMENTS = "/api/v1/lending/investments";

    String amina;
    String jose;
    String amos;
    String brian;
    String club;
    String clubOther;
    String lotA;
    String lotB;
    String second;

    /** Fabricated members: a prefix, a word-start and a substring hit for "kato", an accent, two lookalikes. */
    void seedMembers() {
        amina = member("Amina Kato", "0700000111", t.headOffice(), true);
        jose = member("José Mukasa", "0700000222", t.headOffice(), true);
        amos = member("Kato Amos", "0700000333", t.headOffice(), true);
        brian = member("Mukato Brian", "+256700000444", t.headOffice(), true);
        club = member("Rate 50% Club", "0700000555", t.headOffice(), true);
        clubOther = member("Rate 50x Club", "0700000556", t.headOffice(), true);
        lotA = member("Lot_A Traders", "0700000557", t.headOffice(), true);
        lotB = member("LotXA Traders", "0700000558", t.headOffice(), true);
        second = member("Kato Secondbranch", "0700000666", t.secondBranch(), true);
        TestDatabase.owner()
                .sql("UPDATE lending_members SET id_type = 'nin', national_id = 'CMTEST0000001A' WHERE id = ?::uuid")
                .param(amina)
                .update();
    }

    URI uri(String path, String q, Integer limit, String cursor) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(path);
        Map<String, Object> vars = new LinkedHashMap<>();
        if (q != null) {
            b.queryParam("q", "{q}");
            vars.put("q", q);
        }
        if (limit != null) {
            b.queryParam("limit", "{limit}");
            vars.put("limit", limit);
        }
        if (cursor != null) {
            b.queryParam("cursor", "{cursor}");
            vars.put("cursor", cursor);
        }
        return b.encode().buildAndExpand(vars).toUri();
    }

    ResponseEntity<JsonNode> page(String path, String q, Integer limit, String cursor, HttpHeaders h) {
        return http.exchange(
                uri(path, q, limit, cursor),
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(h),
                JsonNode.class);
    }

    HttpHeaders officerHeaders() {
        return as(officer, officerPerms, "*", null);
    }

    List<String> names(String q) {
        return names(MEMBERS, q, "full_name", officerHeaders());
    }

    List<String> names(String path, String q, String field, HttpHeaders h) {
        ResponseEntity<JsonNode> r = page(path, q, 50, null, h);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        r.getBody().get("items").forEach(i -> out.add(i.get(field).asString()));
        return out;
    }

    // ---- members ---------------------------------------------------------------------------

    /** A phone number matches in every way it is written, spaces, +256, 256 and a leading 0 alike. */
    @Test
    void aPhoneMatchesInEveryWrittenForm() {
        seedMembers();
        for (String q : List.of(
                "0700000111",
                "+256 700 000 111",
                "700000111",
                "256700000111",
                "0700 000 111",
                "+256700000111",
                "0700-000-111",
                "(0700) 000 111")) {
            assertThat(names(q)).as("q=%s", q).containsExactly("Amina Kato");
        }
        // a partial number (four or more digits) finds it too; a digit run under four is no phone
        assertThat(names("000111")).containsExactly("Amina Kato");
        assertThat(names("0700000")).hasSizeGreaterThan(5);
        assertThat(names("700")).isEmpty();
    }

    /** Typing a number is a search from the fourth digit on: 0700, 0772, +2567 and every longer prefix. */
    @Test
    void everyPrefixOfAPhoneNumberFindsIt() {
        seedMembers();
        String number = "0700000111";
        for (int length = 4; length <= number.length(); length++) {
            String typed = number.substring(0, length);
            assertThat(names(typed)).as("typed %s", typed).contains("Amina Kato");
            assertThat(names("+256" + number.substring(1, length)))
                    .as("typed +256%s", number.substring(1, length))
                    .contains("Amina Kato");
        }
        assertThat(names("0700")).hasSize(9);
        assertThat(names("+2567")).hasSize(9);
        assertThat(names("2567")).hasSize(9);
        assertThat(names("0772")).isEmpty();
        assertThat(names("+256 77")).isEmpty();
        assertThat(names("077")).isEmpty();
        // spaces in a phone-like text are separators: "07 00" is the number 0700, so it finds all nine
        assertThat(names("07 00")).hasSize(9);
    }

    /** A four digit term that is not a phone number keeps its text match and rank; it is not read as 123 anywhere. */
    @Test
    void aFourDigitTermThatIsNotAPhoneKeepsItsTextMatch() {
        seedMembers();
        member("Batch 2023 Traders", "0700000901", t.headOffice(), true);
        member("Other Trader", "0702023111", t.headOffice(), true);
        member("Digits Test", "0771239999", t.headOffice(), true);
        // the name that says 2023 first, then the number that holds it
        assertThat(names("2023")).containsExactly("Batch 2023 Traders", "Other Trader");
        // 0123 is not 123 anywhere in a number: this one holds 123 and not 0123
        assertThat(names("0123")).doesNotContain("Digits Test");
        assertThat(names("1239")).containsExactly("Digits Test");
    }

    @Test
    void aCursorIsTiedToTheSearchTextItWasIssuedFor() {
        seedMembers();
        String cursor = page(MEMBERS, "kato", 1, null, officerHeaders())
                .getBody()
                .get("next_cursor")
                .asString();
        ResponseEntity<JsonNode> other = page(MEMBERS, "mukato", 1, cursor, officerHeaders());
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(other.getBody().get("detail").asString()).contains("different search text");
        // the same words in another case are the same search
        assertThat(page(MEMBERS, "  KATO ", 1, cursor, officerHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // and a searched cursor does not page a list without a search
        assertThat(page(MEMBERS, null, 1, cursor, officerHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** The same cases are folded in the browser (frontend fold.test.ts); both sides must give these answers. */
    @Test
    void theDatabaseFoldsAsTheBrowserDoes() {
        String[][] cases = {
            {"Jos\u00e9", "jose"},
            {"\u00c0\u00d8\u00d1", "aon"},
            {"Stra\u00dfe", "strasse"},
            {"\u00c6sop", "aesop"},
            {"C\u0153ur", "coeur"},
            {"\u0141\u00f3d\u017a", "lodz"},
            {"\u0110or\u0111e", "dorde"},
            {"Ni\u00f1o", "nino"},
            {"Plain Name 07", "plain name 07"},
        };
        for (String[] c : cases) {
            String folded = TestDatabase.owner()
                    .sql("SELECT bms_fold(?)")
                    .param(c[0])
                    .query(String.class)
                    .single();
            assertThat(folded).as(c[0]).isEqualTo(c[1]);
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT bms_digits(?)")
                        .param("+256 (700)-000 011 ext 5")
                        .query(String.class)
                        .single())
                .isEqualTo("2567000000115");
        assertThat(TestDatabase.owner()
                        .sql("SELECT bms_fold(NULL::text) IS NULL")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void severalTermsAreAnAndAcrossNameAndCode() {
        seedMembers();
        assertThat(names("amina kato")).containsExactly("Amina Kato");
        assertThat(names("kato amina")).containsExactly("Amina Kato");
        assertThat(names("kato brian")).containsExactly("Mukato Brian");
        assertThat(names("kato nobody")).isEmpty();
        // a name term and a member number term together
        String no = officerGet(MEMBERS + "/" + amos).get("member_no").asString();
        assertThat(names("kato " + no.toLowerCase())).containsExactly("Kato Amos");
        // surrounding and repeated white space does not matter
        assertThat(names("   kato    amina  ")).containsExactly("Amina Kato");
    }

    @Test
    void accentsAndCaseAreFolded() {
        seedMembers();
        assertThat(names("jose")).containsExactly("José Mukasa");
        assertThat(names("JOSÉ")).containsExactly("José Mukasa");
        assertThat(names("mukasa jose")).containsExactly("José Mukasa");
        assertThat(names("josé")).containsExactly("José Mukasa");
    }

    @Test
    void likeCharactersAreLiteral() {
        seedMembers();
        assertThat(names("50%")).containsExactly("Rate 50% Club");
        assertThat(names("%")).containsExactly("Rate 50% Club");
        assertThat(names("lot_a")).containsExactly("Lot_A Traders");
        assertThat(names("_")).containsExactly("Lot_A Traders");
        assertThat(names("\\")).isEmpty();
        assertThat(names("a\\b")).isEmpty();
        // a lone wildcard must not be a wildcard: the whole list does not come back
        assertThat(names("%%")).isEmpty();
    }

    @Test
    void nationalIdMatchesWholeOrInPart() {
        seedMembers();
        assertThat(names("cmtest0000001a")).containsExactly("Amina Kato");
        assertThat(names("CMTEST0000001A")).containsExactly("Amina Kato");
        assertThat(names("test0000001")).containsExactly("Amina Kato");
    }

    /** A name that starts with the term ranks before one with a word that starts with it, then a substring. */
    @Test
    void prefixMatchesRankBeforeSubstringMatches() {
        seedMembers();
        List<String> order = names("kato");
        assertThat(order).containsExactlyInAnyOrder("Kato Amos", "Kato Secondbranch", "Amina Kato", "Mukato Brian");
        assertThat(order.subList(0, 2)).containsExactlyInAnyOrder("Kato Amos", "Kato Secondbranch");
        assertThat(order.get(2)).isEqualTo("Amina Kato");
        assertThat(order.get(3)).isEqualTo("Mukato Brian");
        // within one rank the order is the member number, so it is the same every time
        assertThat(order.subList(0, 2)).containsExactly("Kato Amos", "Kato Secondbranch");
        assertThat(names("kato")).isEqualTo(order);
    }

    @Test
    void pagingKeepsTheRankedOrderWithoutGapsOrRepeats() {
        seedMembers();
        List<String> all = names("kato");
        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<JsonNode> r = page(MEMBERS, "kato", 1, cursor, officerHeaders());
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            r.getBody().get("items").forEach(i -> paged.add(i.get("full_name").asString()));
            JsonNode next = r.getBody().get("next_cursor");
            cursor = next == null || next.isNull() ? null : next.asString();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(paged).isEqualTo(all);
        assertThat(pages).isEqualTo(all.size());
    }

    @Test
    void aCursorFromAnotherListIsRefused() {
        seedMembers();
        String plain = page(MEMBERS, null, 1, null, officerHeaders())
                .getBody()
                .get("next_cursor")
                .asString();
        ResponseEntity<JsonNode> r = page(MEMBERS, "kato", 1, plain, officerHeaders());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code").asString()).isEqualTo("malformed_request");
    }

    /** A narrower branch scope hides the row, whatever the text matches: name, phone or national id. */
    @Test
    void aSearchNeverReachesARowOutsideTheCallersBranches() {
        seedMembers();
        HttpHeaders headOfficeOnly = as(officer, officerPerms, t.headOffice().toString(), null);
        assertThat(names(MEMBERS, "secondbranch", "full_name", headOfficeOnly)).isEmpty();
        assertThat(names(MEMBERS, "0700000666", "full_name", headOfficeOnly)).isEmpty();
        assertThat(names(MEMBERS, "kato", "full_name", headOfficeOnly))
                .doesNotContain("Kato Secondbranch")
                .contains("Kato Amos");
        assertThat(names(MEMBERS, "0700000666", "full_name", officerHeaders())).containsExactly("Kato Secondbranch");
    }

    @Test
    void aBlankOrOverlongTextIsHandled() {
        seedMembers();
        assertThat(names(MEMBERS, "   ", "full_name", officerHeaders())).hasSize(9);
        assertThat(page(MEMBERS, "x".repeat(500), 5, null, officerHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(page(MEMBERS, "a b c d e f g h i j k l", 5, null, officerHeaders())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- loans -----------------------------------------------------------------------------

    List<String> loanMembers(String q, HttpHeaders h) {
        return names(LOANS, q, "member_name", h);
    }

    ResponseEntity<JsonNode> openLoan(String member, String product) {
        ResponseEntity<JsonNode> r = apply(member, product, 500_000, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r;
    }

    @Test
    void loansMatchLoanNumberMemberNameAndPhoneWithRanking() {
        seedMembers();
        String product = product("SRCH", false, false);
        String first = openLoan(amina, product).getBody().get("loan_no").asString();
        openLoan(amos, product);
        openLoan(brian, product);
        openLoan(second, product);

        assertThat(loanMembers(first.toLowerCase(), officerHeaders())).containsExactly("Amina Kato");
        assertThat(loanMembers("ln00000", officerHeaders())).hasSize(4);
        assertThat(loanMembers("0700000111", officerHeaders())).containsExactly("Amina Kato");
        assertThat(loanMembers("+256 700 000 333", officerHeaders())).containsExactly("Kato Amos");
        assertThat(loanMembers("cmtest0000001a", officerHeaders())).containsExactly("Amina Kato");
        assertThat(loanMembers("kato", officerHeaders()))
                .containsExactly("Kato Amos", "Kato Secondbranch", "Amina Kato", "Mukato Brian");
        // paging keeps the order
        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<JsonNode> r = page(LOANS, "kato", 3, cursor, officerHeaders());
            r.getBody()
                    .get("items")
                    .forEach(item -> paged.add(item.get("member_name").asString()));
            JsonNode next = r.getBody().get("next_cursor");
            cursor = next == null || next.isNull() ? null : next.asString();
            if (cursor == null) {
                break;
            }
        }
        assertThat(paged).containsExactly("Kato Amos", "Kato Secondbranch", "Amina Kato", "Mukato Brian");
    }

    /** A loan list reader without lending.members.read cannot use a loan search to find a member's phone or id. */
    @Test
    void loansDoNotConfirmAPhoneOrIdToACallerWhoCannotReadMembers() {
        seedMembers();
        String product = product("SRCH2", false, false);
        openLoan(amina, product);
        openLoan(second, product);

        HttpHeaders loansOnly = as(UUID.randomUUID(), "lending.loans.read", "*", null);
        assertThat(loanMembers("amina", loansOnly)).containsExactly("Amina Kato");
        assertThat(loanMembers("0700000111", loansOnly)).isEmpty();
        assertThat(loanMembers("cmtest0000001a", loansOnly)).isEmpty();

        // members readable at the second branch only: the head office member's phone stays hidden
        HttpHeaders scoped = as(UUID.randomUUID(), "lending.loans.read,lending.members.read", "*", null);
        scoped.add("X-Dev-Scopes", "lending.members.read=" + t.secondBranch());
        assertThat(loanMembers("0700000111", scoped)).isEmpty();
        assertThat(loanMembers("0700000666", scoped)).containsExactly("Kato Secondbranch");

        // and the loan scope itself still applies to what a search returns
        HttpHeaders headOfficeLoans = as(
                UUID.randomUUID(),
                "lending.loans.read,lending.members.read",
                t.headOffice().toString(),
                null);
        assertThat(loanMembers("kato", headOfficeLoans)).doesNotContain("Kato Secondbranch");
        assertThat(loanMembers("0700000666", headOfficeLoans)).isEmpty();
    }

    // ---- savings and investments -----------------------------------------------------------

    @Test
    void savingsAccountsMatchNumberNamePhoneWithRanking() {
        seedMembers();
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test savings");
        terms.put("interest_rate_bp", 0);
        terms.put("interest_calc", "none");
        terms.put("interest_posting", "monthly");
        String product = send(
                        HttpMethod.POST,
                        "/api/v1/lending/savings-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", "SRCHSAV", "terms", terms))
                .getBody()
                .get("id")
                .asString();
        List<String> numbers = new ArrayList<>();
        for (String m : List.of(amina, amos, brian, second)) {
            ResponseEntity<JsonNode> r =
                    asOfficer(HttpMethod.POST, SAVINGS, null, Map.of("member_id", m, "product_id", product));
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
            numbers.add(r.getBody().get("account_no").asString());
        }
        assertThat(names(SAVINGS, numbers.get(0).toLowerCase(), "member_name", officerHeaders()))
                .containsExactly("Amina Kato");
        assertThat(names(SAVINGS, "kato", "member_name", officerHeaders()))
                .containsExactly("Kato Amos", "Kato Secondbranch", "Amina Kato", "Mukato Brian");
        assertThat(names(SAVINGS, "+256 700 000 444", "member_name", officerHeaders()))
                .containsExactly("Mukato Brian");
        HttpHeaders accountsOnly = as(UUID.randomUUID(), "lending.savings.read", "*", null);
        assertThat(names(SAVINGS, "0700000444", "member_name", accountsOnly)).isEmpty();
        assertThat(names(SAVINGS, "brian", "member_name", accountsOnly)).containsExactly("Mukato Brian");
    }

    @Test
    void investmentsMatchNumberNamePhoneWithRanking() {
        seedMembers();
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("product_type", "fixed_term");
        terms.put("allowed_terms_months", List.of(3, 6, 12));
        terms.put("return_rate_bp", 1200);
        terms.put("return_method", "flat");
        terms.put("payout_frequency", "monthly");
        terms.put("min_amount_minor", 100_000);
        terms.put("max_amount_minor", 50_000_000);
        terms.put("early_withdrawal_allowed", false);
        String product = send(
                        HttpMethod.POST,
                        "/api/v1/lending/investment-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", "SRCHINV", "name", "Test search", "terms", terms))
                .getBody()
                .get("id")
                .asString();
        List<String> numbers = new ArrayList<>();
        for (String m : List.of(amina, amos, brian, second)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("member_id", m);
            body.put("product_id", product);
            body.put("amount_minor", 1_000_000);
            body.put("term_months", 6);
            ResponseEntity<JsonNode> r = asOfficer(HttpMethod.POST, INVESTMENTS, null, body);
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
            numbers.add(r.getBody().get("account_no").asString());
        }
        assertThat(names(INVESTMENTS, numbers.get(1).toLowerCase(), "member_name", officerHeaders()))
                .containsExactly("Kato Amos");
        // the investment list is newest first, so inside one rank the later account number comes first
        assertThat(names(INVESTMENTS, "kato", "member_name", officerHeaders()))
                .containsExactly("Kato Secondbranch", "Kato Amos", "Amina Kato", "Mukato Brian");
        assertThat(names(INVESTMENTS, "0700000111", "member_name", officerHeaders()))
                .containsExactly("Amina Kato");
        HttpHeaders investmentsOnly = as(UUID.randomUUID(), "lending.investments.read", "*", null);
        assertThat(names(INVESTMENTS, "0700000111", "member_name", investmentsOnly))
                .isEmpty();
    }

    JsonNode officerGet(String path) {
        return http.exchange(
                        path,
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(officerHeaders()),
                        JsonNode.class)
                .getBody();
    }
}
