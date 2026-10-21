package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.notifications.PushPreferences;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.PushNotice;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The two daily push notices of retail (issue #412, ADR-057), prepared for the bound tenant by the job {@link DailyNoticeJobs}
 * every 15 minutes, each at most once a day (a row in {@code retail_notice_runs} is taken first):
 *
 * <ul>
 *   <li><b>credit payments</b>, from {@value #CREDIT_HOUR}:00 on the business's clock, when a credit sale falls due today or
 *       fell overdue yesterday: one notice to the administrators over every shop, and one per shop to the supervisors of
 *       that shop (the holders of {@code retail.sale.all_sellers} given for that branch, the Supervisor of #404; administrators
 *       hold it everywhere through a branchless role and hear the whole business once; sellers and storekeepers do not
 *       hold it). Counts only;
 *   <li>the <b>daily closing summary</b>, at the time the business set (20:00 by default), to the owner and the
 *       administrators: the number of sales of the day at each shop. A notification lies on a lock screen, so it carries no
 *       amount (ADR-050 decision 11); a tap opens the day's sales, where the totals are shown to those who may see them.
 * </ul>
 */
@Component
class DailyNotices {

    static final int CREDIT_HOUR = 8;
    /** Neither the morning notice nor a missed summary of yesterday is sent from this hour on. */
    static final int CREDIT_CUTOFF_HOUR = 12;

    static final String ADMINS = "core.settings.manage";
    static final String SUPERVISORS = "retail.sale.all_sellers";

    /** The longest list of shops the body takes before it says how many more there are. */
    static final int SHOPS_MAX_CHARS = 140;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK);

    /** What a credit sale still owes, as in the credit control report: the unpaid total and a delivery fee left on the account. */
    private static final String FEE = "coalesce((SELECT o.delivery_fee_minor FROM retail_customer_orders o"
            + " WHERE o.sale_id = s.id AND o.fee_method = 'account' AND o.fee_paid_at IS NULL), 0)";

    private final JdbcClient jdbc;
    private final PushPreferences preferences;
    private final ApplicationEventPublisher events;
    private final CurrentTenant tenant;

    DailyNotices(JdbcClient jdbc, PushPreferences preferences, ApplicationEventPublisher events, CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.preferences = preferences;
        this.events = events;
        this.tenant = tenant;
    }

    /** One tick for the bound tenant at {@code now}. Package visible so a test drives it at a chosen time. */
    void runAt(Instant now) {
        ZoneId zone = tenant.profile().timezone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalTime time = LocalTime.ofInstant(now, zone);
        // The morning notice says "today": after the cut-off (a server down all morning) it is not sent late that day;
        // the
        // next morning's counts still carry what is overdue.
        if (!time.isBefore(LocalTime.of(CREDIT_HOUR, 0))
                && time.isBefore(LocalTime.of(CREDIT_CUTOFF_HOUR, 0))
                && preferences.on(PushPreferences.CREDIT_DUE)
                && claim("credit_due", today)) {
            creditDue(today);
        }
        if (!preferences.on(PushPreferences.DAILY_SUMMARY)) {
            // A day the business switched the summary off is still recorded once its time passes, without a notice, so
            // turning it back on the next morning never sends that day late as "Yesterday's sales".
            if (!time.isBefore(preferences.dailySummaryTime())) {
                claim("daily_summary", today);
            }
            return;
        }
        // A summary that was due yesterday and never prepared (the server was down at closing time) goes after
        // midnight, as
        // yesterday's, until the cut-off; only for a business that has had a summary before, so a first release sends
        // none.
        LocalDate yesterday = today.minusDays(1);
        if (time.isBefore(LocalTime.of(CREDIT_CUTOFF_HOUR, 0))
                && hadASummaryBefore(yesterday)
                && claim("daily_summary", yesterday)) {
            summary(yesterday, true);
        }
        if (!time.isBefore(preferences.dailySummaryTime()) && claim("daily_summary", today)) {
            summary(today, false);
        }
    }

    /** True when a summary was prepared for this business on a day before {@code day}. */
    private boolean hadASummaryBefore(LocalDate day) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_notice_runs WHERE kind = 'daily_summary' AND run_on < ?)")
                .param(Date.valueOf(day))
                .query(Boolean.class)
                .single();
    }

    /** True for the one run that records the day; every later tick of the day finds the row and does nothing. */
    private boolean claim(String kind, LocalDate day) {
        return jdbc.sql("""
                        INSERT INTO retail_notice_runs (tenant_id, kind, run_on)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?)
                        ON CONFLICT DO NOTHING
                        """).params(kind, Date.valueOf(day)).update() == 1;
    }

    record CreditCounts(UUID branchId, int due, int newlyOverdue, int overdue) {}

    private void creditDue(LocalDate today) {
        List<CreditCounts> rows = jdbc.sql("SELECT s.branch_id,"
                        + " count(*) FILTER (WHERE s.due_date = :today) AS due,"
                        + " count(*) FILTER (WHERE s.due_date = :yesterday) AS newly,"
                        + " count(*) FILTER (WHERE s.due_date < :today) AS overdue"
                        + "  FROM retail_sales s"
                        + " WHERE s.payment_method = 'credit' AND s.status = 'completed'"
                        + "   AND (s.total_minor > s.paid_minor OR " + FEE + " > 0)"
                        + "   AND s.due_date <= :today"
                        + " GROUP BY s.branch_id ORDER BY s.branch_id")
                .param("today", Date.valueOf(today))
                .param("yesterday", Date.valueOf(today.minusDays(1)))
                .query((rs, n) -> new CreditCounts(
                        rs.getObject("branch_id", UUID.class),
                        rs.getInt("due"),
                        rs.getInt("newly"),
                        rs.getInt("overdue")))
                .list();
        int due = 0;
        int overdue = 0;
        boolean news = false;
        for (CreditCounts c : rows) {
            due += c.due();
            overdue += c.overdue();
            if (c.due() == 0 && c.newlyOverdue() == 0) {
                continue;
            }
            news = true;
            events.publishEvent(new PushNotice(
                    "retail.credit_due_branch",
                    c.branchId() + ":" + today,
                    new PushNotice.BranchStaff(SUPERVISORS, c.branchId(), null),
                    Map.of(
                            "branch_id", c.branchId().toString(),
                            "due_count", String.valueOf(c.due()),
                            "overdue_count", String.valueOf(c.overdue()),
                            "credit_words", creditWords(c.due(), c.overdue()),
                            "path", "/staff/retail/credit-control")));
        }
        if (news) {
            events.publishEvent(new PushNotice(
                    "retail.credit_due",
                    today.toString(),
                    new PushNotice.Holders(ADMINS, null, null),
                    Map.of(
                            "due_count", String.valueOf(due),
                            "overdue_count", String.valueOf(overdue),
                            "credit_words", creditWords(due, overdue),
                            "path", "/staff/retail/credit-control")));
        }
    }

    /**
     * "2 credit sales fall due today and 3 are overdue in all", without a "0" part: "1 credit sale falls due today" when
     * nothing is overdue, "3 credit sales are overdue in all" when nothing falls due today.
     */
    static String creditWords(int due, int overdue) {
        String over = overdue == 1 ? "1 credit sale is overdue" : overdue + " credit sales are overdue";
        if (due == 0) {
            return over + " in all";
        }
        String today = due == 1 ? "1 credit sale falls due today" : due + " credit sales fall due today";
        if (overdue == 0) {
            return today;
        }
        return today + " and " + overdue + (overdue == 1 ? " is" : " are") + " overdue in all";
    }

    record ShopDay(String name, int sales) {}

    private void summary(LocalDate today, boolean late) {
        List<ShopDay> shops = jdbc.sql("""
                        SELECT b.name, count(s.id) AS sales
                          FROM branches b
                          LEFT JOIN retail_sales s
                            ON s.tenant_id = b.tenant_id AND s.branch_id = b.id AND s.sale_date = ? AND s.status = 'completed'
                         WHERE b.status = 'active' AND b.sells_goods AND b.stocks_goods
                         GROUP BY b.id, b.name
                         ORDER BY b.name, b.id
                        """)
                .param(Date.valueOf(today))
                .query((rs, n) -> new ShopDay(rs.getString("name"), rs.getInt("sales")))
                .list();
        if (shops.isEmpty()) {
            return;
        }
        events.publishEvent(new PushNotice(
                late ? "retail.daily_summary_late" : "retail.daily_summary",
                today.toString(),
                new PushNotice.Holders(ADMINS, null, null),
                Map.of("day", DAY.format(today), "shops", shopsLine(shops), "path", "/staff/retail/sales")));
    }

    /** "Kireka 14 sales, Main 1 sale, Ntinda no sales", cut with "and 3 more shops" so the body fits a lock screen. */
    static String shopsLine(List<ShopDay> shops) {
        List<String> parts = new ArrayList<>();
        int length = 0;
        for (int i = 0; i < shops.size(); i++) {
            ShopDay s = shops.get(i);
            String part =
                    s.name() + " " + (s.sales() == 0 ? "no sales" : s.sales() == 1 ? "1 sale" : s.sales() + " sales");
            if (!parts.isEmpty() && length + part.length() + 2 > SHOPS_MAX_CHARS) {
                int more = shops.size() - i;
                parts.add("and " + more + (more == 1 ? " more shop" : " more shops"));
                break;
            }
            parts.add(part);
            length += part.length() + 2;
        }
        return String.join(", ", parts);
    }
}
