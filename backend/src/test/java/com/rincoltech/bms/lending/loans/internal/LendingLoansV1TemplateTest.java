package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.SetRules;
import com.rincoltech.bms.core.imports.internal.EngineRun;
import com.rincoltech.bms.lending.members.MemberImports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code lending_loans_v1} without a database (issue #136, chapter 13 sections 13.5 to 13.12): the fabricated pilot register
 * {@code fixtures/pilot_loan_register_sample.csv} read with its fixture mapping gives exactly the issues and values of section
 * 13.12; a register of fabricated edge cases gives every other code of the template's catalogue; the codes are pinned; the
 * normalisers read the tables of 13.6 as written; and the forecast predicts the opening journal and the days past due of
 * 13.12. Fabricated values only.
 */
class LendingLoansV1TemplateTest {

    static final Path FIXTURES = Path.of("../fixtures");

    static final Path MAPPING = FIXTURES.resolve("imports/lending_loans_v1.pilot_register.mapping.json");

    static final Path PILOT = FIXTURES.resolve("pilot_loan_register_sample.csv");

    static final Path BRANCH_TWO = FIXTURES.resolve("imports/lending/fab-register-branch-two.csv");

    static final Path EDGE = FIXTURES.resolve("imports/lending/fab-register-edge-cases.csv");

    /** The batch business date of 13.12, 30 June 2026, as a cutover moment in Kampala (21:00). */
    static final Instant CUTOVER = Instant.parse("2026-06-30T18:00:00Z");

    static final LocalDate AS_AT = LocalDate.of(2026, 6, 30);

    /** The infos 13.12 leaves out of its table ("besides info PHONE_NORMALISED, COLLATERAL_PARSED"). */
    static final Set<String> BESIDES = Set.of("PHONE_NORMALISED", "COLLATERAL_PARSED");

    /** The accepted suggestions of 13.12 (rows 4, 6 and 11), as the review's edits. */
    static final Map<Integer, Map<String, String>> ACCEPTED = Map.of(
            4, Map.of("disbursed_on", "2026-03-05"),
            6, Map.of("rate", "0.1"),
            11, Map.of("disbursed_on", "2026-04-07", "due_on", "2026-05-07"));

    static Map<String, Object> mapping() throws IOException {
        return JsonMapper.builder().build().readValue(MAPPING.toFile(), new TypeReference<>() {});
    }

    /** The template with its rules reading {@code directory} in place of the business. */
    static LendingLoansV1Template template(LoanRegisterRules.Directory directory) {
        return new LendingLoansV1Template(null, null, null, null, null, null, null, null) {
            @Override
            public SetRules setRules(String entitySet) {
                return LOANS.equals(entitySet) ? new LoanRegisterRules(directory) : null;
            }
        };
    }

    static EngineRun.File file(Path csv, String name, String source, Map<Integer, Map<String, String>> edits)
            throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            rows.add(EngineRun.cells(l));
        }
        return new EngineRun.File(name, source, EngineRun.cells(lines.getFirst()), rows, edits);
    }

    static EngineRun.Result pilot(Map<Integer, Map<String, String>> edits) throws IOException {
        return EngineRun.run(
                template(LoanRegisterRules.Directory.NONE),
                mapping(),
                CUTOVER,
                List.of(file(PILOT, "pilot_loan_register_sample.csv", "register", edits)));
    }

    /** The codes of each line, sorted, without the infos 13.12 leaves out. */
    static Map<Integer, List<String>> codes(EngineRun.Result r) {
        Map<Integer, List<String>> out = new TreeMap<>();
        for (EngineRun.Row row : r.rows()) {
            out.put(row.line(), new ArrayList<>());
        }
        for (EngineRun.Issue i : r.issues()) {
            if (!BESIDES.contains(i.code())) {
                out.get(i.line()).add(i.code());
            }
        }
        out.values().forEach(l -> l.sort(String::compareTo));
        return out;
    }

    static Map<String, Object> row(EngineRun.Result r, int line) {
        return r.rows().stream()
                .filter(x -> x.line() == line)
                .findFirst()
                .orElseThrow()
                .normalised();
    }

    static String suggestion(EngineRun.Result r, int line, String code, String field) {
        return r.issues().stream()
                .filter(i -> i.line() == line && i.code().equals(code) && field.equals(i.field()))
                .findFirst()
                .orElseThrow()
                .suggestion();
    }

    // ---------------------------------------------------------------------------------------------
    // 13.12 through the mapping.
    // ---------------------------------------------------------------------------------------------

    @Test
    void thePilotRegisterGivesTheIssuesOfSection1312() throws IOException {
        EngineRun.Result r = pilot(Map.of());
        Map<Integer, List<String>> codes = codes(r);

        assertThat(codes.get(2)).containsExactly("SUBHEADER_ROW");
        assertThat(codes.get(3)).isEmpty();
        assertThat(codes.get(4)).containsExactly("DATE_SWAP_REPAIRED", "DURATION_NORMALISED", "KIN_IS_MEMBER");
        assertThat(codes.get(5)).containsExactly("DATE_TEXT_PARSED", "DATE_TEXT_PARSED");
        assertThat(codes.get(6)).containsExactly("RATE_OUT_OF_RANGE");
        assertThat(codes.get(7)).containsExactly("PLACEHOLDER_ROW");
        assertThat(codes.get(8)).containsExactly("SUBHEADER_ROW");
        assertThat(codes.get(9)).containsExactly("AMOUNTS_ONLY_ROW");
        assertThat(codes.get(10)).isEmpty();
        assertThat(codes.get(11)).containsExactly("DATE_SWAP_REPAIRED", "DATE_SWAP_REPAIRED", "KIN_IS_MEMBER");
        assertThat(codes.get(12)).containsExactly("DUPLICATE_BORROWER", "MULTIPLE_ACTIVE_LOANS");
        assertThat(codes.get(13)).containsExactly("COLLATERAL_UNRECOGNISED", "DATE_INCONSISTENT", "NIN_MISSING");
        assertThat(codes.keySet()).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);

        // The suggestions and the lines the issues name.
        assertThat(suggestion(r, 4, "DATE_SWAP_REPAIRED", "disbursed_on")).isEqualTo("2026-03-05");
        assertThat(suggestion(r, 6, "RATE_OUT_OF_RANGE", "rate")).isEqualTo("0.1");
        assertThat(suggestion(r, 11, "DATE_SWAP_REPAIRED", "disbursed_on")).isEqualTo("2026-04-07");
        assertThat(suggestion(r, 11, "DATE_SWAP_REPAIRED", "due_on")).isEqualTo("2026-05-07");
        assertThat(r.issues())
                .filteredOn(i -> i.line() == 12 && i.code().equals("DUPLICATE_BORROWER"))
                .singleElement()
                .satisfies(i -> assertThat(i.message()).contains("line 3"));
        assertThat(r.issues())
                .filteredOn(i -> i.code().equals("NIN_MISSING"))
                .extracting(EngineRun.Issue::field)
                .containsExactly("kin_nin");
        assertThat(r.issues())
                .filteredOn(
                        i -> i.code().equals("RATE_OUT_OF_RANGE") || i.code().equals("PLACEHOLDER_ROW"))
                .allMatch(i -> i.severity().equals("blocking"));

        // The key values of 13.12: the dates resolved and stored as ISO text.
        assertThat(row(r, 3)).containsEntry("disbursed_on", "2026-03-16").containsEntry("due_on", "2026-04-16");
        assertThat(row(r, 4)).containsEntry("disbursed_on", "2026-03-05").containsEntry("due_on", "2026-04-05");
        assertThat(row(r, 5)).containsEntry("disbursed_on", "2026-03-20").containsEntry("due_on", "2026-04-03");
        assertThat(row(r, 11)).containsEntry("disbursed_on", "2026-04-07").containsEntry("due_on", "2026-05-07");
        assertThat(row(r, 10)).containsEntry("paid_on", "2026-05-14");
        assertThat(row(r, 2)).isEmpty();
        assertThat(row(r, 7)).isEmpty();
        assertThat(row(r, 9)).isEmpty();
    }

    @Test
    void theStandardResolutionsLeaveOnlyTheRowsTheyExclude() throws IOException {
        EngineRun.Result r = pilot(ACCEPTED);
        Map<Integer, List<String>> codes = codes(r);

        assertThat(codes.get(4)).containsExactly("DURATION_NORMALISED", "KIN_IS_MEMBER");
        assertThat(codes.get(6)).isEmpty();
        assertThat(codes.get(11)).containsExactly("KIN_IS_MEMBER");
        // Left for exclusion (13.12): the placeholder, the row of amounts only and the inconsistent dates.
        Set<Integer> blocked = r.issues().stream()
                .filter(i -> i.severity().equals("blocking"))
                .map(EngineRun.Issue::line)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertThat(blocked).containsExactly(7, 9, 13);
        assertThat(r.issues()).noneMatch(i -> i.severity().equals("warning"));
    }

    @Test
    void theSameRowsGiveTheSameIssuesAndValues() throws IOException {
        EngineRun.Result first = pilot(Map.of());
        EngineRun.Result second = pilot(Map.of());

        assertThat(second.issues()).isEqualTo(first.issues());
        assertThat(second.rows()).isEqualTo(first.rows());
    }

    @Test
    void noMessageHoldsAValueOfTheRegister() throws IOException {
        List<EngineRun.Issue> all = new ArrayList<>(pilot(Map.of()).issues());
        all.addAll(edge().issues());
        for (EngineRun.Issue i : all) {
            assertThat(i.message()).doesNotContain("{").doesNotContain("(hidden)");
            for (String personal : List.of(
                    "Test Borrower", "Test Kin", "Test Edge", "Test Village", "700000", "CMTEST", "CFTEST", "UXX")) {
                assertThat(i.message())
                        .as("a value of the register in a message")
                        .doesNotContain(personal);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Every other code, from the edge cases.
    // ---------------------------------------------------------------------------------------------

    /** The edge register's mapping: the pilot register's, its branch and product read from columns. */
    static Map<String, Object> edgeMapping() throws IOException {
        Map<String, Object> m = mapping();
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) m.get("sources");
        @SuppressWarnings("unchecked")
        Map<String, Object> source = new LinkedHashMap<>((Map<String, Object>) sources.get("register"));
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = new LinkedHashMap<>((Map<String, Object>) source.get("columns"));
        columns.put("BRANCH", Map.of("field", "branch"));
        columns.put("PRODUCT", Map.of("field", "product"));
        source.put("columns", columns);
        source.remove("defaults");
        source.put("file", "fab-register-edge-cases.csv");
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("sources", Map.of("edge", source));
        return out;
    }

    /** A business holding one member (NIN CMTEST0000199A, phone +256700000112), branch ZZZ closed, product NOPE none. */
    static final LoanRegisterRules.Directory EDGE_BUSINESS = new LoanRegisterRules.Directory() {
        final MemberImports.Held held = new MemberImports.Held(
                UUID.nameUUIDFromBytes("held".getBytes(StandardCharsets.UTF_8)),
                null,
                "CMTEST0000199A",
                "+256700000112",
                "Test Held Member");

        @Override
        public Optional<MemberImports.Held> memberByNin(String nin) {
            return held.nationalId().equals(nin) ? Optional.of(held) : Optional.empty();
        }

        @Override
        public List<MemberImports.Held> membersByPhone(String phoneE164) {
            return held.phoneE164().equals(phoneE164) ? List.of(held) : List.of();
        }

        @Override
        public boolean branchOpen(String code) {
            return !"ZZZ".equals(code);
        }

        @Override
        public boolean productActive(String code) {
            return !"NOPE".equals(code);
        }

        @Override
        public boolean plateHeld(String plate) {
            return false;
        }
    };

    static EngineRun.Result edge() throws IOException {
        return EngineRun.run(
                template(EDGE_BUSINESS),
                edgeMapping(),
                CUTOVER,
                List.of(file(EDGE, "fab-register-edge-cases.csv", "edge", Map.of())));
    }

    @Test
    void theEdgeCasesGiveEveryOtherCodeOfTheCatalogue() throws IOException {
        EngineRun.Result r = edge();
        Map<Integer, List<String>> codes = codes(r);

        assertThat(codes.get(2))
                .contains(
                        "PHONE_MULTIPLE",
                        "NIN_INVALID",
                        "MARITAL_UNMAPPED",
                        "PHONE_NOT_MOBILE",
                        "NIN_MISSING",
                        "RELATIONSHIP_UNMAPPED",
                        "RATE_TEXT_PARSED",
                        "STATUS_UNMAPPED");
        assertThat(codes.get(3)).contains("PHONE_UNPARSEABLE", "KIN_PHONE_UNPARSEABLE", "DATE_UNVERIFIED", "RATE_ZERO");
        assertThat(codes.get(4)).contains("PHONE_MISSING", "NIN_MISSING", "DATE_MONTH_AS_30_DAYS", "RATE_PERCENT_FORM");
        assertThat(codes.get(5)).contains("DATE_AMBIGUOUS", "AMOUNT_NOT_INTEGER", "RETURN_AMOUNT_DERIVED");
        assertThat(codes.get(6))
                .contains("DATE_UNPARSEABLE", "DATE_OUT_OF_RANGE", "AMOUNT_UNPARSEABLE", "NEGATIVE_AMOUNT");
        assertThat(codes.get(7)).contains("PRINCIPAL_MISSING", "RATE_MISSING", "DURATION_UNPARSEABLE");
        assertThat(codes.get(8)).contains("AMOUNT_MISMATCH", "BALANCE_MISMATCH", "STATUS_BALANCE_CONFLICT");
        assertThat(codes.get(9)).contains("PAYMENT_EXCEEDS_RETURN");
        assertThat(codes.get(10)).contains("PAY_DATE_AMBIGUOUS");
        assertThat(codes.get(11)).contains("PAY_DATE_OUT_OF_RANGE");
        assertThat(codes.get(12)).contains("MEMBER_MATCHED_EXISTING");
        assertThat(codes.get(13)).contains("COLLATERAL_PLEDGED_TWICE", "MEMBER_PHONE_CONFLICT");
        assertThat(codes.get(14)).contains("KIN_PHONE_MATCH");
        assertThat(codes.get(15)).contains("BRANCH_UNKNOWN", "PRODUCT_UNKNOWN");
        assertThat(codes.get(16)).contains("DATE_AFTER_CUTOVER");
        assertThat(suggestion(r, 4, "RATE_PERCENT_FORM", "rate")).isEqualTo("0.05");
        assertThat(suggestion(r, 5, "AMOUNT_NOT_INTEGER", "principal")).isEqualTo("100001");
        assertThat(suggestion(r, 8, "AMOUNT_MISMATCH", "return_amount")).isEqualTo("120000");
        assertThat(suggestion(r, 8, "BALANCE_MISMATCH", "outstanding")).isEqualTo("120000");

        // JUSTIFICATION-A3: the new code BRANCH_IN_TWO_FILES (round 2 of the review), produced and pinned here.
        // A branch's register in two files of one run: every row of the later file, none of the first.
        EngineRun.Result twice = EngineRun.run(
                template(LoanRegisterRules.Directory.NONE),
                mapping(),
                CUTOVER,
                List.of(
                        file(PILOT, "pilot_loan_register_sample.csv", "register", Map.of()),
                        file(BRANCH_TWO, "fab-register-branch-two.csv", "register", Map.of())));
        assertThat(twice.issues().stream()
                        .filter(i -> "BRANCH_IN_TWO_FILES".equals(i.code()))
                        .map(i -> i.file() + ":" + i.line()))
                .containsExactly(
                        "fab-register-branch-two.csv:2",
                        "fab-register-branch-two.csv:3",
                        "fab-register-branch-two.csv:4");

        // Every code of the template's own catalogue is produced by one of these registers, but the run's refusal
        // DISBURSEMENT_DECISION_MISSING, which LendingLoansV1TemplateIT asserts.
        Set<String> produced = new LinkedHashSet<>();
        pilot(Map.of()).issues().forEach(i -> produced.add(i.code()));
        twice.issues().forEach(i -> produced.add(i.code()));
        r.issues().forEach(i -> produced.add(i.code()));
        Set<String> catalogue = LendingLoansV1Template.CODES.stream()
                .filter(c -> !c.retired())
                .map(IssueCode::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        catalogue.removeAll(produced);
        assertThat(catalogue).containsExactly("DISBURSEMENT_DECISION_MISSING");
    }

    @Test
    void theLendingCodesArePinned() throws IOException {
        Map<String, String> pinned = new TreeMap<>();
        for (String line : Files.readAllLines(FIXTURES.resolve("imports/issue-codes.txt"), StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && "lending_loans_v1".equals(parts[0])) {
                pinned.put(parts[1], parts[2] + (parts.length > 3 ? " " + parts[3] : ""));
            }
        }
        Map<String, String> current = new TreeMap<>();
        for (IssueCode c : LendingLoansV1Template.CODES) {
            current.put(c.code(), c.severity().key() + (c.retired() ? " retired" : ""));
        }
        assertThat(current).isEqualTo(pinned);
    }

    // ---------------------------------------------------------------------------------------------
    // The normalisers of 13.6, as its tables write them.
    // ---------------------------------------------------------------------------------------------

    @Test
    void thePhonesOfTable1361ReadAsWritten() {
        assertThat(LoanRegister.phone("700000001").e164()).isEqualTo("+256700000001");
        assertThat(LoanRegister.phone("700000001").changed()).isTrue();
        assertThat(LoanRegister.phone("0700000003").e164()).isEqualTo("+256700000003");
        assertThat(LoanRegister.phone("256700000002").e164()).isEqualTo("+256700000002");
        assertThat(LoanRegister.phone("+256 700 000 001").e164()).isEqualTo("+256700000001");
        assertThat(LoanRegister.phone("+256700000001").changed()).isFalse();
        assertThat(LoanRegister.phone("70000001").e164()).isNull();
        assertThat(LoanRegister.phone("700000001.0").e164()).isEqualTo("+256700000001");
        assertThat(LoanRegister.phone("0700000101 or 0700000102").alternate()).isEqualTo("+256700000102");
    }

    @Test
    void theDurationsRatesAndCollateralOf136ReadAsWritten() {
        assertThat(LoanRegister.duration("1Month")).isEqualTo(new LoanRegister.Duration(1, "month", true));
        assertThat(LoanRegister.duration("2 Week")).isEqualTo(new LoanRegister.Duration(2, "week", false));
        assertThat(LoanRegister.duration("one month")).isEqualTo(new LoanRegister.Duration(1, "month", true));
        assertThat(LoanRegister.duration("1.5 months")).isNull();
        assertThat(LoanRegister.rate("0.2", 500_000L, 600_000L).bp()).isEqualTo(2000);
        assertThat(LoanRegister.rate("205", 1_000_000L, 1_100_000L))
                .isEqualTo(new LoanRegister.Rate(null, "RATE_OUT_OF_RANGE", "0.1"));
        assertThat(LoanRegister.collateral("CAR UXX 001X", null))
                .isEqualTo(new LoanRegister.Collateral("vehicle", "CAR UXX 001X", "UXX001X", true));
        assertThat(LoanRegister.collateral("CAR LOG", null).type()).isEqualTo("vehicle_logbook");
        assertThat(LoanRegister.collateral("ID", "CFTEST0000003A").reference()).isEqualTo("CFTEST0000003A");
        assertThat(LoanRegister.collateral("TV SET", null).recognised()).isFalse();
        assertThat(LoanRegister.monthLabel("Mar 2026")).isEqualTo(new LoanRegister.MonthLabel(3, 2026));
        assertThat(LoanRegister.monthLabel("APRIL 26")).isEqualTo(new LoanRegister.MonthLabel(4, 2026));
        assertThat(LoanRegister.monthLabel("MARCH")).isEqualTo(new LoanRegister.MonthLabel(3, null));
    }

    // ---------------------------------------------------------------------------------------------
    // The forecast: the opening journal and the days past due of 13.12.
    // ---------------------------------------------------------------------------------------------

    /** The rows the commit writes after the standard resolutions: every row without a blocking issue that is a record. */
    static List<Map<String, Object>> written() throws IOException {
        EngineRun.Result r = pilot(ACCEPTED);
        Set<Integer> blocked = r.issues().stream()
                .filter(i -> i.severity().equals("blocking")
                        || Set.of("SUBHEADER_ROW").contains(i.code()))
                .map(EngineRun.Issue::line)
                .collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (EngineRun.Row row : r.rows()) {
            if (!blocked.contains(row.line())) {
                out.add(row.normalised());
            }
        }
        return out;
    }

    @Test
    void theForecastPredictsTheOpeningJournalAndTheDaysPastDueOf1312() throws IOException {
        List<Map<String, Object>> rows = written();
        assertThat(rows).hasSize(7);
        Map<String, Integer> dpd = new TreeMap<>();
        Map<String, Long> owed = new TreeMap<>();
        for (Map<String, Object> v : rows) {
            BringIn.Plan plan = LoanRegister.plan(LoanRegister.row(v), null, AS_AT);
            String loan = v.get("customer_name") + "|" + v.get("disbursed_on");
            dpd.put(loan, plan.position().daysPastDue());
            owed.put(loan, plan.position().principalOutstandingMinor());
        }
        assertThat(dpd)
                .containsEntry("Test Borrower 01|2026-03-16", 75)
                .containsEntry("Test Borrower 02|2026-03-05", 86)
                .containsEntry("Test Borrower 03|2026-03-20", 88)
                .containsEntry("Test Borrower 04|2026-03-24", 67)
                .containsEntry("Test Borrower 05|2026-04-14", 16)
                .containsEntry("Test Borrower 06|2026-04-07", 54);
        assertThat(owed)
                .containsEntry("Test Borrower 05|2026-04-14", 560_000L)
                .containsEntry("Test Borrower 01|2026-04-20", 0L);

        ImportForecast f = new LendingLoansV1Template.LoanForecast(
                Map.of(), Map.of("HQ", "HQ", "BR2", "BR2"), "HQ", AS_AT, Set.of(), Map.of());
        f.batch(1);
        rows.forEach(v -> f.row("loans", v));
        ImportForecast.Balances b = f.balances();

        assertThat(b.journals())
                .containsExactly(new ImportForecast.Journal("HQ", "loans_receivable", 2_810_000, 2_810_000));
        assertThat(b.loanPrincipal()).containsExactly(new ImportForecast.Amount("HQ", 2_810_000));
        assertThat(b.arrears())
                .containsExactlyInAnyOrder(
                        new ImportForecast.ArrearsBucket("HQ", "1-30", 1, 560_000),
                        new ImportForecast.ArrearsBucket("HQ", "31-60", 1, 250_000),
                        new ImportForecast.ArrearsBucket("HQ", "61-90", 4, 2_000_000));
    }

    /** One opening journal per branch, ever: a branch another run opened takes none, as the loader posts none there. */
    @Test
    void aBranchAnotherRunOpenedIsPredictedNoJournal() throws IOException {
        long[] earlier = {1_000, 0};
        Map<String, long[]> others = Map.of("HQ", earlier);
        ImportForecast f = new LendingLoansV1Template.LoanForecast(
                Map.of(), Map.of("HQ", "HQ"), "HQ", AS_AT, Set.of("loans_receivable|HQ"), others);
        f.batch(1);
        written().forEach(v -> f.row("loans", v));

        assertThat(f.balances().journals()).isEmpty();
        assertThat(f.balances().loanPrincipal()).containsExactly(new ImportForecast.Amount("HQ", 2_810_000));
    }
}
