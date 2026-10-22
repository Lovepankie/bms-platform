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
 * A tripwire for ADR-034. The statement and the "loan after this payment" of a receipt place each
 * penalty by its dated charge row ({@code lending_loan_charges}, R-PEN), never by the item's running
 * {@code penalties_due_minor}, which has no date: read from there, a receipt first opened after a
 * penalty accrued would include it, and the statement would show it charged at disbursement.
 *
 * <p>This test fails when {@link LoanLedger} reads the item's penalties, or when anything other than
 * the nightly arrears run ({@link ArrearsRun}, which writes the dated charge in the same statement
 * group), the cancel or charge again of a period by the reconcile ({@link ServicingRepository}, which
 * writes the dated cancel or charge row in the same transaction) and {@link ServicingRepository}'s two
 * reads touches the column. Whoever adds a new writer
 * (a waiver, say) records it with its date too and updates this guard.
 */
class PenaltyDatingGuardTest {

    @Test
    void penaltiesReachTheDocumentsOnlyThroughDatedCharges() throws IOException {
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
                        penalties_due_minor is used somewhere new. A penalty must be recorded with the date it
                        was charged so LoanLedger (statement lines and the receipt's loan after this payment)
                        can place it in time; see ADR-034, then update this guard.""")
                .hasSize(4)
                .allSatisfy(l -> assertThat(l)
                        .satisfiesAnyOf(
                                r -> assertThat(r)
                                        .startsWith("ServicingRepository.java:")
                                        .contains("rs.getLong("),
                                r -> assertThat(r)
                                        .startsWith("ServicingRepository.java:")
                                        .contains("penalties_due_minor = penalties_due_minor + ?"),
                                r -> assertThat(r)
                                        .startsWith("ArrearsRun.java:")
                                        .contains("penalties_due_minor = penalties_due_minor + ?")));
        assertThat(Files.readString(Path.of("src/main/java/com/rincoltech/bms/lending/loans/internal/LoanLedger.java")))
                .as("LoanLedger takes penalties from the dated charges, not from the schedule items")
                .doesNotContain("penaltiesDueMinor");
    }
}
