package com.rincoltech.bms.core.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The report of a run's commit, the same for a dry run and a real run (issue #132, spec section 3.6, ADR-027 decision 5):
 * per source (a batch, or a file a batch holds) the rows read, written, already present and skipped; every anomaly with its
 * file and line; the opening journals with the debit and credit totals the ledger holds for them; the balances the commit
 * wrote (stock quantity and value, receivables, loan principal outstanding and loans by arrears bucket, per branch); the
 * template's notes; the balances checksum; and why the run stopped, if it did. {@link #render} prints it as plain text in
 * the form {@code import-retail} prints today. The dry run's balances and journals are compared with the preview's
 * ({@link ImportForecast.Balances}); a run is ready only when they are the same.
 *
 * <p>It holds codes, counts and amounts only, never a value of a personal field: the commit checks every text a writer gives
 * it against the personal values of the run's staged rows before it keeps it, and keeps one that holds such a value as
 * {@link #HIDDEN}. A failure names the batch and the kind of failure, never the message of an unexpected error, which
 * may quote a row; a coded refusal (an {@code ApiException}) names its code and a fixed sentence for it, never its
 * message (issue #885). Two reports
 * of the same input are equal apart from their {@link #mode} ({@link #sameAs}).
 */
public record CommitReport(
        String mode,
        List<Source> sources,
        List<Anomaly> anomalies,
        List<Journal> journals,
        List<ImportForecast.Stock> stock,
        List<ImportForecast.Amount> receivables,
        List<ImportForecast.Amount> loanPrincipal,
        List<ImportForecast.ArrearsBucket> arrears,
        List<String> notes,
        String checksum,
        String failure) {

    /** The mode of a dry run: every write rolled back. */
    public static final String DRY_RUN = "dry_run";

    /** The mode of a real run: one transaction per batch. */
    public static final String COMMIT = "commit";

    /** What a report says in place of a text that held a personal value of a staged row. */
    public static final String HIDDEN = "(hidden: the text named a personal value of the import)";

    public CommitReport {
        Objects.requireNonNull(mode, "mode");
        sources = List.copyOf(sources);
        anomalies = List.copyOf(anomalies);
        journals = List.copyOf(journals);
        stock = List.copyOf(stock);
        receivables = List.copyOf(receivables);
        loanPrincipal = List.copyOf(loanPrincipal);
        arrears = List.copyOf(arrears);
        notes = List.copyOf(notes);
    }

    /** One line of the table: a batch, or one file of a batch that holds several (the retail export). */
    public record Source(String name, int read, int written, int existing, int skipped) {}

    /** One anomaly: the file, the line (0 for the file as a whole) and what the writer says, never a personal value. */
    public record Anomaly(String file, int line, String message) {}

    /**
     * One opening journal (spec section 3.7: one per branch per kind): the branch's code, the kind (for example
     * {@code inventory}), its debit and credit totals in minor units as the ledger holds them for the entry, what happened
     * to it ({@code posted}, {@code nothing to post}, {@code already posted}) and its entry number, or null.
     */
    public record Journal(
            String branch, String kind, long debitMinor, long creditMinor, String status, String entryNo) {}

    /** The predicted balances this report answers for: its stock, receivables, loans, arrears and journals. */
    public ImportForecast.Balances balances() {
        return new ImportForecast.Balances(
                stock,
                receivables,
                loanPrincipal,
                arrears,
                journals.stream()
                        .map(j -> new ImportForecast.Journal(j.branch(), j.kind(), j.debitMinor(), j.creditMinor()))
                        .toList());
    }

    /** Whether the other report says the same apart from its mode: the dry run predicts the real run. */
    public boolean sameAs(CommitReport other) {
        return other != null
                && sources.equals(other.sources)
                && anomalies.equals(other.anomalies)
                && journals.equals(other.journals)
                && stock.equals(other.stock)
                && receivables.equals(other.receivables)
                && loanPrincipal.equals(other.loanPrincipal)
                && arrears.equals(other.arrears)
                && notes.equals(other.notes)
                && Objects.equals(checksum, other.checksum)
                && Objects.equals(failure, other.failure);
    }

    /** The report as plain text, in the form {@code import-retail} prints. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("import report\n");
        out.append("mode: ")
                .append(
                        DRY_RUN.equals(mode)
                                ? "dry run, every write rolled back"
                                : "committed, one transaction per batch")
                .append('\n');
        out.append("result: ")
                .append(failure == null ? "ok" : "FAILED: " + failure)
                .append("\n\n");
        out.append(String.format("%-32s %8s %8s %8s %8s%n", "source", "read", "written", "existing", "skipped"));
        sources.forEach(s -> out.append(
                String.format("%-32s %8d %8d %8d %8d%n", s.name(), s.read(), s.written(), s.existing(), s.skipped())));
        out.append('\n');
        out.append("opening journals (debit and credit totals):\n");
        if (journals.isEmpty()) {
            out.append("  none\n");
        }
        long debit = 0;
        long credit = 0;
        for (Journal j : journals) {
            debit = Math.addExact(debit, j.debitMinor());
            credit = Math.addExact(credit, j.creditMinor());
            out.append(String.format(
                    "  %-10s %-24s debit %15d  credit %15d  %s%s%n",
                    j.branch(),
                    j.kind(),
                    j.debitMinor(),
                    j.creditMinor(),
                    j.status(),
                    j.entryNo() == null ? "" : " " + j.entryNo()));
        }
        out.append(String.format("  %-10s %-24s debit %15d  credit %15d%n%n", "total", "", debit, credit));
        if (!stock.isEmpty() || !receivables.isEmpty() || !loanPrincipal.isEmpty() || !arrears.isEmpty()) {
            out.append("balances written:\n");
            stock.forEach(s -> out.append(String.format(
                    "  %-10s stock %s value %d%n",
                    s.branch(), s.qty().stripTrailingZeros().toPlainString(), s.valueMinor())));
            receivables.forEach(
                    r -> out.append(String.format("  %-10s receivables %d%n", r.branch(), r.amountMinor())));
            loanPrincipal.forEach(
                    l -> out.append(String.format("  %-10s loan principal %d%n", l.branch(), l.amountMinor())));
            arrears.forEach(a -> out.append(String.format(
                    "  %-10s arrears %s loans %d principal %d%n",
                    a.branch(), a.bucket(), a.loans(), a.principalMinor())));
            out.append('\n');
        }
        notes.forEach(n -> out.append(n).append('\n'));
        if (!notes.isEmpty()) {
            out.append('\n');
        }
        out.append("anomalies (").append(anomalies.size()).append("):\n");
        anomalies.forEach(a -> out.append("  ")
                .append(a.line() > 0 ? a.file() + ":" + a.line() + ": " : a.file() + ": ")
                .append(a.message())
                .append('\n'));
        out.append('\n');
        out.append(checksum == null ? "balances checksum: not computed" : checksum)
                .append('\n');
        return out.toString();
    }

    /**
     * What a writer tells the report while it writes a batch (issue #132). {@link BatchWriter.Checkpoint} is one, so a
     * template's loader reports through the checkpoint it is given; {@link Builder} is another, for a report assembled
     * outside a run (the {@code import-retail} command's own report).
     */
    public interface Sink {

        /**
         * A line of the table for one file the batch holds, when it holds several. Without any, the batch is one line, its
         * counts read from the map its writer returns ({@code read}, {@code written}, {@code existing}, {@code skipped}).
         */
        void counts(String source, int read, int written, int existing, int skipped);

        /** An anomaly on a line of the batch's own file (0 for the file as a whole). */
        void anomaly(int line, String message);

        /** An anomaly on a line of a named file the batch holds. */
        void anomaly(String file, int line, String message);

        /**
         * An opening journal the batch posted: its debit and credit totals as the ledger holds them for the entry (read back
         * from its lines, never the amount the writer meant to post), its status and entry number.
         */
        void journal(String branch, String kind, long debitMinor, long creditMinor, String status, String entryNo);

        /** Stock the batch put on hand at a branch: the quantity and its value at cost. */
        void stock(String branch, BigDecimal qty, long valueMinor);

        /** A receivable the batch opened at a branch. */
        void receivable(String branch, long amountMinor);

        /** Loan principal outstanding the batch opened at a branch. */
        void loanPrincipal(String branch, long amountMinor);

        /** Loans the batch opened at a branch in one days-past-due bucket, and their principal outstanding. */
        void arrears(String branch, String bucket, int loans, long principalMinor);

        /** A line of the template's own words for the report: codes, counts and amounts, never a value of a row. */
        void note(String note);

        /** The balances checksum line, as {@code import-retail} prints it; the last one given is kept. */
        void checksum(String line);
    }

    /** Assembles a report from what a writer tells it, in order. */
    public static final class Builder implements Sink {

        private final String file;
        private final List<Source> sources = new ArrayList<>();
        private final List<Anomaly> anomalies = new ArrayList<>();
        private final List<Journal> journals = new ArrayList<>();
        private final List<ImportForecast.Stock> stock = new ArrayList<>();
        private final List<ImportForecast.Amount> receivables = new ArrayList<>();
        private final List<ImportForecast.Amount> loanPrincipal = new ArrayList<>();
        private final List<ImportForecast.ArrearsBucket> arrears = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private String checksum;
        private String failure;

        /** @param file the file an anomaly without one is on, or null */
        public Builder(String file) {
            this.file = file;
        }

        @Override
        public void counts(String source, int read, int written, int existing, int skipped) {
            sources.add(new Source(source, read, written, existing, skipped));
        }

        @Override
        public void anomaly(int line, String message) {
            anomaly(file == null ? "-" : file, line, message);
        }

        @Override
        public void anomaly(String file, int line, String message) {
            anomalies.add(new Anomaly(file, Math.max(0, line), message));
        }

        @Override
        public void journal(
                String branch, String kind, long debitMinor, long creditMinor, String status, String entryNo) {
            journals.add(new Journal(branch, kind, debitMinor, creditMinor, status, entryNo));
        }

        @Override
        public void stock(String branch, BigDecimal qty, long valueMinor) {
            stock.add(new ImportForecast.Stock(branch, qty, valueMinor));
        }

        @Override
        public void receivable(String branch, long amountMinor) {
            receivables.add(new ImportForecast.Amount(branch, amountMinor));
        }

        @Override
        public void loanPrincipal(String branch, long amountMinor) {
            loanPrincipal.add(new ImportForecast.Amount(branch, amountMinor));
        }

        @Override
        public void arrears(String branch, String bucket, int loans, long principalMinor) {
            arrears.add(new ImportForecast.ArrearsBucket(branch, bucket, loans, principalMinor));
        }

        @Override
        public void note(String note) {
            notes.add(note);
        }

        @Override
        public void checksum(String line) {
            this.checksum = line;
        }

        /** Why the run stopped: the batch and the kind of failure, or a refusal's code and fixed sentence (#885). */
        public void fail(String failure) {
            this.failure = failure;
        }

        /** Whether any line of the table was given. */
        public boolean hasCounts() {
            return !sources.isEmpty();
        }

        /** Adds what another part holds, after what this one holds; its checksum and failure win when set. */
        public void add(CommitReport part) {
            sources.addAll(part.sources());
            anomalies.addAll(part.anomalies());
            journals.addAll(part.journals());
            stock.addAll(part.stock());
            receivables.addAll(part.receivables());
            loanPrincipal.addAll(part.loanPrincipal());
            arrears.addAll(part.arrears());
            notes.addAll(part.notes());
            if (part.checksum() != null) {
                checksum = part.checksum();
            }
            if (part.failure() != null) {
                failure = part.failure();
            }
        }

        public CommitReport build(String mode) {
            return new CommitReport(
                    mode,
                    sources,
                    anomalies,
                    journals,
                    stock,
                    receivables,
                    loanPrincipal,
                    arrears,
                    notes,
                    checksum,
                    failure);
        }
    }
}
