package com.rincoltech.bms.core.imports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The reconciliation of a committed import run and the customer's sign-off in the app (issue #134, spec sections 3.9 and
 * 3.10, FR-IMP-08, ADR-027 decision 8). Every method works for the business bound to the thread (ADR-003) and takes its own
 * transaction.
 *
 * <p>The reconciliation compares, per check, the <b>source value</b> (what the run's staged rows give by the template's own
 * rules, every row that is not blank, the rows the review left out included) with the <b>system value</b> (what the books
 * hold now: the journals through the ledger's port, the stock ledger and the loan book through the template's
 * {@link ImportBooks}, the records through {@code import_refs}), never with the commit's report, so it is independent of the
 * writer. Every difference is itemised with its reason: {@link #EXCLUDED} (a row the review left out, or one an earlier run
 * imported), {@link #OVERRIDDEN} (a row written with a warning overridden, listed for the signer to see) and {@link #POLICY}
 * (a recorded policy decision, or a negative stock balance listed separately). A difference that is not itemised makes the
 * reconciliation not signable.
 *
 * <p>Values are exact decimal strings: money in minor units, quantities as the stock ledger holds them, never rounded. No
 * text holds a personal value of a row: branches and products are named by their codes, rows by file and line, and a text
 * that names a personal value of the run is kept as {@link CommitReport#HIDDEN}.
 */
public interface ImportReconciliation {

    /** A row the review left out, or a row an earlier run already imported. */
    String EXCLUDED = "excluded";

    /** A row written although a warning on it was overridden in the review. */
    String OVERRIDDEN = "overridden";

    /** A recorded policy decision (spec section 3.8), or a negative stock balance listed separately. */
    String POLICY = "policy";

    /**
     * Makes the run's reconciliation anew from the source and the books and stores it as the next version: a run
     * {@code committed} and not signed off (409 {@code run_not_committed} otherwise). The commit makes the first one itself
     * as it finishes; this is for after a correction in the app. Audited.
     */
    Report reconcile(UUID runId, UUID by);

    /** The run's newest reconciliation, with its sign-off once signed; empty while none was made. */
    Optional<Report> latest(UUID runId);

    /**
     * The business's imports that are committed or signed off, newest first, at most 20: what the sign-off screen lists, each
     * with its newest reconciliation's version and whether it can be signed, and when it was signed.
     */
    List<RunSummary> forSignOff();

    /** One import on the sign-off screen. {@code version} and {@code signable} are null while no reconciliation was made. */
    record RunSummary(
            UUID runId, String status, Instant committedAt, Integer version, Boolean signable, Instant signedAt) {}

    /**
     * The customer's sign-off (spec section 3.10): the newest reconciliation of a {@code committed} run, as the signer saw
     * it (its id and sha256: 409 {@code reconciliation_changed} otherwise), with every section ticked (422
     * {@code sections_not_ticked}), the signer's typed name (422 {@code name_required}) and the date, which is today on the
     * business clock (422 {@code sign_off_date_not_today}). A reconciliation with a difference that is not itemised is
     * refused (409 {@code unitemised_difference}). The signed report is stored, never to change again; the run becomes
     * {@code signed_off}; audited without the name. The caller holds {@code core.imports.sign_off} (the route checks it).
     */
    Report signOff(UUID runId, SignOff signOff);

    /** What the signer sends: the reconciliation they read, the sections they ticked, their typed name and the date. */
    record SignOff(UUID reconciliationId, String sha256, List<String> sections, String name, LocalDate date, UUID by) {

        public SignOff {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /**
     * A stored reconciliation: its id and version, when it was made and the day it is as at, the sha256 of its text
     * ({@link #render}), whether every difference is itemised, its sections, and its sign-off once signed.
     */
    record Report(
            UUID id,
            UUID runId,
            int version,
            Instant generatedAt,
            LocalDate asAt,
            String sha256,
            boolean signable,
            List<Section> sections,
            Signed signed) {

        public Report {
            sections = List.copyOf(sections);
        }

        /**
         * The report as plain text: the document the signer signs and the sha256 is taken of. Its own facts only: the run,
         * the version, the day it is as at and every section, never its sign-off.
         */
        public String render() {
            StringBuilder out = new StringBuilder();
            out.append("import reconciliation\n");
            out.append("run: ").append(runId).append('\n');
            out.append("version: ").append(version).append('\n');
            out.append("as at: ").append(asAt).append('\n');
            out.append("signable: ")
                    .append(signable ? "yes" : "no, a difference is not itemised")
                    .append("\n\n");
            for (Section s : sections) {
                out.append("== ").append(s.key()).append(": ").append(s.title()).append('\n');
                for (Fact f : s.facts()) {
                    out.append(String.format("%-60s %20s%n", f.label(), f.value()));
                }
                out.append(String.format(
                        "%-40s %-6s %20s %20s %20s %20s%n",
                        "check", "unit", "source", "system", "difference", "not itemised"));
                for (Check c : s.checks()) {
                    out.append(String.format(
                            "%-40s %-6s %20s %20s %20s %20s%n",
                            c.label(), c.unit(), c.source(), c.system(), c.difference(), c.unitemised()));
                }
                if (!s.items().isEmpty()) {
                    out.append("itemised:\n");
                    for (Item i : s.items()) {
                        out.append("  ")
                                .append(i.check())
                                .append(" | ")
                                .append(i.reason())
                                .append(" | ")
                                .append(i.line() > 0 ? i.file() + ":" + i.line() : i.file())
                                .append(" | ")
                                .append(i.amount())
                                .append(" | ")
                                .append(i.detail())
                                .append('\n');
                    }
                }
                out.append('\n');
            }
            return out.toString();
        }
    }

    /**
     * One part the signer ticks: {@code counts}, {@code stock_quantity}, {@code stock_value}, {@code receivables},
     * {@code loan_principal}, {@code arrears} or {@code ledger}, with its checks and its itemised differences. A section the
     * run has nothing to check in is left out; {@code counts} and {@code ledger} are always there.
     */
    record Section(String key, String title, List<Fact> facts, List<Check> checks, List<Item> items) {

        public Section {
            facts = List.copyOf(facts);
            checks = List.copyOf(checks);
            items = List.copyOf(items);
        }
    }

    /**
     * A figure of the section that is shown and not compared, for example the rows of a file already imported by an
     * earlier run or the records created by type.
     */
    record Fact(String label, String value) {}

    /**
     * One check: its key within the section (for example {@code HQ FAB-900}), its label in plain words, the unit
     * ({@code rows}, {@code qty}, {@code minor}, {@code loans}, {@code entries}), the source value, the system value, the
     * difference (source less system), how much of it the items explain and how much they do not. It ties when nothing is
     * left unexplained.
     */
    record Check(
            String key,
            String label,
            String unit,
            String source,
            String system,
            String difference,
            String itemised,
            String unitemised,
            boolean ties) {}

    /**
     * One itemised difference: the check it explains, the file and line of the row (line 0 for a difference of the run as a
     * whole, such as a policy decision), the reason, the detail in plain words (codes only) and its amount in the check's
     * unit, or {@code 0} for a row listed for the signer without an amount.
     */
    record Item(String check, String file, int line, String reason, String detail, String amount) {

        public Item {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The sign-off: when and by whom, the name and date they typed, and the sections they ticked. */
    record Signed(Instant signedAt, UUID signedBy, String signedName, LocalDate signedOn, List<String> sections) {

        public Signed {
            sections = List.copyOf(sections);
        }
    }
}
