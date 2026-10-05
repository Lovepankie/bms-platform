package com.rincoltech.bms.retail.imports.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one run did, printed as plain text at the end of the command: counts per file, anomalies,
 * the opening journals per branch and the balances checksum. Holds codes and quantities only, never
 * a buyer's name or contact.
 */
public final class ImportReport {

    /** Per file: rows read, written, already present (found or imported before) and skipped. */
    static final class Counts {
        int read;
        int written;
        int existing;
        int skipped;
    }

    record Opening(String branch, long debitMinor, String entryNo, String status) {}

    private final String tenant;
    private final String dir;
    private final boolean dryRun;
    private final Map<String, Counts> files = new LinkedHashMap<>();
    private final List<String> anomalies = new ArrayList<>();
    private final List<String> negatives = new ArrayList<>();
    private final List<Opening> openings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private String checksum = "not computed";
    private String failure;

    ImportReport(String tenant, String dir, boolean dryRun) {
        this.tenant = tenant;
        this.dir = dir;
        this.dryRun = dryRun;
    }

    Counts file(String name) {
        return files.computeIfAbsent(name, n -> new Counts());
    }

    void anomaly(String file, int line, String message) {
        anomalies.add(line > 0 ? file + ":" + line + ": " + message : file + ": " + message);
    }

    void skip(String file, int line, String message) {
        file(file).skipped++;
        anomaly(file, line, message + "; row skipped");
    }

    void negative(String line) {
        negatives.add(line);
    }

    void opening(Opening opening) {
        openings.add(opening);
    }

    void note(String note) {
        notes.add(note);
    }

    void checksum(String checksum) {
        this.checksum = checksum;
    }

    void fail(String failure) {
        this.failure = failure;
    }

    public boolean failed() {
        return failure != null;
    }

    public int anomalyCount() {
        return anomalies.size();
    }

    public List<String> anomalies() {
        return List.copyOf(anomalies);
    }

    public String checksumLine() {
        return checksum;
    }

    /** Written, existing and skipped of one file, for tests and callers. */
    public int[] counts(String name) {
        Counts c = files.getOrDefault(name, new Counts());
        return new int[] {c.read, c.written, c.existing, c.skipped};
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("import-retail report\n");
        out.append("tenant: ").append(tenant).append('\n');
        out.append("directory: ").append(dir).append('\n');
        out.append("mode: ")
                .append(dryRun ? "dry run, every write rolled back" : "committed, one transaction per file")
                .append('\n');
        out.append("result: ")
                .append(failure == null ? "ok" : "FAILED: " + failure)
                .append("\n\n");
        out.append(String.format("%-18s %8s %8s %8s %8s%n", "file", "read", "written", "existing", "skipped"));
        files.forEach((name, c) ->
                out.append(String.format("%-18s %8d %8d %8d %8d%n", name, c.read, c.written, c.existing, c.skipped)));
        out.append('\n');
        out.append("opening journals (debit inventory, credit opening balance equity):\n");
        if (openings.isEmpty()) {
            out.append("  none\n");
        }
        long total = 0;
        for (Opening o : openings) {
            total = Math.addExact(total, o.debitMinor());
            out.append(String.format(
                    "  %-10s debit %15d  credit %15d  %s%s%n",
                    o.branch(),
                    o.debitMinor(),
                    o.debitMinor(),
                    o.status(),
                    o.entryNo() == null ? "" : " " + o.entryNo()));
        }
        out.append(String.format("  %-10s debit %15d  credit %15d%n%n", "total", total, total));
        out.append("negative source balances, excluded from the opening journal, for the first stock-take ("
                + negatives.size() + "):\n");
        negatives.forEach(n -> out.append("  ").append(n).append('\n'));
        out.append('\n');
        notes.forEach(n -> out.append(n).append('\n'));
        if (!notes.isEmpty()) {
            out.append('\n');
        }
        out.append("anomalies (").append(anomalies.size()).append("):\n");
        anomalies.forEach(a -> out.append("  ").append(a).append('\n'));
        out.append('\n');
        out.append(checksum).append('\n');
        return out.toString();
    }
}
