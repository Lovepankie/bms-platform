package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code lending.arrears_list} (FR-CLN-03; chapter 14 section 14.4, permission {@code lending.reports.collections}):
 * every active loan more than zero days past due on a date, grouped by age bucket and most overdue first, through
 * {@link InsightsFigures#arrears}, so its loans, days and arrears are those of {@code lending.par}. One table,
 * {@code arrears}, with a total row.
 *
 * <p>What a member's line shows is decided at request time and frozen with the run, as the collection lists decide it:
 * the member's name for a holder of {@code lending.members.read}, and the phone in full only in the branches where the
 * requester holds {@code lending.members.contact} (masked elsewhere). A download by someone whose access has narrowed
 * since is refused.
 */
@Component
class ArrearsListReport extends PortfolioReport {

    static final String KEY = "lending.arrears_list";
    static final List<String> BUCKETS = List.of("1_30", "31_60", "61_90", "over_90");
    static final String MEMBERS_READ = "lending.members.read";
    static final String CONTACT = "lending.members.contact";

    private static final Map<String, String> BUCKET_LABELS = Map.of(
            "1_30", "1 to 30 days late",
            "31_60", "31 to 60 days late",
            "61_90", "61 to 90 days late",
            "over_90", "Over 90 days late");

    ArrearsListReport(InsightsFigures figures, ObjectMapper mapper) {
        super(figures, mapper);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Arrears list";
    }

    @Override
    public String description() {
        return "Every loan that is late on a date, by how late it is, with the last payment, the last follow up and any promise to pay.";
    }

    @Override
    public String permission() {
        return "lending.reports.collections";
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param(
                        "as_at",
                        "date",
                        false,
                        "The date the loans stand at; today when absent. A past date reads the nightly snapshot."),
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."),
                new Param("bucket", "enum", false, "Only the loans this late; every late loan when absent.", BUCKETS));
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        LocalDate asked = ReportParams.date(params, "as_at", null);
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        String bucket = ReportParams.choice(params, "bucket", BUCKETS, null);
        PortfolioScope resolved = figures.scope(asked, asked, branches, null, null);
        LocalDate asAt = resolved.to();
        PortfolioScope scope = new PortfolioScope(
                asAt, asAt, resolved.today(), resolved.branchIds(), resolved.officerId(), resolved.productId());
        Principal principal = CurrentPrincipal.require();
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("as_at", asAt.toString());
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        if (bucket != null) {
            normalised.put("bucket", bucket);
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("portfolio", mapper.convertValue(scope, Map.class));
        frozen.put("names", principal.hasPermission(MEMBERS_READ));
        List<UUID> contact = principal.branchFilter(CONTACT, scope.branchIds());
        frozen.put(
                "contact_branch_ids",
                contact == null ? null : contact.stream().map(UUID::toString).toList());
        return new Prepared(normalised, frozen, asAt);
    }

    @Override
    PortfolioScope scope(Prepared prepared) {
        return mapper.convertValue(prepared.scope().get("portfolio"), PortfolioScope.class);
    }

    private static boolean names(Prepared prepared) {
        return Boolean.TRUE.equals(prepared.scope().get("names"));
    }

    private static List<UUID> contactBranches(Prepared prepared) {
        Object value = prepared.scope().get("contact_branch_ids");
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
        if (!super.stillAllowed(run)) {
            return false;
        }
        Principal principal = CurrentPrincipal.require();
        if (names(run) && !principal.hasPermission(MEMBERS_READ)) {
            return false;
        }
        List<UUID> frozen = contactBranches(run);
        List<UUID> now = principal.branchFilter(CONTACT, frozen);
        return frozen == null ? now == null : now == null || new HashSet<>(now).containsAll(frozen);
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        boolean names = names(prepared);
        String bucket = (String) prepared.params().get("bucket");
        PortfolioScope scope = scope(prepared);
        out.note("Only active loans more than 0 days late are listed; written-off loans are not.");
        out.note("Arrears is the principal, interest and fees due and not paid; penalties are shown on their own.");
        if (!scope.to().equals(scope.today())) {
            out.note("Penalties outstanding are not shown for a past date: the nightly snapshot does not hold them.");
        }
        out.note("Phone numbers are shown in full only in the branches where you may contact borrowers.");

        List<Column> columns = new ArrayList<>();
        columns.add(new Column("bucket", "How late", Kind.TEXT));
        columns.add(new Column("loan_no", "Loan", Kind.TEXT));
        columns.add(new Column("member_no", "Member", Kind.TEXT));
        if (names) {
            columns.add(new Column("member_name", "Name", Kind.TEXT));
        }
        columns.add(new Column("phone", "Phone", Kind.TEXT));
        columns.add(new Column("officer", "Officer", Kind.TEXT));
        columns.add(new Column("days_past_due", "Days late", Kind.COUNT));
        columns.add(new Column("arrears", "Arrears", Kind.MONEY));
        columns.add(new Column("penalties_outstanding", "Penalties outstanding", Kind.MONEY));
        columns.add(new Column("principal_outstanding", "Principal outstanding", Kind.MONEY));
        columns.add(new Column("last_repayment_on", "Last repayment", Kind.DATE));
        columns.add(new Column("last_action", "Last follow up", Kind.TEXT));
        columns.add(new Column("last_action_on", "Follow up date", Kind.DATE));
        columns.add(new Column("promised_date", "Promised date", Kind.DATE));
        columns.add(new Column("promised_amount", "Promised amount", Kind.MONEY));
        ReportOutput.Table table = out.table("arrears", "Loans in arrears", columns);

        long[] totals = new long[4];
        boolean[] anyPenalty = {false};
        figures.arrears(scope, bucket, names, contactBranches(prepared), out::checkpoint, l -> {
            totals[0]++;
            totals[1] += l.arrearsMinor();
            totals[2] += l.penaltiesOutstandingMinor() == null ? 0 : l.penaltiesOutstandingMinor();
            totals[3] += l.principalOutstandingMinor();
            anyPenalty[0] |= l.penaltiesOutstandingMinor() != null;
            List<Object> cells = new ArrayList<>();
            cells.add(BUCKET_LABELS.get(l.bucket()));
            cells.add(l.loanNo());
            cells.add(l.memberNo());
            if (names) {
                cells.add(l.memberName());
            }
            cells.add(l.phone());
            cells.add(l.officer());
            cells.add(l.daysPastDue());
            cells.add(l.arrearsMinor());
            cells.add(l.penaltiesOutstandingMinor());
            cells.add(l.principalOutstandingMinor());
            cells.add(l.lastRepaymentOn());
            cells.add(l.lastAction() == null ? null : actionLabel(l.lastAction()));
            cells.add(l.lastActionOn());
            cells.add(l.promisedDate());
            cells.add(l.promisedAmountMinor());
            table.row(cells.toArray());
        });
        List<Object> total = new ArrayList<>();
        total.add("Total, " + totals[0] + (totals[0] == 1 ? " loan" : " loans"));
        total.add(null);
        total.add(null);
        if (names) {
            total.add(null);
        }
        total.add(null);
        total.add(null);
        total.add(null);
        total.add(totals[1]);
        total.add(anyPenalty[0] || scope.to().equals(scope.today()) ? totals[2] : null);
        total.add(totals[3]);
        total.add(null);
        total.add(null);
        total.add(null);
        total.add(null);
        total.add(null);
        table.total(total.toArray());
    }

    /** A collection action type in words. */
    static String actionLabel(String type) {
        return switch (type) {
            case "call" -> "Call";
            case "visit" -> "Visit";
            case "sms" -> "SMS";
            case "letter" -> "Letter";
            case "promise_to_pay" -> "Promise to pay";
            default -> "Other";
        };
    }
}
