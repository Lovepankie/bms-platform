package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-ARR-01, the nightly arrears run for one tenant: for every active loan, the penalty periods due
 * by today (R-PEN, {@link Penalties}) become charges on their items, then item statuses, DPD and
 * arrears are recomputed (R-DPD) and its due reminders and arrears notices queued ({@link LoanNotices},
 * issue #227); last, the promises to pay are settled (FR-CLN-05). Charges are
 * memorandum items: nothing is posted here.
 *
 * <p>How it stays safe to run twice (the review of #270, finding 4, and ADR-008):
 *
 * <ul>
 *   <li>The run for a business date is claimed in {@code lending_arrears_runs} with a time-bounded lease that it
 *       renews as it goes; a second worker finds the lease held and does nothing, a crashed run is taken over once
 *       its lease has lapsed, and a finished run is never run again for that date. The attempt number fences a run
 *       whose lease was taken over: it stops at its next renewal and cannot mark the date finished.
 *   <li>Each loan is one short transaction of its own under the loan's row lock, so one loan that fails (a missing
 *       product version, an overflow) is logged and counted and the others go on, and no lock is held past the loan.
 *   <li>A charge is keyed by (item, period) in the database ({@code ON CONFLICT DO NOTHING}), so even two runs over
 *       the same loan at once never charge a period twice; and the recompute writes only what moved, so a rerun
 *       writes nothing.
 * </ul>
 */
@Service
class ArrearsRun {

    private static final Logger log = LoggerFactory.getLogger(ArrearsRun.class);

    /** How long a claim holds without a renewal. */
    static final Duration LEASE = Duration.ofMinutes(10);

    /** The lease is renewed after this many loans. */
    static final int RENEW_EVERY = 50;

    private final JdbcClient jdbc;
    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final ProductCatalog products;
    private final LoanServicer servicer;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final CollectionsService collections;
    private final LoanNotices notices;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    /** The push sent when a loan fails the nightly run two nights running. */
    static final String FAILING_TWICE = "lending.arrears_loan_failing";

    ArrearsRun(
            JdbcClient jdbc,
            LoanRepository loans,
            ServicingRepository repo,
            ProductCatalog products,
            LoanServicer servicer,
            CurrentTenant currentTenant,
            BusinessClock clock,
            AuditLog audit,
            CollectionsService collections,
            LoanNotices notices,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.repo = repo;
        this.products = products;
        this.servicer = servicer;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.audit = audit;
        this.collections = collections;
        this.notices = notices;
        this.tx = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    record Result(
            LocalDate businessDate,
            int loans,
            int charges,
            long chargedMinor,
            int failedLoans,
            int promisesKept,
            int promisesBroken,
            int remindersQueued) {}

    /** What one loan's step did. */
    record Outcome(int charges, long chargedMinor, int reminders) {}

    /** The days after its bring-in date in which a brought-in loan gets no arrears SMS (ADR-071 decision 10). */
    static final int BRING_IN_ARREARS_HOLD_DAYS = 7;

    /**
     * The bound tenant's run for its business date today, called outside any transaction (the job binds the tenant
     * with {@code TenantJobs.callAs}). Empty when that date's run has finished or another worker holds its lease.
     */
    Optional<Result> runIfDue() {
        LocalDate today = tx.execute(s -> clock.today(currentTenant.profile().timezone()));
        Integer attempt = tx.execute(s -> claim(today));
        if (attempt == null) {
            return Optional.empty();
        }
        // Penalties start at the business's first arrears run (finding 2 of the review of #270).
        LocalDate from = tx.execute(s -> jdbc.sql("SELECT min(business_date) FROM lending_arrears_runs")
                .query((rs, n) -> rs.getObject(1, LocalDate.class))
                .single());
        List<UUID> active = tx.execute(s -> jdbc.sql("SELECT id FROM lending_loans WHERE status = 'active' ORDER BY id")
                .query(UUID.class)
                .list());
        Map<UUID, PenaltyRule> rules = new HashMap<>();
        int charges = 0;
        long charged = 0;
        int failed = 0;
        List<UUID> failedIds = new java.util.ArrayList<>();
        int reminders = 0;
        int done = 0;
        for (UUID id : active) {
            if (done > 0 && done % RENEW_EVERY == 0 && !Boolean.TRUE.equals(tx.execute(s -> renew(today, attempt)))) {
                log.warn(
                        "arrears run for {} lost its lease after {} loans; the run that took it over goes on",
                        today,
                        done);
                return Optional.empty();
            }
            done++;
            try {
                Outcome o = tx.execute(s -> one(id, today, from, rules));
                if (o != null) {
                    charges += o.charges();
                    charged = Math.addExact(charged, o.chargedMinor());
                    reminders += o.reminders();
                }
            } catch (RuntimeException e) {
                failed++;
                failedIds.add(id);
                log.error(
                        "arrears run for {} failed on loan {} error={}",
                        today,
                        id,
                        e.getClass().getSimpleName(),
                        e);
            }
        }
        // Last, the promises to pay (FR-CLN-05), in a transaction of their own: a failure there is logged and the date
        // still finishes, and tomorrow's run settles them again from the payments that stand.
        CollectionsService.Settled promises = new CollectionsService.Settled(0, 0);
        try {
            promises = tx.execute(s -> collections.settlePromises(today));
        } catch (RuntimeException e) {
            log.error(
                    "arrears run for {} could not settle promises error={}",
                    today,
                    e.getClass().getSimpleName(),
                    e);
        }
        Result result = new Result(
                today, active.size(), charges, charged, failed, promises.kept(), promises.broken(), reminders);
        return Boolean.TRUE.equals(tx.execute(s -> finish(result, attempt, failedIds)))
                ? Optional.of(result)
                : Optional.empty();
    }

    /** One loan in its own transaction: its charges, then its position. */
    private Outcome one(UUID id, LocalDate today, LocalDate from, Map<UUID, PenaltyRule> rules) {
        Loan loan = loans.lock(id).orElseThrow();
        if (!loan.status().equals("active")) {
            return new Outcome(0, 0, 0); // moved since the list was read
        }
        PenaltyRule rule = rules.computeIfAbsent(
                loan.productVersionId(), v -> products.version(v).orElseThrow().penalty());
        // Penalties start at the later of the business's first run and the loan's own start in the system, so a long
        // overdue loan imported today is not charged every period since the first run in one night. A loan brought in
        // from a paper register (ADR-071 decision 6) is charged only for periods that start on or after its bring-in
        // date, the later of go-live and its approval, while its days overdue still count from the real due dates.
        LocalDate[] start = jdbc.sql(
                        "SELECT (created_at AT TIME ZONE ?)::date, brought_in_on FROM lending_loans WHERE id = ?")
                .params(currentTenant.profile().timezone().getId(), id)
                .query((rs, n) -> new LocalDate[] {rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)})
                .single();
        LocalDate penaltiesFrom = start[0].isAfter(from) ? start[0] : from;
        LocalDate broughtInOn = start[1];
        List<Item> items = repo.items(id);
        int charges = 0;
        long charged = 0;
        if (rule.charges()) {
            Map<UUID, Set<Integer>> periods = new HashMap<>();
            Map<UUID, Long> sums = new HashMap<>();
            // The cap counts what stands: amounts cancelled by a back-dated payment are left out of it.
            jdbc.sql("SELECT schedule_item_id, period_no, amount_minor - cancelled_minor FROM lending_loan_charges"
                            + " WHERE loan_id = ?")
                    .param(id)
                    .query((rs, n) -> {
                        UUID item = rs.getObject(1, UUID.class);
                        periods.computeIfAbsent(item, k -> new HashSet<>()).add(rs.getInt(2));
                        sums.merge(item, rs.getLong(3), Long::sum);
                        return null;
                    })
                    .list();
            for (Item item : items) {
                for (Penalties.Charge c : Penalties.due(
                        item,
                        rule,
                        today,
                        penaltiesFrom,
                        broughtInOn,
                        periods.getOrDefault(item.id, Set.of()),
                        sums.getOrDefault(item.id, 0L))) {
                    int inserted = jdbc.sql("""
                                    INSERT INTO lending_loan_charges (id, tenant_id, loan_id, schedule_item_id,
                                        charge_type, period_no, business_date, amount_minor)
                                    VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'penalty', ?, ?, ?)
                                    ON CONFLICT (tenant_id, schedule_item_id, period_no) WHERE charge_type = 'penalty'
                                    DO NOTHING
                                    """)
                            .params(UUID.randomUUID(), id, item.id, c.periodNo(), c.chargeDate(), c.amountMinor())
                            .update();
                    if (inserted == 1) {
                        jdbc.sql("UPDATE lending_schedule_items SET penalties_due_minor = penalties_due_minor + ?,"
                                        + " updated_at = now() WHERE id = ?")
                                .params(c.amountMinor(), item.id)
                                .update();
                        charges++;
                        charged = Math.addExact(charged, c.amountMinor());
                    }
                }
            }
            if (charges > 0) {
                items = repo.items(id);
            }
        }
        Servicing.Position position = servicer.refreshPosition(id, items, today);
        // The due reminders and arrears notices (issue #227) in the loan's own transaction, through the outbox with
        // the tenant's SMS settings, window and opt-outs; keyed by (item, notice), so a rerun queues nothing new.
        // A loan brought in from a paper register gets no arrears notice in its first days in the app (ADR-071 decision
        // 10): the borrower hears first from the business, and a register mistake can still be put right.
        boolean arrearsHeld = broughtInOn != null && today.isBefore(broughtInOn.plusDays(BRING_IN_ARREARS_HOLD_DAYS));
        int reminded = notices.remind(
                loan, items, position, today, currentTenant.profile().timezone(), arrearsHeld);
        return new Outcome(charges, charged, reminded);
    }

    /** Claims the run for {@code today}; returns the attempt number, or null when it is finished or leased. */
    private Integer claim(LocalDate today) {
        return jdbc.sql("""
                        INSERT INTO lending_arrears_runs (tenant_id, business_date, started_at, lease_until)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, clock_timestamp(),
                                clock_timestamp() + ? * interval '1 second')
                        ON CONFLICT (tenant_id, business_date) DO UPDATE
                           SET started_at = clock_timestamp(), lease_until = EXCLUDED.lease_until,
                               attempts = lending_arrears_runs.attempts + 1
                         WHERE lending_arrears_runs.finished_at IS NULL
                           AND lending_arrears_runs.lease_until < clock_timestamp()
                        RETURNING attempts
                        """)
                .params(today, LEASE.toSeconds())
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    private boolean renew(LocalDate today, int attempt) {
        return jdbc.sql("""
                        UPDATE lending_arrears_runs SET lease_until = clock_timestamp() + ? * interval '1 second'
                         WHERE business_date = ? AND attempts = ? AND finished_at IS NULL
                        """).params(LEASE.toSeconds(), today, attempt).update() == 1;
    }

    /**
     * Marks the date finished, with the run's audit row, unless another run took the lease over (a stale attempt is
     * refused). A loan that failed on the business's previous finished run too (its last two finished runs, so a night
     * with no run between them does not hide it) is alerted to the branch's managers and the administrators by push,
     * not only logged (the review of #270).
     */
    boolean finish(Result result, int attempt, List<UUID> failedIds) {
        int marked = jdbc.sql("""
                        UPDATE lending_arrears_runs SET finished_at = clock_timestamp(), loans = ?, charges = ?,
                            charged_minor = ?, failed_loans = ?, failed_loan_ids = ?::uuid[]
                         WHERE business_date = ? AND attempts = ? AND finished_at IS NULL
                        """)
                .params(
                        result.loans(),
                        result.charges(),
                        result.chargedMinor(),
                        result.failedLoans(),
                        failedIds.stream()
                                .map(UUID::toString)
                                .collect(java.util.stream.Collectors.joining(",", "{", "}")),
                        result.businessDate(),
                        attempt)
                .update();
        if (marked != 1) {
            return false;
        }
        if (!failedIds.isEmpty()) {
            jdbc.sql("""
                            SELECT l.id, l.loan_no, l.branch_id FROM lending_loans l
                             WHERE l.id = ANY (?::uuid[])
                               AND EXISTS (SELECT 1 FROM lending_arrears_runs r
                                            WHERE r.business_date = (SELECT max(p.business_date)
                                                                       FROM lending_arrears_runs p
                                                                      WHERE p.business_date < ?
                                                                        AND p.finished_at IS NOT NULL)
                                              AND l.id = ANY (r.failed_loan_ids))
                            """)
                    .params(
                            failedIds.stream()
                                    .map(UUID::toString)
                                    .collect(java.util.stream.Collectors.joining(",", "{", "}")),
                            result.businessDate())
                    .query((rs, n) -> {
                        events.publishEvent(new PushNotice(
                                FAILING_TWICE,
                                rs.getObject(1, UUID.class) + ":" + result.businessDate(),
                                new PushNotice.Holders("lending.collections.assign", rs.getObject(3, UUID.class), null),
                                Map.of(
                                        "loan_no",
                                        rs.getString(2),
                                        "path",
                                        "/staff/lending/loans/" + rs.getObject(1, UUID.class))));
                        return null;
                    })
                    .list();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("business_date", result.businessDate().toString());
        after.put("loans", result.loans());
        after.put("penalty_charges", result.charges());
        after.put("penalties_charged_minor", result.chargedMinor());
        after.put("failed_loans", result.failedLoans());
        after.put("promises_kept", result.promisesKept());
        after.put("promises_broken", result.promisesBroken());
        after.put("reminders_queued", result.remindersQueued());
        after.put("attempt", attempt);
        audit.record(
                AuditLog.Entry.created("lending.arrears.run", "lending.arrears", null, null, after), null, "system");
        return true;
    }
}
