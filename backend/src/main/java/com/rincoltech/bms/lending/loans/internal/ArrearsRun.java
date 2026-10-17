package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.PenaltyRule;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-ARR-01, the nightly arrears run for one tenant: for every active loan, the penalty periods due
 * by today (R-PEN, {@link Penalties}) become charges on their items, then item statuses, DPD and
 * arrears are recomputed (R-DPD). A charge is keyed by (item, period) in the database, so a rerun for
 * the same business date changes nothing. Charges are memorandum items: nothing is posted here.
 * Each loan's due reminders and arrears notices are queued after its recompute ({@link LoanNotices}, issue #227), and
 * last, every pending promise to pay is settled as kept or broken (FR-CLN-05).
 */
@Service
class ArrearsRun {

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
            LoanNotices notices) {
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
    }

    record Result(
            LocalDate businessDate,
            int loans,
            int charges,
            long chargedMinor,
            int promisesKept,
            int promisesBroken,
            int remindersQueued) {}

    /** Runs inside the tenant's job transaction (TenantJobs binds the tenant). */
    @Transactional(propagation = Propagation.MANDATORY)
    Result run() {
        LocalDate today = clock.today(currentTenant.profile().timezone());
        List<UUID> active = jdbc.sql("SELECT id FROM lending_loans WHERE status = 'active' ORDER BY id")
                .query(UUID.class)
                .list();
        int charges = 0;
        int reminders = 0;
        long charged = 0;
        for (UUID id : active) {
            Loan loan = loans.lock(id).orElseThrow();
            if (!loan.status().equals("active")) {
                continue; // moved since the list was read
            }
            PenaltyRule rule =
                    products.version(loan.productVersionId()).orElseThrow().penalty();
            List<Item> items = repo.items(id);
            if (rule.charges()) {
                Map<UUID, Set<Integer>> periods = new HashMap<>();
                Map<UUID, Long> sums = new HashMap<>();
                jdbc.sql("SELECT schedule_item_id, period_no, amount_minor FROM lending_loan_charges"
                                + " WHERE loan_id = ? AND charge_type = 'penalty'")
                        .param(id)
                        .query((rs, n) -> {
                            UUID item = rs.getObject(1, UUID.class);
                            periods.computeIfAbsent(item, k -> new HashSet<>()).add(rs.getInt(2));
                            sums.merge(item, rs.getLong(3), Long::sum);
                            return null;
                        })
                        .list();
                boolean added = false;
                for (Item item : items) {
                    for (Penalties.Charge c : Penalties.due(
                            item,
                            rule,
                            today,
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
                            added = true;
                        }
                    }
                }
                if (added) {
                    items = repo.items(id);
                }
            }
            Servicing.Position position = servicer.refreshPosition(id, items, today);
            reminders += notices.remind(loan, items, position, today);
        }
        CollectionsService.Settled promises = collections.settlePromises(today);
        Result result =
                new Result(today, active.size(), charges, charged, promises.kept(), promises.broken(), reminders);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("business_date", today.toString());
        after.put("loans", result.loans());
        after.put("penalty_charges", result.charges());
        after.put("penalties_charged_minor", result.chargedMinor());
        after.put("promises_kept", result.promisesKept());
        after.put("promises_broken", result.promisesBroken());
        after.put("reminders_queued", result.remindersQueued());
        audit.record(
                AuditLog.Entry.created("lending.arrears.run", "lending.arrears", null, null, after), null, "system");
        return result;
    }
}
