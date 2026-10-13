package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A tripwire for ADR-034. The statement and the "loan after this payment" of a receipt place the
 * schedule's penalties at the start of the loan, which is right only while nothing writes
 * {@code penalties_due_minor}: it is zero on every item today. Penalties (increment 6) accrue later,
 * and then a receipt first opened after one accrued would include it, and the statement would show it
 * charged at disbursement.
 *
 * <p>This test fails the moment any code outside {@link ServicingRepository}'s two reads touches the
 * column, so increment 6 cannot ship without a decision: record each penalty charge with its date (a
 * transaction or ledger row), and have {@code LoanLedger} take penalties from those dated charges.
 * When that is done, delete this test and the ADR note.
 */
class PenaltyDatingGuardTest {

    @Test
    void nothingWritesPenaltiesDueUntilPenaltyChargesAreDated() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".sql"))
                    .toList()) {
                if (p.getFileName().toString().startsWith("V29__")) {
                    continue; // the column is created there
                }
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("penalties_due_minor")) {
                        found.add(p.getFileName() + ":" + (i + 1) + ": "
                                + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(found)
                .as("""
                        penalties_due_minor is used somewhere new. Penalties must be recorded with the date they
                        were charged before LoanLedger (statement lines and the receipt's loan after this payment)
                        can place them in time; see ADR-034, then update this guard.""")
                .hasSize(2)
                .allSatisfy(l ->
                        assertThat(l).startsWith("ServicingRepository.java:").contains("rs.getLong("));
    }
}
