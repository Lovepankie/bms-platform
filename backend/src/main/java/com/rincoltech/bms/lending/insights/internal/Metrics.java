package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.lending.insights.internal.InsightsApi.Drill;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Metric;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The metrics dictionary in code: every number the insights page shows, with the definition its
 * info tooltip carries. {@code docs/specs/lending-insights-metrics.md} lists the same keys with
 * the formula and source tables, and {@code MetricsDictionaryTest} fails when the two drift.
 */
final class Metrics {

    static final String MONEY = "money";
    static final String COUNT = "count";
    static final String BP = "basis_points";
    static final String DAYS = "days";
    static final String HOURS = "hours";

    record Definition(String label, String kind, String definition) {}

    static final Map<String, Definition> ALL = new LinkedHashMap<>();

    private static void def(String key, String label, String kind, String definition) {
        ALL.put(key, new Definition(label, kind, definition));
    }

    static {
        // Morning brief: the business date only, read live.
        def(
                "brief.disbursed_today",
                "Money out today",
                MONEY,
                "Principal of loans disbursed with today's value date. Fees deducted at disbursement are part of it.");
        def(
                "brief.disbursed_loans_today",
                "Loans disbursed today",
                COUNT,
                "Number of disbursements with today's value date.");
        def(
                "brief.collected_today",
                "Money in today",
                MONEY,
                "Repayments and recoveries with today's value date that have not been reversed.");
        def(
                "brief.expected_today",
                "Due today",
                MONEY,
                "Principal, interest and fees of schedule items due today, on loans active today.");
        def(
                "brief.collected_on_due_today",
                "Collected on today's dues",
                MONEY,
                "Principal, interest and fees allocated to the items due today, from repayments not reversed.");
        def(
                "brief.collection_rate_today",
                "Collection rate today",
                BP,
                "Collected on today's dues divided by due today. Empty when nothing is due.");
        def(
                "brief.new_arrears",
                "New arrears",
                COUNT,
                "Active loans that fell into arrears today: the oldest unpaid item was due yesterday (1 day past"
                        + " due).");
        def(
                "brief.new_arrears_amount",
                "New arrears amount",
                MONEY,
                "Unpaid principal, interest and fees overdue on the loans that fell into arrears today.");
        def(
                "brief.going_bad",
                "Going bad this week",
                COUNT,
                "Active loans 24 to 30 days past due: unless paid they pass 30 days past due within 7 days.");
        def(
                "brief.going_bad_principal",
                "Principal going bad this week",
                MONEY,
                "Principal outstanding of the loans going bad this week.");

        // Loan portfolio.
        def(
                "portfolio.principal_outstanding",
                "Principal outstanding",
                MONEY,
                "Unpaid principal of schedule items of active loans. Written-off and closed loans are excluded.");
        def(
                "portfolio.interest_receivable",
                "Interest receivable",
                MONEY,
                "Scheduled interest not yet paid or waived on active loans. A memo figure: the ledger books"
                        + " interest when it is paid.");
        def("portfolio.active_loans", "Active loans", COUNT, "Loans with status active.");
        def("portfolio.borrowers", "Active borrowers", COUNT, "Distinct members with at least one active loan.");
        def(
                "portfolio.arrears",
                "Arrears",
                MONEY,
                "Unpaid principal, interest and fees of items due before today on active loans.");
        def(
                "portfolio.par1",
                "PAR 1",
                BP,
                "Principal outstanding of active loans 1 or more days past due, divided by all principal"
                        + " outstanding.");
        def(
                "portfolio.par30",
                "PAR 30",
                BP,
                "Principal outstanding of active loans more than 30 days past due, divided by all principal"
                        + " outstanding.");
        def(
                "portfolio.par60",
                "PAR 60",
                BP,
                "Principal outstanding of active loans more than 60 days past due, divided by all principal"
                        + " outstanding.");
        def(
                "portfolio.par90",
                "PAR 90",
                BP,
                "Principal outstanding of active loans more than 90 days past due, divided by all principal"
                        + " outstanding.");
        def("portfolio.disbursed", "Disbursed", MONEY, "Principal of disbursements with a value date in the range.");
        def("portfolio.disbursed_count", "Loans disbursed", COUNT, "Number of disbursements in the range.");
        def(
                "portfolio.collected",
                "Collected",
                MONEY,
                "Repayments and recoveries with a value date in the range, excluding reversed ones (chapter 14).");
        def(
                "portfolio.expected",
                "Expected",
                MONEY,
                "Principal, interest and fees of schedule items due in the range, on loans active at some point in"
                        + " it. Penalties are not scheduled and are left out.");
        def(
                "portfolio.collected_on_due",
                "Collected on due",
                MONEY,
                "Principal, interest and fees allocated to the items due in the range, from repayments on or before"
                        + " the range end that have not been reversed.");
        def("portfolio.collection_rate", "Collection rate", BP, "Collected on due divided by expected.");
        def(
                "portfolio.forecast_7",
                "Expected next 7 days (estimate)",
                MONEY,
                "Unpaid principal, interest and fees of items of active loans due in the next 7 days. An estimate:"
                        + " it assumes every borrower pays on time and nothing is prepaid.");
        def(
                "portfolio.forecast_30",
                "Expected next 30 days (estimate)",
                MONEY,
                "Unpaid principal, interest and fees of items of active loans due in the next 30 days. An estimate:"
                        + " it assumes every borrower pays on time and nothing is prepaid.");
        def("portfolio.written_off", "Written off", MONEY, "Principal written off with a date in the range.");
        def("portfolio.written_off_count", "Loans written off", COUNT, "Number of write-offs in the range.");
        def(
                "portfolio.recovered",
                "Recovered",
                MONEY,
                "Recoveries on written-off loans with a value date in the range, excluding reversed ones.");
        def(
                "portfolio.repeat_borrowers",
                "Repeat borrowers",
                COUNT,
                "Members disbursed a loan in the range who had an earlier disbursed loan.");
        def(
                "portfolio.repeat_share",
                "Repeat share",
                BP,
                "Repeat borrowers divided by all members disbursed a loan in the range.");
        def(
                "portfolio.average_loan_size",
                "Average loan size",
                MONEY,
                "Principal disbursed in the range divided by the number of disbursements, rounded half up.");
        def(
                "portfolio.average_tenor_days",
                "Average tenor",
                DAYS,
                "Mean of maturity date minus disbursement date for loans disbursed in the range.");
        def(
                "portfolio.yield",
                "Portfolio yield (annualised)",
                BP,
                "Interest and fee income posted in the range divided by the average daily principal outstanding,"
                        + " scaled to 365 days.");

        // Revenue, from posted journal lines only.
        def(
                "revenue.interest",
                "Interest earned",
                MONEY,
                "Credits less debits on the loan interest income account in the range (posted entries).");
        def(
                "revenue.fees",
                "Fees earned",
                MONEY,
                "Credits less debits on the loan fee income account in the range (posted entries).");
        def(
                "revenue.penalties",
                "Penalties earned",
                MONEY,
                "Credits less debits on the loan penalty income account in the range (posted entries).");
        def(
                "revenue.recovered",
                "Bad debt recovered",
                MONEY,
                "Credits less debits on the bad debt recovered account in the range (posted entries).");
        def(
                "revenue.write_off_expense",
                "Write-off expense",
                MONEY,
                "Debits less credits on the loan write-off expense account in the range (posted entries).");
        def(
                "revenue.contribution",
                "Profit contribution",
                MONEY,
                "Interest, fees, penalties and recoveries earned less write-off expense. No cost of funds or"
                        + " operating cost is allocated.");
        def(
                "revenue.effective_yield",
                "Effective yield (annualised)",
                BP,
                "Interest, fees and penalties earned divided by the average daily principal outstanding, scaled to"
                        + " 365 days.");

        // Member activity.
        def("members.new_members", "New members", COUNT, "Members registered with a date in the range.");
        def(
                "members.active_borrowers",
                "Active borrowers",
                COUNT,
                "Distinct members with at least one active loan today.");
        def("members.applied", "Applied", COUNT, "Applications submitted in the range.");
        def(
                "members.appraised",
                "Appraised",
                COUNT,
                "Applications submitted in the range that have at least one appraisal.");
        def(
                "members.approved",
                "Approved",
                COUNT,
                "Applications submitted in the range that were approved (whatever happened after).");
        def("members.disbursed", "Disbursed", COUNT, "Applications submitted in the range that were disbursed.");
        def(
                "members.median_decision_hours",
                "Median time to decision",
                HOURS,
                "Median hours from submission to approval or rejection, for applications decided in the range.");
        def(
                "members.kyc_verified_share",
                "KYC verified",
                BP,
                "Active members with KYC verified divided by all active members.");
        def(
                "members.dormant",
                "Dormant members",
                COUNT,
                "Active members with no active loan and no loan transaction in the last 90 days.");
    }

    private Metrics() {}

    static Metric of(String key, Long value, String currency, Drill drill) {
        Definition d = ALL.get(key);
        if (d == null) {
            throw new IllegalArgumentException("no metric " + key);
        }
        return new Metric(
                key, d.label(), d.kind(), value, d.kind().equals(MONEY) ? currency : null, d.definition(), drill);
    }

    /** {@code part / whole} in basis points, rounded half up; null when the whole is zero. */
    static Long bp(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return Math.floorDiv(part * 20_000L + whole, whole * 2L);
    }
}
