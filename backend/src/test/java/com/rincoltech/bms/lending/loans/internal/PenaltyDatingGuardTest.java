package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A tripwire for ADR-034. The statement and the "loan after this payment" of a receipt place each
 * penalty by its dated charge row ({@code lending_loan_charges}, R-PEN), never by the item's running
 * {@code penalties_due_minor}, which has no date: read from there, a receipt first opened after a
 * penalty accrued would include it, and the statement would show it charged at disbursement.
 *
 * <p>This test fails when {@link LoanLedger} reads the item's penalties, or when anything other than
 * the nightly arrears run ({@link ArrearsRun}, which writes the dated charge next to it) writes the
 * column. Reads elsewhere (the schedule, the due list) are fine. Whoever adds a new writer records
 * the penalty with its date too and updates this guard.
 */
class PenaltyDatingGuardTest {

    @Test
    void penaltiesReachTheDocumentsOnlyThroughDatedCharges() throws IOException {
        List<String> writes = new ArrayList<>();
        Pattern write = Pattern.compile("penalties_due_minor\\s*=(?!=)");
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".sql"))
                    .toList()) {
                if (p.getFileName().toString().startsWith("V29__")) {
                    continue; // the column is created there
                }
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (write.matcher(lines.get(i)).find()) {
                        writes.add(p.getFileName() + ":" + (i + 1) + ": "
                                + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(writes)
                .as("""
                        penalties_due_minor is written somewhere new. A penalty must be recorded with the date it
                        was charged so LoanLedger (statement lines and the receipt's loan after this payment)
                        can place it in time; see ADR-034, then update this guard.""")
                .singleElement()
                .satisfies(l -> assertThat(l)
                        .startsWith("ArrearsRun.java:")
                        .contains("penalties_due_minor = penalties_due_minor + ?"));
        assertThat(Files.readString(Path.of("src/main/java/com/rincoltech/bms/lending/loans/internal/LoanLedger.java")))
                .as("LoanLedger takes penalties from the dated charges, not from the schedule items")
                .doesNotContain("penaltiesDueMinor");
    }
}
