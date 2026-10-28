package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.loans.internal.LoanRegisterReader.Loan;
import com.rincoltech.bms.lending.loans.internal.LoanRegisterReader.Payment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The setup helper's reader of a model's answer into draft loans of the existing loans import (issue #626, ADR-102), on a
 * fixture register: year-month-day dates only, E.164 phones, a missing or repeated register number made by the app, the
 * flat total worked out from a monthly rate and a term and marked so, and a term or rate out of reason left for the owner.
 * Fabricated data only.
 */
class LoanRegisterReaderTest {

    static String fixture(String name) throws IOException {
        try (var in = LoanRegisterReaderTest.class.getResourceAsStream("/setup-drafts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static final LoanRegisterReader READER =
            new LoanRegisterReader(JsonMapper.builder().build(), 0, "R261027");

    @Test
    void aRegisterAnswerBecomesDraftLoans() throws IOException {
        List<Loan> loans = READER.read(fixture("loan-register.json")).orElseThrow();
        assertThat(loans)
                .containsExactly(
                        new Loan(
                                "Y-001",
                                "Test Borrower Alpha",
                                "+256700000101",
                                500_000L,
                                LocalDate.of(2026, 8, 2),
                                3,
                                650_000L,
                                "10",
                                true,
                                List.of(new Payment(LocalDate.of(2026, 9, 2), 200_000L), new Payment(null, 200_000L))),
                        new Loan(
                                "R261027-01",
                                "Test Borrower Beta",
                                "+256700000102",
                                300_000L,
                                null,
                                2,
                                360_000L,
                                null,
                                false,
                                List.of()),
                        new Loan(
                                "R261027-02",
                                "Test Borrower Gamma",
                                "not a phone",
                                100_000L,
                                LocalDate.of(2026, 9, 10),
                                null,
                                null,
                                null,
                                false,
                                List.of()));
    }

    @Test
    void anAnswerThatIsNotAListOfLoansIsNoAnswer() {
        assertThat(READER.read("The register has three loans.")).isEmpty();
        assertThat(READER.read("{\"items\": []}")).isEmpty();
        assertThat(READER.read("[]")).contains(List.of());
    }
}
