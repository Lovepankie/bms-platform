package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.RegisterFigures;
import com.rincoltech.bms.lending.insights.RegisterFigures.CollateralFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code lending.collateral_register} (FR-COL-06; chapter 14 section 14.5, permission {@code lending.reports.members}):
 * every collateral item in the caller's branches as it stands now, through {@link RegisterFigures#collateral}. Two
 * tables:
 *
 * <ul>
 *   <li>{@code items}: per item the branch, member, type, description, reference, estimated value, latest valuation and
 *       its date, custody status and location, the linked loans with their status, and its flags.
 *   <li>{@code flags}: how many items carry each flag, and how many items there are.
 * </ul>
 *
 * The branches are those of {@code lending.reports.members} for the caller, resolved at request time and frozen with the
 * run; who owns an item (the member's number and name, the item's reference and description) is printed only where
 * the requester holds {@code lending.members.read}, by the member's branch; and a requester without
 * {@code lending.insights.all_officers} gets only the items that secure their own loans. All of it is frozen the same way,
 * and a download by someone whose access to any of it has narrowed since is refused. The PDF cuts a long register at the row cap with a pointer to the Excel file; the
 * summary table comes first so it is always printed whole.
 */
@Component
class CollateralRegisterReport implements ReportDefinition {

    static final String KEY = "lending.collateral_register";
    static final String PERMISSION = "lending.reports.members";
    static final String MEMBERS_READ = "lending.members.read";

    static final String RELEASE_OVERDUE = "Release overdue";
    static final String UNVERIFIED = "Imported, not verified";
    static final String NO_VALUATION = "No valuation";

    private final RegisterFigures figures;

    CollateralRegisterReport(RegisterFigures figures) {
        this.figures = figures;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Collateral register";
    }

    @Override
    public String description() {
        return "Every collateral item in your branches: its value, where it is kept, the loans it secures, and what needs attention.";
    }

    @Override
    public String module() {
        return "lending";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."),
                new Param(
                        "collateral_type",
                        "enum",
                        false,
                        "Only items of this type; every type when absent.",
                        RegisterFigures.COLLATERAL_TYPES),
                new Param(
                        "custody_status",
                        "enum",
                        false,
                        "Only items in this custody status; every status when absent.",
                        RegisterFigures.CUSTODY_STATUSES));
    }

    /** An item never valued is ordinary, so its empty value cells print blank, not as a dash. */
    @Override
    public boolean blankMissingFigures() {
        return true;
    }

    @Override
    public Set<String> formats() {
        return Set.of("json", "csv", "pdf", "xlsx");
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        String type = ReportParams.choice(params, "collateral_type", RegisterFigures.COLLATERAL_TYPES, null);
        String custody = ReportParams.choice(params, "custody_status", RegisterFigures.CUSTODY_STATUSES, null);
        Principal principal = CurrentPrincipal.require();
        List<UUID> scope = principal.branchFilter(PERMISSION, branches);
        Map<String, Object> normalised = new LinkedHashMap<>();
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        if (type != null) {
            normalised.put("collateral_type", type);
        }
        if (custody != null) {
            normalised.put("custody_status", custody);
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put(BRANCHES, ids(scope));
        frozen.put(NAME_BRANCHES, ids(principal.branchFilter(MEMBERS_READ, scope)));
        // A loan officer sees only their own loans (chapter 7): without every officer's loans, the items securing
        // theirs.
        frozen.put(
                OFFICER,
                principal.hasPermission(ALL_OFFICERS)
                        ? null
                        : principal.userId().toString());
        // The register is the items as they stand now; the business date of the request is printed as its date.
        return new Prepared(normalised, frozen, figures.today());
    }

    static final String BRANCHES = "branch_ids";
    static final String NAME_BRANCHES = "name_branch_ids";
    static final String OFFICER = "officer_id";
    static final String ALL_OFFICERS = "lending.insights.all_officers";

    private static UUID officer(Prepared run) {
        Object value = run.scope().get(OFFICER);
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    private static List<String> ids(List<UUID> branches) {
        return branches == null ? null : branches.stream().map(UUID::toString).toList();
    }

    private static List<UUID> branches(Prepared run) {
        return branches(run, BRANCHES);
    }

    /** The frozen branches of {@code key}: null for every branch, empty for none. */
    private static List<UUID> branches(Prepared run, String key) {
        Object value = run.scope().get(key);
        if (value == null) {
            return null;
        }
        List<UUID> out = new ArrayList<>();
        for (Object id : (List<?>) value) {
            out.add(UUID.fromString(String.valueOf(id)));
        }
        return out;
    }

    @Override
    public boolean stillAllowed(Prepared run) {
        Principal principal = CurrentPrincipal.require();
        // A file of every officer's items is not for someone who now sees only their own loans.
        boolean officers =
                officer(run) != null ? officer(run).equals(principal.userId()) : principal.hasPermission(ALL_OFFICERS);
        return officers
                && stillCovers(principal, PERMISSION, branches(run))
                && stillCovers(principal, MEMBERS_READ, branches(run, NAME_BRANCHES));
    }

    /** True when the caller still holds {@code permission} in every branch the run was made with it in. */
    private static boolean stillCovers(Principal principal, String permission, List<UUID> frozen) {
        if (frozen != null && frozen.isEmpty()) {
            return true;
        }
        List<UUID> now = principal.branchFilter(permission, frozen);
        return frozen == null ? now == null : now == null || new HashSet<>(now).containsAll(frozen);
    }

    @Override
    public String describeScope(Prepared run) {
        List<UUID> b = branches(run);
        String branches = b == null
                ? "All branches in your access"
                : b.isEmpty() ? "No branches" : b.size() == 1 ? "1 branch selected" : b.size() + " branches selected";
        return officer(run) == null ? branches : branches + ", the items of your own loans only";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        List<UUID> nameBranches = branches(prepared, NAME_BRANCHES);
        boolean names = nameBranches == null || !nameBranches.isEmpty();
        out.note(
                "The items as they stand when the report is made; custody and values are not read back to a past date.");
        out.note(
                RELEASE_OVERDUE + ": still pledged or in our custody, and every loan it secures is closed, cancelled or"
                        + " rejected.");
        out.note("Who owns an item (the member, their name, and the item's reference and description) is shown only in"
                + " the branches where you may see members.");
        if (officer(prepared) != null) {
            out.note("Only your own loans are named under Loans.");
        }
        out.note(UNVERIFIED + ": brought in by an import and not valued, moved or noted since.");
        out.note(NO_VALUATION + ": no valuation recorded; the estimated value is the one given at registration.");

        // The counts are written first, from a first pass over the items, so the PDF prints them whole however long
        // the register is. Both passes read inside the run's one REPEATABLE READ snapshot transaction (RunExecutor),
        // so the counts and the items are of the same instant.
        long[] counts = new long[4];
        CollateralFilter filter = filter(prepared, nameBranches);
        figures.collateral(filter, out::checkpoint, l -> {
            counts[0]++;
            counts[1] += l.releaseOverdue() ? 1 : 0;
            counts[2] += l.unverified() ? 1 : 0;
            counts[3] += l.noValuation() ? 1 : 0;
        });
        ReportOutput.Table flags = out.table(
                "flags",
                "What needs attention",
                List.of(new Column("flag", "Flag", Kind.TEXT), new Column("items", "Items", Kind.COUNT)));
        flags.row(RELEASE_OVERDUE, counts[1]);
        flags.row(UNVERIFIED, counts[2]);
        flags.row(NO_VALUATION, counts[3]);
        flags.total("Items in the register", counts[0]);

        List<Column> columns = new ArrayList<>();
        columns.add(new Column("branch", "Branch", Kind.TEXT));
        columns.add(new Column("member_no", "Member", Kind.TEXT));
        if (names) {
            columns.add(new Column("member_name", "Name", Kind.TEXT));
        }
        columns.add(new Column("type", "Type", Kind.TEXT));
        columns.add(new Column("description", "Description", Kind.TEXT));
        columns.add(new Column("reference", "Reference", Kind.TEXT));
        columns.add(new Column("estimated_value", "Estimated value", Kind.MONEY));
        columns.add(new Column("latest_valuation", "Latest valuation", Kind.MONEY));
        columns.add(new Column("valued_on", "Valued on", Kind.DATE));
        columns.add(new Column("custody_status", "Custody", Kind.TEXT));
        columns.add(new Column("location", "Kept at", Kind.TEXT));
        columns.add(new Column("loans", "Loans", Kind.TEXT));
        columns.add(new Column("flags", "Flags", Kind.TEXT));
        ReportOutput.Table items = out.table("items", "Collateral items", columns);
        figures.collateral(filter, out::checkpoint, l -> {
            List<Object> cells = new ArrayList<>();
            cells.add(l.branch());
            cells.add(l.memberNo());
            if (names) {
                cells.add(l.memberName());
            }
            cells.add(l.type());
            cells.add(l.description());
            cells.add(l.reference());
            cells.add(l.estimatedValueMinor());
            cells.add(l.latestValuationMinor());
            cells.add(l.valuedOn());
            cells.add(l.custodyStatus());
            cells.add(l.location());
            cells.add(l.loans());
            List<String> f = new ArrayList<>();
            if (l.releaseOverdue()) {
                f.add(RELEASE_OVERDUE);
            }
            if (l.unverified()) {
                f.add(UNVERIFIED);
            }
            if (l.noValuation()) {
                f.add(NO_VALUATION);
            }
            cells.add(f.isEmpty() ? null : String.join("; ", f));
            items.row(cells.toArray());
        });
    }

    private static CollateralFilter filter(Prepared prepared, List<UUID> nameBranches) {
        return new CollateralFilter(
                branches(prepared),
                (String) prepared.params().get("collateral_type"),
                (String) prepared.params().get("custody_status"),
                nameBranches,
                officer(prepared));
    }
}
